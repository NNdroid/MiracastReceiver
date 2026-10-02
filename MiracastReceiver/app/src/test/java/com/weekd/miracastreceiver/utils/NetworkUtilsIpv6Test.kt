package com.weekd.miracastreceiver.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkUtilsIpv6Test {
    @Test
    fun formatsIpv4HttpUrl() {
        assertEquals("http://192.168.1.10:18090/", NetworkUtils.buildHttpUrl("192.168.1.10", 18090))
    }

    @Test
    fun bracketsIpv6LiteralInHttpUrl() {
        assertEquals("http://[fd66:f2f:2090::2]:18090/", NetworkUtils.buildHttpUrl("fd66:f2f:2090::2", 18090))
    }

    @Test
    fun escapesIpv6ZoneIdForUrl() {
        assertEquals("http://[fe80::1234%25wlan0]:18090/", NetworkUtils.buildHttpUrl("fe80::1234%wlan0", 18090))
    }

    @Test
    fun doesNotDoubleBracketIpv6Host() {
        assertEquals("[2001:db8::1]", NetworkUtils.formatHostForUrl("[2001:db8::1]"))
    }
}
