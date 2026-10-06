package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber
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
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false
    private var frameworkStarted = false
    private var discoveryRetryCount = 0
    private var sinkGroupAttempted = false

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
        WfdSourceHint.clear()

        Thread({
            // requestPermissions is @MainThread, so the permission probe happens here and the
            // actual request on the main handler.
            val granted = p2pPermissionsGranted()
            if (isStarted) {
                if (!granted) mainHandler.post { requestP2pPermissions() }
                mainHandler.post { startFrameworkP2p(p2p) }
            }
        }, "wfd-prepare").apply { isDaemon = true }.start()
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
            registerReceiver()
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
     * there is no p2p0, no G/O beacon, and no WFD advertisement on the air.
     */
    private fun ensureSinkGroup() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { existing ->
                if (!isStarted) return@requestGroupInfo
                if (existing != null) {
                    Timber.i("P2P group already exists: ${existing.networkName}; sinkIsOwner=${existing.isGroupOwner}")
                    onGroupReady(existing)
                } else {
                    createSinkGroup()
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
        Timber.i("Creating the P2P group so this device acts as the Miracast sink Group Owner")
        try {
            p2p.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isStarted) return
                    sinkGroupAttempted = true
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
                            RuntimeStateMiracast.report("SINK_GROUP_FAILED_${reasonText(reason)}")
                            Timber.w("Sink createGroup failed: ${reasonText(reason)}")
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            sinkGroupAttempted = true
            RuntimeStateMiracast.report("SINK_GROUP_PERMISSION_DENIED")
            Timber.w("createGroup denied; grant NEARBY_WIFI_DEVICES so the sink can become Group Owner")
        } catch (e: Exception) {
            sinkGroupAttempted = true
            RuntimeStateMiracast.report("SINK_GROUP_FAILED")
            Timber.w(e, "Unable to create the sink P2P group")
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

    private fun onGroupReady(group: WifiP2pGroup) {
        Timber.i("P2P group ready: ${group.networkName}; sinkIsOwner=${group.isGroupOwner}; clients=${group.clientList.size}")
        onGroupCreated?.invoke(group)
        setDeviceName(deviceName)
        WfdRootHelper.refreshAdvertisingAsync(appContext, force = true)
        group.clientList.forEach { rememberSourceDevice(it, "group-client") }
        group.clientList.firstOrNull()?.let { onDeviceConnected?.invoke(it) }
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
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        Timber.w("P2P scan permissions missing, requesting ${requested.joinToString()}")
        runCatching { appContext.requestPermissions(requested, 1001) }
            .onFailure { Timber.d(it, "Unable to request P2P permissions from the service") }
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
        runCatching { ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
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
