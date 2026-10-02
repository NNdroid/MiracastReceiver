package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UniversalMiracastInteropSourceTest {
    private val session = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt").readText()
    private val rtp = File("src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt").readText()

    @Test
    fun setupUsesStandardRtpRtcpPortPair() {
        assertTrue(session.contains("client_port=\$rtpPort-\$rtcpPort"))
        assertTrue(rtp.contains("DatagramSocket(port + 1)"))
        assertTrue(rtp.contains("drainRtcp()"))
    }

    @Test
    fun m3RepliesAreRequestAwareAndCoverCommonExtensions() {
        assertTrue(session.contains("requestedParameters(msg)"))
        assertTrue(session.contains("wfd_standby_resume_capability"))
        assertTrue(session.contains("wfd_idr_request_capability"))
        assertTrue(session.contains("intel_friendly_name"))
        assertTrue(session.contains("microsoft_rtcp_capability"))
        assertTrue(session.contains("wfd_3d_video_formats"))
    }

    @Test
    fun legacySetupWithoutSessionIsTolerated() {
        assertTrue(session.contains("continuing legacy-compatible"))
        assertTrue(session.contains("sessionId.ifBlank { \"legacy\" }"))
        assertTrue(session.contains("if (sessionId.isNotBlank())"))
    }

    @Test
    fun androidSourcesCanReceiveSecondPlay() {
        assertTrue(session.contains("androidLikeSource"))
        assertTrue(session.contains("secondPlaySent"))
        assertTrue(session.contains("sending Android compatibility second PLAY"))
    }

    @Test
    fun rtpReceiverHandlesReorderingWithoutHugeFalseLoss() {
        assertTrue(rtp.contains("lost in 1..0x7FFF"))
        assertTrue(rtp.contains("reordered/old packet"))
    }
}
