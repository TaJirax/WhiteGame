package com.whitegame.app.data

import com.whitegame.app.model.SubNode
import org.junit.Assert.*
import org.junit.Test

class SubscriptionScanTest {
    private fun node(id: String = "test", conf: String = "", type: String = "raw") =
        SubNode(id, id, "example.com", 443, type, conf = conf)

    private val wireguard = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.7.0.2/32
        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = example.com:443
    """.trimIndent()

    @Test fun `WireGuard uses authenticated HTTPS and never a TCP endpoint probe`() {
        assertEquals(SubscriptionScan.Probe.WIREGUARD, SubscriptionScan.probeFor(node(conf = wireguard)))
        val outbound = SubscriptionScan.wireGuardParsed(node(conf = wireguard, type = "wireguard")).outbound
        assertEquals("wireguard", outbound.getString("protocol"))
        assertEquals("example.com:443", outbound.getJSONObject("settings").getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals(SubscriptionScan.Probe.TUNNEL, SubscriptionScan.probeFor(node(conf = "[Interface]")))
        assertEquals(SubscriptionScan.Probe.TUNNEL, SubscriptionScan.probeFor(node(type = "wireguard")))
        assertEquals(SubscriptionScan.Probe.PROXY, SubscriptionScan.probeFor(node(conf = "vless://id@example.com:443")))
        assertEquals(SubscriptionScan.Probe.TCP, SubscriptionScan.probeFor(node()))
    }

    @Test fun `fastest measured node wins even when quality scores tie`() {
        val slow = node("slow").copy(pingMs = 28, lossPct = 0, score = 100)
        val fast = node("fast").copy(pingMs = 3, lossPct = 0, score = 100)
        val flaky = node("flaky").copy(pingMs = 1, lossPct = 66)
        assertEquals(listOf("fast", "slow", "flaky", "unmeasured"),
            SubscriptionScan.rank(listOf(node("unmeasured"), slow, flaky, fast)).map { it.id })
    }

    @Test fun `core errors stay visible and negative delay is not success`() {
        val failed = SubscriptionScan.proxyResult(node(), -1, "TLS handshake failed")
        assertNull(failed.pingMs)
        assertEquals(100, failed.lossPct)
        assertEquals("TLS handshake failed", failed.status)
        val verified = SubscriptionScan.proxyResult(node(), 15)
        assertEquals(15L, verified.pingMs)
        assertEquals("Proxy HTTPS", verified.status)
    }
    @Test fun `obfuscated and split WireGuard routes remain explicitly unmeasured`() {
        val awg = wireguard.replace("Address =", "Jc = 4\nAddress =")
        val split = wireguard.replace("0.0.0.0/0, ::/0", "10.0.0.0/8")
        assertEquals(SubscriptionScan.Probe.TUNNEL, SubscriptionScan.probeFor(node(conf = awg)))
        assertEquals(SubscriptionScan.Probe.TUNNEL, SubscriptionScan.probeFor(node(conf = split)))
        val multiple = wireguard + "\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nAllowedIPs = 10.0.0.0/8\nEndpoint = other.example.com:2408"
        assertEquals(SubscriptionScan.Probe.TUNNEL, SubscriptionScan.probeFor(node(conf = multiple)))
        assertTrue(SubscriptionScan.tunnelStatus(node(conf = awg)).contains("AmneziaWG"))
        assertTrue(SubscriptionScan.tunnelStatus(node(conf = split)).contains("full WireGuard"))
        val measured = SubscriptionScan.proxyResult(node(), 20, successLabel = "WireGuard HTTPS")
        assertEquals("WireGuard HTTPS", measured.status)
    }

}
