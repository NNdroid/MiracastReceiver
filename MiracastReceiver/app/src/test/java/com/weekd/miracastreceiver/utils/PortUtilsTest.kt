package com.weekd.miracastreceiver.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class PortUtilsTest {

    @Test
    fun `findAvailablePort skips an occupied preferred port`() {
        ServerSocket(0).use { occupied ->
            val selected = PortUtils.findAvailablePort(occupied.localPort)
            assertNotEquals(occupied.localPort, selected)
            assertTrue(selected in 1024..65535)
        }
    }

    @Test
    fun `bindAvailableServerSocket uses preferred port when it is free`() {
        val preferred = ServerSocket(0).use { it.localPort }
        PortUtils.bindAvailableServerSocket(preferred).use { bound ->
            assertEquals(preferred, bound.localPort)
        }
    }

    @Test
    fun `bindAvailableServerSocket never uses excluded port`() {
        val excluded = ServerSocket(0).use { it.localPort }
        PortUtils.bindAvailableServerSocket(
            preferredPort = excluded,
            excludedPorts = setOf(excluded)
        ).use { bound ->
            assertNotEquals(excluded, bound.localPort)
            assertTrue(bound.localPort in 1024..65535)
        }
    }
}
