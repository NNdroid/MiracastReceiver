package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Wi-Fi Display RTSP session handler for the Sink side. */
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
    private var streamStopNotified = false
    private var optionsSent = false
    private var setupCseq = -1
    private var playCseq = -1

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val MAX_RTSP_BODY_BYTES = 1024 * 1024

        /**
         * H.264 Sink capability.
         *
         * Profile bitmap 0x03 advertises mandatory CBP plus CHP. CEA bit 0 is the mandatory
         * 640x480p60 interoperability baseline; bits 6/7/8 retain 720p60/1080p30/1080p60.
         */
        private const val VIDEO_FORMATS =
            "00 00 03 10 000001C1 00000000 00000000 00 0000 0000 00 none none"

        /**
         * Every audio-capable WFD device must support 2ch 48 kHz 16-bit LPCM (LPCM mode bit 1).
         * AAC-LC stereo remains advertised as an optional preferred compressed format.
         */
        private const val AUDIO_CODECS = "LPCM 00000002 00, AAC 00000001 00"
    }

    fun handleSession() {
        try {
            Timber.i(
                "WFD RTSP session started with source ${socket.inetAddress.hostAddress}:${socket.port} " +
                    "RTP=$rtpPort"
            )
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
                if (!optionsSent) {
                    optionsSent = true
                    sendOptions()
                }
            }
            "GET_PARAMETER" -> {
                if (msg.contains("wfd_", ignoreCase = true)) sendCapabilities(cseq) else sendOk(cseq)
            }
            "SET_PARAMETER" -> {
                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://", ignoreCase = true) }
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
                        0x001L -> "640x480p60"
                        else -> "CEA 0x%08X".format(ceaBit)
                    }
                    Timber.i("WFD: negotiated mode = $mode")
                }
                param(msg, "wfd_audio_codecs")?.let {
                    Timber.i("WFD: source selected audio codec = $it")
                }

                sendOk(cseq)
                if (msg.contains("wfd_trigger_method: SETUP", ignoreCase = true)) sendSetup()
                if (msg.contains("wfd_trigger_method: TEARDOWN", ignoreCase = true)) close()
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                close()
            }
            else -> sendOk(cseq)
        }
    }

    /**
     * Match replies by CSeq. The old implementation treated any response arriving while PLAY was
     * pending as the PLAY acknowledgement. Android sources may reorder/delay the M2 OPTIONS reply,
     * which could start RTP/player state before M7 actually succeeded.
     */
    private fun handleResponse(msg: String) {
        val status = Regex("^RTSP/1\\.0\\s+(\\d+)")
            .find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val cseq = header(msg, "CSeq")?.toIntOrNull() ?: -1
        if (status !in 200..299) {
            Timber.w("WFD: RTSP response failed status=$status cseq=$cseq")
            if (cseq == setupCseq || cseq == playCseq) close()
            return
        }

        when (cseq) {
            setupCseq -> {
                val session = header(msg, "Session")?.substringBefore(';')
                if (session.isNullOrBlank()) {
                    Timber.w("WFD: SETUP response missing Session header")
                    close()
                    return
                }
                sessionId = session
                val transport = header(msg, "Transport")
                Timber.i("WFD: SETUP accepted session=$sessionId transport=${transport ?: "unknown"}")
                onSessionEstablished?.invoke(sessionId)
                sendPlay()
            }
            playCseq -> {
                Timber.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
                onStreamStart?.invoke(rtpPort)
                startPlayerActivity()
                playCseq = -1
            }
            else -> Timber.d("WFD: RTSP response acknowledged cseq=$cseq")
        }
    }

    private fun sendOptions() {
        val cseq = ++outCseq
        send(
            "OPTIONS * RTSP/1.0\r\n" +
                "CSeq: $cseq\r\n" +
                "Require: org.wfa.wfd1.0\r\n\r\n"
        )
    }

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        setupCseq = ++outCseq
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: $setupCseq\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n\r\n"
        )
    }

    private fun sendPlay() {
        playCseq = ++outCseq
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: $playCseq\r\n" +
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
