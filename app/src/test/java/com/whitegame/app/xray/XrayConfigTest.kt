package com.whitegame.app.xray

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class XrayConfigTest {
    private val link = "vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=ws&path=%2Fgame&host=edge.example.com#Game"
    @Test fun shareLinkPreservesTransportAndTls() {
        val parsed = XrayConfig.parse(link)
        assertEquals("example.com", parsed.host)
        assertEquals(443, parsed.port)
        val stream = parsed.outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("/game", stream.getJSONObject("wsSettings").getString("path"))
    }
    @Test fun comparisonBuildsSeparateLoopbackInbounds() {
        val config = JSONObject(XrayConfig.socksConfig(listOf(18080 to XrayConfig.parse(link), 18081 to XrayConfig.parse(link))))
        val inbounds = config.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        assertEquals("127.0.0.1", inbounds.getJSONObject(0).getString("listen"))
        assertEquals(18081, inbounds.getJSONObject(1).getInt("port"))
        assertEquals(2, config.getJSONObject("routing").getJSONArray("rules").length())
    }
    @Test fun jsonOutboundCanBeTested() {
        val parsed = XrayConfig.parse(link)
        val restored = XrayConfig.parse(parsed.outbound.toString())
        assertEquals(parsed.host, restored.host)
        assertEquals("vless", JSONObject(XrayConfig.delayConfig(restored)).getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }
}
