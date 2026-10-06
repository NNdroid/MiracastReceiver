package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WfdRootHelperTest {
    @Test
    fun `primary sink advertises Xiaomi compatible 7236 control port by default`() {
        assertEquals("00060cf11c440032", WfdRootHelper.subelemHex())
    }

    @Test
    fun `device info can still encode an explicit control port`() {
        assertEquals("00060cf11f900032", WfdRootHelper.subelemHex(8080))
    }

    @Test
    fun `device info keeps the sink role and declares the full capability set`() {
        // Layout of the WFD Device Information subelement value, bit 0 being the least significant:
        //   0          session available
        //   1..2       preferred HTP mode (0 = sink only)
        //   3          maximum concurrent sessions bandwidth
        //   4..6       supported HTP modes 1024x768 | 1280x720 | 1920x1080
        //   7          U-APSD
        //   8..9       graphics profile / level
        //   10..11     video capability
        val deviceInfo = WfdRootHelper.subelemHex().substring(4, 8).toInt(16)
        assertEquals(0xCF1, deviceInfo)
        assertEquals(1, deviceInfo and 0x1)
        assertEquals(0, (deviceInfo shr 1) and 0x3)
        assertEquals(0x7, (deviceInfo shr 4) and 0x7)
        assertEquals(1, (deviceInfo shr 7) and 0x1)
        assertEquals(3, (deviceInfo shr 10) and 0x3)
    }

    @Test
    fun `group owner intent parses from a stock supplicant p2p_get reply`() {
        // wpa_supplicant prints the field with a colon separator.
        val output = """
            P2P_GET -> OK
            p2p_dev_addr=02:11:22:33:44:55
            group_owner_intent: 15
            max_concurrent_groups: 1
        """.trimIndent()
        assertEquals(15, WfdRootHelper.parseGroupOwnerIntent(output))
    }

    @Test
    fun `group owner intent parses from a vendor go_int reply`() {
        // Some vendor builds print the same value with an equals separator.
        assertEquals(15, WfdRootHelper.parseGroupOwnerIntent("go_int=15\n"))
    }

    @Test
    fun `group owner intent parser returns null when the field is absent`() {
        // A probe that never finds the field is "unread", not "zero" — treating silence as a
        // wrong intent would report a healthy sink as broken.
        assertNull(WfdRootHelper.parseGroupOwnerIntent("p2p_dev_addr=02:11:22:33:44:55\n"))
        assertNull(WfdRootHelper.parseGroupOwnerIntent(""))
    }

    @Test
    fun `group owner intent parser takes the last value when the field repeats`() {
        assertEquals(7, WfdRootHelper.parseGroupOwnerIntent("go_int=15\ngo_int=7\n"))
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
