package com.whitegame.app.connection

import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import org.json.JSONObject

/** Uses the existing Xray userspace WireGuard implementation for an authenticated HTTPS test. */
object WireGuardProbe {
    fun parsed(config: AwgConfig, endpoint: String): XrayConfig.Parsed {
        if (config.iface.amnezia.isNotEmpty())
            throw ConfigException("AmneziaWG must be connected to test its obfuscated UDP tunnel")
        if (config.peers.size != 1 || !config.isFullTunnel)
            throw ConfigException("Endpoint scanning needs a single-peer full WireGuard tunnel")
        val (host, port) = AwgConfig.splitHostPort(endpoint)
        val peer = config.peers.single()
        val settings = JSONObject().put("secretKey", config.iface.privateKey)
            .put("address", JSONArray(config.iface.addresses))
            .put("mtu", config.iface.mtu ?: 1280).put("noKernelTun", true)
            .put("reserved", JSONArray(peer.reserved))
            .put("peers", JSONArray().put(JSONObject().put("endpoint", endpoint)
                .put("publicKey", peer.publicKey).put("allowedIPs", JSONArray(peer.allowedIps))
                .apply { peer.presharedKey?.let { put("preSharedKey", it) } }))
        val outbound = JSONObject().put("protocol", "wireguard").put("settings", settings)
        return XrayConfig.Parsed("wireguard", "WireGuard endpoint", host, port, "udp", "", outbound)
    }

    /** Explicit candidates only: never scan unrelated networks or substitute arbitrary IPs. */
    fun endpoints(text: String): List<String> {
        val endpoints = text.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (endpoints.isEmpty()) throw ConfigException("Enter at least one endpoint as host:port")
        if (endpoints.size > 64) throw ConfigException("Scan up to 64 endpoints at a time")
        endpoints.forEach { AwgConfig.splitHostPort(it) }
        return endpoints
    }
}
