package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackCapabilitySourceTest {
    private val runtime = File("src/main/java/com/weekd/miracastreceiver/web/RuntimeState.kt").readText()
    private val urlPlayer = File("src/main/java/com/weekd/miracastreceiver/ui/UrlPlaybackActivity.kt").readText()
    private val dlnaPlayer = File("src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt").readText()
    private val server = File("src/main/java/com/weekd/miracastreceiver/web/WebUiServer.kt").readText()
    private val web = File("src/main/assets/webui/index.html").readText()

    @Test
    fun playbackCapabilitiesComeFromMedia3InsteadOfDurationHeuristics() {
        assertTrue(runtime.contains("val isLive: Boolean = false"))
        assertTrue(runtime.contains("val isSeekable: Boolean = false"))
        assertTrue(urlPlayer.contains("isCurrentMediaItemLive"))
        assertTrue(urlPlayer.contains("isCurrentMediaItemSeekable"))
        assertTrue(dlnaPlayer.contains("putExtra(\"is_live\", currentPlayer.isCurrentMediaItemLive)"))
        assertTrue(dlnaPlayer.contains("putExtra(\"is_seekable\", currentPlayer.isCurrentMediaItemSeekable)"))
        assertTrue(server.contains(".put(\"isLive\", playback.isLive)"))
        assertTrue(server.contains(".put(\"isSeekable\", playback.isSeekable)"))
        assertTrue(web.contains("timeline(Number(p.positionMs)||0,Number(p.durationMs)||0,!!p.isLive,!!p.isSeekable)"))
    }
}
