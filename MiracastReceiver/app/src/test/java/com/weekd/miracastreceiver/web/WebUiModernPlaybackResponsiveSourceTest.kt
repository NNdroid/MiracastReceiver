package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackResponsiveSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun remotePlayerHasDesktopAndMobileLayouts() {
        assertTrue(index.contains("@media(max-width:860px)"))
        assertTrue(index.contains("@media(max-width:520px)"))
        assertTrue(index.contains(".player-control-strip"))
        assertTrue(index.contains(".player-volume"))
        assertTrue(index.contains(".player-speed"))
    }
}
