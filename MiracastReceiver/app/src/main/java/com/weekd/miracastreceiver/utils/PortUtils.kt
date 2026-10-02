package com.weekd.miracastreceiver.utils

import timber.log.Timber
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** TCP port selection and deterministic IPv4/IPv6 listener binding. */
object PortUtils {

    private const val RANDOM_PORT_MIN = 20_000
    private const val RANDOM_PORT_MAX = 60_000
    private const val RANDOM_ATTEMPTS = 48
    private val random = SecureRandom()

    fun findAvailablePort(
        preferredPort: Int,
        fallbackPort: Int? = null,
        excludedPorts: Set<Int> = emptySet()
    ): Int {
        val candidates = linkedSetOf<Int>()
        if (preferredPort in 1024..65535 && preferredPort !in excludedPorts) candidates += preferredPort
        if (fallbackPort != null && fallbackPort in 1024..65535 && fallbackPort !in excludedPorts) candidates += fallbackPort
        for (candidate in candidates) if (isTcpPortAvailable(candidate)) return candidate

        repeat(RANDOM_ATTEMPTS) {
            val candidate = randomHighPort()
            if (candidate !in excludedPorts && isTcpPortAvailable(candidate)) {
                Timber.w("Preferred TCP port $preferredPort is unavailable; selected fallback port $candidate")
                return candidate
            }
        }

        repeat(8) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate in 1024..65535 && candidate !in excludedPorts) return candidate
        }
        throw IllegalStateException("Unable to allocate a TCP port")
    }

    /** Bind the requested port on every usable IP family, or fail without changing the port. */
    fun bindFixedServerSocket(port: Int, backlog: Int = 50): ServerSocket =
        tryBindDualStack(port, backlog)
            ?: throw java.io.IOException("Unable to bind TCP port $port on IPv4/IPv6")

    /**
     * Allocate an OS-selected high port and then bind it through the same deterministic dual-stack
     * path used by fixed listeners. AirPlay 2 uses several SETUP-negotiated ephemeral TCP ports;
     * returning an IPv4-only ephemeral listener while RTSP arrived over IPv6 makes the sender fail
     * immediately after a successful handshake.
     */
    fun bindEphemeralServerSocket(backlog: Int = 50): ServerSocket {
        repeat(RANDOM_ATTEMPTS) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate >= 1024) {
                tryBindDualStack(candidate, backlog)?.let { return it }
            }
        }
        throw java.io.IOException("Unable to allocate a dual-stack ephemeral TCP port")
    }

    /** Bind an available port, preserving the caller's preferred/fallback order. */
    fun bindAvailableServerSocket(
        preferredPort: Int,
        fallbackPort: Int? = null,
        excludedPorts: Set<Int> = emptySet(),
        backlog: Int = 32
    ): ServerSocket {
        val candidates = linkedSetOf<Int>()
        if (preferredPort in 1024..65535 && preferredPort !in excludedPorts) candidates += preferredPort
        if (fallbackPort != null && fallbackPort in 1024..65535 && fallbackPort !in excludedPorts) candidates += fallbackPort

        for (candidate in candidates) {
            tryBindDualStack(candidate, backlog)?.let { socket ->
                if (candidate != preferredPort) {
                    Timber.w("Preferred TCP port $preferredPort unavailable; reused fallback port $candidate")
                }
                return socket
            }
        }

        repeat(RANDOM_ATTEMPTS) {
            val candidate = randomHighPort()
            if (candidate !in excludedPorts) {
                tryBindDualStack(candidate, backlog)?.let { socket ->
                    Timber.w("Preferred TCP port $preferredPort unavailable; bound random port $candidate")
                    return socket
                }
            }
        }

        repeat(8) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate !in excludedPorts && candidate >= 1024) {
                tryBindDualStack(candidate, backlog)?.let { socket ->
                    Timber.w("TCP listener fell back to kernel-selected port $candidate")
                    return socket
                }
            }
        }
        throw IllegalStateException("Unable to bind a dual-stack TCP port")
    }

    fun isTcpPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return tryBindIpv4(port, 1)?.use { true } ?: false
    }

    private fun tryBindDualStack(port: Int, backlog: Int): ServerSocket? {
        if (port !in 1024..65535) return null

        val ipv4WasAvailable = tryBindIpv4(port, 1)?.use { true } ?: false
        if (!ipv4WasAvailable) return null

        val ipv6 = tryBind(IPV6_WILDCARD, port, backlog)
        val ipv4 = tryBindIpv4(port, backlog)
        if (ipv6 == null && ipv4 == null) return null

        val physicalSockets = listOfNotNull(ipv6, ipv4)
        val mode = when {
            ipv6 != null && ipv4 != null -> "separate IPv6 + IPv4 sockets"
            ipv6 != null -> "IPv6 wildcard (dual-stack IPv4-mapped expected)"
            else -> "IPv4-only fallback (IPv6 unavailable on platform)"
        }
        Timber.i("TCP listener bound on port $port using $mode")

        return if (physicalSockets.size == 1) physicalSockets.first()
        else MultiplexingServerSocket(port, physicalSockets)
    }

    private fun tryBindIpv4(port: Int, backlog: Int): ServerSocket? =
        tryBind(IPV4_WILDCARD, port, backlog)

    private fun tryBind(address: InetAddress, port: Int, backlog: Int): ServerSocket? {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, port), backlog)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun randomHighPort(): Int = RANDOM_PORT_MIN + random.nextInt(RANDOM_PORT_MAX - RANDOM_PORT_MIN + 1)

    private val IPV4_WILDCARD: InetAddress by lazy { InetAddress.getByName("0.0.0.0") }
    private val IPV6_WILDCARD: InetAddress by lazy { InetAddress.getByName("::") }

    /** A ServerSocket facade that merges accepts from IPv4 and IPv6 physical listeners. */
    private class MultiplexingServerSocket(
        private val boundPort: Int,
        private val delegates: List<ServerSocket>
    ) : ServerSocket() {
        private val closed = AtomicBoolean(false)
        private val accepted = LinkedBlockingQueue<AcceptResult>()

        init {
            delegates.forEach { delegate ->
                Thread({ acceptLoop(delegate) }, "dual-stack-accept-${delegate.inetAddress.hostAddress}-$boundPort").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun acceptLoop(delegate: ServerSocket) {
            while (!closed.get() && !delegate.isClosed) {
                try {
                    accepted.put(AcceptResult.Client(delegate.accept()))
                } catch (e: Exception) {
                    if (!closed.get() && !delegate.isClosed) {
                        accepted.offer(AcceptResult.Error(e))
                    }
                    break
                }
            }
        }

        override fun accept(): Socket {
            while (!closed.get()) {
                when (val result = accepted.take()) {
                    is AcceptResult.Client -> return result.socket
                    is AcceptResult.Error -> throw SocketException(result.error.message ?: "dual-stack accept failed")
                }
            }
            throw SocketException("Socket is closed")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            delegates.forEach { runCatching { it.close() } }
            super.close()
            accepted.offer(AcceptResult.Error(SocketException("Socket is closed")))
        }

        override fun isClosed(): Boolean = closed.get()
        override fun getLocalPort(): Int = boundPort
        override fun getInetAddress(): InetAddress =
            delegates.firstOrNull { it.inetAddress is Inet6Address }?.inetAddress
                ?: delegates.first().inetAddress

        private sealed class AcceptResult {
            data class Client(val socket: Socket) : AcceptResult()
            data class Error(val error: Exception) : AcceptResult()
        }
    }
}
