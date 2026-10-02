package com.weekd.miracastreceiver.util

import android.content.Context
import com.weekd.miracastreceiver.utils.NetworkUtils

/** Application settings shared by the TV UI and the always-on receiver service. */
object AppSettings {

    private const val PREFS_NAME = "miracast_settings"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_CONNECTION_CODE = "connection_code"

    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    /**
     * Keep the connection code stable while the app/UI restarts so the background mDNS record and
     * the Android TV status page always advertise the same value.
     */
    fun getOrCreateConnectionCode(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString(KEY_CONNECTION_CODE, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = NetworkUtils.generateConnectionCode()
        preferences.edit().putString(KEY_CONNECTION_CODE, generated).apply()
        return generated
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
