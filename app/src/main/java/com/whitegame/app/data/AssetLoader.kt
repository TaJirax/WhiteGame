package com.whitegame.app.data

import android.content.Context
import com.whitegame.app.model.DnsItem
import com.whitegame.app.model.GameInfo
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetLoader @Inject constructor(
    private val context: Context,
    private val remoteCatalog: RemoteCatalog
) {
    /**
     * Bundled entries first — those are hand-picked (anti-sanction resolvers, game DNS) and
     * would be lost if a downloaded list simply replaced them — then whatever the last update
     * fetched, deduplicated by IP.
     */
    fun loadDns(): List<DnsItem> {
        val bundled = readAsset("dns.json")?.let { parseDns(it) }.orEmpty()
        val remote = remoteCatalog.cachedDnsText()?.let { parseDns(it) }.orEmpty()
        val merged = (bundled + remote)
            .distinctBy { it.ip }
            .mapIndexed { i, d -> d.copy(id = i + 1) }
        return merged.ifEmpty { DnsData.fallback() }
    }

    fun loadGames(): List<GameInfo> {
        val bundled = readAsset("games.json")?.let { parseGames(it) }.orEmpty()
        val catalog = readAsset("games_online.json")?.let { parseGames(it) }.orEmpty()
        val remote = remoteCatalog.cachedGamesText()?.let { parseGames(it) }.orEmpty()
        // Bundled first: those carry package names, so installed-game detection keeps working.
        val merged = (bundled + remote + catalog).distinctBy { it.id }
        return merged.ifEmpty { GamesData.fallback() }
    }

    private fun readAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun extractArray(text: String): JSONArray {
        val t = text.trim()
        if (t.startsWith("[")) return JSONArray(t)
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            return o.optJSONArray("items")
                ?: o.optJSONArray("resolvers")
                ?: o.optJSONArray("dns")
                ?: o.optJSONArray("games")
                ?: JSONArray()
        }
        return JSONArray()
    }

    /**
     * Reads either the app's own shape or public-dns-directory's resolver records. A country
     * file can hold hundreds of resolvers and every one of them costs a probe round, so the
     * directory entries are ranked by uptime and capped.
     */
    private fun parseDns(text: String): List<DnsItem> = try {
        val arr = extractArray(text)
        val own = mutableListOf<DnsItem>()
        val directory = mutableListOf<Pair<Double, DnsItem>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ip = o.optString("ip")
            if (ip.isBlank()) continue
            val provider = o.optString("provider")
            if (provider.isNotBlank() || o.has("regionHint")) {
                own += DnsItem(
                    id = o.optInt("id", i + 1),
                    ip = ip,
                    provider = provider.ifBlank { "Unknown" },
                    notes = o.optString("notes", ""),
                    regionHint = o.optString("regionHint", ""),
                    gaming = o.optBoolean("gaming", true)
                )
                continue
            }
            val uptime = o.optJSONObject("uptime")?.optDouble("30d", 0.0) ?: 0.0
            val trusted = o.optBoolean("trusted", false)
            directory += (if (trusted) uptime + 1000 else uptime) to DnsItem(
                id = i + 1,
                ip = ip,
                provider = o.optString("organization").ifBlank { o.optString("domain") }
                    .ifBlank { "Public resolver" },
                notes = dnsNotes(o),
                regionHint = o.optString("country_code", o.optString("country", "")).uppercase(),
                gaming = true
            )
        }
        own + directory.sortedByDescending { it.first }.take(MAX_DIRECTORY_DNS).map { it.second }
    } catch (_: Exception) {
        emptyList()
    }

    private fun dnsNotes(o: JSONObject): String {
        val bits = mutableListOf<String>()
        o.optString("domain").takeIf { it.isNotBlank() }?.let { bits += it }
        o.optJSONObject("uptime")?.optDouble("30d", -1.0)?.takeIf { it >= 0 }
            ?.let { bits += "up " + it.toInt() + "%" }
        if (o.optBoolean("anycast")) bits += "anycast"
        if (o.optJSONObject("dnssec")?.optBoolean("validating") == true) bits += "dnssec"
        o.optJSONObject("blocking")?.let { b ->
            if (b.optBoolean("ads")) bits += "ad-block"
            if (b.optBoolean("malware")) bits += "malware-block"
        }
        if (o.optBoolean("trusted")) bits += "trusted"
        return bits.joinToString(" · ")
    }

    /** Reads the app's own shape or the online-games database (tcp_ports / test_endpoints). */
    private fun parseGames(text: String): List<GameInfo> = try {
        val arr = extractArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "Game")
            val category = o.optString("category", "")
            val hosts = o.strList("hosts").ifEmpty { o.strList("test_endpoints") }
            val platforms = o.strList("platforms")
            GameInfo(
                id = o.optString("id").ifBlank { slug(name) },
                name = name,
                category = category,
                hosts = hosts,
                tcpPorts = o.portList("tcpPorts").ifEmpty { o.portList("tcp_ports") },
                udpPorts = o.portList("udpPorts").ifEmpty { o.portList("udp_ports") },
                regions = o.strList("regions"),
                pingNeed = o.optString("pingNeed").ifBlank { pingNeedFor(category) },
                notes = o.optString("notes").ifBlank { platforms.joinToString(" · ") },
                packageNames = o.strList("packageNames")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JSONObject.strList(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /** Ports arrive as numbers, as strings, or as ranges ("27015-27050") — take the low end. */
    private fun JSONObject.portList(key: String): List<Int> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optString(i).substringBefore('-').trim().toIntOrNull()?.takeIf { it in 1..65535 }
        }
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "game" }

    private fun pingNeedFor(category: String): String = when (category) {
        "FPS", "Fighting", "Racing", "Battle Royale" -> "Critical <60ms"
        "MOBA", "Sports", "Party" -> "<80ms"
        "MMO", "Survival", "Sandbox", "Strategy", "Mobile" -> "<120ms"
        else -> "<150ms"
    }

    private companion object {
        const val MAX_DIRECTORY_DNS = 40
    }
}
