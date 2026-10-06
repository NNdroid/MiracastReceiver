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
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val appBuild = File("build.gradle.kts").readText()

    @Test
    fun sinkCreatesItsOwnGroupOwnerGroupOrItIsInvisibleToSources() {
        // A Miracast source only lists P2P devices that advertise Group Owner capability together
        // with the WFD information element. Reading the group state and never forming one leaves
        // the sink undiscoverable, so group creation is the discovery path, not a side step.
        assertTrue(wifiDirect.contains("ensureSinkGroup()"))
        assertTrue(wifiDirect.contains("createSinkGroup()"))
        assertTrue(wifiDirect.contains("p2p.createGroup(ch"))
        assertTrue(wifiDirect.contains("P2P group already exists"))
    }

    @Test
    fun rootGroupFormationFallbackExistsBecauseTheFrameworkRefusesOnVendorBuilds() {
        // createGroup() has no root equivalent and fails with ERROR on many vendor builds, leaving
        // the sink without a group. Without a group there is no G/O beacon, so a source can list
        // the device and still be unable to connect to it at all.
        assertTrue(rootHelper.contains("GROUP_FORMATION"))
        assertTrue(rootHelper.contains("fun formSinkGroup"))
        assertTrue(rootHelper.contains("fun groupInterfaceExists()"))
        assertTrue(rootHelper.contains("waitForGroupInterface"))
        assertTrue(rootHelper.contains("SINK_GROUP_SSID"))
        assertTrue(rootHelper.contains("fun setKeepAliveGroupFormation"))
        assertTrue(wifiDirect.contains("scheduleFallbackGroupFormation()"))
        assertTrue(wifiDirect.contains("formSinkGroupFallback()"))
        assertTrue(wifiDirect.contains("SINK_GROUP_FORMED_ROOT"))
    }

    @Test
    fun rootGroupFormationIsIdempotentSoAnAttachedSourceIsNeverDropped() {
        // An existing group is left alone, because tearing it down would disconnect a source
        // already attached to it — so the fallback must check before it creates.
        assertTrue(rootHelper.contains("group already present"))
        assertFalse(rootHelper.contains("GROUP_RELEASE"))
    }

    @Test
    fun theWfdElementIsReinjectedIntoTheGroupOwnerBeaconAfterFormation() {
        // WFD_SUBELEM_SET reaches the air only from the interface that carries the beacon, and
        // p2p0 only exists once a group exists. Forming the group without re-injecting the IE
        // therefore leaves a source with a group it can join but nothing inside it.
        assertTrue(rootHelper.contains("/data/vendor/wifi/wpa/sockets/p2p0"))
        assertTrue(wifiDirect.contains("WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)"))
    }

    @Test
    fun groupOwnerIntentIsReadBackAndReassertedOnEveryKeepAliveCycle() {
        // An acknowledged P2P_SET is not proof the supplicant applied it, and nothing else re-sets
        // the intent after startup — so it must be verified, and re-issued alongside the
        // advertisement, or the sink loses Group Owner negotiation mid-session.
        assertTrue(rootHelper.contains("fun parseGroupOwnerIntent"))
        assertTrue(rootHelper.contains("fun groupOwnerIntentReadback"))
        assertTrue(rootHelper.contains("P2P_GET"))
        assertTrue(rootHelper.contains("lastGroupOwnerIntentReadback"))
        assertTrue(rootHelper.contains("configureGroupOwnerIntent(appContext)"))
        // The two separators are both read; accepting only one makes every read-back null and the
        // check looks healthier than it is.
        assertTrue(rootHelper.contains("[:=]"))
    }

    @Test
    fun sinkDeclaresItsFullMiracastCapabilitySetInTheInformationElement() {
        // Declaring only 1024x768 with no video capability makes strict sources drop the sink
        // during connection setup even after the device is discovered.
        assertTrue(rootHelper.contains("WFD_DEVICE_INFO = 0xCF1"))
        assertFalse(rootHelper.contains("\"0011\""))
    }

    @Test
    fun groupFormationIsVisibleInDiagnostics() {
        assertTrue(rootHelper.contains("groupOwnerIntentReadback\""))
        assertTrue(rootHelper.contains("groupFormation\""))
        assertTrue(rootHelper.contains("not a Group Owner"))
    }

    @Test
    fun sinkKeepsToleratingASourceOwnedGroupAtTheRtspLayer() {
        assertTrue(wifiDirect.contains("Sink is GO (standard Android Source-compatible)"))
        assertTrue(wifiDirect.contains("Source is GO"))
    }

    @Test
    fun advertisementIsInjectedIntoTheP2pDeviceInterfaceAfterInitialize() {
        // WFD_SUBELEM_SET has no effect on the STA interface: it cannot emit a P2P advertisement.
        // Injection therefore has to wait for initialize() to bring p2p-dev-* up.
        assertTrue(wifiDirect.contains("SINK_PREPARE_DELAY_MS"))
        assertTrue(wifiDirect.contains("WfdRootHelper.advertiseSink(appContext, force = true)"))
        assertTrue(wifiDirect.contains("WfdRootHelper.configureGroupOwnerIntent(appContext)"))
        assertTrue(wifiDirect.contains("prepareSink()"))
    }

    @Test
    fun groupOwnerIntentIsRaisedSoTheSinkWinsGoNegotiation() {
        assertTrue(rootHelper.contains("GO_OWNER_INTENT"))
        assertTrue(rootHelper.contains("fun configureGroupOwnerIntent"))
        assertTrue(rootHelper.contains("P2P_SET go_int"))
        assertTrue(rootHelper.contains("groupOwnerIntentConfigured"))
    }

    @Test
    fun advertisementSuccessIsReportedWithVerificationHonesty() {
        assertTrue(rootHelper.contains("WFDCTL_OK"))
        assertTrue(rootHelper.contains("WFDCTL_REJECTED"))
        assertTrue(rootHelper.contains("WFDCTL_UNCONFIRMED"))
        assertTrue(rootHelper.contains("fun kindOf("))
        assertTrue(rootHelper.contains("P2P_DEV"))
        assertTrue(rootHelper.contains("STA_FALLBACK"))
        assertTrue(rootHelper.contains("GROUP_IFACE"))
    }

    @Test
    fun p2pScanPermissionsAreCheckedBeforeGroupCreation() {
        assertTrue(wifiDirect.contains("NEARBY_WIFI_DEVICES"))
        assertTrue(wifiDirect.contains("fun p2pPermissionsGranted()"))
        assertTrue(wifiDirect.contains("requestP2pPermissions()"))
    }

    @Test
    fun wiFiRadioIsOnBeforeP2pInitializesOrNothingCanBeAdvertised() {
        // Wi-Fi Direct has no radio while Wi-Fi is disabled, and an Ethernet-connected TV box
        // ships with Wi-Fi off. If initialize() is never reached there is no p2p-dev-* socket,
        // so the sink cannot emit a WFD element and is invisible to every source.
        assertTrue(wifiDirect.contains("private fun wifiEnabledOrEnable()"))
        assertTrue(wifiDirect.contains("setWifiEnabled(true)"))
        assertTrue(wifiDirect.contains("WIFI_ENABLED_BY_APP"))
        assertTrue(wifiDirect.contains("WIFI_CANT_ENABLE"))
    }

    @Test
    fun wfdHelperSurvivesDisabledNativeLibraryExtraction() {
        // With extractNativeLibs=false the platform never populates nativeLibraryDir, the helper
        // is silently absent, and every WFD command turns into a no-op — so the sink is invisible
        // with nothing in the logs but "libwfdctl.so missing". The binary must be unpacked out of
        // the APK, and every call site must go through that resolver.
        assertTrue(rootHelper.contains("private fun helperBinary(context: Context)"))
        assertTrue(rootHelper.contains("private fun extractHelperFromApk("))
        assertTrue(rootHelper.contains("applicationInfo.sourceDir"))
        assertTrue(rootHelper.contains("lib/\$abi/\$BINARY_NAME"))
        assertTrue(rootHelper.contains("chmod 755 '\$extractedPath'"))
        assertTrue(rootHelper.contains("helperBinary(context) ?: return null"))
        assertTrue(rootHelper.contains("helperBinary(appContext) ?: run {"))
        assertFalse(rootHelper.contains("binary.exists()"))
    }

    @Test
    fun nativeLibrariesAreForcedOntoDiskBecauseTheHelperIsExecuted() {
        // wfdctl is an executable that su launches, not a library the linker dlopens. While AGP
        // keeps the native payload inside the APK, nativeLibraryDir is empty at runtime and
        // Miracast discovery cannot start at all.
        assertTrue(manifest.contains("android:extractNativeLibs=\"true\""))
        assertTrue(appBuild.contains("useLegacyPackaging = true"))
    }

    @Test
    fun sinkAdvertisementStateIsExposedSoFailureIsDiagnosable() {
        assertTrue(wifiDirect.contains("RuntimeStateMiracast.report("))
        assertTrue(rootHelper.contains("fun diagnostics(context: Context)"))
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
        assertTrue(rootHelper.contains("fun advertiseSink(context: Context, controlPort: Int = 7236"))
    }

    @Test
    fun advertisementIsClearedWhenTheReceiverStops() {
        assertTrue(wifiDirect.contains("WfdRootHelper.stopAdvertising(appContext)"))
        assertTrue(rootHelper.contains("fun stopAdvertising"))
        assertTrue(rootHelper.contains("SET wifi_display 0"))
        assertFalse(rootHelper.contains("\"assume accepted\""))
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
