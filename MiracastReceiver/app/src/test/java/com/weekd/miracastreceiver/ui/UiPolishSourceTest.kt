package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiPolishSourceTest {
    private val mainLayout = File("src/main/res/layout/activity_main.xml").readText()
    private val styles = File("src/main/res/values/styles.xml").readText()
    private val webCss = File("src/main/assets/webui/styles.css").readText()

    @Test
    fun tvShellUsesBrandedNavigationRail() {
        assertTrue(mainLayout.contains("@drawable/bg_tv_sidebar"))
        assertTrue(mainLayout.contains("@drawable/bg_tv_brand_mark"))
        assertTrue(mainLayout.contains("android:text=\"MR\""))
        assertTrue(styles.contains("@drawable/bg_tv_nav_item"))
        assertTrue(styles.contains("android:layout_height\">64dp"))
        assertTrue(styles.contains("Theme.Material3.DayNight.NoActionBar"))
    }

    @Test
    fun webUiKeepsResponsiveDayNightHierarchy() {
        assertTrue(webCss.contains(".app-shell"))
        assertTrue(webCss.contains(".hero"))
        assertTrue(webCss.contains(".metric-grid"))
        assertTrue(webCss.contains("html[data-theme=\"light\"]"))
        assertTrue(webCss.contains("html[data-theme=\"dark\"]"))
        assertTrue(webCss.contains("prefers-color-scheme:light"))
        assertTrue(webCss.contains("backdrop-filter:blur"))
        assertTrue(webCss.contains("@supports not"))
        assertTrue(webCss.contains("@media(max-width:820px)"))
        assertTrue(webCss.contains("@media(max-width:520px)"))
        assertTrue(webCss.contains("body.stale:after"))
    }
}
