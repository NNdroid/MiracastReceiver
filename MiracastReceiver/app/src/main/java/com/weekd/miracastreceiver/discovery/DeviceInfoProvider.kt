package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.CodecUtils
import com.weekd.miracastreceiver.utils.NetworkUtils

/** Device identity/status provider shared by casting protocols, TV UI and WebUI. */
class DeviceInfoProvider(private val context: Context) {

    /**
     * User override wins, then Android TV system device name, then manufacturer/model fallback.
     * This keeps AirPlay / DLNA / Miracast naming consistent.
     */
    fun getDeviceName(): String {
        AppSettings.getDeviceNameOverride(context)?.let { return it }

        val systemName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return systemName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android TV" }
    }

    /** Stable per-install ID; does not depend on restricted/deprecated Build.SERIAL. */
    fun getDeviceId(): String = AppSettings.getOrCreateDeviceUuid(context)

    fun getDeviceInfo(): Map<String, String> {
        val videoConfig = CodecUtils.getRecommendedVideoConfig()

        return mapOf(
            "name" to getDeviceName(),
            "id" to getDeviceId(),
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "ip" to (NetworkUtils.getLocalIpAddress() ?: "unknown"),
            "h264" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264).toString(),
            "h265" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265).toString(),
            "recommended_codec" to videoConfig.mimeType,
            "max_width" to videoConfig.width.toString(),
            "max_height" to videoConfig.height.toString(),
            "max_fps" to videoConfig.frameRate.toString()
        )
    }
}
