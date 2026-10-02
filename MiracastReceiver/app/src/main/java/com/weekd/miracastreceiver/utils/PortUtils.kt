package com.weekd.miracastreceiver.utils

import timber.log.Timber
import java.net.InetAddress
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
        if (fallbackPort != null && fallbackPort in 1024..65535 && fallbackPort !in excludedPorts) candidates += fallbackPort
        for (candidate in candidates) if (isTcpPortAvailable(candidate)) return candidate

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
     * Bind the primary WebUI listener explicitly on IPv4 wildcard.
     *
     * Android vendor kernels are not consistent about whether an unspecified Java ServerSocket
     * wildcard becomes IPv4, dual-stack, or IPv6-only. The previous platform-wildcard binding could
     * therefore make the WebUI unreachable through the IPv4 LAN address shown on the TV. Keep the
     * management listener deterministic and reachable on 0.0.0.0; a dedicated IPv6 listener can be
     * layered on separately without risking IPv4 reachability.
     */
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
            tryBindIpv4(candidate, backlog)?.let { socket ->
                if (candidate != preferredPort) Timber.w("WebUI preferred port $preferredPort unavailable; reused fallback port $candidate")
                return socket
            }
        }

        repeat(RANDOM_ATTEMPTS) {
            val candidate = randomHighPort()
            if (candidate !in excludedPorts) {
                tryBindIpv4(candidate, backlog)?.let { socket ->
                    Timber.w("WebUI preferred port $preferredPort unavailable; bound random port $candidate")
                    return socket
                }
            }
        }

        repeat(8) {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(IPV4_WILDCARD, 0), backlog)
            }
            if (socket.localPort !in excludedPorts && socket.localPort >= 1024) {
                Timber.w("WebUI fell back to kernel-assigned IPv4 port ${socket.localPort}")
                return socket
            }
            socket.close()
        }
        throw IllegalStateException("Unable to bind a WebUI TCP port")
    }

    fun isTcpPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return tryBindIpv4(port, 1)?.use { true } ?: false
    }

    private fun tryBindIpv4(port: Int, backlog: Int): ServerSocket? {
        if (port !in 1024..65535) return null
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(IPV4_WILDCARD, port), backlog)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun randomHighPort(): Int = RANDOM_PORT_MIN + random.nextInt(RANDOM_PORT_MAX - RANDOM_PORT_MIN + 1)

    private val IPV4_WILDCARD: InetAddress by lazy { InetAddress.getByName("0.0.0.0") }
}
