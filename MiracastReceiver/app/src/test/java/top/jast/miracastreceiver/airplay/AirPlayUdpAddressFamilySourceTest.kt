package top.jast.miracastreceiver.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirPlayUdpAddressFamilySourceTest {
    private val receiver = File("src/main/java/top/jast/miracastreceiver/airplay/AirPlayReceiver.kt").readText()
    private val timing = File("src/main/java/top/jast/miracastreceiver/airplay/TimingHandler.kt").readText()
    private val rtsp = File("src/main/java/top/jast/miracastreceiver/airplay/RtspHandler.kt").readText()
    private val ports = File("src/main/java/top/jast/miracastreceiver/utils/PortUtils.kt").readText()

    @Test fun audioRtpUsesExplicitPeerFamilyBinder() {
        assertTrue(receiver.contains("bindDatagramSocketForPeer(AUDIO_RTP_PORT, peer)"))
        assertFalse(receiver.contains("DatagramSocket(AUDIO_RTP_PORT)"))
        assertTrue(receiver.contains("legacyPeerAddress"))
    }

    @Test fun timingRebindsWhenRtspPeerIsKnown() {
        assertTrue(rtsp.contains("onPeerAddressKnown(socket.inetAddress)"))
        assertTrue(receiver.contains("timingHandler?.restartForPeer(scope, peer)"))
        assertTrue(timing.contains("PortUtils.bindDatagramSocketForPeer(port, peerAddress)"))
        assertFalse(timing.contains("DatagramSocket(port)"))
    }

    @Test fun udpBinderNeverUsesUnspecifiedJavaWildcard() {
        assertTrue(ports.contains("fun bindDatagramSocketForPeer"))
        assertTrue(ports.contains("peerAddress is Inet6Address"))
        assertTrue(ports.contains("IPV6_WILDCARD"))
        assertTrue(ports.contains("IPV4_WILDCARD"))
    }
}
