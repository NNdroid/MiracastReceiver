package com.weekd.miracastreceiver.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 阶段 2：WebRTC 接收与播放（单例：PeerConnectionFactory 原生资源昂贵，跨服务 reconfigure 复用）。
 *
 * - PeerConnection 创建与配置（硬件编解码 + EglBase 共享上下文，LAN 直连无需 STUN）
 * - 视频轨道接收：VideoTrack -> SurfaceViewRenderer（由 PlayerActivity 提供渲染面）
 * - 音频轨道接收：AudioTrack -> libwebrtc JavaAudioDeviceModule 直接输出到 Android AudioTrack
 *
 * 所有 PeerConnection 操作串行在单个控制线程上。
 */
object WebRtcReceiver {

    var onSignalingStarted: ((port: Int) -> Unit)? = null
    var onSessionRequested: ((clientInfo: String) -> Unit)? = null
    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: (() -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "webrtc-ctl").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionCounter = AtomicLong(0)

    private var signaling: WebRtcSignalingServer? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var currentSessionId: String? = null

    @Volatile private var appContext: Context? = null

    private val eglBase: EglBase by lazy { EglBase.create() }
    private val seenTrackIds = HashSet<String>()

    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var renderer: SurfaceViewRenderer? = null
    @Volatile private var statsSink: VideoSink? = null

    @Volatile var statsWidth: Int = 0
        private set
    @Volatile var statsHeight: Int = 0
        private set
    @Volatile var statsFrameCount: Long = 0
        private set

    val isSessionActive: Boolean get() = peerConnection != null
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    fun start(context: Context, deviceName: String, stateProvider: () -> String, preferredPort: Int) {
        appContext = context.applicationContext
        stop()
        val server = WebRtcSignalingServer(context.applicationContext, deviceName, stateProvider).apply {
            onOffer = { sdp -> handleOffer(sdp) }
            onRemoteCandidate = { mid, mline, candidate -> handleRemoteCandidate(mid, mline, candidate) }
            onClientConnected = { info ->
                Timber.i("WebRTC signaling client connected: $info")
            }
            onClientDisconnected = {
                Timber.i("WebRTC signaling client disconnected")
                if (!isSessionActive) onStreamStopped?.invoke()
            }
            onBye = {
                Timber.i("WebRTC sender sent bye; closing session")
                executor.execute { closePeerConnection("bye") }
            }
        }
        val port = server.start(preferredPort)
        signaling = server
        Timber.i("WebRTC receiver ready: signaling ws://<device>:$port/signaling")
        onSignalingStarted?.invoke(port)
    }

    fun stop() {
        executor.execute { closePeerConnection("stopped") }
        signaling?.stop()
    }

    fun release() {
        stop()
        signaling?.shutdown()
        signaling = null
        executor.execute {
            factory?.dispose()
            factory = null
            audioModule = null
            runCatching { eglBase.release() }
            Timber.i("WebRTC factory released")
        }
    }

    /** Called by PlayerActivity once its SurfaceViewRenderer is created and attached to the window. */
    fun attachRenderer(surfaceRenderer: SurfaceViewRenderer) {
        mainHandler.post {
            val oldSink = statsSink
            val oldTrack = videoTrack
            if (oldSink != null && oldTrack != null) {
                runCatching { oldTrack.removeSink(oldSink) }
            }
            statsSink = null
            renderer = surfaceRenderer
            videoTrack?.let { track ->
                val sink = statsForwardingSink(surfaceRenderer)
                statsSink = sink
                track.addSink(sink)
                Timber.i("WebRTC video track attached to renderer")
            }
        }
    }

    /** Called before the renderer is released by PlayerActivity. */
    fun detachRenderer() {
        mainHandler.post {
            val track = videoTrack
            val sink = statsSink
            if (track != null && sink != null) {
                runCatching { track.removeSink(sink) }
            }
            statsSink = null
            renderer = null
        }
    }

    private fun handleOffer(sdp: String) {
        executor.execute {
            try {
                ensureFactory()
                closePeerConnectionQuietly("new offer")

                val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                    // LAN casting relies on host candidates; mDNS-obfuscated Chrome candidates are
                    // resolved by libwebrtc's built-in resolver.
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                val connection = factory?.createPeerConnection(config, createPeerObserver())
                if (connection == null) {
                    Timber.e("WebRTC: createPeerConnection returned null")
                    signaling?.sendToClient(JSONObject().put("type", "error").put("message", "peer_connection_failed"))
                    return@execute
                }
                peerConnection = connection
                synchronized(seenTrackIds) { seenTrackIds.clear() }
                currentSessionId = "webrtc-${sessionCounter.incrementAndGet()}"
                onSessionRequested?.invoke(currentSessionId ?: "webrtc")

                connection.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription?) = Unit
                        override fun onSetSuccess() = createAndSendAnswer()
                        override fun onCreateFailure(error: String?) = Unit
                        override fun onSetFailure(error: String?) = reportFailure("setRemote(offer)", error)
                    },
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
            } catch (e: Exception) {
                Timber.e(e, "WebRTC: failed handling offer")
                signaling?.sendToClient(JSONObject().put("type", "error").put("message", "offer_failed"))
            }
        }
    }

    private fun createAndSendAnswer() {
        val connection = peerConnection ?: return
        connection.createAnswer(
            object : SdpObserver {
                override fun onSetSuccess() = Unit
                override fun onCreateSuccess(description: SessionDescription?) {
                    val answer = description ?: run {
                        reportFailure("createAnswer", "null description")
                        return
                    }
                    connection.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription?) = Unit
                            override fun onSetSuccess() {
                                val sent = signaling?.sendToClient(
                                    JSONObject().put("type", "answer").put("sdp", answer.description)
                                ) ?: false
                                Timber.i(
                                    if (sent) "WebRTC: answer sent to sender" else "WebRTC: no signaling client for answer"
                                )
                            }
                            override fun onSetFailure(error: String?) = reportFailure("setLocal(answer)", error)
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        answer
                    )
                }
                override fun onSetFailure(error: String?) = Unit
                override fun onCreateFailure(error: String?) = reportFailure("createAnswer", error)
            },
            MediaConstraints()
        )
    }

    private fun reportFailure(operation: String, error: String?) {
        Timber.e("WebRTC: $operation failed: $error")
        signaling?.sendToClient(
            JSONObject().put("type", "error").put("message", "${operation}_failed")
        )
    }

    private fun handleRemoteCandidate(sdpMid: String?, mLineIndex: Int, sdp: String) {
        executor.execute {
            try {
                peerConnection?.addIceCandidate(IceCandidate(sdpMid, mLineIndex, sdp))
            } catch (e: Exception) {
                Timber.w(e, "WebRTC: addIceCandidate failed")
            }
        }
    }

    private fun ensureFactory() {
        if (factory != null) return
        val app = appContext ?: return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val module = JavaAudioDeviceModule.builder(app)
            .createAudioDeviceModule()
        audioModule = module
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
        Timber.i("WebRTC PeerConnectionFactory created (hardware codecs enabled)")
    }

    private fun closePeerConnection(reason: String) {
        if (peerConnection == null && videoTrack == null && audioTrack == null) {
            currentSessionId = null
            return
        }
        closePeerConnectionQuietly(reason)
        currentSessionId = null
    }

    private fun closePeerConnectionQuietly(reason: String) {
        val hadSession = peerConnection != null
        val hadTracks = videoTrack != null || audioTrack != null
        try {
            val closingSink = statsSink
            val closingTrack = videoTrack
            statsSink = null
            if (closingSink != null && closingTrack != null) {
                mainHandler.post { runCatching { closingTrack.removeSink(closingSink) } }
            }
            videoTrack = null
            audioTrack = null
            statsWidth = 0
            statsHeight = 0
            peerConnection?.let { connection ->
                runCatching { connection.close() }
                runCatching { connection.dispose() }
            }
            peerConnection = null
            if (hadSession || hadTracks) {
                Timber.i("WebRTC session closed ($reason)")
                onStreamStopped?.invoke()
            }
        } catch (e: Exception) {
            Timber.w(e, "WebRTC: error while closing session")
        }
    }

    private fun statsForwardingSink(target: SurfaceViewRenderer): VideoSink = VideoSink { frame ->
        updateStreamStats(frame)
        runCatching { target.onFrame(frame) }
    }

    private fun updateStreamStats(frame: VideoFrame) {
        statsFrameCount++
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        if (width > 0 && height > 0 && (width != statsWidth || height != statsHeight)) {
            statsWidth = width
            statsHeight = height
            Timber.i("WebRTC video resolution: ${width}x$height")
        }
    }

    private fun createPeerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Timber.d("WebRTC signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Timber.i("WebRTC ICE state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    val sessionId = currentSessionId ?: "webrtc"
                    onSessionEstablished?.invoke(sessionId)
                    onStreamStarted?.invoke()
                    signaling?.sendToClient(
                        JSONObject().put("type", "state").put("state", "streaming")
                    )
                }
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED -> executor.execute { closePeerConnection("ice-$state") }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Timber.d("WebRTC ICE gathering: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val json = JSONObject()
                .put("type", "candidate")
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp)
            val sent = signaling?.sendToClient(json) ?: false
            if (!sent) Timber.d("WebRTC: local candidate dropped (no signaling client)")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit

        override fun onRenegotiationNeeded() {
            Timber.d("WebRTC renegotiation requested (ignored; answer-only receiver)")
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            handleIncomingTrack(transceiver.receiver)
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            // Unified plan fires onTrack; legacy senders may only fire onAddTrack. Deduplicated by
            // track id so both paths can coexist.
            handleIncomingTrack(receiver)
        }

        fun handleIncomingTrack(receiver: RtpReceiver?) {
            val track = receiver?.track() ?: return
            synchronized(seenTrackIds) {
                if (!seenTrackIds.add(track.id())) return
            }
            when (track.kind()) {
                MediaStreamTrack.VIDEO_TRACK_KIND -> {
                    val video = track as? VideoTrack ?: return
                    videoTrack = video
                    Timber.i("WebRTC video track received: ${track.id()}")
                    mainHandler.post {
                        val surfaceRenderer = renderer
                        if (surfaceRenderer != null) {
                            val sink = statsForwardingSink(surfaceRenderer)
                            statsSink = sink
                            video.addSink(sink)
                        }
                    }
                }
                MediaStreamTrack.AUDIO_TRACK_KIND -> {
                    val audio = track as? AudioTrack ?: return
                    audioTrack = audio
                    // AudioTrack playback: libwebrtc's JavaAudioDeviceModule renders the remote
                    // track into an Android AudioTrack once enabled.
                    audio.setEnabled(true)
                    Timber.i("WebRTC audio track received and enabled: ${track.id()}")
                }
            }
        }
    }
}
