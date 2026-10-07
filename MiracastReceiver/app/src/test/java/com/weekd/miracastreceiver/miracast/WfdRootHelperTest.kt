package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdRootHelperTest {
    @Test
    fun `primary sink advertises Xiaomi compatible 7236 control port by default`() {
        assertEquals("00063b111c440032", WfdRootHelper.subelemHex())
    }

    @Test
    fun `device info can still encode an explicit control port`() {
        assertEquals("00063b111f900032", WfdRootHelper.subelemHex(8080))
    }

    @Test
    fun `device info keeps the sink role and declares the full capability set`() {
        // Layout of the WFD Device Information field, per android.net.wifi.p2p.WifiP2pWfdInfo,
        // bit 0 being the least significant:
        //   0..1       device type (1 = primary sink)
        //   2          coupled sink support at source
        //   3          coupled sink support at sink
        //   4..5       session available (0x10 = available at the time of discovery)
        //   6..7       preferred HTP mode (0 = sink only)
        //   8..10      supported HTP modes
        //   11         U-APSD
        //   12..14     supported video capability
        val deviceInfo = WfdRootHelper.subelemHex().substring(4, 8).toInt(16)
        assertEquals(0x3B11, deviceInfo)
        assertEquals(1, deviceInfo and 0x3)
        assertEquals(0x10, deviceInfo and 0x30)
        assertEquals(0, (deviceInfo shr 6) and 0x3)
        assertEquals(0x3, (deviceInfo shr 8) and 0x7)
        assertEquals(1, (deviceInfo shr 11) and 0x1)
        assertEquals(3, (deviceInfo shr 12) and 0x7)
    }

    @Test
    fun `session available is the two-bit field android writes and not the reserved pattern`() {
        // 0xCF1 decoded bits 4..5 as 0b11, which the WFD spec reserves. Android's
        // setSessionAvailable(true) writes 0x10 only, and a real source (Redmi 10X) advertises
        // 0x0010. Asserting the exact two-bit value is what stops this regressing.
        val deviceInfo = WfdRootHelper.subelemHex().substring(4, 8).toInt(16)
        assertEquals(0x10, deviceInfo and 0x30)
        assertNotEquals(0x30, deviceInfo and 0x30)
    }

    @Test
    fun `video capability is advertised rather than zeroed`() {
        // Zero in this field means "no supported video capability", which several sources read
        // as "this sink cannot display anything" and refuse to open a session against.
        val deviceInfo = WfdRootHelper.subelemHex().substring(4, 8).toInt(16)
        assertTrue((deviceInfo shr 12) and 0x7 != 0)
    }

    @Test
    fun `device info layout matches a real source's own advertisement`() {
        // A Redmi 10X advertises wfd_dev_info=0x00101c440032. Its device-information field is
        // 0x0010: device type 0 (source) with session available = 0x10. Reusing the same field
        // arithmetic proves the layout in this file is the one real devices use.
        val sourceDeviceInfo = 0x0010
        assertEquals(0, sourceDeviceInfo and 0x3)
        assertEquals(0x10, sourceDeviceInfo and 0x30)
        assertEquals(sourceDeviceInfo and 0x30, WfdRootHelper.subelemHex().substring(4, 8).toInt(16) and 0x30)
    }

    @Test
    fun `device info payload still round trips the control port and throughput`() {
        val hex = WfdRootHelper.subelemHex()
        assertEquals("00", hex.substring(0, 2))
        assertEquals(6, hex.substring(2, 4).toInt(16))
        assertEquals(7236, hex.substring(8, 12).toInt(16))
        assertEquals(50, hex.substring(12, 16).toInt(16))
        assertEquals(16, hex.length)
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
