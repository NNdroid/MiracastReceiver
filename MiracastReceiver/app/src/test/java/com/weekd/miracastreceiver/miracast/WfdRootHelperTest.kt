package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class WfdRootHelperTest {
    @Test
    fun `primary sink device info advertises session and port 7236`() {
        assertEquals("000600111c440032", WfdRootHelper.subelemHex(7236))
    }

    @Test
    fun `device info encodes custom control port`() {
        assertEquals("000600111f900032", WfdRootHelper.subelemHex(8080))
    }
}
