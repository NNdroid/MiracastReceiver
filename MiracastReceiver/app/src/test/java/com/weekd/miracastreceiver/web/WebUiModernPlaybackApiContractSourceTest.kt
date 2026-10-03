package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackApiContractSourceTest {
    private val appJs = File("src/main/assets/webui/app.js").readText()
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun modernControlsKeepUsingExistingPlayerEndpoint() {
        assertTrue(appJs.contains("api('/api/actions/player'"))
        assertTrue(index.contains("send('seek',{positionMs:"))
        assertTrue(index.contains("send('volume',{value:"))
        assertTrue(index.contains("send('speed',{value:"))
        assertTrue(index.contains("data-player=\"stop\""))
    }
}
