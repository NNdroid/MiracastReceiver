package com.weekd.miracastreceiver.airplay.handshake

import android.view.Surface
import com.weekd.miracastreceiver.airplay.StreamStats
import com.weekd.miracastreceiver.airplay.VideoDecoder
import com.weekd.miracastreceiver.util.Logger
import com.weekd.miracastreceiver.utils.PortUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** AirPlay 2 mirroring video data server. */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket: ServerSocket = PortUtils.bindEphemeralServerSocket()
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    @Volatile private var awaitingKeyframe = false

    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening dual-stack on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                StreamStats.videoBytesTotal += payloadSize
                when (payloadType) {
                    0 -> {
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    private fun enqueue(item: Item) {
        framesIn++
        StreamStats.videoFramesTotal++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null || !surface.isValid) return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray) {
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return
        if (!d.isHealthy) {
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null
            return
        }
        if (awaitingKeyframe) {
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true
                i += 3
            } else i++
        }
        return false
    }

    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024
        private const val FRAME_INTERVAL_US = 1_000_000L / 60
        private const val QUEUE_CAPACITY = 90
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
