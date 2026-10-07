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
    fun locationPermissionIsGrantedBecauseTheWifiServiceDropsP2pBroadcastsOtherwise() {
        // The Wi-Fi service checks a location permission and a location appop before delivering
        // android.net.wifi.p2p.CONNECTION_STATE_CHANGE. Denied either way, the failure is silent:
        // the group forms and the receiver never learns about it — listable but never connectable.
        // Root grants both through pm and appops, so no Settings detour is needed.
        assertTrue(rootHelper.contains("fun grantWifiPermissions"))
        assertTrue(rootHelper.contains("pm grant"))
        assertTrue(rootHelper.contains("cmd appops set"))
        assertTrue(rootHelper.contains("android:fine_location"))
        assertTrue(rootHelper.contains("android:coarse_location"))
        assertTrue(rootHelper.contains("ACCESS_FINE_LOCATION"))
        assertTrue(wifiDirect.contains("WfdRootHelper.grantWifiPermissions(appContext)"))
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
    }

    @Test
    fun nearbyWifiDevicesIsSelfGrantedBecauseWithoutItTheSinkNeverFormsItsGroup() {
        // The live receiver had NEARBY_WIFI_DEVICES still denied after the location grants landed,
        // and on API 33+ that is what makes WifiP2pManager.requestGroupInfo() throw
        // SecurityException. The sink then never creates the group that makes it the Group Owner:
        // the supplicant has already put the WFD element on the air, so every source lists the
        // device and none of them can connect to it. Granting location alone leaves that symptom.
        assertTrue(rootHelper.contains("Manifest.permission.NEARBY_WIFI_DEVICES"))
        // Every wanted permission rides the same pm path, so the new one needs no new plumbing.
        assertTrue(rootHelper.contains("pm grant \${context.packageName} \$it"))
        assertTrue(rootHelper.contains("buildList"))
    }

    @Test
    fun connectionStateIsPolledBecauseTheLocationAppopIsCappedAtForegroundOnly() {
        // On the receiver the appop came back "Uid mode: FINE_LOCATION: foreground" and could not
        // be forced past it, so BroadcastQueue kept refusing CONNECTION_STATE_CHANGE with every
        // permission granted. requestConnectionInfo() is a plain call with no appop, so a timer
        // poll observes the join the broadcast never reported. Verified live: the log showed
        // "P2P poll saw a group the broadcast had not reported" repeating for the Source's group.
        assertTrue(wifiDirect.contains("fun pollConnectionState"))
        assertTrue(wifiDirect.contains("CONNECTION_POLL_MS"))
        assertTrue(wifiDirect.contains("connectionPollInFlight"))
        assertTrue(wifiDirect.contains("mainHandler.postDelayed({ if (isStarted) pollConnectionState() }"))
        // An unchanged topology must not be re-handled every tick, or the sink re-injects its WFD
        // element twice a second and churns the supplicant for nothing.
        assertTrue(wifiDirect.contains("lastPollTopology"))
    }

    @Test
    fun aForeignGroupWithNoClientsTriggersOurOwnGroupOwnerGroup() {
        // On the receiver the poll found DIRECT-h8-Redmi 10X with sinkIsOwner=false and
        // clients=0: the Source had won Group Owner negotiation and formed its own group, so
        // this device held a beacon with no network behind it. Treating that group as ours skips
        // createGroup() entirely, the Source cannot reach an RTSP server that is not on the
        // network, and the attempt dies with no error anywhere.
        assertTrue(wifiDirect.contains("existing.clientList.isNotEmpty()"))
        assertTrue(wifiDirect.contains("creating a Group Owner group of our own"))
    }

    @Test
    fun theGroupInterfaceNameIsNotHardcodedToP2p0() {
        // A supplicant built without use_p2p_group_interface=1 names its group interface
        // p2p-<phy>-<n> instead of p2p0. The receiver on this project is exactly that, so
        // matching only p2p\\d+ made a live Group Owner group look absent, and the diagnostics
        // then reported "not a Group Owner, so sources cannot find it" while one was in fact up.
        assertFalse(rootHelper.contains("name.matches(Regex(\"p2p\\\\d+\"))"))
        assertTrue(rootHelper.contains("fun groupInterfaceNames"))
        assertTrue(rootHelper.contains("!it.startsWith(\"p2p-dev-\")"))
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
        // The stock parameter name is refused outright on vendor builds, so every spelling is
        // tried: a sink stuck at intent 0 loses to the Source and the mirror goes to the phone.
        assertTrue(rootHelper.contains("GO_INTENT_SET_NAMES"))
        assertTrue(rootHelper.contains("P2P_SET"))
        assertTrue(rootHelper.contains("groupOwnerIntentConfigured"))
    }

    @Test
    fun groupOwnerIntentIsRewrittenIntoTheVendorConfigWhenTheLiveSetIsRefused() {
        // These supplicants reject every P2P_SET intent variant and bake 0 into a read-only
        // /vendor file, so the intent can only be changed on disk. `p2p_no_group_iface` is
        // patched away too, because with it set the device never creates p2p0 and no intent
        // value can make it a Group Owner at all.
        assertTrue(rootHelper.contains("P2P_SUPPLICANT_CONFIGS"))
        assertTrue(rootHelper.contains("p2p_supplicant_ssv.conf"))
        assertTrue(rootHelper.contains("p2p_go_intent"))
        assertTrue(rootHelper.contains("NO_GROUP_IFACE_KEY"))
        assertTrue(rootHelper.contains("mount -o remount,rw /vendor"))
        assertTrue(rootHelper.contains("fun applyGroupOwnerIntentToConfig"))
        assertTrue(rootHelper.contains("fun restartWifiForGroupOwnerIntent"))
    }

    @Test
    fun configRewriteStagesToATempFileBecauseSedInPlaceDestroysFilesOnAFullPartition() {
        // The vendor image ships /vendor at 100%. sed -i there creates its temp file, the content
        // write fails with ENOSPC, and the rename still succeeds — so the file is left at zero
        // bytes and the original wifi configuration is gone for good. The patch must therefore
        // stage, verify the staged file actually carries both new keys, and only then rename.
        assertTrue(rootHelper.contains("goIntentConfigPatchScript"))
        assertTrue(rootHelper.contains("grep -vE"))
        assertTrue(rootHelper.contains("echo 'p2p_go_intent="))
        assertTrue(rootHelper.contains(".mr."))
        assertTrue(rootHelper.contains("mv -f"))
        assertTrue(rootHelper.contains("UNWRITABLE"))
        assertFalse(rootHelper.contains("sed -i"))
    }

    @Test
    fun wifiCycleUsesTheArgumentsThisSupplicantActuallyAccepts() {
        // `cmd wifi set-wifi-enabled` takes the words enabled|disabled. Boolean arguments are
        // rejected with an IllegalArgumentException, so a cycle written with true/false never ran
        // and the patched intent never took effect. The P2P interface is cycled first, because
        // that is what Group Owner negotiation needs and wificond does not recreate it from a
        // bare supplicant restart.
        assertTrue(rootHelper.contains("set-p2p-enabled"))
        assertTrue(rootHelper.contains("set-wifi-enabled disabled"))
        assertTrue(rootHelper.contains("set-wifi-enabled enabled"))
        assertFalse(rootHelper.contains("set-wifi-enabled false"))
        assertFalse(rootHelper.contains("set-wifi-enabled true"))
    }

    @Test
    fun groupOwnerIntentSurvivesRebootThroughABootService() {
        // The vendor image restores the wifi configuration on every boot, silently undoing the
        // patch, so the rewrite has to re-run at boot or the sink regresses to intent 0.
        assertTrue(rootHelper.contains("fun installBootPersistence"))
        assertTrue(rootHelper.contains("MAGISK_GO_INTENT_SERVICE"))
        assertTrue(rootHelper.contains("/data/adb/service.d"))
        assertTrue(rootHelper.contains("MAGISK_SERVICE_MARKER"))
        assertTrue(rootHelper.contains("groupOwnerIntentBootPersisted"))
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
    fun sinkListensForTheSourceInsteadOfScanningForIt() {
        // The Source dials the control port published in the WFD information element. A Sink that
        // scans the P2P subnet answers connections nobody made and never accepts the one that was
        // made, so the Source waits out its RTSP timeout and drops the group.
        assertTrue(server.contains("ServerSocket"))
        assertTrue(server.contains("acceptSource()"))
        assertTrue(server.contains("ensureListener()"))
        assertTrue(server.contains("listeningPort()"))
        assertFalse(server.contains("p2pSubnetPrefix"))
        assertFalse(server.contains("tryConnect"))
        assertFalse(server.contains("dialSource"))
    }

    @Test
    fun sourceOwnedGroupIsStillDetectedForDiagnostics() {
        assertTrue(wifiDirect.contains("getControlPort"))
        assertTrue(wifiDirect.contains("source-group-owner"))
        assertTrue(server.contains("WfdSourceHint.update"))
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
    fun setupAndPlayAreAnsweredAsAServerInsteadOfSentAsRequests() {
        // The Source drives the exchange. Answering its SETUP with a Session and a Transport that
        // carries our RTP port is what starts the stream; sending SETUP as an outgoing request
        // leaves both peers waiting for each other until the Source's timeout drops the group.
        assertTrue(session.contains("sendSetupResponse"))
        assertTrue(session.contains("sendPlayResponse"))
        assertTrue(session.contains("\"SETUP\" -> sendSetupResponse(cseq)"))
        assertTrue(session.contains("\"PLAY\" -> sendPlayResponse(cseq)"))
        assertTrue(session.contains("server_port=\$rtpPort-"))
        assertTrue(session.contains("PLAY acknowledged; waiting for RTP"))
        assertFalse(session.contains("setupCseq"))
        assertFalse(session.contains("playCseq"))
    }
}
