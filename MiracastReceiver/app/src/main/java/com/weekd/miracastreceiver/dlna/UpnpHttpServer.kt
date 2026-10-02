package com.weekd.miracastreceiver.dlna

import android.content.Context
import android.content.Intent
import com.weekd.miracastreceiver.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/**
 * UPnP HTTP 服务器
 * 处理设备描述、服务描述和 SOAP 控制请求
 */
class UpnpHttpServer(
    private val context: Context,
    private val renderer: DlnaMediaRenderer,
    private val deviceUuid: String,
    private val deviceName: String,
    private val manufacturer: String,
    private val modelName: String,
    private val localIp: String,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (serverJob?.isActive == true) {
            Timber.w("UPnP HTTP server already running")
            return
        }

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Timber.i("UPnP HTTP server started on port $port")

                while (serverSocket?.isClosed == false) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        launch {
                            handleClient(client)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UPnP HTTP server error")
            }
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
            Timber.i("UPnP HTTP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping UPnP HTTP server")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.tcpNoDelay = true

                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()

                val requestLine = readHttpLine(input, MAX_REQUEST_LINE_BYTES) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    sendHttpError(output, 400, "Bad Request")
                    return
                }

                // HTTP header names are case-insensitive. A case-insensitive map also preserves
                // compatibility with the existing SOAPAction lookups below.
                val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                var headerBytes = 0

                while (true) {
                    val line = readHttpLine(input, MAX_HEADER_LINE_BYTES)
                        ?: throw IllegalArgumentException("Unexpected EOF in HTTP headers")
                    if (line.isEmpty()) break

                    headerBytes += line.toByteArray(StandardCharsets.ISO_8859_1).size + 2
                    if (headerBytes > MAX_HEADER_BYTES) {
                        sendHttpError(output, 431, "Request Header Fields Too Large")
                        return
                    }

                    val index = line.indexOf(':')
                    if (index <= 0) {
                        sendHttpError(output, 400, "Bad Request")
                        return
                    }
                    val key = line.substring(0, index).trim()
                    val value = line.substring(index + 1).trim()
                    headers[key] = value
                }

                if (headers["Transfer-Encoding"]?.contains("chunked", ignoreCase = true) == true) {
                    // UPnP control requests are expected to provide Content-Length. Explicitly
                    // reject an unsupported framing mode instead of accidentally parsing an empty
                    // or truncated SOAP body.
                    sendHttpError(output, 400, "Chunked Request Body Not Supported")
                    return
                }

                val contentLengthLong = headers["Content-Length"]?.toLongOrNull() ?: 0L
                if (contentLengthLong < 0L) {
                    sendHttpError(output, 400, "Bad Content-Length")
                    return
                }
                if (contentLengthLong > MAX_BODY_BYTES) {
                    sendHttpError(output, 413, "Payload Too Large")
                    return
                }
                val contentLength = contentLengthLong.toInt()

                if (contentLength > 0 && headers["Expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }

                // Content-Length is a byte count, so read the raw stream exactly. The previous
                // BufferedReader.read(...) call could legally return a partial SOAP body when TCP
                // split the request across packets.
                val body = if (contentLength > 0) {
                    String(readExact(input, contentLength), StandardCharsets.UTF_8)
                } else {
                    ""
                }

                val method = parts[0]
                val path = parts[1].substringBefore('?')
                Timber.d("UPnP $method $path")

                when {
                    path == "/device.xml" -> sendDeviceDescription(output)
                    path == "/service/ConnectionManager.xml" -> sendConnectionManagerScpd(output)
                    path == "/service/AVTransport.xml" -> sendAvTransportScpd(output)
                    path == "/service/RenderingControl.xml" -> sendRenderingControlScpd(output)
                    path == "/control/AVTransport" -> handleAvTransportControl(output, body, headers)
                    path == "/control/RenderingControl" -> handleRenderingControl(output, body, headers)
                    path == "/control/ConnectionManager" -> handleConnectionManager(output, body, headers)
                    path.startsWith("/event/") -> handleEvent(output)
                    else -> sendNotFound(output)
                }
            } catch (e: Exception) {
                Timber.w(e, "Error handling UPnP client")
            }
        }
    }

    private fun readHttpLine(input: BufferedInputStream, maxBytes: Int): String? {
        val buffer = ByteArrayOutputStream()
        var previous = -1

        while (buffer.size() <= maxBytes) {
            val current = input.read()
            if (current == -1) {
                return if (buffer.size() == 0) null
                else String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
            }

            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = (bytes.size - 1).coerceAtLeast(0)
                return String(bytes, 0, length, StandardCharsets.ISO_8859_1)
            }

            buffer.write(current)
            previous = current
        }

        throw IllegalArgumentException("HTTP line exceeds $maxBytes bytes")
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IllegalArgumentException("Unexpected EOF in HTTP request body")
            offset += read
        }
        return bytes
    }

    private fun sendHttpError(output: OutputStream, status: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            400 -> "Bad Request"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Bad Request"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun sendDeviceDescription(output: OutputStream) {
        val baseUrl = "http://$localIp:$port"
        val xml = DlnaXmlBuilder.buildDeviceDescription(
            deviceUuid, deviceName, manufacturer, modelName, baseUrl
        )
        sendXmlResponse(output, xml)
    }

    private fun sendConnectionManagerScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildConnectionManagerScpd())
    }

    private fun sendAvTransportScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildAvTransportScpd())
    }

    private fun sendRenderingControlScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildRenderingControlScpd())
    }

    private fun handleAvTransportControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["SOAPACTION"] ?: headers["SOAPAction"])
        Timber.i("AVTransport action: $action")

        // 调试：打印完整请求体，帮助排查 Emby 的格式
        if (action == "SetAVTransportURI") {
            Timber.d("SetAVTransportURI body (first 1000 chars): ${body.take(1000)}")
        }

        val response = when (action) {
            "SetAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "CurrentURI")
                val metadata = SoapParser.extractTagValue(body, "CurrentURIMetaData")

                // 如果 URI 为空，记录错误并尝试更宽松的解析
                if (uri.isBlank()) {
                    Timber.e("SetAVTransportURI: Empty URI! Trying fallback parsing...")
                    Timber.e("Request body: $body")

                    // 尝试其他可能的标签名
                    val fallbackUri = listOf("URI", "Url", "MediaUri", "CurrentUri").firstNotNullOfOrNull { tag ->
                        val value = SoapParser.extractTagValue(body, tag)
                        if (value.isNotBlank()) value else null
                    } ?: ""

                    if (fallbackUri.isNotBlank()) {
                        Timber.i("Found URI via fallback: $fallbackUri")
                        renderer.setAVTransportURI(fallbackUri, metadata)
                    } else {
                        renderer.setAVTransportURI(uri, metadata)
                    }
                } else {
                    renderer.setAVTransportURI(uri, metadata)
                }
                buildSoapResponse(action, "")
            }
            "SetNextAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "NextURI")
                if (uri.isNotBlank()) renderer.setQualityUri(uri)
                buildSoapResponse(action, "")
            }
            "Play" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                }
                renderer.play()
                buildSoapResponse(action, "")
            }
            "SetRate", "SetSpeed", "SetPlaybackSpeed", "SetTransportPlaySpeed",
            "X_SetRate", "X_SetSpeed", "X_SetPlaybackSpeed", "X_SetTransportPlaySpeed" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                    buildSoapResponse(action, "")
                } else {
                    buildSoapFault("402", "Invalid Args")
                }
            }
            "Pause" -> {
                renderer.pause()
                buildSoapResponse(action, "")
            }
            "Stop" -> {
                renderer.stop()
                buildSoapResponse(action, "")
            }
            "Seek" -> {
                val target = SoapParser.extractTagValue(body, "Target")
                renderer.seek(target)
                buildSoapResponse(action, "")
            }
            "GetTransportInfo" -> {
                val state = renderer.getState()
                val content = """
                    <CurrentTransportState>${state.transportState}</CurrentTransportState>
                    <CurrentTransportStatus>OK</CurrentTransportStatus>
                    <CurrentSpeed>${formatSpeed(state.playbackSpeed)}</CurrentSpeed>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetPositionInfo" -> {
                val state = renderer.getState()
                val content = """
                    <Track>1</Track>
                    <TrackDuration>${SoapParser.formatTime(state.duration)}</TrackDuration>
                    <TrackMetaData>${SoapParser.escapeXml(state.metadata)}</TrackMetaData>
                    <TrackURI>${SoapParser.escapeXml(state.uri)}</TrackURI>
                    <RelTime>${SoapParser.formatTime(state.position)}</RelTime>
                    <AbsTime>${SoapParser.formatTime(state.position)}</AbsTime>
                    <RelCount>2147483647</RelCount>
                    <AbsCount>2147483647</AbsCount>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetMediaInfo" -> {
                val state = renderer.getState()
                val content = """
                    <NrTracks>1</NrTracks>
                    <MediaDuration>${SoapParser.formatTime(state.duration)}</MediaDuration>
                    <CurrentURI>${SoapParser.escapeXml(state.uri)}</CurrentURI>
                    <CurrentURIMetaData>${SoapParser.escapeXml(state.metadata)}</CurrentURIMetaData>
                    <NextURI></NextURI>
                    <NextURIMetaData></NextURIMetaData>
                    <PlayMedium>NETWORK</PlayMedium>
                    <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                    <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetCurrentTransportActions" -> {
                val content = "<Actions>Play,Pause,Stop,Seek</Actions>"
                buildSoapResponse(action, content)
            }
            "GetTransportSettings" -> {
                val content = """
                    <PlayMode>NORMAL</PlayMode>
                    <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "X_GetFeatureList" -> {
                // 一些客户端请求扩展特性
                buildSoapResponse(action, "<FeatureList></FeatureList>")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleRenderingControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["SOAPACTION"] ?: headers["SOAPAction"])
        Timber.i("RenderingControl action: $action")

        val response = when (action) {
            "GetVolume" -> {
                val state = renderer.getState()
                buildSoapResponse(action, "<CurrentVolume>${state.volume}</CurrentVolume>")
            }
            "SetVolume" -> {
                val volume = SoapParser.extractTagValue(body, "DesiredVolume").toIntOrNull() ?: 50
                renderer.setVolume(volume)
                buildSoapResponse(action, "")
            }
            "GetMute" -> {
                val state = renderer.getState()
                val mute = if (state.muted) "1" else "0"
                buildSoapResponse(action, "<CurrentMute>$mute</CurrentMute>")
            }
            "SetMute" -> {
                val mute = SoapParser.extractTagValue(body, "DesiredMute") == "1"
                renderer.setMute(mute)
                buildSoapResponse(action, "")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleConnectionManager(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = SoapParser.extractAction(headers["SOAPACTION"] ?: headers["SOAPAction"])
        Timber.i("ConnectionManager action: $action")

        val response = when (action) {
            "GetProtocolInfo" -> {
                val content = """
                    <Source></Source>
                    <Sink>http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetCurrentConnectionIDs" -> {
                buildSoapResponse(action, "<ConnectionIDs>0</ConnectionIDs>")
            }
            "GetCurrentConnectionInfo" -> {
                val content = """
                    <RcsID>0</RcsID>
                    <AVTransportID>0</AVTransportID>
                    <ProtocolInfo></ProtocolInfo>
                    <PeerConnectionManager></PeerConnectionManager>
                    <PeerConnectionID>-1</PeerConnectionID>
                    <Direction>Input</Direction>
                    <Status>OK</Status>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleEvent(output: OutputStream) {
        // 简化实现：返回 200 OK
        val response = "HTTP/1.1 200 OK\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun buildSoapResponse(action: String, content: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      $content
    </u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    private fun buildSoapFault(errorCode: String, errorDescription: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$errorCode</errorCode>
          <errorDescription>$errorDescription</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""
    }

    private fun sendXmlResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendSoapResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "EXT:\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == 1f) "1" else speed.toString()
    }

    private fun extractSpeed(body: String): String {
        val tagNames = listOf(
            "Speed",
            "DesiredSpeed",
            "Rate",
            "DesiredRate",
            "PlaybackSpeed",
            "TransportPlaySpeed",
            "CurrentSpeed"
        )
        for (tag in tagNames) {
            val value = SoapParser.extractTagValue(body, tag)
            if (value.isNotBlank()) return normalizeSpeed(value)
        }

        // 兜底：有些客户端会把倍速放在非标准字段或属性里
        val regexes = listOf(
            Regex("(?i)<[^>]*(?:speed|rate)[^>]*>([^<]+)</[^>]+>"),
            Regex("(?i)(?:speed|rate)\\s*=\\s*[\"']?([0-9.]+)"),
            Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*x")
        )
        for (regex in regexes) {
            val value = regex.find(body)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return normalizeSpeed(value)
        }
        return ""
    }

    private fun normalizeSpeed(value: String): String {
        return value.trim()
            .removeSuffix("x")
            .removeSuffix("X")
            .toFloatOrNull()
            ?.coerceIn(0.25f, 4f)
            ?.toString()
            ?: ""
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024L
    }
}
