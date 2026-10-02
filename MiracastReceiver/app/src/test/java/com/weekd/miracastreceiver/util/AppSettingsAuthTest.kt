package com.weekd.miracastreceiver.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsAuthTest {
    @Test
    fun customTokenAcceptsReasonablePrintableValues() {
        assertTrue(AppSettings.isValidCustomToken("my-tv-token-2026"))
        assertTrue(AppSettings.isValidCustomToken("AbC123!@#_-."))
        assertTrue(AppSettings.isValidCustomToken("12345678"))
    }

    @Test
    fun customTokenRejectsShortWhitespaceAndControlCharacters() {
        assertFalse(AppSettings.isValidCustomToken("short"))
        assertFalse(AppSettings.isValidCustomToken("token with space"))
        assertFalse(AppSettings.isValidCustomToken("token\nwith-newline"))
        assertFalse(AppSettings.isValidCustomToken("a".repeat(AppSettings.MAX_CUSTOM_TOKEN_LENGTH + 1)))
    }
}
