package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import java.io.File

/** Root-assisted Wi-Fi Display sink advertisement and peer diagnostics. */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"
    private const val ADVERTISE_THROTTLE_MS = 4_000L

    private val advertiseLock = Any()
    @Volatile private var advertiseInProgress = false
    @Volatile private var lastSuccessfulAdvertiseAt = 0L

    /**
     * Prefer the global P2P-device/STA control interfaces. `p2p0` is commonly only a temporary
     * group interface; sending WFD_SUBELEM_SET there can return FAIL while the real device socket
     * (`p2p-dev-wlan0` or `wlan0`) would have accepted it.
     */
    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan1",
        "/data/vendor/wifi/wpa/sockets/wlan1",
        "/data/misc/wifi/sockets/p2p-dev-wlan0",
        "/data/misc/wifi/sockets/wlan0",
        "/data/misc/wifi/sockets/p2p-dev-wlan1",
        "/data/misc/wifi/sockets/wlan1",
        "/data/vendor/wifi/wpa/sockets/p2p0",
        "/data/misc/wifi/sockets/p2p0"
    )

    /** Well-known supplicant control directories, scanned for sockets with other interface names. */
    private val CTRL_SOCKET_DIRS = listOf(
        "/data/vendor/wifi/wpa/sockets",
        "/data/misc/wifi/sockets"
    )

    data class AdvertisementStatus(
        val success: Boolean,
        val socketPath: String? = null,
        val coreWfdConfigured: Boolean = false,
        val extendedListenConfigured: Boolean = false,
        val verified: Boolean = false,
        val detail: String = "not attempted"
    )

    @Volatile
    private var lastAdvertisementStatus = AdvertisementStatus(success = false)

    fun advertisementStatus(): AdvertisementStatus = lastAdvertisementStatus

    /**
     * WFD Device Information subelement value (id 0 is supplied separately to WFD_SUBELEM_SET).
     * 0006 = six-byte payload length
     * 0011 = Primary Sink + Session Available
     * 1c44 = RTSP control port 7236
     * 0032 = 50 Mbps maximum throughput
     */
    internal fun subelemHex(controlPort: Int = 7236, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort.coerceIn(0, 0xffff)) +
            "%04x".format(maxThroughputMbps.coerceIn(0, 0xffff))

    internal fun parsePeerControlPort(output: String): Int? {
        val hex = Regex("(?im)^wfd_subelems=([0-9a-f]+)\\s*$")
            .find(output)?.groupValues?.getOrNull(1)?.lowercase() ?: return null

        var offset = 0
        while (offset + 6 <= hex.length) {
            val id = hex.substring(offset, offset + 2).toIntOrNull(16) ?: return null
            val lenBytes = hex.substring(offset + 2, offset + 6).toIntOrNull(16) ?: return null
            val payloadStart = offset + 6
            val payloadEnd = payloadStart + lenBytes * 2
            if (payloadEnd > hex.length) return null
            if (id == 0 && lenBytes >= 6) {
                val portHexStart = payloadStart + 4
                val port = hex.substring(portHexStart, portHexStart + 4).toIntOrNull(16)
                return port?.takeIf { it in 1..65535 }
            }
            offset = payloadEnd
        }
        return null
    }

    fun discoverSourceControlPort(context: Context): Int? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val appContext = context.applicationContext
        val binary = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return null

        for (socketPath in existingControlSockets()) {
            val command = "${binary.absolutePath} $socketPath \"P2P_PEER FIRST\""
            val output = runAsRootCapture(command) ?: continue
            val port = parsePeerControlPort(output)
            if (port != null) {
                Timber.i("WFD: Source control port discovered via $socketPath: $port")
                return port
            }
        }
        Timber.d("WFD: peer WFD IE did not expose a usable Source control port")
        return null
    }

    /** Advertise this device as an available primary Miracast sink. */
    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAdvertisingAsync(context, controlPort)
            return true
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(advertiseLock) {
            if (advertiseInProgress) {
                Timber.d("WFD: advertisement refresh already in progress")
                return lastAdvertisementStatus.success
            }
            if (lastSuccessfulAdvertiseAt != 0L && now - lastSuccessfulAdvertiseAt < ADVERTISE_THROTTLE_MS) {
                Timber.d("WFD: advertisement still fresh via ${lastAdvertisementStatus.socketPath}")
                return lastAdvertisementStatus.success
            }
            advertiseInProgress = true
        }

        return try {
            val appContext = context.applicationContext
            val binary = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (!binary.exists()) {
                lastAdvertisementStatus = AdvertisementStatus(false, detail = "$BINARY_NAME missing")
                Timber.w("WFD: $BINARY_NAME not found in nativeLibraryDir")
                return false
            }

            val candidates = existingControlSockets()
            if (candidates.isEmpty()) {
                lastAdvertisementStatus = AdvertisementStatus(false, detail = "no supplicant control socket")
                Timber.w("WFD: no usable wpa_supplicant control socket found (root/driver unavailable?)")
                return false
            }

            val payload = subelemHex(controlPort)
            var lastDetail = "all control sockets rejected WFD commands"

            for (socketPath in candidates) {
                // Configure the two commands Xiaomi/HyperOS actually filters on first. They are
                // considered the core success condition; optional discoverability commands are
                // best-effort because several vendor supplicants do not implement P2P_SET.
                val coreCmd = "${binary.absolutePath} $socketPath " +
                    "\"SET wifi_display 1\" " +
                    "\"WFD_SUBELEM_SET 0 $payload\""
                if (!runAsRoot(coreCmd)) {
                    lastDetail = "core WFD IE rejected on $socketPath"
                    Timber.w("WFD: core advertisement rejected by $socketPath; trying next control interface")
                    continue
                }

                // Read-back verification: wfdctl treats "sent, no reply" as sent, so a filtered or
                // SELinux-blocked command would otherwise look successful while the sink stays
                // invisible to Miracast sources. A real reply with wrong content fails this socket.
                val verification = verifyAdvertisement(binary.absolutePath, socketPath, payload)
                if (verification.replied && !verification.ok) {
                    lastDetail = "verification failed on $socketPath (${verification.detail})"
                    Timber.w("WFD: $socketPath accepted the command but read-back disagrees; trying next")
                    continue
                }

                val discoverabilityOk = runAsRoot(
                    "${binary.absolutePath} $socketPath \"P2P_SET discoverability 1\""
                )
                val listenOk = runAsRoot(
                    "${binary.absolutePath} $socketPath \"P2P_EXT_LISTEN 500 1000\""
                )

                lastSuccessfulAdvertiseAt = SystemClock.elapsedRealtime()
                lastAdvertisementStatus = AdvertisementStatus(
                    success = true,
                    socketPath = socketPath,
                    coreWfdConfigured = true,
                    extendedListenConfigured = listenOk,
                    verified = verification.ok,
                    detail = buildString {
                        append("Primary Sink + Session Available + RTSP ")
                        append(controlPort)
                        append("; discoverability=")
                        append(discoverabilityOk)
                        append("; extendedListen=")
                        append(listenOk)
                        append("; verified=")
                        append(verification.ok)
                        if (!verification.replied) append(" (supplicant reply blocked; assume accepted)")
                    }
                )
                Timber.i(
                    "WFD: primary sink advertised via $socketPath; advertisedRtsp=$controlPort " +
                        "discoverability=$discoverabilityOk extended-listen=$listenOk verified=${verification.ok}"
                )
                return true
            }

            lastAdvertisementStatus = AdvertisementStatus(false, detail = lastDetail)
            Timber.w("WFD: no supplicant interface accepted the core WFD sink advertisement")
            false
        } finally {
            advertiseInProgress = false
        }
    }

    fun refreshAdvertisingAsync(context: Context, controlPort: Int = 7236) {
        val appContext = context.applicationContext
        Thread({
            runCatching { advertiseSink(appContext, controlPort) }
                .onFailure {
                    lastAdvertisementStatus = AdvertisementStatus(false, detail = it.message ?: "advertisement exception")
                    Timber.w(it, "WFD: asynchronous advertisement refresh failed")
                }
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
        lastAdvertisementStatus = AdvertisementStatus(false, detail = "stopped")

        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        var sent = false
        existingControlSockets().forEach { socketPath ->
            if (runAsRoot(
                    "${binary.absolutePath} $socketPath " +
                        "\"P2P_EXT_LISTEN\" \"SET wifi_display 0\""
                )
            ) sent = true
        }
        return sent
    }

    fun isRootAvailable(): Boolean = runAsRoot("id")

    private class Verification(val replied: Boolean, val ok: Boolean, val detail: String)

    /**
     * Read the state back with WFD_SUBELEM_GET 0. Reply semantics:
     * - hex containing the injected payload -> verified
     * - hex with different content          -> the SET did not stick; socket rejected
     * - FAIL / UNKNOWN / empty              -> vendor supplicant without WFD_SUBELEM_GET or a
     *   blocked reply path; treated as unverified (same as the historic no-reply behavior) instead
     *   of failing a working injection.
     */
    private fun verifyAdvertisement(binaryPath: String, socketPath: String, payload: String): Verification {
        val output = runAsRootOutput("$binaryPath $socketPath \"WFD_SUBELEM_GET 0\"")
        if (output.isNullOrBlank()) return Verification(replied = false, ok = true, detail = "no-reply")
        val reply = output.lineSequence()
            .firstOrNull { it.contains("WFD_SUBELEM_GET") && it.contains("->") }
            ?.substringAfter("->")?.trim().orEmpty()
        val normalized = reply.lowercase().removePrefix("0x")
        return when {
            normalized.contains(payload) -> Verification(replied = true, ok = true, detail = "read-back ok")
            normalized.length >= 12 && normalized.all { it.isDigit() || it in 'a'..'f' } ->
                Verification(replied = true, ok = false, detail = "subelem mismatch: $reply")
            else -> Verification(replied = false, ok = true, detail = "get unsupported ($reply); assume accepted")
        }
    }

    /**
     * Static candidates first, then a live scan of the well-known control directories so sockets
     * with non-standard interface names (wlan2, vendor-renamed p2p devices on Android 15–17
     * builds) are still discovered.
     */
    private fun existingControlSockets(): List<String> {
        val found = CTRL_SOCKET_PATHS.filter { path ->
            runAsRoot("test -S '$path' || test -e '$path'")
        }.toMutableList()
        CTRL_SOCKET_DIRS.forEach { dir ->
            val listing = runAsRootOutput("ls -1 '$dir' 2>/dev/null") ?: return@forEach
            listing.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("p2p") || it.startsWith("wlan") }
                .forEach { name ->
                    val full = "$dir/$name"
                    if (full !in found && runAsRoot("test -S '$full' || test -e '$full'")) found += full
                }
        }
        return found
    }

    /** Periodic re-advertisement: supplicant restarts and vendor scans silently clear WFD state. */
    @Volatile private var keepAliveRunning = false
    @Volatile private var keepAliveThread: Thread? = null

    fun startKeepAlive(context: Context, periodMs: Long = 45_000L) {
        if (keepAliveRunning) return
        keepAliveRunning = true
        val appContext = context.applicationContext
        keepAliveThread = Thread({
            while (keepAliveRunning) {
                runCatching { advertiseSink(appContext) }
                var sleptMs = 0L
                while (keepAliveRunning && sleptMs < periodMs) {
                    Thread.sleep(1_000L)
                    sleptMs += 1_000L
                }
            }
        }, "wfd-keepalive").apply { isDaemon = true; start() }
    }

    fun stopKeepAlive() {
        keepAliveRunning = false
        keepAliveThread = null
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

    private fun runAsRootOutput(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (exit == 0) output.ifEmpty { null } else {
            Timber.d("WFD root query failed exit=$exit output=${output.take(200)}")
            null
        }
    } catch (e: Exception) {
        Timber.d("WFD root query unavailable: ${e.message}")
        null
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
