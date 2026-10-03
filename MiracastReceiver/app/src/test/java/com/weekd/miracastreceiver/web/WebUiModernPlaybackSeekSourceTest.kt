package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackSeekSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun seekSliderSupportsDraggingAndTenSecondJumps() {
        assertTrue(index.contains("id=\"seekRange\" type=\"range\""))
        assertTrue(index.contains("jump(-10000)"))
        assertTrue(index.contains("jump(10000)"))
        assertTrue(index.contains("seekBusy=Date.now()+900"))
        assertTrue(index.contains("send('seek',{positionMs:target})"))
    }
}
