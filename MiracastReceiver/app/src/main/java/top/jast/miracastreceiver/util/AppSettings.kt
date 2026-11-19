package top.jast.miracastreceiver.util

import android.content.Context
import android.content.SharedPreferences
import top.jast.miracastreceiver.utils.NetworkUtils
import java.security.SecureRandom
import java.util.UUID

/** Application settings shared by the TV UI, WebUI and the always-on receiver service. */
object AppSettings {

    private const val PREFS_NAME = "miracast_settings"

    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_CONNECTION_CODE = "connection_code"
    private const val KEY_DEVICE_UUID = "device_uuid"
    private const val KEY_DEVICE_NAME_OVERRIDE = "device_name_override"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_RETURN_TO_APP_AFTER_CAST = "return_to_app_after_cast"

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
    private const val KEY_WEBRTC_ENABLED = "webrtc_enabled"
    private const val KEY_WEBRTC_PORT = "webrtc_port"
    private const val KEY_WEB_UI_LAST_BOUND_PORT = "web_ui_last_bound_port"
    private const val KEY_WEB_UI_AUTH_REQUIRED = "web_ui_auth_required"
    private const val KEY_WEB_UI_AUTH_MODE = "web_ui_auth_mode"
    private const val KEY_WEB_UI_TOKEN = "web_ui_token"
    private const val KEY_WEB_UI_CUSTOM_TOKEN = "web_ui_custom_token"

    // 上游新增：所有投屏入口共用的接收器名称与显示方向
    const val KEY_RECEIVER_NAME = "receiver_name"
    const val KEY_AIRPLAY_PORTRAIT_DISPLAY = "airplay_portrait_display"

    const val WEB_UI_AUTH_NONE = "none"
    const val WEB_UI_AUTH_AUTO = "auto"
    const val WEB_UI_AUTH_CUSTOM = "custom"
    const val MIN_CUSTOM_TOKEN_LENGTH = 8
    const val MAX_CUSTOM_TOKEN_LENGTH = 128

    const val DEFAULT_UPNP_PORT = 8080
    const val DEFAULT_WEB_UI_PORT = 18090
    const val DEFAULT_WEBRTC_PORT = 18095
    const val DEFAULT_MIRROR_MAX_HEIGHT = 0 // 0 = auto / display maximum

    // ── 上游新增：Miracast / Cast+ / AirPlay 竖屏与旋转 ────────────────────────

    fun getMiracastManualRotation(context: Context): Int = prefs(context).getInt("miracast_manual_rotation", 0)
    fun isMiracastPortraitDisplay(context: Context): Boolean = prefs(context).getBoolean("miracast_portrait_display", false)

    fun setMiracastRotation(context: Context, manual: Int, effective: Int): Boolean {
        val portrait = effective % 180 != 0
        val changed = isMiracastPortraitDisplay(context) != portrait
        if (changed || getMiracastManualRotation(context) != manual) {
            prefs(context).edit().putInt("miracast_manual_rotation", manual)
                .putBoolean("miracast_portrait_display", portrait).apply()
        }
        return changed
    }

    fun getCastPlusRotation(context: Context): Int = prefs(context).getInt("castplus_rotation", 0)

    fun setCastPlusRotation(context: Context, rotation: Int) {
        require(rotation in listOf(0, 90, 180, 270))
        prefs(context).edit().putInt("castplus_rotation", rotation).apply()
    }

    fun isAirPlayPortraitDisplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AIRPLAY_PORTRAIT_DISPLAY, false)

    fun getAirPlayManualRotation(context: Context): Int =
        prefs(context).getInt("airplay_manual_rotation", 0)

    /** Returns true when the declaration changes orientation and requires sender renegotiation. */
    fun setAirPlayRotation(context: Context, manual: Int, effective: Int): Boolean {
        val portrait = effective % 180 != 0
        val changed = isAirPlayPortraitDisplay(context) != portrait
        if (changed || getAirPlayManualRotation(context) != manual) {
            prefs(context).edit().putBoolean(KEY_AIRPLAY_PORTRAIT_DISPLAY, portrait)
                .putInt("airplay_manual_rotation", manual).apply()
        }
        return changed
    }

    fun getReceiverName(context: Context): String =
        prefs(context).getString(KEY_RECEIVER_NAME, "") ?: ""

    /** Empty means follow the system name. All entry points share the same validation. */
    fun setReceiverName(context: Context, value: String): Boolean {
        val name = if (value.isEmpty()) "" else ReceiverName.normalize(value) ?: return false
        return prefs(context).edit().putString(KEY_RECEIVER_NAME, name).commit()
    }

    fun observe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unobserve(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    fun isCastPlusEnabled(context: Context): Boolean = prefs(context).getBoolean("castplus_enabled", false)
    fun setCastPlusEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("castplus_enabled", enabled).apply()
    }

    fun isAutoRotateToFit(context: Context): Boolean = prefs(context).getBoolean("auto_rotate_to_fit", false)
    fun setAutoRotateToFit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_rotate_to_fit", enabled).apply()
    }

    /**
     * 是否开机自动启动接收服务。
     *
     * 默认开启：这个应用装在电视上就是当常驻投屏接收器用的，
     * 每次重启电视都要手动打开一次不符合使用场景。
     */
    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun isAirPlayEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AIRPLAY_ENABLED, true)
    fun setAirPlayEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_AIRPLAY_ENABLED, enabled).apply() }
    fun isDlnaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DLNA_ENABLED, true)
    fun setDlnaEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_DLNA_ENABLED, enabled).apply() }
    fun isMiracastEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MIRACAST_ENABLED, true)
    fun setMiracastEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_MIRACAST_ENABLED, enabled).apply() }
    fun isCustomMdnsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CUSTOM_MDNS_ENABLED, true)
    fun setCustomMdnsEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_CUSTOM_MDNS_ENABLED, enabled).apply() }
    fun isAirPlayAudioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AIRPLAY_AUDIO_ENABLED, true)
    fun setAirPlayAudioEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_AIRPLAY_AUDIO_ENABLED, enabled).apply() }
    fun isAutoLaunchPlayer(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_LAUNCH_PLAYER, true)
    fun setAutoLaunchPlayer(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_AUTO_LAUNCH_PLAYER, enabled).apply() }

    fun getMirrorMaxHeight(context: Context): Int = prefs(context).getInt(KEY_MIRROR_MAX_HEIGHT, DEFAULT_MIRROR_MAX_HEIGHT)
    fun setMirrorMaxHeight(context: Context, height: Int) {
        val normalized = when (height) { 720, 1080, 1440, 2160 -> height; else -> DEFAULT_MIRROR_MAX_HEIGHT }
        prefs(context).edit().putInt(KEY_MIRROR_MAX_HEIGHT, normalized).apply()
    }

    fun getUpnpPort(context: Context): Int = sanitizePort(prefs(context).getInt(KEY_UPNP_PORT, DEFAULT_UPNP_PORT), DEFAULT_UPNP_PORT)
    fun setUpnpPort(context: Context, port: Int) { prefs(context).edit().putInt(KEY_UPNP_PORT, sanitizePort(port, DEFAULT_UPNP_PORT)).apply() }

    fun isWebRtcEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WEBRTC_ENABLED, true)
    fun setWebRtcEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_WEBRTC_ENABLED, enabled).apply() }

    fun getWebRtcPort(context: Context): Int = sanitizePort(prefs(context).getInt(KEY_WEBRTC_PORT, DEFAULT_WEBRTC_PORT), DEFAULT_WEBRTC_PORT)
    fun setWebRtcPort(context: Context, port: Int) { prefs(context).edit().putInt(KEY_WEBRTC_PORT, sanitizePort(port, DEFAULT_WEBRTC_PORT)).apply() }

    fun isWebUiEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WEB_UI_ENABLED, true)
    fun setWebUiEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_WEB_UI_ENABLED, enabled).apply() }

    /** User-configured preferred WebUI port. Runtime collision fallback is stored separately. */
    fun getWebUiPort(context: Context): Int = sanitizePort(prefs(context).getInt(KEY_WEB_UI_PORT, DEFAULT_WEB_UI_PORT), DEFAULT_WEB_UI_PORT)
    fun setWebUiPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_WEB_UI_PORT, sanitizePort(port, DEFAULT_WEB_UI_PORT)).apply()
    }

    /** Last port the WebUI successfully bound; this never replaces the user's preferred port. */
    fun getWebUiLastBoundPort(context: Context): Int? {
        val port = prefs(context).getInt(KEY_WEB_UI_LAST_BOUND_PORT, 0)
        return port.takeIf { it in 1024..65535 }
    }
    fun setWebUiLastBoundPort(context: Context, port: Int) {
        if (port in 1024..65535) prefs(context).edit().putInt(KEY_WEB_UI_LAST_BOUND_PORT, port).apply()
    }
    fun setWebUiBoundPort(context: Context, port: Int) = setWebUiLastBoundPort(context, port)

    /**
     * Authentication mode. Existing installs are migrated lazily from the historical boolean:
     * true -> auto generated token, false -> no authentication.
     */
    fun getWebUiAuthMode(context: Context): String {
        val p = prefs(context)
        val explicit = p.getString(KEY_WEB_UI_AUTH_MODE, null)
        if (explicit in setOf(WEB_UI_AUTH_NONE, WEB_UI_AUTH_AUTO, WEB_UI_AUTH_CUSTOM)) return explicit!!
        return if (p.getBoolean(KEY_WEB_UI_AUTH_REQUIRED, true)) WEB_UI_AUTH_AUTO else WEB_UI_AUTH_NONE
    }

    fun isWebUiAuthRequired(context: Context): Boolean = getWebUiAuthMode(context) != WEB_UI_AUTH_NONE

    /** Compatibility setter retained for older callers/config payloads. */
    fun setWebUiAuthRequired(context: Context, required: Boolean) {
        setWebUiAuthMode(context, if (required) WEB_UI_AUTH_AUTO else WEB_UI_AUTH_NONE)
    }

    fun getWebUiCustomToken(context: Context): String? =
        prefs(context).getString(KEY_WEB_UI_CUSTOM_TOKEN, null)?.takeIf { isValidCustomToken(it) }

    fun setWebUiAuthMode(context: Context, mode: String, customToken: String? = null) {
        val normalizedMode = mode.lowercase().trim()
        require(normalizedMode in setOf(WEB_UI_AUTH_NONE, WEB_UI_AUTH_AUTO, WEB_UI_AUTH_CUSTOM)) { "invalid_auth_mode" }
        val editor = prefs(context).edit()
            .putString(KEY_WEB_UI_AUTH_MODE, normalizedMode)
            .putBoolean(KEY_WEB_UI_AUTH_REQUIRED, normalizedMode != WEB_UI_AUTH_NONE)
        if (normalizedMode == WEB_UI_AUTH_CUSTOM) {
            val token = customToken?.trim().orEmpty()
            require(isValidCustomToken(token)) { "invalid_custom_token" }
            editor.putString(KEY_WEB_UI_CUSTOM_TOKEN, token)
        }
        editor.apply()
    }

    fun isValidCustomToken(token: String): Boolean {
        if (token.length !in MIN_CUSTOM_TOKEN_LENGTH..MAX_CUSTOM_TOKEN_LENGTH) return false
        return token.none { it.isWhitespace() || it.isISOControl() }
    }

    fun getDeviceNameOverride(context: Context): String? = prefs(context).getString(KEY_DEVICE_NAME_OVERRIDE, null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setDeviceNameOverride(context: Context, name: String?) {
        val normalized = name?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        prefs(context).edit().apply {
            if (normalized == null) remove(KEY_DEVICE_NAME_OVERRIDE) else putString(KEY_DEVICE_NAME_OVERRIDE, normalized)
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

    /** Return the token currently accepted by the WebUI. */
    fun getOrCreateWebUiToken(context: Context): String {
        if (getWebUiAuthMode(context) == WEB_UI_AUTH_CUSTOM) {
            return getWebUiCustomToken(context) ?: run {
                // Corrupt/missing custom state fails safe by falling back to a generated token.
                setWebUiAuthMode(context, WEB_UI_AUTH_AUTO)
                getOrCreateAutoWebUiToken(context)
            }
        }
        return getOrCreateAutoWebUiToken(context)
    }

    private fun getOrCreateAutoWebUiToken(context: Context): String {
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
        return if (getWebUiAuthMode(context) == WEB_UI_AUTH_CUSTOM) getOrCreateWebUiToken(context) else generated
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
        val webrtcEnabled: Boolean,
        val webrtcPort: Int,
        val webUiEnabled: Boolean,
        val webUiPort: Int,
        val webUiLastBoundPort: Int?,
        val webUiAuthRequired: Boolean,
        val webUiAuthMode: String,
        val autoStartOnBoot: Boolean,
        val deviceNameOverride: String?
    )

    fun snapshot(context: Context): Snapshot = Snapshot(
        airPlayEnabled = isAirPlayEnabled(context), dlnaEnabled = isDlnaEnabled(context),
        miracastEnabled = isMiracastEnabled(context), customMdnsEnabled = isCustomMdnsEnabled(context),
        airPlayAudioEnabled = isAirPlayAudioEnabled(context), autoLaunchPlayer = isAutoLaunchPlayer(context),
        mirrorMaxHeight = getMirrorMaxHeight(context), upnpPort = getUpnpPort(context),
        webrtcEnabled = isWebRtcEnabled(context), webrtcPort = getWebRtcPort(context),
        webUiEnabled = isWebUiEnabled(context), webUiPort = getWebUiPort(context),
        webUiLastBoundPort = getWebUiLastBoundPort(context), webUiAuthRequired = isWebUiAuthRequired(context),
        webUiAuthMode = getWebUiAuthMode(context), autoStartOnBoot = isAutoStartOnBoot(context),
        deviceNameOverride = getDeviceNameOverride(context)
    )

    private fun sanitizePort(port: Int, fallback: Int): Int = if (port in 1024..65535) port else fallback

    private fun generateToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── 上游新增：界面语言与投屏结束后是否停留在应用 ──────────────────────────

    /** 界面语言的 BCP 47 标签，空串表示跟随系统。由 [AppLanguage] 读写。 */
    fun getLanguageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "") ?: ""

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /**
     * 投屏结束（遥控器返回键 / 手机端停止）后，是否留在本应用的设置页。
     *
     * 默认关闭：退出投屏就回到电视桌面，这是多数人投完屏想继续看电视的路径。
     * 打开后停在设置页，适合需要接着调设置（自启、语言）或确认接收状态的人。
     */
    fun isReturnToAppAfterCast(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RETURN_TO_APP_AFTER_CAST, false)

    fun setReturnToAppAfterCast(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETURN_TO_APP_AFTER_CAST, enabled).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
