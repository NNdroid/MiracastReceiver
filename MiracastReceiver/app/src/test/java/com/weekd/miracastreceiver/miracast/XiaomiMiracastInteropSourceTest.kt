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
    private val appClass = File("src/main/java/com/weekd/miracastreceiver/MiracastApp.kt").readText()
    private val logBuffer = File("src/main/java/com/weekd/miracastreceiver/web/WebLogBuffer.kt").readText()

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
    fun aStaleGroupSocketMustNotPretendTheGroupStillExists() {
        // A vendor supplicant leaves the p2p-wlan0-0 control socket on disk after the group
        // interface is gone. Counting that socket as a group made formSinkGroup() return "group
        // already present" without sending a single formation command, so a rebooted sink
        // advertised a WFD beacon with no group behind it and sources could list it but never
        // connect. The interface list is the only thing that counts as proof.
        assertTrue(
            rootHelper.contains(
                "internal fun groupInterfaceExists(): Boolean = groupInterfaceNames().isNotEmpty()"
            )
        )
        assertTrue(rootHelper.contains("fun groupControlSocketPresent()"))
        assertTrue(rootHelper.contains("the interface is gone"))
    }

    @Test
    fun groupFormationTriesEveryCommandDialectTheSupplicantMayHave() {
        // Stock Android answers GROUP_FORMATION; a Realtek/SSV p2p port answers UNKNOWN COMMAND
        // to it and only accepts P2P_GROUP_ADD. Hardcoding one dialect is exactly how a sink
        // silently never becomes Group Owner, so each dialect is tried and the interface list
        // is the verdict.
        assertTrue(rootHelper.contains("GROUP_FORMATION_COMMANDS"))
        assertTrue(rootHelper.contains("\"GROUP_FORMATION '%s' '%s'\""))
        assertTrue(rootHelper.contains("P2P_GROUP_ADD"))
        assertTrue(rootHelper.contains("for (template in GROUP_FORMATION_COMMANDS)"))
    }

    @Test
    fun theKeepAliveReformsADroppedGroupWithoutWaitingForAFrameworkEvent() {
        // A supplicant restart removes the group interface and delivers no Group Owner message,
        // so the keep-alive must notice the missing interface on its own.
        assertTrue(rootHelper.contains("if (keepAliveReFormGroup || !hadGroup)"))
    }

    @Test
    fun theGroupOwnerAddressIsAppliedWhenTheVendorNetdNeverDoes() {
        // The framework registers the group and reports ownerIp=192.168.49.1, but a vendor netd
        // leaves p2p-wlan0-0 with no IPv4 at all. The group is then a beacon with no network
        // behind it: a Source can associate and still drop every RTSP packet at the first hop.
        assertTrue(rootHelper.contains("SINK_GROUP_OWNER_IP = \"192.168.49.1\""))
        assertTrue(rootHelper.contains("fun ensureGroupOwnerAddress()"))
        assertTrue(rootHelper.contains("fun groupAddressOf(iface: String): String?"))
        assertTrue(
            rootHelper.contains("ip addr add \${SINK_GROUP_OWNER_IP}/\${SINK_GROUP_OWNER_PREFIX} dev")
        )
        assertTrue(rootHelper.contains("runCatching { ensureGroupOwnerAddress() }"))
        assertTrue(rootHelper.contains("out[\"groupOwnerAddress\"]"))
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
        // Declaring no video capability makes strict sources drop the sink during connection
        // setup even after the device is discovered. The field is assembled from named masks so
        // a wrong bit layout cannot slip back in as a single magic number.
        assertTrue(rootHelper.contains("WFD_DEVICE_TYPE_PRIMARY_SINK"))
        assertTrue(rootHelper.contains("WFD_SESSION_AVAILABLE_BIT1 = 0x10"))
        assertTrue(rootHelper.contains("WFD_VIDEO_CAPABILITY_720P60_1080P30"))
        // The field must be assembled from named masks, not hardcoded, or a wrong bit layout can
        // come back in as one opaque literal with no reviewer left to catch it.
        assertFalse(rootHelper.contains("WFD_DEVICE_INFO = 0x"))
        assertFalse(rootHelper.contains("subelemHex() = \""))
    }

    @Test
    fun sessionAvailableUsesTheTwoBitEncodingAndroidItselfWrites() {
        // WifiP2pWfdInfo defines session available across bits 4..5, not bit 0. A single-bit
        // reading of this field is what produced a "reserved" session-available value.
        assertTrue(rootHelper.contains("SESSION_AVAILABLE_BIT"))
        assertTrue(rootHelper.contains("WFD_SESSION_AVAILABLE_MASK = 0x30"))
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
    fun aGroupOwnerFrequencyRestrictionCannotOverrideTheGroupOwnerIntent() {
        // The vendor ships p2p_no_go_freq=5170-5740 in the read-only P2P overlay. On a restricted
        // channel the supplicant declines the Group Owner role before negotiation, so even
        // p2p_go_intent=15 loses: the source wins and this sink comes out as GroupClient on a
        // network whose owner is the other side, so nothing can ever reach RTSP on 7236. Verified
        // live as groupRole=GroupClient on 5180, the same channel the station itself sat on.
        assertTrue(rootHelper.contains("NO_GO_FREQ_KEY"))
        assertTrue(rootHelper.contains("p2p_no_go_freq"))
        assertTrue(rootHelper.contains("echo '\${NO_GO_FREQ_KEY}='"))
        // A non-empty value must count as wrong, otherwise a keep-alive tick reads the file as
        // already correct and the restriction is never cleared at all.
        assertTrue(rootHelper.contains("\${NO_GO_FREQ_KEY}=[^[:space:]]"))
        // The wifi service re-merges the vendor overlay over the /data copy at every HAL start, so
        // patching only /data is undone by the first wifi cycle — which is what kept happening.
        // /vendor is read-only, so the vendor file is hidden with a bind mount instead, and that
        // has to be re-applied on boot because /vendor is remounted from the vendor image.
        assertTrue(rootHelper.contains("VENDOR_P2P_OVERLAY"))
        assertTrue(rootHelper.contains("PATCHED_P2P_OVERLAY"))
        assertTrue(rootHelper.contains("mount --bind"))
        assertTrue(rootHelper.contains("MAGISK_POST_FS_DATA_OVERLAY"))
        assertTrue(rootHelper.contains("post-fs-data.d"))
        assertTrue(rootHelper.contains("fun installP2pOverlayBindMount"))
        // The mount has to land before the wifi cycle that makes the new value take effect.
        assertTrue(rootHelper.contains("installP2pOverlayBindMount()"))
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
        // and the patched intent never took effect.
        assertTrue(rootHelper.contains("set-wifi-enabled disabled"))
        assertTrue(rootHelper.contains("set-wifi-enabled enabled"))
        assertFalse(rootHelper.contains("set-wifi-enabled false"))
        assertFalse(rootHelper.contains("set-wifi-enabled true"))
        // The primary cycle is the HAL service, probed for by name: sending ctl.restart at a
        // service that does not exist is a silent no-op, which is how the intent could have sat
        // on disk and still never reached the supplicant.
        assertTrue(rootHelper.contains("setprop ctl.restart"))
        assertTrue(rootHelper.contains("echo \\\"RESTARTED"))
        // The toggle is only the last resort, for images that expose no HAL service name.
        assertTrue(rootHelper.contains("echo \\\"TOGGLED via cmd wifi\\\""))
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

    @Test
    fun eachSourceIsServedOnItsOwnCoroutineSoOneStaleClientCannotWedgeTheServer() {
        // Running runSession inline in the accept loop meant a Source that opened the TCP
        // connection and went silent parked the whole loop: later Sources completed the handshake,
        // sat in the backlog unanswered, and timed out. On-device this reads as "the receiver
        // shows up in the list but I cannot connect", because the RTSP reply never arrives.
        assertTrue(server.contains("serveSource(socket)"))
        assertTrue(server.contains("private fun serveSource(socket: Socket)"))
        assertTrue(server.contains("scope.launch {"))
        assertTrue(server.contains("activeSessions"))
        assertTrue(server.contains("CopyOnWriteArrayList"))
        // The accept loop must not call runSession synchronously.
        assertFalse(server.contains("if (socket != null) runSession(socket)"))
    }

    @Test
    fun sessionsAreCancelledOnStopSoTheirSocketsAreNotLeaked() {
        assertTrue(server.contains("activeSessions.forEach { it.cancel() }"))
        assertTrue(server.contains("runCatching { socket.close() }"))
    }

    @Test
    fun rtspReadsHaveATimeoutBecauseATimeoutIsTheOnlyWayToSeeAHalfOpenPeer() {
        // A peer that dies without delivering FIN never produces EOF, so a plain blocking read
        // holds the handler indefinitely. The timeout is what lets the session end at all.
        assertTrue(session.contains("IDLE_TIMEOUT_MS"))
        assertTrue(session.contains("IDLE_TIMEOUT_STREAMING_MS"))
        assertTrue(session.contains("SocketTimeoutException"))
        assertTrue(session.contains("socket.soTimeout"))
        assertTrue(session.contains("armReadTimeout()"))
        // Silence while a stream is live is legitimate; before negotiation it is not.
        assertTrue(session.contains("if (streamStarted)"))
        assertTrue(session.contains("silent for"))
    }

    @Test
    fun groupReadyIsIdempotentSoAStableGroupIsNotReAnnouncedEveryPollTick() {
        // Every re-announcement forced a WFD element re-injection into the supplicant. That churn
        // landed on top of a Source's in-flight handshake and made the failure worse.
        assertTrue(wifiDirect.contains("announcedGroupKey"))
        assertTrue(wifiDirect.contains("announcedClients"))
        assertTrue(wifiDirect.contains("announcedClients.add(client.deviceAddress)"))
        assertTrue(wifiDirect.contains("val freshGroup = key != announcedGroupKey"))
        // A torn-down group must count as fresh again when it is formed a second time.
        assertTrue(wifiDirect.contains("announcedGroupKey = null"))
    }

    @Test
    fun aRefusedCreateGroupIsLatchedSoTheFrameworkSurfaceIsNotPolledEveryTick() {
        // On the reference TV box the framework WifiP2pManager is a stub: createGroup,
        // discoverPeers, setDeviceName and addLocalService all return ERROR immediately and keep
        // doing so forever. Each call is a round trip into the Wi-Fi service that shares state
        // with the vendor P2P engine, and a stray createGroup() during the Source's own connect
        // request is what resets that negotiation out from under it. One hard refusal is enough
        // to know the answer, so asking again is only churn.
        assertTrue(wifiDirect.contains("frameworkGroupFormationBroken"))
        // The latch is set only on a hard refusal. BUSY is transient and still gets retried.
        assertTrue(wifiDirect.contains("WifiP2pManager.BUSY ->"))
        assertTrue(wifiDirect.contains("frameworkGroupFormationBroken = true"))
        // And it is honoured before every framework P2P call that would otherwise be retried.
        assertTrue(wifiDirect.contains("if (frameworkGroupFormationBroken)"))
    }

    @Test
    fun p2pCapabilitiesAreLoggedSoTheRoleThisHardwareAllowsIsKnown() {
        // A sink that cannot be a Group Owner has nothing to gain from createGroup(), and one that
        // cannot run STA and P2P concurrently can never form a group while it stays on its Wi-Fi
        // network — which is how these TV boxes ship. Reading the capability once settles it
        // instead of inferring it from repeated failures.
        assertTrue(wifiDirect.contains("requestP2pInfo"))
        assertTrue(wifiDirect.contains("supportsGroupOwner"))
        assertTrue(wifiDirect.contains("supportsConcurrentConnections"))
    }

    @Test
    fun groupOwnerIntentIsNotProbedAgainOnceEveryPathHasRefusedIt() {
        // Every 45 s the keep-alive re-asserted the Group Owner intent: four spellings of P2P_SET,
        // each one a forked su subprocess, plus a vendor config rewrite and a read-back. On the
        // reference box all four spellings FAIL and /vendor is unwritable, so the answer never
        // changes and the churn was pure cost — landing squarely on the shared radio during the
        // one moment a Source's handshake must not be disturbed.
        assertTrue(rootHelper.contains("groupOwnerIntentUnsupported"))
        // Only after the whole probe has been exhausted, so a transiently absent control socket is
        // not mistaken for a permanent refusal.
        assertTrue(rootHelper.contains("if (!configApplied && !acknowledged)"))
        assertTrue(rootHelper.contains("if (groupOwnerIntentUnsupported) return"))
        // A fresh advertisement cycle must be able to re-probe.
        assertTrue(rootHelper.contains("groupOwnerIntentUnsupported = false"))
        // The verdict is surfaced, so a dead end is visible rather than silent.
        assertTrue(rootHelper.contains("out[\"groupOwnerIntentUnsupported\"]"))
    }

    @Test
    fun logcatIsPlantedInEveryBuildBecauseTheWebBufferIsTooSmallToBeTheOnlyWitness() {
        // With the debug tree behind BuildConfig.DEBUG a release build wrote to no log sink at
        // all, and the in-memory buffer held only what it held. A Miracast failure that happens
        // once during a connection attempt is unrecoverable, which is how the last debugging
        // session ended up blind. logcat is the sink that survives.
        assertTrue(appClass.contains("Timber.plant(WebLogBuffer.timberTree)"))
        assertTrue(appClass.contains("Timber.plant(Timber.DebugTree())"))
        assertFalse(appClass.contains("if (BuildConfig.DEBUG)"))
        // Enough history to hold a whole failed attempt at the observed logging rate.
        assertTrue(logBuffer.contains("MAX_ENTRIES = 2000"))
    }

    @Test
    fun groupOwnerIntentGoesThroughTheWritableRuntimeConfigBeforeTheVendorOverlays() {
        // The vendor overlays under /vendor were either zero bytes or on a read-only partition, so
        // a patch aimed only at them reported that it tried and changed nothing. The HAL keeps a
        // merged copy under /data that is both writable and the file the supplicant actually
        // reads. It has to be on the candidate list at all — it was not — and it has to come
        // first, because the intent is taken from whichever config the patcher rewrites.
        assertTrue(rootHelper.contains("\"/data/vendor/wifi/wpa/p2p_supplicant.conf\""))
        assertTrue(rootHelper.contains("P2P_SUPPLICANT_CONFIGS = listOf("))
        val listBlock = rootHelper.substringAfter("P2P_SUPPLICANT_CONFIGS = listOf(")
            .substringBefore(")")
        val dataIndex = listBlock.indexOf("/data/vendor/wifi/wpa/p2p_supplicant.conf")
        val vendorIndex = listBlock.indexOf("/vendor/etc/wifi")
        assertTrue("the writable /data copy must precede the read-only /vendor overlays",
            dataIndex in 0 until vendorIndex)
    }

    @Test
    fun aReadBackTrustsStdoutOverTheSuExitCodeSoAWorkingConfigIsNotReportedBroken() {
        // su -c on this image exits 2 while still printing the correct answer, so keying a
        // read-back on the exit code made the WFD advertisement report verified=false on every
        // cycle even though the supplicant echoed the injected element back byte for byte. The
        // same trap made a successful config patch read back as null and therefore as a failure,
        // which is what kept the wifi stack from ever being cycled to pick the intent up.
        assertTrue(rootHelper.contains("private fun runAsRootQuery"))
        // A nonzero exit with a reply is a reply, not a failure.
        assertTrue(rootHelper.contains("exited \$exit but answered"))
        // Only genuinely empty output counts as no answer.
        assertTrue(rootHelper.contains("produced no reply"))
        // Every read-back path goes through it, not just one.
        assertTrue(rootHelper.contains("runAsRootQuery(\"\$binaryPath \$socketPath \\\"WFD_SUBELEM_GET 0\\\"\")"))
        assertTrue(rootHelper.contains("runAsRootQuery(\n            \"grep -hE"))
        assertTrue(rootHelper.contains("runAsRootQuery(\"sh \$scriptPath\")"))
    }

    @Test
    fun theWifiStackIsCycledOnlyWhenTheConfigWasActuallyWritten() {
        // The intent is read once at supplicant startup, so nothing takes effect without a cycle.
        // But a cycle drops the LAN link the app is usually sitting on, so it must be gated on a
        // write that really happened — otherwise an already-correct config costs a connection
        // drop on every app start and on every reboot.
        assertTrue(rootHelper.contains("private enum class GoIntentConfigResult"))
        assertTrue(rootHelper.contains("PATCHED, ALREADY_CURRENT, FAILED"))
        assertTrue(rootHelper.contains(
            "if (configResult == GoIntentConfigResult.PATCHED) restartWifiForGroupOwnerIntent()"))
        // The restart target is probed rather than guessed: sending ctl.restart at a service name
        // that does not exist is a silent no-op, which is how the intent could have sat on disk
        // and still never reached the supplicant.
        assertTrue(rootHelper.contains("vendor.wifi_hal_legacy"))
        assertTrue(rootHelper.contains("getprop init.svc.\\\$s"))
        // And the wait for the link is a poll, so a slow box is not judged dead on arrival.
        assertTrue(rootHelper.contains("Thread.sleep(5_000L)"))
    }

    @Test
    fun theLiveIntentProbeIsSkippedOnceTheConfigAlreadyHoldsIt() {
        // With the writable config in place the live P2P_SET probe adds nothing but churn: sockets
        // multiplied by spellings, one forked su subprocess each, every keep-alive tick, and every
        // one refused by the vendor control socket. That traffic shares the radio with the Source
        // that is negotiating, which is the one moment it must not be there.
        assertTrue(rootHelper.contains("the live P2P_SET probe is not attempted"))
        assertTrue(rootHelper.contains("if (configApplied) {"))
        // The probe is still there for images that have no writable configuration at all.
        assertTrue(rootHelper.contains("GO_INTENT_SET_NAMES"))
        assertTrue(rootHelper.contains("for (name in GO_INTENT_SET_NAMES)"))
    }

    @Test
    fun patchingPreservesSupplicantOwnershipSoTheStackCanStillWriteItsConfig() {
        // The supplicant runs as wifi with update_config=1, so it has to keep being able to write
        // its own config. A root rename makes the file root:root and the formed P2P groups stop
        // being persisted back to it — a silent regression that shows up only after a reboot.
        assertTrue(rootHelper.contains("stat -c '%U:%G'"))
        assertTrue(rootHelper.contains("stat -c '%a'"))
        assertTrue(rootHelper.contains("chown \\\"\\\$own\\\""))
        assertTrue(rootHelper.contains("chmod \\\"\\\$mode\\\""))
        // And a file that is already correct is left alone, so a keep-alive tick is not a churn.
        assertTrue(rootHelper.contains("echo \\\"OK \\\$f\\\""))
        // Boot persistence is installed only when a write really happened, and the helper path
        // travels with it so the script can keep the WFD element alive as root.
        assertTrue(rootHelper.contains("installBootPersistence(script, binaryPath)"))
    }

    @Test
    fun theBootServiceKeepsTheWfdElementAliveWithoutAnySuGrant() {
        // The runtime keep-alive needs an su grant, and Magisk drops grants across reinstalls: a
        // freshly installed build has a new uid and no policy entry, so every su call comes back
        // "Permission denied" and the sink stops being a sink. A boot service needs no grant.
        assertTrue(rootHelper.contains("BOOT_WFDCTL_PATH"))
        assertTrue(rootHelper.contains("fun wfdAdvertiseBootBody()"))
        assertTrue(rootHelper.contains("WFD_SUBELEM=\${subelemHex()}"))
        assertTrue(rootHelper.contains("WFD_SUBELEM_SET 0 \\\$WFD_SUBELEM"))
        // The loop must not block the boot service, or the script that starts the app service
        // later in the same directory never gets to run.
        assertTrue(rootHelper.contains("(\n"))
        assertTrue(rootHelper.contains("exit 0"))
        // The intent-patch body is written to end with `exit 0` because it is also run on its own
        // at runtime. Appended after that, the advertisement would be dead code — verified on
        // device, where the generated service advertised WFD nothing and the sink was invisible.
        assertTrue(rootHelper.contains("removeSuffix(\"exit 0\")"))
        // Same reasoning applies to the Group Owner address: the runtime path that assigns it is
        // also grant-gated, so the boot loop carries it too.
        assertTrue(rootHelper.contains("for gi in /sys/class/net/p2p-wlan0-*"))
        assertTrue(rootHelper.contains("ip addr add \${SINK_GROUP_OWNER_IP}/\${SINK_GROUP_OWNER_PREFIX} dev"))
    }

    @Test
    fun diagnosticsNameWhichConfigFileIsActuallyWritable() {
        // Which candidate is writable decides whether the image can be fixed at all, and the two
        // look identical in a log line that says only "configApplied=false". Surfacing it makes the
        // dead end visible from the diagnostics page instead of requiring a shell session.
        assertTrue(rootHelper.contains("out[\"groupOwnerIntentConfigFiles\"]"))
        assertTrue(rootHelper.contains("WRITABLE \\\$f"))
        assertTrue(rootHelper.contains("READONLY \\\$f"))
    }
}
