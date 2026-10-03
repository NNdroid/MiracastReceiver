package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackAccessibilitySourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun remotePlayerHasAccessibleInteractiveControls() {
        assertTrue(index.contains("aria-label=\"Playback position\""))
        assertTrue(index.contains("aria-label=\"Volume\""))
        assertTrue(index.contains("aria-haspopup=\"menu\""))
        assertTrue(index.contains("aria-expanded=\"false\""))
        assertTrue(index.contains("role=\"menu\""))
        assertTrue(index.contains("role=\"menuitem\""))
    }
}
