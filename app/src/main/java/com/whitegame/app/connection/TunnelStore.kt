package com.whitegame.app.connection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One saved tunnel. [confText] is the source of truth; everything else is derived at save time. */
data class SavedTunnel(
    val id: String,
    val name: String,
    val confText: String,
    val kind: TunnelKind,
    val endpoint: String,
    val isFullTunnel: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long = 0L
) {
    val isXray: Boolean get() = kind == TunnelKind.XRAY

    /** Re-parses the stored text. Throws [ConfigException] if it was saved by an older/broken build. */
    fun parse(): AwgConfig = AwgConfig.parse(confText)

    /** The Xray side of [parse]. */
    fun parseXray(): XrayConfig.Parsed = XrayConfig.parse(confText)

    /** Validates whichever kind this is; null when it is fine. */
    fun problem(): String? = try {
        if (isXray) parseXray() else parse()
        null
    } catch (e: Exception) {
        e.message ?: "invalid"
    }
}

/**
 * Persists WireGuard / AmneziaWG / WARP profiles and Xray share links.
 *
 * Configs contain private keys, so they live in the app's private SharedPreferences and are
 * never logged or included in exported diagnostics.
 */
@Singleton
class TunnelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("wg_tunnels", Context.MODE_PRIVATE)

    private val _tunnels = MutableStateFlow<List<SavedTunnel>>(emptyList())
    val tunnels: StateFlow<List<SavedTunnel>> = _tunnels.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    init {
        _tunnels.value = readAll()
        _selectedId.value = prefs.getString(KEY_SELECTED, null)
            ?: _tunnels.value.firstOrNull()?.id
    }

    val selected: SavedTunnel? get() = find(_selectedId.value)

    fun find(id: String?): SavedTunnel? = _tunnels.value.firstOrNull { it.id == id }

    fun select(id: String?) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    /**
     * Validates and stores [confText]. Pass [id] to overwrite an existing tunnel.
     * @throws ConfigException with a message meant for the user.
     */
    fun save(name: String, confText: String, id: String? = null): SavedTunnel {
        if (XrayConfig.looksLikeXray(confText)) return saveXray(name, confText, id)
        val parsed = AwgConfig.parse(confText)
        val cleanName = name.trim().ifBlank { defaultName(parsed) }
        val normalized = parsed.toConfText()
        // Re-loading a subscription must not pile up copies of the same server.
        if (id == null) {
            _tunnels.value.firstOrNull { it.confText == normalized }?.let {
                select(it.id)
                return it
            }
        }
        val existing = find(id)
        val tunnel = SavedTunnel(
            id = id ?: ("t_" + System.currentTimeMillis() + "_" + (0..999).random()),
            name = cleanName,
            confText = normalized,
            kind = parsed.kind,
            endpoint = parsed.primaryEndpoint.orEmpty(),
            isFullTunnel = parsed.isFullTunnel,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = existing?.lastUsedAt ?: 0L
        )
        val next = _tunnels.value.filterNot { it.id == tunnel.id } + tunnel
        writeAll(next.sortedByDescending { it.lastUsedAt })
        if (_selectedId.value == null || id == null) select(tunnel.id)
        return tunnel
    }

    private fun saveXray(name: String, confText: String, id: String?): SavedTunnel {
        val parsed = XrayConfig.parse(confText)
        val normalized = confText.trim()
        if (id == null) {
            _tunnels.value.firstOrNull { it.confText == normalized }?.let {
                select(it.id)
                return it
            }
        }
        val existing = find(id)
        val tunnel = SavedTunnel(
            id = id ?: ("t_" + System.currentTimeMillis() + "_" + (0..999).random()),
            name = name.trim().ifBlank { parsed.name }.take(48),
            confText = normalized,
            kind = TunnelKind.XRAY,
            endpoint = parsed.endpoint,
            isFullTunnel = true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = existing?.lastUsedAt ?: 0L
        )
        val next = _tunnels.value.filterNot { it.id == tunnel.id } + tunnel
        writeAll(next.sortedByDescending { it.lastUsedAt })
        if (_selectedId.value == null || id == null) select(tunnel.id)
        return tunnel
    }

    /** Imports a file or clipboard blob that may hold several configs; returns what was saved. */
    fun importMany(text: String, nameHint: String = ""): List<SavedTunnel> {
        val chunks = splitConfigs(text)
        if (chunks.isEmpty()) throw ConfigException("No WireGuard [Interface] section or Xray link found")
        val saved = mutableListOf<SavedTunnel>()
        val errors = mutableListOf<String>()
        chunks.forEachIndexed { i, chunk ->
            try {
                val label = if (chunks.size == 1) nameHint
                else (nameHint.ifBlank { "Imported" } + " " + (i + 1))
                saved += save(label, chunk)
            } catch (e: ConfigException) {
                errors += "#" + (i + 1) + ": " + e.message
            }
        }
        if (saved.isEmpty()) throw ConfigException(errors.joinToString("; "))
        return saved
    }

    fun delete(id: String) {
        writeAll(_tunnels.value.filterNot { it.id == id })
        if (_selectedId.value == id) select(_tunnels.value.firstOrNull()?.id)
    }

    fun markUsed(id: String) {
        if (find(id) == null) return
        writeAll(_tunnels.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it })
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        writeAll(_tunnels.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    private fun defaultName(c: AwgConfig): String {
        val host = c.primaryEndpoint?.let { AwgConfig.splitHostPort(it).first } ?: return c.kind.label
        return c.kind.label + " " + host
    }

    /** Splits a paste into WireGuard blocks (on [Interface]), share-link lines, or one Xray JSON. */
    private fun splitConfigs(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return if (XrayConfig.looksLikeXray(trimmed)) listOf(trimmed) else emptyList()
        val isLink = { line: String -> XrayConfig.looksLikeXray(line.trim()) }
        val marker = Regex("(?im)^\\s*\\[Interface\\]\\s*$")
        val starts = marker.findAll(text).map { it.range.first }.toList()
        val blocks = starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: text.length
            // A WireGuard block ends where a following share link starts.
            text.substring(start, end).lines().takeWhile { !isLink(it) }.joinToString("\n").trim()
        }.filter { it.isNotBlank() }
        return blocks + trimmed.lines().map { it.trim() }.filter(isLink)
    }

    private fun readAll(): List<SavedTunnel> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedTunnel(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    confText = o.optString("conf"),
                    kind = TunnelKind.from(o.optString("kind")),
                    endpoint = o.optString("endpoint"),
                    isFullTunnel = o.optBoolean("full", true),
                    createdAt = o.optLong("createdAt"),
                    lastUsedAt = o.optLong("lastUsedAt")
                )
            }.filter { it.id.isNotBlank() && it.confText.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(list: List<SavedTunnel>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("conf", t.confText)
                put("kind", t.kind.name)
                put("endpoint", t.endpoint)
                put("full", t.isFullTunnel)
                put("createdAt", t.createdAt)
                put("lastUsedAt", t.lastUsedAt)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        _tunnels.value = list
    }

    companion object {
        private const val KEY_LIST = "tunnels"
        private const val KEY_SELECTED = "selected"

        /** Starting points shown in the editor. Keys are placeholders the user must replace. */
        fun template(kind: TunnelKind): String = when (kind) {
            // Nothing sensible to pre-fill: an Xray link is only ever pasted whole.
            TunnelKind.XRAY -> ""

            TunnelKind.WARP -> """
                [Interface]
                PrivateKey = PASTE_YOUR_WARP_PRIVATE_KEY
                Address = 172.16.0.2/32
                DNS = 1.1.1.1
                MTU = 1280

                [Peer]
                PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = engage.cloudflareclient.com:2408
                PersistentKeepalive = 25
            """.trimIndent()

            TunnelKind.AMNEZIA -> """
                [Interface]
                PrivateKey = PASTE_YOUR_PRIVATE_KEY
                Address = 10.8.1.2/32
                DNS = 1.1.1.1
                MTU = 1280
                Jc = 4
                Jmin = 40
                Jmax = 70
                S1 = 50
                S2 = 100
                H1 = 1234567
                H2 = 2345678
                H3 = 3456789
                H4 = 4567890

                [Peer]
                PublicKey = PASTE_SERVER_PUBLIC_KEY
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = server.example.com:51820
                PersistentKeepalive = 25
            """.trimIndent()

            TunnelKind.WIREGUARD -> """
                [Interface]
                PrivateKey = PASTE_YOUR_PRIVATE_KEY
                Address = 10.7.0.2/32
                DNS = 1.1.1.1
                MTU = 1280

                [Peer]
                PublicKey = PASTE_SERVER_PUBLIC_KEY
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = server.example.com:51820
                PersistentKeepalive = 25
            """.trimIndent()
        }
    }
}
