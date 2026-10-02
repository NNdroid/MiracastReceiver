package com.weekd.miracastreceiver.dlna

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SsdpDualStackSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/dlna/SsdpServer.kt").readText()

    @Test
    fun ssdpUsesSeparateIpv4AndIpv6Listeners() {
        assertTrue(source.contains("InetAddress.getByName(\"0.0.0.0\")"))
        assertTrue(source.contains("InetAddress.getByName(\"::\")"))
        assertTrue(source.contains("SSDP_IPV4"))
        assertTrue(source.contains("SSDP_IPV6"))
        assertTrue(source.contains("createFamilySocket"))
    }

    @Test
    fun ipv6NotificationsAreScopedToAnInterface() {
        assertTrue(source.contains("socket.networkInterface = iface"))
        assertTrue(source.contains("Inet6Address.getByAddress(null, group.address, iface.index)"))
    }
}
