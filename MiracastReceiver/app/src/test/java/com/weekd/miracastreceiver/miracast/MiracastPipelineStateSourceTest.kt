package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiracastPipelineStateSourceTest {
    private val source = File("src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt").readText()

    @Test
    fun pipelineReportsObservedStagesInsteadOfRtspOnlyStreaming() {
        assertTrue(source.contains("WAITING_RTP"))
        assertTrue(source.contains("RTP_ACTIVE"))
        assertTrue(source.contains("WAITING_SURFACE"))
        assertTrue(source.contains("WAITING_IDR"))
        assertTrue(source.contains("DISPLAYING"))
        assertTrue(source.contains("refreshPipelineState"))
    }

    @Test
    fun pipelineStateIsPublishedToRuntimeStatus() {
        assertTrue(source.contains("RuntimeState.miracastState = state"))
        assertTrue(source.contains("RuntimeState.lastError = \"Miracast RTP:"))
    }
}
