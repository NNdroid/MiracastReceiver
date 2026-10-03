package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WfdRootHelperTest {
    @Test
    fun `primary sink advertises Xiaomi compatible 7236 control port by default`() {
        assertEquals("000600111c440032", WfdRootHelper.subelemHex())
    }

    @Test
    fun `device info can still encode an explicit control port`() {
        assertEquals("000600111f900032", WfdRootHelper.subelemHex(8080))
    }

    @Test
    fun `peer WFD subelement exposes source control port`() {
        val output = """
            P2P_PEER FIRST -> 02:11:22:33:44:55
            device_name=Xiaomi Phone
            wfd_subelems=00000600001f900032
        """.trimIndent()
        assertEquals(8080, WfdRootHelper.parsePeerControlPort(output))
    }

    @Test
    fun `peer WFD parser accepts source ephemeral port`() {
        val output = "wfd_subelems=0000060000c3500032"
        assertEquals(50000, WfdRootHelper.parsePeerControlPort(output))
    }

    @Test
    fun `peer WFD parser rejects zero source port`() {
        val output = "wfd_subelems=000006001100000032"
        assertNull(WfdRootHelper.parsePeerControlPort(output))
    }
}
