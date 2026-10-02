package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UrlPlaybackStartupSourceTest {

    private val source: String by lazy {
        File("src/main/java/com/weekd/miracastreceiver/ui/UrlPlaybackActivity.kt").readText()
    }

    @Test
    fun urlSwitchDoesNotTearDownPlayer() {
        val startMedia = source
            .substringAfter("private fun startMedia()")
            .substringBefore("private fun ensurePlayer()")

        assertFalse("URL switching must not stop ExoPlayer", startMedia.contains("exo.stop()"))
        assertFalse("URL switching must not clear the timeline before replacement", startMedia.contains("clearMediaItems"))
        assertTrue("URL switching should replace the MediaItem in-place", startMedia.contains("exo.setMediaItem"))
    }

    @Test
    fun adaptivePlaybackUsesPooledOkHttpAndLowLatencyLivePolicy() {
        assertTrue(source.contains("OkHttpDataSource.Factory(httpClient)"))
        assertTrue(source.contains("ConnectionPool(8, 5, TimeUnit.MINUTES)"))
        assertTrue(source.contains("LIVE_TARGET_OFFSET_MS = 1_500L"))
        assertTrue(source.contains("setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)"))
        assertTrue(source.contains("setLiveConfiguration("))
    }

    @Test
    fun duplicateUrlRequestKeepsExistingBuffer() {
        assertTrue(source.contains("activeUrl == currentUrl"))
        assertTrue(source.contains("keeping buffer/decoder"))
    }
}
