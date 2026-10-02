package com.weekd.miracastreceiver

import android.app.Application
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.web.WebLogBuffer
import timber.log.Timber

/** Miracast Receiver Application. */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        NetworkUtils.initialize(this)

        // Keep a bounded in-memory diagnostic log in all builds for the local WebUI.
        Timber.plant(WebLogBuffer.timberTree)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("MiracastApp initialized")
    }
}
