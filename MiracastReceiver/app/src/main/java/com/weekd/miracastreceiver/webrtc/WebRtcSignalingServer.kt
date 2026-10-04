package com.weekd.miracastreceiver.webrtc

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * 阶段 1：HTTP/WebSocket 信令服务器。
 *
 * - HTTP `GET /`            -> 浏览器测试发送端页面（assets/webrtc/sender.html）
 * - HTTP `GET /health`      -> JSON 状态
 * - WS   任意路径 Upgrade   -> 信令会话（SDP Offer/Answer + ICE Candidate 交换）
 *
 * 信令 JSON 协议（与 sender.html 保持一致）：
 *   Client -> Receiver: {"type":"offer","sdp":...}
 *                       {"type":"candidate","sdpMid":..,"sdpMLineIndex":..,"candidate":..}
 *                       {"type":"ping"} / {"type":"bye"}
 *   Receiver -> Client: {"type":"welcome","name":..,"port":..}
 *                       {"type":"answer","sdp":...}
 *                       {"type":"candidate",...}
 *                       {"type":"state","state":...}
 *                       {"type":"pong"} / {"type":"error","message":..}
 */
class WebRtcSignalingServer(
    private val context: Context,
    private val deviceName: String,
    private val stateProvider: () -> String
) {

    var onOffer: ((sdp: String) -> Unit)? = null
    var onRemoteCandidate: ((sdpMid: String?, mLineIndex: Int, sdp: String) -> Unit)? = null
    var onClientConnected: ((clientInfo: String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onBye: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionCounter = AtomicLong(0)
    private val clientLock = Any()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: SignalingSession? = null

    val boundPort: Int get() = serverSocket?.localPort ?: 0
    val hasClient: Boolean get() = synchronized(clientLock) { activeClient != null }

    /** Binds the preferred port, walking upward past occupied ports like the WebUI does. */
    fun start(preferredPort: Int): Int {
        stop()
        var lastError: Exception? = null
        for (port in preferredPort..preferredPort + 50) {
            try {
                val server = ServerSocket(port)
                server.reuseAddress = true
                serverSocket = server
                Timber.i("WebRTC signaling server listening on port $port")
                scope.launch { acceptLoop(server) }
                return port
            } catch (e: BindException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
                break
            }
        }
        throw IllegalStateException("WebRTC signaling server could not bind: ${lastError?.message}")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clientLock) {
            activeClient?.close()
            activeClient = null
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun sendToClient(json: JSONObject): Boolean {
        val client = synchronized(clientLock) { activeClient } ?: return false
        return client.sendText(json.toString())
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) return
                Timber.w(e, "WebRTC signaling accept failed")
                continue
            }
            scope.launch { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 16 * 1024))
            val request = readHttpRequest(input) ?: run {
                socket.close()
                return
            }

            if (request.isWebSocketUpgrade) {
                socket.soTimeout = 0
                val session = SignalingSession(
                    sessionCounter.incrementAndGet(),
                    socket,
                    input,
                    DataOutputStream(socket.getOutputStream())
                )
                if (performHandshake(session, request)) {
                    runSession(session)
                } else {
                    socket.close()
                }
                return
            }

            when (request.path.substringBefore('?')) {
                "/", "/sender", "/sender.html" -> serveSenderPage(socket)
                "/health" -> {
                    val body = JSONObject()
                        .put("service", "webrtc-signaling")
                        .put("device", deviceName)
                        .put("state", stateProvider())
                        .put("clientConnected", hasClient)
                        .put("port", boundPort)
                    respond(socket, 200, "application/json", body.toString().toByteArray())
                }
                else -> respond(socket, 404, "application/json",
                    JSONObject().put("error", "not_found").toString().toByteArray())
            }
        } catch (e: Exception) {
            Timber.d("WebRTC signaling connection ended: ${e.message}")
            runCatching { socket.close() }
        }
    }

    private fun serveSenderPage(socket: Socket) {
        val bytes = try {
            context.assets.open("webrtc/sender.html").use { it.readBytes() }
        } catch (e: Exception) {
            Timber.w(e, "sender.html missing from assets")
            respond(socket, 500, "text/plain", "sender page unavailable".toByteArray())
            return
        }
        respond(socket, 200, "text/html; charset=utf-8", bytes)
    }

    private fun respond(socket: Socket, code: Int, contentType: String, body: ByteArray) {
        try {
            val output = socket.getOutputStream()
            val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
            output.write(
                ("HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun performHandshake(session: SignalingSession, request: HttpRequest): Boolean {
        val key = request.headers["sec-websocket-key"]
        if (key.isNullOrBlank() || !request.headers["upgrade"].orEmpty().contains("websocket", ignoreCase = true)) {
            return false
        }
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_MAGIC).toByteArray())
        )
        return try {
            session.output.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
            )
            session.output.flush()
            true
        } catch (e: Exception) {
            Timber.w(e, "WebSocket handshake write failed")
            false
        }
    }

    private fun runSession(session: SignalingSession) {
        val sessionId = session.id
        synchronized(clientLock) {
            activeClient?.let { previous ->
                Timber.i("WebRTC signaling session $sessionId replaces #${previous.id}")
                previous.close()
            }
            activeClient = session
        }
        session.sendText(
            JSONObject()
                .put("type", "welcome")
                .put("name", deviceName)
                .put("port", boundPort)
                .put("state", stateProvider())
                .toString()
        )
        onClientConnected?.invoke("ws-session#$sessionId")

        val pingJob = scope.launch {
            while (session.isOpen) {
                Thread.sleep(PING_INTERVAL_MS)
                if (!session.sendPing()) break
            }
        }
        try {
            session.readLoop { text -> dispatchMessage(sessionId, text) }
        } finally {
            pingJob.cancel()
            synchronized(clientLock) {
                if (activeClient === session) activeClient = null
            }
            onClientDisconnected?.invoke()
            Timber.i("WebRTC signaling session $sessionId closed")
        }
    }

    private fun dispatchMessage(sessionId: Long, text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Timber.w("WebRTC signaling: invalid JSON from session $sessionId")
            sendToClient(JSONObject().put("type", "error").put("message", "invalid_json"))
            return
        }
        when (json.optString("type")) {
            "offer" -> {
                val sdp = json.optString("sdp")
                if (sdp.isBlank()) {
                    sendToClient(JSONObject().put("type", "error").put("message", "missing_sdp"))
                } else {
                    Timber.i("WebRTC signaling: offer received (session=$sessionId, ${sdp.length} bytes)")
                    onOffer?.invoke(sdp)
                }
            }
            "candidate" -> {
                val candidate = json.optString("candidate")
                if (candidate.isNotBlank()) {
                    onRemoteCandidate?.invoke(
                        json.optString("sdpMid").takeIf { it.isNotBlank() },
                        json.optInt("sdpMLineIndex", 0),
                        candidate
                    )
                }
            }
            "ping" -> sendToClient(JSONObject().put("type", "pong"))
            "bye" -> {
                Timber.i("WebRTC signaling: bye from session $sessionId")
                onBye?.invoke()
            }
            else -> sendToClient(JSONObject().put("type", "error").put("message", "unknown_type"))
        }
    }

    private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>) {
        val isWebSocketUpgrade: Boolean
            get() = headers["upgrade"].orEmpty().contains("websocket", ignoreCase = true) &&
                headers["sec-websocket-key"] != null
    }

    private fun readHttpRequest(input: DataInputStream): HttpRequest? {
        return try {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c == -1) return null
                head.append(c.toChar())
                if (head.length > MAX_HTTP_HEADER_BYTES) return null
            }
            val headerText = head.toString().trim()
            if (headerText.isEmpty()) return null
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return null
            if (requestLine.size < 2) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }.toMap()
            HttpRequest(requestLine[0], requestLine[1], headers)
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: EOFException) {
            null
        } catch (e: Exception) {
            Timber.d("WebRTC signaling: malformed HTTP request: ${e.message}")
            null
        }
    }

    /** Minimal RFC 6455 server-side session: text frames, fragmentation, ping/pong, close. */
    private inner class SignalingSession(
        val id: Long,
        private val socket: Socket,
        private val input: DataInputStream,
        val output: DataOutputStream
    ) {
        @Volatile private var closed = false
        private val writeLock = Any()
        private val fragmentBuffer = StringBuilder()

        val isOpen: Boolean get() = !closed && !socket.isClosed

        fun readLoop(onMessage: (String) -> Unit) {
            while (isOpen) {
                val frame = try {
                    readFrame()
                } catch (_: EOFException) {
                    break
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Timber.d("WebRTC signaling: frame read failed: ${e.message}")
                    break
                } ?: break
                when (frame.opcode) {
                    OPCODE_TEXT, OPCODE_CONTINUATION -> {
                        if (frame.opcode == OPCODE_TEXT) fragmentBuffer.setLength(0)
                        fragmentBuffer.append(frame.textPayload)
                        if (frame.fin) {
                            val message = fragmentBuffer.toString()
                            fragmentBuffer.setLength(0)
                            if (message.isNotEmpty()) onMessage(message)
                        }
                    }
                    OPCODE_BINARY -> Unit // ignored
                    OPCODE_PING -> sendFrame(OPCODE_PONG, frame.rawPayload)
                    OPCODE_PONG -> Unit
                    OPCODE_CLOSE -> {
                        sendFrame(OPCODE_CLOSE, frame.rawPayload)
                        break
                    }
                }
            }
            close()
        }

        private fun readFrame(): Frame? {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length < 0 || length > MAX_FRAME_BYTES) throw EOFException("frame too large: $length")
            val maskKey = ByteArray(4)
            if (masked) input.readFully(maskKey)
            val payload = ByteArray(length.toInt())
            if (length > 0) input.readFully(payload)
            if (masked) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            return Frame(fin, opcode, payload)
        }

        fun sendText(text: String): Boolean = sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

        fun sendPing(): Boolean = sendFrame(OPCODE_PING, ByteArray(0))

        private fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
            if (closed) return false
            synchronized(writeLock) {
                return try {
                    output.writeByte(0x80 or opcode)
                    when {
                        payload.size < 126 -> output.writeByte(payload.size)
                        payload.size < 65536 -> {
                            output.writeByte(126)
                            output.writeShort(payload.size)
                        }
                        else -> {
                            output.writeByte(127)
                            output.writeLong(payload.size.toLong())
                        }
                    }
                    output.write(payload)
                    output.flush()
                    true
                } catch (e: Exception) {
                    Timber.d("WebRTC signaling: send failed: ${e.message}")
                    close()
                    false
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val rawPayload: ByteArray) {
        val textPayload: String get() = String(rawPayload, Charsets.UTF_8)
    }

    private companion object {
        const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0
        const val OPCODE_TEXT = 1
        const val OPCODE_BINARY = 2
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10
        const val PING_INTERVAL_MS = 30_000L
        const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        const val MAX_FRAME_BYTES = 4L * 1024 * 1024
    }
}
