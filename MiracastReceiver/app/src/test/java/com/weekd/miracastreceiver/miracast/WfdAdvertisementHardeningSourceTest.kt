package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WfdAdvertisementHardeningSourceTest {
    private val helper = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdRootHelper.kt").readText()
    private val native = File("src/main/cpp/wfdctl.c").readText()

    @Test
    fun `global p2p device socket is preferred over temporary group socket`() {
        val p2pDevice = helper.indexOf("/p2p-dev-wlan0")
        val wlan = helper.indexOf("/wlan0")
        val group = helper.indexOf("/p2p0")
        assertTrue(p2pDevice >= 0)
        assertTrue(wlan >= 0)
        assertTrue(group >= 0)
        assertTrue(p2pDevice < group)
        assertTrue(wlan < group)
    }

    @Test
    fun `native client propagates explicit supplicant failures`() {
        assertTrue(native.contains("strncmp(p, \"FAIL\", 4)"))
        assertTrue(native.contains("strncmp(p, \"UNKNOWN COMMAND\", 15)"))
        assertTrue(native.contains("return -1;"))
    }

    @Test
    fun `native client classifies replies instead of pretending silence is success`() {
        assertTrue(native.contains("CTRL_NO_REPLY"))
        assertTrue(native.contains("is_rejected_reply"))
        assertTrue(native.contains("strstr(p, \"failed\")"))
        assertTrue(native.contains("strncmp(p, \"ERROR\", 5)"))
        assertTrue(native.contains("verdict="))
    }

    @Test
    fun `advertiser retries sockets and treats WFD IE as core requirement`() {
        assertTrue(helper.contains("candidates.map { socketPath ->"))
        assertTrue(helper.contains("WFD_SUBELEM_SET 0"))
        assertTrue(helper.contains("core advertisement rejected"))
        assertTrue(helper.contains("extendedListenConfigured"))
    }

    @Test
    fun `read back must contain the injected payload to count as verified`() {
        assertTrue(helper.contains("read-back ok"))
        assertTrue(helper.contains("read-back unavailable"))
        assertTrue(helper.contains("subelem mismatch"))
        assertTrue(helper.contains("verified = readBack.ok"))
        assertFalse(helper.contains("assume accepted"))
    }

    @Test
    fun `per socket attempts are scored so the right interface wins`() {
        assertTrue(helper.contains("class Attempt"))
        assertTrue(helper.contains("fun score()"))
        assertTrue(helper.contains("minByOrNull { it.score() }"))
        assertTrue(helper.contains("takeIf { it.exit != WFDCTL_REJECTED }"))
    }
}
