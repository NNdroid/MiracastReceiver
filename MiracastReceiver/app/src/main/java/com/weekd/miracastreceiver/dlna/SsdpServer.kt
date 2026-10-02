package com.weekd.miracastreceiver.dlna

import android.content.Context
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/** SSDP server with explicit IPv4 and IPv6 multicast sockets. */
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val multicastSockets = CopyOnWriteArrayList<MulticastSocket>()
    private val receiveJobs = CopyOnWriteArrayList<Job>()
    private var notifyJob: Job? = null

    fun start() {
        if (receiveJobs.any { it.isActive }) return

        val interfaces = multicastInterfaces()
        val ipv4Socket = createFamilySocket(InetAddress.getByName("0.0.0.0"), SSDP_IPV4, interfaces)
        val ipv6Socket = createFamilySocket(InetAddress.getByName("::"), SSDP_IPV6, interfaces)

        listOfNotNull(ipv4Socket, ipv6Socket).forEach { socket ->
            multicastSockets += socket
            receiveJobs += scope.launch { receiveLoop(socket) }
        }

        if (multicastSockets.isEmpty()) {
            Timber.w("SSDP could not bind either IPv4 or IPv6 multicast socket")
        } else {
            Timber.i(
                "SSDP started: listeners=${multicastSockets.joinToString { it.localAddress.hostAddress ?: "?" }}:$SSDP_PORT"
            )
        }
        startPeriodicNotify()
    }

    fun stop() {
        runCatching { sendByebye() }
        notifyJob?.cancel()
        notifyJob = null
        receiveJobs.forEach { it.cancel() }
        receiveJobs.clear()
        multicastSockets.forEach { runCatching { it.close() } }
        multicastSockets.clear()
        Timber.i("SSDP server stopped")
    }

    private fun createFamilySocket(
        wildcard: InetAddress,
        groupLiteral: String,
        interfaces: List<NetworkInterface>
    ): MulticastSocket? {
        val group = InetAddress.getByName(groupLiteral)
        val socket = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(wildcard, SSDP_PORT))
            }
        } catch (e: Exception) {
            Timber.w(e, "Unable to bind SSDP ${wildcard.hostAddress}:$SSDP_PORT")
            return null
        }

        var joined = false
        interfaces.forEach { iface ->
            if (!interfaceSupportsFamily(iface, group)) return@forEach
            runCatching {
                val scopedGroup = scopedGroupAddress(group, iface)
                socket.joinGroup(InetSocketAddress(scopedGroup, SSDP_PORT), iface)
                joined = true
                Timber.d("Joined SSDP group $groupLiteral on ${iface.name}")
            }.onFailure { Timber.d(it, "Could not join $groupLiteral on ${iface.name}") }
        }

        if (!joined) {
            runCatching { socket.joinGroup(InetSocketAddress(group, SSDP_PORT), null) }
                .onSuccess { joined = true }
                .onFailure { Timber.w(it, "Unable to join SSDP group $groupLiteral") }
        }

        if (!joined) {
            socket.close()
            return null
        }
        return socket
    }

    private suspend fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(2048)
        try {
            while (isActive && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length)
                if (message.contains(SSDP_SEARCH_PATTERN, ignoreCase = true)) {
                    handleSearchRequest(message, packet.address, packet.port)
                }
            }
        } catch (e: Exception) {
            if (!socket.isClosed) Timber.e(e, "SSDP receive loop failed on ${socket.localAddress.hostAddress}")
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
            val wildcard = if (address is Inet6Address) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(wildcard, 0))
                val data = response.toByteArray()
                socket.send(DatagramPacket(data, data.size, address, port))
            }
        }.onFailure { Timber.e(it, "Error sending SSDP response to ${address.hostAddress}:$port") }
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

    private fun sendNotify() = sendNotifications("ssdp:alive")

    private fun sendByebye() = sendNotifications("ssdp:byebye")

    private fun sendNotifications(nts: String) {
        val addresses = NetworkUtils.getLanAddresses()
        val interfaces = multicastInterfaces()

        addresses.ipv4?.let { localAddress ->
            interfaces.filter { interfaceHasAddress(it, localAddress) || interfaceHasFamily(it, false) }
                .forEach { iface -> sendNotificationOnInterface(nts, SSDP_IPV4, localAddress, "$SSDP_IPV4:$SSDP_PORT", iface) }
        }
        addresses.ipv6?.let { localAddress ->
            interfaces.filter { interfaceHasAddress(it, localAddress) || interfaceHasFamily(it, true) }
                .forEach { iface -> sendNotificationOnInterface(nts, SSDP_IPV6, localAddress, "[$SSDP_IPV6]:$SSDP_PORT", iface) }
        }
    }

    private fun sendNotificationOnInterface(
        nts: String,
        groupLiteral: String,
        localAddress: String,
        hostHeader: String,
        iface: NetworkInterface
    ) {
        runCatching {
            val group = scopedGroupAddress(InetAddress.getByName(groupLiteral), iface)
            MulticastSocket().use { socket ->
                socket.networkInterface = iface
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
            Timber.d("Sent SSDP $nts via ${iface.name} to $groupLiteral")
        }.onFailure { Timber.d(it, "Unable to send SSDP $nts via ${iface.name} to $groupLiteral") }
    }

    private fun multicastInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface ->
                runCatching { iface.isUp && !iface.isLoopback && iface.supportsMulticast() }.getOrDefault(false)
            }
            .filterNot { iface ->
                val n = iface.name.lowercase()
                n.startsWith("tun") || n.startsWith("tap") || n.startsWith("wg") ||
                    n.startsWith("zt") || n.startsWith("vti") || n.startsWith("ipsec") ||
                    n.startsWith("dummy") || n.startsWith("clat") || n.startsWith("rmnet")
            }

    private fun interfaceSupportsFamily(iface: NetworkInterface, address: InetAddress): Boolean =
        if (address is Inet6Address) interfaceHasFamily(iface, true) else interfaceHasFamily(iface, false)

    private fun interfaceHasFamily(iface: NetworkInterface, ipv6: Boolean): Boolean =
        iface.inetAddresses.toList().any { if (ipv6) it is Inet6Address else it is Inet4Address }

    private fun interfaceHasAddress(iface: NetworkInterface, address: String): Boolean {
        val normalized = address.substringBefore('%')
        return iface.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == normalized }
    }

    private fun scopedGroupAddress(group: InetAddress, iface: NetworkInterface): InetAddress {
        if (group !is Inet6Address) return group
        return Inet6Address.getByAddress(null, group.address, iface.index)
    }
}
