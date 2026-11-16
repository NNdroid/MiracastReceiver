package top.jast.miracastreceiver.airplay

import top.jast.miracastreceiver.R

import android.content.Context
import android.view.Surface
import top.jast.miracastreceiver.BuildConfig
import top.jast.miracastreceiver.airplay.handshake.AirPlayNtpClient
import top.jast.miracastreceiver.airplay.handshake.AudioStreamServer
import top.jast.miracastreceiver.airplay.handshake.BufferedAudioServer
import top.jast.miracastreceiver.airplay.handshake.MirrorStreamServer
import top.jast.miracastreceiver.util.Logger
import top.jast.miracastreceiver.utils.PortUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * Top-level AirPlay receiver orchestrator.
 *
 * Supports legacy SDP/RTP AirPlay, AirPlay 2 mirroring, audio-only streams, photos and URL
 * playback. Legacy media listeners are bound through [PortUtils] so negotiated AirPlay 2 TCP
 * ports and legacy UDP audio follow the peer address family (IPv4/IPv6). UxPlay-based mirroring
 * is used when [BuildConfig.USE_UXPLAY] is enabled; otherwise the built-in RTSP pipeline is used.
 */
class AirPlayReceiver(
    private val context: Context,
    @Volatile private var displayName: String = "",
    mirrorWidth: Int = 1920,
    mirrorHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val pinAuthEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (AirPlayState) -> Unit = {},
    private val onSenderNameChanged: (String) -> Unit = {},
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    private val onActualNameRegistered: (String) -> Unit = {},
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    private val onPinChanged: (pin: String?) -> Unit = {},
    /** A real session end, independent of mDNS registration state. Dispatched on Main. */
    private val onSessionEnded: () -> Unit = {}
) {
    private val pairingStore = top.jast.miracastreceiver.airplay.handshake.PairingStore(context)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var legacyVideoRenderer: LegacyAirPlayVideoRenderer? = null
    private var audioPlayer: AudioPlayer? = null

    /** Advertised mirroring resolution; updated at runtime by [updateDisplayResolution]. */
    @Volatile private var mirrorResolution: Pair<Int, Int> = mirrorWidth to mirrorHeight

    @Volatile private var audioSocket: DatagramSocket? = null
    @Volatile private var legacyPeerAddress: java.net.InetAddress? = null

    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Optional UxPlay-based mirroring path.
    private var uxPlayReceiver: UxPlayReceiver? = null
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    /** Starts the AirPlay receiver (mDNS advertising + RTSP server, or UxPlay when enabled). */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        if (BuildConfig.USE_UXPLAY) {
            if (uxPlayReceiver != null) return
            uxPlayReceiver = UxPlayReceiver(
                context,
                displayName.ifBlank { context.getString(R.string.receiver_default_name) },
                videoSurfaceProvider,
                onConnected = { onSenderNameChanged("AirPlay UxPlay"); emitState(AirPlayState.CONNECTED) },
                onStopped = { scope.launch(Dispatchers.Main) { onSessionEnded() } },
                onAdvertising = { name -> onActualNameRegistered(name); emitState(AirPlayState.ADVERTISING) },
                onError = { emitState(AirPlayState.ERROR) },
                initialResolution = mirrorResolution,
            ).also { it.start() }
            return
        }
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(AirPlayState.ERROR)
            }
        }
    }

    /** Stops the receiver and releases all resources. Must be called when the service stops. */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            uxPlayReceiver?.stop()
            uxPlayReceiver = null
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /** Surface recreated by the player — reattach URL player / mirror decoder output. */
    fun onVideoSurfaceChanged() {
        uxPlayReceiver?.let { it.attachSurface(); return }
        urlVideoPlayer?.attachSurface()
        mirrorServer?.onSurfaceChanged()
    }

    /** Sends a DACP transport command from the TV remote back to the AirPlay sender. */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    fun disconnectSender() {
        uxPlayReceiver?.let { it.disconnectSender(); return }
        scope.launch { rtspHandler?.disconnectSender() }
    }

    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    /** Refresh discovery without tearing down an active stream. */
    @Synchronized
    fun updateDisplayName(name: String) {
        displayName = name
        uxPlayReceiver?.let {
            it.updateDisplayName(name.ifBlank { context.getString(R.string.receiver_default_name) })
            return
        }
        refreshDisplayName()
    }

    @Synchronized
    private fun refreshDisplayName() {
        try {
            mdnsService?.restart(displayName.ifBlank { null })
        } catch (e: Exception) {
            Logger.e("Failed to refresh AirPlay name", e)
        }
    }

    @Synchronized
    fun updateDisplayResolution(width: Int, height: Int) {
        require(width > 0 && height > 0)
        mirrorResolution = width to height
        uxPlayReceiver?.updateDisplayResolution(width, height)
        rtspHandler?.updateDisplayResolution(width, height)
        Logger.i("AirPlay display declaration updated: ${width}x$height; sender must re-read /info")
    }

    private fun startTimingHandler() {
        timingHandler = TimingHandler()
        Logger.d("Timing handler ready; waiting for RTSP peer address before UDP bind")
    }

    @Synchronized
    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    @Synchronized
    private fun startRtspHandler() {
        val (width, height) = mirrorResolution
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = width,
            displayHeight = height,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPeerAddressKnown = { peer ->
                legacyPeerAddress = peer
                timingHandler?.restartForPeer(scope, peer)
                Logger.i("AirPlay RTSP peer=${peer.hostAddress}; UDP listeners use ${if (peer is java.net.Inet6Address) "IPv6" else "IPv4"}")
            },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { v -> audioServer?.setVolume(v) },
            onNowPlayingMetadata = { title, artist, album ->
                npTitle = title
                npArtist = artist
                npAlbum = album
                emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onVideoPlay = { url, start, startSec, youtubeMlhls -> startUrlVideo(url, start, startSec, youtubeMlhls) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            isUrlVideoActive = { urlVideoPlayer != null },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")
        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                npSenderName = session.senderName.ifBlank { npSenderName }
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                // Notify the service of the sender name BEFORE emitting CONNECTED.
                onSenderNameChanged(session.senderName)
                emitState(AirPlayState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(AirPlayState.ERROR)
            }
        }
    }

    private fun onStreamingStopped() {
        if (urlVideoPlayer != null) {
            // 镜像中全屏视频切换为视频投送后，iPhone 会 TEARDOWN 镜像流（甚至断开控制连接），
            // 但视频由电视独立播放，不能跟着停。只释放镜像相关资源，视频结束时再完整收尾。
            Logger.i("Mirror session ended while URL video is playing — keeping the video")
            releaseMediaComponents(keepUrlVideo = true)
            return
        }
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(AirPlayState.ADVERTISING)
        scope.launch(Dispatchers.Main) { onSessionEnded() }
        scope.launch {
            try {
                refreshDisplayName()
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    /**
     * Legacy SDP/RTP video. Wire the NAL callback immediately; the renderer buffers the newest
     * IDR-based GOP and lazily attaches MediaCodec when the Surface appears (RECORD may arrive
     * before the streaming Activity has created its Surface).
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        legacyVideoRenderer?.release()
        val renderer = LegacyAirPlayVideoRenderer(
            surfaceProvider = videoSurfaceProvider,
            sps = sps,
            pps = pps,
            widthHint = DEFAULT_VIDEO_WIDTH,
            heightHint = DEFAULT_VIDEO_HEIGHT
        )
        legacyVideoRenderer = renderer
        rtspHandler?.onVideoNalUnit = renderer::onNalUnit
        Logger.i("Legacy AirPlay video pipeline armed; waiting for Surface/IDR as needed")
    }

    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv = session.aesIv.takeIf { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels = session.channels,
                codec = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")
        startAudioUdpReceiver()
    }

    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val peer = legacyPeerAddress
                val socket = PortUtils.bindDatagramSocketForPeer(AUDIO_RTP_PORT, peer)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT family=${if (peer is java.net.Inet6Address) "IPv6" else "IPv4"}")

                val buf = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    socket.receive(packet)
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                if (audioSocket != null) Logger.e("Audio UDP receiver error (unexpected)", e)
                else Logger.d("Audio socket closed (expected during shutdown)")
            }
        }
    }

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event channel
     * (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port and the NTP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        val event = PortUtils.bindEphemeralServerSocket()
        eventSocket = event
        // Accept + drain the event connection so the advertised port is connectable for macOS.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        emitState(AirPlayState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /** Mirror SETUP msg 2: start the data-stream server; @return its TCP port. */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        mirrorServer?.stop()
        val (width, height) = mirrorResolution
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, width, height)
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.start(scope) }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): hand the URL to [AirPlayVideoPlayer]. If handed off
     * from a running mirror, suspend the mirror's decoder first (Surface accepts one producer).
     */
    private fun startUrlVideo(url: String, startFraction: Double, startSeconds: Double?, youtubeMlhls: Boolean) {
        val mirror = mirrorServer
        if (mirror != null) {
            mirror.suspendDecoding()
        } else {
            onSenderNameChanged("AirPlay")
        }
        emitState(AirPlayState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            context = context,
            surfaceProvider = videoSurfaceProvider,
            onEnded = {
                if (rtspHandler?.markYouTubePlaybackStopped() == true) {
                    Logger.i("AirPlay YouTube video reached the end — releasing player")
                    stopUrlVideo()
                } else {
                    Logger.i("AirPlay URL video reached the end — paused at end, waiting for the sender")
                    rtspHandler?.sendVideoEvent("paused")
                }
            },
            onFailed = { reason ->
                Logger.w("AirPlay URL video failed: $reason")
                rtspHandler?.markYouTubePlaybackStopped()
                rtspHandler?.sendVideoEvent("stopped")
                stopUrlVideo()
            },
            onPlaying = { rtspHandler?.sendVideoEvent("playing") },
        ).also { urlVideoPlayer = it }
        rtspHandler?.sendVideoEvent("loading")
        player.play(url, startFraction, startSeconds, youtubeMlhls)
        Logger.i("AirPlay URL video started: $url (start=$startFraction, fromMirror=${mirror != null})")
    }

    /** Stops URL video playback; if handed off from a still-running mirror, resume mirroring. */
    private fun stopUrlVideo() {
        val player = urlVideoPlayer ?: return
        player.release()
        urlVideoPlayer = null
        val mirror = mirrorServer
        if (mirror == null && rtspHandler?.hasActiveSession == true) {
            Logger.i("AirPlay URL video stopped — mirror session still open, waiting for the sender to resume mirroring")
        } else if (mirror != null) {
            mirror.resumeDecoding()
            Logger.i("AirPlay URL video stopped — back to screen mirroring")
        } else {
            onStreamingStopped()
            Logger.i("AirPlay URL video stopped")
        }
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /**
     * Clears the video NAL callback, closes the audio socket, and releases media components.
     * [keepUrlVideo] preserves URL playback during a mirror→video handoff.
     */
    private fun releaseMediaComponents(keepUrlVideo: Boolean = false) {
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (_: Exception) { }
        audioSocket = null
        legacyPeerAddress = null

        legacyVideoRenderer?.release()
        legacyVideoRenderer = null

        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        if (!keepUrlVideo) {
            urlVideoPlayer?.release()
            urlVideoPlayer = null
        }
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (_: Exception) { }
        eventClientSocket = null
        try { eventSocket?.close() } catch (_: Exception) { }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only (per-stream stops go through
        // stopMirrorAudio/stopMirrorVideo and must leave the keys intact for dynamic re-add).
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        audioPlayer?.release()
        audioPlayer = null

        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes now-playing state: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    private fun clearNowPlayingMetadata() {
        npTitle = null
        npArtist = null
        npAlbum = null
        npArtwork = null
    }

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: AirPlayState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        private const val DEFAULT_VIDEO_WIDTH = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080
        internal const val AUDIO_RTP_PORT = 6001
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
