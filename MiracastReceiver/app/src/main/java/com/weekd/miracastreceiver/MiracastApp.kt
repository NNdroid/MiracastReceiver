package com.weekd.miracastreceiver

import android.app.Application
import com.weekd.miracastreceiver.cast.GoogleCastReceiver
import com.weekd.miracastreceiver.util.LegacyUiLocalizer
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.web.WebLogBuffer
import timber.log.Timber

/** Miracast Receiver Application. */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        NetworkUtils.initialize(this)
        LegacyUiLocalizer.install(this)

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
