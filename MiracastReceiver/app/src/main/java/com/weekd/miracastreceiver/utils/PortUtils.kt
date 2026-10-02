package com.weekd.miracastreceiver.utils

import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** TCP port selection for the local management WebUI. */
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
        if (fallbackPort != null && fallbackPort in 1024..65535 && fallbackPort !in excludedPorts) {
            candidates += fallbackPort
        }

        for (candidate in candidates) {
            if (isTcpPortAvailable(candidate)) return candidate
        }

        repeat(RANDOM_ATTEMPTS) {
            val candidate = randomHighPort()
            if (candidate !in excludedPorts && isTcpPortAvailable(candidate)) {
                Timber.w("Preferred WebUI port $preferredPort is unavailable; selected fallback port $candidate")
                return candidate
            }
        }

        repeat(8) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate in 1024..65535 && candidate !in excludedPorts) return candidate
        }
        throw IllegalStateException("Unable to allocate a WebUI TCP port")
    }

    /**
     * Atomically bind a listening socket. We try preferred -> previous successful fallback -> random
     * high ports, so there is no probe/bind race on receiver startup.
     */
    fun bindAvailableServerSocket(
        preferredPort: Int,
        fallbackPort: Int? = null,
        excludedPorts: Set<Int> = emptySet(),
        backlog: Int = 32
    ): ServerSocket {
        val candidates = linkedSetOf<Int>()
        if (preferredPort in 1024..65535 && preferredPort !in excludedPorts) candidates += preferredPort
        if (fallbackPort != null && fallbackPort in 1024..65535 && fallbackPort !in excludedPorts) {
            candidates += fallbackPort
        }

        for (candidate in candidates) {
            tryBind(candidate, backlog)?.let { socket ->
                if (candidate != preferredPort) {
                    Timber.w("WebUI preferred port $preferredPort unavailable; reused fallback port $candidate")
                }
                return socket
            }
        }

        repeat(RANDOM_ATTEMPTS) {
            val candidate = randomHighPort()
            if (candidate !in excludedPorts) {
                tryBind(candidate, backlog)?.let { socket ->
                    Timber.w("WebUI preferred port $preferredPort unavailable; bound random port $candidate")
                    return socket
                }
            }
        }

        repeat(8) {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", 0), backlog)
            }
            if (socket.localPort !in excludedPorts && socket.localPort >= 1024) {
                Timber.w("WebUI fell back to kernel-assigned port ${socket.localPort}")
                return socket
            }
            socket.close()
        }
        throw IllegalStateException("Unable to bind a WebUI TCP port")
    }

    fun isTcpPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return tryBind(port, 1)?.use { true } ?: false
    }

    private fun tryBind(port: Int, backlog: Int): ServerSocket? {
        if (port !in 1024..65535) return null
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port), backlog)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun randomHighPort(): Int =
        RANDOM_PORT_MIN + random.nextInt(RANDOM_PORT_MAX - RANDOM_PORT_MIN + 1)
}
