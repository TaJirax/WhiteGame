package com.whitegame.app.data

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateResult(
    val ok: Boolean,
    val message: String,
    val count: Int = 0,
    val version: String = ""
)

@Singleton
class RemoteCatalog @Inject constructor(
    private val context: Context
) {
    private val dnsCache get() = File(context.filesDir, "dns_remote.json")
    private val gamesCache get() = File(context.filesDir, "games_remote.json")

    fun cachedDnsText(): String? = readIfValid(dnsCache)
    fun cachedGamesText(): String? = readIfValid(gamesCache)

    /**
     * Resolvers for the device's own country first — those are the ones worth pinging — then
     * the global list when the directory has no file for that country.
     */
    fun updateDns(): UpdateResult {
        val country = deviceCountry()
        if (country.isNotBlank()) {
            val local = downloadAndCache(RemoteEndpoints.dnsUrlForCountry(country), dnsCache, "dns")
            if (local.ok) return local.copy(message = local.message + " · " + country.uppercase())
        }
        return downloadAndCache(RemoteEndpoints.DNS_GLOBAL_URL, dnsCache, "dns")
    }

    /** SIM country beats locale: a phone roaming on an Iranian SIM wants Iranian resolvers. */
    private fun deviceCountry(): String {
        val tm = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()
        val sim = runCatching { tm?.simCountryIso.orEmpty() }.getOrDefault("")
        if (sim.length == 2) return sim
        val network = runCatching { tm?.networkCountryIso.orEmpty() }.getOrDefault("")
        if (network.length == 2) return network
        // Configuration.locales is API 24; below that the single locale field is all there is.
        val locale = runCatching { java.util.Locale.getDefault().country }.getOrDefault("")
        return if (locale.length == 2) locale else ""
    }

    fun updateGames(): UpdateResult {
        if (RemoteEndpoints.gamesUrl.isBlank()) {
            return UpdateResult(true, "Local games catalog refreshed; online updates are not configured")
        }
        return downloadAndCache(RemoteEndpoints.gamesUrl, gamesCache, "games")
    }

    private fun readIfValid(file: File): String? {
        if (!file.exists() || file.length() < 8) return null
        return try {
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAndCache(url: String, cache: File, kind: String): UpdateResult {
        if (url.isBlank()) {
            return UpdateResult(false, "URL is empty — set RemoteEndpoints")
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = RemoteEndpoints.CONNECT_TIMEOUT_MS
                readTimeout = RemoteEndpoints.READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WhiteGame/6.3")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UpdateResult(false, "HTTP $code from server")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val (count, version) = validate(kind, body)
            if (count <= 0) {
                return UpdateResult(false, "Invalid $kind JSON (0 items)")
            }
            cache.writeText(body)
            UpdateResult(true, "$kind updated · $count items", count, version)
        } catch (e: Exception) {
            UpdateResult(false, "Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun validate(kind: String, body: String): Pair<Int, String> {
        val trimmed = body.trim()
        val version: String
        val arr: JSONArray
        when {
            trimmed.startsWith("[") -> {
                arr = JSONArray(trimmed)
                version = ""
            }
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val stamp = obj.optString("version")
                    .ifBlank { obj.optString("updatedAt") }
                    .ifBlank { obj.optString("generated_at") }
                    .ifBlank { obj.optJSONObject("metadata")?.optString("generated_at").orEmpty() }
                // "2026-09-10T18:04:01Z" reads better as a date in the UI.
                version = stamp.take(10)
                arr = obj.optJSONArray("items")
                    ?: obj.optJSONArray("resolvers")
                    ?: obj.optJSONArray("dns")
                    ?: obj.optJSONArray("games")
                    ?: JSONArray()
            }
            else -> return 0 to ""
        }
        if (kind == "dns") {
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("ip").isNotBlank()) n++
            }
            return n to version
        } else {
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name").isNotBlank() || o.optString("id").isNotBlank()) n++
            }
            return n to version
        }
    }
}
