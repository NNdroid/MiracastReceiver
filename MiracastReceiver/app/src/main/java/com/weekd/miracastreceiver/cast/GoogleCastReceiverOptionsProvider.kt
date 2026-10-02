package com.weekd.miracastreceiver.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.weekd.miracastreceiver.BuildConfig

/**
 * Google Cast Connect receiver options.
 *
 * The Cast application id is supplied at build time with either:
 *   - Gradle property: -PgoogleCastAppId=XXXXXXX
 *   - environment: GOOGLE_CAST_APP_ID=XXXXXXX
 *
 * A blank id keeps Cast disabled so normal sideload/debug builds remain usable on
 * non-Google Android TV devices.
 */
class GoogleCastReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions {
        val builder = CastReceiverOptions.Builder(context)
        val appId = BuildConfig.GOOGLE_CAST_APP_ID.trim()
        if (appId.isNotEmpty()) {
            builder.setCastAppId(appId)
        }
        return builder.build()
    }
}
