package com.whitegame.app.data

import android.content.Context
import com.whitegame.app.model.SubNode
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Loads the user's own subscription. There is deliberately no built-in list: a hard-coded
 * third-party link hands people servers nobody here controls, and the keys it carries are
 * useless the day that link rots — which is exactly what happened to the old one.
 *
 * Understands what a WireGuard subscription actually ships:
 *  - one or more `[Interface]` blocks (plain .conf text),
 *  - sing-box JSON (`endpoints` / `outbounds` of type wireguard),
 *  - either of those wrapped in base64,
 *  - bare `host:port` lines, which can only be ranked by ping, not connected to.
 */
@Singleton
class ConfigSubscription @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("wg_subscription", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    fun fetch(from: String = url): List<SubNode> {
        val target = from.trim()
        if (target.isBlank()) error("Set your subscription URL first")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            error("Subscription URL must start with http:// or https://")
        }
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WhiteGame/7.1")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            // Panels answer with a JSON reason ("Warp Not Configured", bad token…); show it.
            val reason = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            val message = runCatching { JSONObject(reason).optString("message") }.getOrNull()
                .orEmpty().ifBlank { reason.trim().take(120) }
            error("HTTP " + code + if (message.isNotBlank()) " · " + message else "")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val nodes = parse(body)
        if (nodes.isEmpty()) error("No servers found in that subscription")
        return nodes
    }

    companion object {
        private const val KEY_URL = "sub_url"

        fun parse(body: String, depth: Int = 0): List<SubNode> {
            val t = body.trim()
            if (t.isEmpty()) return emptyList()

            if (t.contains("[Interface]", ignoreCase = true)) return fromConfText(t)
            if (t.startsWith("{") || t.startsWith("[")) return fromSingBox(t)

            // Whole-body base64 is the most common subscription wrapper.
            if (depth == 0) decodeBase64(t)?.let { return parse(it, depth + 1) }
            return fromHostPortLines(t)
        }

        /** Plain .conf text: each [Interface] block is a tunnel that can be saved as-is. */
        private fun fromConfText(text: String): List<SubNode> {
            val starts = Regex("(?im)^\\s*\\[Interface\\]\\s*$")
                .findAll(text).map { it.range.first }.toList()
            return starts.mapIndexedNotNull { i, start ->
                val chunk = text.substring(start, starts.getOrNull(i + 1) ?: text.length).trim()
                val endpoint = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+)$")
                    .find(chunk)?.groupValues?.get(1)?.trim().orEmpty()
                val host = endpoint.substringBeforeLast(':', "").trim('[', ']')
                val port = endpoint.substringAfterLast(':', "").toIntOrNull()
                if (host.isBlank() || port == null) return@mapIndexedNotNull null
                val name = Regex("(?im)^\\s*#\\s*Name\\s*=\\s*(.+)$")
                    .find(chunk)?.groupValues?.get(1)?.trim() ?: host
                node(i, name, host, port, "wireguard", chunk)
            }
        }

        /** sing-box config: a wireguard endpoint or outbound carries everything a .conf needs. */
        private fun fromSingBox(text: String): List<SubNode> {
            val arrays = mutableListOf<JSONArray>()
            if (text.startsWith("[")) {
                arrays += JSONArray(text)
            } else {
                val root = JSONObject(text)
                root.optJSONArray("endpoints")?.let { arrays += it }
                root.optJSONArray("outbounds")?.let { arrays += it }
            }
            val out = mutableListOf<SubNode>()
            for (arr in arrays) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val peer = o.optJSONArray("peers")?.optJSONObject(0)
                    val host = o.optString("server")
                        .ifBlank { peer?.optString("address").orEmpty() }
                        .ifBlank { peer?.optString("server").orEmpty() }
                    if (host.isBlank()) continue
                    val port = when {
                        o.optInt("server_port", 0) > 0 -> o.optInt("server_port")
                        (peer?.optInt("port", 0) ?: 0) > 0 -> peer!!.optInt("port")
                        else -> 443
                    }
                    val tag = o.optString("tag", "node-" + (i + 1))
                    val type = o.optString("type", "unknown")
                    out += node(out.size, tag, host, port, type, wireguardConf(o, peer, host, port))
                }
            }
            return out
        }

        /** Builds .conf text from a sing-box wireguard entry; blank when it is not a wireguard one. */
        private fun wireguardConf(o: JSONObject, peer: JSONObject?, host: String, port: Int): String {
            if (!o.optString("type").equals("wireguard", ignoreCase = true)) return ""
            val privateKey = o.optString("private_key")
            if (privateKey.isBlank()) return ""
            val addresses = (o.optJSONArray("address") ?: o.optJSONArray("local_address"))
                ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }
                .orEmpty()
            if (addresses.isEmpty()) return ""
            val publicKey = o.optString("peer_public_key")
                .ifBlank { peer?.optString("public_key").orEmpty() }
            if (publicKey.isBlank()) return ""
            val allowed = peer?.optJSONArray("allowed_ips")
                ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }
                ?.takeIf { it.isNotEmpty() } ?: listOf("0.0.0.0/0", "::/0")
            val mtu = o.optInt("mtu", 0).takeIf { it in 576..9000 } ?: 1280
            val keepalive = (peer?.optInt("persistent_keepalive_interval", 0) ?: 0).takeIf { it > 0 } ?: 25
            return buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = " + privateKey)
                appendLine("Address = " + addresses.joinToString(", "))
                appendLine("MTU = " + mtu)
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = " + publicKey)
                appendLine("AllowedIPs = " + allowed.joinToString(", "))
                appendLine("Endpoint = " + host + ":" + port)
                appendLine("PersistentKeepalive = " + keepalive)
            }
        }

        /** Last resort: a list that only mentions host:port. Ping-only, nothing to connect to. */
        private fun fromHostPortLines(text: String): List<SubNode> {
            val hostPort = Regex("([A-Za-z0-9.\\-]+\\.[A-Za-z0-9\\-]+|\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d{2,5})")
            return text.lines().mapIndexedNotNull { i, line ->
                val m = hostPort.find(line) ?: return@mapIndexedNotNull null
                val port = m.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
                // Share links (vless://…#Name) carry their label after '#'.
                val label = runCatching {
                    java.net.URLDecoder.decode(line.substringAfterLast('#', ""), "UTF-8")
                }.getOrDefault("").ifBlank { line.trim().take(40) }
                node(i, label, m.groupValues[1], port, line.substringBefore("://", "raw").trim(), "")
            }
        }

        private fun node(i: Int, name: String, host: String, port: Int, type: String, conf: String): SubNode {
            val region = guessRegion(name, host)
            return SubNode(
                id = host + ":" + port + ":" + i,
                name = name.ifBlank { host },
                host = host,
                port = port,
                type = type,
                region = region.first,
                flag = region.second,
                gamesHint = region.third,
                conf = conf
            )
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun decodeBase64(text: String): String? {
            val compact = text.filterNot { it.isWhitespace() }
            if (compact.length < 24 || !compact.matches(Regex("[A-Za-z0-9+/=_-]+"))) return null
            // Normalise the URL-safe alphabet: combining URL_SAFE with DEFAULT rejects '+' and
            // '/', which is what nearly every subscription actually uses.
            val standard = compact.replace('-', '+').replace('_', '/').trimEnd('=')
            val decoded = runCatching {
                String(Base64.decode(standard.padEnd(standard.length + (4 - standard.length % 4) % 4, '=')))
            }.getOrNull() ?: return null
            val looksReal = decoded.contains("[Interface]", ignoreCase = true) ||
                decoded.trimStart().startsWith("{") ||
                decoded.trimStart().startsWith("[") ||
                decoded.contains("://")
            return if (looksReal) decoded else null
        }

        private fun guessRegion(tag: String, host: String): Triple<String, String, String> {
            val s = (tag + " " + host).lowercase()
            return when {
                "🇩🇪" in tag || "de" in s || "germany" in s || "frankfurt" in s ->
                    Triple("Europe-DE", "🇩🇪", "EU games · Valorant EU · MLBB EU · CODM EU")
                "🇫🇮" in tag || "fi " in s || "finland" in s ->
                    Triple("Europe-FI", "🇫🇮", "EU path")
                "🇳🇱" in tag || "nl " in s || "amsterdam" in s ->
                    Triple("Europe-NL", "🇳🇱", "EU games")
                "🇹🇷" in tag || "tr " in s || "turkey" in s || "istanbul" in s ->
                    Triple("Turkey", "🇹🇷", "TR / nearby EU · Wild Rift TR")
                "🇸🇬" in tag || "sg" in s || "singapore" in s ->
                    Triple("Asia-SG", "🇸🇬", "MLBB · Free Fire · PUBG SG")
                "🇯🇵" in tag || "jp" in s || "tokyo" in s ->
                    Triple("Asia-JP", "🇯🇵", "Asia latency-sensitive titles")
                "🇺🇸" in tag || "us" in s || "america" in s ->
                    Triple("US", "🇺🇸", "NA servers · Fortnite NA · Roblox")
                else -> Triple("Unknown", "🌐", "General online play")
            }
        }
    }
}
