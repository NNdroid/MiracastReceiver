package com.weekd.miracastreceiver.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PortUtilsSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt").readText()

    @Test
    fun webUiListenerHandlesBothAddressFamiliesDeterministically() {
        assertTrue(source.contains("InetAddress.getByName(\"0.0.0.0\")"))
        assertTrue(source.contains("InetAddress.getByName(\"::\")"))
        assertTrue(source.contains("tryBindDualStack"))
        assertTrue(source.contains("MultiplexingServerSocket"))
        assertTrue(source.contains("separate IPv6 + IPv4 sockets"))
    }

    @Test
    fun dualStackBinderPreflightsIpv4BeforeIpv6ClaimsThePort() {
        assertTrue(source.contains("ipv4WasAvailable"))
        assertTrue(source.contains("tryBindIpv4(port, 1)"))
        assertTrue(source.contains("IPv6 wildcard (dual-stack IPv4-mapped expected)"))
    }
}
