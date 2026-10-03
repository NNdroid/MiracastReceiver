package com.weekd.miracastreceiver.airplay

import android.content.Context
import android.view.Surface
import com.weekd.miracastreceiver.airplay.handshake.AirPlayNtpClient
import com.weekd.miracastreceiver.airplay.handshake.AudioStreamServer
import com.weekd.miracastreceiver.airplay.handshake.BufferedAudioServer
import com.weekd.miracastreceiver.airplay.handshake.MirrorStreamServer
import com.weekd.miracastreceiver.util.Logger
import com.weekd.miracastreceiver.utils.PortUtils
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
 * The receiver supports legacy SDP/RTP AirPlay, AirPlay 2 mirroring, audio-only streams,
 * photos and URL playback. Network listeners are created through [PortUtils] so negotiated
 * AirPlay 2 TCP ports and legacy UDP audio follow the peer address family.
 */
class AirPlayReceiver(
    private val context: Context,
    private val displayName: String = "",
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val pinAuthEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (AirPlayState) -> Unit = {},
    private val onSenderNameChanged: (String) -> Unit = {},
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    private val onActualNameRegistered: (String) -> Unit = {},
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    private val onPinChanged: (pin: String?) -> Unit = {}
) {
    private val pairingStore = com.weekd.miracastreceiver.airplay.handshake.PairingStore(context)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var legacyVideoRenderer: LegacyAirPlayVideoRenderer? = null
    private var audioPlayer: AudioPlayer? = null

    @Volatile private var audioSocket: DatagramSocket? = null
    @Volatile private var legacyPeerAddress: java.net.InetAddress? = null

    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
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

    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
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

    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    private fun startTimingHandler() {
        timingHandler = TimingHandler()
        Logger.d("Timing handler ready; waiting for RTSP peer address before UDP bind")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
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
            onVideoPlay = { url, start -> startUrlVideo(url, start) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i(
            "Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                "audioOnly=${session.isAudioOnly}"
        )

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                npSenderName = session.senderName.ifBlank { npSenderName }
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                onSenderNameChanged(session.senderName)
                emitState(AirPlayState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(AirPlayState.ERROR)
            }
        }
    }

    private fun onStreamingStopped() {
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(AirPlayState.ADVERTISING)
        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    /**
     * Legacy SDP/RTP video.
     *
     * Do not query the Surface once and give up. RECORD normally arrives before the streaming
     * Activity has finished creating its Surface. Wire the NAL callback immediately; the renderer
     * buffers the newest IDR-based GOP and lazily attaches MediaCodec when the Surface appears.
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
        Logger.i(
            "AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})"
        )
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
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also {
            ntpClient = it
            it.start(scope)
        }
        onSenderNameChanged("AirPlay")
        emitState(AirPlayState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run {
            Logger.e("mirror stream start before keys set")
            return 0
        }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(
            aesKey,
            ecdhSecret,
            streamConnectionId,
            videoSurfaceProvider,
            mirrorWidth,
            mirrorHeight
        ).also {
            mirrorServer = it
            it.start(scope)
            videoPlaying = true
            emitNowPlaying()
        }.dataPort.also { Logger.i("Mirror data server started on port $it") }
    }

    private fun startMirrorAudio(
        sampleRate: Int,
        channels: Int,
        codecType: Int,
        framesPerPacket: Int
    ): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run {
            Logger.e("audio start before keys set")
            return 0 to 0
        }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(
            aesKey,
            ecdhSecret,
            aesIv,
            sampleRate,
            channels,
            codecType,
            framesPerPacket
        ).also {
            audioServer = it
            it.start(scope)
        }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    private fun startUrlVideo(url: String, startFraction: Double) {
        onSenderNameChanged("AirPlay")
        emitState(AirPlayState.CONNECTED)
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    private fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also {
            bufferedAudioServer = it
            it.start(scope)
        }
        audioPlaying = true
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

    private fun releaseMediaComponents() {
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
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (_: Exception) { }
        eventClientSocket = null
        try { eventSocket?.close() } catch (_: Exception) { }
        eventSocket = null

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
