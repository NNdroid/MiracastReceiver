package com.weekd.miracastreceiver.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlRequestTest {

    @Test
    fun acceptsHttpsHlsAndSafeHeaders() {
        val request = MediaUrlRequest.parse(
            "https://example.com/live/index.m3u8?token=abc",
            " Live TV ",
            mapOf("User-Agent" to "TV Client", "Referer" to "https://example.com/")
        )
        assertEquals("https://example.com/live/index.m3u8?token=abc", request.url)
        assertEquals("Live TV", request.title)
        assertEquals("TV Client", request.headers["User-Agent"])
    }

    @Test
    fun rejectsNonHttpSchemes() {
        val result = runCatching { MediaUrlRequest.parse("file:///sdcard/movie.mp4") }
        assertTrue(result.isFailure)
        assertEquals("unsupported_url_scheme", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsCredentialBearingUrls() {
        val result = runCatching { MediaUrlRequest.parse("https://user:pass@example.com/video.mp4") }
        assertTrue(result.isFailure)
        assertEquals("url_userinfo_not_allowed", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsHopByHopAndWebUiTokenHeaders() {
        listOf("Host", "Content-Length", "Connection", "X-API-Token").forEach { header ->
            val result = runCatching {
                MediaUrlRequest.parse("https://example.com/video.mp4", headers = mapOf(header to "x"))
            }
            assertTrue("$header should be rejected", result.isFailure)
        }
    }

    @Test
    fun rejectsHeaderInjection() {
        val result = runCatching {
            MediaUrlRequest.parse(
                "https://example.com/video.mp4",
                headers = mapOf("Referer" to "https://ok/\r\nX-Evil: 1")
            )
        }
        assertTrue(result.isFailure)
        assertEquals("invalid_header_value", result.exceptionOrNull()?.message)
    }
}
