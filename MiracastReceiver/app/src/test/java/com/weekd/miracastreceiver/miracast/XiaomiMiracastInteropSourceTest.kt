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
    fun sinkPrefersSourceOwnedGroupInsteadOfAutonomousGo() {
        assertTrue(wifiDirect.contains("prepareForSourceOwnedGroup()"))
        assertTrue(wifiDirect.contains("topology=source-go-preferred"))
        assertTrue(wifiDirect.contains("Removing stale empty autonomous GO"))
        assertTrue(wifiDirect.contains("Source can become Group Owner"))
        assertFalse(wifiDirect.contains("p2p.createGroup("))
        assertFalse(wifiDirect.contains("autonomousGo=true"))
    }

    @Test
    fun activeGroupsArePreservedWhileOnlyEmptySinkGoIsRemoved() {
        assertTrue(wifiDirect.contains("existing.isGroupOwner && existing.clientList.isEmpty()"))
        assertTrue(wifiDirect.contains("Preserving active P2P group"))
        assertTrue(wifiDirect.contains("existing.clientList.size"))
    }

    @Test
    fun sourceControlPortAndGroupOwnerAddressHintsAreStillPreserved() {
        assertTrue(wifiDirect.contains("getControlPort"))
        assertTrue(wifiDirect.contains("source-group-owner"))
        assertTrue(server.contains("WfdSourceHint.snapshot()"))
        assertTrue(server.contains("hint.controlPort"))
        assertTrue(server.contains("hint.ipAddress"))
        assertTrue(server.contains("discoverSourceControlPort"))
        assertTrue(server.contains("supplicant-peer-ie"))
    }

    @Test
    fun sinkAdvertises7236ButStillReadsRealSourceWfdIe() {
        assertTrue(rootHelper.contains("controlPort: Int = 7236"))
        assertTrue(rootHelper.contains("P2P_PEER FIRST"))
        assertTrue(rootHelper.contains("parsePeerControlPort"))
        assertTrue(rootHelper.contains("fun advertiseSink(context: Context, controlPort: Int = 7236)"))
    }

    @Test
    fun broadR1CapabilitiesRemainCompatibleWithXiaomiAndAndroid() {
        assertTrue(session.contains("LPCM 00000003 00"))
        assertTrue(session.contains("AAC 0000000F 00"))
        assertTrue(session.contains("0001FFFF"))
        assertTrue(session.contains("1FFFFFFF"))
        assertTrue(session.contains("00 00 03 10"))
        assertTrue(session.contains("androidLikeSource"))
        assertTrue(session.contains("second PLAY"))
    }

    @Test
    fun setupAndPlayResponsesAreMatchedByCseq() {
        assertTrue(session.contains("setupCseq"))
        assertTrue(session.contains("playCseq"))
        assertTrue(session.contains("when (cseq)"))
    }
}
