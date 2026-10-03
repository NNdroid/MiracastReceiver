package com.weekd.miracastreceiver.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiModernPlaybackInlineScriptSyntaxTest {
    private val index = File("src/main/assets/webui/index.html").readText()

    @Test
    fun playbackInlineScriptIsBalancedAndClosed() {
        val playerStart = index.indexOf("const pstate=()=>latestStatus?.playback||{}")
        assertTrue(playerStart >= 0)
        val scriptStart = index.lastIndexOf("<script>", playerStart)
        val scriptEnd = index.indexOf("</script>", playerStart)
        assertTrue(scriptStart >= 0 && scriptEnd > playerStart)
        val script = index.substring(scriptStart + "<script>".length, scriptEnd)
        assertEquals(script.count { it == '{' }, script.count { it == '}' })
        assertEquals(script.count { it == '(' }, script.count { it == ')' })
        assertEquals(script.count { it == '[' }, script.count { it == ']' })
        assertTrue(script.contains("setInterval(render,300)"))
    }
}
