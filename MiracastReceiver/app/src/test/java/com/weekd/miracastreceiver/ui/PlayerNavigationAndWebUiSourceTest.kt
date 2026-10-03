package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerNavigationAndWebUiSourceTest {
    private val mainActivity = File("src/main/java/com/weekd/miracastreceiver/ui/MainActivity.kt").readText()
    private val mainLayout = File("src/main/res/layout/activity_main.xml").readText()
    private val webStyles = File("src/main/assets/webui/styles.css").readText()
    private val webScript = File("src/main/assets/webui/app.js").readText()

    @Test
    fun tvNavigationNoLongerExposesPlayerHub() {
        assertFalse(mainActivity.contains("Destination.PLAYER"))
        assertFalse(mainActivity.contains("PlayerHubFragment"))
        assertFalse(mainLayout.contains("@+id/nav_player"))
        assertTrue(mainActivity.contains("Destination.HOME"))
        assertTrue(mainActivity.contains("Destination.SETTINGS"))
        assertTrue(mainActivity.contains("Destination.ABOUT"))
    }

    @Test
    fun fullScreenPlaybackCapabilitiesRemainAvailable() {
        val playerActivity = File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
        assertTrue(playerActivity.contains("class PlayerActivity"))
        assertTrue(playerActivity.contains("startMiracastPlayback"))
        assertTrue(playerActivity.contains("startAirPlayMirrorPlayback"))
    }

    @Test
    fun webPlayerUsesMobileNowPlayingPresentationAndLiveRuntimeData() {
        assertTrue(webStyles.contains("Mobile-style Now Playing surface"))
        assertTrue(webStyles.contains("#page-playback"))
        assertTrue(webStyles.contains(".player-panel:before"))
        assertTrue(webStyles.contains(".play-main"))
        assertTrue(webStyles.contains("@media(max-width:520px)"))
        assertTrue(webScript.contains("const p=s.playback||{}"))
        assertTrue(webScript.contains("p.durationMs"))
        assertTrue(webScript.contains("p.positionMs"))
        assertTrue(webScript.contains("p.volume"))
        assertTrue(webScript.contains("p.speed"))
        assertTrue(webScript.contains("p.decoder"))
        assertTrue(webScript.contains("p.hardwareDecoder"))
    }
}
