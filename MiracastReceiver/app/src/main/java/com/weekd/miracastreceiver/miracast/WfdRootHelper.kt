package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import java.io.File

/**
 * Root-assisted Wi-Fi Display sink advertisement and peer diagnostics.
 *
 * A normal application cannot call the hidden CONFIGURE_WIFI_DISPLAY APIs on modern Android,
 * so rooted Android TV devices inject the WFD Device Information subelement directly into
 * wpa_supplicant. The sink is also put into Extended Listen mode so Miracast sources can reliably
 * discover it. Peer WFD subelements are queried as a fallback when vendor Android hides wfdInfo.
 */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"
    private const val ADVERTISE_THROTTLE_MS = 4_000L

    private val advertiseLock = Any()
    @Volatile private var advertiseInProgress = false
    @Volatile private var lastSuccessfulAdvertiseAt = 0L

    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p0",
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/vendor/wifi/wpa/sockets/wlan1",
        "/data/misc/wifi/sockets/p2p0",
        "/data/misc/wifi/sockets/p2p-dev-wlan0",
        "/data/misc/wifi/sockets/wlan0",
        "/data/misc/wifi/sockets/wlan1"
    )

    /**
     * WFD Device Information subelement value (id 0 is supplied separately to WFD_SUBELEM_SET).
     * 0006 = six-byte payload length
     * 0011 = Primary Sink + Session Available
     * 0000 = no RTSP server on the Sink (standard WFD Sink is the TCP client)
     * 0032 = 50 Mbps maximum throughput
     */
    internal fun subelemHex(controlPort: Int = 0, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort.coerceIn(0, 0xffff)) +
            "%04x".format(maxThroughputMbps.coerceIn(0, 0xffff))

    /** Parse the Source control port from a `P2P_PEER` response's WFD subelements. */
    internal fun parsePeerControlPort(output: String): Int? {
        val hex = Regex("(?im)^wfd_subelems=([0-9a-f]+)\\s*$")
            .find(output)?.groupValues?.getOrNull(1)?.lowercase() ?: return null

        // Peer output is a concatenation of WFD subelements: <id:1><len:2><payload:len>.
        var offset = 0
        while (offset + 6 <= hex.length) {
            val id = hex.substring(offset, offset + 2).toIntOrNull(16) ?: return null
            val lenBytes = hex.substring(offset + 2, offset + 6).toIntOrNull(16) ?: return null
            val payloadStart = offset + 6
            val payloadEnd = payloadStart + lenBytes * 2
            if (payloadEnd > hex.length) return null
            if (id == 0 && lenBytes >= 6) {
                // Device info (2 bytes), control port (2), maximum throughput (2).
                val portHexStart = payloadStart + 4
                val port = hex.substring(portHexStart, portHexStart + 4).toIntOrNull(16)
                return port?.takeIf { it in 1..65535 }
            }
            offset = payloadEnd
        }
        return null
    }

    /**
     * Ask wpa_supplicant for the currently visible/connected WFD peer and read its real Source
     * control port. HyperOS/MIUI sources may use an ephemeral port instead of 7236, while Android's
     * public WifiP2pDevice API often hides the peer wfdInfo field from third-party applications.
     */
    fun discoverSourceControlPort(context: Context): Int? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val appContext = context.applicationContext
        val binary = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return null
        val socketPath = findControlSocket() ?: return null
        val command = "${binary.absolutePath} $socketPath \"P2P_PEER FIRST\""
        val output = runAsRootCapture(command) ?: return null
        val port = parsePeerControlPort(output)
        if (port != null) {
            Timber.i("WFD: Source control port discovered from supplicant peer IE: $port")
        } else {
            Timber.d("WFD: peer WFD IE did not expose a usable Source control port")
        }
        return port
    }

    /** Advertise this device as an available primary Miracast sink. */
    fun advertiseSink(context: Context, controlPort: Int = 0): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAdvertisingAsync(context, controlPort)
            return true
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(advertiseLock) {
            if (advertiseInProgress) {
                Timber.d("WFD: advertisement refresh already in progress")
                return true
            }
            if (lastSuccessfulAdvertiseAt != 0L && now - lastSuccessfulAdvertiseAt < ADVERTISE_THROTTLE_MS) {
                Timber.d("WFD: advertisement still fresh")
                return true
            }
            advertiseInProgress = true
        }

        return try {
            val appContext = context.applicationContext
            val binary = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (!binary.exists()) {
                Timber.w("WFD: $BINARY_NAME not found in nativeLibraryDir")
                return false
            }

            val socketPath = findControlSocket()
            if (socketPath == null) {
                Timber.w("WFD: no usable wpa_supplicant control socket found (root/driver unavailable?)")
                return false
            }

            val cmd = "${binary.absolutePath} $socketPath " +
                "\"SET wifi_display 1\" " +
                "\"WFD_SUBELEM_SET 0 ${subelemHex(controlPort)}\" " +
                "\"P2P_SET discoverability 1\" " +
                "\"P2P_EXT_LISTEN 500 1000\""

            if (runAsRoot(cmd)) {
                lastSuccessfulAdvertiseAt = SystemClock.elapsedRealtime()
                Timber.i(
                    "WFD: primary sink advertised via $socketPath; " +
                        "sinkRtsp=${if (controlPort == 0) "none" else controlPort} extended-listen=500/1000"
                )
                true
            } else {
                Timber.w("WFD: supplicant advertisement command failed")
                false
            }
        } finally {
            advertiseInProgress = false
        }
    }

    fun refreshAdvertisingAsync(context: Context, controlPort: Int = 0) {
        val appContext = context.applicationContext
        Thread({
            runCatching { advertiseSink(appContext, controlPort) }
                .onFailure { Timber.w(it, "WFD: asynchronous advertisement refresh failed") }
        }, "wfd-advertise").apply { isDaemon = true }.start()
    }

    fun stopAdvertising(context: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appContext = context.applicationContext
            Thread({ stopAdvertising(appContext) }, "wfd-stop").apply { isDaemon = true }.start()
            return true
        }
        synchronized(advertiseLock) {
            lastSuccessfulAdvertiseAt = 0L
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socketPath = findControlSocket() ?: return false
        return runAsRoot(
            "${binary.absolutePath} $socketPath " +
                "\"P2P_EXT_LISTEN\" \"SET wifi_display 0\""
        )
    }

    fun isRootAvailable(): Boolean = runAsRoot("id")

    private fun findControlSocket(): String? = CTRL_SOCKET_PATHS.firstOrNull { path ->
        runAsRoot("test -S '$path' || test -e '$path'")
    }

    private fun runAsRoot(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (output.isNotEmpty()) Timber.d("WFD root: $output")
        exit == 0
    } catch (e: Exception) {
        Timber.d("WFD root unavailable: ${e.message}")
        false
    }

    private fun runAsRootCapture(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit == 0) output else {
            Timber.d("WFD root query failed exit=$exit output=${output.trim()}")
            null
        }
    } catch (e: Exception) {
        Timber.d("WFD root query unavailable: ${e.message}")
        null
    }
}
