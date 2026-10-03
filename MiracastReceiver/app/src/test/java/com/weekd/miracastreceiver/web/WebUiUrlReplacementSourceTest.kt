package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiUrlReplacementSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/web/WebUiServer.kt").readText()

    @Test
    fun consecutiveUrlPlaybackReusesUrlPlayerWithoutStopRace() {
        assertTrue(source.contains("val currentSource = RuntimeState.playbackSnapshot().source.uppercase()"))
        assertTrue(source.contains("currentSource == \"WEB_URL\" || currentSource == \"GOOGLE_CAST\""))
        assertTrue(source.contains("if (!reusingUrlPlayer)"))
        assertTrue(source.contains("appContext.sendBroadcast(Intent(PlayerActivity.ACTION_STOP)"))
    }
}
