package top.jast.miracastreceiver

import android.app.Application
import android.os.Build
import top.jast.miracastreceiver.cast.GoogleCastReceiver
import top.jast.miracastreceiver.util.AppLanguage
import top.jast.miracastreceiver.util.LegacyUiLocalizer
import top.jast.miracastreceiver.utils.NetworkUtils
import top.jast.miracastreceiver.web.WebLogBuffer
import timber.log.Timber

/** Miracast Receiver Application. */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        NetworkUtils.initialize(this)
        LegacyUiLocalizer.install(this)

        // 要在任何 Activity / 服务创建前恢复界面语言
        AppLanguage.restore(this)

        // Both the UI and :castplus_nearby process load this before the SDK.
        // Earlier Android versions retain the original netlink watcher.
        if (Build.VERSION.SDK_INT >= 30) {
            System.loadLibrary("castplus_network_compat")
        }

        // A bounded in-memory diagnostic log for the local WebUI, plus logcat in every build.
        // Gate logcat on DEBUG and a receiver stops producing any observable evidence: the buffer
        // keeps the last 600 lines and everything older is gone, so a failure that happened once
        // during a Miracast attempt is unrecoverable. On a TV box that is usually backgrounded
        // there is no other way to read the log, and Timber.plant() only adds a tree — planting
        // both keeps the two surfaces independent.
        Timber.plant(WebLogBuffer.timberTree)
        Timber.plant(Timber.DebugTree())

        // Optional official Google Cast Connect integration. A normal build with no Cast App ID
        // keeps this disabled and therefore remains compatible with non-Google Android TV devices.
        GoogleCastReceiver.initialize(this)

        Timber.i("MiracastApp initialized")
    }
}
