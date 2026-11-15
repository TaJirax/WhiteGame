package com.whitegame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A subscription is only useful if what comes back can become a tunnel. These cover the two
 * text shapes that need no Android JSON: plain .conf blocks, and a list that is nothing but
 * host:port and therefore can only be pinged. Xray share-link lists are covered too: those
 * used to fall through to the host:port parser, which dropped the link and left every node
 * ping-only.
 */
class SubscriptionParseTest {

    private val twoConfigs = """
        [Interface]
        # Name = Frankfurt
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.7.0.2/32

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = de1.example.com:51820

        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.7.0.3/32

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 51.15.1.2:443
    """.trimIndent()

    @Test fun `each Interface block becomes a node that carries its own config`() {
        val nodes = ConfigSubscription.parse(twoConfigs)
        assertEquals(2, nodes.size)
        assertEquals("Frankfurt", nodes[0].name)
        assertEquals("de1.example.com", nodes[0].host)
        assertEquals(51820, nodes[0].port)
        assertEquals("51.15.1.2", nodes[1].host)
        assertEquals(443, nodes[1].port)
        // The config travels with the node, or "Add" would have nothing to save.
        assertTrue(nodes.all { it.conf.contains("[Peer]") && it.conf.contains("PrivateKey") })
    }

    @Test fun `a bare host-port list is ping-only, never offered as a tunnel`() {
        val nodes = ConfigSubscription.parse("de1.example.com:51820\n51.15.1.2:443\nnot a server")
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.conf.isEmpty() })
    }

    @Test fun `junk parses to nothing rather than to fake servers`() {
        assertTrue(ConfigSubscription.parse("404: Not Found").isEmpty())
        assertTrue(ConfigSubscription.parse("").isEmpty())
    }

    @Test fun `standard base64 with plus and slash decodes`() {
        // The leading comment encodes to "IyA/Pz8+Pj4K": '+' and '/' up front, which the old
        // URL_SAFE decoding rejected.
        val encoded = java.util.Base64.getEncoder().encodeToString(("# ???>>>\n" + twoConfigs).toByteArray())
        assertTrue(encoded.contains('+') && encoded.contains('/'))
        assertEquals(2, ConfigSubscription.parse(encoded).size)
    }

    @Test fun `base64 share-link list is named from the fragment and keeps its config`() {
        val links = "vless://uuid@edge.example.com:443?security=tls#BlueKnight-VLESS-443\n" +
            "trojan://pw@1.2.3.4:2053?security=tls#BlueKnight-Trojan-2053"
        val nodes = ConfigSubscription.parse(java.util.Base64.getEncoder().encodeToString(links.toByteArray()))
        assertEquals(2, nodes.size)
        assertEquals("BlueKnight-VLESS-443", nodes[0].name)
        assertEquals("edge.example.com", nodes[0].host)
        assertEquals("trojan", nodes[1].type)
        // These used to come back with an empty conf, which made them ping-only: not
        // importable, and rankable only by a TCP connect a middlebox can answer itself.
        assertTrue(nodes.none { it.conf.isEmpty() })
    }

    private val shareLinks = """
        vless://11111111-2222-3333-4444-555555555555@62.210.91.2:23576?encryption=none&security=none&type=tcp#%40WhiteDNS%20%7C%20FR233
        trojan://s3cret@51.158.206.25:443?security=tls&type=tcp#%40WhiteDNS%20%7C%20NL543
    """.trimIndent()

    @Test fun `share links keep their config instead of degrading to ping-only`() {
        val nodes = ConfigSubscription.parse(shareLinks)
        assertEquals(2, nodes.size)
        assertEquals("62.210.91.2", nodes[0].host)
        assertEquals(23576, nodes[0].port)
        assertEquals("vless", nodes[0].type)
        assertEquals("51.158.206.25", nodes[1].host)
        assertEquals(443, nodes[1].port)
        // The point of the branch: a node that still carries its link can be measured
        // through the proxy and imported, rather than ranked by a bare TCP connect.
        assertTrue(nodes[0].conf.startsWith("vless://"))
        assertTrue(nodes[1].conf.startsWith("trojan://"))
    }
    @Test fun `BOM and URL-safe wrapped subscriptions retain every link`() {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(shareLinks.toByteArray())
        assertEquals(2, ConfigSubscription.parse("\uFEFF" + encoded.chunked(30).joinToString("\n")).size)
    }

    @Test fun `mixed WireGuard configs links and bare endpoints all survive`() {
        val nodes = ConfigSubscription.parse(twoConfigs + "\n" + shareLinks + "\nraw.example.com:8443")
        assertEquals(5, nodes.size)
        assertEquals(2, nodes.count { it.conf.startsWith("[Interface]") })
        assertEquals(2, nodes.count { it.conf.startsWith("vless://") || it.conf.startsWith("trojan://") })
        assertEquals(1, nodes.count { it.conf.isBlank() })
        assertTrue(nodes.filter { it.conf.startsWith("[Interface]") }.none { it.conf.contains("trojan://") })
    }

    @Test fun `Xray JSON array keeps each outbound importable`() {
        val first = com.whitegame.app.xray.XrayConfig.parse(shareLinks.lines()[0]).outbound
        val second = com.whitegame.app.xray.XrayConfig.parse(shareLinks.lines()[1]).outbound
        val json = org.json.JSONObject().put("outbounds", org.json.JSONArray().put(first).put(second))
        val nodes = ConfigSubscription.parse("[" + json.toString() + "]")
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { com.whitegame.app.xray.XrayConfig.parse(it.conf).host == it.host })
    }

    @Test fun `malformed links HTML and out-of-range ports cannot become fake endpoints`() {
        assertTrue(ConfigSubscription.parse("vless://@edge.example.com:443").isEmpty())
        assertTrue(ConfigSubscription.parse("unknown://user@edge.example.com:443").isEmpty())
        assertTrue(ConfigSubscription.parse("<a href='https://edge.example.com:443'>login</a>").isEmpty())
        assertTrue(ConfigSubscription.parse("edge.example.com:65536\nedge.example.com:0").isEmpty())
        assertTrue(ConfigSubscription.parse("{broken json").isEmpty())
    }

    @Test fun `base64 bare endpoints support IPv6 and every valid port`() {
        val text = "[2001:db8::1]:9 # IPv6\nexample.com:65535"
        val nodes = ConfigSubscription.parse(java.util.Base64.getEncoder().encodeToString(text.toByteArray()))
        assertEquals(2, nodes.size)
        assertEquals("2001:db8::1", nodes[0].host)
        assertEquals(9, nodes[0].port)
        assertEquals(65535, nodes[1].port)
    }

    @Test fun `sing-box WireGuard preserves DNS reserved bytes and IPv6 endpoint`() {
        val json = """{
            "endpoints": [{"type":"wireguard", "tag":"WARP",
                "private_key":"yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=",
                "address":["172.16.0.2/32"], "dns":["1.1.1.1","1.0.0.1"],
                "peers":[{"address":"2001:db8::1", "port":2408,
                    "public_key":"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=",
                    "reserved":[49,132,132]}]}]
        }"""
        val root = org.json.JSONObject(json)
        val malformed = org.json.JSONObject(root.getJSONArray("endpoints").getJSONObject(0).toString())
        malformed.getJSONArray("peers").getJSONObject(0).put("reserved", org.json.JSONArray().put("invalid"))
        root.getJSONArray("endpoints").put(malformed)
        val nodes = ConfigSubscription.parse(root.toString())
        assertEquals(1, nodes.size)
        val parsed = com.whitegame.app.connection.AwgConfig.parse(nodes.single().conf)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), parsed.iface.dnsServers)
        assertEquals(listOf(49,132,132), parsed.peers.single().reserved)
        assertEquals("[2001:db8::1]:2408", parsed.primaryEndpoint)
    }

    @Test fun `WireGuard share links stay importable beside Xray links`() {
        val privateKey = java.net.URLEncoder.encode("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "UTF-8")
        val publicKey = java.net.URLEncoder.encode("xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=", "UTF-8")
        val wg = "wireguard://$privateKey@engage.cloudflareclient.com:2408?publickey=$publicKey&address=172.16.0.2%2F32#Warp"
        val nodes = ConfigSubscription.parse(wg + "\n" + shareLinks)
        assertEquals(3, nodes.size)
        assertEquals("Warp", nodes[0].name)
        assertTrue(nodes[0].conf.startsWith("[Interface]"))
    }

}
