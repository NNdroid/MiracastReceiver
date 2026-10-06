package com.weekd.miracastreceiver.web

import com.weekd.miracastreceiver.airplay.VideoDecoder
import com.weekd.miracastreceiver.cast.GoogleCastReceiver
import java.util.concurrent.atomic.AtomicReference

/** Runtime snapshot shared by the Android TV UI and WebUI. */
object RuntimeState {

    data class PlaybackSnapshot(
        val state: String = "IDLE",
        val title: String = "",
        val uri: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isLive: Boolean = false,
        val isSeekable: Boolean = false,
        val speed: Float = 1f,
        val volume: Int = 100,
        val source: String = "",
        val error: String = "",
        val retryAttempt: Int = 0,
        val decoderName: String = "",
        val hardwareDecoder: Boolean = false
    )

    @Volatile var serviceRunning: Boolean = false
    @Volatile var serviceStartedAtMs: Long = 0L
    @Volatile var networkIp: String = ""
    @Volatile var lastError: String = ""
    @Volatile var webUiPreferredPort: Int = 0
    @Volatile var webUiPort: Int = 0

    @Volatile var airPlayState: String = "IDLE"
    @Volatile var airPlaySender: String = ""

    @Volatile var miracastState: String = "IDLE"
    @Volatile var miracastClient: String = ""
    @Volatile var miracastRtpPort: Int = 0
    /** Advertisement pipeline token reported by WifiDirectManager; see /api/diagnostics. */
    @Volatile var miracastAdvertisement: String = "NOT_ATTEMPTED"

    @Volatile var webrtcState: String = "IDLE"
    @Volatile var webrtcClient: String = ""
    @Volatile var webrtcSignalingPort: Int = 0

    private val playbackRef = AtomicReference(PlaybackSnapshot())

    fun playbackSnapshot(): PlaybackSnapshot = playbackRef.get()

    fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        while (true) {
            val current = playbackRef.get()
            val next = transform(current)
            if (playbackRef.compareAndSet(current, next)) {
                GoogleCastReceiver.syncPlayback(next)
                return
            }
        }
    }

    var playbackState: String
        get() = playbackRef.get().state
        set(value) = updatePlayback { it.copy(state = value) }
    var playbackTitle: String
        get() = playbackRef.get().title
        set(value) = updatePlayback { it.copy(title = value) }
    var playbackUri: String
        get() = playbackRef.get().uri
        set(value) = updatePlayback { it.copy(uri = value) }
    var playbackPositionMs: Long
        get() = playbackRef.get().positionMs
        set(value) = updatePlayback { it.copy(positionMs = value) }
    var playbackDurationMs: Long
        get() = playbackRef.get().durationMs
        set(value) = updatePlayback { it.copy(durationMs = value) }
    var playbackIsLive: Boolean
        get() = playbackRef.get().isLive
        set(value) = updatePlayback { it.copy(isLive = value) }
    var playbackIsSeekable: Boolean
        get() = playbackRef.get().isSeekable
        set(value) = updatePlayback { it.copy(isSeekable = value) }
    var playbackSpeed: Float
        get() = playbackRef.get().speed
        set(value) = updatePlayback { it.copy(speed = value) }
    var playbackVolume: Int
        get() = playbackRef.get().volume
        set(value) = updatePlayback { it.copy(volume = value) }
    var playbackSource: String
        get() = playbackRef.get().source
        set(value) = updatePlayback { it.copy(source = value) }
    var playbackError: String
        get() = playbackRef.get().error
        set(value) = updatePlayback { it.copy(error = value) }
    var playbackRetryAttempt: Int
        get() = playbackRef.get().retryAttempt
        set(value) = updatePlayback { it.copy(retryAttempt = value) }

    fun resetPlayback() {
        val reset = PlaybackSnapshot()
        playbackRef.set(reset)
        GoogleCastReceiver.syncPlayback(reset)
    }

    fun decoderName(): String {
        val playback = playbackRef.get()
        if (playback.decoderName.isNotBlank()) return playback.decoderName
        return if (playback.source.equals("AirPlay", ignoreCase = true) ||
            playback.source.equals("Miracast", ignoreCase = true)
        ) {
            VideoDecoder.lastDecoderName
        } else {
            ""
        }
    }

    fun decoderHardwareAccelerated(): Boolean {
        val playback = playbackRef.get()
        if (playback.decoderName.isNotBlank()) return playback.hardwareDecoder
        val mirrorSource = playback.source.equals("AirPlay", ignoreCase = true) ||
            playback.source.equals("Miracast", ignoreCase = true)
        return mirrorSource && VideoDecoder.lastDecoderName.isNotBlank() &&
            VideoDecoder.lastDecoderHardwareAccelerated
    }
}
