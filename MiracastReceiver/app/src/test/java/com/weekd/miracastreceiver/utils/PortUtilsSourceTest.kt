package com.weekd.miracastreceiver.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PortUtilsSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt").readText()

    @Test
    fun webUiPrimaryListenerUsesExplicitIpv4Wildcard() {
        assertTrue(source.contains("InetAddress.getByName(\"0.0.0.0\")"))
        assertTrue(source.contains("tryBindIpv4"))
        assertTrue(source.contains("InetSocketAddress(IPV4_WILDCARD, port)"))
    }

    @Test
    fun webUiDoesNotRelyOnUnspecifiedWildcardBinding() {
        assertFalse(source.contains("bind(InetSocketAddress(port), backlog)"))
    }
}
