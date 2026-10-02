package com.weekd.miracastreceiver.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.weekd.miracastreceiver.R
import java.util.WeakHashMap

/**
 * Migration safety-net for the legacy player code that still assigns a few historical Chinese
 * strings directly. New UI must use Android string resources. This watcher translates only known
 * user-facing legacy phrases and costs nothing when text is unchanged.
 */
object LegacyUiLocalizer : Application.ActivityLifecycleCallbacks {
    private val watched = WeakHashMap<TextView, Boolean>()
    private val installed = WeakHashMap<Activity, Boolean>()

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    private fun attach(activity: Activity) {
        val root = activity.window?.decorView ?: return
        localizeTree(activity, root)
        if (installed.put(activity, true) != true) {
            root.viewTreeObserver.addOnGlobalLayoutListener { localizeTree(activity, root) }
        }
    }

    private fun localizeTree(activity: Activity, view: View) {
        if (view is TextView) {
            translateView(activity, view)
            if (watched.put(view, true) != true) {
                view.addTextChangedListener(object : TextWatcher {
                    private var applying = false
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (applying) return
                        val source = s?.toString().orEmpty()
                        val translated = translate(activity, source)
                        if (translated != source) {
                            applying = true
                            view.text = translated
                            applying = false
                        }
                    }
                })
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) localizeTree(activity, view.getChildAt(i))
    }

    private fun translateView(activity: Activity, view: TextView) {
        val text = view.text?.toString().orEmpty()
        val translated = translate(activity, text)
        if (translated != text) view.text = translated
        val hint = view.hint?.toString().orEmpty()
        val translatedHint = translate(activity, hint)
        if (translatedHint != hint) view.hint = translatedHint
    }

    private fun translate(activity: Activity, source: String): String {
        if (source.isBlank()) return source
        val direct = when (source) {
            "正在缓冲...", "重试后正在缓冲…" -> R.string.buffering
            "正在播放" -> R.string.playing
            "已暂停" -> R.string.paused
            "播放完成" -> R.string.playback_finished
            "播放错误" -> R.string.error_playback
            "更多" -> R.string.more
            "视频信息" -> R.string.video_info
            "关闭视频信息" -> R.string.hide_video_info
            "画质" -> R.string.quality
            "字幕" -> R.string.subtitles
            "屏幕方向" -> R.string.screen_orientation
            "字幕切换将随媒体字幕轨自动支持" -> R.string.subtitles_auto_hint
            "选择画质" -> R.string.quality_title
            "自动" -> R.string.quality_auto
            "流畅 480p" -> R.string.quality_480
            "高清 720p" -> R.string.quality_720
            "超清 1080p" -> R.string.quality_1080
            "原画" -> R.string.quality_original
            "画质：自动" -> R.string.quality_auto_status
            "正在显示图片" -> R.string.displaying_image
            "图片轮播已暂停" -> R.string.image_slideshow_paused
            "iPhone 屏幕镜像" -> R.string.airplay_mirror_title
            "正在接收 iPhone 屏幕...", "正在接收 iPhone 屏幕…" -> R.string.airplay_receiving
            "等待 AirPlay 显示画面...", "等待 AirPlay 显示画面…" -> R.string.airplay_waiting
            "Windows 无线显示器" -> R.string.miracast_title
            "正在接收 Windows 屏幕...", "正在接收 Windows 屏幕…" -> R.string.miracast_receiving
            "等待 Windows 画面...", "等待 Windows 画面…" -> R.string.miracast_waiting
            else -> null
        }
        if (direct != null) return activity.getString(direct)

        if (source.startsWith("播放错误: ")) return activity.getString(R.string.playback_error_detail, source.removePrefix("播放错误: "))
        if (source.startsWith("图片加载错误: ")) return activity.getString(R.string.image_error_detail, source.removePrefix("图片加载错误: "))
        if (source.startsWith("画质：") && source.endsWith("p")) {
            source.removePrefix("画质：").removeSuffix("p").toIntOrNull()?.let { return activity.getString(R.string.quality_status, it) }
        }
        if (source.startsWith("播放速度：")) return activity.getString(R.string.playback_speed_status, source.removePrefix("播放速度："))

        var result = source
        val labels = listOf(
            "视频信息" to R.string.stream_info_title,
            "源分辨率" to R.string.stream_source_resolution,
            "显示分辨率" to R.string.stream_display_resolution,
            "视频编码" to R.string.stream_codec,
            "帧率" to R.string.stream_fps,
            "码率" to R.string.stream_bitrate,
            "网速" to R.string.stream_speed
        )
        labels.forEach { (legacy, id) -> result = result.replace(legacy, activity.getString(id)) }
        return result
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { activity.window?.decorView?.post { attach(activity) } }
    override fun onActivityResumed(activity: Activity) = attach(activity)
    override fun onActivityDestroyed(activity: Activity) { installed.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
