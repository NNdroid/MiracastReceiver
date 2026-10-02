package com.weekd.miracastreceiver.miracast

import android.view.Surface
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Miracast RTP receiver. WFD carries MPEG-2 TS over RTP (normally PT=33); TS is demuxed and
 * H.264/AAC/LPCM are rendered directly for low latency.
 */
class RtpReceiver(
    private val port: Int,
    private val surfaceProvider: () -> Surface?
) {

    private var socket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private val renderer = MiracastVideoRenderer(surfaceProvider)
    private val audioPlayer = MiracastAudioPlayer()
    private val demuxer = TsDemuxer(
        onAccessUnit = renderer::onAccessUnit,
        onDiscontinuity = renderer::onDiscontinuity,
        onAudioPes = { codec, data -> if (surfaceProvider() != null) audioPlayer.onAudioPes(codec, data) },
        onAudioDiscontinuity = audioPlayer::onDiscontinuity
    )

    private val payloadQueue = ArrayBlockingQueue<ByteArray>(256)

    @Volatile
    var packetsLost = 0L
        private set

    @Volatile
    var packetsReceived = 0L
        private set

    @Volatile
    var bytesReceived = 0L
        private set

    @Volatile
    var rtcpPacketsReceived = 0L
        private set

    var onError: ((String) -> Unit)? = null

    companion object {
        @Volatile
        var active: RtpReceiver? = null
    }

    fun start() {
        if (isRunning) {
            Timber.w("RTP Receiver already running")
            return
        }

        active = this
        scope.launch { runDecoder() }

        scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    runCatching { receiveBufferSize = 1024 * 1024 }
                }

                // AOSP WFD SETUP uses client_port=<RTP>-<RTCP>. Some phone sources send RTCP
                // immediately after PLAY and consider an ICMP port-unreachable a failed transport.
                // We do not need RTCP feedback yet, but binding/draining the companion port keeps
                // the standard RTP/RTCP transport valid across Android, Windows and vendor sources.
                rtcpSocket = runCatching {
                    DatagramSocket(port + 1).apply {
                        runCatching { receiveBufferSize = 256 * 1024 }
                    }
                }.onFailure {
                    Timber.w(it, "Unable to bind Miracast RTCP UDP ${port + 1}; continuing RTP-only")
                }.getOrNull()

                isRunning = true
                Timber.i(
                    "RTP Receiver listening on UDP $port (MPEG-2 TS, PT usually 33), " +
                        "RTCP=${if (rtcpSocket != null) port + 1 else "unavailable"}, " +
                        "recvBuf=${socket?.receiveBufferSize}"
                )

                if (rtcpSocket != null) scope.launch { drainRtcp() }

                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)
                var loggedFirst = false
                var expectedSeq = -1

                while (isRunning) {
                    try {
                        packet.length = buffer.size
                        socket?.receive(packet)
                        if (packet.length <= 0) continue

                        packetsReceived++
                        bytesReceived += packet.length

                        val payloadOffset = rtpHeaderLength(packet.data, packet.length)
                        if (payloadOffset <= 0 || payloadOffset >= packet.length) continue

                        if (!loggedFirst) {
                            loggedFirst = true
                            val pt = packet.data[1].toInt() and 0x7F
                            Timber.i(
                                "First RTP packet: ${packet.length}B payloadType=$pt " +
                                    "payload=${packet.length - payloadOffset}B source=${packet.address.hostAddress}:${packet.port}"
                            )
                        }

                        val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or
                            (packet.data[3].toInt() and 0xFF)
                        if (expectedSeq >= 0 && seq != expectedSeq) {
                            val lost = (seq - expectedSeq + 0x10000) and 0xFFFF
                            // Large backwards/reordered jumps should not be reported as tens of
                            // thousands of lost packets. Treat only a forward half-window as loss.
                            if (lost in 1..0x7FFF) {
                                packetsLost += lost
                                Timber.w("RTP: lost $lost packets (seq $expectedSeq -> $seq)")
                                renderer.onDiscontinuity()
                                audioPlayer.onDiscontinuity()
                            } else {
                                Timber.d("RTP: reordered/old packet seq=$seq expected=$expectedSeq")
                            }
                        }
                        expectedSeq = (seq + 1) and 0xFFFF

                        val payload = packet.data.copyOfRange(payloadOffset, packet.length)
                        if (!payloadQueue.offer(payload)) {
                            payloadQueue.poll()
                            payloadQueue.offer(payload)
                            renderer.onDiscontinuity()
                            audioPlayer.onDiscontinuity()
                        }

                        if (packetsReceived % 1000L == 0L) {
                            Timber.i(
                                "RTP stats: $packetsReceived packets, ${bytesReceived / 1024}KB, " +
                                    "lost=$packetsLost, rtcp=$rtcpPacketsReceived, queue=${payloadQueue.size}"
                            )
                        }
                    } catch (e: Exception) {
                        if (isRunning) Timber.e(e, "Error receiving RTP packet")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start RTP Receiver")
                onError?.invoke("RTP 接收启动失败: ${e.message}")
                isRunning = false
            }
        }
    }

    /** Drain RTCP so standards-compliant sources see a live companion port. */
    private suspend fun drainRtcp() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (isRunning && scope.isActive) {
            try {
                packet.length = buffer.size
                rtcpSocket?.receive(packet) ?: break
                rtcpPacketsReceived++
                if (rtcpPacketsReceived == 1L) {
                    Timber.i("First RTCP packet: ${packet.length}B from ${packet.address.hostAddress}:${packet.port}")
                }
            } catch (e: Exception) {
                if (isRunning) Timber.d("RTCP receive ended: ${e.message}")
                break
            }
        }
    }

    private suspend fun runDecoder() {
        while (scope.isActive) {
            val payload = withContext(Dispatchers.IO) {
                payloadQueue.poll(200, TimeUnit.MILLISECONDS)
            } ?: continue
            try {
                demuxer.feed(payload, 0, payload.size)
            } catch (e: Exception) {
                Timber.e(e, "Error demuxing TS payload")
                renderer.onDiscontinuity()
            }
        }
    }

    private fun rtpHeaderLength(data: ByteArray, length: Int): Int {
        if (length < 12) return -1
        val version = (data[0].toInt() shr 6) and 0x03
        if (version != 2) return -1

        val csrcCount = data[0].toInt() and 0x0F
        val hasExtension = ((data[0].toInt() shr 4) and 0x01) == 1
        val hasPadding = ((data[0].toInt() shr 5) and 0x01) == 1
        var headerLength = 12 + csrcCount * 4

        if (hasExtension) {
            if (length < headerLength + 4) return -1
            val extWords = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or
                (data[headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extWords * 4
        }

        if (headerLength >= length) return -1
        if (hasPadding) {
            val padding = data[length - 1].toInt() and 0xFF
            if (padding <= 0 || padding >= length - headerLength) return -1
        }
        return headerLength
    }

    fun stop() {
        isRunning = false
        runCatching { socket?.close() }
        runCatching { rtcpSocket?.close() }
        socket = null
        rtcpSocket = null
        scope.cancel()
        renderer.release()
        audioPlayer.release()
        demuxer.reset()
        payloadQueue.clear()
        if (active === this) active = null
        Timber.i(
            "RTP Receiver stopped: $packetsReceived packets, ${bytesReceived / 1024}KB, " +
                "lost=$packetsLost, rtcp=$rtcpPacketsReceived"
        )
    }

    fun isRunning(): Boolean = isRunning
}
