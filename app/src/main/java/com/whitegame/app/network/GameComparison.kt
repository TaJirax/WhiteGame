package com.whitegame.app.network

/** Select one working HTTPS endpoint, then keep every route on that same endpoint. */
object GameComparison {
    data class Selection(val host: String, val results: List<RouteProbe.Result>, val checked: Int)

    suspend fun selectEndpoint(hosts: List<String>, sample: suspend (String) -> List<RouteProbe.Result>): Selection {
        val candidates = hosts.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        require(candidates.isNotEmpty()) { "No HTTPS probe hosts in the catalog" }
        var first: Selection? = null
        for ((index, host) in candidates.withIndex()) {
            val selection = Selection(host, sample(host), index + 1)
            if (first == null) first = selection
            // A tunnel can reach the game even while the phone's direct route is blocked.
            if (selection.results.any { it.ms != null }) return selection
        }
        return first!!.copy(checked = candidates.size)
    }
}
