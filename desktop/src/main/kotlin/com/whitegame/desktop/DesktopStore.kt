package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.data.SubscriptionSource
import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class DesktopStore(val directory: Path) {
    private val file = directory.resolve("whitegame.json")
    private var data = if (Files.exists(file)) JSONObject(Files.readString(file)) else JSONObject()

    val profiles: List<DesktopProfile>
        get() = data.objects("profiles").map {
            desktopProfile(it.getString("config"), it.getString("name"), it.getString("id"))
        }

    val reports: List<DesktopReport>
        get() = data.objects("reports").map { report ->
            DesktopReport(
                report.getString("id"), DesktopReportKind.valueOf(report.getString("kind")),
                report.getString("title"), report.objects("rows").map(::readMeasurement)
            )
        }

    val dnsResolvers: List<DesktopDnsResolver>
        get() = data.objects("dnsResolvers").mapNotNull {
            runCatching {
                customDnsResolver(it.getString("name"), it.getString("address"), it.getString("id"))
            }.getOrNull()
        }

    fun string(key: String): String = data.optString(key)

    fun flag(key: String, fallback: Boolean): Boolean = data.optBoolean(key, fallback)

    fun put(vararg values: Pair<String, Any?>) {
        val next = JSONObject(data.toString())
        for ((key, value) in values) next.put(key, value)
        Files.createDirectories(directory)
        val tmp = Files.createTempFile(directory, "settings-", ".tmp")
        try {
            Files.writeString(tmp, next.toString(2))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            data = next
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun saveProfiles(profiles: List<DesktopProfile>, selectedId: String?) {
        put("selectedId" to selectedId, "profiles" to JSONArray(profiles.map {
            JSONObject().put("id", it.id).put("name", it.name).put("config", it.config)
        }))
    }

    fun saveReports(reports: List<DesktopReport>) {
        put("reports" to JSONArray(reports.map {
            JSONObject().put("id", it.id).put("kind", it.kind.name).put("title", it.title)
                .put("rows", JSONArray(it.rows.map(::writeMeasurement)))
        }))
    }

    fun saveDnsResolvers(resolvers: List<DesktopDnsResolver>) {
        put("dnsResolvers" to JSONArray(resolvers.map {
            JSONObject().put("id", it.id).put("name", it.name).put("address", it.address)
        }))
    }

    private fun writeMeasurement(row: DesktopMeasurement): JSONObject = JSONObject()
        .put("id", row.id).put("name", row.name).put("endpoint", row.endpoint)
        .put("protocol", row.protocol).put("latencyMs", row.latencyMs).put("jitterMs", row.jitterMs)
        .put("lossPercent", row.lossPercent).put("downloadMbps", row.downloadMbps)
        .put("uploadMbps", row.uploadMbps).put("detail", row.detail).put("status", row.status)

    private fun readMeasurement(row: JSONObject): DesktopMeasurement = DesktopMeasurement(
        row.getString("id"), row.getString("name"), row.optString("endpoint"), row.optString("protocol"),
        row.metric("latencyMs"), row.metric("jitterMs"), row.metric("lossPercent"),
        row.metric("downloadMbps"), row.metric("uploadMbps"), row.optString("detail"), row.optString("status")
    )

    private fun JSONObject.metric(key: String): Double? = optDouble(key).takeIf { it.isFinite() }
}

internal fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

internal fun desktopProfile(config: String, name: String = "", id: String = UUID.randomUUID().toString()): DesktopProfile {
    val input = config.trim()
    val title = name.trim().take(80)
    if (XrayConfig.looksLikeXray(input)) {
        val parsed = XrayConfig.parse(input)
        return DesktopProfile(id, title.ifBlank { parsed.name }, "Xray", parsed.endpoint, input, true)
    }
    val parsed = AwgConfig.parse(input)
    val fromLink = AwgConfig.isShareLink(input)
    return DesktopProfile(
        id, title.ifBlank { (if (fromLink) AwgConfig.shareLinkName(input) else null) ?: parsed.kind.label },
        parsed.kind.label, parsed.primaryEndpoint.orEmpty(), if (fromLink) parsed.toConfText() else input,
        parsed.isFullTunnel
    )
}

internal fun DesktopProfile.configurationIdentity(): String =
    if (kind == "Xray") config else AwgConfig.parse(config).toConfText()

internal fun desktopConfigInputs(text: String): List<String> {
    val input = text.trim().removePrefix("\uFEFF").trim()
    if (input.isEmpty()) throw ConfigException("Paste a config or select a saved profile.")
    if (XrayConfig.looksLikeXray(input) && input.startsWith("{")) return listOf(input)
    if (!Regex("(?im)^\\s*\\[Interface]\\s*$").containsMatchIn(input)) {
        val supported = input.lines().map(String::trim)
            .filter { XrayConfig.looksLikeXray(it) || AwgConfig.isShareLink(it) }
        if (supported.isNotEmpty()) return supported
    }
    return SubscriptionSource.parse(input).map { it.conf }.filter { it.isNotBlank() }
        .ifEmpty { listOf(input) }
}
