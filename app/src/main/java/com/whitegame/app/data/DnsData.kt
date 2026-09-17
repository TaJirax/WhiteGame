package com.whitegame.app.data

import com.whitegame.app.model.DnsItem

object DnsData {
    fun fallback(): List<DnsItem> = listOf(
        DnsItem(1, "178.22.122.100", "Shecan", "Primary · anti-sanction", "IR"),
        DnsItem(2, "185.51.200.2", "Shecan", "Secondary", "IR"),
        DnsItem(3, "10.202.10.202", "403.online", "RFC1918 · games/stores", "IR"),
        DnsItem(4, "1.1.1.1", "Cloudflare", "Anycast", "INT"),
        DnsItem(5, "8.8.8.8", "Google", "Standard", "INT")
    )

    fun scoreDns(avg: Long?, loss: Int, jitter: Long?): Pair<Int, String> {
        if (avg == null) return 0 to "Unreachable"
        var score = 100
        score -= (loss * 1.6).toInt()
        score -= ((jitter ?: 0) / 2).toInt()
        score -= when {
            avg > 120 -> 35
            avg > 80 -> 20
            avg > 50 -> 10
            avg > 30 -> 4
            else -> 0
        }
        score = score.coerceIn(0, 100)
        val label = when {
            score >= 85 -> "Excellent"
            score >= 70 -> "Good"
            score >= 50 -> "Acceptable"
            score >= 30 -> "Poor"
            else -> "Not suitable"
        }
        return score to label
    }

    fun distanceScore(avg: Long?, loss: Int?, jitter: Long?): Double {
        if (avg == null) return 99999.0
        val j = (jitter ?: 0).toDouble()
        val l = (loss ?: 100).toDouble()
        return avg + j * 1.5 + l * 20.0
    }
}
