package com.weekd.miracastreceiver.airplay.handshake

import com.weekd.miracastreceiver.util.Logger
import com.weekd.miracastreceiver.utils.PortUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * BufferedAudioServer — receives the AirPlay 2 buffered audio-only stream.
 *
 * The SETUP-negotiated TCP port is explicitly dual-stack. AirPlay control may arrive over IPv6;
 * returning an IPv4-only ephemeral data port would make the sender abort immediately afterwards.
 */
class BufferedAudioServer {

    private val serverSocket: ServerSocket = PortUtils.bindEphemeralServerSocket()
    @Volatile private var running = false
    @Volatile private var client: Socket? = null

    /** TCP port macOS connects to for the buffered audio stream (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReceive() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        Logger.i("BufferedAudioServer stopped")
    }

    private fun runReceive() {
        try {
            Logger.i("BufferedAudioServer (audio-only) listening dual-stack on TCP $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Buffered audio connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val buf = ByteArray(16384)
            var reads = 0
            var totalBytes = 0L
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                totalBytes += n
                if (reads < 12) {
                    Logger.d("Buffered audio read[$reads] ${n}B head=${hex(buf, minOf(28, n))}")
                    reads++
                }
            }
            Logger.i("Buffered audio connection ended (total ${totalBytes}B over ${reads}+ reads)")
        } catch (e: Exception) {
            if (running) Logger.e("Buffered audio stream error", e)
        }
    }

    private companion object {
        fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }
    }
}
