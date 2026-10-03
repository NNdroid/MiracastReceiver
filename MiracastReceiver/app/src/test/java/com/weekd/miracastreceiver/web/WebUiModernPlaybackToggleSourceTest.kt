package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackToggleSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun mainTransportButtonTracksRemotePlaybackState() {
        assertTrue(index.contains("id=\"playToggleBtn\""))
        assertTrue(index.contains("toggle.dataset.action=pause?'pause':'play'"))
        assertTrue(index.contains("send(toggle.dataset.action||'play')"))
        assertTrue(index.contains("pausing=new Set"))
    }
}
