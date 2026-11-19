package top.jast.miracastreceiver.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Miracast 音频播放：把 [TsDemuxer] 解出的音频 PES 解码后写进 AudioTrack。
 *
 * WFD 的音频有两种封装：
 * - AAC（stream_type 0x0F）：PES 负载是一串 ADTS 帧，剥掉 ADTS 头后逐帧喂给 MediaCodec
 * - LPCM（stream_type 0x83）：PES 负载是 4 字节私有头 + 16 位大端 PCM，字节序翻转后直接播放
 *
 * 和视频一样走「收到即播」，不按 PTS 做音画同步：视频是收到即送显的，音频只要不积压，
 * 临时网络突发由有限队列吸收，只有 PTS 确认积压超过 600ms 才追赶；
 * 不能仅凭 PES 个数判断延迟，因为一个 PES 的时长和每批到达数量都不固定。
 *
 * 解码器和 AudioTrack 只在播放线程上创建、使用和释放，不跨线程碰 MediaCodec。
 */
class MiracastAudioPlayer {

    private data class AudioPes(val type: TsDemuxer.AudioCodec, val data: ByteArray, val ptsUs: Long)
    private val queue = ArrayBlockingQueue<AudioPes>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var released = false
    private var thread: Thread? = null
    @Volatile private var resyncRequested = false

    private var codec: MediaCodec? = null
    private var codecConfig = 0
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 0
    private val adts = ByteArrayOutputStream(16 * 1024)
    private var firstPcm = true
    private val droppedPes = AtomicLong()
    @Volatile private var receivedPes = 0L
    @Volatile private var firstSourcePtsUs = 0L
    @Volatile private var latestSourcePtsUs = 0L
    @Volatile private var firstArrivalMs = 0L
    private var decodedFrames = 0L
    private var pcmDurationUs = 0L
    private var inputTimeouts = 0L
    private var lastStatsMs = 0L

    companion object {
        private const val QUEUE_CAPACITY = 64
        // fork 的「按队列个数追赶」阈值；合并后主线采用 PTS 策略，保留以备旧策略复用。
        private const val MAX_BACKLOG = 8
        private const val QUEUE_KEEP = 2
        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }

    /** [TsDemuxer] 的回调入口，运行在解复用线程上，只入队不做重活。 */
    // ptsUs 默认 0L 保留 fork 的两参数调用形式；TsDemuxer 实际回调会传入第三个 PTS 参数。
    fun onAudioPes(codecType: TsDemuxer.AudioCodec, data: ByteArray, ptsUs: Long = 0L) {
        if (released) return
        if (!running) start()
        receivedPes++
        if (firstArrivalMs == 0L) firstArrivalMs = android.os.SystemClock.elapsedRealtime()
        if (ptsUs > 0) {
            if (firstSourcePtsUs == 0L) firstSourcePtsUs = ptsUs
            latestSourcePtsUs = ptsUs
        }
        val item = AudioPes(codecType, data, ptsUs)
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            droppedPes.incrementAndGet()
            resyncRequested = true
        }
    }

    fun onDiscontinuity() {
        resyncRequested = true
    }

    @Synchronized
    private fun start() {
        if (running || released) return
        running = true
        thread = Thread(::runPlayback, "MiracastAudio").apply { start() }
    }

    @Synchronized
    fun release() {
        released = true
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private fun runPlayback() {
        Timber.i("Miracast audio: playback thread started")
        try {
            while (running) {
                var item = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                val stale = AudioBacklogPolicy.dropCount((listOf(item) + queue.toList()).map { it.ptsUs })
                if (stale > 0) {
                    var dropped = 0
                    repeat(stale) {
                        val next = queue.poll() ?: return@repeat
                        item = next
                        dropped++
                    }
                    droppedPes.addAndGet(dropped.toLong())
                    resyncRequested = true
                    Timber.w("Miracast audio: PTS backlog exceeded 600ms, dropped %d PES (total %d)", dropped, droppedPes.get())
                }

                try {
                    when (item.type) {
                        TsDemuxer.AudioCodec.AAC_ADTS -> handleAac(item.data)
                        TsDemuxer.AudioCodec.LPCM -> handleLpcm(item.data)
                    }
                    reportStats()
                } catch (e: Exception) {
                    if (running) Timber.e(e, "Miracast audio: decode error")
                    releaseCodec()                       // 解码器出错后状态不可信，下一帧重建
                    releaseTrack()
                }
            }
        } finally {
            releaseCodec()
            releaseTrack()
            Timber.i("Miracast audio: playback thread stopped, dropped %d PES", droppedPes.get())
        }
    }

    private fun handleAac(data: ByteArray) {
        if (resyncRequested) {
            resyncRequested = false
            adts.reset()
        }
        adts.write(data)
        val buf = adts.toByteArray()
        var pos = 0

        while (pos + 7 <= buf.size) {
            if ((buf[pos].toInt() and 0xFF) != 0xFF || (buf[pos + 1].toInt() and 0xF0) != 0xF0) {
                pos++
                continue
            }
            val b1 = buf[pos + 1].toInt() and 0xFF
            val b2 = buf[pos + 2].toInt() and 0xFF
            val b3 = buf[pos + 3].toInt() and 0xFF
            val b4 = buf[pos + 4].toInt() and 0xFF
            val b5 = buf[pos + 5].toInt() and 0xFF

            val headerLength = if ((b1 and 0x01) != 0) 7 else 9
            val objectType = ((b2 shr 6) and 0x03) + 1
            val srIndex = (b2 shr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)

            if (srIndex >= SAMPLE_RATES.size || channelConfig == 0 || frameLength <= headerLength) {
                pos++
                continue
            }
            if (pos + frameLength > buf.size) break

            ensureAacDecoder(objectType, srIndex, channelConfig)
            decodeAacFrame(buf, pos + headerLength, frameLength - headerLength)
            pos += frameLength
        }

        adts.reset()
        if (pos < buf.size) adts.write(buf, pos, buf.size - pos)
    }

    private fun ensureAacDecoder(objectType: Int, srIndex: Int, channelConfig: Int) {
        val asc = (objectType shl 11) or (srIndex shl 7) or (channelConfig shl 3)
        if (codec != null && asc == codecConfig) return

        releaseCodec()
        val sampleRate = SAMPLE_RATES[srIndex]
        val channels = if (channelConfig == 7) 8 else channelConfig
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            if (objectType == 2) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        codecConfig = asc
        ensureTrack(sampleRate, channels)
        Timber.i("Miracast audio: AAC decoder ready, objectType=$objectType ${sampleRate}Hz x$channels")
    }

    private fun decodeAacFrame(buf: ByteArray, offset: Int, length: Int) {
        val mc = codec ?: return
        val started = System.nanoTime()
        while (running) {
            val inIdx = mc.dequeueInputBuffer(10_000)
            val accepted = inIdx >= 0
            if (accepted) {
                val inBuf = mc.getInputBuffer(inIdx)!!
                inBuf.clear()
                inBuf.put(buf, offset, length)
                mc.queueInputBuffer(inIdx, 0, length, 0, 0)
                decodedFrames++
            }
            // Drain PCM even if input is full, then retry the same AAC frame.
            // Previously that frame was discarded before making room in the codec.
            drainAacOutput(mc)
            if (accepted) return
            if (System.nanoTime() - started >= 500_000_000L) {
                inputTimeouts++
                resyncRequested = true
                Timber.w("Miracast audio: decoder input stalled for 500ms")
                return
            }
        }
    }

    private fun drainAacOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val outBuf = mc.getOutputBuffer(outIdx)!!
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm)
                    mc.releaseOutputBuffer(outIdx, false)
                    writePcm(pcm, pcm.size)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = mc.outputFormat
                    ensureTrack(
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    private fun handleLpcm(data: ByteArray) {
        var offset = 0
        var sampleRate = 48000
        var channels = 2
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == 0xA0) {
            val info = data[3].toInt() and 0xFF
            sampleRate = if (((info shr 3) and 0x07) == 1) 44100 else 48000
            channels = if ((info and 0x07) == 0) 1 else 2
            offset = 4
        }
        val length = (data.size - offset) and 1.inv()
        if (length <= 0) return

        val pcm = ByteArray(length)
        var i = 0
        while (i < length) {
            pcm[i] = data[offset + i + 1]
            pcm[i + 1] = data[offset + i]
            i += 2
        }
        ensureTrack(sampleRate, channels)
        writePcm(pcm, length)
    }

    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && sampleRate == trackSampleRate && channels == trackChannels) return
        releaseTrack()

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        trackSampleRate = sampleRate
        trackChannels = channels
        Timber.i(
            "Miracast audio: AudioTrack ${sampleRate}Hz x$channels, buffer=${minBuf}B " +
                "(~${minBuf * 1000 / (sampleRate * channels * 2)}ms)"
        )
    }

    /** API 23 is the app baseline, so the modern blocking write overload is always available. */
    private fun writePcm(pcm: ByteArray, length: Int) {
        val track = audioTrack ?: return
        if (firstPcm) {
            firstPcm = false
            Timber.i("Miracast audio: first PCM (${length}B) → AudioTrack")
        }
        var offset = 0
        while (offset < length && running) {
            val written = track.write(pcm, offset, length - offset, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "AudioTrack.write failed: $written" }
            offset += written
            pcmDurationUs += written.toLong() * 1_000_000 / (trackSampleRate * trackChannels * 2)
        }
    }

    private fun reportStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsMs < 2_000) return
        lastStatsMs = now
        Timber.i("Miracast audio stats: pes=%d, aac=%d, pcmMs=%d, sourceMs=%d, wallMs=%d, q=%d, drops=%d, inputTimeouts=%d",
            receivedPes, decodedFrames, pcmDurationUs / 1000,
            (latestSourcePtsUs - firstSourcePtsUs) / 1000, now - firstArrivalMs,
            queue.size, droppedPes.get(), inputTimeouts)
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfig = 0
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        trackSampleRate = 0
        trackChannels = 0
    }
}
