package com.weekd.miracastreceiver.miracast

import android.view.Surface
import com.weekd.miracastreceiver.airplay.VideoDecoder
import timber.log.Timber
import java.util.ArrayDeque

/**
 * Low-latency Miracast H.264 renderer.
 *
 * Miracast starts sending RTP as soon as the RTSP PLAY transition completes, while the TV player
 * Activity/Surface may still be being created. Dropping those first access units is especially
 * painful when the first IDR is lost: the screen stays black until the Source sends another IDR.
 *
 * To close that race we keep a small recovery GOP while the Surface is unavailable. As soon as a
 * valid Surface arrives, the decoder is configured from the captured SPS/PPS and the buffered GOP
 * is replayed immediately. The buffer is deliberately bounded so a backgrounded Activity cannot
 * accumulate unbounded video data.
 */
class MiracastVideoRenderer(private val surfaceProvider: () -> Surface?) {

    data class Snapshot(
        val accessUnits: Long,
        val keyframes: Long,
        val decoderReady: Boolean,
        val waitingForSurface: Boolean,
        val waitingForKeyframe: Boolean,
        val recoveryBufferedUnits: Int,
        val recoveryBufferedBytes: Int,
        val recoveryReplays: Long
    )

    private data class RecoveryUnit(val data: ByteArray, val ptsUs: Long)

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedBeforeConfig = 0

    private val recoveryUnits = ArrayDeque<RecoveryUnit>()
    private var recoveryBytes = 0
    private var recoveryHasIdr = false

    @Volatile private var accessUnitsReceived = 0L
    @Volatile private var keyframesSeen = 0L
    @Volatile private var recoveryReplays = 0L
    @Volatile private var waitingForSurface = true

    companion object {
        private const val MAX_RECOVERY_UNITS = 120
        private const val MAX_RECOVERY_BYTES = 8 * 1024 * 1024
    }

    fun snapshot(): Snapshot = Snapshot(
        accessUnits = accessUnitsReceived,
        keyframes = keyframesSeen,
        decoderReady = decoder != null,
        waitingForSurface = waitingForSurface,
        waitingForKeyframe = awaitingKeyframe,
        recoveryBufferedUnits = recoveryUnits.size,
        recoveryBufferedBytes = recoveryBytes,
        recoveryReplays = recoveryReplays
    )

    /** Corruption/loss invalidates inter-frame references; resume only from a fresh IDR. */
    fun onDiscontinuity() {
        if (!awaitingKeyframe) Timber.w("Miracast: data loss detected, waiting for next keyframe")
        awaitingKeyframe = true
        clearRecoveryBuffer()
    }

    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        accessUnitsReceived++
        captureParameterSets(unit)
        val isIdr = containsIdr(unit)
        if (isIdr) keyframesSeen++

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            waitingForSurface = true
            bufferForSurfaceRecovery(unit, ptsUs, isIdr)
            releaseDecoder()
            return
        }
        waitingForSurface = false

        val rebuilt = decoder == null || surface !== configuredSurface
        if (rebuilt && !rebuildDecoder(surface)) {
            droppedBeforeConfig++
            if (droppedBeforeConfig % 60 == 0) {
                Timber.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
            }
            return
        }

        // If streaming began before PlayerActivity produced a Surface, replay the bounded GOP that
        // starts with the most recent IDR. This avoids waiting an entire GOP for the next keyframe.
        if (rebuilt && recoveryHasIdr && recoveryUnits.isNotEmpty()) {
            val count = recoveryUnits.size
            while (recoveryUnits.isNotEmpty()) {
                val buffered = recoveryUnits.removeFirst()
                recoveryBytes -= buffered.data.size
                decoder?.decodeNalUnit(buffered.data, buffered.ptsUs)
            }
            recoveryHasIdr = false
            awaitingKeyframe = false
            recoveryReplays++
            Timber.i("Miracast: replayed $count buffered access units after Surface became ready")
        } else if (rebuilt) {
            clearRecoveryBuffer()
        }

        if (awaitingKeyframe) {
            if (!isIdr) return
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }

        decoder?.decodeNalUnit(unit, ptsUs)
    }

    private fun bufferForSurfaceRecovery(unit: ByteArray, ptsUs: Long, isIdr: Boolean) {
        if (isIdr) {
            clearRecoveryBuffer()
            recoveryHasIdr = true
        }
        if (!recoveryHasIdr) return

        // PES assembler returns independent byte arrays, but copy here to keep this class robust if
        // that implementation changes later.
        val copy = unit.copyOf()
        recoveryUnits.addLast(RecoveryUnit(copy, ptsUs))
        recoveryBytes += copy.size

        // Never drop the leading IDR and then keep unusable P/B frames. If the recovery GOP grows
        // too large, discard it and wait for the next IDR instead.
        if (recoveryUnits.size > MAX_RECOVERY_UNITS || recoveryBytes > MAX_RECOVERY_BYTES) {
            Timber.w("Miracast: Surface recovery GOP exceeded limit; waiting for next IDR")
            clearRecoveryBuffer()
        }
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        decoder = VideoDecoder(surface).also { it.initialize(spsBytes, ppsBytes, width, height) }
        configuredSurface = surface
        awaitingKeyframe = true
        Timber.i("Miracast decoder initialized: ${width}x$height")
        return true
    }

    private fun captureParameterSets(unit: ByteArray) {
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val withStartCode = ByteArray(4 + (end - start))
                withStartCode[3] = 1
                System.arraycopy(unit, start, withStartCode, 4, end - start)
                if (nalType == 7 && !withStartCode.contentEquals(sps)) {
                    sps = withStartCode
                    Timber.i("Miracast: SPS captured (${end - start} bytes)")
                } else if (nalType == 8 && !withStartCode.contentEquals(pps)) {
                    pps = withStartCode
                    Timber.i("Miracast: PPS captured (${end - start} bytes)")
                }
            }
        }
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun clearRecoveryBuffer() {
        recoveryUnits.clear()
        recoveryBytes = 0
        recoveryHasIdr = false
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        configuredSurface = null
    }

    fun release() {
        releaseDecoder()
        clearRecoveryBuffer()
        sps = null
        pps = null
        awaitingKeyframe = true
        waitingForSurface = true
    }
}
