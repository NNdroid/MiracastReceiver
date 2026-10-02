package com.weekd.miracastreceiver.miracast

import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) session starter.
 *
 * In standard WFD the Source is the RTSP TCP server and the Sink connects to it. The Source may
 * choose an ephemeral RTSP control port, so endpoint hints from Android and wpa_supplicant are
 * preferred over the legacy 7236 fallback.
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 450
        private const val SCAN_CHUNK = 32
        private const val RTSP_SOCKET_TIMEOUT_MS = 30_000
        private const val ROOT_PORT_REFRESH_MS = 5_000L
    }

    private var lastRootPortProbeAt = 0L
    private var lastRootDiscoveredPort: Int? = null

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        Timber.i("WFD session starter running (default source port=$port, RTP=$rtpPort)")

        scope.launch {
            while (isRunning) {
                try {
                    val socket = dialSource()
                    if (socket != null) runSession(socket)
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "WFD session loop error")
                }
                if (isRunning) delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /** Keep the socket that succeeds; some Sources accept only one RTSP connection per attempt. */
    private suspend fun dialSource(): Socket? = coroutineScope {
        val hint = WfdSourceHint.snapshot()
        val supplicantPort = sourcePortFromSupplicant()
        val candidatePorts = buildList {
            hint.controlPort?.takeIf { it in 1..65535 }?.let { add(it) }
            supplicantPort?.takeIf { it in 1..65535 && it !in this }?.let { add(it) }
            if (port !in this) add(port)
        }

        if (candidatePorts.isEmpty()) return@coroutineScope null
        Timber.d(
            "WFD: source candidates ip=${hint.ipAddress ?: "scan"} " +
                "ports=${candidatePorts.joinToString()} frameworkPort=${hint.controlPort ?: "?"} " +
                "supplicantPort=${supplicantPort ?: "?"}"
        )

        // If the Source is group owner, Android gives us the exact P2P address.
        hint.ipAddress?.let { ip ->
            for (candidatePort in candidatePorts) {
                if (!isRunning) return@coroutineScope null
                tryConnect(ip, candidatePort)?.let { socket ->
                    Timber.i("WFD: connected to hinted source $ip:$candidatePort")
                    WfdSourceHint.update(ipAddress = ip, controlPort = candidatePort, reason = "rtsp-dial")
                    return@coroutineScope socket
                }
            }
        }

        // If this Sink is GO, the Source is a P2P client and Android does not expose its IP. Probe
        // the active P2P subnet, but only on the learned Source port(s) plus standards fallback.
        val prefix = p2pSubnetPrefix() ?: return@coroutineScope null
        for (candidatePort in candidatePorts) {
            for (chunkStart in 2..254 step SCAN_CHUNK) {
                if (!isRunning) return@coroutineScope null
                val range = chunkStart until minOf(chunkStart + SCAN_CHUNK, 255)
                val connected = range.map { host ->
                    async { tryConnect("$prefix$host", candidatePort) }
                }.awaitAll().filterNotNull()

                if (connected.isNotEmpty()) {
                    val session = connected.first()
                    connected.drop(1).forEach { runCatching { it.close() } }
                    val sourceIp = session.inetAddress.hostAddress
                    Timber.i(
                        "WFD: connected to scanned source $sourceIp:$candidatePort " +
                            "(frameworkPort=${hint.controlPort ?: "none"} supplicantPort=${supplicantPort ?: "none"})"
                    )
                    WfdSourceHint.update(
                        ipAddress = sourceIp,
                        controlPort = candidatePort,
                        reason = "rtsp-scan"
                    )
                    return@coroutineScope session
                }
            }
        }
        null
    }

    private fun sourcePortFromSupplicant(): Int? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRootPortProbeAt != 0L && now - lastRootPortProbeAt < ROOT_PORT_REFRESH_MS) {
            return lastRootDiscoveredPort
        }
        lastRootPortProbeAt = now
        lastRootDiscoveredPort = runCatching { WfdRootHelper.discoverSourceControlPort(context) }
            .onFailure { Timber.d("WFD: supplicant Source-port query failed: ${it.message}") }
            .getOrNull()
        lastRootDiscoveredPort?.let {
            WfdSourceHint.update(controlPort = it, reason = "supplicant-peer-ie")
        }
        return lastRootDiscoveredPort
    }

    private fun tryConnect(ip: String, targetPort: Int): Socket? = try {
        Socket().also { socket ->
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(ip, targetPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = RTSP_SOCKET_TIMEOUT_MS
        }
    } catch (_: Exception) {
        null
    }

    private fun p2pSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("p2p") && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    } catch (e: Exception) {
        Timber.e(e, "Failed to resolve P2P subnet")
        null
    }

    private fun runSession(socket: Socket) {
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        val sourcePort = socket.port
        WfdSourceHint.update(sourceIp, sourcePort, "rtsp-connected")
        onConnectionRequested?.invoke("Miracast Source", sourceIp)

        // RTP must be listening before M3 advertises the port; packets may arrive immediately after
        // the PLAY response.
        val receiver = RtpReceiver(
            rtpPort,
            { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface }
        ).apply {
            onError = { Timber.e("Miracast RTP error: $it") }
            start()
        }
        rtpReceiver = receiver

        val handler = WfdSessionHandler(context, socket, rtpPort).apply {
            onSessionEstablished = { onConnectionEstablished?.invoke(it) }
            onStreamStart = { onStreamStarted?.invoke(it) }
            onStreamStop = { onStreamStopped?.invoke() }
        }
        sessionHandler = handler

        try {
            handler.handleSession()
        } finally {
            receiver.stop()
            rtpReceiver = null
            sessionHandler = null
        }
    }

    fun stop() {
        isRunning = false
        sessionHandler?.close()
        sessionHandler = null
        rtpReceiver?.stop()
        rtpReceiver = null
        scope.cancel()
        Timber.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning
}
