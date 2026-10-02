package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerMetadataSourceTest {
    private val playerSource = File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
    private val urlSource = File("src/main/java/com/weekd/miracastreceiver/ui/UrlPlaybackActivity.kt").readText()
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
        assertTrue(playerSource.contains("\"音频编码\""))
        assertTrue(playerSource.contains("\"缓冲\""))
        assertTrue(playerSource.contains("bandwidthEstimateBps"))
    }

    @Test
    fun urlPlayerShowsTopBarWithTechnicalMetadata() {
        assertTrue(urlSource.contains("setControllerVisibilityListener"))
        assertTrue(urlSource.contains("updatePlaybackMeta()"))
        assertTrue(urlSource.contains("StreamInfoTracker.formatBitrate"))
        assertTrue(urlSource.contains("StreamInfoTracker.formatSpeed"))
    }
}
