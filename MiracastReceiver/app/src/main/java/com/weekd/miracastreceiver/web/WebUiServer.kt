package com.weekd.miracastreceiver.web

import android.content.Context
import android.content.Intent
import android.os.Build
import com.weekd.miracastreceiver.BuildConfig
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.ui.PlayerActivity
import com.weekd.miracastreceiver.ui.UrlPlaybackActivity
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.util.PrivilegedAccess
import com.weekd.miracastreceiver.utils.CodecUtils
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.utils.PortUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

/** Small authenticated LAN WebUI/API server. */
class WebUiServer(
    context: Context,
    private val port: Int,
    private val onReconfigureRequested: (String) -> Unit,
    private val onRestartReceiverRequested: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientSlots = Semaphore(MAX_CONCURRENT_CLIENTS, true)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return

        val previousPort = AppSettings.getWebUiLastBoundPort(appContext)
        val socket = PortUtils.bindAvailableServerSocket(
            preferredPort = port,
            fallbackPort = previousPort,
            excludedPorts = setOf(AppSettings.getUpnpPort(appContext), MIRACAST_RTSP_PORT),
            backlog = 32
        )
        val actualPort = socket.localPort
        serverSocket = socket
        RuntimeState.webUiPreferredPort = port
        RuntimeState.webUiPort = actualPort
        AppSettings.setWebUiLastBoundPort(appContext, actualPort)

        val bindAddress = socket.inetAddress?.hostAddress ?: "*"
        if (actualPort == port) Timber.i("WebUI started on preferred port $actualPort bind=$bindAddress")
        else Timber.w("WebUI preferred port $port occupied; using runtime fallback $actualPort bind=$bindAddress")

        acceptJob = scope.launch {
            try {
                while (isActive && !socket.isClosed) {
                    val client = try { socket.accept() } catch (e: SocketException) {
                        if (!socket.isClosed) Timber.w(e, "WebUI accept failed")
                        break
                    }
                    if (!clientSlots.tryAcquire()) {
                        runCatching {
                            client.use {
                                it.soTimeout = 1_000
                                sendJson(it.getOutputStream(), 503, JSONObject().put("error", "too_many_connections"))
                            }
                        }
                        continue
                    }
                    launch {
                        try { handleClient(client) } finally { clientSlots.release() }
                    }
                }
            } catch (e: Exception) {
                RuntimeState.lastError = "WebUI: ${e.message.orEmpty()}"
                Timber.e(e, "WebUI server loop failed on port $actualPort")
            }
        }
    }

    fun stop() {
        val stoppedPort = serverSocket?.localPort ?: RuntimeState.webUiPort
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        if (RuntimeState.webUiPort == stoppedPort) RuntimeState.webUiPort = 0
        RuntimeState.webUiPreferredPort = 0
        Timber.i("WebUI stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()

                val requestLine = readLine(input, MAX_REQUEST_LINE) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) return sendText(output, 400, "Bad Request")

                val method = parts[0].uppercase()
                val rawTarget = parts[1]
                val path = rawTarget.substringBefore('?')
                val query = parseQuery(rawTarget.substringAfter('?', ""))
                val headers = linkedMapOf<String, String>()

                var headerBytes = 0
                while (true) {
                    val line = readLine(input, MAX_HEADER_LINE) ?: break
                    if (line.isEmpty()) break
                    headerBytes += line.length
                    if (headerBytes > MAX_HEADER_BYTES) return sendText(output, 431, "Headers Too Large")
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (contentLength > MAX_BODY_BYTES) return sendText(output, 413, "Payload Too Large")
                val body = if (contentLength > 0) String(readExact(input, contentLength), StandardCharsets.UTF_8) else ""

                if (path.startsWith("/api/") && path != "/api/ping" && !isAuthorized(headers)) {
                    return sendJson(output, 401, JSONObject().put("error", "unauthorized"))
                }

                when {
                    method == "GET" && path == "/" -> sendAsset(output, "webui/index.html", "text/html; charset=utf-8")
                    method == "GET" && path == "/app.js" -> sendAsset(output, "webui/app.js", "application/javascript; charset=utf-8")
                    method == "GET" && path == "/styles.css" -> sendAsset(output, "webui/styles.css", "text/css; charset=utf-8")
                    method == "GET" && path == "/api/ping" -> sendJson(
                        output,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("authRequired", AppSettings.isWebUiAuthRequired(appContext))
                            .put("authMode", AppSettings.getWebUiAuthMode(appContext))
                            .put("port", RuntimeState.webUiPort.takeIf { it > 0 } ?: AppSettings.getWebUiPort(appContext))
                            .put("preferredPort", AppSettings.getWebUiPort(appContext))
                    )
                    method == "GET" && path == "/api/status" -> sendJson(output, 200, buildStatus())
                    method == "GET" && path == "/api/config" -> sendJson(output, 200, buildConfig())
                    method == "GET" && path == "/api/diagnostics" -> sendJson(output, 200, buildDiagnostics())
                    method == "GET" && path == "/api/logs" -> {
                        val limit = query["limit"]?.toIntOrNull() ?: 250
                        sendJson(output, 200, buildLogs(limit))
                    }
                    method == "POST" && path == "/api/config" -> handleConfig(output, body)
                    method == "POST" && path == "/api/actions/player" -> handlePlayerAction(output, body)
                    method == "POST" && path == "/api/actions/open-url" -> handleOpenUrl(output, body)
                    method == "POST" && path == "/api/actions/restart" -> {
                        sendJson(output, 200, JSONObject().put("ok", true))
                        onRestartReceiverRequested()
                    }
                    method == "POST" && path == "/api/actions/reconfigure" -> {
                        sendJson(output, 200, JSONObject().put("ok", true))
                        onReconfigureRequested("WebUI manual reconfigure")
                    }
                    method == "POST" && path == "/api/actions/regenerate-code" -> {
                        val code = AppSettings.regenerateConnectionCode(appContext)
                        sendJson(output, 200, JSONObject().put("ok", true).put("connectionCode", code))
                        onReconfigureRequested("connection code changed")
                    }
                    method == "POST" && path == "/api/actions/rotate-token" -> {
                        if (AppSettings.getWebUiAuthMode(appContext) == AppSettings.WEB_UI_AUTH_CUSTOM) {
                            sendJson(output, 400, JSONObject().put("error", "custom_token_managed_in_config"))
                        } else {
                            val token = AppSettings.regenerateWebUiToken(appContext)
                            sendJson(output, 200, JSONObject().put("ok", true).put("token", token))
                        }
                    }
                    method == "POST" && path == "/api/actions/background-optimize" -> {
                        val result = PrivilegedAccess.applyBackgroundOptimizations(appContext, AppSettings.isAutoStartOnBoot(appContext))
                        sendJson(output, 200, JSONObject().put("ok", result.success).put("channel", result.privilegedChannel)
                            .put("succeeded", result.succeeded).put("attempted", result.attempted)
                            .put("bootScriptInstalled", result.bootScriptInstalled))
                    }
                    method == "POST" && path == "/api/logs/clear" -> {
                        WebLogBuffer.clear()
                        sendJson(output, 200, JSONObject().put("ok", true))
                    }
                    else -> sendJson(output, 404, JSONObject().put("error", "not_found"))
                }
            } catch (e: Exception) {
                Timber.w(e, "WebUI request failed")
                runCatching { sendText(client.getOutputStream(), 500, "Internal Server Error") }
            }
        }
    }

    private fun handleConfig(output: OutputStream, body: String) {
        val json = runCatching { JSONObject(body) }.getOrElse {
            return sendJson(output, 400, JSONObject().put("error", "invalid_json"))
        }

        val requestedWebPort = if (json.has("webUiPort")) json.optInt("webUiPort", AppSettings.getWebUiPort(appContext)) else AppSettings.getWebUiPort(appContext)
        val requestedUpnpPort = if (json.has("upnpPort")) json.optInt("upnpPort", AppSettings.DEFAULT_UPNP_PORT) else AppSettings.getUpnpPort(appContext)
        val requestedWebEnabled = if (json.has("webUiEnabled")) json.optBoolean("webUiEnabled", true) else AppSettings.isWebUiEnabled(appContext)
        val requestedAuthMode = when {
            json.has("webUiAuthMode") -> json.optString("webUiAuthMode", AppSettings.WEB_UI_AUTH_AUTO).lowercase()
            json.has("webUiAuthRequired") -> if (json.optBoolean("webUiAuthRequired", true)) AppSettings.WEB_UI_AUTH_AUTO else AppSettings.WEB_UI_AUTH_NONE
            else -> AppSettings.getWebUiAuthMode(appContext)
        }
        val requestedCustomToken = if (json.has("webUiCustomToken")) json.optString("webUiCustomToken", "").trim() else AppSettings.getWebUiCustomToken(appContext)

        if (requestedWebPort !in 1024..65535 || requestedUpnpPort !in 1024..65535) {
            return sendJson(output, 400, JSONObject().put("error", "port_out_of_range"))
        }
        if (requestedWebPort == requestedUpnpPort) {
            return sendJson(output, 400, JSONObject().put("error", "webui_and_upnp_ports_must_differ"))
        }
        if (requestedAuthMode !in setOf(AppSettings.WEB_UI_AUTH_NONE, AppSettings.WEB_UI_AUTH_AUTO, AppSettings.WEB_UI_AUTH_CUSTOM)) {
            return sendJson(output, 400, JSONObject().put("error", "invalid_auth_mode"))
        }
        if (requestedAuthMode == AppSettings.WEB_UI_AUTH_CUSTOM && !AppSettings.isValidCustomToken(requestedCustomToken.orEmpty())) {
            return sendJson(output, 400, JSONObject().put("error", "invalid_custom_token")
                .put("minLength", AppSettings.MIN_CUSTOM_TOKEN_LENGTH).put("maxLength", AppSettings.MAX_CUSTOM_TOKEN_LENGTH))
        }

        val reconnectPort = predictReconnectPort(requestedWebPort, requestedWebEnabled)

        if (json.has("airPlayEnabled")) AppSettings.setAirPlayEnabled(appContext, json.optBoolean("airPlayEnabled", true))
        if (json.has("dlnaEnabled")) AppSettings.setDlnaEnabled(appContext, json.optBoolean("dlnaEnabled", true))
        if (json.has("miracastEnabled")) AppSettings.setMiracastEnabled(appContext, json.optBoolean("miracastEnabled", true))
        if (json.has("customMdnsEnabled")) AppSettings.setCustomMdnsEnabled(appContext, json.optBoolean("customMdnsEnabled", true))
        if (json.has("airPlayAudioEnabled")) AppSettings.setAirPlayAudioEnabled(appContext, json.optBoolean("airPlayAudioEnabled", true))
        if (json.has("autoLaunchPlayer")) AppSettings.setAutoLaunchPlayer(appContext, json.optBoolean("autoLaunchPlayer", true))
        if (json.has("mirrorMaxHeight")) AppSettings.setMirrorMaxHeight(appContext, json.optInt("mirrorMaxHeight", 0))
        if (json.has("upnpPort")) AppSettings.setUpnpPort(appContext, requestedUpnpPort)
        if (json.has("webUiEnabled")) AppSettings.setWebUiEnabled(appContext, requestedWebEnabled)
        if (json.has("webUiPort")) AppSettings.setWebUiPort(appContext, requestedWebPort)
        if (json.has("webUiAuthMode") || json.has("webUiAuthRequired")) {
            runCatching { AppSettings.setWebUiAuthMode(appContext, requestedAuthMode, requestedCustomToken) }.getOrElse {
                return sendJson(output, 400, JSONObject().put("error", it.message ?: "invalid_auth_config"))
            }
        }
        if (json.has("autoStartOnBoot")) {
            val enabled = json.optBoolean("autoStartOnBoot", true)
            AppSettings.setAutoStartOnBoot(appContext, enabled)
            if (enabled) PrivilegedAccess.installMagiskBootScript(appContext) else PrivilegedAccess.removeMagiskBootScript()
        }
        if (json.has("deviceName")) AppSettings.setDeviceNameOverride(appContext, json.optString("deviceName", ""))

        val activeToken = if (AppSettings.isWebUiAuthRequired(appContext)) AppSettings.getOrCreateWebUiToken(appContext) else ""
        sendJson(output, 200, JSONObject().put("ok", true).put("config", buildConfig()).put("reconnectPort", reconnectPort).put("activeToken", activeToken))
        onReconfigureRequested("WebUI configuration changed")
    }

    private fun predictReconnectPort(requestedPort: Int, enabled: Boolean): Int {
        if (!enabled) return 0
        val runtimePort = RuntimeState.webUiPort
        if (requestedPort == runtimePort && runtimePort > 0) return runtimePort
        if (PortUtils.isTcpPortAvailable(requestedPort)) return requestedPort
        if (runtimePort > 0) return runtimePort
        return AppSettings.getWebUiLastBoundPort(appContext) ?: requestedPort
    }

    private fun handleOpenUrl(output: OutputStream, body: String) {
        val json = runCatching { JSONObject(body) }.getOrElse { return sendJson(output, 400, JSONObject().put("error", "invalid_json")) }
        val headerObject = json.optJSONObject("headers")
        val rawHeaders = linkedMapOf<String, String>()
        headerObject?.keys()?.forEach { key -> rawHeaders[key] = headerObject.optString(key) }
        val request = runCatching { MediaUrlRequest.parse(json.optString("url"), json.optString("title"), rawHeaders) }.getOrElse {
            return sendJson(output, 400, JSONObject().put("error", it.message ?: "invalid_media_request"))
        }

        val currentSource = RuntimeState.playbackSnapshot().source.uppercase()
        val reusingUrlPlayer = currentSource == "WEB_URL" || currentSource == "GOOGLE_CAST"
        if (!reusingUrlPlayer) {
            appContext.sendBroadcast(Intent(PlayerActivity.ACTION_STOP).setPackage(appContext.packageName))
        }
        val intent = Intent(appContext, UrlPlaybackActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(UrlPlaybackActivity.EXTRA_URL, request.url)
            putExtra(UrlPlaybackActivity.EXTRA_TITLE, request.title)
            putExtra(UrlPlaybackActivity.EXTRA_HEADERS_JSON, JSONObject(request.headers).toString())
        }
        val started = runCatching { appContext.startActivity(intent) }
        if (started.isFailure) {
            val error = started.exceptionOrNull()?.message.orEmpty()
            RuntimeState.lastError = "URL playback launch failed: $error"
            return sendJson(output, 500, JSONObject().put("error", "player_launch_failed").put("detail", error))
        }
        RuntimeState.updatePlayback {
            it.copy(
                state = "LAUNCHING",
                title = request.title.ifBlank { request.url },
                uri = request.url,
                positionMs = 0L,
                durationMs = 0L,
                isLive = false,
                isSeekable = false,
                source = "WEB_URL",
                error = "",
                retryAttempt = 0,
                decoderName = "",
                hardwareDecoder = false
            )
        }
        sendJson(output, 200, JSONObject().put("ok", true).put("url", request.url).put("title", request.title).put("headerCount", request.headers.size))
    }

    private fun handlePlayerAction(output: OutputStream, body: String) {
        val json = runCatching { JSONObject(body) }.getOrElse { return sendJson(output, 400, JSONObject().put("error", "invalid_json")) }
        val action = json.optString("action").lowercase()
        val intent = when (action) {
            "play" -> Intent(PlayerActivity.ACTION_PLAY)
            "pause" -> Intent(PlayerActivity.ACTION_PAUSE)
            "stop" -> Intent(PlayerActivity.ACTION_STOP)
            "seek" -> Intent(PlayerActivity.ACTION_SEEK).apply { putExtra(PlayerActivity.EXTRA_SEEK_POSITION, json.optLong("positionMs", 0L).coerceAtLeast(0L)) }
            "volume" -> Intent(PlayerActivity.ACTION_SET_VOLUME).apply { putExtra(PlayerActivity.EXTRA_VOLUME, json.optInt("value", 50).coerceIn(0, 100)) }
            "speed" -> Intent(PlayerActivity.ACTION_SET_SPEED).apply { putExtra(PlayerActivity.EXTRA_SPEED, json.optDouble("value", 1.0).toFloat().coerceIn(0.25f, 4f)) }
            else -> return sendJson(output, 400, JSONObject().put("error", "unsupported_player_action"))
        }
        intent.setPackage(appContext.packageName)
        appContext.sendBroadcast(intent)
        sendJson(output, 200, JSONObject().put("ok", true).put("action", action))
    }

    private fun buildStatus(): JSONObject {
        val deviceInfo = DeviceInfoProvider(appContext)
        val privileged = PrivilegedAccess.getStatus(appContext)
        val playback = RuntimeState.playbackSnapshot()
        val lan = NetworkUtils.getLanAddresses()
        val now = System.currentTimeMillis()
        val preferredPort = AppSettings.getWebUiPort(appContext)
        val runtimePort = RuntimeState.webUiPort.takeIf { it > 0 } ?: AppSettings.getWebUiLastBoundPort(appContext) ?: preferredPort
        return JSONObject()
            .put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("debug", BuildConfig.DEBUG))
            .put("device", JSONObject()
                .put("name", deviceInfo.getDeviceName())
                .put("id", deviceInfo.getDeviceId())
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("ip", lan.preferred.orEmpty())
                .put("ipv4", lan.ipv4.orEmpty())
                .put("ipv6", lan.ipv6.orEmpty()))
            .put("webui", JSONObject()
                .put("port", runtimePort)
                .put("preferredPort", preferredPort)
                .put("fallback", runtimePort != preferredPort)
                .put("bindAddress", serverSocket?.inetAddress?.hostAddress.orEmpty())
                .put("authRequired", AppSettings.isWebUiAuthRequired(appContext))
                .put("authMode", AppSettings.getWebUiAuthMode(appContext)))
            .put("service", JSONObject().put("running", RuntimeState.serviceRunning).put("startedAtMs", RuntimeState.serviceStartedAtMs).put("uptimeMs", if (RuntimeState.serviceStartedAtMs > 0) now - RuntimeState.serviceStartedAtMs else 0).put("lastError", RuntimeState.lastError))
            .put("airplay", JSONObject().put("state", RuntimeState.airPlayState).put("sender", RuntimeState.airPlaySender))
            .put("miracast", JSONObject().put("state", RuntimeState.miracastState).put("client", RuntimeState.miracastClient).put("rtpPort", RuntimeState.miracastRtpPort))
            .put("playback", JSONObject()
                .put("state", playback.state)
                .put("title", playback.title)
                .put("uri", playback.uri)
                .put("positionMs", playback.positionMs)
                .put("durationMs", playback.durationMs)
                .put("isLive", playback.isLive)
                .put("isSeekable", playback.isSeekable)
                .put("speed", playback.speed.toDouble())
                .put("volume", playback.volume)
                .put("source", playback.source)
                .put("error", playback.error)
                .put("retryAttempt", playback.retryAttempt)
                .put("decoder", RuntimeState.decoderName())
                .put("hardwareDecoder", RuntimeState.decoderHardwareAccelerated()))
            .put("privileged", JSONObject().put("root", privileged.rootAvailable).put("magisk", privileged.magiskAvailable).put("shizukuAlive", privileged.shizukuAlive).put("shizukuAuthorized", privileged.shizukuAuthorized).put("bootScriptInstalled", privileged.bootScriptInstalled))
    }

    private fun buildConfig(): JSONObject {
        val s = AppSettings.snapshot(appContext)
        val runtimePort = RuntimeState.webUiPort.takeIf { it > 0 } ?: s.webUiLastBoundPort ?: s.webUiPort
        return JSONObject()
            .put("airPlayEnabled", s.airPlayEnabled).put("dlnaEnabled", s.dlnaEnabled).put("miracastEnabled", s.miracastEnabled)
            .put("customMdnsEnabled", s.customMdnsEnabled).put("airPlayAudioEnabled", s.airPlayAudioEnabled).put("autoLaunchPlayer", s.autoLaunchPlayer)
            .put("mirrorMaxHeight", s.mirrorMaxHeight).put("upnpPort", s.upnpPort).put("webUiEnabled", s.webUiEnabled).put("webUiPort", s.webUiPort)
            .put("webUiRuntimePort", runtimePort).put("webUiFallbackActive", runtimePort != s.webUiPort).put("webUiAuthRequired", s.webUiAuthRequired)
            .put("webUiAuthMode", s.webUiAuthMode).put("webUiCustomToken", if (s.webUiAuthMode == AppSettings.WEB_UI_AUTH_CUSTOM) AppSettings.getWebUiCustomToken(appContext).orEmpty() else "")
            .put("autoStartOnBoot", s.autoStartOnBoot).put("deviceName", s.deviceNameOverride.orEmpty()).put("connectionCode", AppSettings.getOrCreateConnectionCode(appContext))
    }

    private fun buildDiagnostics(): JSONObject {
        val recommended = CodecUtils.getRecommendedVideoConfig()
        val lan = NetworkUtils.getLanAddresses()
        val preferredPort = AppSettings.getWebUiPort(appContext)
        val runtimePort = RuntimeState.webUiPort.takeIf { it > 0 } ?: AppSettings.getWebUiLastBoundPort(appContext) ?: preferredPort
        return JSONObject()
            .put("network", JSONObject()
                .put("ip", lan.preferred.orEmpty())
                .put("ipv4", lan.ipv4.orEmpty())
                .put("ipv6", lan.ipv6.orEmpty())
                .put("available", NetworkUtils.isNetworkAvailable(appContext))
                .put("wifi", NetworkUtils.isWifiConnected(appContext)))
            .put("codecs", JSONObject().put("h264", CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264)).put("h265", CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265)).put("recommendedMime", recommended.mimeType).put("recommendedWidth", recommended.width).put("recommendedHeight", recommended.height).put("recommendedFps", recommended.frameRate).put("activeDecoder", RuntimeState.decoderName()).put("activeDecoderHardware", RuntimeState.decoderHardwareAccelerated()))
            .put("ports", JSONObject()
                .put("webUi", runtimePort)
                .put("webUiPreferred", preferredPort)
                .put("webUiFallback", runtimePort != preferredPort)
                .put("webUiBindAddress", serverSocket?.inetAddress?.hostAddress.orEmpty())
                .put("upnp", AppSettings.getUpnpPort(appContext))
                .put("miracastRtsp", MIRACAST_RTSP_PORT))
            .put("limits", JSONObject().put("maxRequestBodyBytes", MAX_BODY_BYTES).put("maxConcurrentClients", MAX_CONCURRENT_CLIENTS).put("maxMediaUrlLength", MediaUrlRequest.MAX_URL_LENGTH).put("maxMediaHeaders", MediaUrlRequest.MAX_HEADERS).put("logEntries", WebLogBuffer.snapshot(600).size))
    }

    private fun buildLogs(limit: Int): JSONObject {
        val entries = JSONArray()
        WebLogBuffer.snapshot(limit).forEach { entry -> entries.put(JSONObject().put("timestampMs", entry.timestampMs).put("timestamp", WebLogBuffer.formatTimestamp(entry.timestampMs)).put("level", entry.level).put("tag", entry.tag).put("message", entry.message)) }
        return JSONObject().put("entries", entries)
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        if (AppSettings.getWebUiAuthMode(appContext) == AppSettings.WEB_UI_AUTH_NONE) return true
        val expected = AppSettings.getOrCreateWebUiToken(appContext)
        val direct = headers["x-api-token"]
        val bearer = headers["authorization"]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')
        return direct == expected || bearer == expected
    }

    private fun sendAsset(output: OutputStream, assetPath: String, contentType: String) {
        val bytes = runCatching { appContext.assets.open(assetPath).use { it.readBytes() } }.getOrElse { return sendText(output, 404, "Asset not found") }
        sendBytes(output, 200, contentType, bytes)
    }
    private fun sendJson(output: OutputStream, status: Int, json: JSONObject) { sendBytes(output, status, "application/json; charset=utf-8", json.toString().toByteArray(StandardCharsets.UTF_8)) }
    private fun sendText(output: OutputStream, status: Int, text: String) { sendBytes(output, status, "text/plain; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8)) }

    private fun sendBytes(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> "Internal Server Error" }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII)); output.write(body); output.flush()
    }

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val buffer = ByteArrayOutputStream(); var previous = -1
        while (buffer.size() <= maxBytes) {
            val current = input.read()
            if (current == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = buffer.toByteArray(); val length = (bytes.size - 1).coerceAtLeast(0)
                return String(bytes, 0, length, StandardCharsets.UTF_8)
            }
            buffer.write(current); previous = current
        }
        throw IllegalArgumentException("HTTP line too long")
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length); var offset = 0
        while (offset < length) { val read = input.read(bytes, offset, length - offset); if (read < 0) throw IllegalArgumentException("Unexpected end of request body"); offset += read }
        return bytes
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isBlank()) null else URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }.toMap()
    }

    companion object {
        private const val MIRACAST_RTSP_PORT = 7236
        private const val SOCKET_TIMEOUT_MS = 7_000
        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_CONCURRENT_CLIENTS = 24
    }
}
