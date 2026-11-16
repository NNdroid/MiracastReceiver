package top.jast.miracastreceiver.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import top.jast.miracastreceiver.util.Logger

/**
 * Low-latency AVC/HEVC decoder shared by AirPlay (mirror stream + legacy renderer) and Miracast.
 *
 * Android TV devices often expose both vendor hardware codecs and generic software codecs. For a
 * realtime second-screen receiver we try hardware (preferably vendor) codecs first, falling back
 * to any compatible decoder only if every hardware candidate fails to configure.
 *
 * A dedicated output thread presents each frame the instant MediaCodec produces it — waiting for
 * the next NAL unit to arrive before draining the output queue would add a full frame interval
 * of latency. [release] stops/joins that thread before touching the codec.
 */
class VideoDecoder(private val outputSurface: Surface) {

    private var mediaCodec: MediaCodec? = null

    @Volatile
    private var isInitialized = false

    /** False once MediaCodec has thrown (unrecoverable error state). Recreate the decoder. */
    @Volatile
    var isHealthy = true
        private set

    /** Dedicated output thread — see class doc. */
    private var outputThread: Thread? = null
    @Volatile private var outputRunning = false

    @Volatile var framesQueued = 0L
        private set
    @Volatile var framesReleased = 0L
        private set

    /**
     * Initializes the MediaCodec decoder with the negotiated stream parameters. Call ONCE before
     * [decodeNalUnit]/[tryQueueNalUnit]. SPS/PPS (and VPS for HEVC) come from the SDP ANNOUNCE or
     * the AirPlay 2 mirror SETUP. AVC by default; HEVC for a negotiated hvcC configuration.
     */
    fun initialize(
        spsBytes: ByteArray,
        ppsBytes: ByteArray,
        width: Int,
        height: Int,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        vpsBytes: ByteArray? = null
    ) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // SPS carries the true resolution (AirPlay SDP has no width/height). Validate the parsed
        // result: a malformed SPS can still yield nonsense that wedges MediaCodec. The hardware
        // decoder reads the real size from csd-0 and reports it via INFO_OUTPUT_FORMAT_CHANGED.
        val parsed = if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) parseSpsResolution(spsBytes) else null
        val (actualWidth, actualHeight) = when {
            mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC -> Pair(width, height)
            parsed != null && isPlausibleSize(parsed.first, parsed.second) -> parsed
            else -> {
                Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
                Pair(width, height)
            }
        }

        Logger.i("Initializing $mimeType decoder: ${actualWidth}x$actualHeight (hint ${width}x${height})")
        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight
        StreamStats.videoCodec = mimeType

        // Build fresh CSD buffers for each attempt — configure() may consume them.
        fun createFormat(lowLatency: Boolean) = MediaFormat.createVideoFormat(
            mimeType, actualWidth, actualHeight
        ).apply {
            if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                require(vpsBytes != null) { "HEVC requires VPS" }
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(vpsBytes + spsBytes + ppsBytes))
            } else {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))
            }
            // Do NOT set KEY_MAX_WIDTH/HEIGHT: this SoC's MStar decoder rejects adaptive playback
            // and produces banding. Resolution changes recreate the decoder in MirrorStreamServer.
            // Some MTK codecs reject these hints instead of ignoring them — hence lowLatency flag.
            if (lowLatency) {
                setInteger("low-latency", 1)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)   // 0 = realtime task
        }

        mediaCodec = try {
            createAndStartDecoder(createFormat(true), mimeType, actualWidth, actualHeight)
        } catch (firstFailure: Exception) {
            Logger.w("VideoDecoder low-latency initialization failed; retrying without hints: ${firstFailure.message}")
            try {
                createAndStartDecoder(createFormat(false), mimeType, actualWidth, actualHeight)
            } catch (fallbackFailure: Exception) {
                fallbackFailure.addSuppressed(firstFailure)
                throw fallbackFailure
            }
        }

        isInitialized = true
        isHealthy = true
        startOutputThread()
        Logger.i("$mimeType decoder initialized successfully")
    }

    /**
     * Creates, configures and starts a decoder.
     * HEVC uses the same hardware decoder whose 4K capability was advertised to the sender.
     * AVC prefers vendor hardware codecs (skipping broken candidates), keeping the framework
     * preferred decoder as a final compatibility fallback (enumeration is incomplete on some builds).
     */
    private fun createAndStartDecoder(
        format: MediaFormat, mimeType: String, width: Int, height: Int
    ): MediaCodec {
        if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            val decoderName = requireNotNull(AirPlayCapabilities.hevcDecoderName(width, height)) {
                "No compatible HEVC hardware decoder"
            }
            val codec = MediaCodec.createByCodecName(decoderName)
            try {
                codec.configure(format, outputSurface, null, 0)
                codec.start()
                lastDecoderName = decoderName
                lastDecoderHardwareAccelerated = true
                return codec
            } catch (e: Exception) {
                runCatching { codec.release() }.onFailure { e.addSuppressed(it) }
                throw e
            }
        }

        val candidates = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .distinctBy { it.name }
                .sortedWith(
                    compareByDescending<MediaCodecInfo> { isHardwareCodec(it) }
                        .thenByDescending { isVendorCodec(it) }
                )
                .toList()
        }.getOrElse {
            Logger.w("Unable to enumerate $mimeType decoders: ${it.message}")
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
                Logger.i("Decoder started: ${info.name} (${if (hardware) "hardware" else "software/fallback"})")
                return codec
            } catch (e: Exception) {
                Logger.w("Decoder ${info.name} failed to start: ${e.message}")
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
        }

        val fallback = MediaCodec.createDecoderByType(mimeType)
        try {
            fallback.configure(format, outputSurface, null, 0)
            fallback.start()
            lastDecoderName = "framework-preferred"
            lastDecoderHardwareAccelerated = false
            Logger.w("Using framework-selected $mimeType decoder fallback")
            return fallback
        } catch (e: Exception) {
            runCatching { fallback.release() }
            throw e
        }
    }

    /** 启动输出线程：阻塞等待解码完成的帧并立即送显（送显是延迟敏感路径）。 */
    private fun startOutputThread() {
        outputRunning = true
        outputThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (outputRunning) {
                val codec = mediaCodec ?: break
                try {
                    val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_BUFFER_TIMEOUT_US)
                    when {
                        index >= 0 -> {
                            codec.releaseOutputBuffer(index, true)
                            framesReleased++
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            publishOutputSize(codec.outputFormat)
                        // INFO_TRY_AGAIN_LATER: timeout with no output — keep waiting.
                    }
                } catch (e: IllegalStateException) {
                    if (outputRunning) {
                        Logger.e("VideoDecoder output thread: codec in error state", e)
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

    /** Decodes a single NAL unit and presents it on the Surface (no UI-thread involvement). */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        if (!tryQueueNalUnit(nalUnit, presentationTimeUs)) {
            Logger.v("VideoDecoder: input not accepted, dropping NAL unit")
        }
    }

    /** False means no input was queued. Miracast may retry the SAME access unit. */
    fun tryQueueNalUnit(nalUnit: ByteArray, presentationTimeUs: Long): Boolean {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return false
        }
        try {
            // Output draining is handled by the dedicated output thread (see startOutputThread).
            // Wait for an input buffer — on a modest SoC waiting beats dropping (which corrupts
            // the stream) while INPUT_BUFFER_TIMEOUT_US still bounds a truly wedged codec.
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return false
                if (nalUnit.size > inputBuffer.remaining()) {
                    Logger.w("VideoDecoder input frame too large: ${nalUnit.size} > ${inputBuffer.remaining()}")
                    return false
                }
                inputBuffer.clear()
                inputBuffer.put(nalUnit)
                codec.queueInputBuffer(inputBufferIndex, 0, nalUnit.size, presentationTimeUs, 0)
                framesQueued++
                return true
            }
            return false
        } catch (e: IllegalStateException) {
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
            isHealthy = false
        }
        return false
    }

    /*
     * 送显策略：releaseOutputBuffer(index, true) 立即渲染，不做定时对齐音画 ——
     * 这个 Surface 的 BufferQueue 只有约 3 帧，任何等待都会迅速反压到解码器，导致积压、
     * 延迟飙升并丢帧（丢帧即花屏）。音画对齐靠 AudioStreamServer 保持音频路径低延迟。
     */

    /** Reads the decoder's true display size (honouring the crop rectangle) for the UI. */
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

    /**
     * Switches the output Surface without rebuilding the decoder (API 23+ setOutputSurface).
     * Manual rotation flips SurfaceView ↔ TextureView; rebuilding would wait for the next IDR,
     * and a static image may not get a keyframe for seconds (black screen). Returns false on
     * failure so the caller can rebuild.
     */
    fun switchOutputSurface(surface: Surface): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val codec = mediaCodec ?: return false
        return try {
            codec.setOutputSurface(surface)
            true
        } catch (e: Exception) {
            Logger.w("VideoDecoder: setOutputSurface failed, caller will rebuild (${e.message})")
            false
        }
    }

    /** Releases all MediaCodec resources. Stops/joins the output thread BEFORE releasing. */
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

    /** True if (w, h) look like a real video resolution — rejects parser garbage. */
    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        // 输出线程阻塞等待上限（µs）：短于一帧，解码器有输出会立刻返回；超时只用于检查退出标志。
        private const val OUTPUT_BUFFER_TIMEOUT_US = 20_000L
        // Input-buffer wait: generous enough to avoid dropping NAL units, bounded for wedged codecs.
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
            // Pre-API 29: name-based approximation (media3 uses a similar approach). Unknown
            // vendor codecs are treated as hardware candidates; generic platform codecs as software.
            val name = info.name.lowercase()
            val softwarePrefixes = listOf(
                "omx.google.", "c2.android.", "c2.google.",
                "omx.ffmpeg.", "omx.pv.", "omx.k3.ffmpeg."
            )
            return softwarePrefixes.none { name.startsWith(it) }
        }

        private fun isVendorCodec(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isVendor
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android."))
        }

        /**
         * Parses the H.264 SPS NAL unit for its (cropped) display resolution.
         * AirPlay SDP has no width/height — the true size lives in the SPS
         * (`pic_width_in_mbs_minus1` / `pic_height_in_map_units_minus1`, 16×16 macroblocks).
         * Handles Baseline/Main/High profiles; returns null (→ caller uses hint) on malformed input.
         * `internal` for unit testing without an Android runtime.
         */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null

                // All callers pass SPS with an Annex-B start code (MirrorStreamServer passes
                // sc+sps, MiracastVideoRenderer grabs it from the stream). Skipping it is
                // mandatory: a fixed startOffset=1 reads misaligned bits (e.g. 1920x1080 decoded
                // as 190x140, which even passed the plausibility check).
                val nalStart = when {
                    sps.size >= 4 && sps[0].toInt() == 0 && sps[1].toInt() == 0 &&
                        sps[2].toInt() == 0 && sps[3].toInt() == 1 -> 4
                    sps[0].toInt() == 0 && sps[1].toInt() == 0 && sps[2].toInt() == 1 -> 3
                    else -> 0
                }
                if (nalStart + 1 >= sps.size) return null

                val reader = SpsBitReader(sps, startOffset = nalStart + 1)  // skip NAL header (0x67)
                val profileIdc = reader.readBits(8)
                reader.readBits(8)   // constraint flags + 2 reserved zeros
                reader.readBits(8)   // level_idc
                reader.readUe()      // seq_parameter_set_id

                // Baseline/Main defaults per H.264 spec.
                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0

                // High profiles carry extra fields before the common ones.
                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)
                    }
                    reader.readUe()     // bit_depth_luma_minus8
                    reader.readUe()     // bit_depth_chroma_minus8
                    reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                    if (reader.readBits(1) == 1) {  // seq_scaling_matrix_present_flag
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) {  // seq_scaling_list_present_flag[i]
                                reader.skipScalingList(if (it < 6) 16 else 64)
                            }
                        }
                    }
                }

                reader.readUe()   // log2_max_frame_num_minus4
                when (reader.readUe()) {  // pic_order_cnt_type
                    0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                    1 -> {
                        reader.readBits(1)  // delta_pic_order_always_zero_flag
                        reader.readSe()     // offset_for_non_ref_pic
                        reader.readSe()     // offset_for_top_to_bottom_field
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                }

                reader.readUe()     // max_num_ref_frames
                reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) {
                    reader.readBits(1)  // mb_adaptive_frame_field_flag
                }
                reader.readBits(1)  // direct_8x8_inference_flag

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {  // frame_cropping_flag
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc
                val subWidthC = when (chromaArrayType) {
                    0 -> 1
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = if (chromaArrayType == 1) 2 else 1
                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
                return Pair(width, height)
            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        /** Bitstream reader for H.264 NAL units (MSB-first, unsigned/signed Exp-Golomb). */
        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos = 7

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) { bitPos = 7; bytePos++ }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            /** Reads an unsigned Exp-Golomb code ue(v). */
            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0
                       else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            /** Reads a signed Exp-Golomb code se(v). */
            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            /** Skips a scaling list of [size] entries (High Profile SPS §7.3.2.1.1.1). */
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
