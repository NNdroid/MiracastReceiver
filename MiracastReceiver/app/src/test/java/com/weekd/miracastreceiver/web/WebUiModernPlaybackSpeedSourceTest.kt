package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackSpeedSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun speedMenuUsesPresetRemoteActions() {
        assertTrue(index.contains("id=\"speedMenuBtn\""))
        assertTrue(index.contains("data-speed=\"0.75\""))
        assertTrue(index.contains("data-speed=\"1.25\""))
        assertTrue(index.contains("speedBusy=Date.now()+900"))
        assertTrue(index.contains("send('speed',{value:v})"))
    }
}
