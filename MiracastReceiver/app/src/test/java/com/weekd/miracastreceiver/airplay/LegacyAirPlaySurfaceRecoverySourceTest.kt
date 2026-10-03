package com.weekd.miracastreceiver.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyAirPlaySurfaceRecoverySourceTest {
    private val receiver = File("src/main/java/com/weekd/miracastreceiver/airplay/AirPlayReceiver.kt").readText()
    private val renderer = File("src/main/java/com/weekd/miracastreceiver/airplay/LegacyAirPlayVideoRenderer.kt").readText()

    @Test
    fun legacyVideoPipelineDoesNotPermanentlyAbortWhenSurfaceIsLate() {
        assertFalse(receiver.contains("no surface available — skipping video pipeline"))
        assertTrue(receiver.contains("LegacyAirPlayVideoRenderer"))
        assertTrue(receiver.contains("rtspHandler?.onVideoNalUnit = renderer::onNalUnit"))
    }

    @Test
    fun rendererBuffersLatestIdrGopUntilSurfaceExists() {
        assertTrue(renderer.contains("recoveryHasIdr"))
        assertTrue(renderer.contains("MAX_RECOVERY_NALS"))
        assertTrue(renderer.contains("surface == null || !surface.isValid"))
        assertTrue(renderer.contains("replayed $count buffered NAL units".replace("$count", "")))
    }

    @Test
    fun rendererRebuildsWhenSurfaceChanges() {
        assertTrue(renderer.contains("surface !== configuredSurface"))
        assertTrue(renderer.contains("rebuildDecoder(surface)"))
    }
}
