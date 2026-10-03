package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerLoadingIndicatorSourceTest {
    private val layout = File("src/main/res/layout/activity_player.xml").readText()

    @Test
    fun customCenterSpinnerIsHiddenByDefault() {
        val progressBlock = Regex(
            """<ProgressBar\s+android:id=\"@\+id/progress_bar\"[\s\S]*?/>"""
        ).find(layout)?.value.orEmpty()

        assertTrue(progressBlock.isNotEmpty())
        assertTrue(progressBlock.contains("android:visibility=\"gone\""))
        assertTrue(layout.contains("app:show_buffering=\"when_playing\""))
    }
}
