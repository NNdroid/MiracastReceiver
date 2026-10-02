package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerStartupRound3SourceTest {

    private val source: String by lazy {
        File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
    }

    @Test
    fun playlistMediaItemsAreCached() {
        assertTrue(source.contains("cachedVideoMediaItems"))
        assertTrue(source.contains("rebuildMediaItemCacheIfNeeded"))
        assertTrue(source.contains("cachedVideoIndexByPlaylistIndex"))
    }

    @Test
    fun startupMeasuresReadyAndFirstFrameSeparately() {
        assertTrue(source.contains("DLNA/media playback READY in"))
        assertTrue(source.contains("override fun onRenderedFirstFrame()"))
        assertTrue(source.contains("DLNA/media first frame in"))
        assertTrue(source.contains("ready-to-frame="))
    }

    @Test
    fun decoderInitializationIsMeasured() {
        assertTrue(source.contains("onVideoDecoderInitialized"))
        assertTrue(source.contains("decoder-init="))
    }

    @Test
    fun playerViewIsNotReattachedForSamePlayer() {
        assertTrue(source.contains("if (playerView.player !== player) playerView.player = player"))
    }
}
