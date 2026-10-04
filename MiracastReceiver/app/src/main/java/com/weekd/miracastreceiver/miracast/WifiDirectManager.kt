package com.weekd.miracastreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/** Wi-Fi Direct control plane for the Miracast sink. */
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
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false
    private var frameworkStarted = false
    private var discoveryRetryCount = 0

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null
    var onGroupCreated: ((WifiP2pGroup) -> Unit)? = null

    fun start() {
        if (isStarted) return
        val p2p = manager ?: run {
            Timber.w("Wi-Fi Direct is unavailable; Miracast cannot be advertised")
            return
        }
        isStarted = true
        WfdSourceHint.clear()

        Thread({
            val rootAdvertised = runCatching { WfdRootHelper.advertiseSink(appContext) }
                .onFailure { Timber.w(it, "WFD root advertisement failed") }
                .getOrDefault(false)
            mainHandler.post {
                if (isStarted) startFrameworkP2p(p2p, rootAdvertised)
            }
        }, "wfd-prepare").apply { isDaemon = true }.start()
    }

    private fun startFrameworkP2p(p2p: WifiP2pManager, rootAdvertised: Boolean) {
        if (!isStarted || frameworkStarted) return
        try {
            channel = p2p.initialize(appContext, Looper.getMainLooper()) {
                Timber.w("Wi-Fi P2P channel disconnected; scheduling recovery")
                frameworkStarted = false
                channel = null
                if (isStarted) mainHandler.postDelayed({ startFrameworkP2p(p2p, rootAdvertised) }, 1_000L)
            }
            if (channel == null) return

            frameworkStarted = true
            registerReceiver()
            setWfdInfo()
            setMiracastMode(2, "SINK")
            registerLocalService()
            prepareCompatibleTopology()

            if (!rootAdvertised) WfdRootHelper.refreshAdvertisingAsync(appContext)
            // Vendor supplicants (and framework P2P restarts on Android 12–17) can drop the
            // injected WFD subelements; a slow periodic refresh keeps the sink discoverable.
            WfdRootHelper.startKeepAlive(appContext)
            Timber.i(
                "Wi-Fi Direct started for Miracast (api=${Build.VERSION.SDK_INT}, " +
                    "rootWfd=$rootAdvertised, topology=dual-role-sink-go-compatible)"
            )
        } catch (e: Exception) {
            frameworkStarted = false
            Timber.e(e, "Failed to start Wi-Fi Direct")
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
                    WfdRootHelper.refreshAdvertisingAsync(appContext)
                }
                override fun onFailure(reason: Int) {
                    Timber.w("Framework setWFDInfo failed: ${reasonText(reason)}; using root fallback")
                    WfdRootHelper.refreshAdvertisingAsync(appContext)
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
            WfdRootHelper.refreshAdvertisingAsync(appContext)
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
     * Preserve either valid P2P role. Android/AOSP Sources normally request the minimum GO intent,
     * therefore the receiver becoming GO is a standard topology, not an error. Some vendor Sources
     * still become GO, so the RTSP layer supports that topology as well.
     */
    private fun prepareCompatibleTopology() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { existing ->
                if (!isStarted) return@requestGroupInfo
                if (existing == null) {
                    Timber.i("P2P topology ready: accepting Sink-GO or Source-GO negotiation")
                } else {
                    Timber.i(
                        "Preserving P2P group ${existing.networkName}; sinkIsOwner=${existing.isGroupOwner}; " +
                            "clients=${existing.clientList.size}; both Miracast roles supported"
                    )
                    onGroupReady(existing)
                }
                WfdRootHelper.refreshAdvertisingAsync(appContext)
                startPeerDiscovery()
            }
        } catch (e: SecurityException) {
            Timber.w("Cannot query P2P group; missing nearby/location permission")
            startPeerDiscovery()
        } catch (e: Exception) {
            Timber.w(e, "Unable to prepare compatible Miracast P2P topology")
            startPeerDiscovery()
        }
    }

    private fun onGroupReady(group: WifiP2pGroup) {
        Timber.i("P2P group ready: ${group.networkName}; sinkIsOwner=${group.isGroupOwner}; clients=${group.clientList.size}")
        onGroupCreated?.invoke(group)
        setDeviceName(deviceName)
        WfdRootHelper.refreshAdvertisingAsync(appContext)
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
        } catch (e: Exception) {
            Timber.w(e, "Unable to start P2P discovery")
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
                            WfdRootHelper.refreshAdvertisingAsync(appContext)
                            prepareCompatibleTopology()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersForDiagnostics()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnectionState()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> if (isStarted) {
                        WfdRootHelper.refreshAdvertisingAsync(appContext)
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
                        WfdRootHelper.refreshAdvertisingAsync(appContext)
                        prepareCompatibleTopology()
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
