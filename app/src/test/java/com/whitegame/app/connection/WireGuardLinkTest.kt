package com.whitegame.app.connection

import com.whitegame.app.parser.ConfigParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class WireGuardLinkTest {
    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val publicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun link(extra: String = "", endpoint: String = "engage.cloudflareclient.com:2408") =
        "wireguard://${encoded(privateKey)}@$endpoint?publickey=${encoded(publicKey)}" +
            "&address=172.16.0.2%2F32$extra#${encoded("CF · WARP · AWG2")}"

    @Test fun importsReportedWarpAwg2ShapeAndRoundTripsAllSettings() {
        val c = AwgConfig.parse(link("&reserved=49%2C132%2C132&mtu=1380" +
            "&dns=1.1.1.1%2C1.0.0.1&address6=2606%3A4700%3A110%3A80a9%3A%3A1" +
            "&awg=2&jc=4&jmin=57&jmax=1175&h1=1&h2=2&h3=3&h4=4&cpa=27-81" +
            "&i1=%3Cb+0xce00000001%3E%3Crc+13%3E%3Ct%3E%3Cr+63%3E" +
            "&i2=%3Cb+0xf6ab3267fa%3E%3Ct%3E%3Crd+10%3E%3Cr+64%3E"))
        assertEquals(privateKey, c.iface.privateKey)
        assertEquals(publicKey, c.peers.single().publicKey)
        assertEquals(listOf(49, 132, 132), c.peers.single().reserved)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:80a9::1/128"), c.iface.addresses)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), c.iface.dnsServers)
        assertEquals(1380, c.iface.mtu)
        assertEquals("27-81", c.iface.amnezia["contentpaddingaddition"])
        assertEquals("<b 0xce00000001><rc 13><t><r 63>", c.iface.amnezia["i1"])
        assertEquals("<b 0xf6ab3267fa><t><rd 10><r 64>", c.iface.amnezia["i2"])
        assertEquals(TunnelKind.AMNEZIA, c.kind)
        assertEquals(c, AwgConfig.parse(c.toConfText()))
        val nativeCopy = c.copy(peers = c.peers.map { it.copy(reserved = emptyList()) })
        val uapi = nativeCopy.toUapi { java.net.InetAddress.getByName("203.0.113.7") }
        assertTrue("content_padding_addition=27-81" in uapi.lines())
        assertTrue("i1=<b 0xce00000001><rc 13><t><r 63>" in uapi.lines())
    }

    @Test fun supportsLiteralPlusKeysAndBracketedIpv6Endpoints() {
        val raw = "wg://$privateKey@[2001:db8::1]:51820?publickey=$publicKey&address=10.0.0.2&keepalive=25"
        val c = AwgConfig.parse(raw)
        assertEquals(privateKey, c.iface.privateKey)
        assertEquals(publicKey, c.peers.single().publicKey)
        assertEquals("[2001:db8::1]:51820", c.primaryEndpoint)
        assertEquals(listOf("10.0.0.2/32"), c.iface.addresses)
        assertEquals("25", c.peers.single().persistentKeepalive)
        assertTrue(c.isFullTunnel)
    }

    @Test fun diagnosticParserUsesTheUriEndpointAndDecodedNameBeforeWarpDetection() {
        val nodes = ConfigParser.parseAll(link(endpoint = "198.51.100.9:4500"))
        assertEquals(1, nodes.size)
        assertEquals("198.51.100.9", nodes.single().host)
        assertEquals(4500, nodes.single().port)
        assertEquals("CF · WARP · AWG2", nodes.single().name)
    }

    @Test fun rejectsInvalidLinksAndUnsupportedTransportOptions() {
        val cases = listOf(
            link().replace("publickey=", "missing="),
            link("&reserved=1,2"), link("&reserved=1,2,256"),
            link("&awg=99"), link("&fakeSni=example.com"),
            link("&jc=4&jc=5"), link("&dns=1.1.1.1%0AEndpoint=evil:80"),
            link("&i1=%ZZ"), link("&reserved=1,2,3&s1=16"),
            link("&reserved=1,2,3&h1=90"),
            link("&reserved=1,2,3&randomTrailers=on"),
            link("&reserved=1,2,3&headerProtectionKey=${encoded(privateKey)}")
        )
        for (raw in cases) {
            try { AwgConfig.parse(raw); fail("Expected invalid link to be rejected") }
            catch (_: ConfigException) { }
        }
    }

    @Test fun reservedAllowsExplicitlyDisabledRandomTrailers() {
        val c = AwgConfig.parse(link("&reserved=1,2,3&randomTrailers=off"))
        assertEquals(listOf(1, 2, 3), c.peers.single().reserved)
    }

    @Test fun reservedCannotSilentlyDisappearFromNativeConfig() {
        val c = AwgConfig.parse(link("&reserved=49,132,132"))
        try { c.toUapi(); fail("Reserved needs a transport adapter") }
        catch (e: ConfigException) { assertTrue(e.message!!.contains("adapter")) }
    }

    @Test fun clipboardImportsRecognizeLinksAlongsideIniAndXray() {
        val ini = AwgConfig.parse(link()).toConfText()
        val raw = "$ini\n${link()}\nvless://id@edge.example.com:443#Edge"
        val chunks = TunnelStore.splitConfigs(raw)
        assertEquals(3, chunks.size)
        assertEquals(ini, chunks.first())
        assertEquals(link(), chunks[1])
        assertEquals("CF · WARP · AWG2", AwgConfig.shareLinkName(chunks[1]))
        assertTrue(chunks.last().startsWith("vless://"))
    }
}
