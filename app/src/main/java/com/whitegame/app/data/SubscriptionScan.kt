package com.whitegame.app.data

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.model.SubNode
import com.whitegame.app.xray.XrayConfig

/** Protocol-aware scan decisions shared by the loader and its regression tests. */
object SubscriptionScan {
    enum class Probe { PROXY, WIREGUARD, TCP, TUNNEL }

    fun probeFor(node: SubNode): Probe = when {
        XrayConfig.looksLikeXray(node.conf) -> Probe.PROXY
        node.conf.isNotBlank() -> if (runCatching { wireGuardParsed(node) }.isSuccess) Probe.WIREGUARD else Probe.TUNNEL
        node.type.lowercase() in listOf("wireguard", "amneziawg", "amnezia", "warp") -> Probe.TUNNEL
        else -> Probe.TCP
    }

    fun wireGuardParsed(node: SubNode): XrayConfig.Parsed {
        val endpoint = (if (':' in node.host) "[${node.host}]" else node.host) + ":" + node.port
        return WireGuardProbe.parsed(AwgConfig.parse(node.conf), endpoint)
    }

    fun tunnelStatus(node: SubNode): String = if (node.conf.isBlank()) "Needs keys to test UDP tunnel"
        else runCatching { wireGuardParsed(node) }.exceptionOrNull()?.message ?: "Connect to test UDP tunnel"

    fun proxyResult(node: SubNode, ms: Long?, error: String? = null, successLabel: String = "Proxy HTTPS"): SubNode {
        val delay = ms?.takeIf { it >= 0 }
        return node.copy(
            pingMs = delay,
            lossPct = if (delay != null) 0 else 100,
            score = if (delay != null) DnsData.scoreDns(delay, 0, 0L).first else 0,
            status = if (delay != null) successLabel else error?.take(90)?.ifBlank { null } ?: "No proxy response"
        )
    }

    /** Actual delay breaks score ties: scores intentionally use coarse quality buckets. */
    fun rank(nodes: List<SubNode>): List<SubNode> = nodes.sortedWith(
        compareBy<SubNode> { it.pingMs == null }
            .thenBy { it.lossPct ?: 100 }
            .thenBy { it.pingMs ?: Long.MAX_VALUE }
    )
}
