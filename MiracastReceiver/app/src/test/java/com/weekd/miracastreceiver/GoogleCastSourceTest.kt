package com.weekd.miracastreceiver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleCastSourceTest {
    private val gradle = File("build.gradle.kts").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val receiver = File("src/main/java/com/weekd/miracastreceiver/cast/GoogleCastReceiver.kt").readText()
    private val options = File("src/main/java/com/weekd/miracastreceiver/cast/GoogleCastReceiverOptionsProvider.kt").readText()

    @Test
    fun castConnectDependenciesAndBuildConfigArePresent() {
        assertTrue(gradle.contains("play-services-cast-tv"))
        assertTrue(gradle.contains("play-services-cast:22.3.1"))
        assertTrue(gradle.contains("GOOGLE_CAST_APP_ID"))
    }

    @Test
    fun manifestRegistersCastLaunchAndLoadEntries() {
        assertTrue(manifest.contains("com.google.android.gms.cast.tv.action.LAUNCH"))
        assertTrue(manifest.contains("com.google.android.gms.cast.tv.action.LOAD"))
        assertTrue(manifest.contains("RECEIVER_OPTIONS_PROVIDER_CLASS_NAME"))
        assertTrue(manifest.contains("GoogleCastLoadActivity"))
    }

    @Test
    fun receiverBridgesLoadAndMediaSessionControls() {
        assertTrue(receiver.contains("setMediaLoadCommandCallback"))
        assertTrue(receiver.contains("setSessionCompatToken"))
        assertTrue(receiver.contains("UrlPlaybackActivity.EXTRA_URL"))
        assertTrue(receiver.contains("PlayerActivity.ACTION_PLAY"))
        assertTrue(receiver.contains("PlayerActivity.ACTION_PAUSE"))
        assertTrue(receiver.contains("PlayerActivity.ACTION_SEEK"))
        assertTrue(receiver.contains("broadcastMediaStatus"))
    }

    @Test
    fun optionsUseConfiguredCastAppId() {
        assertTrue(options.contains("BuildConfig.GOOGLE_CAST_APP_ID"))
        assertTrue(options.contains("setCastAppId"))
    }
}
