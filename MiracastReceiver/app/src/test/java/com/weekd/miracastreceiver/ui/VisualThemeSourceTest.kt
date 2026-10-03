package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisualThemeSourceTest {
    private val styles = File("src/main/res/values/styles.xml").readText()
    private val gradle = File("build.gradle.kts").readText()
    private val webCss = File("src/main/assets/webui/styles.css").readText()

    @Test
    fun tvThemeUsesMaterial3WithoutDroppingApi21() {
        assertTrue(styles.contains("Theme.Material3.DayNight.NoActionBar"))
        assertTrue(styles.contains("colorPrimaryContainer"))
        assertTrue(styles.contains("shapeAppearanceLargeComponent"))
        assertTrue(gradle.contains("minSdk = 21"))
        assertTrue(gradle.contains("com.google.android.material:material:1.13.0"))
    }

    @Test
    fun webUiKeepsLiquidGlassAndThemeFallbacks() {
        assertTrue(webCss.contains("backdrop-filter:blur"))
        assertTrue(webCss.contains("-webkit-backdrop-filter"))
        assertTrue(webCss.contains("html[data-theme=\"light\"]"))
        assertTrue(webCss.contains("prefers-color-scheme:light"))
        assertTrue(webCss.contains("@supports not"))
        assertTrue(webCss.contains("@media(max-width:820px)"))
    }
}
