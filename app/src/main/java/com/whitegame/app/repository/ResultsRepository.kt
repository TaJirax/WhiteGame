package com.whitegame.app.repository

import android.content.Context
import android.content.SharedPreferences
import com.whitegame.app.model.DnsItem
import com.whitegame.app.model.ProxyNode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class SavedDnsSnapshot(
    val id: String,
    val title: String,
    val timestamp: Long,
    val items: List<DnsItem>
)

data class SavedProxySnapshot(
    val id: String,
    val title: String,
    val timestamp: Long,
    val nodes: List<ProxyNode>
)

@Singleton
class ResultsRepository @Inject constructor(
    private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wg_results", Context.MODE_PRIVATE)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    // ---------- DNS snapshots ----------

    fun saveDns(title: String, items: List<DnsItem>): String {
        val id = "dns_${System.currentTimeMillis()}"
        val arr = JSONArray()
        items.filter { it.pingMs != null }.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("ip", d.ip)
                put("provider", d.provider)
                put("notes", d.notes)
                put("regionHint", d.regionHint)
                put("pingMs", d.pingMs)
                put("lossPct", d.lossPct)
                put("jitterMs", d.jitterMs)
                put("status", d.status)
                put("score", d.score)
                put("gameLabel", d.gameLabel)
                put("gameAdvice", d.gameAdvice)
            })
        }
        val meta = JSONObject().apply {
            put("id", id)
            put("title", title.ifBlank { "DNS ${dateFmt.format(Date())}" })
            put("timestamp", System.currentTimeMillis())
            put("items", arr)
        }
        prefs.edit().putString(id, meta.toString()).apply()
        rememberId("dns_ids", id)
        return id
    }

    fun listDnsSnapshots(): List<SavedDnsSnapshot> {
        return loadIds("dns_ids").mapNotNull { loadDns(it) }
            .sortedByDescending { it.timestamp }
    }

    fun loadDns(id: String): SavedDnsSnapshot? {
        val raw = prefs.getString(id, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DnsItem(
                    id = d.optInt("id"),
                    ip = d.getString("ip"),
                    provider = d.optString("provider"),
                    notes = d.optString("notes"),
                    regionHint = d.optString("regionHint"),
                    pingMs = if (d.has("pingMs") && !d.isNull("pingMs")) d.getLong("pingMs") else null,
                    lossPct = if (d.has("lossPct") && !d.isNull("lossPct")) d.getInt("lossPct") else null,
                    jitterMs = if (d.has("jitterMs") && !d.isNull("jitterMs")) d.getLong("jitterMs") else null,
                    status = d.optString("status", "—"),
                    score = d.optInt("score"),
                    gameLabel = d.optString("gameLabel"),
                    gameAdvice = d.optString("gameAdvice")
                )
            }
            SavedDnsSnapshot(o.getString("id"), o.optString("title"), o.getLong("timestamp"), items)
        } catch (_: Exception) { null }
    }

    fun deleteDns(id: String) {
        prefs.edit().remove(id).apply()
        forgetId("dns_ids", id)
    }

    // ---------- Proxy snapshots ----------

    fun saveProxy(title: String, nodes: List<ProxyNode>): String {
        val id = "proxy_${System.currentTimeMillis()}"
        val arr = JSONArray()
        nodes.forEach { n ->
            arr.put(JSONObject().apply {
                put("index", n.index)
                put("protocol", n.protocol)
                put("host", n.host)
                put("port", n.port)
                put("name", n.name)
                put("security", n.security)
                put("network", n.network)
                put("rawPreview", n.rawPreview)
                put("isUdpHeavy", n.isUdpHeavy)
                put("resolveMs", n.resolveMs)
                put("tcpMs", n.tcpMs)
                put("tcpLoss", n.tcpLoss)
                put("tcpJitter", n.tcpJitter)
                put("tlsMs", n.tlsMs)
                put("udpMs", n.udpMs)
                put("udpLoss", n.udpLoss)
                put("udpJitter", n.udpJitter)
                put("udpStatus", n.udpStatus)
                put("reachable", n.reachable)
                put("detail", n.detail)
                put("gameLabel", n.gameLabel)
                put("gameAdvice", n.gameAdvice)
                put("gameRegion", n.gameRegion)
                put("gamePingMin", n.gamePingMin)
                put("gamePingMax", n.gamePingMax)
                put("gameLossMin", n.gameLossMin)
                put("gameLossMax", n.gameLossMax)
                put("serverRegion", n.serverRegion)
                put("serverFlag", n.serverFlag)
                put("dropRisk", n.dropRisk)
                put("dropAdvice", n.dropAdvice)
                put("stabilityScore", n.stabilityScore)
                put("caption", n.caption)
            })
        }
        val meta = JSONObject().apply {
            put("id", id)
            put("title", title.ifBlank { "Proxy ${dateFmt.format(Date())}" })
            put("timestamp", System.currentTimeMillis())
            put("nodes", arr)
        }
        prefs.edit().putString(id, meta.toString()).apply()
        rememberId("proxy_ids", id)
        return id
    }

    fun listProxySnapshots(): List<SavedProxySnapshot> {
        return loadIds("proxy_ids").mapNotNull { loadProxy(it) }
            .sortedByDescending { it.timestamp }
    }

    fun loadProxy(id: String): SavedProxySnapshot? {
        val raw = prefs.getString(id, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("nodes")
            fun longOrNull(d: JSONObject, k: String): Long? =
                if (d.has(k) && !d.isNull(k)) d.getLong(k) else null
            fun intOrNull(d: JSONObject, k: String): Int? =
                if (d.has(k) && !d.isNull(k)) d.getInt(k) else null
            fun boolOrNull(d: JSONObject, k: String): Boolean? =
                if (d.has(k) && !d.isNull(k)) d.getBoolean(k) else null

            val nodes = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                ProxyNode(
                    index = d.optInt("index"),
                    protocol = d.optString("protocol"),
                    host = d.optString("host"),
                    port = d.optInt("port"),
                    name = d.optString("name"),
                    security = d.optString("security"),
                    network = d.optString("network"),
                    rawPreview = d.optString("rawPreview"),
                    isUdpHeavy = d.optBoolean("isUdpHeavy"),
                    resolveMs = longOrNull(d, "resolveMs"),
                    tcpMs = longOrNull(d, "tcpMs"),
                    tcpLoss = intOrNull(d, "tcpLoss"),
                    tcpJitter = longOrNull(d, "tcpJitter"),
                    tlsMs = longOrNull(d, "tlsMs"),
                    udpMs = longOrNull(d, "udpMs"),
                    udpLoss = intOrNull(d, "udpLoss"),
                    udpJitter = longOrNull(d, "udpJitter"),
                    udpStatus = d.optString("udpStatus"),
                    reachable = boolOrNull(d, "reachable"),
                    detail = d.optString("detail"),
                    gameLabel = d.optString("gameLabel"),
                    gameAdvice = d.optString("gameAdvice"),
                    gameRegion = d.optString("gameRegion"),
                    gamePingMin = longOrNull(d, "gamePingMin"),
                    gamePingMax = longOrNull(d, "gamePingMax"),
                    gameLossMin = intOrNull(d, "gameLossMin"),
                    gameLossMax = intOrNull(d, "gameLossMax"),
                    serverRegion = d.optString("serverRegion"),
                    serverFlag = d.optString("serverFlag"),
                    dropRisk = d.optString("dropRisk"),
                    dropAdvice = d.optString("dropAdvice"),
                    stabilityScore = d.optInt("stabilityScore"),
                    caption = d.optString("caption")
                )
            }
            SavedProxySnapshot(o.getString("id"), o.optString("title"), o.getLong("timestamp"), nodes)
        } catch (_: Exception) { null }
    }

    fun deleteProxy(id: String) {
        prefs.edit().remove(id).apply()
        forgetId("proxy_ids", id)
    }

    // ---------- helpers ----------

    private fun rememberId(key: String, id: String) {
        val set = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        set.add(id)
        prefs.edit().putStringSet(key, set).apply()
    }

    private fun forgetId(key: String, id: String) {
        val set = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        set.remove(id)
        prefs.edit().putStringSet(key, set).apply()
    }

    private fun loadIds(key: String): List<String> =
        prefs.getStringSet(key, emptySet())?.toList() ?: emptyList()
}
