package com.weekd.miracastreceiver.miracast

import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wi-Fi Display (Miracast) session acceptor.
 *
 * In WFD the Sink publishes its own control port inside its WFD Information Element and the Source
 * dials that port to begin the RTSP exchange. The Sink is therefore the RTSP *server*: this socket
 * has to be listening before the group is even announced, because a Source starts dialing the
 * moment it parses the element.
 *
 * The opposite direction cannot work and is worth being explicit about. A Source that becomes
 * Group Owner instead — which happens whenever the Sink loses group-owner negotiation — treats
 * itself as the sink and starts its own listener. Dialing back at it would put two servers
 * face to face, each waiting for the other to send OPTIONS, and the session dies on the Source's
 * RTSP timeout. There is no Sink-side fix for a lost negotiation, so the negotiation itself has to
 * be forced (see WfdRootHelper's group-owner-intent handling).
 *
 * The listener binds the wildcard address rather than a specific interface: at the moment the
 * receiver comes up p2p0 does not exist yet, and the Source arrives on whichever interface the
 * group ends up being created on.
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    @Volatile
    private var listener: ServerSocket? = null

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    /** Running session coroutines, so stop() can close them instead of leaking the socket. */
    private val activeSessions = CopyOnWriteArrayList<Job>()

    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    companion object {
        /** How long accept() blocks before the loop re-checks shutdown and re-binds the listener. */
        private const val ACCEPT_TIMEOUT_MS = 1_000

        private const val REOPEN_DELAY_MS = 200L
    }

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        Timber.i("WFD session starter running (control port=$port, RTP=$rtpPort)")

        scope.launch {
            while (isRunning) {
                val socket = acceptSource()
                if (socket != null) serveSource(socket)
            }
        }
    }

    /**
     * Each Source is handled on its own coroutine. Running `runSession` inline in the accept loop
     * was a fatal flaw: it blocks on `readMessage`, and a Source that opens the TCP connection and
     * then goes silent — a half-open socket left by a failed handshake, a peer that bailed mid-M3
     * without sending FIN — parked the loop forever. Every later Source then completed the TCP
     * handshake, sat in the backlog unanswered, and gave up on its own RTSP timeout. That presents
     * to the user as exactly "the receiver shows up in the list but I cannot connect".
     *
     * One session at a time is still how Miracast actually runs; this only guarantees the listener
     * keeps answering instead of freezing.
     */
    private fun serveSource(socket: Socket) {
        val job = scope.launch {
            try {
                runSession(socket)
            } catch (e: Exception) {
                Timber.e(e, "WFD: session coroutine failed")
            }
        }
        activeSessions += job
        job.invokeOnCompletion {
            activeSessions.remove(job)
            runCatching { socket.close() }
        }
    }

    /** Accept the next Source connection, or null so the loop keeps waiting. */
    private suspend fun acceptSource(): Socket? {
        val live = ensureListener() ?: return null
        return try {
            val client = live.accept()
            client.tcpNoDelay = true
            client.keepAlive = true
            Timber.i("WFD: Source connected from ${client.inetAddress.hostAddress}:${client.port}")
            client
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            Timber.w("WFD: listener failed (${e.message}); reopening")
            runCatching { live.close() }
            listener = null
            delay(REOPEN_DELAY_MS)
            null
        }
    }

    private suspend fun ensureListener(): ServerSocket? {
        listener?.takeIf { !it.isClosed }?.let { return it }
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = ACCEPT_TIMEOUT_MS
            }
            listener = socket
            Timber.i("WFD: listening for the Source's RTSP connection on *:$port")
            socket
        } catch (e: Exception) {
            Timber.w(e, "WFD: cannot listen on control port $port — Sources cannot connect; retrying")
            delay(REOPEN_DELAY_MS)
            null
        }
    }

    private fun runSession(socket: Socket) {
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        val sourcePort = socket.port
        WfdSourceHint.update(sourceIp, sourcePort, "rtsp-connected")
        onConnectionRequested?.invoke("Miracast Source", sourceIp)

        // RTP must be listening before SETUP advertises the port; packets may arrive immediately
        // after the PLAY response.
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
        runCatching { listener?.close() }
        listener = null
        activeSessions.forEach { it.cancel() }
        activeSessions.clear()
        sessionHandler?.close()
        sessionHandler = null
        rtpReceiver?.stop()
        rtpReceiver = null
        scope.cancel()
        Timber.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning

    /** The port the listener actually holds, or null if it is not bound right now. */
    fun listeningPort(): Int? = listener?.takeIf { !it.isClosed }?.localPort
}
