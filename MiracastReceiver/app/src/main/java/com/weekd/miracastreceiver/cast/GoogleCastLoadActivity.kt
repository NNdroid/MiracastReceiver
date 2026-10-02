package com.weekd.miracastreceiver.cast

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber

/**
 * Thin exported entry point for Cast Connect LOAD intents.
 * Playback itself is delegated to UrlPlaybackActivity by [GoogleCastReceiver].
 */
class GoogleCastLoadActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent()
    }

    private fun processIntent() {
        if (!GoogleCastReceiver.handleIntent(intent)) {
            Timber.w("Unhandled Google Cast intent action=${intent?.action}")
        }
        finish()
    }
}
