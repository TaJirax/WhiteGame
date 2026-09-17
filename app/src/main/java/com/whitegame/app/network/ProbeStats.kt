package com.whitegame.app.network

/** Connection-probe statistics, not an estimate of gameplay UDP packet loss. */
data class ProbeStats(val attempts: Int, val successful: Int, val latencyMs: Long?, val jitterMs: Long?, val lossPct: Int?) {
    companion object {
        fun from(samples: List<Long?>): ProbeStats {
            val good = samples.filterNotNull()
            val differences = samples.zipWithNext().mapNotNull { (a, b) ->
                if (a != null && b != null) kotlin.math.abs(a - b) else null
            }
            return ProbeStats(samples.size, good.size,
                good.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                differences.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                samples.takeIf { it.isNotEmpty() }?.let { (it.size - good.size) * 100 / it.size })
        }
    }
}
