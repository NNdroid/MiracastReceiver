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

/**
 * Wi-Fi Direct manager used by the Miracast sink.
 *
 * Miracast sources discover sinks from the WFD IE carried by P2P discovery frames. A rooted TV
 * therefore prepares the WFD IE and Extended Listen state before the framework creates/restores
 * the P2P group. Framework peer discovery is also kept active as a non-root/system-app fallback.
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

        // Do root/supplicant work off the main thread. Some SELinux configurations do not return
        // a reply to the control socket, and waiting here used to stall app/service startup.
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

            // System/privileged builds can set this through the framework. Normal APKs will fail
            // and use the root-assisted supplicant path prepared above.
            setWfdInfo()

            registerLocalService()
            createOrReuseGroup()
            startPeerDiscovery()

            if (!rootAdvertised) {
                WfdRootHelper.refreshAdvertisingAsync(appContext)
            }
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
            wfdInfoClass.getMethod("setDeviceType", Int::class.java).invoke(wfdInfo, 1) // PRIMARY_SINK
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

    private fun createOrReuseGroup() {
        val p2p = p2pManager() ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { existing ->
                if (!isStarted) return@requestGroupInfo
                if (existing != null) {
                    Timber.i("Reusing P2P group ${existing.networkName}; owner=${existing.isGroupOwner}")
                    onGroupReady(existing)
                    return@requestGroupInfo
                }
                createGroup(p2p, ch)
            }
        } catch (e: SecurityException) {
            Timber.w("Cannot query P2P group; missing nearby/location permission")
        } catch (e: Exception) {
            Timber.w(e, "Unable to query P2P group")
            createGroup(p2p, ch)
        }
    }

    private fun createGroup(p2p: WifiP2pManager, ch: WifiP2pManager.Channel) {
        try {
            p2p.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct group created")
                    p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                }

                override fun onFailure(reason: Int) {
                    Timber.w("P2P createGroup failed: ${reasonText(reason)}")
                    if (reason == WifiP2pManager.BUSY) {
                        p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                    }
                    // Even without an autonomous GO, stay discoverable so a source can negotiate.
                    startPeerDiscovery()
                    WfdRootHelper.refreshAdvertisingAsync(appContext)
                }
            })
        } catch (e: SecurityException) {
            Timber.w("Cannot create P2P group; missing nearby/location permission")
        } catch (e: Exception) {
            Timber.w(e, "Exception while creating P2P group")
        }
    }

    private fun onGroupReady(group: WifiP2pGroup) {
        Timber.i("P2P group ready: ${group.networkName}; owner=${group.isGroupOwner}")
        onGroupCreated?.invoke(group)
        setDeviceName(deviceName)
        WfdRootHelper.refreshAdvertisingAsync(appContext)
        group.clientList.firstOrNull()?.let { onDeviceConnected?.invoke(it) }
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

    /**
     * discoverPeers() is not the Miracast discovery mechanism itself, but it makes the framework
     * P2P state machine cycle through search/listen states. Rooted devices additionally use
     * P2P_EXT_LISTEN, which is the reliable sink-discoverability path.
     */
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
            p2p.requestPeers(ch) { peers -> Timber.d("P2P peers visible to sink: ${peers.deviceList.size}") }
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
                    Timber.i("P2P connected; owner=${info.isGroupOwner}; GO=${info.groupOwnerAddress?.hostAddress}")
                    p2p.requestGroupInfo(ch) { group -> if (group != null) onGroupReady(group) }
                } else {
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
