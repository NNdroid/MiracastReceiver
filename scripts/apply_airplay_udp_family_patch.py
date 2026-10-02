from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "MiracastReceiver/app"


def load(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8-sig")


def save(p, text):
    p.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

# 1) Deterministic UDP binder in PortUtils.
p, s = load("MiracastReceiver/app/src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt")
s = replace_once(s,
    "import java.net.InetSocketAddress\nimport java.net.ServerSocket",
    "import java.net.InetSocketAddress\nimport java.net.DatagramSocket\nimport java.net.ServerSocket",
    "PortUtils import")
anchor = "    /** Bind an available port, preserving the caller's preferred/fallback order. */\n"
insert = '''    /**\n     * Bind a UDP listener to the wildcard address of the actual peer's address family.\n     *\n     * Android/vendor kernels differ in the default IPV6_V6ONLY behavior of an unspecified\n     * DatagramSocket. Never rely on IPv4-mapped IPv6 here: an IPv4 RTSP peer gets 0.0.0.0 and\n     * an IPv6 RTSP peer gets ::. Unknown peers intentionally fall back to IPv4 for legacy RAOP.\n     */\n    fun bindDatagramSocketForPeer(port: Int, peerAddress: InetAddress?): DatagramSocket {\n        require(port in 1..65535) { "Invalid UDP port $port" }\n        val wildcard = if (peerAddress is Inet6Address) IPV6_WILDCARD else IPV4_WILDCARD\n        return DatagramSocket(null).apply {\n            reuseAddress = true\n            bind(InetSocketAddress(wildcard, port))\n        }.also { socket ->\n            val family = if (wildcard is Inet6Address) "IPv6" else "IPv4"\n            Timber.i("UDP listener bound on $family wildcard port $port for peer=${peerAddress?.hostAddress ?: "unknown"}")\n        }\n    }\n\n'''
if anchor not in s:
    raise SystemExit("PortUtils anchor missing")
s = s.replace(anchor, insert + anchor, 1)
save(p, s)

# 2) Tell AirPlayReceiver about the actual RTSP peer as soon as TCP connects.
p, s = load("MiracastReceiver/app/src/main/java/com/weekd/miracastreceiver/airplay/RtspHandler.kt")
s = replace_once(s,
    "    private val onStreamingStarted: (session: SessionDescription) -> Unit,\n    private val onStreamingStopped: () -> Unit,",
    "    private val onStreamingStarted: (session: SessionDescription) -> Unit,\n    private val onStreamingStopped: () -> Unit,\n    private val onPeerAddressKnown: (java.net.InetAddress) -> Unit = {},",
    "RtspHandler callback declaration")
s = replace_once(s,
    "        currentRemoteAddress = socket.inetAddress\n\n        try {",
    "        currentRemoteAddress = socket.inetAddress\n        onPeerAddressKnown(socket.inetAddress)\n\n        try {",
    "RtspHandler peer callback")
save(p, s)

# 3) Rebind legacy timing and RTP UDP to the actual RTSP peer family.
p, s = load("MiracastReceiver/app/src/main/java/com/weekd/miracastreceiver/airplay/AirPlayReceiver.kt")
s = replace_once(s,
    "    @Volatile private var audioSocket: DatagramSocket? = null\n",
    "    @Volatile private var audioSocket: DatagramSocket? = null\n    @Volatile private var legacyPeerAddress: java.net.InetAddress? = null\n",
    "AirPlay peer field")
s = replace_once(s,
    "    private fun startTimingHandler() {\n        timingHandler = TimingHandler().also { it.start(scope) }\n        Logger.d(\"Timing handler started on UDP port ${TimingHandler.TIMING_PORT}\")\n    }",
    "    private fun startTimingHandler() {\n        timingHandler = TimingHandler()\n        Logger.d(\"Timing handler ready; waiting for RTSP peer address before UDP bind\")\n    }",
    "AirPlay timing startup")
s = replace_once(s,
    "            onStreamingStarted = { session -> onStreamingStarted(session) },\n            onStreamingStopped = { onStreamingStopped() },",
    "            onStreamingStarted = { session -> onStreamingStarted(session) },\n            onStreamingStopped = { onStreamingStopped() },\n            onPeerAddressKnown = { peer ->\n                legacyPeerAddress = peer\n                timingHandler?.restartForPeer(scope, peer)\n                Logger.i(\"AirPlay RTSP peer=${peer.hostAddress}; UDP listeners use ${if (peer is java.net.Inet6Address) \"IPv6\" else \"IPv4\"}\")\n            },",
    "AirPlay peer wiring")
s = replace_once(s,
    "                val socket = DatagramSocket(AUDIO_RTP_PORT)\n                audioSocket = socket\n                Logger.i(\"Audio UDP receiver listening on port $AUDIO_RTP_PORT\")",
    "                val peer = legacyPeerAddress\n                val socket = PortUtils.bindDatagramSocketForPeer(AUDIO_RTP_PORT, peer)\n                audioSocket = socket\n                Logger.i(\"Audio UDP receiver listening on port $AUDIO_RTP_PORT family=${if (peer is java.net.Inet6Address) \"IPv6\" else \"IPv4\"}\")",
    "AirPlay audio UDP bind")
s = replace_once(s,
    "        audioSocket = null\n        mirrorServer?.stop()",
    "        audioSocket = null\n        legacyPeerAddress = null\n        mirrorServer?.stop()",
    "AirPlay peer clear")
save(p, s)

# 4) Timing socket: close/rebind when a new RTSP peer family is known.
p, s = load("MiracastReceiver/app/src/main/java/com/weekd/miracastreceiver/airplay/TimingHandler.kt")
s = replace_once(s,
    "import com.weekd.miracastreceiver.util.Logger\n",
    "import com.weekd.miracastreceiver.util.Logger\nimport com.weekd.miracastreceiver.utils.PortUtils\n",
    "TimingHandler import")
s = replace_once(s,
    "import java.net.DatagramSocket\n",
    "import java.net.DatagramSocket\nimport java.net.InetAddress\nimport java.net.Inet6Address\n",
    "TimingHandler net imports")
s = replace_once(s,
    "    fun start(scope: CoroutineScope, port: Int = TIMING_PORT) {\n        scope.launch(Dispatchers.IO) {\n            runLoop(this, port)\n        }\n    }",
    '''    fun start(scope: CoroutineScope, port: Int = TIMING_PORT) {\n        startForPeer(scope, null, port)\n    }\n\n    /** Rebind timing UDP to the same address family as the active RTSP control peer. */\n    fun restartForPeer(scope: CoroutineScope, peerAddress: InetAddress, port: Int = TIMING_PORT) {\n        stop()\n        startForPeer(scope, peerAddress, port)\n    }\n\n    private fun startForPeer(scope: CoroutineScope, peerAddress: InetAddress?, port: Int) {\n        scope.launch(Dispatchers.IO) {\n            runLoop(this, port, peerAddress)\n        }\n    }''',
    "TimingHandler start API")
s = replace_once(s,
    "    private fun runLoop(scope: CoroutineScope, port: Int) {\n        try {\n            // Use a local val to avoid repeated null-checks on the @Volatile field\n            val sock = DatagramSocket(port)\n            socket = sock\n            Logger.i(\"Timing handler listening on UDP port $port\")",
    '''    private fun runLoop(scope: CoroutineScope, port: Int, peerAddress: InetAddress?) {\n        try {\n            // Bind explicitly to the RTSP peer family; never rely on vendor IPV6_V6ONLY defaults.\n            val sock = PortUtils.bindDatagramSocketForPeer(port, peerAddress)\n            socket = sock\n            Logger.i("Timing handler listening on UDP port $port family=${if (peerAddress is Inet6Address) "IPv6" else "IPv4"}")''',
    "TimingHandler bind")
save(p, s)

# 5) Version bump.
p, s = load("MiracastReceiver/app/build.gradle.kts")
s = replace_once(s, '        versionCode = 32\n        versionName = "1.9.10"', '        versionCode = 33\n        versionName = "1.9.11"', "version bump")
save(p, s)

# 6) Source regression coverage.
test = APP / "src/test/java/com/weekd/miracastreceiver/airplay/AirPlayUdpAddressFamilySourceTest.kt"
test.write_text('''package com.weekd.miracastreceiver.airplay\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.io.File\n\nclass AirPlayUdpAddressFamilySourceTest {\n    private val receiver = File("src/main/java/com/weekd/miracastreceiver/airplay/AirPlayReceiver.kt").readText()\n    private val timing = File("src/main/java/com/weekd/miracastreceiver/airplay/TimingHandler.kt").readText()\n    private val rtsp = File("src/main/java/com/weekd/miracastreceiver/airplay/RtspHandler.kt").readText()\n    private val ports = File("src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt").readText()\n\n    @Test fun audioRtpUsesExplicitPeerFamilyBinder() {\n        assertTrue(receiver.contains("bindDatagramSocketForPeer(AUDIO_RTP_PORT, peer)"))\n        assertFalse(receiver.contains("DatagramSocket(AUDIO_RTP_PORT)"))\n        assertTrue(receiver.contains("legacyPeerAddress"))\n    }\n\n    @Test fun timingRebindsWhenRtspPeerIsKnown() {\n        assertTrue(rtsp.contains("onPeerAddressKnown(socket.inetAddress)"))\n        assertTrue(receiver.contains("timingHandler?.restartForPeer(scope, peer)"))\n        assertTrue(timing.contains("PortUtils.bindDatagramSocketForPeer(port, peerAddress)"))\n        assertFalse(timing.contains("DatagramSocket(port)"))\n    }\n\n    @Test fun udpBinderNeverUsesUnspecifiedJavaWildcard() {\n        assertTrue(ports.contains("fun bindDatagramSocketForPeer"))\n        assertTrue(ports.contains("peerAddress is Inet6Address"))\n        assertTrue(ports.contains("IPV6_WILDCARD"))\n        assertTrue(ports.contains("IPV4_WILDCARD"))\n    }\n}\n''', encoding="utf-8")

# Remove this one-shot machinery from the final branch.
(ROOT / "scripts/apply_airplay_udp_family_patch.py").unlink(missing_ok=True)
(ROOT / ".github/workflows/apply-airplay-udp-family-patch.yml").unlink(missing_ok=True)
print("AirPlay UDP family patch applied")
