package com.weekd.miracastreceiver.dlna

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpnpDualStackSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/dlna/UpnpHttpServer.kt").readText()

    @Test
    fun upnpHttpUsesDeterministicDualStackBinder() {
        assertTrue(source.contains("PortUtils.bindFixedServerSocket(port)"))
        assertTrue(source.contains("started dual-stack"))
    }

    @Test
    fun deviceDescriptionUsesRequestAddressFamilyAndIpv6SafeUrlBuilder() {
        assertTrue(source.contains("remoteAddress is Inet6Address"))
        assertTrue(source.contains("addresses.ipv6 ?: addresses.ipv4"))
        assertTrue(source.contains("NetworkUtils.buildHttpUrl(address, port"))
    }
}
