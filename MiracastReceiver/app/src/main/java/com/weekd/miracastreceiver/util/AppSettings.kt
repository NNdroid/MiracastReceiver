package com.weekd.miracastreceiver.util

import android.content.Context
import com.weekd.miracastreceiver.utils.NetworkUtils
import java.security.SecureRandom
import java.util.UUID

/** Application settings shared by the TV UI, WebUI and the always-on receiver service. */
object AppSettings {

    private const val PREFS_NAME = "miracast_settings"

    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_CONNECTION_CODE = "connection_code"
    private const val KEY_DEVICE_UUID = "device_uuid"
    private const val KEY_DEVICE_NAME_OVERRIDE = "device_name_override"

    private const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
    private const val KEY_DLNA_ENABLED = "dlna_enabled"
    private const val KEY_MIRACAST_ENABLED = "miracast_enabled"
    private const val KEY_CUSTOM_MDNS_ENABLED = "custom_mdns_enabled"
    private const val KEY_AIRPLAY_AUDIO_ENABLED = "airplay_audio_enabled"
    private const val KEY_AUTO_LAUNCH_PLAYER = "auto_launch_player"
    private const val KEY_MIRROR_MAX_HEIGHT = "mirror_max_height"

    private const val KEY_UPNP_PORT = "upnp_port"
    private const val KEY_WEB_UI_ENABLED = "web_ui_enabled"
    private const val KEY_WEB_UI_PORT = "web_ui_port"
    private const val KEY_WEB_UI_LAST_BOUND_PORT = "web_ui_last_bound_port"
    private const val KEY_WEB_UI_AUTH_REQUIRED = "web_ui_auth_required"
    private const val KEY_WEB_UI_TOKEN = "web_ui_token"

    const val DEFAULT_UPNP_PORT = 8080
    const val DEFAULT_WEB_UI_PORT = 18090
    const val DEFAULT_MIRROR_MAX_HEIGHT = 0 // 0 = auto / display maximum

    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun isAirPlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AIRPLAY_ENABLED, true)

    fun setAirPlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AIRPLAY_ENABLED, enabled).apply()
    }

    fun isDlnaEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DLNA_ENABLED, true)

    fun setDlnaEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DLNA_ENABLED, enabled).apply()
    }

    fun isMiracastEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MIRACAST_ENABLED, true)

    fun setMiracastEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIRACAST_ENABLED, enabled).apply()
    }

    fun isCustomMdnsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CUSTOM_MDNS_ENABLED, true)

    fun setCustomMdnsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_MDNS_ENABLED, enabled).apply()
    }

    fun isAirPlayAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AIRPLAY_AUDIO_ENABLED, true)

    fun setAirPlayAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AIRPLAY_AUDIO_ENABLED, enabled).apply()
    }

    fun isAutoLaunchPlayer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_LAUNCH_PLAYER, true)

    fun setAutoLaunchPlayer(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LAUNCH_PLAYER, enabled).apply()
    }

    fun getMirrorMaxHeight(context: Context): Int =
        prefs(context).getInt(KEY_MIRROR_MAX_HEIGHT, DEFAULT_MIRROR_MAX_HEIGHT)

    fun setMirrorMaxHeight(context: Context, height: Int) {
        val normalized = when (height) {
            720, 1080, 1440, 2160 -> height
            else -> DEFAULT_MIRROR_MAX_HEIGHT
        }
        prefs(context).edit().putInt(KEY_MIRROR_MAX_HEIGHT, normalized).apply()
    }

    fun getUpnpPort(context: Context): Int =
        sanitizePort(prefs(context).getInt(KEY_UPNP_PORT, DEFAULT_UPNP_PORT), DEFAULT_UPNP_PORT)

    fun setUpnpPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_UPNP_PORT, sanitizePort(port, DEFAULT_UPNP_PORT)).apply()
    }

    fun isWebUiEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_UI_ENABLED, true)

    fun setWebUiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_UI_ENABLED, enabled).apply()
    }

    /** Preferred WebUI port. The runtime may bind a fallback when this port is occupied. */
    fun getWebUiPort(context: Context): Int =
        sanitizePort(prefs(context).getInt(KEY_WEB_UI_PORT, DEFAULT_WEB_UI_PORT), DEFAULT_WEB_UI_PORT)

    fun setWebUiPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_WEB_UI_PORT, sanitizePort(port, DEFAULT_WEB_UI_PORT)).apply()
    }

    /** Last port the WebUI successfully bound. Used to keep an automatically selected fallback stable. */
    fun getWebUiLastBoundPort(context: Context): Int? {
        val port = prefs(context).getInt(KEY_WEB_UI_LAST_BOUND_PORT, 0)
        return port.takeIf { it in 1024..65535 }
    }

    fun setWebUiLastBoundPort(context: Context, port: Int) {
        if (port in 1024..65535) {
            prefs(context).edit().putInt(KEY_WEB_UI_LAST_BOUND_PORT, port).apply()
        }
    }

    fun isWebUiAuthRequired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_UI_AUTH_REQUIRED, true)

    fun setWebUiAuthRequired(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_UI_AUTH_REQUIRED, required).apply()
    }

    fun getDeviceNameOverride(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setDeviceNameOverride(context: Context, name: String?) {
        val normalized = name?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        prefs(context).edit().apply {
            if (normalized == null) remove(KEY_DEVICE_NAME_OVERRIDE)
            else putString(KEY_DEVICE_NAME_OVERRIDE, normalized)
        }.apply()
    }

    /** Keep the connection code stable across app/service restarts. */
    fun getOrCreateConnectionCode(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString(KEY_CONNECTION_CODE, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = NetworkUtils.generateConnectionCode()
        preferences.edit().putString(KEY_CONNECTION_CODE, generated).apply()
        return generated
    }

    fun regenerateConnectionCode(context: Context): String {
        val generated = NetworkUtils.generateConnectionCode()
        prefs(context).edit().putString(KEY_CONNECTION_CODE, generated).apply()
        return generated
    }

    /** Stable per-install UUID; avoids Build.SERIAL restrictions/collisions on modern Android. */
    fun getOrCreateDeviceUuid(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString(KEY_DEVICE_UUID, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_UUID, generated).apply()
        return generated
    }

    fun getOrCreateWebUiToken(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString(KEY_WEB_UI_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = generateToken()
        preferences.edit().putString(KEY_WEB_UI_TOKEN, generated).apply()
        return generated
    }

    fun regenerateWebUiToken(context: Context): String {
        val generated = generateToken()
        prefs(context).edit().putString(KEY_WEB_UI_TOKEN, generated).apply()
        return generated
    }

    data class Snapshot(
        val airPlayEnabled: Boolean,
        val dlnaEnabled: Boolean,
        val miracastEnabled: Boolean,
        val customMdnsEnabled: Boolean,
        val airPlayAudioEnabled: Boolean,
        val autoLaunchPlayer: Boolean,
        val mirrorMaxHeight: Int,
        val upnpPort: Int,
        val webUiEnabled: Boolean,
        val webUiPort: Int,
        val webUiLastBoundPort: Int?,
        val webUiAuthRequired: Boolean,
        val autoStartOnBoot: Boolean,
        val deviceNameOverride: String?
    )

    fun snapshot(context: Context): Snapshot = Snapshot(
        airPlayEnabled = isAirPlayEnabled(context),
        dlnaEnabled = isDlnaEnabled(context),
        miracastEnabled = isMiracastEnabled(context),
        customMdnsEnabled = isCustomMdnsEnabled(context),
        airPlayAudioEnabled = isAirPlayAudioEnabled(context),
        autoLaunchPlayer = isAutoLaunchPlayer(context),
        mirrorMaxHeight = getMirrorMaxHeight(context),
        upnpPort = getUpnpPort(context),
        webUiEnabled = isWebUiEnabled(context),
        webUiPort = getWebUiPort(context),
        webUiLastBoundPort = getWebUiLastBoundPort(context),
        webUiAuthRequired = isWebUiAuthRequired(context),
        autoStartOnBoot = isAutoStartOnBoot(context),
        deviceNameOverride = getDeviceNameOverride(context)
    )

    private fun sanitizePort(port: Int, fallback: Int): Int =
        if (port in 1024..65535) port else fallback

    private fun generateToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
