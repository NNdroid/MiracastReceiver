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
            val candidate = RANDOM_PORT_MIN + random.nextInt(RANDOM_PORT_MAX - RANDOM_PORT_MIN + 1)
            if (candidate !in excludedPorts && isTcpPortAvailable(candidate)) {
                Timber.w("Preferred WebUI port $preferredPort is unavailable; selected fallback port $candidate")
                return candidate
            }
        }

        // Extremely unlikely fallback: ask the kernel for an ephemeral port, retry if it collides
        // with one of the receiver's reserved ports.
        repeat(8) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate in 1024..65535 && candidate !in excludedPorts) return candidate
        }
        throw IllegalStateException("Unable to allocate a WebUI TCP port")
    }

    fun isTcpPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
