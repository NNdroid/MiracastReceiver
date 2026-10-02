package com.weekd.miracastreceiver.web

import com.weekd.miracastreceiver.airplay.VideoDecoder
import java.util.concurrent.atomic.AtomicReference

/** Runtime snapshot shared by the Android TV UI and WebUI. */
object RuntimeState {

    data class PlaybackSnapshot(
        val state: String = "IDLE",
        val title: String = "",
        val uri: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val speed: Float = 1f,
        val volume: Int = 100,
        val source: String = "",
        val error: String = "",
        val retryAttempt: Int = 0
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

    private val playbackRef = AtomicReference(PlaybackSnapshot())

    fun playbackSnapshot(): PlaybackSnapshot = playbackRef.get()

    fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        while (true) {
            val current = playbackRef.get()
            val next = transform(current)
            if (playbackRef.compareAndSet(current, next)) return
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
        playbackRef.set(PlaybackSnapshot())
    }

    fun decoderName(): String = VideoDecoder.lastDecoderName
    fun decoderHardwareAccelerated(): Boolean = VideoDecoder.lastDecoderHardwareAccelerated
}
