package com.whitegame.app.model

enum class TunnelState {
    DISCONNECTED,
    SCANNING,
    READY,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED;

    val isBusy: Boolean get() = this == CONNECTING || this == RECONNECTING
    val isLive: Boolean get() = this == CONNECTED || isBusy
}

data class IspScanResult(
    val networkType: String = "—",
    val ispHint: String = "—",
    val livePingMs: Long? = null,
    val avgPingMs: Long? = null,
    val minPingMs: Long? = null,
    val maxPingMs: Long? = null,
    val lossPct: Int = 0,
    val quality: String = "—",
    val advice: String = "",
    val samples: List<Long> = emptyList()
)

data class RecommendedDns(
    val ip: String,
    val provider: String,
    val pingMs: Long?,
    val jitterMs: Long?,
    val lossPct: Int?,
    val score: Int,
    val reason: String
)
