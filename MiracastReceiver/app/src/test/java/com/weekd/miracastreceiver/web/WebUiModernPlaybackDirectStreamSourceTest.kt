package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackDirectStreamSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun directStreamControlsRemainAvailableInCollapsibleDrawer() {
        assertTrue(index.contains("<details id=\"directUrlCard\""))
        assertTrue(index.contains("id=\"directMediaUrl\""))
        assertTrue(index.contains("id=\"directUserAgent\""))
        assertTrue(index.contains("id=\"directReferer\""))
        assertTrue(index.contains("id=\"directAuthorization\""))
        assertTrue(index.contains("id=\"directPlayBtn\""))
    }
}
