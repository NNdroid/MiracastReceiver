package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerMetadataSourceTest {
    private val playerSource = File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
    private val urlSource = File("src/main/java/com/weekd/miracastreceiver/ui/UrlPlaybackActivity.kt").readText()
    private val trackerSource = File("src/main/java/com/weekd/miracastreceiver/ui/StreamInfoTracker.kt").readText()
    private val layout = File("src/main/res/layout/activity_player.xml").readText()

    @Test
    fun sharedPlayerLayoutContainsCompactMetadataRow() {
        assertTrue(layout.contains("@+id/tv_playback_meta"))
        assertTrue(layout.contains("app:show_timeout=\"2000\""))
    }

    @Test
    fun dlnaPlayerRefreshesMetadataAndDetailedDiagnostics() {
        assertTrue(playerSource.contains("updateCompactPlaybackMeta()"))
        assertTrue(playerSource.contains("lastDecoderName"))
        assertTrue(playerSource.contains("droppedVideoFrames"))
        assertTrue(playerSource.contains("\"HDR\""))
        assertTrue(playerSource.contains("\"色彩空间\""))
        assertTrue(playerSource.contains("\"色深\""))
        assertTrue(playerSource.contains("\"音频编码\""))
        assertTrue(playerSource.contains("\"音频声道\""))
        assertTrue(playerSource.contains("\"采样率\""))
        assertTrue(playerSource.contains("\"缓冲时长\""))
        assertTrue(playerSource.contains("\"缓冲比例\""))
        assertTrue(playerSource.contains("\"掉帧\""))
        assertTrue(playerSource.contains("bandwidthEstimateBps"))
        assertTrue(playerSource.contains("onDroppedVideoFrames"))
    }

    @Test
    fun urlPlayerShowsTopBarAndAdvancedDiagnostics() {
        assertTrue(urlSource.contains("setControllerVisibilityListener"))
        assertTrue(urlSource.contains("updatePlaybackMeta()"))
        assertTrue(urlSource.contains("StreamInfoTracker.formatBitrate"))
        assertTrue(urlSource.contains("StreamInfoTracker.formatSpeed"))
        assertTrue(urlSource.contains("addNetworkInterceptor"))
        assertTrue(urlSource.contains("Protocol.HTTP_2"))
        assertTrue(urlSource.contains("lastHttpProtocol"))
        assertTrue(urlSource.contains("onDroppedVideoFrames"))
        assertTrue(urlSource.contains("KeyEvent.KEYCODE_INFO"))
        assertTrue(urlSource.contains("\"缓冲比例\""))
    }

    @Test
    fun trackerFormatsHdrColorAndAudioMetadata() {
        assertTrue(trackerSource.contains("formatHdr"))
        assertTrue(trackerSource.contains("VIDEO_DOLBY_VISION"))
        assertTrue(trackerSource.contains("COLOR_TRANSFER_ST2084"))
        assertTrue(trackerSource.contains("COLOR_TRANSFER_HLG"))
        assertTrue(trackerSource.contains("formatColorSpace"))
        assertTrue(trackerSource.contains("COLOR_SPACE_BT2020"))
        assertTrue(trackerSource.contains("formatBitDepth"))
        assertTrue(trackerSource.contains("formatAudioChannels"))
        assertTrue(trackerSource.contains("formatSampleRate"))
    }
}
