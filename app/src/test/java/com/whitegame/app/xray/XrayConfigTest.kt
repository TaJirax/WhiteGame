package com.whitegame.app.xray

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class XrayConfigTest {
    private val link = "vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=ws&path=%2Fgame&host=edge.example.com#Game"
    private val pin = "ab".repeat(32)

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

    @Test fun configSpeedListenerOnlyRoutesTheCloudflareHost() {
        val config = JSONObject(XrayConfig.speedSocksConfig(18080, XrayConfig.parse(link)))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertFalse(inbound.getJSONObject("settings").getBoolean("udp"))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals("blackhole", outbounds.getJSONObject(0).getString("protocol"))
        assertEquals("vless", outbounds.getJSONObject(1).getString("protocol"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, rules.length())
        val allowed = rules.getJSONObject(0)
        assertEquals("full:speed.cloudflare.com", allowed.getJSONArray("domain").getString(0))
        assertEquals("tcp", allowed.getString("network"))
        assertEquals(443, allowed.getInt("port"))
        assertEquals(outbounds.getJSONObject(1).getString("tag"), allowed.getString("outboundTag"))
    }
    @Test fun jsonOutboundCanBeTested() {
        val parsed = XrayConfig.parse(link)
        val restored = XrayConfig.parse(parsed.outbound.toString())
        assertEquals(parsed.host, restored.host)
        assertEquals("vless", JSONObject(XrayConfig.delayConfig(restored)).getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }

    @Test fun legacyTlsFlagsLeaveCertificateVerificationEnabled() {
        val vmessJson = """{"add":"example.com","port":443,"id":"00000000-0000-0000-0000-000000000001","tls":"tls","allowInsecure":true}"""
        val vmess = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val links = listOf(
            link.replace("#Game", "&allowInsecure=1#Game"),
            "trojan://password@example.com:443?allow_insecure=true",
            "hy2://password@example.com:443?insecure=1",
            vmess
        )
        for (uri in links) {
            val tls = tlsSettings(XrayConfig.parse(uri).outbound)
            assertFalse(tls.has("allowInsecure"))
            assertFalse(tls.has("pinnedPeerCertSha256"))
            assertFalse(tls.has("verifyPeerCertByName"))
        }
    }

    @Test fun tlsShareLinksUseConfiguredCertificateVerification() {
        val vless = link.replace("#Game", "&allowInsecure=1&pcs=$pin&vcn=cert.example.com%2Cbackup.example.com#Game")
        val tls = tlsSettings(XrayConfig.parse(vless).outbound)
        assertEquals(pin, tls.getString("pinnedPeerCertSha256"))
        assertEquals("cert.example.com,backup.example.com", tls.getString("verifyPeerCertByName"))
        assertEquals("edge.example.com", tls.getString("serverName"))
        assertFalse(tls.has("allowInsecure"))

        val hysteria = XrayConfig.parse("hysteria2://password@example.com:443?insecure=1&pinSHA256=$pin")
        assertEquals(pin, tlsSettings(hysteria.outbound).getString("pinnedPeerCertSha256"))
    }

    @Test fun importedJsonDropsLegacyFlagAndRetainsTlsSettingsForAllCoreConfigs() {
        val outbound = XrayConfig.parse(link).outbound
        tlsSettings(outbound).put("AllowInsecure", true).put("pinnedPeerCertSha256", pin)
        val fullConfig = JSONObject().put("outbounds", JSONArray().put(outbound))
        for (json in listOf(outbound.toString(), fullConfig.toString())) {
            val parsed = XrayConfig.parse(json)
            val configs = listOf(
                XrayConfig.delayConfig(parsed),
                XrayConfig.socksConfig(listOf(18080 to parsed)),
                XrayConfig.tunConfig(parsed, listOf("1.1.1.1"))
            )
            for (config in configs) {
                val tls = tlsSettings(JSONObject(config).getJSONArray("outbounds").getJSONObject(0))
                assertFalse(tls.has("AllowInsecure"))
                assertEquals(pin, tls.getString("pinnedPeerCertSha256"))
            }
        }
    }

    private fun tlsSettings(outbound: JSONObject): JSONObject =
        outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings")
}
