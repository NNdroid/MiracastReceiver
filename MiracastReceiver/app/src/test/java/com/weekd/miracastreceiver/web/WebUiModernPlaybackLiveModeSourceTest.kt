package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackLiveModeSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun liveStreamsDisableSeekingAndExposeLiveState() {
        assertTrue(index.contains("seek.disabled=true"))
        assertTrue(index.contains("back.disabled=true"))
        assertTrue(index.contains("forward.disabled=true"))
        assertTrue(index.contains("tr('live','LIVE')"))
        assertTrue(index.contains("live.classList.toggle('hidden'"))
    }
}
