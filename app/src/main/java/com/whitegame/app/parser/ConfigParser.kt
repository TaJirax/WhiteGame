package com.whitegame.app.parser

import android.util.Base64
import com.whitegame.app.model.ProxyNode
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Only UDP-capable / gaming-relevant protocols:
 * VLESS, VMess, Trojan (stream may carry UDP), Shadowsocks, Hysteria2, TUIC, WireGuard, WARP.
 * OpenVPN removed by product requirement.
 */
object ConfigParser {

    private val UDP_PROTOCOLS = setOf(
        "VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "HYSTERIA2", "TUIC",
        "WIREGUARD", "WARP", "SS", "HY2"
    )

    fun parseAll(raw: String): List<ProxyNode> {
        val blocks = splitBlocks(raw)
        val out = mutableListOf<ProxyNode>()
        var idx = 1
        for (b in blocks) {
            val lower = b.lowercase()
            when {
                isWarpFamily(lower) -> parseWarp(b, idx)?.let { out.add(it); idx++ }
                isWireGuard(lower) -> parseWgIni(b, idx).let { out.add(it); idx++ }
                isDnsTunnelFamily(lower) && !lower.startsWith("dns-endpoint://") -> {
                    val multi = parseDnsTunnelFamily(b, idx)
                    out.addAll(multi)
                    idx += multi.size.coerceAtLeast(1)
                }
                else -> parseOne(b, idx)?.let { out.add(it); idx++ }
            }
        }
        return out
            .filter { n ->
                val p = n.protocol.uppercase()
                p in UDP_PROTOCOLS || n.isUdpHeavy || p.contains("HYSTERIA") || p.contains("TUIC") || p.contains("WIREGUARD")
            }
            .distinctBy { "${it.protocol}:${it.host}:${it.port}" }
    }

    private fun splitBlocks(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val blocks = mutableListOf<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val lower = line.lowercase()
            if (lower.startsWith("vless://") || lower.startsWith("vmess://") ||
                lower.startsWith("trojan://") || lower.startsWith("ss://") ||
                lower.startsWith("hy2://") || lower.startsWith("hysteria2://") ||
                lower.startsWith("tuic://") || lower.startsWith("wireguard://") ||
                lower.startsWith("wg://")
            ) blocks.add(line)
        }
        if (text.contains("{") && text.contains("}")) {
            var depth = 0
            val buf = StringBuilder()
            for (c in text) {
                if (c == '{') { if (depth == 0) buf.clear(); depth++ }
                if (depth > 0) buf.append(c)
                if (c == '}') {
                    depth--
                    if (depth == 0 && buf.isNotEmpty()) {
                        val j = buf.toString()
                        if (j.contains("outbounds") || j.contains("vnext") ||
                            j.contains("address") || j.contains("server") ||
                            j.contains("peer") || j.contains("Interface")
                        ) blocks.add(j)
                    }
                }
            }
        }
        if (text.contains("[Interface]", ignoreCase = true) && text.contains("[Peer]", ignoreCase = true)) {
            blocks.add(text)
        }
        if (isWarpFamily(text.lowercase())) blocks.add(text)
        val lowerAll = text.lowercase()
        if (lowerAll.contains("masterdns") || lowerAll.contains("stormdns") ||
            lowerAll.contains("cottondns") || lowerAll.contains("client_resolvers")
        ) blocks.add(text)
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val bare = line.substringBefore("#").trim()
            if (Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?$""").matches(bare)) {
                blocks.add("dns-endpoint://$bare")
            }
        }
        return blocks.distinct().ifEmpty { if (text.length > 8) listOf(text) else emptyList() }
    }

    private fun parseOne(raw: String, index: Int): ProxyNode? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        // Explicitly reject OpenVPN
        if (lower.contains("dev tun") || lower.contains("dev tap") || lower.contains("openvpn")) return null
        return try {
            when {
                lower.startsWith("vless://") -> parseVless(t, index)
                lower.startsWith("vmess://") -> parseVmess(t, index)
                lower.startsWith("trojan://") -> parseTrojan(t, index)
                lower.startsWith("ss://") -> parseSs(t, index)
                lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHy2(t, index)
                lower.startsWith("tuic://") -> parseTuic(t, index)
                lower.startsWith("wireguard://") || lower.startsWith("wg://") -> parseWgLink(t, index)
                lower.startsWith("dns-endpoint://") -> parseDnsEndpoint(t, index)
                t.startsWith("{") -> parseJsonNode(t, index)
                t.contains("[Interface]", true) -> parseWgIni(t, index)
                else -> {
                    val m = Regex("""([A-Za-z0-9.\-\[\]]+):(\d{2,5})""").find(t) ?: return null
                    ProxyNode(
                        index, "Custom-UDP",
                        m.groupValues[1].removePrefix("[").removeSuffix("]"),
                        m.groupValues[2].toInt(), "node-$index", "—", "udp", t.take(80), true
                    )
                }
            }
        } catch (_: Exception) { null }
    }

    private fun parseVless(link: String, i: Int): ProxyNode? {
        val body = link.removePrefix("vless://").removePrefix("VLESS://")
        val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }.ifBlank { "VLESS-$i" }
        val main = body.substringBefore("#")
        val userHost = main.substringBefore("?")
        val params = main.substringAfter("?", "")
        val hostPort = userHost.substringAfter("@")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        val security = param(params, "security") ?: "none"
        val network = param(params, "type") ?: "tcp"
        return ProxyNode(i, "VLESS", host, port, name, security, network, link.take(100), true)
    }

    private fun parseVmess(link: String, i: Int): ProxyNode? {
        val b64 = link.removePrefix("vmess://").removePrefix("VMESS://")
        val json = try {
            val decoded = String(Base64.decode(b64, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(decoded)
        } catch (_: Exception) { return null }
        val host = json.optString("add").ifBlank { json.optString("host") }
        val port = json.optInt("port", 443)
        val name = json.optString("ps").ifBlank { "VMess-$i" }
        val network = json.optString("net", "tcp")
        val security = json.optString("tls", "none")
        return ProxyNode(i, "VMess", host, port, name, security, network, link.take(100), true)
    }

    private fun parseTrojan(link: String, i: Int): ProxyNode? {
        val body = link.removePrefix("trojan://").removePrefix("TROJAN://")
        val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }.ifBlank { "Trojan-$i" }
        val main = body.substringBefore("#")
        val hostPort = main.substringAfter("@").substringBefore("?")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        val params = main.substringAfter("?", "")
        val network = param(params, "type") ?: "tcp"
        return ProxyNode(i, "Trojan", host, port, name, "tls", network, link.take(100), true)
    }

    private fun parseSs(link: String, i: Int): ProxyNode? {
        val body = link.removePrefix("ss://").removePrefix("SS://")
        val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }.ifBlank { "SS-$i" }
        val main = body.substringBefore("#")
        val hostPort = if (main.contains("@")) main.substringAfter("@") else {
            try {
                val dec = String(Base64.decode(main, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP))
                dec.substringAfter("@")
            } catch (_: Exception) { main }
        }
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 8388
        return ProxyNode(i, "Shadowsocks", host, port, name, "—", "udp", link.take(100), true)
    }

    private fun parseHy2(link: String, i: Int): ProxyNode? {
        val body = link.substringAfter("://")
        val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }.ifBlank { "Hysteria2-$i" }
        val main = body.substringBefore("#")
        val hostPort = main.substringAfter("@").substringBefore("?")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        return ProxyNode(i, "Hysteria2", host, port, name, "tls", "udp", link.take(100), true)
    }

    private fun parseTuic(link: String, i: Int): ProxyNode? {
        val body = link.removePrefix("tuic://").removePrefix("TUIC://")
        val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }.ifBlank { "TUIC-$i" }
        val main = body.substringBefore("#")
        val hostPort = main.substringAfter("@").substringBefore("?")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        return ProxyNode(i, "TUIC", host, port, name, "tls", "udp", link.take(100), true)
    }

    private fun parseWgLink(link: String, i: Int): ProxyNode? {
        val body = link.substringAfter("://")
        val name = body.substringAfter("#", "").ifBlank { "WireGuard-$i" }
        val main = body.substringBefore("#")
        val hostPort = main.substringAfter("@").substringBefore("?")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 51820
        return ProxyNode(i, "WireGuard", host, port, name, "—", "udp", link.take(100), true)
    }

    fun parseWgIni(conf: String, i: Int): ProxyNode {
        val endpoint = Regex("""Endpoint\s*=\s*([^\s:]+):(\d+)""", RegexOption.IGNORE_CASE).find(conf)
        val host = endpoint?.groupValues?.get(1) ?: "unknown"
        val port = endpoint?.groupValues?.get(2)?.toIntOrNull() ?: 51820
        val name = Regex("""#\s*(.+)""").find(conf)?.groupValues?.get(1)?.trim() ?: "WG-$i"
        return ProxyNode(i, "WireGuard", host, port, name, "—", "udp", conf.take(100), true)
    }

    private fun parseDnsEndpoint(link: String, i: Int): ProxyNode {
        val bare = link.removePrefix("dns-endpoint://")
        val host = bare.substringBefore(":")
        val port = bare.substringAfter(":", "53").toIntOrNull() ?: 53
        return ProxyNode(i, "DNS-Endpoint", host, port, "EP-$i", "—", "udp", bare, true)
    }

    private fun parseJsonNode(json: String, i: Int): ProxyNode? {
        return try {
            val obj = JSONObject(json)
            val outbound = obj.optJSONArray("outbounds")?.optJSONObject(0)
                ?: obj.optJSONObject("outbound") ?: obj
            val proto = outbound.optString("protocol", outbound.optString("type", "JSON"))
            if (proto.contains("openvpn", true)) return null
            val settings = outbound.optJSONObject("settings") ?: outbound
            val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
            val servers = settings.optJSONArray("servers")?.optJSONObject(0)
            val host = vnext?.optString("address")
                ?: servers?.optString("address")
                ?: servers?.optString("server")
                ?: settings.optString("address")
                ?: settings.optString("server")
                ?: "unknown"
            val port = vnext?.optInt("port")
                ?: servers?.optInt("port")
                ?: settings.optInt("port", 443)
            val stream = outbound.optJSONObject("streamSettings")
            val network = stream?.optString("network", "tcp") ?: "tcp"
            val security = stream?.optString("security", "none") ?: "none"
            ProxyNode(
                i, proto.uppercase(), host, port, "JSON-$i", security, network, json.take(80),
                true
            )
        } catch (_: Exception) { null }
    }

    private fun isWireGuard(lower: String): Boolean =
        lower.contains("[interface]") && lower.contains("[peer]")

    private fun isWarpFamily(lower: String): Boolean =
        lower.contains("warp") ||
            (lower.contains("cloudflare") && lower.contains("private_key")) ||
            lower.contains("engage.cloudflareclient.com")

    private fun parseWarp(text: String, i: Int): ProxyNode? {
        val endpoint = Regex("""Endpoint\s*=\s*([^\s:]+):(\d+)""", RegexOption.IGNORE_CASE).find(text)
        val host = endpoint?.groupValues?.get(1) ?: "engage.cloudflareclient.com"
        val port = endpoint?.groupValues?.get(2)?.toIntOrNull() ?: 2408
        return ProxyNode(i, "WARP", host, port, "Cloudflare WARP", "—", "udp", text.take(80), true)
    }

    private fun isDnsTunnelFamily(lower: String): Boolean =
        lower.contains("masterdns") || lower.contains("stormdns") ||
            lower.contains("cottondns") || lower.contains("client_resolvers")

    private fun parseDnsTunnelFamily(text: String, startIndex: Int): List<ProxyNode> {
        val nodes = mutableListOf<ProxyNode>()
        var idx = startIndex
        Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(\d{1,5}))?""").findAll(text).forEach { m ->
            val host = m.groupValues[1]
            val port = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 53
            nodes.add(ProxyNode(idx, "DNS-Tunnel", host, port, "DNS-$idx", "—", "udp", m.value, true))
            idx++
        }
        return nodes
    }

    private fun param(qs: String, key: String): String? {
        if (qs.isBlank()) return null
        return qs.split("&").map { it.split("=", limit = 2) }
            .firstOrNull { it[0].equals(key, true) }?.getOrNull(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }
}
