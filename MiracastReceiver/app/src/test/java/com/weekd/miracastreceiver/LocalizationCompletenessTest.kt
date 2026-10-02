package com.weekd.miracastreceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalizationCompletenessTest {
    private val stringRegex = Regex("<string\\s+name=\"([^\"]+)\"")

    private fun keys(dir: File): Set<String> = dir.listFiles()
        .orEmpty()
        .filter { it.extension == "xml" }
        .flatMap { file -> stringRegex.findAll(file.readText()).map { it.groupValues[1] }.toList() }
        .toSet()

    @Test
    fun `all supported Android locales contain every default string key`() {
        val res = File("src/main/res")
        val base = keys(File(res, "values"))
        assertTrue("Default string resources must not be empty", base.isNotEmpty())

        listOf("values-zh-rCN", "values-zh-rTW", "values-ja", "values-ko").forEach { qualifier ->
            val localized = keys(File(res, qualifier))
            assertEquals("Missing or extra localized keys in $qualifier", base, localized)
        }
    }

    @Test
    fun `webui exposes all supported languages and auto mode`() {
        val html = File("src/main/assets/webui/index.html").readText()
        listOf("auto", "en", "zh-CN", "zh-TW", "ja", "ko").forEach { language ->
            assertTrue("WebUI is missing language option $language", html.contains("value=\"$language\""))
        }

        val js = File("src/main/assets/webui/app.js").readText()
        listOf("'en':EN", "'zh-CN':ZHCN", "'zh-TW':ZHTW", "'ja':JA", "'ko':KO").forEach { marker ->
            assertTrue("WebUI dictionary is missing $marker", js.contains(marker))
        }
    }
}
