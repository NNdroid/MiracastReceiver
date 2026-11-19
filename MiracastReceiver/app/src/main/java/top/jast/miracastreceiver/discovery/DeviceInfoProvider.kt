package top.jast.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import top.jast.miracastreceiver.util.AppSettings
import top.jast.miracastreceiver.utils.CodecUtils
import top.jast.miracastreceiver.utils.NetworkUtils

/** Device identity/status provider shared by casting protocols, TV UI and WebUI. */
class DeviceInfoProvider(private val context: Context) {

    /**
     * 对外显示的设备名，AirPlay / DLNA / Google Cast / 自定义发现共用。
     *
     * 优先级：fork 的设备名覆盖（WebUI/设置页写入）> 应用内接收器名称（上游 [AppSettings.setReceiverName]
     * 写入，所有投屏入口共用同一套校验）> 系统设备名称（Settings.Global `device_name`）> 「厂商 型号」。
     * `Settings.Global.DEVICE_NAME` 是 API 25 才加的，minSdk 21 所以直接用键名。
     */
    fun getDeviceName(): String {
        AppSettings.getDeviceNameOverride(context)?.let { return it }
        AppSettings.getReceiverName(context).takeIf { it.isNotBlank() }?.let { return it }
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
            "ip" to (NetworkUtils.getLocalIpAddress(context) ?: "unknown"),
            "h264" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264).toString(),
            "h265" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265).toString(),
            "recommended_codec" to videoConfig.mimeType,
            "max_width" to videoConfig.width.toString(),
            "max_height" to videoConfig.height.toString(),
            "max_fps" to videoConfig.frameRate.toString()
        )
    }
}
