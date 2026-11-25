package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.WireGuardProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsTunnelConfigTest {
    @Test
    fun wireGuardUsesNativeFirewallOnlyWhenRequested() {
        val original = config.peers.single()
        val routed = windowsWireGuardConfig(config, killSwitch = false)

        assertEquals(
            listOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1", "192.168.10.0/24"),
            routed.peers.single().allowedIps
        )
        assertEquals(original, routed.peers.single().copy(allowedIps = original.allowedIps))
        assertEquals(config, windowsWireGuardConfig(config, killSwitch = true))
        assertThrows(ConfigException::class.java) {
            windowsWireGuardConfig(
                config.copy(peers = listOf(original.copy(allowedIps = listOf("192.168.10.0/24")))),
                killSwitch = true
            )
        }
    }

    @Test
    fun warpTunKeepsReservedBytesAndUsesConfiguredWindowsControls() {
        val warp = config.copy(peers = config.peers.map { it.copy(reserved = listOf(49, 132, 132)) })
        val profile = WireGuardProbe.forSpeedTest(warp)
        val dns = listOf("9.9.9.9")
        val root = JSONObject(windowsXrayConfig(profile, "WGfixture", dns, warp.allowedIps,
            killSwitch = true, wireGuard = true))
        val tun = root.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")

        assertEquals("WGfixture", tun.getString("name"))
        assertEquals("auto", tun.getString("autoOutboundsInterface"))
        assertEquals(dns, tun.getJSONArray("dns").toList())
        assertEquals(warp.allowedIps, tun.getJSONArray("autoSystemRoutingTable").toList())
        assertEquals(listOf("all"), tun.getJSONArray("autoSystemWfpBlockLeak").toList())
        assertFalse(root.has("routing"))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", outbound.getString("protocol"))
        assertEquals(listOf(49, 132, 132), outbound.getJSONObject("settings").getJSONArray("reserved").toList())
    }

    private val config = AwgConfig.parse("""
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.7.0.2/32, 2001:db8::2/128

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0, 192.168.10.0/24
        Endpoint = wg.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent())
}
