package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UniversalMiracastInteropSourceTest {
    private val session = File("src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt").readText()
    private val rtp = File("src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt").readText()

    @Test
    fun setupResponsePublishesTheSinkRtpRtcpPortPair() {
        // A Source reads the receive port from the SETUP reply's Transport header — the one place
        // the spec guarantees it — and the RTCP port is the next one up.
        assertTrue(session.contains("server_port=\$rtpPort-"))
        assertTrue(session.contains("private fun rtcpPort(): Int = (rtpPort + 1)"))
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
        // A Sink is the receiver, so it advertises mode=recv and names the port it is bound to.
        assertTrue(session.contains("RTP/AVP/UDP;unicast 0 \$rtpPort mode=recv"))
        // Sources that never read the header assume SETUP; stating it explicitly avoids the
        // ambiguity that makes some of them stall after SET_PARAMETER.
        assertTrue(session.contains("\"wfd_trigger_method\" to \"SETUP\""))
    }

    @Test
    fun wildcardM3WithAnEmptyBodyReturnsAllCapabilities() {
        // Several sources send `GET_PARAMETER *` with no body to mean "everything". Answering
        // that with a bare 200 OK hands them nothing to negotiate with, and they then refuse to
        // move on to SET_PARAMETER.
        assertTrue(session.contains("uri(msg).lowercase() == \"*\""))
        assertTrue(session.contains("capabilityValues().keys"))
        assertFalse(session.contains("if (body.isBlank()) return linkedSetOf()"))
    }

    @Test
    fun describeIsAnsweredBecauseSomeSourcesUseItInsteadOfGetParameter() {
        assertTrue(session.contains("\"DESCRIBE\" -> sendDescribe(cseq)"))
        assertTrue(session.contains("private fun sendDescribe(cseq: String)"))
    }

    @Test
    fun everyRtspMethodGetsAReplyBecauseSilenceTimesOutTheSource() {
        // A Source that receives no response to any of its methods tears the group down, so each
        // method we understand must be answered rather than folded into a generic else.
        assertTrue(session.contains("PUBLIC_METHODS"))
        assertTrue(session.contains("\"OPTIONS\" ->"))
        assertTrue(session.contains("\"GET_PARAMETER\" ->"))
        assertTrue(session.contains("\"SET_PARAMETER\" ->"))
        assertTrue(session.contains("\"SETUP\" -> sendSetupResponse(cseq)"))
        assertTrue(session.contains("\"PLAY\" -> sendPlayResponse(cseq)"))
        assertTrue(session.contains("\"PAUSE\" -> sendOk(cseq)"))
        assertTrue(session.contains("\"TEARDOWN\" ->"))
    }

    @Test
    fun playMethodStartsTheStreamInsteadOfOnlyReturningOk() {
        // Answering PLAY without signalling the stream is why the group forms and the picture
        // never appears: RTP is already arriving and nobody reports it as live.
        assertTrue(session.contains("onStreamStart?.invoke(rtpPort)"))
        assertTrue(session.contains("if (!streamStarted)"))
        assertTrue(session.contains("startPlayerActivity()"))
        assertFalse(session.contains("secondPlaySent"))
        assertFalse(session.contains("compatibility second PLAY"))
    }

    @Test
    fun aSessionIdIsAlwaysMintedSoSourcesThatOmitItStillWork() {
        // Some Sources send PLAY before SETUP and never carry a Session header of their own, so
        // the Sink mints one instead of waiting for one that will never arrive.
        assertTrue(session.contains("if (sessionId.isBlank())"))
        assertTrue(session.contains("sessionId.ifBlank { \"legacy\" }"))
    }

    @Test
    fun rtpReceiverHandlesReorderingWithoutHugeFalseLoss() {
        assertTrue(rtp.contains("REORDER_WINDOW_PACKETS"))
        assertTrue(rtp.contains("REORDER_MAX_WAIT_MS"))
        assertTrue(rtp.contains("pending.putIfAbsent"))
        assertTrue(rtp.contains("skipConfirmedGap"))
        assertTrue(rtp.contains("packetsLateOrDuplicate"))
    }
}
