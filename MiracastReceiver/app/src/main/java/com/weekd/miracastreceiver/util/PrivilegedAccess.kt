package com.weekd.miracastreceiver.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Privileged helpers for Android TV deployments.
 *
 * The target environment is usually Magisk rooted, so root is always preferred. Shizuku is
 * supported as a fallback for appops/device-idle commands when root is not available. All
 * privileged operations are best-effort: a failure never prevents the normal foreground service
 * path from working.
 */
object PrivilegedAccess {

    const val SHIZUKU_PERMISSION_REQUEST = 2401

    private const val MAGISK_DIR = "/data/adb/magisk"
    private const val MAGISK_SERVICE_DIR = "/data/adb/service.d"
    private const val MAGISK_BOOT_SCRIPT = "$MAGISK_SERVICE_DIR/99-miracast-receiver.sh"
    private const val STATUS_CACHE_TTL_MS = 10_000L

    private val statusLock = Any()
    @Volatile private var cachedStatus: Status? = null
    @Volatile private var cachedStatusAtMs: Long = 0L

    data class CommandResult(
        val success: Boolean,
        val output: String = "",
        val error: String = ""
    )

    data class Status(
        val rootAvailable: Boolean,
        val magiskAvailable: Boolean,
        val shizukuAlive: Boolean,
        val shizukuAuthorized: Boolean,
        val bootScriptInstalled: Boolean
    )

    data class BootstrapResult(
        val privilegedChannel: String,
        val succeeded: Int,
        val attempted: Int,
        val bootScriptInstalled: Boolean
    ) {
        val success: Boolean get() = succeeded > 0
    }

    /**
     * Status is polled frequently by the WebUI. Cache it briefly so an open dashboard does not
     * spawn several `su` processes every two seconds on the TV.
     */
    fun getStatus(context: Context): Status {
        val now = SystemClock.elapsedRealtime()
        cachedStatus?.let { cached ->
            if (now - cachedStatusAtMs < STATUS_CACHE_TTL_MS) return cached
        }

        synchronized(statusLock) {
            val secondNow = SystemClock.elapsedRealtime()
            cachedStatus?.let { cached ->
                if (secondNow - cachedStatusAtMs < STATUS_CACHE_TTL_MS) return cached
            }

            val root = isRootAvailable()
            val magisk = root && runRoot("test -d $MAGISK_DIR").success
            val shizukuAlive = isShizukuAlive()
            val shizukuAuthorized = shizukuAlive && isShizukuAuthorized()
            val bootScript = root && runRoot("test -x $MAGISK_BOOT_SCRIPT").success
            return Status(root, magisk, shizukuAlive, shizukuAuthorized, bootScript).also {
                cachedStatus = it
                cachedStatusAtMs = secondNow
            }
        }
    }

    fun invalidateStatusCache() {
        synchronized(statusLock) {
            cachedStatus = null
            cachedStatusAtMs = 0L
        }
    }

    fun isRootAvailable(): Boolean = runRoot("id").success

    fun isShizukuAlive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun isShizukuAuthorized(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestShizukuPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (!isShizukuAlive() || isShizukuAuthorized()) return
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
            .onFailure { Timber.w(it, "Unable to request Shizuku permission") }
    }

    /**
     * Apply the Android-side background allowances needed by a permanently listening TV receiver.
     * Root is preferred; Shizuku is used when it is alive and authorized.
     */
    fun applyBackgroundOptimizations(context: Context, installBootScript: Boolean): BootstrapResult {
        val pkg = context.packageName
        val root = isRootAvailable()
        val shizuku = !root && isShizukuAuthorized()
        val channel = when {
            root -> "Magisk Root"
            shizuku -> "Shizuku"
            else -> "Android"
        }

        if (!root && !shizuku) {
            invalidateStatusCache()
            return BootstrapResult(channel, 0, 0, false)
        }

        val commands = listOf(
            "cmd deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )

        var succeeded = 0
        commands.forEach { command ->
            val result = if (root) runRoot(command) else runShizuku(command)
            if (result.success) {
                succeeded++
            } else {
                Timber.w("Privileged command failed via $channel: $command; ${result.error}")
            }
        }

        val bootInstalled = if (root && installBootScript) {
            installMagiskBootScript(context)
        } else {
            root && runRoot("test -x $MAGISK_BOOT_SCRIPT").success
        }

        invalidateStatusCache()
        return BootstrapResult(channel, succeeded, commands.size, bootInstalled)
    }

    /** Install a Magisk late_start script as a second boot path in addition to BOOT_COMPLETED. */
    fun installMagiskBootScript(context: Context): Boolean {
        if (!isRootAvailable() || !runRoot("test -d $MAGISK_DIR").success) return false

        val pkg = context.packageName
        val component = "$pkg/.service.CastReceiverService"
        val script = """
            #!/system/bin/sh
            # MiracastReceiver Android TV boot fallback generated by the app.
            until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do
                sleep 2
            done
            sleep 4
            SDK="${'$'}(getprop ro.build.version.sdk)"
            if [ "${'$'}SDK" -ge 26 ]; then
                am start-foreground-service -n "$component" --ez from_boot true >/dev/null 2>&1
            else
                am startservice -n "$component" --ez from_boot true >/dev/null 2>&1
            fi
        """.trimIndent() + "\n"

        val temp = File(context.cacheDir, "miracastreceiver-service.sh")
        return try {
            temp.writeText(script)
            val source = shellQuote(temp.absolutePath)
            val target = shellQuote(MAGISK_BOOT_SCRIPT)
            val result = runRoot(
                "mkdir -p $MAGISK_SERVICE_DIR && cp $source $target && chmod 0755 $target"
            )
            if (!result.success) {
                Timber.w("Failed installing Magisk service.d script: ${result.error}")
            }
            result.success
        } catch (e: Exception) {
            Timber.w(e, "Failed preparing Magisk service.d script")
            false
        } finally {
            temp.delete()
            invalidateStatusCache()
        }
    }

    fun removeMagiskBootScript(): Boolean {
        if (!isRootAvailable()) return false
        return runRoot("rm -f ${shellQuote(MAGISK_BOOT_SCRIPT)}").success.also {
            invalidateStatusCache()
        }
    }

    private fun runRoot(command: String): CommandResult = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.waitFor()
        CommandResult(exit == 0, stdout, stderr)
    } catch (e: Exception) {
        CommandResult(false, error = e.message.orEmpty())
    }

    /**
     * Shizuku 13.x keeps the old remote-process entry point for compatibility but does not expose
     * it as a normal public Kotlin API. Reflection keeps this optional path isolated; if a future
     * Shizuku version removes it, the normal/root paths continue to work unchanged.
     */
    private fun runShizuku(command: String): CommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return CommandResult(false, error = "Shizuku 13.1.5 requires Android 7+")
        }
        if (!isShizukuAuthorized()) return CommandResult(false, error = "Shizuku not authorized")
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val remoteProcess = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                ?: return CommandResult(false, error = "Shizuku returned no process")
            val clazz = remoteProcess.javaClass
            val stdout = (clazz.getMethod("getInputStream").invoke(remoteProcess) as InputStream)
                .bufferedReader().use { it.readText() }.trim()
            val stderr = (clazz.getMethod("getErrorStream").invoke(remoteProcess) as InputStream)
                .bufferedReader().use { it.readText() }.trim()
            val exit = clazz.getMethod("waitFor").invoke(remoteProcess) as Int
            runCatching { clazz.getMethod("destroy").invoke(remoteProcess) }
            CommandResult(exit == 0, stdout, stderr)
        } catch (e: Exception) {
            Timber.w(e, "Shizuku command execution failed")
            CommandResult(false, error = e.message.orEmpty())
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
