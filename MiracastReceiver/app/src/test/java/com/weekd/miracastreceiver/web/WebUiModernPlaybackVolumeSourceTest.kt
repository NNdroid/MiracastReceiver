package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackVolumeSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun volumeUsesSliderWithDebouncedRemoteUpdates() {
        assertTrue(index.contains("id=\"volumeValue\" type=\"range\""))
        assertTrue(index.contains("volumeTimer=setTimeout"))
        assertTrue(index.contains("send('volume',{value:v})"))
        assertTrue(index.contains("id=\"muteBtn\""))
        assertTrue(index.contains("lastVolume"))
    }
}
