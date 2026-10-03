package com.weekd.miracastreceiver.airplay

import android.view.Surface
import timber.log.Timber
import java.util.ArrayDeque

/**
 * Renderer for the legacy SDP/RTP AirPlay video path.
 *
 * RECORD can arrive before PlayerActivity has created its Surface. The old pipeline queried the
 * Surface once and permanently skipped video when it was null. This renderer installs the RTP/NAL
 * callback immediately, keeps a bounded GOP beginning at the newest IDR while the Surface is not
 * ready, and lazily creates/recreates MediaCodec when a valid Surface becomes available.
 */
class LegacyAirPlayVideoRenderer(
    private val surfaceProvider: () -> Surface?,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val widthHint: Int,
    private val heightHint: Int
) {
    private data class BufferedNal(val data: ByteArray, val ptsUs: Long)

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private val recovery = ArrayDeque<BufferedNal>()
    private var recoveryBytes = 0
    private var recoveryHasIdr = false
    private var waitingForKeyframe = true

    @Volatile var receivedNals: Long = 0
        private set
    @Volatile var keyframes: Long = 0
        private set
    @Volatile var recoveryReplays: Long = 0
        private set

    companion object {
        private const val MAX_RECOVERY_NALS = 120
        private const val MAX_RECOVERY_BYTES = 8 * 1024 * 1024
    }

    fun onNalUnit(nalUnit: ByteArray, ptsUs: Long) {
        receivedNals++
        val isIdr = containsNalType(nalUnit, 5)
        if (isIdr) keyframes++

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            bufferRecovery(nalUnit, ptsUs, isIdr)
            releaseDecoder()
            return
        }

        val rebuilt = decoder == null || surface !== configuredSurface
        if (rebuilt) {
            rebuildDecoder(surface)
            if (recoveryHasIdr && recovery.isNotEmpty()) {
                val count = recovery.size
                while (recovery.isNotEmpty()) {
                    val item = recovery.removeFirst()
                    recoveryBytes -= item.data.size
                    decoder?.decodeNalUnit(item.data, item.ptsUs)
                }
                recoveryHasIdr = false
                waitingForKeyframe = false
                recoveryReplays++
                Timber.i("AirPlay legacy: replayed $count buffered NAL units after Surface became ready")
            } else {
                clearRecovery()
            }
        }

        if (waitingForKeyframe) {
            if (!isIdr) return
            waitingForKeyframe = false
            Timber.i("AirPlay legacy: keyframe acquired; video decode started")
        }
        decoder?.decodeNalUnit(nalUnit, ptsUs)
    }

    private fun rebuildDecoder(surface: Surface) {
        releaseDecoder()
        val resolution = VideoDecoder.parseSpsResolution(sps)
        val width = resolution?.first ?: widthHint
        val height = resolution?.second ?: heightHint
        decoder = VideoDecoder(surface).also { it.initialize(sps, pps, width, height) }
        configuredSurface = surface
        waitingForKeyframe = true
        Timber.i("AirPlay legacy decoder initialized ${width}x$height")
    }

    private fun bufferRecovery(nalUnit: ByteArray, ptsUs: Long, isIdr: Boolean) {
        if (isIdr) {
            clearRecovery()
            recoveryHasIdr = true
        }
        if (!recoveryHasIdr) return
        val copy = nalUnit.copyOf()
        recovery.addLast(BufferedNal(copy, ptsUs))
        recoveryBytes += copy.size
        if (recovery.size > MAX_RECOVERY_NALS || recoveryBytes > MAX_RECOVERY_BYTES) {
            Timber.w("AirPlay legacy recovery GOP exceeded limit; waiting for next IDR")
            clearRecovery()
        }
    }

    private fun containsNalType(data: ByteArray, wantedType: Int): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            val start3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            val start4 = i + 4 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (start3 || start4) {
                val nal = i + if (start4) 4 else 3
                if (nal < data.size && (data[nal].toInt() and 0x1F) == wantedType) return true
                i = nal
            } else i++
        }
        // Some legacy handlers deliver one raw NAL without Annex-B start code.
        return data.isNotEmpty() && (data[0].toInt() and 0x1F) == wantedType
    }

    private fun clearRecovery() {
        recovery.clear()
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
        clearRecovery()
        waitingForKeyframe = true
    }
}
