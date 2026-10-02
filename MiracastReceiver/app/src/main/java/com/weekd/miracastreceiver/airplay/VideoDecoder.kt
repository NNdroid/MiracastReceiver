package com.weekd.miracastreceiver.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.weekd.miracastreceiver.util.Logger

/**
 * Low-latency H.264 decoder shared by AirPlay mirroring and Miracast.
 *
 * Android TV devices often expose both vendor hardware codecs and generic software codecs. For a
 * realtime second-screen receiver we explicitly try hardware codecs first, then fall back to any
 * compatible decoder only if every hardware candidate fails to configure.
 */
class VideoDecoder(private val outputSurface: Surface) {

    private var mediaCodec: MediaCodec? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    var isHealthy = true
        private set

    private var outputThread: Thread? = null

    @Volatile
    private var outputRunning = false

    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        val parsed = parseSpsResolution(spsBytes)
        val (actualWidth, actualHeight) = parsed
            ?.takeIf { isPlausibleSize(it.first, it.second) }
            ?: run {
                Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
                width to height
            }

        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight
        StreamStats.videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            actualWidth,
            actualHeight
        ).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))

            // Best-effort low-latency hints. Unknown vendor keys are ignored by codecs that do not
            // implement them. Priority 0 marks the stream as realtime where supported.
            setInteger("low-latency", 1)
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x$actualHeight (hint ${width}x$height)")
        mediaCodec = createAndStartPreferredDecoder(format)
        isInitialized = true
        isHealthy = true
        startOutputThread()
    }

    /**
     * Prefer vendor hardware decoders. A broken vendor codec is skipped and the next candidate is
     * tried, so hardware preference never turns into a hard compatibility requirement.
     */
    private fun createAndStartPreferredDecoder(format: MediaFormat): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val candidates = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .distinctBy { it.name }
                .sortedWith(
                    compareByDescending<MediaCodecInfo> { isHardwareCodec(it) }
                        .thenByDescending { isVendorCodec(it) }
                )
                .toList()
        }.getOrElse {
            Logger.w("Unable to enumerate AVC decoders: ${it.message}")
            emptyList()
        }

        for (info in candidates) {
            val hardware = isHardwareCodec(info)
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, outputSurface, null, 0)
                codec.start()
                lastDecoderName = info.name
                lastDecoderHardwareAccelerated = hardware
                Logger.i(
                    "H.264 decoder started: ${info.name} " +
                        "(${if (hardware) "hardware" else "software/fallback"})"
                )
                return codec
            } catch (e: Exception) {
                Logger.w("Decoder ${info.name} failed to start: ${e.message}")
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
        }

        // Codec enumeration can be incomplete on old/vendor Android builds. Keep the framework
        // preferred decoder as a final compatibility fallback.
        val fallback = MediaCodec.createDecoderByType(mime)
        try {
            fallback.configure(format, outputSurface, null, 0)
            fallback.start()
            lastDecoderName = "framework-preferred"
            lastDecoderHardwareAccelerated = false
            Logger.w("Using framework-selected AVC decoder fallback")
            return fallback
        } catch (e: Exception) {
            runCatching { fallback.release() }
            throw e
        }
    }

    private fun startOutputThread() {
        outputRunning = true
        outputThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (outputRunning) {
                val codec = mediaCodec ?: break
                try {
                    when (val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_BUFFER_TIMEOUT_US)) {
                        in 0..Int.MAX_VALUE -> codec.releaseOutputBuffer(index, true)
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> publishOutputSize(codec.outputFormat)
                    }
                } catch (e: IllegalStateException) {
                    if (outputRunning) {
                        Logger.e("VideoDecoder output thread: codec entered error state", e)
                        isHealthy = false
                    }
                    break
                } catch (e: Exception) {
                    if (outputRunning) Logger.e("VideoDecoder output thread error", e)
                    break
                }
            }
            Logger.d("VideoDecoder output thread exited")
        }, "VideoDecoderOutput").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return
        }

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                if (nalUnit.size > inputBuffer.remaining()) {
                    Logger.w("VideoDecoder input frame too large: ${nalUnit.size} > ${inputBuffer.remaining()}")
                    return
                }
                inputBuffer.put(nalUnit)
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    nalUnit.size,
                    presentationTimeUs,
                    0
                )
            } else {
                // Realtime mirroring prefers a current frame over building a latency backlog.
                Logger.v("VideoDecoder: no input buffer available, dropping NAL unit")
            }
        } catch (e: IllegalStateException) {
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
        }
    }

    private fun publishOutputSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-left") && format.containsKey("crop-right") &&
            format.containsKey("crop-top") && format.containsKey("crop-bottom")
        ) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (isPlausibleSize(w, h)) {
            StreamStats.videoWidth = w
            StreamStats.videoHeight = h
            Logger.i("Video output size ${w}x$h")
        }
    }

    fun release() {
        Logger.d("Releasing VideoDecoder")
        outputRunning = false
        outputThread?.let { thread ->
            runCatching { thread.join(500) }
            if (thread.isAlive) Logger.w("VideoDecoder output thread did not exit in time")
        }
        outputThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            mediaCodec = null
            isInitialized = false
        }
    }

    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        private const val OUTPUT_BUFFER_TIMEOUT_US = 20_000L
        private const val INPUT_BUFFER_TIMEOUT_US = 100_000L

        @Volatile
        var lastDecoderName: String = ""
            private set

        @Volatile
        var lastDecoderHardwareAccelerated: Boolean = false
            private set

        private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isHardwareAccelerated && !info.isSoftwareOnly
            }

            // Pre-API 29 does not expose hardware/software flags. Media3 uses a similar name-based
            // approximation on those releases. Keep the list conservative: unknown vendor codecs
            // are treated as hardware candidates and generic platform codecs as software.
            val name = info.name.lowercase()
            val softwarePrefixes = listOf(
                "omx.google.",
                "c2.android.",
                "c2.google.",
                "omx.ffmpeg.",
                "omx.pv.",
                "omx.k3.ffmpeg."
            )
            return softwarePrefixes.none { name.startsWith(it) }
        }

        private fun isVendorCodec(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isVendor
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android."))
        }

        /** Parse H.264 SPS and return the cropped display size. */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null

                val nalStart = when {
                    sps.size >= 4 && sps[0].toInt() == 0 && sps[1].toInt() == 0 &&
                        sps[2].toInt() == 0 && sps[3].toInt() == 1 -> 4
                    sps[0].toInt() == 0 && sps[1].toInt() == 0 && sps[2].toInt() == 1 -> 3
                    else -> 0
                }
                if (nalStart + 1 >= sps.size) return null

                val reader = SpsBitReader(sps, startOffset = nalStart + 1)
                val profileIdc = reader.readBits(8)
                reader.readBits(8)
                reader.readBits(8)
                reader.readUe()

                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0
                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)
                    }
                    reader.readUe()
                    reader.readUe()
                    reader.readBits(1)
                    if (reader.readBits(1) == 1) {
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) {
                                reader.skipScalingList(if (it < 6) 16 else 64)
                            }
                        }
                    }
                }

                reader.readUe()
                when (val picOrderCntType = reader.readUe()) {
                    0 -> reader.readUe()
                    1 -> {
                        reader.readBits(1)
                        reader.readSe()
                        reader.readSe()
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                    else -> Unit
                }

                reader.readUe()
                reader.readBits(1)

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) reader.readBits(1)
                reader.readBits(1)

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)
                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

                val subWidthC = when (chromaArrayType) {
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = if (chromaArrayType == 1) 2 else 1
                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) {
                    2 - frameMbsOnlyFlag
                } else {
                    subHeightC * (2 - frameMbsOnlyFlag)
                }

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x$height (profile=$profileIdc)")
                return width to height
            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos = 7

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) {
                    bitPos = 7
                    bytePos++
                }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0 else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            fun skipScalingList(size: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(size) {
                    if (nextScale != 0) {
                        val deltaScale = readSe()
                        nextScale = (lastScale + deltaScale + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }
        }
    }
}
