package com.whitegame.desktop

import com.whitegame.app.connection.ConfigException
import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import org.json.JSONObject

internal fun linuxXrayConfig(
    profile: XrayConfig.Parsed,
    adapter: String,
    dns: List<String>,
    configureSystemDns: Boolean,
    ipv6: Boolean = true
): String {
    if (profile.protocol.lowercase() in setOf("freedom", "blackhole", "dns", "loopback")) {
        throw ConfigException("This config has no tunnel outbound.")
    }
    val servers = dns.ifEmpty { if (ipv6) listOf("1.1.1.1", "2606:4700:4700::1111") else listOf("1.1.1.1") }
    val root = JSONObject(XrayConfig.tunConfig(profile, servers))
    val tun = root.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
    val gateways = if (ipv6) listOf("198.18.0.1/30", "fd01:7767::1/64") else listOf("198.18.0.1/30")
    val routes = if (ipv6) listOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1")
        else listOf("0.0.0.0/1", "128.0.0.0/1")
    tun.put("name", adapter)
        .put("gateway", JSONArray(gateways))
        .put("autoSystemRoutingTable", JSONArray(routes))
        .put("autoOutboundsInterface", "auto")
        .put("autoSystemDnsToGateway", configureSystemDns)

    val proxy = root.getJSONArray("outbounds").getJSONObject(0)
    val stream = proxy.optJSONObject("streamSettings") ?: JSONObject().also { proxy.put("streamSettings", it) }
    val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
    sockopt.remove("interface")
    // Resolve the proxy server over the underlying interface before user DNS can go through it.
    sockopt.put("domainStrategy", if (ipv6) "ForceIP" else "ForceIPv4")
    root.getJSONObject("dns").put("servers", JSONArray(servers.map { ip ->
        val host = if (':' in ip) "[$ip]" else ip
        "tcp+local://$host:53"
    }))
    if (configureSystemDns) {
        val routing = root.getJSONObject("routing")
        val previousRules = routing.getJSONArray("rules")
        val rules = JSONArray().put(JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray(listOf("tun")))
            .put("ip", JSONArray(listOf("198.18.0.2/32")))
            .put("port", "53")
            .put("outboundTag", "tun-dns"))
        for (index in 0 until previousRules.length()) rules.put(previousRules.getJSONObject(index))
        routing.put("rules", rules)

        // TCP DNS works over both TCP-only and UDP-capable proxy transports.
        root.getJSONArray("outbounds").put(JSONObject()
            .put("protocol", "dns")
            .put("tag", "tun-dns")
            .put("settings", JSONObject()
                .put("rewriteNetwork", "tcp")
                .put("rewriteAddress", servers.first())
                .put("rewritePort", 53)
                .put("rules", JSONArray().put(JSONObject().put("action", "direct"))))
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "proxy"))))
    }
    return root.toString()
}
