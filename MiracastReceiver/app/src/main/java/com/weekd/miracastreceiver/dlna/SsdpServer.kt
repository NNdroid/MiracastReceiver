package com.weekd.miracastreceiver.dlna

import android.content.Context
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/** SSDP server with IPv4 and IPv6 multicast support. */
class SsdpServer(
    private val context: Context,
    private val deviceUuid: String,
    private val localIp: String,
    private val httpPort: Int = 8080
) {
    companion object {
        private const val SSDP_IPV4 = "239.255.255.250"
        private const val SSDP_IPV6 = "FF02::C"
        private const val SSDP_PORT = 1900
        private const val SSDP_SEARCH_PATTERN = "M-SEARCH"
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SERVICE_TYPE_AV = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SERVICE_TYPE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SERVICE_TYPE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private var multicastSocket: MulticastSocket? = null
    private var serverJob: Job? = null
    private var notifyJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SSDP_PORT))
                }
                multicastSocket = socket
                joinGroupBestEffort(socket, SSDP_IPV4)
                joinGroupBestEffort(socket, SSDP_IPV6)
                Timber.i("SSDP server started dual-stack on port $SSDP_PORT")

                val buffer = ByteArray(2048)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message.contains(SSDP_SEARCH_PATTERN, ignoreCase = true)) {
                        handleSearchRequest(message, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                if (multicastSocket?.isClosed != true) Timber.e(e, "SSDP server error")
            }
        }
        startPeriodicNotify()
    }

    fun stop() {
        runCatching { sendByebye() }
        notifyJob?.cancel()
        serverJob?.cancel()
        runCatching { multicastSocket?.close() }
        multicastSocket = null
        Timber.i("SSDP server stopped")
    }

    private fun joinGroupBestEffort(socket: MulticastSocket, literal: String) {
        val group = InetAddress.getByName(literal)
        var joined = false
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
        for (iface in interfaces) {
            runCatching {
                socket.joinGroup(InetSocketAddress(group, SSDP_PORT), iface)
                joined = true
                Timber.d("Joined SSDP group $literal on ${iface.name}")
            }
        }
        if (!joined) {
            runCatching { socket.joinGroup(InetSocketAddress(group, SSDP_PORT), null) }
                .onSuccess { Timber.d("Joined SSDP group $literal on default interface") }
                .onFailure { Timber.w(it, "Unable to join SSDP group $literal") }
        }
    }

    private fun handleSearchRequest(message: String, address: InetAddress, port: Int) {
        val searchTarget = extractSearchTarget(message) ?: return
        val shouldRespond = searchTarget == "ssdp:all" ||
            searchTarget == "upnp:rootdevice" ||
            searchTarget.contains("MediaRenderer") ||
            searchTarget.contains("AVTransport") ||
            searchTarget.contains("RenderingControl") ||
            searchTarget.contains("ConnectionManager") ||
            searchTarget == "uuid:$deviceUuid"
        if (shouldRespond) sendSearchResponse(address, port, searchTarget)
    }

    private fun extractSearchTarget(message: String): String? =
        message.split("\r\n").firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    private fun locationFor(remote: InetAddress?): String {
        val addresses = NetworkUtils.getLanAddresses()
        val address = if (remote is Inet6Address) addresses.ipv6 ?: addresses.ipv4 ?: localIp
        else addresses.ipv4 ?: addresses.ipv6 ?: localIp
        return NetworkUtils.buildHttpUrl(address, httpPort, "/device.xml")
    }

    private fun sendSearchResponse(address: InetAddress, port: Int, searchTarget: String) {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: ${locationFor(address)}\r\n")
            append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
            append("ST: $searchTarget\r\n")
            append("USN: uuid:$deviceUuid")
            when {
                searchTarget == "upnp:rootdevice" -> append("::upnp:rootdevice")
                searchTarget.contains("device:") || searchTarget.contains("service:") -> append("::$searchTarget")
            }
            append("\r\n\r\n")
        }
        runCatching {
            DatagramSocket().use { socket ->
                val data = response.toByteArray()
                socket.send(DatagramPacket(data, data.size, address, port))
            }
        }.onFailure { Timber.e(it, "Error sending SSDP response") }
    }

    private fun startPeriodicNotify() {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            delay(2_000)
            while (isActive) {
                sendNotify()
                delay(30 * 60 * 1000L)
            }
        }
    }

    private fun notificationTypes() = listOf(
        "upnp:rootdevice", "uuid:$deviceUuid", DEVICE_TYPE,
        SERVICE_TYPE_AV, SERVICE_TYPE_RC, SERVICE_TYPE_CM
    )

    private fun sendNotify() {
        sendNotifications("ssdp:alive")
    }

    private fun sendByebye() {
        sendNotifications("ssdp:byebye")
    }

    private fun sendNotifications(nts: String) {
        val addresses = NetworkUtils.getLanAddresses()
        val targets = buildList {
            addresses.ipv4?.let { add(Triple(SSDP_IPV4, it, "$SSDP_IPV4:$SSDP_PORT")) }
            addresses.ipv6?.let { add(Triple(SSDP_IPV6, it, "[$SSDP_IPV6]:$SSDP_PORT")) }
        }
        for ((groupLiteral, localAddress, hostHeader) in targets) {
            runCatching {
                DatagramSocket().use { socket ->
                    val group = InetAddress.getByName(groupLiteral)
                    for (nt in notificationTypes()) {
                        val message = buildString {
                            append("NOTIFY * HTTP/1.1\r\n")
                            append("HOST: $hostHeader\r\n")
                            if (nts == "ssdp:alive") {
                                append("CACHE-CONTROL: max-age=1800\r\n")
                                append("LOCATION: ${NetworkUtils.buildHttpUrl(localAddress, httpPort, "/device.xml")}\r\n")
                            }
                            append("NT: $nt\r\n")
                            append("NTS: $nts\r\n")
                            if (nts == "ssdp:alive") append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
                            append("USN: uuid:$deviceUuid")
                            if (nt != "uuid:$deviceUuid") append("::$nt")
                            append("\r\n\r\n")
                        }
                        val data = message.toByteArray()
                        socket.send(DatagramPacket(data, data.size, group, SSDP_PORT))
                    }
                }
                Timber.i("Sent SSDP $nts via $groupLiteral")
            }.onFailure { Timber.w(it, "Unable to send SSDP $nts via $groupLiteral") }
        }
    }
}
