package com.weekd.miracastreceiver.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun playbackUsesRemotePlayerControlsInsteadOfNumberFields() {
        assertTrue(index.contains("id=\"seekRange\" type=\"range\""))
        assertTrue(index.contains("id=\"volumeValue\" type=\"range\""))
        assertTrue(index.contains("id=\"playToggleBtn\""))
        assertTrue(index.contains("id=\"back10Btn\""))
        assertTrue(index.contains("id=\"forward10Btn\""))
        assertTrue(index.contains("id=\"speedMenu\""))
        assertTrue(index.contains("data-speed=\"0.5\""))
        assertTrue(index.contains("data-speed=\"2\""))
    }

    @Test
    fun remoteControlsReuseExistingPlayerApi() {
        assertTrue(index.contains("send('seek',{positionMs:"))
        assertTrue(index.contains("send('volume',{value:"))
        assertTrue(index.contains("send('speed',{value:"))
        assertTrue(index.contains("playerAction(a,x)"))
    }

    @Test
    fun livePlaybackAndDirectUrlRemainSupported() {
        assertTrue(index.contains("id=\"liveBadge\""))
        assertTrue(index.contains("id=\"directUrlCard\""))
        assertTrue(index.contains("id=\"directPlayBtn\""))
        assertTrue(index.contains("id=\"activeDecoder\""))
        assertTrue(index.contains("id=\"activeHardware\""))
    }

    @Test
    fun playbackAssetsStayOnExistingWebUiRoutes() {
        assertFalse(index.contains("/playback.css"))
        assertFalse(index.contains("/playback-modern.js"))
        assertTrue(index.contains("<script src=\"/app.js\"></script>"))
        assertTrue(index.contains("<link rel=\"stylesheet\" href=\"/styles.css\">"))
    }
}
