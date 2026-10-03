package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import android.os.Build
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
    private var streamStarted = false
    private var sourceIdentity = ""

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val MAX_RTSP_BODY_BYTES = 1024 * 1024

        /** Broad WFD R1 H.264 set retained for existing Windows/Android interoperability. */
        private const val VIDEO_FORMATS =
            "00 00 03 10 0001FFFF 1FFFFFFF 00000FFF 00 0000 0000 00 none none"

        /** LPCM is mandatory; AAC-LC is also supported by our TS audio path. */
        private const val AUDIO_CODECS = "LPCM 00000003 00, AAC 0000000F 00"
    }

    fun handleSession() {
        try {
            Timber.i(
                "WFD RTSP session started with source ${socket.inetAddress.hostAddress}:${socket.port} " +
                    "RTP=$rtpPort RTCP=${rtpPort + 1}"
            )
            while (!socket.isClosed) {
                val msg = readMessage() ?: break
                observeSourceIdentity(msg)
                if (msg.startsWith("RTSP/1.0")) handleResponse(msg) else handleRequest(msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in WFD session")
        } finally {
            Timber.i("WFD session ended source=${sourceIdentity.ifBlank { "unknown" }}")
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
        val method = msg.substringBefore(' ').uppercase()
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
                val requested = requestedParameters(msg)
                if (requested.isEmpty()) sendOk(cseq) else sendCapabilities(cseq, requested)
            }
            "SET_PARAMETER" -> {
                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://", ignoreCase = true) }
                    ?.let {
                        presentationUrl = it
                        Timber.i("WFD: presentation URL = $it")
                    }
                param(msg, "wfd_video_formats")?.let {
                    Timber.i("WFD: source selected video format = $it")
                    logNegotiatedVideoMode(it)
                }
                param(msg, "wfd_audio_codecs")?.let { Timber.i("WFD: source selected audio codec = $it") }

                sendOk(cseq)
                when {
                    msg.contains("wfd_trigger_method: SETUP", ignoreCase = true) -> sendSetup()
                    msg.contains("wfd_trigger_method: PLAY", ignoreCase = true) -> sendPlay()
                    msg.contains("wfd_trigger_method: TEARDOWN", ignoreCase = true) -> close()
                }
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                close()
            }
            "PAUSE", "PLAY" -> sendOk(cseq)
            else -> sendOk(cseq)
        }
    }

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
                sessionId = header(msg, "Session")?.substringBefore(';')?.trim().orEmpty()
                val transport = header(msg, "Transport")
                if (sessionId.isBlank()) {
                    Timber.w("WFD: SETUP response missing Session header; continuing legacy-compatible")
                }
                Timber.i(
                    "WFD: SETUP accepted session=${sessionId.ifBlank { "legacy" }} " +
                        "transport=${transport ?: "unknown"}"
                )
                onSessionEstablished?.invoke(sessionId.ifBlank { "legacy" })
                setupCseq = -1
                sendPlay()
            }
            playCseq -> {
                playCseq = -1
                if (!streamStarted) {
                    streamStarted = true
                    Timber.i("WFD: PLAY acknowledged; waiting for RTP on $rtpPort")
                    onStreamStart?.invoke(rtpPort)
                    startPlayerActivity()
                }
            }
            else -> Timber.d("WFD: RTSP response acknowledged cseq=$cseq")
        }
    }

    private fun sendOptions() {
        val cseq = ++outCseq
        send(
            "OPTIONS * RTSP/1.0\r\n" +
                "CSeq: $cseq\r\n" +
                "Require: org.wfa.wfd1.0\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n\r\n"
        )
    }

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        setupCseq = ++outCseq
        val rtcpPort = (rtpPort + 1).coerceAtMost(65535)
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: $setupCseq\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort-$rtcpPort\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n\r\n"
        )
    }

    private fun sendPlay() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        playCseq = ++outCseq
        val sessionHeader = if (sessionId.isNotBlank()) "Session: $sessionId\r\n" else ""
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: $playCseq\r\n" +
                sessionHeader +
                "User-Agent: MiracastReceiver/1.0\r\n\r\n"
        )
    }

    private fun sendOk(cseq: String, extraHeaders: String = "") {
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n" +
                extraHeaders +
                "\r\n"
        )
    }

    private fun sendCapabilities(cseq: String, requested: Set<String>) {
        val capabilities = capabilityValues()
        val lines = requested.mapNotNull { name -> capabilities[name.lowercase()]?.let { "$name: $it" } }
        val body = if (lines.isEmpty()) "" else lines.joinToString("\r\n", postfix = "\r\n")
        if (body.isEmpty()) {
            sendOk(cseq)
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" +
                body
        )
        Timber.i("WFD: replied to M3 parameters=${lines.map { it.substringBefore(':') }}")
    }

    private fun capabilityValues(): Map<String, String> = linkedMapOf(
        "wfd_video_formats" to VIDEO_FORMATS,
        "wfd_audio_codecs" to AUDIO_CODECS,
        "wfd_3d_video_formats" to "none",
        // WFD M3 uses port1=0 for the sink's RTP capability; SETUP later carries the RTP/RTCP pair.
        "wfd_client_rtp_ports" to "RTP/AVP/UDP;unicast $rtpPort 0 mode=play",
        "wfd_content_protection" to "none",
        "wfd_display_edid" to "none",
        "wfd_coupled_sink" to "none",
        "wfd_uibc_capability" to "none",
        "wfd_standby_resume_capability" to "none",
        "wfd_connector_type" to "05",
        "wfd_idr_request_capability" to "0",
        "wfd_i2c" to "none",
        "intel_friendly_name" to Build.MODEL.take(64),
        "intel_sink_manufacturer_name" to Build.MANUFACTURER.take(64),
        "intel_sink_model_name" to Build.MODEL.take(64),
        "microsoft_cursor" to "none",
        "microsoft_rtcp_capability" to "none",
        "microsoft_latency_management_capability" to "none",
        "microsoft_format_change_capability" to "none",
        "microsoft_diagnostics_capability" to "none"
    )

    private fun requestedParameters(msg: String): LinkedHashSet<String> {
        val body = msg.substringAfter("\r\n\r\n", "")
        if (body.isBlank()) return linkedSetOf()
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains(':') }
            .filter {
                val lower = it.lowercase()
                lower.startsWith("wfd_") || lower.startsWith("intel_") || lower.startsWith("microsoft_")
            }
            .toCollection(linkedSetOf())
    }

    private fun observeSourceIdentity(msg: String) {
        val candidates = listOfNotNull(header(msg, "Server"), header(msg, "User-Agent"))
        if (candidates.isNotEmpty()) {
            sourceIdentity = candidates.joinToString(" | ")
            Timber.d("WFD Source identity: $sourceIdentity")
        }
    }

    private fun logNegotiatedVideoMode(selected: String) {
        val ceaBit = selected.split(Regex("\\s+")).getOrNull(4)?.toLongOrNull(16) ?: 0L
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

    private fun header(msg: String, name: String): String? =
        Regex("(?i)^${Regex.escape(name)}:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun param(msg: String, name: String): String? =
        Regex("(?i)^${Regex.escape(name)}:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun startPlayerActivity() {
        if (!AppSettings.isAutoLaunchPlayer(context)) {
            Timber.i("WFD stream is active; auto-launch player is disabled")
            return
        }
        val intent = Intent(context, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_RTP_PORT, rtpPort)
            putExtra(
                com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SESSION_ID,
                sessionId.ifBlank { "legacy" }
            )
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
