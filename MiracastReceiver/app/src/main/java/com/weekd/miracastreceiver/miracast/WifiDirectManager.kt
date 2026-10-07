package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException

/**
 * Wi-Fi Direct control plane for the Miracast sink.
 *
 * A Miracast sink has to be the P2P Group Owner. The Source scans for G/O devices carrying the WFD
 * information element and starts GO negotiation against the ones it finds; a sink that never forms
 * a group therefore never advertises and stays invisible in the Source's device list, no matter how
 * correct the RTSP side is. The ordering here follows that constraint:
 *
 *   1. initialize the P2pManager, which brings up the supplicant's `p2p-dev-*` interface
 *   2. advertise the WFD IE into `p2p-dev-*` — `wlan0` cannot emit a P2P advertisement
 *   3. raise the Group Owner intent to the maximum
 *   4. form the group as Group Owner, then re-inject the IE so it lands in the G/O beacon
 *
 * Steps 2-4 are retried whenever the group comes back down. Step 2 used to run before step 1,
 * which sent `WFD_SUBELEM_SET` to the STA interface and left the sink invisible.
 */
class WifiDirectManager(
    private val context: Context,
    private val deviceName: String
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private companion object {
        /**
         * Reflection name candidates for WifiP2pManager's WFD entry point, ordered by Android
         * version: `setWfdInfo` resolves on API 30–37 (@SystemApi), `setWFDInfo` on API 23–29 and
         * as the hidden twin on 30–R. Trying both keeps every supported release covered.
         */
        val WFD_METHOD_CANDIDATES = listOf("setWfdInfo", "setWFDInfo")

        /** P2P group interfaces need a moment after initialize() before they accept commands. */
        val SINK_PREPARE_DELAY_MS = 750L

        /**
         * Cadence for the broadcast-independent connection poll. `CONNECTION_STATE_CHANGE` is not
         * guaranteed to arrive: the Wi-Fi service holds a foreground-only location appop that a
         * backgrounded receiver cannot satisfy, so the broadcast gets dropped and the sink never
         * learns a Source joined. `requestConnectionInfo()` is a plain API call with no appop, so
         * polling it is the fallback that makes the connection observable regardless.
         */
        val CONNECTION_POLL_MS = 2_000L
    }

    private var connectionPollInFlight = false

    /** Last topology the poll observed, so an unchanged group is not re-handled on every tick. */
    private var lastPollTopology = "no-group"

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false
    private var frameworkStarted = false
    private var discoveryRetryCount = 0
    private var sinkGroupAttempted = false

    /**
     * The framework's P2P control surface answered once with a hard refusal. On vendor builds that
     * ship a proprietary P2P engine the framework `WifiP2pManager` is a stub: `createGroup`,
     * `discoverPeers`, `setDeviceName` and `addLocalService` all return ERROR immediately and will
     * keep doing so for the lifetime of the process. The latch stops asking again, because each
     * call is a round trip into the Wi-Fi service that shares state with the vendor P2P engine
     * handling the Source's real connection request — and a stray `createGroup()` while that
     * negotiation is in flight is exactly how a working connect attempt gets reset out from under
     * it. Latched for good rather than backoff-timed, since the answer is deterministic.
     */
    @Volatile
    private var frameworkGroupFormationBroken = false

    private var p2pCapabilityLogged = false

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null
    var onGroupCreated: ((WifiP2pGroup) -> Unit)? = null

    fun start() {
        if (isStarted) return
        val p2p = manager ?: run {
            Timber.w("Wi-Fi Direct is unavailable; Miracast cannot be advertised")
            RuntimeStateMiracast.report("WIFI_DIRECT_SERVICE_MISSING")
            return
        }
        isStarted = true
        sinkGroupAttempted = false
        lastPollTopology = "no-group"
        WfdSourceHint.clear()

        Thread({
            if (!isStarted) return@Thread
            // Wi-Fi Direct has no radio while Wi-Fi is off, and an Ethernet-connected TV box ships
            // with Wi-Fi disabled by default. With the radio down there is no p2p-dev-* socket at
            // all, so the sink can advertise nothing and is invisible to every source.
            if (!wifiEnabledOrEnable()) return@Thread
            // requestPermissions is @MainThread, so the permission probe happens here and the
            // actual request on the main handler.
            val granted = p2pPermissionsGranted()
            if (isStarted) {
                // Root can grant the location permission the Wi-Fi service checks before delivering
                // P2P connection broadcasts, which otherwise makes the sink listable but never
                // connectable. Done here rather than at request time so a denied dialog still works
                // as the fallback.
                WfdRootHelper.grantWifiPermissions(appContext)
                if (!p2pPermissionsGranted()) mainHandler.post { requestP2pPermissions() }
                mainHandler.post { startFrameworkP2p(p2p) }
            }
        }, "wfd-prepare").apply { isDaemon = true }.start()
    }

    /**
     * Turn the Wi-Fi radio on before touching P2P, and wait for it to come up. Runs off the main
     * thread because the wait is real.
     */
    private fun wifiEnabledOrEnable(): Boolean {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: run {
                RuntimeStateMiracast.report("WIFI_SERVICE_MISSING")
                Timber.e("Wi-Fi service unavailable; Miracast cannot be advertised")
                return false
            }
        if (wifi.isWifiEnabled) return true
        Timber.w("Wi-Fi is disabled, enabling it so Wi-Fi Direct can initialize for Miracast")
        val enabled = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        RuntimeStateMiracast.report(if (enabled) "WIFI_ENABLED_BY_APP" else "WIFI_CANT_ENABLE")
        if (!enabled) {
            Timber.e("Cannot enable Wi-Fi from here; the sink will stay invisible to Miracast sources")
            return false
        }
        // The radio and the P2P device need a moment to come up before initialize() succeeds.
        runCatching { Thread.sleep(2_000L) }
        return wifi.isWifiEnabled
    }

    private fun startFrameworkP2p(p2p: WifiP2pManager) {
        if (!isStarted || frameworkStarted) return
        try {
            channel = p2p.initialize(appContext, Looper.getMainLooper()) {
                Timber.w("Wi-Fi P2P channel disconnected; scheduling recovery")
                frameworkStarted = false
                channel = null
                sinkGroupAttempted = false
                if (isStarted) mainHandler.postDelayed({ startFrameworkP2p(p2p) }, 1_000L)
            }
            if (channel == null) return

            frameworkStarted = true
            logP2pCapability(p2p)
            registerReceiver()
            // The poll runs whether or not the receiver registered: if the Wi-Fi service refuses
            // to deliver its broadcasts to this UID, this is the only path that notices a join.
            mainHandler.postDelayed({ if (isStarted) pollConnectionState() }, CONNECTION_POLL_MS)
            setWfdInfo()
            setMiracastMode(2, "SINK")
            registerLocalService()
            setDeviceName(deviceName)
            RuntimeStateMiracast.report("P2P_INITIALIZED")

            if (!p2pPermissionsGranted()) {
                Timber.w(
                    "P2P scan permissions missing on API ${Build.VERSION.SDK_INT}; group creation and " +
                        "peer discovery will fail and the sink will stay invisible to sources"
                )
            }

            // WFD injection and group formation both need the p2p-dev interface, which only exists
            // after initialize().
            mainHandler.postDelayed({ if (isStarted) prepareSink() }, SINK_PREPARE_DELAY_MS)
            Timber.i("Wi-Fi Direct initialized for Miracast sink (api=${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            frameworkStarted = false
            RuntimeStateMiracast.report("P2P_INITIALIZE_FAILED")
            Timber.e(e, "Failed to start Wi-Fi Direct")
        }
    }

    /**
     * One capability read that settles which P2P role this hardware is allowed to take. On a box
     * that cannot be a Group Owner there is nothing to gain from `createGroup()`, and on one that
     * cannot run STA and P2P concurrently the group can never come up while the box is on Wi-Fi —
     * which is the shipping configuration of exactly these TVs. Either answer means the sink's job
     * is to advertise and wait for the Source rather than to build its own network.
     *
     * `requestP2pInfo` is @hide and its callback type is @hide too, so both are reached through
     * reflection the way the other hidden P2P setters here are. The value is read for logging only,
     * so it is tolerated when the query is refused outright.
     */
    private fun logP2pCapability(p2p: WifiP2pManager) {
        val ch = channel ?: return
        if (p2pCapabilityLogged) return
        p2pCapabilityLogged = true
        try {
            val listenerType = Class.forName("android.net.wifi.p2p.ActionListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
                InvocationHandler { _, method, args ->
                    if (method.name != "onResult") return@InvocationHandler Unit
                    val info = args?.firstOrNull() ?: return@InvocationHandler Unit
                    val supportsGo = fieldOf(info, "supportsGroupOwner")
                    val supportsConcurrent = fieldOf(info, "supportsConcurrentConnections")
                    Timber.i(
                        "P2P capabilities: supportsGroupOwner=$supportsGo " +
                            "supportsConcurrentConnections=$supportsConcurrent " +
                            "maxGroupSessions=${fieldOf(info, "maxGroupSessions")} " +
                            "supportsPersistentGroup=${fieldOf(info, "supportsPersistentGroup")}"
                    )
                    if (supportsGo == false) {
                        Timber.w(
                            "P2P: this device cannot be a Group Owner, so it serves whatever group " +
                                "the Source forms instead of creating one of its own"
                        )
                    }
                    if (supportsConcurrent == false) {
                        Timber.w(
                            "P2P: STA + P2P concurrency unsupported, so while the box stays on its " +
                                "Wi-Fi network no group can form through the framework — the vendor " +
                                "stack has to own it"
                        )
                    }
                }
            )
            p2p::class.java.getMethod(
                "requestP2pInfo", WifiP2pManager.Channel::class.java, listenerType
            ).invoke(p2p, ch, listener)
        } catch (e: Exception) {
            p2pCapabilityLogged = false
            Timber.d("P2P capability unavailable: ${e.message}")
        }
    }

    /** Read one public field by name from a reflective result, tolerating a missing field. */
    private fun fieldOf(target: Any, name: String): Any? = runCatching {
        target.javaClass.getField(name).get(target)
    }.getOrElse { "?" }

    /**
     * The part that makes this device show up in the Source's device list. Runs the root work off
     * the main thread, then forms the Group Owner group on it.
     */
    private fun prepareSink() {
        Thread({
            val rootAdvertised = runCatching { WfdRootHelper.advertiseSink(appContext, force = true) }
                .onFailure { Timber.w(it, "WFD root advertisement failed") }
                .getOrDefault(false)
            val goIntent = runCatching { WfdRootHelper.configureGroupOwnerIntent(appContext) }
                .getOrDefault(false)
            mainHandler.post {
                if (!isStarted) return@post
                WfdRootHelper.startKeepAlive(appContext)
                RuntimeStateMiracast.report(
                    "WFD_" + if (rootAdvertised && goIntent) "ADVERTISED"
                    else if (rootAdvertised) "ADVERTISED_GO_INTENT_MISSING"
                    else "ADVERTISE_FAILED"
                )
                Timber.i(
                    "Miracast sink advertisement ready (rootWfd=$rootAdvertised goIntent=$goIntent, " +
                        "verified=${WfdRootHelper.advertisementStatus().verified}, " +
                        "socket=${WfdRootHelper.advertisementStatus().socketPath})"
                )
                ensureSinkGroup()
            }
        }, "wfd-advertise").apply { isDaemon = true }.start()
    }

    /**
     * Reuse an existing group, or create one so this device becomes the Group Owner. Without this
     * there is no group interface, no G/O beacon, and no WFD advertisement on the air.
     */
    private fun ensureSinkGroup() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { existing ->
                if (!isStarted) return@requestGroupInfo
                when {
                    existing == null -> createSinkGroup()

                    existing.isGroupOwner -> {
                        Timber.i("P2P group already exists: ${existing.networkName}; sinkIsOwner=true")
                        // We own it, so the keep-alive should not create another.
                        WfdRootHelper.setKeepAliveGroupFormation(false)
                        onGroupReady(existing)
                    }

                    existing.clientList.isNotEmpty() -> {
                        // A Source is attached and it owns the group. That is a legal WFD topology
                        // and tearing the group down would drop the client that is there to cast,
                        // so this sink serves it instead.
                        Timber.i("P2P group exists owned by the Source: ${existing.networkName}; serving it")
                        onGroupReady(existing)
                    }

                    else -> {
                        // A group exists that this sink does not own and nobody is attached to. In
                        // practice that is a Source that won the Group Owner negotiation and formed
                        // its own group, leaving this device with a beacon and no network behind it.
                        // The Source cannot reach an RTSP server that is not on the network, so a
                        // connection attempt dies here. Creating a group of our own is what turns
                        // that into a joinable sink.
                        Timber.i(
                            "P2P group ${existing.networkName} exists but is not ours and has no " +
                                "clients; creating a Group Owner group of our own"
                        )
                        createSinkGroup()
                    }
                }
                WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                startPeerDiscovery()
            }
        } catch (e: SecurityException) {
            Timber.w("Cannot inspect P2P topology; NEARBY_WIFI_DEVICES not granted on API 33+")
            RuntimeStateMiracast.report("P2P_PERMISSION_DENIED")
        } catch (e: Exception) {
            Timber.w(e, "Unable to inspect P2P topology")
            startPeerDiscovery()
        }
    }

    private fun createSinkGroup() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        if (frameworkGroupFormationBroken) {
            // Already proven dead for this process. The vendor stack owns group formation here,
            // and the Source's own connect request is the thing that forms the group.
            return
        }
        Timber.i("Creating the P2P group so this device acts as the Miracast sink Group Owner")
        try {
            p2p.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isStarted) return
                    sinkGroupAttempted = true
                    // The framework owns the group, so the keep-alive must not fight it.
                    WfdRootHelper.setKeepAliveGroupFormation(false)
                    Timber.i("Sink Group Owner formed; re-injecting the WFD IE into the G/O beacon")
                    WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                    p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                }

                override fun onFailure(reason: Int) {
                    if (!isStarted) return
                    when (reason) {
                        WifiP2pManager.BUSY -> mainHandler.postDelayed({ if (isStarted) createSinkGroup() }, 1_500L)
                        else -> {
                            sinkGroupAttempted = true
                            frameworkGroupFormationBroken = true
                            RuntimeStateMiracast.report("SINK_GROUP_FAILED_${reasonText(reason)}")
                            Timber.w(
                                "Sink createGroup failed: ${reasonText(reason)}; the framework P2P " +
                                    "control surface is unavailable for this process, so group " +
                                    "formation is left to the vendor stack and to the Source's own " +
                                    "connect request"
                            )
                            // The framework path is not the only one, and it fails outright on many
                            // vendor builds. Root can create the group through wpa_supplicant
                            // regardless, which is the whole point: without a group there is no
                            // G/O beacon to carry the WFD IE in at all.
                            scheduleFallbackGroupFormation()
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            sinkGroupAttempted = true
            RuntimeStateMiracast.report("SINK_GROUP_PERMISSION_DENIED")
            Timber.w("createGroup denied; grant NEARBY_WIFI_DEVICES so the sink can become Group Owner")
            scheduleFallbackGroupFormation()
        } catch (e: Exception) {
            sinkGroupAttempted = true
            RuntimeStateMiracast.report("SINK_GROUP_FAILED")
            Timber.w(e, "Unable to create the sink P2P group")
            scheduleFallbackGroupFormation()
        }
    }

    /**
     * Root work must not run on the main thread — a hung `su` would freeze the UI — and the result
     * is only meaningful once the attempt has actually finished, so it is scheduled with a delay
     * and then handed to a worker.
     */
    private fun scheduleFallbackGroupFormation() {
        mainHandler.postDelayed({
            if (!isStarted) return@postDelayed
            Thread({ formSinkGroupFallback() }, "wfd-fallback-group")
                .apply { isDaemon = true; start() }
        }, 500L)
    }

    /**
     * Root-level group formation, reached only when the framework refuses to create one. On success
     * the WFD IE is re-injected immediately — it has to land in the G/O beacon, not just in
     * p2p-dev-*, or a Source still sees nothing to connect to. Whichever way it goes, the
     * keep-alive takes ownership of re-forming a dropped group from here on.
     */
    private fun formSinkGroupFallback() {
        val formed = runCatching { WfdRootHelper.formSinkGroup(appContext) }
            .onFailure { Timber.w(it, "Root group formation threw") }
            .getOrDefault(false)
        if (!isStarted) return

        WfdRootHelper.setKeepAliveGroupFormation(true)
        RuntimeStateMiracast.report(if (formed) "SINK_GROUP_FORMED_ROOT" else "SINK_GROUP_FAILED")
        Timber.i(
            "Miracast sink group ${if (formed) "formed through wpa_supplicant" else "not formed"} " +
                "(${WfdRootHelper.groupFormationDetail()})"
        )
        if (formed) {
            WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
            p2pManager()?.let { p2p ->
                val ch = channel ?: return@let
                runCatching { p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) } }
            }
        }
    }

    /** Best effort hidden API. Privileged/root-assisted builds may be allowed; ordinary APKs fall back safely. */
    private fun setMiracastMode(mode: Int, label: String) {
        val p2p = p2pManager() ?: return
        try {
            val method = WifiP2pManager::class.java.getMethod("setMiracastMode", Int::class.javaPrimitiveType!!)
            method.invoke(p2p, mode)
            Timber.i("Framework Miracast mode set to $label ($mode)")
        } catch (e: SecurityException) {
            Timber.d("Framework setMiracastMode($label) denied; supplicant WFD mode remains authoritative")
        } catch (e: ReflectiveOperationException) {
            Timber.d("Framework setMiracastMode unavailable: ${e.message}")
        } catch (e: Exception) {
            Timber.d("Framework setMiracastMode($label) failed: ${e.message}")
        }
    }

    /**
     * Push the Wi-Fi Display sink capability through the Android framework.
     *
     * Framework-API coverage across Android 6.0 (API 23) through Android 17 (API 37):
     * - API 23–29: only the hidden `setWFDInfo(Channel, WifiP2pWfdInfo, ActionListener)` exists.
     * - API 30–37: a `setWfdInfo` @SystemApi alias was added next to the hidden `setWFDInfo`
     *   (@UnsupportedAppUsage maxTargetSdk = R), so from Android 12 the hidden name is blocked by
     *   hidden-API enforcement for apps targeting S+ while the SystemApi name still resolves.
     * - Every version requires the signature-level CONFIGURE_WIFI_DISPLAY permission inside
     *   WifiP2pServiceImpl, so non-privileged apps are rejected at runtime regardless of name.
     *   Both candidates are therefore tried in turn and any rejection falls back to the
     *   root/supplicant injection in [WfdRootHelper], which remains the authoritative path.
     */
    private fun setWfdInfo() {
        val ch = channel ?: return
        val p2p = p2pManager() ?: return
        val api = Build.VERSION.SDK_INT
        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = createWfdInfo(wfdInfoClass)
            wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.java).invoke(wfdInfo, true)
            wfdInfoClass.getMethod("setDeviceType", Int::class.java).invoke(wfdInfo, 1) // primary sink
            wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.java).invoke(wfdInfo, true)
            wfdInfoClass.getMethod("setControlPort", Int::class.java).invoke(wfdInfo, 7236)
            wfdInfoClass.getMethod("setMaxThroughput", Int::class.java).invoke(wfdInfo, 50)
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("WFD Info set through Android framework")
                    WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                }
                override fun onFailure(reason: Int) {
                    Timber.w("Framework setWFDInfo failed: ${reasonText(reason)}; using root fallback")
                    WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                }
            }
            var lastFailure: Exception? = null
            for (name in WFD_METHOD_CANDIDATES) {
                val method = try {
                    WifiP2pManager::class.java.getMethod(
                        name,
                        WifiP2pManager.Channel::class.java,
                        wfdInfoClass,
                        WifiP2pManager.ActionListener::class.java
                    )
                } catch (e: NoSuchMethodException) {
                    Timber.d("Framework WFD API $name not resolvable on API $api: ${e.message}")
                    lastFailure = e
                    continue
                }
                try {
                    method.invoke(p2p, ch, wfdInfo, listener)
                    Timber.i("Framework WFD info submitted via $name (API $api)")
                    return
                } catch (e: InvocationTargetException) {
                    Timber.d(
                        "Framework $name rejected on API $api (CONFIGURE_WIFI_DISPLAY is signature-only): " +
                            "${e.cause?.message ?: e.message}"
                    )
                    lastFailure = e
                }
            }
            throw (lastFailure ?: IllegalStateException("no framework WFD setter available on API $api"))
        } catch (e: Exception) {
            Timber.i("Framework WFD API unavailable on API $api (expected for non-privileged apps): ${e.message}")
        }
    }

    /** API 23–29 ships only `WifiP2pWfdInfo()`; API 30+ adds the `(deviceType, port, throughput)` ctor. */
    private fun createWfdInfo(wfdInfoClass: Class<*>): Any = try {
        wfdInfoClass.getDeclaredConstructor().newInstance()
    } catch (e: Exception) {
        wfdInfoClass.getDeclaredConstructor(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
            .newInstance(1, 7236, 50)
    }

    /**
     * The group already announced, keyed on identity rather than content. Re-announcing a group
     * that has not changed re-injects the WFD element and churns the supplicant every poll tick —
     * on this hardware that load lands squarely on top of a Source's in-flight handshake.
     */
    @Volatile
    private var announcedGroupKey: String? = null

    /** Group clients already reported, so a stable membership does not re-fire connect events. */
    private val announcedClients = linkedSetOf<String>()

    private fun onGroupReady(group: WifiP2pGroup) {
        Timber.i("P2P group ready: ${group.networkName}; sinkIsOwner=${group.isGroupOwner}; clients=${group.clientList.size}")
        val key = "${group.networkName}|${group.isGroupOwner}"
        val freshGroup = key != announcedGroupKey
        if (freshGroup) {
            announcedGroupKey = key
            announcedClients.clear()
            onGroupCreated?.invoke(group)
            setDeviceName(deviceName)
            // The freshly formed group's beacon does not carry the WFD element yet, so the
            // injection has to be forced. On an unchanged group this is pure supplicant churn.
            WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
        }

        group.clientList.forEach { client ->
            if (announcedClients.add(client.deviceAddress)) {
                rememberSourceDevice(client, "group-client")
                onDeviceConnected?.invoke(client)
            }
        }
    }

    private fun rememberSourceDevice(device: WifiP2pDevice, reason: String) {
        val port = extractWfdControlPort(device)
        Timber.i("Miracast peer: name=${device.deviceName} address=${device.deviceAddress} status=${device.status} controlPort=${port ?: "unknown"}")
        if (port != null) WfdSourceHint.update(controlPort = port, reason = reason)
    }

    private fun extractWfdControlPort(device: WifiP2pDevice): Int? = runCatching {
        val info = runCatching {
            device.javaClass.methods.firstOrNull { it.name == "getWfdInfo" && it.parameterCount == 0 }?.invoke(device)
        }.getOrNull() ?: runCatching {
            device.javaClass.declaredFields.firstOrNull { it.name == "wfdInfo" }
                ?.apply { isAccessible = true }?.get(device)
        }.getOrNull() ?: return@runCatching null
        val port = info.javaClass.methods.firstOrNull { it.name == "getControlPort" && it.parameterCount == 0 }
            ?.invoke(info) as? Number
        port?.toInt()?.takeIf { it in 1..65535 }
    }.getOrNull()

    /**
     * The Source lists the sink under its P2P device name, so this has to happen before the
     * advertisement is refreshed, not after a group is already up.
     */
    private fun setDeviceName(name: String) {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName", WifiP2pManager.Channel::class.java, String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(p2p, ch, name, emptyActionListener("set device name"))
        } catch (e: Exception) {
            Timber.d("P2P device-name override unavailable: ${e.message}")
        }
    }

    private fun registerLocalService() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            val record = mapOf("version" to "1.0", "type" to "miracast-sink", "rtsp_port" to "7236")
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_miracast", "_tcp", record)
            p2p.addLocalService(ch, serviceInfo, emptyActionListener("register Miracast DNS-SD hint"))
        } catch (e: Exception) {
            Timber.d("P2P local-service hint unavailable: ${e.message}")
        }
    }

    private fun startPeerDiscovery() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        if (!p2pPermissionsGranted()) return
        if (frameworkGroupFormationBroken) {
            // A sink is the party that gets discovered, so this is belt and braces anyway — and on
            // the builds where createGroup() is refused, discoverPeers() is refused with the same
            // ERROR and there is nothing left to be gained from asking.
            return
        }
        try {
            p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discoveryRetryCount = 0
                    Timber.i("P2P peer discovery/listen cycle active")
                }
                override fun onFailure(reason: Int) {
                    Timber.w("P2P discoverPeers failed: ${reasonText(reason)}")
                    if (reason == WifiP2pManager.BUSY && discoveryRetryCount < 3 && isStarted) {
                        discoveryRetryCount++
                        mainHandler.postDelayed({ if (isStarted) startPeerDiscovery() }, 1_500L)
                    }
                }
            })
        } catch (e: SecurityException) {
            Timber.w("P2P discoverPeers denied; NEARBY_WIFI_DEVICES not granted")
        } catch (e: Exception) {
            Timber.w(e, "Unable to start P2P discovery")
        }
    }

    private fun p2pPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestP2pPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        // `NEARBY_WIFI_DEVICES` is enough for the scan APIs, but the Wi-Fi service also checks a
        // location permission before it delivers P2P connection broadcasts. Ask for both: a sink
        // holding only scan permission is listable to every source yet never sees its own group
        // come up.
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        Timber.w("P2P scan permissions missing, requesting ${requested.joinToString()}")
        // Context.requestPermissions is not in the compileSdk's android.jar, so invoke it the same
        // way the hidden WFD setter below does. A non-Activity context can only surface the dialog
        // while the app is in the foreground; otherwise the user grants it in Settings and the next
        // start picks it up, since the whole scan path is retried on every prepareSink().
        runCatching {
            Context::class.java.getMethod(
                "requestPermissions", Array<String>::class.java, Int::class.javaPrimitiveType!!
            ).invoke(appContext, requested, 1001)
        }.onFailure {
            Timber.w(
                it,
                "P2P permissions not requestable from here; grant ${requested.joinToString()} " +
                    "in Settings > Apps > Miracast Receiver > Nearby devices, then restart the receiver"
            )
        }
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Timber.i("Wi-Fi P2P state: ${if (enabled) "ENABLED" else "DISABLED"}")
                        if (enabled && isStarted) {
                            sinkGroupAttempted = false
                            mainHandler.postDelayed({ if (isStarted) prepareSink() }, SINK_PREPARE_DELAY_MS)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersForDiagnostics()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnectionState()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> if (isStarted) {
                        WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                    }
                }
            }
        }
        // These actions are dispatched by the framework (system_server), not by our own UID. On
        // Android 13 a NOT_EXPORTED context receiver only accepts broadcasts from the same UID, so
        // it is refused and the Sink goes completely blind to group formation, connection changes
        // and peer events — every topology decision in this class silently never runs.
        runCatching { ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onSuccess { Timber.i("Wi-Fi P2P event receiver registered") }
            .onFailure { receiver = null; Timber.w(it, "Unable to register P2P receiver") }
    }

    private fun requestPeersForDiagnostics() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestPeers(ch) { peers ->
                peers.deviceList.sortedBy { if (it.status == WifiP2pDevice.CONNECTED) 0 else 1 }
                    .forEach { rememberSourceDevice(it, "peer-discovery") }
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshConnectionState() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestConnectionInfo(ch) { info ->
                if (info.groupFormed) {
                    val goIp = info.groupOwnerAddress?.hostAddress
                    if (!info.isGroupOwner && !goIp.isNullOrBlank()) {
                        Timber.i("Miracast topology: Source is GO at $goIp; using exact RTSP endpoint")
                        WfdSourceHint.update(ipAddress = goIp, reason = "source-group-owner")
                    } else if (info.isGroupOwner) {
                        Timber.i("Miracast topology: Sink is GO (standard Android Source-compatible); locating Source client RTSP endpoint")
                    }
                    p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                } else {
                    WfdSourceHint.clear()
                    // A torn-down group will be formed again, so it must count as fresh a second time.
                    announcedGroupKey = null
                    announcedClients.clear()
                    onDeviceDisconnected?.invoke()
                    if (isStarted) {
                        WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
                        if (!sinkGroupAttempted) ensureSinkGroup()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d("requestConnectionInfo unavailable: ${e.message}")
        }
    }

    /**
     * Same state lookup as the broadcast path, driven by a timer instead of
     * `WIFI_P2P_CONNECTION_CHANGED_ACTION`. Two reasons the poll exists:
     *
     *  - The Wi-Fi service dispatches that broadcast only to receivers it thinks are allowed to
     *    observe location, and on vendor builds the underlying location appop is capped at
     *    foreground-only. This app is usually backgrounded on a TV box, so the broadcast is
     *    dropped and the sink is blind to a Source that has in fact connected.
     *  - A dropped broadcast is indistinguishable from "no change", so without this the failure is
     *    silent in exactly the case a user reports as "it shows up but won't connect".
     *
     * The in-flight guard keeps a slow response from stacking requests behind each other.
     */
    private fun pollConnectionState() {
        if (!isStarted) return
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        // Without NEARBY_WIFI_DEVICES the request would only throw SecurityException. Keep ticking
        // so the poll starts working on its own once root's `pm grant` takes effect.
        if (!p2pPermissionsGranted()) {
            mainHandler.postDelayed({ pollConnectionState() }, CONNECTION_POLL_MS)
            return
        }
        if (connectionPollInFlight) {
            mainHandler.postDelayed({ pollConnectionState() }, CONNECTION_POLL_MS)
            return
        }
        connectionPollInFlight = true
        try {
            p2p.requestConnectionInfo(ch) { info ->
                connectionPollInFlight = false
                val topology = if (info.groupFormed) {
                    "grouped:${info.isGroupOwner}:${info.groupOwnerAddress?.hostAddress}"
                } else {
                    "no-group"
                }
                if (topology != lastPollTopology) {
                    lastPollTopology = topology
                    if (info.groupFormed) {
                        Timber.i(
                            "P2P poll saw a group the broadcast had not reported: " +
                                "sinkIsOwner=${info.isGroupOwner} " +
                                "go=${info.groupOwnerAddress?.hostAddress ?: "-"}"
                        )
                        refreshConnectionState()
                    } else if (!sinkGroupAttempted) {
                        // No group yet is also a state worth detecting by poll: the sink owns
                        // creating it, so do not wait for a broadcast about a group nobody made.
                        ensureSinkGroup()
                    }
                }
                if (isStarted) mainHandler.postDelayed({ pollConnectionState() }, CONNECTION_POLL_MS)
            }
        } catch (e: Exception) {
            connectionPollInFlight = false
            Timber.d("P2P poll skipped: ${e.message}")
            if (isStarted) mainHandler.postDelayed({ pollConnectionState() }, CONNECTION_POLL_MS)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        frameworkStarted = false
        discoveryRetryCount = 0
        sinkGroupAttempted = false
        mainHandler.removeCallbacksAndMessages(null)
        setMiracastMode(0, "DISABLED")

        val p2p = p2pManager()
        val ch = channel
        if (p2p != null && ch != null) {
            runCatching { p2p.stopPeerDiscovery(ch, emptyActionListener("stop discovery")) }
            runCatching { p2p.clearLocalServices(ch, emptyActionListener("clear local services")) }
            runCatching { p2p.removeGroup(ch, emptyActionListener("remove group")) }
        }
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        channel = null
        WfdRootHelper.stopKeepAlive()
        WfdRootHelper.stopAdvertising(appContext)
        RuntimeStateMiracast.report("STOPPED")
        Timber.i("Wi-Fi Direct stopped")
    }

    fun isRunning(): Boolean = isStarted
    private fun p2pManager(): WifiP2pManager? = manager

    private fun emptyActionListener(operation: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Timber.d("P2P $operation succeeded")
        override fun onFailure(reason: Int) = Timber.d("P2P $operation failed: ${reasonText(reason)}")
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "UNKNOWN($reason)"
    }
}

/**
 * Minimal adapter so [WifiDirectManager] does not have to depend on the WebUI runtime state just to
 * make its failure modes visible. The value is a stable, greppable token shown on the diagnostics
 * page, which is the only way to tell "the sink is not advertising" apart from "it is and the
 * Source is filtering it".
 */
private object RuntimeStateMiracast {
    fun report(token: String) {
        com.weekd.miracastreceiver.web.RuntimeState.miracastAdvertisement = token
    }
}
