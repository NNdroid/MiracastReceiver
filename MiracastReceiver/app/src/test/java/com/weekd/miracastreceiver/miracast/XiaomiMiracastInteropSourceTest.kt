package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XiaomiMiracastInteropSourceTest {
    private val wifiDirect = File("src/main/java/com/weekd/miracastreceiver/miracast/WifiDirectManager.kt").readText()
    private val server = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt").readText()
    private val session = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt").readText()
    private val rootHelper = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdRootHelper.kt").readText()

    @Test
    fun sinkSupportsStandardAndroidGroupOwnerTopologyAndVendorFallback() {
        assertTrue(wifiDirect.contains("prepareCompatibleTopology()"))
        assertTrue(wifiDirect.contains("topology=dual-role-sink-go-compatible"))
        assertTrue(wifiDirect.contains("Sink is GO (standard Android Source-compatible)"))
        assertTrue(wifiDirect.contains("Source is GO"))
        assertTrue(wifiDirect.contains("Preserving P2P group"))
        assertFalse(wifiDirect.contains("Removing stale empty autonomous GO"))
        assertFalse(wifiDirect.contains("source-go-preferred"))
    }

    @Test
    fun frameworkMiracastSinkModeIsBestEffort() {
        assertTrue(wifiDirect.contains("setMiracastMode(2, \"SINK\")"))
        assertTrue(wifiDirect.contains("setMiracastMode(0, \"DISABLED\")"))
        assertTrue(wifiDirect.contains("supplicant WFD mode remains authoritative"))
    }

    @Test
    fun sourceControlPortAndBothTopologiesRemainSupported() {
        assertTrue(wifiDirect.contains("getControlPort"))
        assertTrue(wifiDirect.contains("source-group-owner"))
        assertTrue(server.contains("WfdSourceHint.snapshot()"))
        assertTrue(server.contains("hint.controlPort"))
        assertTrue(server.contains("hint.ipAddress"))
        assertTrue(server.contains("p2pSubnetPrefix()"))
        assertTrue(server.contains("discoverSourceControlPort"))
    }

    @Test
    fun sinkAdvertisesPrimarySinkAnd7236() {
        assertTrue(rootHelper.contains("controlPort: Int = 7236"))
        assertTrue(rootHelper.contains("P2P_PEER FIRST"))
        assertTrue(rootHelper.contains("parsePeerControlPort"))
        assertTrue(rootHelper.contains("fun advertiseSink(context: Context, controlPort: Int = 7236)"))
    }

    @Test
    fun broadR1CapabilitiesRemainAvailableWithoutDuplicatePlayHack() {
        assertTrue(session.contains("LPCM 00000003 00"))
        assertTrue(session.contains("AAC 0000000F 00"))
        assertTrue(session.contains("0001FFFF"))
        assertTrue(session.contains("1FFFFFFF"))
        assertTrue(session.contains("00 00 03 10"))
        assertFalse(session.contains("secondPlaySent"))
        assertFalse(session.contains("sending Android compatibility second PLAY"))
    }

    @Test
    fun setupAndPlayResponsesAreMatchedByCseq() {
        assertTrue(session.contains("setupCseq"))
        assertTrue(session.contains("playCseq"))
        assertTrue(session.contains("when (cseq)"))
        assertTrue(session.contains("PLAY acknowledged; waiting for RTP"))
    }
}
