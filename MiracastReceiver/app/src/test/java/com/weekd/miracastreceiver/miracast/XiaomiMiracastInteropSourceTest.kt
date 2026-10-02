package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XiaomiMiracastInteropSourceTest {
    private val wifiDirect = File("src/main/java/com/weekd/miracastreceiver/miracast/WifiDirectManager.kt").readText()
    private val server = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt").readText()
    private val session = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt").readText()

    @Test
    fun sinkDoesNotForceAutonomousGroupOwnerAtStartup() {
        assertFalse(wifiDirect.contains("p2p.createGroup("))
        assertTrue(wifiDirect.contains("prepareForSourceNegotiation()"))
    }

    @Test
    fun sourceControlPortAndGroupOwnerAddressAreUsedAsHints() {
        assertTrue(wifiDirect.contains("getControlPort"))
        assertTrue(wifiDirect.contains("source-group-owner"))
        assertTrue(server.contains("WfdSourceHint.snapshot()"))
        assertTrue(server.contains("hint.controlPort"))
        assertTrue(server.contains("hint.ipAddress"))
    }

    @Test
    fun mandatoryWfdBaselineCapabilitiesAreAdvertised() {
        assertTrue(session.contains("LPCM 00000002 00"))
        assertTrue(session.contains("AAC 00000001 00"))
        assertTrue(session.contains("000001C1"))
        assertTrue(session.contains("00 00 03 10"))
    }

    @Test
    fun setupAndPlayResponsesAreMatchedByCseq() {
        assertTrue(session.contains("setupCseq"))
        assertTrue(session.contains("playCseq"))
        assertTrue(session.contains("when (cseq)"))
    }
}
