package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModernAndroidApiSourceTest {
    private val mainSource = File("src/main/java/com/weekd/miracastreceiver/ui/MainActivity.kt").readText()
    private val playerSource = File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
    private val settingsSource = File("src/main/java/com/weekd/miracastreceiver/ui/SettingsFragment.kt").readText()
    private val airPlayAudioSource = File("src/main/java/com/weekd/miracastreceiver/airplay/AudioPlayer.kt").readText()
    private val miracastAudioSource = File("src/main/java/com/weekd/miracastreceiver/miracast/MiracastAudioPlayer.kt").readText()

    @Test
    fun mainActivityUsesModernPermissionAndBackApis() {
        assertTrue(mainSource.contains("ActivityResultContracts.RequestMultiplePermissions"))
        assertTrue(mainSource.contains("onBackPressedDispatcher.addCallback"))
        assertFalse(mainSource.contains("ActivityCompat.requestPermissions"))
        assertFalse(mainSource.contains("onRequestPermissionsResult"))
        assertFalse(mainSource.contains("override fun onBackPressed"))
    }

    @Test
    fun playerUsesBackDispatcher() {
        assertTrue(playerSource.contains("onBackPressedDispatcher.addCallback"))
        assertTrue(playerSource.contains("OnBackPressedCallback"))
        assertFalse(playerSource.contains("override fun onBackPressed"))
    }

    @Test
    fun api23AudioPathsDoNotKeepAndroid5Fallbacks() {
        assertTrue(airPlayAudioSource.contains("AudioTrack.Builder()"))
        assertTrue(airPlayAudioSource.contains("AudioTrack.WRITE_NON_BLOCKING"))
        assertFalse(airPlayAudioSource.contains("Build.VERSION.SDK_INT"))
        assertFalse(airPlayAudioSource.contains("AudioManager.STREAM_MUSIC"))
        assertFalse(airPlayAudioSource.contains("@Suppress(\"DEPRECATION\")"))

        assertTrue(miracastAudioSource.contains("AudioTrack.Builder()"))
        assertTrue(miracastAudioSource.contains("AudioTrack.WRITE_BLOCKING"))
        assertFalse(miracastAudioSource.contains("Build.VERSION.SDK_INT"))
        assertFalse(miracastAudioSource.contains("AudioManager.STREAM_MUSIC"))
        assertFalse(miracastAudioSource.contains("@Suppress(\"DEPRECATION\")"))
    }

    @Test
    fun overlayStatusUsesApi23BaselineDirectly() {
        assertTrue(settingsSource.contains("val overlayAllowed = Settings.canDrawOverlays(appContext)"))
        assertFalse(settingsSource.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays"))
    }
}
