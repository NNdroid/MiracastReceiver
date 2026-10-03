package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiracastRuntimeHardeningSourceTest {
    private val rtp = File("src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt").readText()
    private val renderer = File("src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt").readText()

    @Test
    fun rtpUsesBoundedReorderWindowInsteadOfImmediateLoss() {
        assertTrue(rtp.contains("REORDER_WINDOW_PACKETS"))
        assertTrue(rtp.contains("REORDER_MAX_WAIT_MS"))
        assertTrue(rtp.contains("pending.putIfAbsent"))
        assertTrue(rtp.contains("skipConfirmedGap"))
        assertTrue(rtp.contains("packetsReordered"))
    }

    @Test
    fun rtpPaddingIsRemovedBeforeTsDemux() {
        assertTrue(rtp.contains("rtpPayloadRange"))
        assertTrue(rtp.contains("end -= padding"))
        assertTrue(rtp.contains("copyOfRange(range.start, range.endExclusive)"))
    }

    @Test
    fun lateSurfaceReplaysBufferedGopFromIdr() {
        assertTrue(renderer.contains("MAX_RECOVERY_UNITS"))
        assertTrue(renderer.contains("recoveryHasIdr"))
        assertTrue(renderer.contains("bufferForSurfaceRecovery"))
        assertTrue(renderer.contains("replayed $count buffered access units"))
        assertTrue(renderer.contains("containsIdr"))
    }
}
