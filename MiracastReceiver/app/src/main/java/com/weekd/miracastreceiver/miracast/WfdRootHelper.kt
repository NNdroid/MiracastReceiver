package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/** Root-assisted Wi-Fi Display sink advertisement and peer diagnostics. */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"
    private const val ADVERTISE_THROTTLE_MS = 4_000L
    private const val GO_OWNER_INTENT = 15

    /** `wfdctl` exit codes. See the header comment in wfdctl.c: "sent" is not "configured". */
    private const val WFDCTL_OK = 0
    private const val WFDCTL_REJECTED = 1
    private const val WFDCTL_UNCONFIRMED = 2

    private enum class SocketKind { P2P_DEV, GROUP_IFACE, STA_FALLBACK }

    private val advertiseLock = Any()
    @Volatile private var advertiseInProgress = false
    @Volatile private var lastSuccessfulAdvertiseAt = 0L

    /**
     * `p2p-dev-*` is the only interface type on which `WFD_SUBELEM_SET` can produce a P2P
     * advertisement, so it is tried first. `wlan*` (STA) and `p2p*` (temporary group) are kept as
     * last resorts on vendor supplicants that route the command differently — but success there is
     * recorded as unverified, because a WFD IE set on an STA interface never reaches the air.
     */
    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan1",
        "/data/vendor/wifi/wpa/sockets/wlan1",
        "/data/vendor/wifi/wpa/sockets/p2p-dev-wlan2",
        "/data/vendor/wifi/wpa/sockets/wlan2",
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
        val groupOwnerIntentConfigured: Boolean = false,
        val socketKind: String = "none",
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

    /**
     * Resolve the wfdctl helper path. The helper is packaged as `lib/<abi>/libwfdctl.so` so it
     * travels with the APK, but on-device native-lib extraction is disabled on many builds:
     * `applicationInfo.nativeLibraryDir` then points at a directory that holds nothing, and the
     * helper has to be unpacked from the APK ourselves.
     */
    private fun helperBinary(context: Context): String? {
        val appContext = context.applicationContext
        val packaged = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (packaged.exists()) return packaged.absolutePath
        return extractHelperFromApk(appContext)
    }

    /**
     * Unpack the helper straight out of the APK when the platform never extracted it. Writing
     * through the system zip is not enough on its own: app-owned files are not executable for the
     * `su` domain, so the mode is fixed up under root afterwards.
     */
    private fun extractHelperFromApk(appContext: Context): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        // `Context.getPackageInfo` takes arguments, so there is no synthetic `packageInfo` property;
        // `ApplicationInfo.sourceDir` is the APK path we need.
        val apk = appContext.applicationInfo.sourceDir ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val entryName = "lib/$abi/$BINARY_NAME"
        val dest = File(File(appContext.filesDir, "wfdctl"), BINARY_NAME)

        val extractedPath = runCatching {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@runCatching null
                val directory = dest.parentFile ?: return@runCatching null
                if (!directory.exists() && !directory.mkdirs()) return@runCatching null
                val staging = File(directory, "$BINARY_NAME.tmp")
                zip.getInputStream(entry).use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                }
                if (!staging.renameTo(dest) && dest.exists()) staging.delete()
                if (dest.exists()) dest.absolutePath else null
            }
        }.getOrElse {
            Timber.w(it, "WFD: could not read $entryName out of the APK")
            null
        }

        if (extractedPath == null) {
            Timber.w("WFD: $entryName is not present in the APK; the helper cannot be unpacked")
            return null
        }
        if (runAsRoot("chmod 755 '$extractedPath'").exitCode != 0) {
            Timber.w("WFD: could not make $extractedPath executable for su")
        }
        Timber.w("WFD: native libraries were not extracted on this device; using $extractedPath")
        return extractedPath
    }

    fun discoverSourceControlPort(context: Context): Int? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val binaryPath = helperBinary(context) ?: return null

        for (socketPath in existingControlSockets()) {
            val command = "$binaryPath $socketPath \"P2P_PEER FIRST\""
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
    fun advertiseSink(context: Context, controlPort: Int = 7236, force: Boolean = false): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAdvertisingAsync(context, controlPort, force)
            return true
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(advertiseLock) {
            if (advertiseInProgress) {
                Timber.d("WFD: advertisement refresh already in progress")
                return lastAdvertisementStatus.success
            }
            if (!force && lastSuccessfulAdvertiseAt != 0L && now - lastSuccessfulAdvertiseAt < ADVERTISE_THROTTLE_MS) {
                Timber.d("WFD: advertisement still fresh via ${lastAdvertisementStatus.socketPath}")
                return lastAdvertisementStatus.success
            }
            advertiseInProgress = true
        }

        return try {
            val appContext = context.applicationContext
            val binaryPath = helperBinary(appContext) ?: run {
                lastAdvertisementStatus = AdvertisementStatus(
                    success = false,
                    detail = "$BINARY_NAME missing: not in nativeLibraryDir " +
                        appContext.applicationInfo.nativeLibraryDir + " and not unpackable from the APK"
                )
                Timber.w(
                    "WFD: $BINARY_NAME unavailable, so nothing can be advertised " +
                        "(nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir})"
                )
                return false
            }
            // The vendor switch that gates WFD discovery is off by default on several boxes.
            runAsRoot("settings put global wifi_display_on 1")

            val candidates = existingControlSockets()
            if (candidates.isEmpty()) {
                lastAdvertisementStatus = AdvertisementStatus(false, detail = "no supplicant control socket")
                Timber.w("WFD: no usable wpa_supplicant control socket found (root/driver unavailable?)")
                return false
            }

            val payload = subelemHex(controlPort)

            // One attempt per socket, then pick the best. Scoring order matters: the correct
            // interface type beats an acknowledged reply on the wrong one, and an acknowledged
            // reply beats a bare "sent, no reply".
            val attempts = candidates.map { socketPath ->
                val kind = kindOf(socketPath)
                val exit = runAsRoot(
                    "$binaryPath $socketPath \"SET wifi_display 1\" \"WFD_SUBELEM_SET 0 $payload\""
                ).exitCode
                if (exit == WFDCTL_REJECTED) {
                    Attempt(socketPath, kind, exit, verified = false, detail = "rejected")
                } else {
                    val readBack = verifyAdvertisement(binaryPath, socketPath, payload)
                    if (readBack.replied && !readBack.ok) {
                        Attempt(socketPath, kind, WFDCTL_REJECTED, verified = false, detail = readBack.detail)
                    } else {
                        Attempt(socketPath, kind, exit, verified = readBack.ok, detail = readBack.detail)
                    }
                }
            }

            val chosen = attempts.minByOrNull { it.score() }
                ?.takeIf { it.exit != WFDCTL_REJECTED }

            attempts.forEach {
                Timber.d("WFD: ${it.socketPath} kind=${it.kind} exit=${it.exit} ${it.detail}")
            }

            if (chosen == null) {
                lastAdvertisementStatus = AdvertisementStatus(
                    success = false,
                    detail = "core advertisement rejected on every control socket: " +
                        attempts.joinToString("; ") { "${it.socketPath.substringAfterLast('/')}(${it.kind})" }
                )
                Timber.w("WFD: no supplicant interface accepted the core WFD sink advertisement")
                false
            } else {
                val discoverabilityOk = runAsRoot(
                    "$binaryPath ${chosen.socketPath} \"P2P_SET discoverability 1\""
                ).exitCode != WFDCTL_REJECTED
                val listenOk = runAsRoot(
                    "$binaryPath ${chosen.socketPath} \"P2P_EXT_LISTEN 500 1000\""
                ).exitCode != WFDCTL_REJECTED

                // Rejected commands are not throttled, so a broken path is retried on the next
                // refresh instead of being remembered as done.
                lastSuccessfulAdvertiseAt = SystemClock.elapsedRealtime()
                lastAdvertisementStatus = AdvertisementStatus(
                    success = true,
                    socketPath = chosen.socketPath,
                    coreWfdConfigured = true,
                    extendedListenConfigured = listenOk,
                    verified = chosen.verified,
                    groupOwnerIntentConfigured = lastGroupOwnerIntentConfigured,
                    socketKind = chosen.kind.name,
                    detail = buildString {
                        append("Primary Sink + Session Available + RTSP ")
                        append(controlPort)
                        append("; socket=${chosen.kind}")
                        append("; discoverability=")
                        append(discoverabilityOk)
                        append("; extendedListen=")
                        append(listenOk)
                        append("; verified=")
                        append(chosen.verified)
                        if (chosen.exit == WFDCTL_UNCONFIRMED) {
                            append(" (wpa_supplicant never replied; SELinux is likely blocking the response)")
                        }
                        if (chosen.kind != SocketKind.P2P_DEV) {
                            append("; NOT on p2p-dev: WFD IE here cannot become a P2P advertisement")
                        }
                    }
                )
                Timber.i(
                    "WFD: primary sink advertised via ${chosen.socketPath}; advertisedRtsp=$controlPort " +
                        "discoverability=$discoverabilityOk extended-listen=$listenOk verified=${chosen.verified}"
                )
                if (chosen.kind != SocketKind.P2P_DEV) {
                    Timber.w(
                        "WFD: advertisement went to a ${chosen.kind} interface; a P2P source will not " +
                            "see this device. Check that p2p-dev-wlan0 exists (Wi-Fi Direct must be initialized first)."
                    )
                }
                true
            }
        } finally {
            synchronized(advertiseLock) {
                advertiseInProgress = false
            }
        }
    }

    fun refreshAdvertisingAsync(context: Context, controlPort: Int = 7236, force: Boolean = false) {
        val appContext = context.applicationContext
        Thread({
            runCatching { advertiseSink(appContext, controlPort, force) }
                .onFailure {
                    lastAdvertisementStatus = AdvertisementStatus(false, detail = it.message ?: "advertisement exception")
                    Timber.w(it, "WFD: asynchronous advertisement refresh failed")
                }
        }, "wfd-advertise").apply { isDaemon = true }.start()
    }

    @Volatile
    private var lastGroupOwnerIntentConfigured = false

    /**
     * Raise the supplicant's Group Owner intent to the maximum. The WFD spec makes the Sink the
     * Group Owner; a supplicant left at the default intent loses the GO negotiation against an
     * Android Source, which asks for the lowest intent, and the session never comes up.
     *
     * Returns true only when the command was acknowledged; an unconfirmed reply is reported as
     * false so the caller can surface it instead of assuming the sink will win the negotiation.
     */
    fun configureGroupOwnerIntent(context: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appContext = context.applicationContext
            Thread({ lastGroupOwnerIntentConfigured = configureGroupOwnerIntent(appContext) }, "wfd-go-intent")
                .apply { isDaemon = true }.start()
            return true
        }

        val binaryPath = helperBinary(context)
        if (binaryPath == null) {
            lastGroupOwnerIntentConfigured = false
            Timber.w("WFD: helper binary unavailable, group owner intent not configured")
            return false
        }

        val sockets = existingControlSockets().filter { kindOf(it) == SocketKind.P2P_DEV }
        if (sockets.isEmpty()) {
            lastGroupOwnerIntentConfigured = false
            Timber.w("WFD: no p2p-dev control socket, group owner intent not set")
            return false
        }

        var acknowledged = false
        for (socketPath in sockets) {
            val exit = runAsRoot("${binaryPath} $socketPath \"P2P_SET go_int $GO_OWNER_INTENT\"").exitCode
            when (exit) {
                WFDCTL_OK -> {
                    acknowledged = true
                    Timber.i("WFD: group owner intent set to $GO_OWNER_INTENT via $socketPath")
                }
                WFDCTL_UNCONFIRMED -> Timber.w("WFD: P2P_SET go_int sent to $socketPath but never acknowledged")
                else -> Timber.w("WFD: $socketPath rejected P2P_SET go_int")
            }
            if (acknowledged) break
        }

        lastGroupOwnerIntentConfigured = acknowledged
        if (!acknowledged) {
            Timber.w(
                "WFD: group owner intent not set; if the Source still does not see this device, run " +
                    "\"P2P_SET go_int $GO_OWNER_INTENT\" manually on p2p-dev-wlan0 or add group_owner_intent=$GO_OWNER_INTENT " +
                    "to the supplicant configuration."
            )
        }
        return acknowledged
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
        // Wait for an in-flight advertisement to finish, otherwise it would re-inject the WFD IE
        // straight after it is cleared and the sink would keep being discoverable after shutdown.
        waitForAdvertiseIdle()
        lastAdvertisementStatus = AdvertisementStatus(success = false, detail = "stopped")
        lastGroupOwnerIntentConfigured = false

        val binaryPath = helperBinary(context) ?: return false
        var sent = false
        existingControlSockets().forEach { socketPath ->
            if (runAsRoot(
                    "${binaryPath} $socketPath " +
                        "\"P2P_EXT_LISTEN\" \"SET wifi_display 0\""
                ).exitCode != WFDCTL_REJECTED
            ) sent = true
        }
        return sent
    }

    fun isRootAvailable(): Boolean = runAsRoot("id").success

    /**
     * Live state of the advertisement path, for the WebUI diagnostics page. Every entry is a real
     * command and its output, so a misconfigured sink is visible without a phone in hand.
     * Synchronous — it spawns several `su` processes, so call it off the main thread.
     */
    fun diagnostics(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        val appContext = context.applicationContext
        out["rootAvailable"] = isRootAvailable().toString()
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // The single most common reason a sink never appears: the Wi-Fi radio is off on an
        // Ethernet-only TV box, so P2P never initializes and no p2p-dev-* socket ever exists.
        out["wifiEnabled"] = if (wifi == null) "WIFI_SERVICE_MISSING" else wifi.isWifiEnabled.toString()
        val binaryPath = helperBinary(appContext)
        out["nativeLibraryDir"] = appContext.applicationInfo.nativeLibraryDir
        out["nativeLibOnDisk"] = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME).exists().toString()
        out["wfdctlBinary"] = binaryPath
            ?: "MISSING: $BINARY_NAME (absent from nativeLibraryDir and not unpackable from the APK)"
        val sockets = existingControlSockets()
        out["controlSockets"] = sockets.joinToString(", ") { "${it}[${kindOf(it)}]" }.ifEmpty { "NONE FOUND" }
        out["p2pDevSockets"] = sockets.filter { kindOf(it) == SocketKind.P2P_DEV }.joinToString(", ")
            .ifEmpty { "NONE — Wi-Fi Direct not initialized, so WFD cannot be advertised" }
        out["lastAdvertisement"] = advertisementStatus().detail
        out["lastAdvertisementSocket"] = advertisementStatus().socketPath.orEmpty()
        out["lastVerified"] = advertisementStatus().verified.toString()
        out["groupOwnerIntentConfigured"] = lastGroupOwnerIntentConfigured.toString()

        if (binaryPath != null) {
            val firstSocket = sockets.firstOrNull()
            if (firstSocket != null) {
                out["wfdSubelemReadback"] = runAsRootOutput(
                    "${binaryPath} $firstSocket \"WFD_SUBELEM_GET 0\""
                ) ?: "no output"
                out["p2pGet"] = runAsRootOutput("${binaryPath} $firstSocket \"P2P_GET\"") ?: "no output"
            }
            out["wfdSupport"] = wpaSupplicantWfdSupport()
        }
        out["groupInterface"] = runAsRootOutput("ip -4 addr show p2p0").orEmpty().ifBlank {
            "p2p0 does not exist — this device is not a Group Owner, so sources cannot find it"
        }
        out["selinux"] = runAsRootOutput("getenforce").orEmpty()
        out["wifiDisplaySetting"] = runAsRootOutput("settings get global wifi_display_on").orEmpty()
        return out
    }

    private class Attempt(
        val socketPath: String,
        val kind: SocketKind,
        val exit: Int,
        val verified: Boolean,
        val detail: String
    ) {
        /**
         * Lower is better. The interface type dominates the exit status: an acknowledged reply on
         * the p2p device socket is worth more than a clean reply on the STA socket, which cannot
         * advertise over the air at all. Rejected commands always rank worst, so `minByOrNull`
         * only returns one of them when every socket refused.
         */
        fun score(): Int = when (kind) {
            SocketKind.P2P_DEV -> when (exit) {
                WFDCTL_OK -> if (verified) 0 else 2
                WFDCTL_UNCONFIRMED -> 3
                else -> 6
            }
            SocketKind.GROUP_IFACE -> when (exit) {
                WFDCTL_OK -> 4
                WFDCTL_UNCONFIRMED -> 5
                else -> 7
            }
            SocketKind.STA_FALLBACK -> when (exit) {
                WFDCTL_OK -> 5
                WFDCTL_UNCONFIRMED -> 6
                else -> 7
            }
        }
    }

    private fun kindOf(socketPath: String): SocketKind {
        val name = socketPath.substringAfterLast('/')
        return when {
            name.startsWith("p2p-dev-") -> SocketKind.P2P_DEV
            name.matches(Regex("p2p\\d+")) -> SocketKind.GROUP_IFACE
            else -> SocketKind.STA_FALLBACK
        }
    }

    /**
     * Block briefly for an in-flight advertisement to finish. `advertiseSink` clears the flag
     * from its `finally`, so this cannot deadlock.
     */
    private fun waitForAdvertiseIdle(timeoutMs: Long = 3_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // Poll the volatile flag instead of monitor-waiting on it: Kotlin's Any exposes neither
        // Object.wait nor notifyAll, and a hung root shell must never block us indefinitely.
        while (advertiseInProgress && SystemClock.elapsedRealtime() < deadline) {
            runCatching { Thread.sleep(50L) }
        }
    }

    /**
     * Vendor builds put wpa_supplicant under /vendor/bin/hw, /vendor/bin, or /system/bin, so
     * probe for the WFD symbols instead of trusting one hard-coded path. Without them no amount
     * of injection will work.
     */
    private fun wpaSupplicantWfdSupport(): String {
        val binaries = listOf(
            "/vendor/bin/hw/wpa_supplicant",
            "/vendor/bin/wpa_supplicant",
            "/system/bin/wpa_supplicant"
        )
        for (path in binaries) {
            when (runAsRoot("grep -qa WFD_SUBELEM '$path'").exitCode) {
                0 -> return "WFD support present in $path"
                1 -> return "no WFD_SUBELEM symbol in $path — wpa_supplicant was built without Wi-Fi Display"
            }
            // 2 means the path does not exist; keep looking.
        }
        return "wpa_supplicant not found at any known path"
    }

    private class Verification(val replied: Boolean, val ok: Boolean, val detail: String)

    /**
     * Read the state back with WFD_SUBELEM_GET 0. Only a reply that actually contains the injected
     * payload counts as verified; everything else — a different payload, FAIL, UNKNOWN, or silence —
     * leaves `ok` false. Treating silence as success is what made this sink look configured while it
     * was not, so the caller must read `verified` rather than `success`.
     */
    private fun verifyAdvertisement(binaryPath: String, socketPath: String, payload: String): Verification {
        val output = runAsRootOutput("$binaryPath $socketPath \"WFD_SUBELEM_GET 0\"")
        if (output.isNullOrBlank()) return Verification(replied = false, ok = false, detail = "read-back unavailable")
        val reply = output.lineSequence()
            .firstOrNull { it.contains("WFD_SUBELEM_GET") && it.contains("->") }
            ?.substringAfter("->")?.trim().orEmpty()
        val normalized = reply.lowercase().removePrefix("0x")
        return when {
            normalized.contains(payload) -> Verification(replied = true, ok = true, detail = "read-back ok")
            normalized.length >= 12 && normalized.all { it.isDigit() || it in 'a'..'f' } ->
                Verification(replied = true, ok = false, detail = "subelem mismatch: $reply")
            else -> Verification(replied = false, ok = false, detail = "read-back unsupported ($reply)")
        }
    }

    /**
     * Static candidates first, then a live scan of the well-known control directories so sockets
     * with non-standard interface names (wlan2, vendor-renamed p2p devices on Android 15–17
     * builds) are still discovered.
     */
    private fun existingControlSockets(): List<String> {
        val socketExists = { path: String ->
            runAsRoot("test -S '$path' || test -e '$path'").success
        }
        val found = CTRL_SOCKET_PATHS.filter(socketExists).toMutableList()
        CTRL_SOCKET_DIRS.forEach { dir ->
            val listing = runAsRootOutput("ls -1 '$dir' 2>/dev/null") ?: return@forEach
            listing.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("p2p") || it.startsWith("wlan") }
                .forEach { name ->
                    val full = "$dir/$name"
                    if (full !in found && socketExists(full)) found += full
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

    private data class RootResult(val exitCode: Int, val output: String) {
        val success: Boolean get() = exitCode == 0
    }

    private fun runAsRoot(command: String): RootResult = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (output.isNotEmpty()) Timber.d("WFD root: $output")
        RootResult(exit, output)
    } catch (e: Exception) {
        Timber.d("WFD root unavailable: ${e.message}")
        RootResult(-1, e.message ?: "exception")
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
