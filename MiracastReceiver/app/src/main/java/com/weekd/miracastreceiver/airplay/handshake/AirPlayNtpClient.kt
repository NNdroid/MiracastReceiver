package com.weekd.miracastreceiver.airplay.handshake

import com.weekd.miracastreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Receiver-initiated AirPlay 2 NTP timing client. */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    /** Bind the UDP socket to the same address family as the RTSP sender. */
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        val wildcard = if (remoteAddress is Inet6Address) {
            InetAddress.getByName("::")
        } else {
            InetAddress.getByName("0.0.0.0")
        }
        bind(InetSocketAddress(wildcard, 0))
    }
    @Volatile private var running = false

    val localPort: Int get() = socket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        socket.soTimeout = RECV_TIMEOUT_MS
        scope.launch(Dispatchers.IO) { loop() }
        Logger.i(
            "NTP client ${if (remoteAddress is Inet6Address) "IPv6" else "IPv4"} → " +
                "[${remoteAddress.hostAddress}]:$remoteTimingPort, local timing port $localPort"
        )
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun loop() {
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var rxCount = 0
        while (running) {
            try {
                putNtpTimestamp(request, 24, System.currentTimeMillis())
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent to sender"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    if (rxCount < 4) {
                        Logger.i("NTP RX[$rxCount] ${rx.length}B type=0x${(response[1].toInt() and 0xFF).toString(16)}: " +
                            (0 until minOf(rx.length, 32)).joinToString(" ") { "%02x".format(response[it]) })
                        rxCount++
                    }
                } catch (_: Exception) { /* receive timeout; retry next tick */ }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun putNtpTimestamp(buf: ByteArray, off: Int, epochMillis: Long) {
        val seconds = epochMillis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (epochMillis % 1000) * (1L shl 32) / 1000
        writeUint32(buf, off, seconds)
        writeUint32(buf, off + 4, fraction)
    }

    private fun writeUint32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    companion object {
        private const val NTP_EPOCH_OFFSET = 2208988800L
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECV_TIMEOUT_MS = 1000
    }
}
