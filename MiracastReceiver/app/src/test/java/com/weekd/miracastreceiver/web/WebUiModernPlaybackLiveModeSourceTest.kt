package com.weekd.miracastreceiver.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackLiveModeSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun liveAndSeekabilityComeFromRuntimeFlags() {
        assertTrue(index.contains("!!p.isLive"))
        assertTrue(index.contains("!!p.isSeekable"))
        assertTrue(index.contains("canSeek=!!isSeekable&&d>0"))
        assertTrue(index.contains("tr('unknownDuration','—')"))
        assertTrue(index.contains("if(!p.isSeekable||d<=0)return"))
        assertFalse(index.contains("live.classList.toggle('hidden',!active.has"))
    }
}
