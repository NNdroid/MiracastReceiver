package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Display RTSP 会话处理器（Sink 侧）。
 *
 * 重要：WFD 里 **Source 才是 RTSP 监听方**，Sink 必须主动连到 Source 的 7236 端口。
 * 这一点已对 Windows 11 的 MSMiracastSource 实测确认。连接建立后，双方在同一条 TCP 上
 * 互为客户端和服务端：
 *
 * ```
 * M1  Source → Sink   OPTIONS         本类回 200 + Public
 * M2  Sink   → Source OPTIONS         本类主动发
 * M3  Source → Sink   GET_PARAMETER   本类回能力集（必须含 wfd_client_rtp_ports）
 * M4  Source → Sink   SET_PARAMETER   选定格式 + presentation URL
 * M5  Source → Sink   SET_PARAMETER   wfd_trigger_method: SETUP
 * M6  Sink   → Source SETUP           本类主动发，带 client_port
 * M7  Sink   → Source PLAY            本类主动发，之后 RTP 开始流入
 * ```
 *
 * @param socket 已连接到 Source 的 TCP 连接
 * @param rtpPort 本机用于接收 RTP 的 UDP 端口，会在 M3 和 M6 里告知 Source
 */
class WfdSessionHandler(
    private val context: Context,
    private val socket: Socket,
    private val rtpPort: Int
) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    private var outCseq = 0
    private var sessionId = ""
    private var presentationUrl = ""
    private var playRequested = false
    private var streamStopNotified = false

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val MAX_RTSP_BODY_BYTES = 1024 * 1024

        /**
         * 本机作为 Sink 声明的能力集。字段依次为：
         * native / preferred-display-mode / profile / level / CEA / VESA / HH /
         * latency / min-slice-size / slice-enc-params / frame-rate-control / max-hres / max-vres
         *
         * CEA 位图只声明三档，Windows 实测会挑其中最高的一档：
         * ```
         * bit 8 (0x100) = 1920x1080p60   ← 目标：帧间隔 16ms
         * bit 7 (0x080) = 1920x1080p30      链路撑不住时的退路
         * bit 6 (0x040) = 1280x720p60       再退一档
         * ```
         * 之前用的 0x0001DEFF 看着覆盖很广，但**恰好没有 bit 8**，所以 Windows 只能选到
         * 1080p30，帧间隔 33ms —— 而视频 PES 不定长，必须等下一帧首包才知道当前帧结束，
         * 这个等待直接等于帧间隔，是延迟的大头。
         *
         * level 同步提到 0x10（H.264 Level 4.2）：1080p60 超出了 Level 4.0 的上限。
         */
        private const val VIDEO_FORMATS =
            "00 00 02 10 000001C0 00000000 00000000 00 0000 0000 00 none none"
        private const val AUDIO_CODECS = "AAC 00000001 00"
    }

    fun handleSession() {
        try {
            Timber.i("WFD session started with source ${socket.inetAddress.hostAddress}")
            while (!socket.isClosed) {
                val msg = readMessage() ?: break
                if (msg.startsWith("RTSP/1.0")) handleResponse(msg) else handleRequest(msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in WFD session")
        } finally {
            Timber.i("WFD session ended")
            notifyStreamStopOnce()
            close()
        }
    }

    private fun readMessage(): String? {
        val buf = StringBuilder()
        val one = ByteArray(1)

        while (!buf.endsWith("\r\n\r\n")) {
            val n = input.read(one)
            if (n <= 0) return null
            buf.append(one[0].toInt().toChar())
            if (buf.length > 64 * 1024) {
                Timber.w("WFD: header too large, dropping session")
                return null
            }
        }

        val contentLength = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(buf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > MAX_RTSP_BODY_BYTES) {
            Timber.w("WFD: RTSP body too large ($contentLength bytes), dropping session")
            return null
        }
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) return null
                read += n
            }
            buf.append(String(body, StandardCharsets.UTF_8))
        }

        val msg = buf.toString()
        Timber.d("WFD >> ${msg.lineSequence().first()}")
        Timber.v("WFD >> full:\n$msg")
        return msg
    }

    private fun send(msg: String) {
        Timber.d("WFD << ${msg.lineSequence().first()}")
        Timber.v("WFD << full:\n$msg")
        output.write(msg.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun handleRequest(msg: String) {
        val method = msg.substringBefore(' ')
        val cseq = header(msg, "CSeq") ?: "0"

        when (method) {
            "OPTIONS" -> {
                sendOk(cseq, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                sendOptions()
            }
            "GET_PARAMETER" -> {
                if (msg.contains("wfd_")) sendCapabilities(cseq) else sendOk(cseq)
            }
            "SET_PARAMETER" -> {
                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://") }
                    ?.let {
                        presentationUrl = it
                        Timber.i("WFD: presentation URL = $it")
                    }

                param(msg, "wfd_video_formats")?.let { selected ->
                    Timber.i("WFD: source selected video format = $selected")
                    val ceaBit = selected.split(' ').getOrNull(4)?.toLongOrNull(16) ?: 0L
                    val mode = when (ceaBit) {
                        0x100L -> "1920x1080p60"
                        0x080L -> "1920x1080p30"
                        0x040L -> "1280x720p60"
                        0x020L -> "1280x720p30"
                        else -> "CEA 0x%08X".format(ceaBit)
                    }
                    Timber.i("WFD: negotiated mode = $mode")
                }
                param(msg, "wfd_audio_codecs")?.let {
                    Timber.i("WFD: source selected audio codec = $it")
                }
                sendOk(cseq)
                if (msg.contains("wfd_trigger_method: SETUP")) sendSetup()
                if (msg.contains("wfd_trigger_method: TEARDOWN")) close()
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                close()
            }
            else -> sendOk(cseq)
        }
    }

    private fun handleResponse(msg: String) {
        val session = header(msg, "Session")?.substringBefore(';')
        if (!session.isNullOrBlank() && sessionId.isEmpty()) {
            sessionId = session
            Timber.i("WFD: session id = $sessionId")
            onSessionEstablished?.invoke(sessionId)
            sendPlay()
        } else if (playRequested) {
            Timber.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
            onStreamStart?.invoke(rtpPort)
            startPlayerActivity()
            playRequested = false
        }
    }

    private fun sendOptions() = send(
        "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: ${++outCseq}\r\n" +
            "Require: org.wfa.wfd1.0\r\n\r\n"
    )

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n\r\n"
        )
    }

    private fun sendPlay() {
        playRequested = true
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Session: $sessionId\r\n\r\n"
        )
    }

    private fun sendOk(cseq: String, extraHeaders: String = "") =
        send("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n")

    private fun sendCapabilities(cseq: String) {
        val body = buildString {
            append("wfd_video_formats: $VIDEO_FORMATS\r\n")
            append("wfd_audio_codecs: $AUDIO_CODECS\r\n")
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_content_protection: none\r\n")
            append("wfd_display_edid: none\r\n")
            append("wfd_uibc_capability: none\r\n")
            append("wfd_connector_type: 05\r\n")
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" +
                body
        )
    }

    private fun header(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun param(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun startPlayerActivity() {
        if (!AppSettings.isAutoLaunchPlayer(context)) {
            Timber.i("WFD stream is active; auto-launch player is disabled")
            return
        }
        val intent = Intent(context, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_RTP_PORT, rtpPort)
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    private fun notifyStreamStopOnce() {
        if (streamStopNotified) return
        streamStopNotified = true
        onStreamStop?.invoke()
    }

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing WFD session socket")
        }
    }
}
