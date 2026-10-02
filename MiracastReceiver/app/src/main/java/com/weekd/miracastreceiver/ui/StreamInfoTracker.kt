package com.weekd.miracastreceiver.ui

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * 视频流信息采集：把各来源写入的「单调累加计数器」换算成瞬时速率。
 *
 * AirPlay 镜像（StreamStats）和 Miracast（RtpReceiver）都只在热路径上累加字节数/帧数，
 * 由 UI 每秒采样一次做差分，这样采集端零成本、显示端也不用额外的时间窗口逻辑。
 */
class StreamInfoTracker {

    /** 一次采样的结果。首次采样（没有基准点）时全部为 0。 */
    data class Sample(val bitrateBps: Long, val bytesPerSec: Long, val fps: Int)

    private var lastSampleMs = 0L
    private var lastBytes = 0L
    private var lastFrames = 0L

    /** 切换播放源时清零，避免把上一路的累计值算进第一次差分。 */
    fun reset() {
        lastSampleMs = 0L
        lastBytes = 0L
        lastFrames = 0L
    }

    /**
     * 用累计字节数与累计帧数采样一次。
     * [totalBytes] 或 [totalFrames] 变小（计数器被重置）时同样从头开始。
     */
    fun sample(totalBytes: Long, totalFrames: Long): Sample {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastSampleMs
        val isFirst = lastSampleMs == 0L
        val restarted = totalBytes < lastBytes || totalFrames < lastFrames

        val sample = if (isFirst || restarted || elapsedMs <= 0) {
            Sample(0L, 0L, 0)
        } else {
            val bytes = totalBytes - lastBytes
            val frames = totalFrames - lastFrames
            Sample(
                bitrateBps = bytes * 8 * 1000 / elapsedMs,
                bytesPerSec = bytes * 1000 / elapsedMs,
                fps = (frames * 1000 / elapsedMs).toInt()
            )
        }

        lastSampleMs = now
        lastBytes = totalBytes
        lastFrames = totalFrames
        return sample
    }

    companion object {
        /** 码率：自动在 kbps / Mbps 之间切换。 */
        fun formatBitrate(bps: Long): String = when {
            bps <= 0 -> "—"
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
            else -> "${bps / 1000} kbps"
        }

        /** 网速：自动在 KB/s / MB/s 之间切换。 */
        fun formatSpeed(bytesPerSec: Long): String = when {
            bytesPerSec <= 0 -> "—"
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> "${bytesPerSec / 1024} KB/s"
        }

        /** 分辨率，任一边为 0 时显示占位符。 */
        fun formatResolution(width: Int, height: Int): String =
            if (width > 0 && height > 0) "${width}x$height" else "—"

        fun formatFps(fps: Float): String =
            if (fps > 0f) String.format("%.0f fps", fps) else "—"

        /** MIME 类型转成常见叫法。 */
        fun formatCodec(mimeType: String?): String = when (mimeType?.lowercase()) {
            null, "" -> "—"
            MimeTypes.VIDEO_H264 -> "H.264 (AVC)"
            MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_VP8 -> "VP8"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_MP4V -> "MPEG-4"
            MimeTypes.VIDEO_H263 -> "H.263"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC-3)"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC-3 JOC)"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            else -> mimeType.substringAfter('/').uppercase()
        }

        /** HDR 类型来自 Media3 实际视频 Format/ColorInfo。 */
        fun formatHdr(format: Format?): String {
            if (format == null) return "—"
            val mime = format.sampleMimeType
            val codecs = format.codecs.orEmpty().lowercase()
            if (mime == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvhe") || codecs.startsWith("dvh1")) {
                return "Dolby Vision"
            }
            return when (format.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
                C.COLOR_TRANSFER_HLG -> "HLG"
                C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2 -> "SDR"
                else -> if (format.colorInfo?.hdrStaticInfo != null) "HDR" else "SDR / 未标记"
            }
        }

        fun formatColorSpace(format: Format?): String = when (format?.colorInfo?.colorSpace) {
            C.COLOR_SPACE_BT2020 -> "BT.2020"
            C.COLOR_SPACE_BT709 -> "BT.709"
            C.COLOR_SPACE_BT601 -> "BT.601"
            else -> "—"
        }

        fun formatColorRange(format: Format?): String = when (format?.colorInfo?.colorRange) {
            C.COLOR_RANGE_FULL -> "Full"
            C.COLOR_RANGE_LIMITED -> "Limited"
            else -> "—"
        }

        fun formatBitDepth(format: Format?): String {
            val colorInfo = format?.colorInfo ?: return "—"
            val luma = colorInfo.lumaBitdepth
            val chroma = colorInfo.chromaBitdepth
            return when {
                luma > 0 && chroma > 0 && luma == chroma -> "${luma}-bit"
                luma > 0 && chroma > 0 -> "Y ${luma}-bit / C ${chroma}-bit"
                luma > 0 -> "${luma}-bit"
                else -> "—"
            }
        }

        fun formatAudioChannels(format: Format?): String = when (val channels = format?.channelCount ?: Format.NO_VALUE) {
            Format.NO_VALUE, 0 -> "—"
            1 -> "1.0 Mono"
            2 -> "2.0 Stereo"
            6 -> "5.1 ($channels ch)"
            8 -> "7.1 ($channels ch)"
            else -> "$channels ch"
        }

        fun formatSampleRate(format: Format?): String {
            val hz = format?.sampleRate ?: Format.NO_VALUE
            return if (hz > 0) String.format("%.1f kHz", hz / 1000.0) else "—"
        }

        fun formatBufferPercent(bufferedPercentage: Int): String =
            if (bufferedPercentage in 0..100) "$bufferedPercentage%" else "—"

        /**
         * 把标签补齐到固定列宽，让等宽字体下各行数值对齐。
         * 中日韩字符按 2 列宽计算（等宽字体里一个汉字正好占两个字符位）。
         */
        fun padLabel(label: String, columns: Int = 12): String {
            val width = label.fold(0) { acc, c -> acc + if (c.code >= 0x2E80) 2 else 1 }
            return label + " ".repeat((columns - width).coerceAtLeast(1))
        }
    }
}
