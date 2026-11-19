package top.jast.miracastreceiver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the TV remote-control navigation contract: focusable controls, visible focus
 * states and a coherent initial focus. These are source-level checks because layout
 * inflation and D-pad behaviour require a real TV device to exercise end to end.
 */
class RemoteControlSourceTest {
    private val res = File("src/main/res")
    private val ui = File("src/main/java/top/jast/miracastreceiver/ui")

    private fun layout(name: String): String = File(res, "layout/$name.xml").readText()

    @Test
    fun `tv shell has no stale landscape layout that breaks fragment navigation`() {
        // The old sw*-land activity_main used different IDs and crashed MainActivity on TVs.
        assertFalse(File(res, "layout-sw480dp-land").exists())
        assertFalse(File(res, "layout-sw600dp-land").exists())
        val main = layout("activity_main")
        assertTrue(main.contains("@+id/nav_home"))
        assertTrue(main.contains("@+id/fragment_container"))
    }

    @Test
    fun `player controls expose visible focus states and a dpad focus chain`() {
        val controls = layout("custom_player_control")
        assertTrue(controls.contains("@drawable/bg_player_button_tv"))
        assertTrue(controls.contains("android:nextFocusRight=\"@id/exo_rew\""))
        assertTrue(controls.contains("android:nextFocusLeft=\"@id/exo_play_pause\""))
        assertTrue(controls.contains("android:nextFocusUp=\"@id/exo_progress\""))
        assertFalse("borderless background hides remote focus", controls.contains("selectableItemBackgroundBorderless"))
    }

    @Test
    fun `player focuses primary control when osd appears for remote`() {
        val player = File(ui, "PlayerActivity.kt").readText()
        assertTrue(player.contains("focusPlayPauseForRemote"))
        assertTrue(player.contains("R.id.exo_play_pause"))
    }

    @Test
    fun `home dashboard offers focusable remote actions and initial focus`() {
        val home = layout("fragment_home")
        assertTrue(home.contains("@+id/btn_home_rename"))
        assertTrue(home.contains("@+id/btn_home_settings"))
        assertTrue(home.contains("android:nextFocusLeft=\"@id/nav_settings\""))
        val fragment = File(ui, "HomeFragment.kt").readText()
        assertTrue(fragment.contains("override fun requestInitialFocus()"))
        assertTrue(fragment.contains("btnRename.requestFocus()"))
    }

    @Test
    fun `cast consent dialog is fully navigable by remote`() {
        val connect = layout("activity_connect")
        assertTrue(connect.contains("@drawable/bg_tv_action"))
        assertTrue(connect.contains("android:nextFocusRight=\"@id/btn_allow\""))
        assertTrue(connect.contains("android:nextFocusLeft=\"@id/btn_deny\""))
        assertTrue(connect.contains("android:nextFocusDown=\"@id/btn_allow\""))
        val activity = File(ui, "ConnectActivity.kt").readText()
        assertTrue(activity.contains("allow.requestFocus()"))
    }
}
