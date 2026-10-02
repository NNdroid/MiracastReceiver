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
    fun dualStackBinderPreflightsAndVerifiesIpv4BeforeTrustingIpv6Wildcard() {
        assertTrue(source.contains("ipv4WasAvailable"))
        assertTrue(source.contains("tryBindIpv4(port, 1)"))
        assertTrue(source.contains("probeTcp(IPV4_LOOPBACK, port)"))
        assertTrue(source.contains("verified IPv6 wildcard dual-stack"))
        assertTrue(source.contains("rebinding with IPv4 priority"))
    }
}
