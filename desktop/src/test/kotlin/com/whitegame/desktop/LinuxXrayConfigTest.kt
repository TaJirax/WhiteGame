package com.whitegame.desktop

import com.whitegame.app.xray.XrayConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxXrayConfigTest {
    private val profile = XrayConfig.parse("""
        {
          "protocol": "socks",
          "settings": {"servers": [{"address": "proxy.example", "port": 1080}]},
          "streamSettings": {"sockopt": {"interface": "old-interface"}}
        }
    """.trimIndent())

    @Test
    fun nativeRoutesKeepDefaultsAndSystemDnsUsesTheTunnel() {
        val config = JSONObject(linuxXrayConfig(profile, "wg0123456789", listOf("2606:4700:4700::1111"), true))
        val tun = config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals("wg0123456789", tun.getString("name"))
        assertEquals(listOf("198.18.0.1/30", "fd01:7767::1/64"), tun.getJSONArray("gateway").toList())
        assertEquals(listOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1"),
            tun.getJSONArray("autoSystemRoutingTable").toList())
        assertEquals("auto", tun.getString("autoOutboundsInterface"))
        assertTrue(tun.getBoolean("autoSystemDnsToGateway"))
        assertEquals("tcp+local://[2606:4700:4700::1111]:53", config.getJSONObject("dns").getJSONArray("servers").getString(0))

        val rule = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        val outbounds = config.getJSONArray("outbounds")
        val proxySockopt = outbounds.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt")
        val dns = outbounds.getJSONObject(outbounds.length() - 1)
        assertEquals("ForceIP", proxySockopt.getString("domainStrategy"))
        assertFalse(proxySockopt.has("interface"))
        assertEquals("198.18.0.2/32", rule.getJSONArray("ip").getString(0))
        assertEquals(dns.getString("tag"), rule.getString("outboundTag"))
        assertEquals("proxy", dns.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
    }

    @Test
    fun ipv4OnlyKernelCanKeepItsExistingResolver() {
        val config = JSONObject(linuxXrayConfig(profile, "wg0123456789", emptyList(), false, ipv6 = false))
        val tun = config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals(listOf("198.18.0.1/30"), tun.getJSONArray("gateway").toList())
        assertEquals(listOf("0.0.0.0/1", "128.0.0.0/1"), tun.getJSONArray("autoSystemRoutingTable").toList())
        assertFalse(tun.getBoolean("autoSystemDnsToGateway"))
        assertEquals(3, config.getJSONArray("outbounds").length())
    }
}
