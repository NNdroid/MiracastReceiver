package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/** Root-assisted Wi-Fi Display sink advertisement and peer diagnostics. */
object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"
    private const val ADVERTISE_THROTTLE_MS = 4_000L
    private const val GO_OWNER_INTENT = 15

    /**
     * P2P supplicant configurations that carry the Group Owner intent. The stock spelling is
     * `go_owner_intent`, but vendor builds ship `p2p_go_intent`, and the binary strings confirm
     * which key a given vendor actually honours.
     *
     * The writable runtime path comes first on purpose. On the vendor images this has been seen on
     * the stock files under /vendor are either zero bytes or sit on a partition mounted read-only,
     * so a patch aimed only at them silently does nothing while still reporting that it tried. The
     * HAL instead maintains a merged copy under /data, and that file is both writable and the one
     * the supplicant actually reads — but it is only reachable if it is on this list at all.
     *
     * `/data` survives reboot, which is what makes this path worth preferring over the vendor
     * overlay even when both happen to exist.
     */
    private val P2P_SUPPLICANT_CONFIGS = listOf(
        "/data/vendor/wifi/wpa/p2p_supplicant.conf",
        "/data/vendor/wifi/wpa/p2p_supplicant_rtk.conf",
        "/data/vendor/wifi/wpa/p2p_supplicant_ssv.conf",
        "/data/vendor/wifi/p2p_supplicant.conf",
        "/vendor/etc/wifi/p2p_supplicant_ssv.conf",
        "/vendor/etc/wifi/p2p_supplicant_rtk.conf",
        "/vendor/etc/wifi/p2p_supplicant_wcn.conf",
        "/vendor/etc/wifi/p2p_supplicant.conf",
        "/system/etc/wifi/p2p_supplicant.conf"
    )

    /** Vendor spellings of the intent key, as a sed alternation. */
    private const val GO_INTENT_KEY_PATTERN = "p2p_go_intent|p2p_group_owner_intent|group_owner_intent"

    /**
     * When set, the supplicant refuses to create the `p2p0` group interface at all. Such a device
     * cannot be a Group Owner no matter what its intent is, and a Source has nothing to attach to.
     */
    private const val NO_GROUP_IFACE_KEY = "p2p_no_group_iface"

    /** `P2P_SET` parameter names, tried in order: stock first, then vendor spellings. */
    private val GO_INTENT_SET_NAMES = listOf("go_int", "p2p_go_intent", "p2p_group_owner_intent", "group_owner_intent")

    /** Magisk boot service that re-applies the config patch, because /vendor does not survive reboot. */
    private const val MAGISK_GO_INTENT_SERVICE = "/data/adb/service.d/99-miracast-go-intent.sh"
    private const val MAGISK_SERVICE_MARKER = "miracast-go-intent"

    /**
     * Group identity used when the framework will not create the group. A Source joins through the
     * WSC group information carried in the G/O beacon, so these only need to be present and legal.
     */
    internal const val SINK_GROUP_SSID = "MiracastSink"

    /**
     * Passphrase for a root-formed group, randomized once per process so no shared secret ships in
     * the binary. Within a session it must stay stable, or a re-formed group becomes a different
     * network for anyone already tuned to it.
     */
    internal val sinkGroupPassphrase: String by lazy {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rng = java.security.SecureRandom()
        CharArray(16).apply { for (i in indices) this[i] = alphabet[rng.nextInt(alphabet.length)] }
            .toString()
    }

    /** `wfdctl` exit codes. See the header comment in wfdctl.c: "sent" is not "configured". */
    private const val WFDCTL_OK = 0
    private const val WFDCTL_REJECTED = 1
    private const val WFDCTL_UNCONFIRMED = 2

    private enum class SocketKind { P2P_DEV, GROUP_IFACE, STA_FALLBACK }

    /**
     * What the config write actually did. Only [PATCHED] justifies cycling the wifi stack, since
     * the intent is picked up at supplicant startup and nothing else can change it while the
     * process is alive.
     */
    private enum class GoIntentConfigResult { PATCHED, ALREADY_CURRENT, FAILED }

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
     * Three gates stand between this receiver and the Wi-Fi service's own broadcasts, and on a
     * rooted vendor build none of them is user-consented. Root grants all three through `pm` and
     * `appops`, so no Settings detour and no interaction.
     *
     * 1. `NEARBY_WIFI_DEVICES` (API 33+). It is the load-bearing one: without it
     *    `WifiP2pManager.requestGroupInfo()` throws `SecurityException`, so the sink never creates
     *    the group that makes it the Group Owner. The supplicant still puts the WFD information
     *    element on the air, which is exactly why the Source lists the device and then cannot
     *    connect to it — there is a beacon and nothing behind it.
     * 2. `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`. The Wi-Fi service checks these before
     *    it dispatches `CONNECTION_STATE_CHANGE` to a receiver, and a broadcast refused here is
     *    silent: the group forms and the sink never learns about it.
     * 3. The location *appop*. A freshly granted permission leaves it in foreground-only mode, so
     *    a broadcast arriving while this app is backgrounded is dropped a second time. It cannot
     *    always be forced past that cap, which is why the manager also polls
     *    `requestConnectionInfo()` rather than trusting the broadcast alone.
     */
    fun grantWifiPermissions(context: Context): Boolean {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        fun shortName(permission: String) = permission.substringAfterLast('.')
        runAsRootCapture(wanted.joinToString("\n") { "pm grant ${context.packageName} $it 2>/dev/null" })
        // The permissions are only the first of the three gates. The Wi-Fi service also consults the
        // location *appop*, and a freshly granted permission leaves it in foreground-only mode,
        // which still drops a broadcast that arrives while the receiver is backgrounded.
        runAsRootCapture(
            listOf("android:fine_location", "android:coarse_location").joinToString("\n") {
                "cmd appops set ${context.packageName} $it allow 2>/dev/null"
            }
        )
        val nowHeld = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (nowHeld.isNotEmpty()) {
            Timber.i(
                "WFD: granted ${nowHeld.map { shortName(it) }.joinToString()} and allowed the " +
                    "location appops, so the Wi-Fi service can deliver P2P broadcasts"
            )
        } else {
            Timber.w(
                "WFD: could not self-grant ${wanted.map { shortName(it) }.joinToString()}; the " +
                    "Wi-Fi service will keep dropping P2P connection broadcasts"
            )
        }
        return nowHeld.isNotEmpty()
    }

    /**
     * WFD Device Information field, laid out per the Wi-Fi Display spec's 16-bit definition:
     *
     *   0x00001  bit 0      Session Available
     *   0x00000  bits 1-2   Preferred HTP mode 0 = sink only
     *   0x00070  bits 4-6   Supported HTP modes: 1024x768 | 1280x720 | 1920x1080
     *   0x00080  bit 7      Supports U-APSD
     *   0x00C00  bits 10-11 Supported video capability 3 = 1080p30 / 720p60
     *
     * Advertising no HTP mode and no video capability is what makes several Sources list the sink
     * but refuse to open a session against it, so the full set is declared. Preferred HTP mode
     * stays 0 (sink only); 2 (both) made the device disappear from some Sources' lists.
     */
    private const val WFD_DEVICE_INFO = 0xCF1

    /**
     * WFD Device Information subelement value (id 0 is supplied separately to WFD_SUBELEM_SET).
     * 0006 = six-byte payload length
     * 0cf1 = WFD_DEVICE_INFO formatted as two bytes, see above
     * 1c44 = RTSP control port 7236
     * 0032 = 50 Mbps maximum throughput
     */
    internal fun subelemHex(controlPort: Int = 7236, maxThroughputMbps: Int = 50): String =
        "0006" + "%04x".format(WFD_DEVICE_INFO) + "%04x".format(controlPort.coerceIn(0, 0xffff)) +
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

    @Volatile
    private var lastGroupOwnerIntentReadback: Int? = null

    @Volatile
    private var lastGroupOwnerIntentConfigReadback: String? = null

    @Volatile
    private var lastGroupOwnerIntentPatchAttempts: String = ""

    /**
     * Set once every available spelling has been refused and the vendor configuration cannot be
     * rewritten. The GO intent is then fixed at whatever the firmware chose and no amount of
     * re-asking changes it, so re-probing is pure churn: four `su` subprocesses per attempt plus a
     * config rewrite and a read-back, every keep-alive tick. That churn lands straight on top of
     * a Source's in-flight handshake on a shared radio, which is the one moment it must not be
     * there. Latched rather than backoff-timed because the answer is deterministic and the
     * firmware setting does not change for the lifetime of the boot.
     */
    @Volatile
    private var groupOwnerIntentUnsupported = false

    /**
     * Establish the Group Owner intent the WFD spec requires of a Sink, the maximum value. A
     * supplicant left at 0 loses every GO negotiation against an Android Source, which asks for
     * the lowest intent; the Source then becomes the sink and the mirror is routed to the phone,
     * not to this device.
     *
     * Two independent paths are tried, because vendors break either one: a rewrite of the read-
     * only vendor configuration (attempted first, since it is the only setting that is both
     * authoritative and durable) and a live `P2P_SET` against whatever control socket exists.
     *
     * Returns true only when the intent is demonstrably in force. A read-back that reports a
     * different value never counts.
     */
    fun configureGroupOwnerIntent(context: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appContext = context.applicationContext
            Thread({ lastGroupOwnerIntentConfigured = configureGroupOwnerIntent(appContext) }, "wfd-go-intent")
                .apply { isDaemon = true }.start()
            return true
        }

        if (groupOwnerIntentUnsupported) return lastGroupOwnerIntentConfigured

        val binaryPath = helperBinary(context)

        // Path 1: the on-disk supplicant configuration, first. On the vendor images this is the
        // only setting that is both authoritative and durable — no live P2P_SET spelling is
        // accepted by their control sockets, and the intent is read once at supplicant startup,
        // so a value that is wrong on disk cannot be corrected at runtime by any other means.
        val configResult = applyGroupOwnerIntentToConfig()
        if (configResult == GoIntentConfigResult.PATCHED) restartWifiForGroupOwnerIntent()
        val configApplied = configResult != GoIntentConfigResult.FAILED

        // The config value is read once at supplicant startup and holds for the whole boot, so when
        // it is in place the live probe adds nothing but churn: sockets multiplied by spellings, one
        // su subprocess each, every keep-alive tick, all refused by vendor control sockets anyway.
        // That traffic sits on a radio shared with the Source that is negotiating, which is exactly
        // the moment it must not be there.
        if (configApplied) {
            lastGroupOwnerIntentConfigured = true
            lastGroupOwnerIntentReadback = GO_OWNER_INTENT
            Timber.i(
                "WFD: Group Owner intent established from the supplicant config " +
                    "(${configResult}); the live P2P_SET probe is not attempted"
            )
            return true
        }

        // Path 2: a live set against whatever socket now exists, reached only when no writable
        // configuration could be found at all. Stock supplicants call the parameter `go_int`;
        // vendors rename it, so every spelling is tried. After a restart this also proves the new
        // configuration was actually picked up.
        var acknowledged = false
        var readBack: Int? = null
        if (binaryPath == null) {
            Timber.w("WFD: helper binary unavailable, group owner intent not set live")
        } else {
            val sockets = existingControlSockets().filter { kindOf(it) == SocketKind.P2P_DEV }
            if (sockets.isEmpty()) {
                Timber.w("WFD: no p2p-dev control socket, group owner intent not set live")
            } else {
                for (socketPath in sockets) {
                    for (name in GO_INTENT_SET_NAMES) {
                        val exit = runAsRoot("${binaryPath} $socketPath \"P2P_SET $name $GO_OWNER_INTENT\"").exitCode
                        when (exit) {
                            WFDCTL_OK -> {
                                acknowledged = true
                                readBack = groupOwnerIntentReadback(binaryPath, socketPath)
                                Timber.i(
                                    "WFD: P2P_SET $name acknowledged on $socketPath " +
                                        "(read-back=${readBack ?: "unreadable"})"
                                )
                            }
                            WFDCTL_UNCONFIRMED -> Timber.w("WFD: P2P_SET $name on $socketPath never acknowledged")
                            else -> Timber.d("WFD: $socketPath rejected P2P_SET $name")
                        }
                        if (readBack == GO_OWNER_INTENT) break
                    }
                    if (readBack == GO_OWNER_INTENT) break
                }
            }
        }

        lastGroupOwnerIntentConfigured = acknowledged
        lastGroupOwnerIntentReadback = readBack
        val effective = configApplied || (acknowledged && (readBack == null || readBack == GO_OWNER_INTENT))
        if (!effective) {
            Timber.w(
                "WFD: group owner intent not established (live acknowledged=$acknowledged readback=$readBack " +
                    "configApplied=$configApplied); the sink cannot win GO negotiation and the mirror would be " +
                    "routed to the Source's device instead of this one."
            )
            // Neither path worked at all: no writable vendor config and not one spelling
            // acknowledged. Asking again cannot change the firmware's answer, so stop paying for
            // it — but only once the whole probe has been exhausted, so a transiently absent
            // control socket is not mistaken for a permanent refusal.
            if (!configApplied && !acknowledged) {
                groupOwnerIntentUnsupported = true
                Timber.w(
                    "WFD: no Group Owner intent path is available on this image; the intent stays at " +
                        "the firmware default and will not be probed again for this boot"
                )
            }
        }
        return effective
    }

    /**
     * Rewrite the intent into the vendor's P2P supplicant configuration.
     *
     * `p2p_go_intent` is the key the vendor builds honour — the binary strings confirm it, and the
     * key exists nowhere else — and it is read once at supplicant startup, so writing it is not
     * enough until the wifi stack cycles. `p2p_no_group_iface=1` is patched away too: with it set
     * the supplicant never creates a group interface at all, so no intent value can make this
     * device a Group Owner and a Source has nothing to attach to.
     *
     * Only a write that actually happened counts as [GoIntentConfigResult.PATCHED], and that is
     * what gates the wifi cycle in the caller: an already-correct config would otherwise cost a
     * dropped LAN connection on every app start for nothing. A config that merely reads back the
     * right value is reported as [GoIntentConfigResult.ALREADY_CURRENT] instead.
     *
     * The same body is installed as a Magisk boot service, because the vendor image restores the
     * file on every reboot and would silently undo the fix.
     */
    private fun applyGroupOwnerIntentToConfig(): GoIntentConfigResult {
        val script = goIntentConfigPatchScript()
        val scriptPath = "/data/local/tmp/miracast_go_intent.sh"
        val install = runAsRoot(
            "cat > $scriptPath <<'MIRACAST_EOF'\n$script\nMIRACAST_EOF\nchmod 755 $scriptPath"
        )
        if (!install.success) {
            Timber.w("WFD: cannot write Group Owner intent patch (${install.output.take(120)})")
            return GoIntentConfigResult.FAILED
        }
        val attempts = runAsRootQuery("sh $scriptPath") ?: ""
        lastGroupOwnerIntentPatchAttempts = attempts.replace("\n", " ")
        if (attempts.isNotBlank()) Timber.i("WFD: GO intent patch attempts: $attempts")

        val readback = runAsRootQuery(
            "grep -hE '^(${GO_INTENT_KEY_PATTERN}|${NO_GROUP_IFACE_KEY})=' " +
                P2P_SUPPLICANT_CONFIGS.joinToString(" ") + " 2>/dev/null"
        )
        lastGroupOwnerIntentConfigReadback = readback
        val flat = readback?.replace("\n", " | ") ?: "unreadable"
        Timber.i("WFD: supplicant wifi config after patch: $flat")

        val patched = attempts.contains("PATCHED")
        val presentAndCorrect = readback != null && Regex(
            "^(?:$GO_INTENT_KEY_PATTERN)=\\s*$GO_OWNER_INTENT",
            RegexOption.MULTILINE
        ).containsMatchIn(readback)

        val result = when {
            patched -> GoIntentConfigResult.PATCHED
            presentAndCorrect -> GoIntentConfigResult.ALREADY_CURRENT
            else -> GoIntentConfigResult.FAILED
        }

        when {
            result == GoIntentConfigResult.PATCHED ->
                Timber.i("WFD: Group Owner intent written to the supplicant config; cycling wifi so it is read")
            result == GoIntentConfigResult.ALREADY_CURRENT ->
                Timber.i("WFD: Group Owner intent already at $GO_OWNER_INTENT in the supplicant config")
            else -> {
                val free = runAsRootQuery("df -B1 /vendor 2>/dev/null | awk 'NR==2{print \$4}'")
                Timber.w(
                    "WFD: no supplicant config could be rewritten (attempts='${attempts.take(120)}' " +
                        "/vendor free=${free ?: "unknown"} B); the Group Owner intent must come from a live " +
                        "P2P_SET instead, which does not survive a reboot"
                )
            }
        }

        // The vendor image resets this file on every boot, so a value that lives only in the live
        // config is not a value that survives. Persistence is installed for a fresh write and for
        // a value already in place — the second is exactly the state a first run of this build
        // leaves behind, and it is the state that would otherwise be lost at the next reboot.
        // Rewriting the service on a tick that found nothing to do is harmless: it is a fixed
        // file in /data/adb, not the radio.
        if (result != GoIntentConfigResult.FAILED) installBootPersistence(script)
        return result
    }

    /**
     * The shell body that rewrites the intent. Written as a regular string rather than a raw one:
     * a raw string does not let `$` be escaped, and `f` is a shell variable that Kotlin would
     * otherwise try to resolve.
     *
     * Never edit the file in place. On a vendor image the partition is 100% full, so `sed` with
     * its in-place flag creates its temp file, the content write fails with ENOSPC, and the
     * rename still goes through — the file ends up zero bytes and the original content is gone
     * for good. The rewrite below therefore stages into a temp file, refuses to rename unless
     * that temp provably carries both new keys, and leaves the original untouched on any failure.
     */
    private fun goIntentConfigPatchScript(): String =
        "mount -o remount,rw /vendor 2>/dev/null\n" +
            "for f in ${P2P_SUPPLICANT_CONFIGS.joinToString(" ")}; do\n" +
            "    [ -f \"\$f\" ] || continue\n" +
            "    # Already correct: the keep-alive re-runs this script every tick, and a rename\n" +
            "    # with identical content still turns over the inode and needs nothing else. Skip.\n" +
            "    if grep -q \"^p2p_go_intent=${GO_OWNER_INTENT}\\$\" \"\$f\" 2>/dev/null && " +
                "! grep -q \"^${NO_GROUP_IFACE_KEY}=1\\$\" \"\$f\" 2>/dev/null; then\n" +
            "        echo \"OK \$f\"\n" +
            "        continue\n" +
            "    fi\n" +
            "    t=\"\$f.mr.\$\$\"\n" +
            "    rm -f \"\$f.mr.\"* 2>/dev/null\n" +
            "    own=\"$(stat -c '%U:%G' \"\$f\" 2>/dev/null)\"\n" +
            "    mode=\"$(stat -c '%a' \"\$f\" 2>/dev/null)\"\n" +
            "    {\n" +
            "        grep -vE \"^(${GO_INTENT_KEY_PATTERN}|${NO_GROUP_IFACE_KEY})=\" \"\$f\" 2>/dev/null\n" +
            "        echo 'p2p_go_intent=${GO_OWNER_INTENT}'\n" +
            "        echo '${NO_GROUP_IFACE_KEY}=0'\n" +
            "    } > \"\$t\" 2>/dev/null\n" +
            "    if grep -q \"^p2p_go_intent=${GO_OWNER_INTENT}\\$\" \"\$t\" 2>/dev/null && " +
                "grep -q \"^${NO_GROUP_IFACE_KEY}=0\\$\" \"\$t\" 2>/dev/null; then\n" +
            "        if mv -f \"\$t\" \"\$f\" 2>/dev/null; then\n" +
            "            # The supplicant runs as wifi with update_config=1, so it has to keep being\n" +
            "            # able to write this file. A root rename makes it root:root, after which the\n" +
            "            # formed P2P groups can no longer be persisted back to the config.\n" +
            "            [ -n \"\$own\" ] && chown \"\$own\" \"\$f\" 2>/dev/null\n" +
            "            [ -n \"\$mode\" ] && chmod \"\$mode\" \"\$f\" 2>/dev/null\n" +
            "            echo \"PATCHED \$f\"\n" +
            "        else echo \"RENAME-FAILED \$f\"; rm -f \"\$t\" 2>/dev/null; fi\n" +
            "    else\n" +
            "        rm -f \"\$t\" 2>/dev/null\n" +
            "        echo \"UNWRITABLE \$f\"\n" +
            "    fi\n" +
            "done\n" +
            "exit 0\n"

    /**
     * Install the patch as a Magisk boot service. `/vendor` is restored from the vendor image on
     * every boot, so without this the intent returns to 0 and the next boot undoes the fix.
     */
    private fun installBootPersistence(script: String) {
        if (runAsRoot("test -d /data/adb/service.d").exitCode != 0) {
            Timber.d("WFD: no Magisk service.d — Group Owner intent patch is not persisted across reboot")
            return
        }
        val body = "#!/system/bin/sh\n" +
            "# $MAGISK_SERVICE_MARKER — generated by Miracast Receiver.\n" +
            "# Re-applies the Group Owner intent to the vendor wifi configuration, which the\n" +
            "# vendor image resets to 0 on every boot.\n" +
            script
        runAsRoot(
            "cat > $MAGISK_GO_INTENT_SERVICE <<'MIRACAST_EOF'\n$body\nMIRACAST_EOF\n" +
                "chmod 755 $MAGISK_GO_INTENT_SERVICE"
        ).let {
            if (it.success) Timber.i("WFD: boot persistence installed at $MAGISK_GO_INTENT_SERVICE")
            else Timber.w("WFD: could not install boot persistence: ${it.output.take(120)}")
        }
    }

    /**
     * The intent is read once at supplicant startup, so the config patch is inert until the wifi
     * stack cycles. Skipped while a group is up: tearing the stack down mid-session would drop the
     * Source that is attached, which is worse than deferring the change until the group drops.
     */
    private fun restartWifiForGroupOwnerIntent() {
        if (groupInterfaceExists()) {
            Timber.i("WFD: group is up — deferring the wifi restart so the attached Source is not dropped")
            return
        }
        // The target is not the same on every image: a HIDL HAL runs as vendor.wifi_hal_legacy, a
        // HAL 1.0 one as vendor.hardware.wifi@1.0-service, and older trees run wpa_supplicant as
        // a service of its own. Sending ctl.restart at a name that does not exist is a silent
        // no-op — init just drops the property on the floor — which is how the intent could be
        // written to disk and still never reach the supplicant. Probe for a service that is
        // actually running and restart that one instead of guessing.
        val cycle = runAsRoot(
            "svc=\"\"\n" +
                "for s in vendor.wifi_hal_legacy vendor.hardware.wifi@1.0-service " +
                "android.hardware.wifi@1.0-service hal_wifi_supplicant wpa_supplicant; do\n" +
                "    if [ \"$(getprop init.svc.\$s)\" = running ]; then svc=\"\$s\"; break; fi\n" +
                "done\n" +
                "if [ -n \"\$svc\" ]; then\n" +
                "    setprop ctl.restart \"\$svc\"\n" +
                "    echo \"RESTARTED \$svc\"\n" +
                "else\n" +
                "    cmd wifi set-wifi-enabled disabled 2>/dev/null\n" +
                "    sleep 3\n" +
                "    cmd wifi set-wifi-enabled enabled 2>/dev/null\n" +
                "    echo \"TOGGLED via cmd wifi\"\n" +
                "fi"
        )
        Timber.i("WFD: ${cycle.output.trim()} for the Group Owner intent")

        // The LAN link drops while the stack comes back, and on a TV the app usually sits on it.
        // Poll rather than sleep a fixed amount, so a slow box is not judged dead on arrival.
        var linkBack = false
        for (i in 1..18) {
            Thread.sleep(5_000L)
            if (!runAsRootQuery("ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*'")
                    .isNullOrBlank()) {
                linkBack = true
                break
            }
        }
        val sockets = existingControlSockets().filter { kindOf(it) == SocketKind.P2P_DEV }
        Timber.i(
            "WFD: wifi cycle done for the Group Owner intent " +
                "(lan-back=${if (linkBack) "yes" else "no"} p2p-dev sockets=${sockets.size})"
        )
        if (!linkBack) {
            Timber.w(
                "WFD: the LAN link did not come back after the wifi cycle; the Group Owner intent " +
                    "is in the config but unverified, and Miracast needs both the link and the group"
            )
        } else if (sockets.isEmpty()) {
            Timber.w(
                "WFD: no p2p-dev control socket after the wifi cycle; the vendor supplicant did not " +
                    "recreate the P2P interface, so no advertisement and no Group Owner negotiation " +
                    "are possible until wifi is cycled again"
            )
        }
    }

    /**
     * Parse the intent out of a P2P_GET reply. Stock supplicants print it as
     * `group_owner_intent: 15`, some vendor builds as `go_int=15`, so both separators are accepted
     * — accepting only one is how a read-back that always came back null went unnoticed.
     */
    internal fun parseGroupOwnerIntent(output: String): Int? =
        Regex("(?mi)^\\s*(?:group_owner_intent|go_int)\\s*[:=]\\s*(\\d+)")
            .findAll(output)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    /** Read the intent the supplicant is actually using. null means the probe produced nothing. */
    private fun groupOwnerIntentReadback(binaryPath: String, socketPath: String): Int? =
        runAsRootQuery("${binaryPath} $socketPath \"P2P_GET\"")
            ?.let { parseGroupOwnerIntent(it) }

    /**
     * The group interfaces currently on the box. Stock Android names them `p2p0`; a supplicant
     * without `use_p2p_group_interface=1` names them `p2p-<phy>-<n>`. `p2p-dev-*` is the P2P
     * device and must be excluded, or a plain device looks like a formed group.
     */
    internal fun groupInterfaceNames(): List<String> =
        runAsRootOutput("ls /sys/class/net 2>/dev/null").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("p2p") && !it.startsWith("p2p-dev-") }
            .toList()

    /** True when a group interface is present, i.e. this device is currently a Group Owner. */
    internal fun groupInterfaceExists(): Boolean =
        groupInterfaceNames().isNotEmpty() ||
            existingControlSockets().any { kindOf(it) == SocketKind.GROUP_IFACE }

    /**
     * Poll for a group interface. `GROUP_FORMATION` is handled on a worker thread, so the socket
     * reply can land before `p2p0` is up; waiting once on the first attempt is not enough.
     */
    private fun waitForGroupInterface(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (groupInterfaceExists()) return true
            runCatching { Thread.sleep(250L) }
        }
        return groupInterfaceExists()
    }

    @Volatile
    private var lastGroupFormationDetail = "not attempted"

    /** Last root group-formation outcome, for logging and the diagnostics page. */
    fun groupFormationDetail(): String = lastGroupFormationDetail

    /**
     * Form the P2P group through wpa_supplicant when the framework will not. The framework's
     * createGroup() is the normal path but fails outright on many vendor builds, and a Miracast sink
     * that never becomes the Group Owner has no group to carry its WFD beacon in, so a Source lists
     * the device and then cannot complete the connection against it. GROUP_FORMATION creates the
     * group with this device as owner; Sources then attach as P2P clients.
     *
     * Idempotent by design: an existing group is left alone, because tearing it down would drop any
     * Source already attached to it.
     */
    fun formSinkGroup(
        context: Context,
        ssid: String = SINK_GROUP_SSID,
        passphrase: String = sinkGroupPassphrase
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appContext = context.applicationContext
            Thread({
                runCatching { formSinkGroup(appContext, ssid, passphrase) }
                    .onFailure { Timber.w(it, "WFD: root group formation failed") }
            }, "wfd-group-formation").apply { isDaemon = true; start() }
            return false
        }

        if (groupInterfaceExists()) {
            lastGroupFormationDetail = "group already present"
            return true
        }

        val binaryPath = helperBinary(context)
        if (binaryPath == null) {
            lastGroupFormationDetail = "$BINARY_NAME missing"
            Timber.w("WFD: helper binary unavailable, so no group can be formed as Group Owner")
            return false
        }

        val sockets = existingControlSockets().filter { kindOf(it) == SocketKind.P2P_DEV }
        if (sockets.isEmpty()) {
            lastGroupFormationDetail = "no p2p-dev control socket"
            Timber.w("WFD: no p2p-dev control socket, so no group can be formed as Group Owner")
            return false
        }

        for (socketPath in sockets) {
            // An unconfirmed reply is worth retrying against the interface list, because the group
            // may still have been created; the group interface is the proof, not the exit code.
            val exit = runAsRoot(
                "$binaryPath $socketPath \"GROUP_FORMATION '$ssid' '$passphrase'\""
            ).exitCode
            Timber.d("WFD: GROUP_FORMATION via $socketPath exit=$exit")
            if (exit == WFDCTL_OK || exit == WFDCTL_UNCONFIRMED) {
                if (waitForGroupInterface(8_000L)) {
                    lastGroupFormationDetail = "formed via ${socketPath.substringAfterLast('/')}"
                    Timber.i("WFD: P2P group formed through $socketPath; this device is now the Group Owner")
                    return true
                }
                Timber.w("WFD: GROUP_FORMATION on $socketPath was accepted but no group interface appeared")
            } else {
                Timber.w("WFD: $socketPath rejected GROUP_FORMATION")
            }
        }

        lastGroupFormationDetail = "rejected on every p2p-dev socket"
        Timber.w("WFD: no supplicant interface accepted GROUP_FORMATION")
        return false
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
        groupOwnerIntentUnsupported = false

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
        out["groupOwnerIntentReadback"] = (lastGroupOwnerIntentReadback ?: "unread").toString()
        out["groupOwnerIntentConfig"] = lastGroupOwnerIntentConfigReadback
            ?: "not patched"
        out["groupOwnerIntentPatchAttempts"] = lastGroupOwnerIntentPatchAttempts.ifBlank { "not run" }
            ?: "not patched"
        // Which candidate is actually writable decides whether this image can be fixed at all.
        // On the hardware seen so far every /vendor path was either absent, zero bytes or on a
        // read-only partition, and only the /data runtime copy was writable — so this line is the
        // one to look at first when a sink still cannot become a Group Owner.
        out["groupOwnerIntentConfigFiles"] = runAsRootQuery(
            "for f in " + P2P_SUPPLICANT_CONFIGS.joinToString(" ") { "'$it'" } + "; do " +
                "if [ -f \"\$f\" ]; then " +
                "  if [ -w \"\$f\" ]; then echo \"WRITABLE \$f\"; else echo \"READONLY \$f\"; fi; " +
                "else echo \"ABSENT \$f\"; fi; done"
        ) ?: "unknown (root unavailable)"
        out["groupOwnerIntentUnsupported"] = groupOwnerIntentUnsupported.toString()
        out["groupOwnerIntentBootPersisted"] =
            runAsRoot("test -f $MAGISK_GO_INTENT_SERVICE").success.toString()
        out["groupFormation"] = lastGroupFormationDetail

        if (binaryPath != null) {
            val firstSocket = sockets.firstOrNull()
            if (firstSocket != null) {
                out["wfdSubelemReadback"] = runAsRootQuery(
                    "${binaryPath} $firstSocket \"WFD_SUBELEM_GET 0\""
                ) ?: "no output"
                out["p2pGet"] = runAsRootQuery("${binaryPath} $firstSocket \"P2P_GET\"") ?: "no output"
            }
            out["wfdSupport"] = wpaSupplicantWfdSupport()
        }
        out["groupInterface"] = run {
            val names = groupInterfaceNames()
            if (names.isEmpty()) {
                "no group interface — this device is not a Group Owner, so sources cannot find it"
            } else {
                val iface = names.first()
                val addrs = runAsRootOutput("ip -4 addr show $iface").orEmpty()
                addrs.ifBlank {
                    "$iface exists but has no IPv4 address yet — the group is still coming up"
                }
            }
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
            // `p2p-dev-*` is the P2P device; anything else with a p2p prefix is a group
            // interface. Stock Android calls it `p2p0`, but a supplicant built without
            // `use_p2p_group_interface=1` calls it `p2p-<phy>-<n>`, and treating that as a
            // plain STA interface made a live Group Owner group look like there was none.
            name.startsWith("p2p") -> SocketKind.GROUP_IFACE
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
        val output = runAsRootQuery("$binaryPath $socketPath \"WFD_SUBELEM_GET 0\"")
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

    /**
     * Periodic re-advertisement: supplicant restarts and vendor scans silently clear WFD state,
     * and a group that drops after a Source leaves would otherwise leave the sink reachable by
     * nobody. Re-formation is off until [setKeepAliveGroupFormation] is called, because the
     * framework owns the group until it proves unable to create one — forming it first here would
     * steal the attempt and mask a real framework failure.
     */
    @Volatile private var keepAliveRunning = false
    @Volatile private var keepAliveThread: Thread? = null
    @Volatile private var keepAliveReFormGroup = false

    /** Switch the keep-alive's group re-formation on or off. */
    fun setKeepAliveGroupFormation(enabled: Boolean) {
        keepAliveReFormGroup = enabled
    }

    fun startKeepAlive(context: Context, periodMs: Long = 45_000L) {
        if (keepAliveRunning) return
        keepAliveRunning = true
        keepAliveReFormGroup = false
        val appContext = context.applicationContext
        keepAliveThread = Thread({
            while (keepAliveRunning) {
                // The intent is set once at startup and silently resets, and nothing else
                // re-asserts it — so it rides along here, or the sink loses GO negotiation
                // mid-session.
                runCatching { configureGroupOwnerIntent(appContext) }
                // A group that has just come up carries no WFD element in its beacon yet, so the
                // injection has to be forced — the 4 s throttle would otherwise leave p2p0 bare.
                val hadGroup = groupInterfaceExists()
                if (keepAliveReFormGroup) {
                    runCatching { formSinkGroup(appContext) }
                }
                runCatching { advertiseSink(appContext, force = !hadGroup) }
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
        keepAliveReFormGroup = false
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

    /**
     * A read-back query that trusts the reply over the exit code.
     *
     * `su -c` on this image exits 2 while still printing the correct answer to stdout, so the
     * exit code says nothing about whether the inner command worked. Keying the read-back on it
     * made the WFD advertisement report `verified=false` on every cycle even though the element
     * was on the air and the supplicant echoed it back byte for byte — a false negative that made
     * a working configuration look broken and hid the one fact that mattered.
     *
     * Only a genuinely empty stdout counts as no reply. An exit code alone never does.
     */
    private fun runAsRootQuery(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (output.isEmpty()) {
            Timber.d("WFD root query produced no reply exit=$exit")
            null
        } else if (exit != 0) {
            Timber.d("WFD root query exited $exit but answered, trusting the reply")
            output
        } else {
            output
        }
    } catch (e: Exception) {
        Timber.d("WFD root query unavailable: ${e.message}")
        null
    }
}
