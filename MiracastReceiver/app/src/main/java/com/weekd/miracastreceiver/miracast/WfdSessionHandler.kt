package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import android.os.Build
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Display RTSP session handler for the Sink side.
 *
 * The Sink is the RTSP *server*: the Source dials the control port advertised in the WFD
 * information element and drives the M3 exchange with requests. Every method therefore gets a
 * `RTSP/1.0 200 OK` reply, and the Sink's own RTP port travels back in the `Transport:` header of
 * the SETUP response — that header is the one place a Source is guaranteed to read it from.
 *
 * Sending SETUP or PLAY as outgoing *requests* would make us a second client. The Source is not
 * listening for one, so both peers would just wait on each other until the Source's RTSP timeout
 * tears the group down. Two servers is a deadlock, not a protocol.
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
    private var streamStopNotified = false
    private var optionsSent = false
    private var setupDone = false
    private var streamStarted = false
    private var sourceIdentity = ""

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val MAX_RTSP_BODY_BYTES = 1024 * 1024

        /**
         * Silence budget before a request has been negotiated. A real Source sends its first
         * OPTIONS or GET_PARAMETER within a second or two of dialing; a connection that sits
         * silent this long is a half-open socket — the peer crashed or its FIN was lost in transit
         * and the kernel will never deliver EOF. Without a timeout `readMessage` blocks on that
         * socket forever, which is how one abandoned probe can wedge the whole RTSP server.
         */
        private const val IDLE_TIMEOUT_MS = 30_000L

        /**
         * Silence budget once PLAY has been acknowledged. RTP now carries the media, so RTSP going
         * quiet is normal — a Source holds the control socket open for the entire session and may
         * not send TEARDOWN at all when it is closed from the phone side. The budget exists only to
         * bound how long a half-open stream session can occupy its handler.
         */
        private const val IDLE_TIMEOUT_STREAMING_MS = 10 * 60 * 1000L

        /** Broad WFD R1 H.264 set retained for existing Windows/Android interoperability. */
        private const val VIDEO_FORMATS =
            "00 00 03 10 0001FFFF 1FFFFFFF 00000FFF 00 0000 0000 00 none none"

        /** LPCM is mandatory; AAC-LC is also supported by our TS audio path. */
        private const val AUDIO_CODECS = "LPCM 00000003 00, AAC 0000000F 00"

        private const val PUBLIC_METHODS =
            "Public: org.wfa.wfd1.0, DESCRIBE, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"
    }

    fun handleSession() {
        try {
            // A blocking read with no timeout is what lets a dead peer pin this handler forever:
            // the kernel never hands back EOF for a half-open connection, so readMessage never
            // returns and no later Source gets answered.
            runCatching { socket.soTimeout = IDLE_TIMEOUT_MS.toInt() }
            Timber.i(
                "WFD RTSP session started with source ${socket.inetAddress.hostAddress}:${socket.port} " +
                    "RTP=$rtpPort RTCP=${rtcpPort()}"
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

    /** Arm the idle budget appropriate to the current phase. */
    private fun armReadTimeout() {
        runCatching {
            socket.soTimeout = if (streamStarted) IDLE_TIMEOUT_STREAMING_MS.toInt() else IDLE_TIMEOUT_MS.toInt()
        }
    }

    /**
     * One RTSP message. Returns null when the peer went away, or when it has been silent long
     * enough to be considered gone; a timeout while a stream is live is not a failure — RTSP is
     * quiet for the whole duration of one, so the phase decides.
     */
    private fun readMessage(): String? {
        val buf = StringBuilder()
        val one = ByteArray(1)
        while (!buf.endsWith("\r\n\r\n")) {
            val n = try {
                input.read(one)
            } catch (e: SocketTimeoutException) {
                if (streamStarted) {
                    // Streaming: silence is expected, just extend the wait.
                    armReadTimeout()
                    continue
                }
                Timber.i("WFD: source silent for ${IDLE_TIMEOUT_MS}ms before negotiating; closing")
                return null
            }
            if (n <= 0) return null
            armReadTimeout()
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
                val n = try {
                    input.read(body, read, contentLength - read)
                } catch (e: SocketTimeoutException) {
                    Timber.w("WFD: source stalled mid-body after $read/$contentLength bytes")
                    return null
                }
                if (n <= 0) return null
                armReadTimeout()
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
                sendOk(cseq, PUBLIC_METHODS)
                if (!optionsSent) {
                    optionsSent = true
                    sendOptions()
                }
            }
            "DESCRIBE" -> sendDescribe(cseq)
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
                param(msg, "wfd_trigger_method")?.let {
                    Timber.i("WFD: source selected trigger method = $it")
                }

                // The Source drives the trigger itself: SETUP is the WFA WFD default and PLAY-only
                // Sources skip straight to it. Either arrives as its own method.
                sendOk(cseq)
            }
            "SETUP" -> sendSetupResponse(cseq)
            "PLAY" -> sendPlayResponse(cseq)
            "PAUSE" -> sendOk(cseq)
            "TEARDOWN" -> {
                sendOk(cseq)
                close()
            }
            else -> sendOk(cseq)
        }
    }

    /**
     * Reply to the Source's SETUP. `Transport:` carries our RTP/RTCP pair because that is the field
     * every WFD Source parses for the receive port; the WFD parameters were already offered in M3.
     */
    private fun sendSetupResponse(cseq: String) {
        if (sessionId.isBlank()) {
            sessionId = "wfd-" + System.nanoTime().toString(16)
        }
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: $sessionId\r\n" +
                "Transport: RTP/AVP/UDP;unicast;server_port=$rtpPort-${rtcpPort()}\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n" +
                "\r\n"
        )
        Timber.i("WFD: SETUP accepted session=$sessionId server_port=$rtpPort-${rtcpPort()}")
        if (!setupDone) {
            setupDone = true
            onSessionEstablished?.invoke(sessionId)
        }
    }

    /**
     * Reply to the Source's PLAY. RTP was bound before M3 advertised the port, so this is only the
     * go signal: report the stream live and bring up the UI.
     */
    private fun sendPlayResponse(cseq: String) {
        if (sessionId.isBlank()) {
            sessionId = "wfd-" + System.nanoTime().toString(16)
        }
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: $sessionId\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n" +
                "\r\n"
        )
        Timber.i("WFD: PLAY acknowledged; waiting for RTP on $rtpPort")
        if (!streamStarted) {
            streamStarted = true
            onStreamStart?.invoke(rtpPort)
            startPlayerActivity()
        }
    }

    /** Some Sources send DESCRIBE instead of GET_PARAMETER to collect the WFD parameter set. */
    private fun sendDescribe(cseq: String) {
        val capabilities = capabilityValues()
        val body = capabilities.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "User-Agent: MiracastReceiver/1.0\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "\r\n" +
                body
        )
        Timber.i("WFD: replied to DESCRIBE with ${capabilities.size} parameters")
    }

    /**
     * We only ever send one request, the OPTIONS echo, so a response is ours if and only if its
     * CSeq is the one we just issued. There is nothing to correlate SETUP or PLAY against: those
     * are requests *from* the Source, never our own.
     */
    private fun handleResponse(msg: String) {
        val status = Regex("^RTSP/1\\.0\\s+(\\d+)")
            .find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val cseq = header(msg, "CSeq")?.toIntOrNull() ?: -1
        if (status !in 200..299) {
            Timber.w("WFD: RTSP response failed status=$status cseq=$cseq")
            if (cseq == outCseq) close()
            return
        }
        Timber.d("WFD: RTSP response acknowledged cseq=$cseq")
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
        // The Sink is the receiver, so mode=recv; the SETUP Transport header carries the real pair.
        "wfd_client_rtp_ports" to "RTP/AVP/UDP;unicast 0 $rtpPort mode=recv",
        "wfd_trigger_method" to "SETUP",
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

    /**
     * The parameters a Source asked for. WFD Sources normally list them in the request body; a
     * Source that sends `GET_PARAMETER *` with an empty body means "everything", and answering
     * that with a bare 200 OK leaves it holding no capabilities to negotiate against.
     */
    private fun requestedParameters(msg: String): Set<String> {
        val body = msg.substringAfter("\r\n\r\n", "")
        val named = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains(':') }
            .filter {
                val lower = it.lowercase()
                lower.startsWith("wfd_") || lower.startsWith("intel_") || lower.startsWith("microsoft_")
            }
            .toCollection(linkedSetOf())
        if (named.isNotEmpty()) return named
        return if (uri(msg).lowercase() == "*") capabilityValues().keys else linkedSetOf()
    }

    /** The request-target of the first line, so a wildcard `*` can be recognised. */
    private fun uri(msg: String): String {
        val parts = (msg.lineSequence().firstOrNull() ?: "").split(' ')
        return if (parts.size > 1) parts[1] else ""
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

    private fun rtcpPort(): Int = (rtpPort + 1).coerceAtMost(65535)

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
