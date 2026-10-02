package com.weekd.miracastreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber

/** Wi-Fi Direct manager used by the Miracast sink. */
class WifiDirectManager(
    private val context: Context,
    private val deviceName: String
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
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
        if (isStarted) {
            Timber.w("Wi-Fi Direct already started")
            return
        }
        val p2p = manager
        if (p2p == null) {
            Timber.w("Wi-Fi Direct is unavailable; Miracast cannot be advertised")
            return
        }

        isStarted = true
        WfdSourceHint.clear()

        // Supplicant control can block on some vendor ROMs, so keep it off the main thread.
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
            if (channel == null) {
                Timber.e("Failed to initialize Wi-Fi P2P channel")
                return
            }

            frameworkStarted = true
            registerReceiver()
            setWfdInfo()
            registerLocalService()

            // Do not create an autonomous GO here. Android/HyperOS Miracast sources are more
            // interoperable when they can perform normal P2P Group Negotiation. Reuse an already
            // active group, but remove an empty stale group left by a previous session.
            prepareForSourceNegotiation()

            if (!rootAdvertised) WfdRootHelper.refreshAdvertisingAsync(appContext)
            Timber.i("Wi-Fi Direct started for Miracast (rootWfd=$rootAdvertised)")
        } catch (e: Exception) {
            frameworkStarted = false
            Timber.e(e, "Failed to start Wi-Fi Direct")
        }
    }

    private fun setWfdInfo() {
        val ch = channel ?: return
        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.getDeclaredConstructor().newInstance()
            wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.java).invoke(wfdInfo, true)
            wfdInfoClass.getMethod("setDeviceType", Int::class.java).invoke(wfdInfo, 1)
            wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.java).invoke(wfdInfo, true)
            wfdInfoClass.getMethod("setControlPort", Int::class.java).invoke(wfdInfo, 7236)
            wfdInfoClass.getMethod("setMaxThroughput", Int::class.java).invoke(wfdInfo, 50)

            val method = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdInfoClass,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(p2pManager(), ch, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("WFD Info set through Android framework")
                    WfdRootHelper.refreshAdvertisingAsync(appContext)
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Framework setWFDInfo failed: ${reasonText(reason)}; using root fallback")
                    WfdRootHelper.refreshAdvertisingAsync(appContext)
                }
            })
        } catch (e: SecurityException) {
            Timber.d("Framework setWFDInfo denied; root-assisted WFD path will be used")
        } catch (e: ReflectiveOperationException) {
            Timber.d("Framework WFD API unavailable: ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "Unable to configure framework WFD info")
        }
    }

    private fun prepareForSourceNegotiation() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { group ->
                if (!isStarted) return@requestGroupInfo
                if (group != null && group.clientList.isNotEmpty()) {
                    Timber.i("Reusing active P2P group ${group.networkName}; owner=${group.isGroupOwner}")
                    onGroupReady(group)
                    startPeerDiscovery()
                } else if (group != null) {
                    Timber.i("Removing idle P2P group before Miracast source negotiation")
                    runCatching {
                        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Timber.i("Idle P2P group removed")
                                startPeerDiscovery()
                                WfdRootHelper.refreshAdvertisingAsync(appContext)
                            }

                            override fun onFailure(reason: Int) {
                                Timber.w("Unable to remove idle P2P group: ${reasonText(reason)}")
                                startPeerDiscovery()
                            }
                        })
                    }.onFailure {
                        Timber.w(it, "Exception removing idle P2P group")
                        startPeerDiscovery()
                    }
                } else {
                    startPeerDiscovery()
                }
            }
        } catch (e: SecurityException) {
            Timber.w("Cannot query P2P group; missing nearby/location permission")
            startPeerDiscovery()
        } catch (e: Exception) {
            Timber.w(e, "Unable to query P2P group")
            startPeerDiscovery()
        }
    }

    private fun onGroupReady(group: WifiP2pGroup) {
        Timber.i("P2P group ready: ${group.networkName}; owner=${group.isGroupOwner}; clients=${group.clientList.size}")
        onGroupCreated?.invoke(group)
        setDeviceName(deviceName)
        WfdRootHelper.refreshAdvertisingAsync(appContext)
        group.clientList.forEach { device ->
            rememberSourceDevice(device, "group-client")
        }
        group.clientList.firstOrNull()?.let { onDeviceConnected?.invoke(it) }
    }

    private fun rememberSourceDevice(device: WifiP2pDevice, reason: String) {
        val port = extractWfdControlPort(device)
        Timber.i(
            "Miracast peer: name=${device.deviceName} address=${device.deviceAddress} " +
                "status=${device.status} controlPort=${port ?: "unknown"}"
        )
        if (port != null) WfdSourceHint.update(controlPort = port, reason = reason)
    }

    /**
     * WifiP2pWfdInfo is hidden on many Android releases. Try both the accessor and backing field;
     * failure is harmless because the WFD default port 7236 remains a fallback.
     */
    private fun extractWfdControlPort(device: WifiP2pDevice): Int? {
        return runCatching {
            val info = runCatching {
                device.javaClass.methods
                    .firstOrNull { it.name == "getWfdInfo" && it.parameterCount == 0 }
                    ?.invoke(device)
            }.getOrNull() ?: runCatching {
                device.javaClass.declaredFields
                    .firstOrNull { it.name == "wfdInfo" }
                    ?.apply { isAccessible = true }
                    ?.get(device)
            }.getOrNull() ?: return@runCatching null

            val port = info.javaClass.methods
                .firstOrNull { it.name == "getControlPort" && it.parameterCount == 0 }
                ?.invoke(info) as? Number
            port?.toInt()?.takeIf { it in 1..65535 }
        }.onFailure {
            Timber.d("Unable to read peer WFD control port: ${it.message}")
        }.getOrNull()
    }

    private fun setDeviceName(name: String) {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(p2p, ch, name, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Timber.i("P2P device name set to $name")
                override fun onFailure(reason: Int) = Timber.d("P2P device-name override unavailable: $reason")
            })
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
            p2p.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Timber.d("Local Miracast DNS-SD hint registered")
                override fun onFailure(reason: Int) = Timber.d("Miracast DNS-SD hint registration failed: $reason")
            })
        } catch (e: SecurityException) {
            Timber.w("P2P local service denied; missing nearby/location permission")
        } catch (e: Exception) {
            Timber.w(e, "Unable to register P2P local service")
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
        } catch (e: SecurityException) {
            Timber.w("P2P discovery denied; grant Nearby devices/location permission")
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
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Timber.i("Wi-Fi P2P state: ${if (enabled) "ENABLED" else "DISABLED"}")
                        if (enabled && isStarted) {
                            WfdRootHelper.refreshAdvertisingAsync(appContext)
                            startPeerDiscovery()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersForDiagnostics()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnectionState()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Timber.d("Local P2P device: ${device?.deviceName}; status=${device?.status}")
                        if (isStarted) WfdRootHelper.refreshAdvertisingAsync(appContext)
                    }
                }
            }
        }

        runCatching {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            receiver = null
            Timber.w(it, "Unable to register P2P receiver")
        }
    }

    private fun requestPeersForDiagnostics() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestPeers(ch) { peers ->
                Timber.d("P2P peers visible to sink: ${peers.deviceList.size}")
                val likelySources = peers.deviceList.sortedBy { device ->
                    when (device.status) {
                        WifiP2pDevice.CONNECTED -> 0
                        WifiP2pDevice.INVITED -> 1
                        else -> 2
                    }
                }
                likelySources.forEach { rememberSourceDevice(it, "peer-discovery") }
            }
        } catch (e: SecurityException) {
            Timber.d("requestPeers denied by permission state")
        }
    }

    private fun refreshConnectionState() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestConnectionInfo(ch) { info ->
                if (info.groupFormed) {
                    val goIp = info.groupOwnerAddress?.hostAddress
                    Timber.i("P2P connected; sinkIsOwner=${info.isGroupOwner}; GO=$goIp")
                    // If the sink is not GO, the group owner is the Miracast Source and this is the
                    // exact address to dial. If the sink is GO, WfdServer will combine the learned
                    // control-port hint with a small /24 host scan.
                    if (!info.isGroupOwner && !goIp.isNullOrBlank()) {
                        WfdSourceHint.update(ipAddress = goIp, reason = "source-group-owner")
                    }
                    p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                } else {
                    WfdSourceHint.clear()
                    onDeviceDisconnected?.invoke()
                    if (isStarted) {
                        WfdRootHelper.refreshAdvertisingAsync(appContext)
                        startPeerDiscovery()
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.d("requestConnectionInfo denied by permission state")
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        frameworkStarted = false
        discoveryRetryCount = 0
        mainHandler.removeCallbacksAndMessages(null)

        val p2p = p2pManager()
        val ch = channel
        if (p2p != null && ch != null) {
            runCatching { p2p.stopPeerDiscovery(ch, emptyActionListener("stop discovery")) }
            runCatching { p2p.clearLocalServices(ch, emptyActionListener("clear local services")) }
            runCatching { p2p.removeGroup(ch, emptyActionListener("remove group")) }
        }

        WfdSourceHint.clear()
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        channel = null
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
