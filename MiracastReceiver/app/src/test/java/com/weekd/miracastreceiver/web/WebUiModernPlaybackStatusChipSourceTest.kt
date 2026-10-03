package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackStatusChipSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun decoderStateIsPresentedAsCompactPlayerStatus() {
        assertTrue(index.contains("class=\"player-status-chips\""))
        assertTrue(index.contains("id=\"activeDecoder\""))
        assertTrue(index.contains("id=\"activeHardware\""))
        assertTrue(index.contains("data-i18n=\"currentDecoder\""))
        assertTrue(index.contains("data-i18n=\"hardwareAcceleration\""))
    }
}
