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
}
