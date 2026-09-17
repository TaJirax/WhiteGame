package com.whitegame.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What Google Play knows about an installed app: its store title, its genre, and the domains
 * its publisher runs (support e-mail, privacy policy) — the nearest thing to "the game's
 * servers" that can be learnt without the game's own catalog entry.
 */
data class PlayInfo(
    val packageName: String,
    val title: String,
    /** GAME_ACTION, COMMUNICATION, … — empty when Play did not say. */
    val category: String,
    val hosts: List<String>
) {
    val isGame: Boolean get() = category.startsWith("GAME")

    /** GAME_BATTLE_ROYALE -> "Battle Royale". */
    val genre: String
        get() = category.removePrefix("GAME_").split('_')
            .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
            .ifBlank { "Game" }
}

object PlayStore {

    /** Consumer web client domains that say nothing about where a game is hosted. */
    private val GENERIC = listOf(
        "google.", "gmail.", "googlemail.", "youtube.", "ytimg.", "ggpht.", "gstatic.", "android.com",
        "outlook.", "hotmail.", "live.com", "yahoo.", "icloud.", "qq.com", "163.com", "126.com",
        "naver.com", "mail.ru", "yandex.", "proton", "facebook.com", "instagram.com", "twitter.com",
        "x.com", "discord.gg", "w3.org", "schema.org", "apple.com", "github.io", "sites.google"
    )

    fun parse(packageName: String, html: String): PlayInfo? {
        val title = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)
            ?.groupValues?.get(1)
            ?.removeSuffix(" - Apps on Google Play")
            ?.let(::unescape)
            ?: return null
        // The genre chip is the first category link that is not the "family" badge.
        val category = Regex("/store/apps/category/([A-Z_]+)").findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { it !in setOf("GAME", "APPLICATION", "FAMILY") && !it.startsWith("FAMILY_") }
            .orEmpty()
        val emailDomains = Regex("mailto:[^@\"\\s]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})").findAll(html)
            .map { it.groupValues[1].lowercase() }
        val privacyHosts = Regex("\"https?://([A-Za-z0-9.-]+\\.[A-Za-z]{2,})/[^\"]*privacy[^\"]*\"", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1].lowercase() }
        val hosts = (emailDomains + privacyHosts)
            .filter { h -> GENERIC.none { h.contains(it) } }
            .distinct()
            .take(3)
            .toList()
        return PlayInfo(packageName, title, category, hosts)
    }

    fun fetch(packageName: String): PlayInfo? = try {
        val conn = (URL(RemoteEndpoints.playStoreDetails(packageName)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        }
        try {
            val html = if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                    if (text.length > 4_000_000) error("Play response too large")
                }
                text.toString()
            } else null
            html?.let { parse(packageName, it) }
        } finally { conn.disconnect() }
    } catch (_: Exception) {
        null
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
}

/**
 * Play answers per package, kept on disk: an app's genre does not change, and asking again on
 * every scan would both be slow and send the installed-app list to Google each time.
 */
class PlayCache(context: Context) {
    private val prefs = context.getSharedPreferences("play_cache", Context.MODE_PRIVATE)

    /** null = never asked; a [PlayInfo] with blank category = asked, Play did not know it. */
    fun get(pkg: String): PlayInfo? = prefs.getString(pkg, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("checkedAt", 0) > 7L * 24 * 60 * 60 * 1000) return@runCatching null
            val h = o.optJSONArray("hosts") ?: JSONArray()
            PlayInfo(pkg, o.optString("title"), o.optString("category"), (0 until h.length()).map { h.getString(it) })
        }.getOrNull()
    }

    fun has(pkg: String): Boolean = prefs.contains(pkg)

    fun put(info: PlayInfo) {
        prefs.edit().putString(info.packageName, JSONObject()
            .put("checkedAt", System.currentTimeMillis())
            .put("title", info.title)
            .put("category", info.category)
            .put("hosts", JSONArray(info.hosts))
            .toString()).apply()
    }

    /** Lookups that failed (offline, Play blocked) are not cached, so they retry next time. */
    fun putUnknown(pkg: String) = put(PlayInfo(pkg, "", "", emptyList()))
}
