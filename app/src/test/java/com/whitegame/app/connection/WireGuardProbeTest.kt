package com.whitegame.app.connection

import org.junit.Assert.*
import org.junit.Test

class WireGuardProbeTest {
    private fun config() = AwgConfig.parse("""
        [Interface]
        PrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        Address = 172.16.0.2/32
        [Peer]
        PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
        Endpoint = engage.cloudflareclient.com:2408
        AllowedIPs = 0.0.0.0/0, ::/0
        Reserved = 49, 132, 132
    """.trimIndent())

    @Test fun authenticatedOutboundPreservesKeysReservedAndCandidateEndpoint() {
        val config = config()
        val parsed = WireGuardProbe.parsed(config, "162.159.192.1:500")
        val settings = parsed.outbound.getJSONObject("settings")
        assertEquals("wireguard", parsed.protocol)
        assertEquals("udp", parsed.network)
        assertEquals(config.iface.privateKey, settings.getString("secretKey"))
        assertTrue(settings.getBoolean("noKernelTun"))
        assertEquals("[49,132,132]", settings.getJSONArray("reserved").toString())
        assertEquals("162.159.192.1:500", settings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals("engage.cloudflareclient.com:2408", config.primaryEndpoint)
    }

    @Test fun obfuscationAndSplitRoutesCannotBeSilentlyDiscarded() {
        val config = config()
        assertTrue(runCatching { WireGuardProbe.parsed(config.copy(iface = config.iface.copy(amnezia = mapOf("jc" to "4"))), "host:123") }.isFailure)
        assertTrue(runCatching { WireGuardProbe.parsed(config.copy(peers = listOf(config.peers.single().copy(allowedIps = listOf("10.0.0.0/8")))), "host:123") }.isFailure)
    }

    @Test fun speedTestUsesTheSavedRouteAndAddressFamily() {
        val base = config()
        val psk = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
        val config = base.copy(
            iface = base.iface.copy(dnsServers = listOf("10.0.0.53"), mtu = 1380),
            peers = listOf(base.peers.single().copy(presharedKey = psk, persistentKeepalive = "25"))
        )
        val parsed = WireGuardProbe.forSpeedTest(config)
        val settings = parsed.outbound.getJSONObject("settings")
        val peer = settings.getJSONArray("peers").getJSONObject(0)
        assertEquals(config.primaryEndpoint, parsed.endpoint)
        assertEquals("ForceIPv4", settings.getString("domainStrategy"))
        assertEquals(1380, settings.getInt("mtu"))
        assertEquals("10.0.0.53", settings.getJSONArray("remoteDNS").getString(0))
        assertEquals(psk, peer.getString("preSharedKey"))
        assertEquals(25, peer.getInt("keepAlive"))
        assertEquals("[49,132,132]", settings.getJSONArray("reserved").toString())
    }

    @Test fun speedTestRejectsRoutesItCannotUseForPublicHttps() {
        val base = config()
        val peer = base.peers.single()
        assertThrows(ConfigException::class.java) {
            WireGuardProbe.forSpeedTest(base.copy(peers = listOf(peer.copy(allowedIps = listOf("10.0.0.0/8")))))
        }
        assertThrows(ConfigException::class.java) {
            WireGuardProbe.forSpeedTest(base.copy(peers = listOf(peer, peer.copy(reserved = emptyList()))))
        }
        assertThrows(ConfigException::class.java) {
            WireGuardProbe.forSpeedTest(base.copy(iface = base.iface.copy(amnezia = mapOf("jc" to "4"))))
        }
    }

    @Test fun candidatesAreExplicitValidatedAndBounded() {
        assertEquals(listOf("a.example:2408", "[::1]:500"), WireGuardProbe.endpoints("a.example:2408\n\n[::1]:500\na.example:2408"))
        listOf("", "example.com", "example.com:0", (1..65).joinToString("\n") { "host$it:2408" }).forEach {
            assertTrue(runCatching { WireGuardProbe.endpoints(it) }.isFailure)
        }
    }

    @Test fun automaticSetupTriesProviderHostsBeforePortAlternates() {
        val default = "warp.example:2408"
        val ipv4 = (1..8).map { "192.0.2.100:$it" }
        val ipv6 = "[2001:db8::100]:2408"
        val providerEndpoints = listOf(default) + ipv4 + ipv6 + default

        assertEquals(listOf(default, ipv4.first(), ipv6) + ipv4.drop(1).take(3), WireGuardProbe.setupEndpoints(providerEndpoints))
    }
}
