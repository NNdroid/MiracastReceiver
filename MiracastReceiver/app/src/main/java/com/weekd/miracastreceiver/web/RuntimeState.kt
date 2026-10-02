package com.weekd.miracastreceiver.web

import com.weekd.miracastreceiver.airplay.VideoDecoder

/** Thread-safe-enough volatile runtime snapshot used by both TV UI and WebUI. */
object RuntimeState {

    @Volatile var serviceRunning: Boolean = false
    @Volatile var serviceStartedAtMs: Long = 0L
    @Volatile var networkIp: String = ""
    @Volatile var lastError: String = ""

    @Volatile var webUiPreferredPort: Int = 0
    @Volatile var webUiActivePort: Int = 0
    @Volatile var webUiPortFallback: Boolean = false
    @Volatile var webUiStartedAtMs: Long = 0L

    @Volatile var airPlayState: String = "IDLE"
    @Volatile var airPlaySender: String = ""

    @Volatile var miracastState: String = "IDLE"
    @Volatile var miracastClient: String = ""
    @Volatile var miracastRtpPort: Int = 0

    @Volatile var playbackState: String = "IDLE"
    @Volatile var playbackTitle: String = ""
    @Volatile var playbackUri: String = ""
    @Volatile var playbackPositionMs: Long = 0L
    @Volatile var playbackDurationMs: Long = 0L
    @Volatile var playbackSpeed: Float = 1f
    @Volatile var playbackVolume: Int = 100
    @Volatile var playbackSource: String = ""

    fun resetWebUi() {
        webUiActivePort = 0
        webUiPortFallback = false
        webUiStartedAtMs = 0L
    }

    fun resetPlayback() {
        playbackState = "IDLE"
        playbackTitle = ""
        playbackUri = ""
        playbackPositionMs = 0L
        playbackDurationMs = 0L
        playbackSpeed = 1f
        playbackSource = ""
    }

    fun decoderName(): String = VideoDecoder.lastDecoderName
    fun decoderHardwareAccelerated(): Boolean = VideoDecoder.lastDecoderHardwareAccelerated
}
