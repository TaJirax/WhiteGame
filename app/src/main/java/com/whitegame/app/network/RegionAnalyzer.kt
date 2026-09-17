package com.whitegame.app.network

import com.whitegame.app.model.ProxyNode
import com.whitegame.app.model.TcpProbeResult
import com.whitegame.app.model.UdpProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Approximate server / game region from latency to well-known regional edges.
 * Also builds drop-risk and full user-facing caption.
 */
object RegionAnalyzer {

    private data class Edge(val region: String, val flag: String, val host: String)

    private val EDGES = listOf(
        Edge("Europe", "🇪🇺", "1.1.1.1"),
        Edge("Europe", "🇪🇺", "9.9.9.9"),
        Edge("Asia-SG", "🇸🇬", "www.google.com.sg"),
        Edge("Asia-JP", "🇯🇵", "www.yahoo.co.jp"),
        Edge("Middle-East", "🇧🇭", "dns.google"),
        Edge("US-East", "🇺🇸", "cloudflare.com"),
        Edge("Asia-HK", "🇭🇰", "www.google.com.hk")
    )

    suspend fun detectServerRegion(host: String): Pair<String, String> = withContext(Dispatchers.IO) {
        // Latency from this device to host vs regional public hosts is weak;
        // we classify by probing the node RTT buckets + hostname hints.
        val hint = host.lowercase()
        when {
            hint.contains("eu") || hint.contains("de") || hint.contains("nl") ||
                hint.contains("fr") || hint.contains("uk") || hint.contains("fra") ||
                hint.contains("ams") -> "Europe" to "🇪🇺"
            hint.contains("sg") || hint.contains("sin") || hint.contains("singapore") -> "Asia-SG" to "🇸🇬"
            hint.contains("jp") || hint.contains("tyo") || hint.contains("tokyo") -> "Asia-JP" to "🇯🇵"
            hint.contains("hk") || hint.contains("hkg") -> "Asia-HK" to "🇭🇰"
            hint.contains("tr") || hint.contains("turkey") || hint.contains("ist") -> "Turkey" to "🇹🇷"
            hint.contains("ae") || hint.contains("dxb") || hint.contains("me-") -> "Middle-East" to "🇦🇪"
            hint.contains("us") || hint.contains("nyc") || hint.contains("la") ||
                hint.contains("chi") || hint.contains("america") -> "US" to "🇺🇸"
            hint.contains("ru") || hint.contains("msk") -> "Russia" to "🇷🇺"
            hint.contains("in") || hint.contains("bom") || hint.contains("del") -> "India" to "🇮🇳"
            hint.contains("warp") || hint.contains("cloudflare") -> "Anycast/CF" to "☁️"
            else -> "Unknown" to "🌐"
        }
    }

    suspend fun probeGameRegionLatency(): Triple<String, Long?, Long?> = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Pair<String, Long>>()
        val targets = listOf(
            "Asia-SG" to "www.google.com.sg",
            "Europe" to "www.google.de",
            "US" to "www.google.com",
            "ME" to "dns.google"
        )
        for ((reg, h) in targets) {
            val r = NetworkProber.probeTcp(h, 443, 2, 1800)
            r.avgMs?.let { samples.add(reg to it) }
        }
        if (samples.isEmpty()) return@withContext Triple("Unknown", null, null)
        val best = samples.minByOrNull { it.second }!!
        val min = samples.minOf { it.second }
        val max = samples.maxOf { it.second }
        Triple(best.first, min, max)
    }

    fun assessDropRisk(
        tcp: TcpProbeResult?,
        udp: UdpProbeResult?,
        stun: UdpProbeResult?,
        tls: Long?,
        jitter: Long?
    ): Triple<String, String, Int> {
        var score = 100
        val reasons = mutableListOf<String>()
        tcp?.let {
            if (it.lossPct >= 20) { score -= 35; reasons += "TCP loss ${it.lossPct}%" }
            else if (it.lossPct >= 8) { score -= 15; reasons += "TCP loss ${it.lossPct}%" }
            if ((it.jitterMs ?: 0) > 60) { score -= 20; reasons += "high TCP jitter" }
            else if ((it.jitterMs ?: 0) > 30) { score -= 10; reasons += "moderate jitter" }
            if (it.avgMs != null && it.avgMs > 200) { score -= 15; reasons += "high RTT" }
        }
        when (udp?.status) {
            "BLOCKED" -> { score -= 40; reasons += "UDP blocked to node" }
            "FAIL" -> { score -= 25; reasons += "UDP send failed" }
            "NO_REPLY" -> { score -= 5 }
            "REPLIES" -> {
                if ((udp.lossPct) >= 25) { score -= 20; reasons += "UDP loss high" }
            }
        }
        when (stun?.status) {
            "BLOCKED", "FAIL" -> { score -= 25; reasons += "device UDP path weak (STUN)" }
        }
        if (tls == null && tcp?.avgMs == null) {
            score -= 30
            reasons += "no stable TCP/TLS"
        }
        score = score.coerceIn(0, 100)
        val risk = when {
            score >= 80 -> "Low"
            score >= 55 -> "Medium"
            score >= 30 -> "High"
            else -> "Critical"
        }
        val advice = when (risk) {
            "Low" -> "Connection looks stable for ranked / long sessions."
            "Medium" -> "May drop under load or Wi‑Fi changes — prefer wired/5GHz."
            "High" -> "Drops likely in competitive play — try another node/region."
            else -> "Not safe for online games — high chance of disconnect."
        } + if (reasons.isNotEmpty()) " (${reasons.take(3).joinToString()})" else ""
        return Triple(risk, advice, score)
    }

    fun buildCaption(n: ProxyNode): String = buildString {
        append("${n.serverFlag} ${n.protocol} · ${n.name}\n")
        append("Server: ${n.host}:${n.port}\n")
        append("Region: ${n.serverRegion.ifBlank { "Unknown" }}\n")
        append("Network: ${n.network} · Security: ${n.security}\n")
        n.tcpMs?.let { append("TCP: ${it}ms") }
        n.tcpLoss?.let { append(" loss ${it}%") }
        n.tcpJitter?.let { append(" ±${it}ms") }
        append("\n")
        append("UDP: ${n.udpStatus}")
        n.udpMs?.let { append(" ${it}ms") }
        n.udpLoss?.let { append(" loss ${it}%") }
        append("\n")
        append("STUN: ${n.stunStatus}")
        n.stunMs?.let { append(" ${it}ms") }
        append("\n")
        append("In-game region hint: ${n.gameRegion.ifBlank { "—" }}\n")
        append("Game ping range: ${n.gamePingMin ?: "—"}–${n.gamePingMax ?: "—"} ms\n")
        append("Game loss range: ${n.gameLossMin ?: "—"}–${n.gameLossMax ?: "—"}%\n")
        append("Drop risk: ${n.dropRisk} (stability ${n.stabilityScore}/100)\n")
        append(n.dropAdvice)
        if (n.gameLabel.isNotBlank()) append("\nGaming: ${n.gameLabel}")
    }
}
