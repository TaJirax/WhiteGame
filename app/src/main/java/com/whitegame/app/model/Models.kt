package com.whitegame.app.model

data class DnsItem(
    val id: Int,
    val ip: String,
    val provider: String,
    val notes: String,
    val regionHint: String = "",
    val gaming: Boolean = true,
    var pingMs: Long? = null,
    var lossPct: Int? = null,
    var jitterMs: Long? = null,
    var status: String = "—",
    var score: Int = 0,
    var gameLabel: String = "",
    var gameAdvice: String = ""
)

data class ProxyNode(
    val index: Int,
    val protocol: String,
    val host: String,
    val port: Int,
    val name: String,
    val security: String,
    val network: String,
    val rawPreview: String,
    val isUdpHeavy: Boolean = true,
    // Probe
    var resolveMs: Long? = null,
    var tcpMs: Long? = null,
    var tcpLoss: Int? = null,
    var tcpJitter: Long? = null,
    var tlsMs: Long? = null,
    var udpMs: Long? = null,
    var udpLoss: Int? = null,
    var udpJitter: Long? = null,
    var udpStatus: String = "",
    var tcp443Ms: Long? = null,
    var udp443Status: String = "",
    var stunMs: Long? = null,
    var stunStatus: String = "",
    var reachable: Boolean? = null,
    // Region / server
    var serverRegion: String = "",
    var serverFlag: String = "",
    var exitHint: String = "",
    var gameRegion: String = "",
    var gamePingMin: Long? = null,
    var gamePingMax: Long? = null,
    var gameLossMin: Int? = null,
    var gameLossMax: Int? = null,
    // Stability
    var dropRisk: String = "",
    var dropAdvice: String = "",
    var stabilityScore: Int = 0,
    var caption: String = "",
    var gameLabel: String = "",
    var gameAdvice: String = "",
    var detail: String = ""
)

data class TcpProbeResult(
    val avgMs: Long?,
    val lossPct: Int,
    val jitterMs: Long?,
    val minMs: Long? = null,
    val maxMs: Long? = null
)

data class UdpProbeResult(
    val avgMs: Long?,
    val lossPct: Int,
    val jitterMs: Long?,
    val status: String,
    val minMs: Long? = null,
    val maxMs: Long? = null
)

data class GameInfo(
    val id: String,
    val name: String,
    val category: String,
    val hosts: List<String>,
    val tcpPorts: List<Int>,
    val udpPorts: List<Int>,
    val regions: List<String>,
    val pingNeed: String,
    val notes: String = "",
    val packageNames: List<String> = emptyList()
)

data class GamePingResult(
    val host: String,
    val region: String,
    val resolveMs: Long?,
    val tcpMs: Long?,
    val tcpLoss: Int,
    val udpStatus: String,
    val udpMs: Long?
)

data class DeviceReport(
    val lines: List<Pair<String, String>>,
    val advice: List<String>,
    val isp: String = "",
    val networkType: String = "",
    val livePingMs: Long? = null,
    val avgPingMs: Long? = null,
    val pingSamples: List<Long> = emptyList(),
    val latencyLabel: String = ""
)

data class NetworkLiveStats(
    val networkType: String,
    val ispHint: String,
    val samples: List<Long>,
    val liveMs: Long?,
    val avgMs: Long?,
    val minMs: Long?,
    val maxMs: Long?,
    val lossPct: Int
)


data class SubNode(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: String,
    val region: String = "",
    val flag: String = "",
    val gamesHint: String = "",
    /** The .conf this node maps to, when the subscription carried one. Empty means ping-only. */
    val conf: String = "",
    var pingMs: Long? = null,
    var lossPct: Int? = null,
    var score: Int = 0,
    var status: String = "—"
)
