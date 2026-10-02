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

        // Keep a bounded in-memory diagnostic log in all builds for the local WebUI.
        Timber.plant(WebLogBuffer.timberTree)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Optional official Google Cast Connect integration. A normal build with no Cast App ID
        // keeps this disabled and therefore remains compatible with non-Google Android TV devices.
        GoogleCastReceiver.initialize(this)

        Timber.i("MiracastApp initialized")
    }
}
