package com.weekd.miracastreceiver.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiDashboardSourceTest {
    private val appJs = File("src/main/assets/webui/app.js").readText()
    private val css = File("src/main/assets/webui/styles.css").readText()

    @Test
    fun managementAddressUsesVerifiedBrowserOrigin() {
        assertTrue(appJs.contains("const actualAddress=location.origin"))
        assertTrue(appJs.contains("navigator.clipboard.writeText(address)"))
        assertFalse(appJs.contains("address=s.device.ip?"))
    }

    @Test
    fun statusUsesConfigAndRuntimeSemantics() {
        assertTrue(appJs.contains("let latestConfig=null"))
        assertTrue(appJs.contains("Promise.all([api('/api/status'),api('/api/config')])"))
        assertTrue(appJs.contains("runtimeStateLabel(latestConfig?.airPlayEnabled"))
        assertTrue(appJs.contains("runtimeStateLabel(latestConfig?.miracastEnabled"))
        assertTrue(appJs.contains("latestConfig.dlnaEnabled"))
    }

    @Test
    fun livePlaybackDoesNotPretendToHaveZeroDuration() {
        assertTrue(appJs.contains("else if(isActive)"))
        assertTrue(appJs.contains("t('live')"))
        assertTrue(appJs.contains("progressFill').classList.add('live')"))
    }

    @Test
    fun staleDecoderIsHiddenWhenPlaybackIsInactive() {
        assertTrue(appJs.contains("const decoder=isActive?(p.decoder||''):''"))
        assertTrue(appJs.contains("activePlayback(latestStatus?.playback)"))
    }

    @Test
    fun refreshedDashboardSupportsThemeFocusAndStaleStates() {
        assertTrue(css.contains(":focus-visible"))
        assertTrue(css.contains("prefers-color-scheme:light"))
        assertTrue(css.contains("prefers-reduced-motion:reduce"))
        assertTrue(css.contains("body.stale"))
        assertTrue(css.contains(".progress>div.live"))
    }
}
