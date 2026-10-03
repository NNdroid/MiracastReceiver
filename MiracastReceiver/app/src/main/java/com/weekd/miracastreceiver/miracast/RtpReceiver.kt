package com.weekd.miracastreceiver.miracast

import android.os.SystemClock
import android.view.Surface
import com.weekd.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Miracast RTP receiver. WFD carries MPEG-2 TS over RTP (normally PT=33).
 *
 * Wi-Fi Direct can reorder a handful of UDP packets even on an otherwise healthy link. Treating
 * every out-of-order packet as loss immediately makes the TS demuxer reset continuously, which in
 * turn leaves the decoder waiting for another IDR and can present as a permanently black screen.
 * A small bounded reorder window absorbs normal jitter while still recovering quickly from real
 * packet loss.
 */
class RtpReceiver(
    private val port: Int,
    private val surfaceProvider: () -> Surface?
) {

    data class Snapshot(
        val running: Boolean,
        val port: Int,
        val payloadType: Int,
        val packetsReceived: Long,
        val packetsLost: Long,
        val packetsReordered: Long,
        val packetsLateOrDuplicate: Long,
        val queueDrops: Long,
        val invalidPackets: Long,
        val bytesReceived: Long,
        val rtcpPacketsReceived: Long,
        val firstPacketAtMs: Long,
        val lastPacketAtMs: Long,
        val queueDepth: Int,
        val renderer: MiracastVideoRenderer.Snapshot
    )

    private data class PayloadRange(val start: Int, val endExclusive: Int)

    private var socket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isRunning = false
    @Volatile private var lastPublishedPipelineState = ""

    private val renderer = MiracastVideoRenderer(surfaceProvider)
    private val audioPlayer = MiracastAudioPlayer()
    private val demuxer = TsDemuxer(
        onAccessUnit = renderer::onAccessUnit,
        onDiscontinuity = renderer::onDiscontinuity,
        onAudioPes = { codec, data -> if (surfaceProvider() != null) audioPlayer.onAudioPes(codec, data) },
        onAudioDiscontinuity = audioPlayer::onDiscontinuity
    )

    private val payloadQueue = ArrayBlockingQueue<ByteArray>(256)

    @Volatile var packetsLost = 0L
        private set
    @Volatile var packetsReceived = 0L
        private set
    @Volatile var packetsReordered = 0L
        private set
    @Volatile var packetsLateOrDuplicate = 0L
        private set
    @Volatile var queueDrops = 0L
        private set
    @Volatile var invalidPackets = 0L
        private set
    @Volatile var bytesReceived = 0L
        private set
    @Volatile var rtcpPacketsReceived = 0L
        private set
    @Volatile var firstPacketAtMs = 0L
        private set
    @Volatile var lastPacketAtMs = 0L
        private set
    @Volatile var payloadType = -1
        private set

    var onError: ((String) -> Unit)? = null

    companion object {
        private const val REORDER_WINDOW_PACKETS = 32
        private const val REORDER_MAX_WAIT_MS = 45L
        private const val RTP_SOCKET_POLL_MS = 25

        @Volatile
        var active: RtpReceiver? = null
    }

    fun snapshot(): Snapshot = Snapshot(
        running = isRunning,
        port = port,
        payloadType = payloadType,
        packetsReceived = packetsReceived,
        packetsLost = packetsLost,
        packetsReordered = packetsReordered,
        packetsLateOrDuplicate = packetsLateOrDuplicate,
        queueDrops = queueDrops,
        invalidPackets = invalidPackets,
        bytesReceived = bytesReceived,
        rtcpPacketsReceived = rtcpPacketsReceived,
        firstPacketAtMs = firstPacketAtMs,
        lastPacketAtMs = lastPacketAtMs,
        queueDepth = payloadQueue.size,
        renderer = renderer.snapshot()
    )

    fun start() {
        if (isRunning) {
            Timber.w("RTP Receiver already running")
            return
        }

        active = this
        scope.launch { runDecoder() }
        scope.launch { runReceiver() }
    }

    private fun publishPipelineState(state: String) {
        if (state == lastPublishedPipelineState) return
        lastPublishedPipelineState = state
        RuntimeState.miracastState = state
        Timber.i("Miracast pipeline state: $state")
    }

    /**
     * Publish what is actually happening after RTSP negotiation instead of claiming STREAMING as
     * soon as PLAY succeeds. This state is consumed by TV/WebUI status and is intentionally based
     * on live RTP/TS/Surface/decoder observations.
     */
    private fun refreshPipelineState() {
        if (!isRunning) return
        val video = renderer.snapshot()
        val next = when {
            packetsReceived == 0L -> "WAITING_RTP"
            video.accessUnits == 0L -> "RTP_ACTIVE"
            video.waitingForSurface -> "WAITING_SURFACE"
            video.waitingForKeyframe -> "WAITING_IDR"
            video.decoderReady -> "DISPLAYING"
            else -> "VIDEO_ACTIVE"
        }
        publishPipelineState(next)
    }

    private suspend fun runReceiver() = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket(port).apply {
                runCatching { receiveBufferSize = 2 * 1024 * 1024 }
                soTimeout = RTP_SOCKET_POLL_MS
            }
            rtcpSocket = runCatching {
                DatagramSocket(port + 1).apply {
                    runCatching { receiveBufferSize = 256 * 1024 }
                    soTimeout = 1_000
                }
            }.onFailure {
                Timber.w(it, "Unable to bind Miracast RTCP UDP ${port + 1}; continuing RTP-only")
            }.getOrNull()

            isRunning = true
            publishPipelineState("WAITING_RTP")
            Timber.i(
                "RTP Receiver listening on UDP $port (MPEG-2 TS), RTCP=" +
                    "${if (rtcpSocket != null) port + 1 else "unavailable"}, recvBuf=${socket?.receiveBufferSize}"
            )
            if (rtcpSocket != null) scope.launch { drainRtcp() }

            val pending = HashMap<Int, ByteArray>(REORDER_WINDOW_PACKETS * 2)
            var expectedSeq = -1
            var gapStartedAtMs = 0L
            val buffer = ByteArray(65536)
            val packet = DatagramPacket(buffer, buffer.size)
            var loggedFirst = false

            fun enqueuePayload(payload: ByteArray) {
                if (!payloadQueue.offer(payload)) {
                    payloadQueue.poll()
                    if (!payloadQueue.offer(payload)) return
                    queueDrops++
                    renderer.onDiscontinuity()
                    audioPlayer.onDiscontinuity()
                    Timber.w("RTP decode queue overflow; dropped oldest payload (drops=$queueDrops)")
                }
            }

            fun drainContiguous() {
                while (expectedSeq >= 0) {
                    val payload = pending.remove(expectedSeq) ?: break
                    enqueuePayload(payload)
                    expectedSeq = (expectedSeq + 1) and 0xFFFF
                }
                if (pending.isEmpty()) gapStartedAtMs = 0L
            }

            fun skipConfirmedGap(reason: String) {
                if (expectedSeq < 0 || pending.isEmpty()) return
                val nextSeq = pending.keys.minByOrNull { forwardDistance(expectedSeq, it) } ?: return
                val lost = forwardDistance(expectedSeq, nextSeq)
                if (lost <= 0 || lost > 0x7FFF) return
                packetsLost += lost
                Timber.w("RTP: confirmed loss of $lost packets ($reason, seq $expectedSeq -> $nextSeq)")
                renderer.onDiscontinuity()
                audioPlayer.onDiscontinuity()
                expectedSeq = nextSeq
                gapStartedAtMs = 0L
                drainContiguous()
            }

            while (isRunning) {
                try {
                    packet.length = buffer.size
                    socket?.receive(packet) ?: break
                    if (packet.length <= 0) continue

                    val now = SystemClock.elapsedRealtime()
                    packetsReceived++
                    bytesReceived += packet.length
                    lastPacketAtMs = now
                    if (firstPacketAtMs == 0L) {
                        firstPacketAtMs = now
                        publishPipelineState("RTP_ACTIVE")
                    }

                    val range = rtpPayloadRange(packet.data, packet.length)
                    if (range == null) {
                        invalidPackets++
                        continue
                    }

                    val pt = packet.data[1].toInt() and 0x7F
                    if (payloadType < 0) payloadType = pt
                    val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or (packet.data[3].toInt() and 0xFF)
                    val payload = packet.data.copyOfRange(range.start, range.endExclusive)

                    if (!loggedFirst) {
                        loggedFirst = true
                        Timber.i(
                            "First RTP packet: ${packet.length}B payloadType=$pt payload=${payload.size}B " +
                                "source=${packet.address.hostAddress}:${packet.port}"
                        )
                    }

                    if (expectedSeq < 0) expectedSeq = seq
                    val distance = forwardDistance(expectedSeq, seq)
                    when {
                        distance == 0 -> {
                            enqueuePayload(payload)
                            expectedSeq = (expectedSeq + 1) and 0xFFFF
                            drainContiguous()
                        }
                        distance in 1..0x7FFF -> {
                            if (pending.putIfAbsent(seq, payload) == null) {
                                packetsReordered++
                                if (gapStartedAtMs == 0L) gapStartedAtMs = now
                            } else {
                                packetsLateOrDuplicate++
                            }
                            if (pending.size >= REORDER_WINDOW_PACKETS) skipConfirmedGap("reorder window full")
                        }
                        else -> packetsLateOrDuplicate++
                    }

                    if (gapStartedAtMs != 0L && now - gapStartedAtMs >= REORDER_MAX_WAIT_MS) {
                        skipConfirmedGap("reorder timeout")
                    }

                    if (packetsReceived % 1000L == 0L) {
                        refreshPipelineState()
                        Timber.i(
                            "RTP stats: packets=$packetsReceived bytes=${bytesReceived / 1024}KB lost=$packetsLost " +
                                "reordered=$packetsReordered late=$packetsLateOrDuplicate invalid=$invalidPackets " +
                                "queueDrops=$queueDrops rtcp=$rtcpPacketsReceived queue=${payloadQueue.size}"
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    val now = SystemClock.elapsedRealtime()
                    if (gapStartedAtMs != 0L && now - gapStartedAtMs >= REORDER_MAX_WAIT_MS) {
                        skipConfirmedGap("reorder timeout")
                    }
                    refreshPipelineState()
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "Error receiving RTP packet")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start RTP Receiver")
            RuntimeState.lastError = "Miracast RTP: ${e.message.orEmpty()}"
            publishPipelineState("RTP_ERROR")
            onError?.invoke("RTP 接收启动失败: ${e.message}")
            isRunning = false
        }
    }

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
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (isRunning) Timber.d("RTCP receive ended: ${e.message}")
                break
            }
        }
    }

    private suspend fun runDecoder() {
        while (scope.isActive) {
            val payload = withContext(Dispatchers.IO) { payloadQueue.poll(200, TimeUnit.MILLISECONDS) } ?: continue
            try {
                demuxer.feed(payload, 0, payload.size)
                refreshPipelineState()
            } catch (e: Exception) {
                Timber.e(e, "Error demuxing TS payload")
                renderer.onDiscontinuity()
                audioPlayer.onDiscontinuity()
                refreshPipelineState()
            }
        }
    }

    /** Return RTP payload bounds, excluding CSRC/extension header and trailing RTP padding. */
    private fun rtpPayloadRange(data: ByteArray, length: Int): PayloadRange? {
        if (length < 12) return null
        val version = (data[0].toInt() ushr 6) and 0x03
        if (version != 2) return null

        val csrcCount = data[0].toInt() and 0x0F
        val hasExtension = ((data[0].toInt() ushr 4) and 0x01) == 1
        val hasPadding = ((data[0].toInt() ushr 5) and 0x01) == 1
        var start = 12 + csrcCount * 4
        if (start > length) return null

        if (hasExtension) {
            if (length < start + 4) return null
            val extWords = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
            start += 4 + extWords * 4
            if (start > length) return null
        }

        var end = length
        if (hasPadding) {
            val padding = data[length - 1].toInt() and 0xFF
            if (padding <= 0 || padding > length - start) return null
            end -= padding
        }
        if (start >= end) return null
        return PayloadRange(start, end)
    }

    private fun forwardDistance(from: Int, to: Int): Int = (to - from + 0x10000) and 0xFFFF

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
            "RTP Receiver stopped: packets=$packetsReceived bytes=${bytesReceived / 1024}KB lost=$packetsLost " +
                "reordered=$packetsReordered late=$packetsLateOrDuplicate invalid=$invalidPackets " +
                "queueDrops=$queueDrops rtcp=$rtcpPacketsReceived"
        )
    }

    fun isRunning(): Boolean = isRunning
}
