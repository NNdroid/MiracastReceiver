package com.weekd.miracastreceiver.airplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirPlayDualStackSourceTest {
    private val receiver = File("src/main/java/com/weekd/miracastreceiver/airplay/AirPlayReceiver.kt").readText()
    private val rtsp = File("src/main/java/com/weekd/miracastreceiver/airplay/RtspHandler.kt").readText()
    private val mirror = File("src/main/java/com/weekd/miracastreceiver/airplay/handshake/MirrorStreamServer.kt").readText()
    private val buffered = File("src/main/java/com/weekd/miracastreceiver/airplay/handshake/BufferedAudioServer.kt").readText()
    private val ports = File("src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt").readText()

    @Test
    fun rtspAndEventListenersUseDeterministicDualStackBinding() {
        assertTrue(rtsp.contains("PortUtils.bindFixedServerSocket(RTSP_PORT)"))
        assertTrue(receiver.contains("PortUtils.bindEphemeralServerSocket()"))
    }

    @Test
    fun dynamicAirPlayTcpStreamsUseDualStackEphemeralPorts() {
        assertTrue(mirror.contains("PortUtils.bindEphemeralServerSocket()"))
        assertTrue(buffered.contains("PortUtils.bindEphemeralServerSocket()"))
        assertTrue(ports.contains("fun bindEphemeralServerSocket"))
    }

    @Test
    fun realtimeAudioUdpPathIsExplicitlyIpv6WildcardCapable() {
        val audio = File("src/main/java/com/weekd/miracastreceiver/airplay/handshake/AudioStreamServer.kt").readText()
        assertTrue(audio.contains("InetAddress.getByName(\"::\")"))
    }
}
