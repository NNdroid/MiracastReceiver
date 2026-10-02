package com.weekd.miracastreceiver.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** Network helpers for LAN discovery/casting. */
object NetworkUtils {

    @Volatile
    private var appContext: Context? = null

    private val secureRandom = SecureRandom()

    /** Initialize once from Application so no-context protocol code can resolve the active LAN. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }

    /**
     * Resolve the IPv4 address used by the real LAN (Ethernet/Wi-Fi), not whichever interface the
     * kernel happens to enumerate first. This is important while Miracast creates p2p0 and on TVs
     * that also run VPN/tun/WireGuard interfaces.
     */
    fun getLocalIpAddress(): String? {
        val context = appContext
        if (context != null) {
            activeLanAddress(context)?.let {
                Timber.d("Active LAN IP address: $it")
                return it
            }
        }

        return fallbackLanAddress()?.also { Timber.d("Fallback LAN IP address: $it") }
    }

    private fun activeLanAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        return try {
            val networks = mutableListOf<Network>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.activeNetwork?.let { networks += it }
            }
            cm.allNetworks.forEach { network ->
                if (network !in networks) networks += network
            }

            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network) ?: continue
                val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (!isLan || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

                val address = cm.getLinkProperties(network)
                    ?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { isUsableIpv4(it) }
                    ?.hostAddress
                if (!address.isNullOrBlank()) return address
            }
            null
        } catch (e: Exception) {
            Timber.w(e, "Unable to resolve active LAN address")
            null
        }
    }

    private fun fallbackLanAddress(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface ->
                runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) &&
                    !isExcludedInterface(iface.name)
            }
            .sortedWith(compareBy<NetworkInterface> { interfacePriority(it.name) }.thenBy { it.name })

        interfaces.firstNotNullOfOrNull { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { isUsableIpv4(it) }
                ?.hostAddress
        }
    } catch (e: Exception) {
        Timber.e(e, "Error getting local LAN IP address")
        null
    }

    private fun isUsableIpv4(address: Inet4Address): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress

    private fun isExcludedInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("p2p") ||
            n.startsWith("tun") ||
            n.startsWith("tap") ||
            n.startsWith("wg") ||
            n.startsWith("zt") ||
            n.startsWith("vti") ||
            n.startsWith("ipsec") ||
            n.startsWith("dummy") ||
            n.startsWith("clat") ||
            n.startsWith("rmnet")
    }

    private fun interfacePriority(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("eth") || n.startsWith("en") -> 0
            n.startsWith("wlan") || n.startsWith("wifi") -> 1
            else -> 10
        }
    }

    fun getWifiSSID(context: Context): String? {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid?.replace("\"", "")
        } catch (e: Exception) {
            Timber.e(e, "Error getting WiFi SSID")
            null
        }
    }

    fun generateConnectionCode(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString(6) {
            repeat(6) { append(chars[secureRandom.nextInt(chars.length)]) }
        }
    }

    /** Get a physical LAN MAC for AirPlay identity, avoiding p2p/VPN interfaces. */
    fun getMacAddress(): String {
        try {
            val preferredName = appContext?.let { activeLanInterfaceName(it) }
            val candidates = buildList {
                if (!preferredName.isNullOrBlank()) {
                    NetworkInterface.getByName(preferredName)?.let { add(it) }
                }
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { !isExcludedInterface(it.name) && it !in this }
                    .sortedWith(compareBy<NetworkInterface> { interfacePriority(it.name) }.thenBy { it.name })
                    .forEach { add(it) }
            }

            for (networkInterface in candidates) {
                if (runCatching { networkInterface.isLoopback || !networkInterface.isUp }.getOrDefault(true)) continue
                val mac = runCatching { networkInterface.hardwareAddress }.getOrNull()
                if (!mac.isNullOrEmpty()) {
                    val macAddress = mac.joinToString(":") {
                        String.format("%02X", it.toInt() and 0xFF)
                    }
                    Timber.d("Found LAN MAC address on ${networkInterface.name}: $macAddress")
                    return macAddress
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting LAN MAC address")
        }

        return generateRandomMacAddress()
    }

    private fun activeLanInterfaceName(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return try {
            val networks = mutableListOf<Network>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork?.let { networks += it }
            cm.allNetworks.forEach { if (it !in networks) networks += it }
            networks.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (!isLan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) null
                else cm.getLinkProperties(network)?.interfaceName
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun generateRandomMacAddress(): String {
        val mac = ByteArray(6)
        secureRandom.nextBytes(mac)
        mac[0] = ((mac[0].toInt() or 0x02) and 0xFE).toByte()
        return mac.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
    }
}
