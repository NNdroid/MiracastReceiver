package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import java.io.File

/**
 * Root-assisted Wi-Fi Display sink advertisement.
 *
 * A normal application cannot call the hidden CONFIGURE_WIFI_DISPLAY APIs on modern Android,
 * so rooted Android TV devices inject the WFD Device Information subelement directly into
 * wpa_supplicant. In addition to the WFD IE, the sink is put into Extended Listen mode so
 * Miracast sources (including Xiaomi/HyperOS) can reliably discover it.
 */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"
    private const val ADVERTISE_THROTTLE_MS = 4_000L

    private val advertiseLock = Any()
    @Volatile private var advertiseInProgress = false
    @Volatile private var lastSuccessfulAdvertiseAt = 0L

    /** Common Android/vendor supplicant control socket names. */
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
     * WFD Device Information subelement (id 0).
     * 0006 = six-byte payload length
     * 0011 = Primary Sink + Session Available
     * 1c44 = RTSP port 7236
     * 0032 = 50 Mbps maximum throughput
     */
    internal fun subelemHex(controlPort: Int, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort) + "%04x".format(maxThroughputMbps)

    /**
     * Advertise this device as an available primary Miracast sink.
     *
     * Extended Listen is important on TV devices that do not otherwise stay in a P2P Listen
     * state. 500/1000 means the device is available for at least 500 ms every second while idle.
     */
    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean {
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
            val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
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
                        "RTSP=$controlPort extended-listen=500/1000"
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

    /** Refresh after P2P state/group changes without blocking Android framework callbacks. */
    fun refreshAdvertisingAsync(context: Context, controlPort: Int = 7236) {
        val appContext = context.applicationContext
        Thread({
            runCatching { advertiseSink(appContext, controlPort) }
                .onFailure { Timber.w(it, "WFD: asynchronous advertisement refresh failed") }
        }, "wfd-advertise").apply { isDaemon = true }.start()
    }

    /** Disable listen timing and WFD advertisement when Miracast is turned off. */
    fun stopAdvertising(context: Context): Boolean {
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
}
