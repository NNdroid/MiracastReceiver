package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackCapabilityBridgeSourceTest {
    private val service = File("src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt").readText()

    @Test
    fun dlnaPlayerCapabilitiesAreConsumedByReceiverService() {
        assertTrue(service.contains("intent.hasExtra(\"is_live\")"))
        assertTrue(service.contains("intent.hasExtra(\"is_seekable\")"))
        assertTrue(service.contains("isLive = isLive"))
        assertTrue(service.contains("isSeekable = isSeekable"))
        assertTrue(service.contains("volume = volume"))
        assertTrue(service.contains("speed = speed"))
    }

    @Test
    fun mirrorSessionsAreExplicitlyLiveAndNotSeekable() {
        assertTrue(service.contains("source = \"AirPlay\""))
        assertTrue(service.contains("source = \"Miracast\""))
        assertTrue(service.contains("isLive = true"))
        assertTrue(service.contains("isSeekable = false"))
    }
}
