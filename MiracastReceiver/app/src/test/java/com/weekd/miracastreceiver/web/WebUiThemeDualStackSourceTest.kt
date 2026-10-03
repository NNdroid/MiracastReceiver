package com.weekd.miracastreceiver.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUiThemeDualStackSourceTest {
    private val index = File("src/main/assets/webui/index.html").readText()
    private val app = File("src/main/assets/webui/app.js").readText()
    private val css = File("src/main/assets/webui/styles.css").readText()
    private val server = File("src/main/java/com/weekd/miracastreceiver/web/WebUiServer.kt").readText()
    private val ports = File("src/main/java/com/weekd/miracastreceiver/utils/PortUtils.kt").readText()

    @Test
    fun webUiHasPersistentManualThemeModes() {
        assertTrue(index.contains("id=\"themeSelect\""))
        assertTrue(index.contains("localStorage.getItem('webuiTheme')"))
        assertTrue(app.contains("themePreference=localStorage.getItem('webuiTheme')"))
        assertTrue(app.contains("function applyTheme()"))
        assertTrue(css.contains("html[data-theme=\"light\"]"))
        assertTrue(css.contains("html[data-theme=\"dark\"]"))
        assertTrue(css.contains("prefers-color-scheme:light"))
    }

    @Test
    fun webUiSeparatesBrowserVerifiedAndLanAddressFamilies() {
        assertTrue(index.contains("id=\"deviceIpMeta\""))
        assertTrue(app.contains("s.device?.ipv4"))
        assertTrue(app.contains("s.device?.ipv6"))
        assertTrue(app.contains("location.hostname"))
        assertTrue(server.contains(".put(\"ipv4\", lan.ipv4.orEmpty())"))
        assertTrue(server.contains(".put(\"ipv6\", lan.ipv6.orEmpty())"))
        assertTrue(server.contains(".put(\"webUiBindAddress\""))
    }

    @Test
    fun listenerVerifiesIpv4InsteadOfAssumingIpv6WildcardIsDualStack() {
        assertTrue(ports.contains("probeTcp(IPV4_LOOPBACK, port)"))
        assertTrue(ports.contains("did not accept IPv4; rebinding with IPv4 priority"))
        assertTrue(ports.contains("verified IPv6 wildcard dual-stack"))
        assertTrue(ports.contains("separate IPv6 + IPv4 sockets"))
    }
}
