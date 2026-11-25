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
        return outbound(config, listOf(config.peers.single().copy(endpoint = endpoint)), endpoint)
    }

    /** Build a userspace tunnel for a public HTTPS target while preserving the peer routes. */
    fun forSpeedTest(config: AwgConfig): XrayConfig.Parsed {
        if (config.iface.amnezia.isNotEmpty())
            throw ConfigException("For AmneziaWG, connect the profile and run Network → Speed with WhiteGame included in the tunnel.")
        val endpoint = config.primaryEndpoint ?: throw ConfigException("WireGuard config has no peer endpoint")
        if (config.peers.any { it.endpoint == null })
            throw ConfigException("The isolated WireGuard test needs an endpoint for each peer")
        if (config.peers.map { it.reserved }.distinct().size > 1)
            throw ConfigException("The isolated WireGuard test requires the same Reserved bytes on each peer")

        val routes = config.allowedIps
        val hasIpv4 = config.iface.addresses.any { ':' !in it } &&
            (routes.any { ':' !in it && it.endsWith("/0") } ||
                routes.containsAll(listOf("0.0.0.0/1", "128.0.0.0/1")))
        val hasIpv6 = config.iface.addresses.any { ':' in it } &&
            (routes.any { ':' in it && it.endsWith("/0") } ||
                routes.containsAll(listOf("::/1", "8000::/1")))
        val strategy = when {
            hasIpv4 && hasIpv6 -> "ForceIPv4v6"
            hasIpv4 -> "ForceIPv4"
            hasIpv6 -> "ForceIPv6"
            else -> throw ConfigException("Cloudflare speed tests require an internet route in WireGuard AllowedIPs")
        }
        val probe = outbound(config, config.peers, endpoint)
        val settings = probe.outbound.getJSONObject("settings").put("domainStrategy", strategy)
        // gVisor uses these resolvers inside the WireGuard tunnel.
        if (config.iface.dnsServers.isNotEmpty()) settings.put("remoteDNS", JSONArray(config.iface.dnsServers))
        return probe
    }

    private fun outbound(config: AwgConfig, peers: List<AwgConfig.Peer>, endpoint: String): XrayConfig.Parsed {
        val (host, port) = AwgConfig.splitHostPort(endpoint)
        val settings = JSONObject().put("secretKey", config.iface.privateKey)
            .put("address", JSONArray(config.iface.addresses))
            .put("mtu", config.iface.mtu ?: 1280).put("noKernelTun", true)
            .put("reserved", JSONArray(peers.first().reserved))
            .put("peers", JSONArray(peers.map { peer ->
                JSONObject().put("endpoint", peer.endpoint)
                    .put("publicKey", peer.publicKey).put("allowedIPs", JSONArray(peer.allowedIps))
                    .apply {
                        peer.presharedKey?.let { put("preSharedKey", it) }
                        peer.persistentKeepalive?.toIntOrNull()?.let { put("keepAlive", it) }
                    }
            }))
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

    /** Keep setup bounded while testing at least one port per provider host before alternates. */
    internal fun setupEndpoints(candidates: List<String>): List<String> {
        val preferred = candidates.groupBy { AwgConfig.splitHostPort(it).first }.values.map { it.first() }
        return (preferred + candidates).distinct().take(6)
    }
}
