package com.whitegame.app.connection

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Protocol a config speaks. AmneziaWG is WireGuard plus obfuscation fields; Xray runs its own core. */
enum class TunnelKind(val label: String) {
    WIREGUARD("WireGuard"),
    AMNEZIA("AmneziaWG"),
    WARP("WARP"),
    XRAY("Xray");

    companion object {
        fun from(name: String?): TunnelKind =
            values().firstOrNull { it.name.equals(name, true) } ?: WIREGUARD
    }
}

class ConfigException(message: String) : Exception(message)

/**
 * A parsed wg-quick / awg-quick config.
 *
 * Holds every field amneziawg-go accepts, so a config round-trips through the editor without
 * losing obfuscation settings. Vanilla WireGuard configs simply leave the Amnezia fields empty,
 * which makes the engine behave as plain WireGuard.
 */
data class AwgConfig(
    val iface: Iface,
    val peers: List<Peer>,
    /** Keys from older AmneziaWG builds (J1–J3, Itime) that this engine has no field for. */
    val ignoredKeys: List<String> = emptyList()
) {
    data class Iface(
        val privateKey: String,
        val addresses: List<String> = emptyList(),
        val dnsServers: List<String> = emptyList(),
        val dnsSearchDomains: List<String> = emptyList(),
        val mtu: Int? = null,
        val listenPort: Int? = null,
        val excludedApplications: List<String> = emptyList(),
        val includedApplications: List<String> = emptyList(),
        /** Amnezia obfuscation fields, keyed by lowercased config spelling. Empty = plain WireGuard. */
        val amnezia: Map<String, String> = emptyMap()
    )

    data class Peer(
        val publicKey: String,
        val presharedKey: String? = null,
        val endpoint: String? = null,
        val allowedIps: List<String> = emptyList(),
        val persistentKeepalive: String? = null
    )

    val kind: TunnelKind
        get() = when {
            iface.amnezia.isNotEmpty() -> TunnelKind.AMNEZIA
            peers.any { it.endpoint?.contains("cloudflareclient.com") == true } -> TunnelKind.WARP
            else -> TunnelKind.WIREGUARD
        }

    /** Derived so the UI can show it without asking the user to paste it. */
    val publicKey: String get() = Keys.publicKeyOf(iface.privateKey)

    /** Routes the tunnel claims, from every peer's AllowedIPs. */
    val allowedIps: List<String> get() = peers.flatMap { it.allowedIps }.distinct()

    val isFullTunnel: Boolean
        get() = allowedIps.any { it == "0.0.0.0/0" || it == "::/0" }

    val primaryEndpoint: String? get() = peers.firstOrNull { it.endpoint != null }?.endpoint

    /**
     * Serializes to the UAPI set payload the engine expects.
     *
     * [resolve] turns a peer hostname into an IP: the engine's UAPI parser only accepts IP
     * literals, so an unresolved hostname is rejected outright. Does blocking DNS by default,
     * so call this off the main thread.
     */
    fun toUapi(
        resolve: (String) -> InetAddress? = { runCatching { InetAddress.getByName(it) }.getOrNull() }
    ): String {
        val sb = StringBuilder()
        sb.append("private_key=").append(Keys.toHex(iface.privateKey)).append('\n')
        iface.listenPort?.let { sb.append("listen_port=").append(it).append('\n') }

        for ((key, raw) in iface.amnezia) {
            val uapiKey = AMNEZIA_UAPI[key.lowercase()] ?: continue
            // Hn = n is plain WireGuard already; nothing to tell the engine.
            if (isDefaultHeader(uapiKey, raw)) continue
            val value = when (uapiKey) {
                "header_protection_key" -> Keys.toHex(raw)
                "random_trailers", "disable_cookies" -> uapiBool(raw)
                else -> raw
            }
            sb.append(uapiKey).append('=').append(value).append('\n')
        }

        sb.append("replace_peers=true\n")
        for (p in peers) {
            // public_key must come first: it opens a new peer section.
            sb.append("public_key=").append(Keys.toHex(p.publicKey)).append('\n')
            sb.append("replace_allowed_ips=true\n")
            for (ip in p.allowedIps) sb.append("allowed_ip=").append(ip).append('\n')
            p.endpoint?.let { ep ->
                val (host, port) = splitHostPort(ep)
                val addr = resolve(host)
                    ?: throw ConfigException("Cannot resolve endpoint host " + host)
                val shown = if (addr is Inet6Address) "[" + addr.hostAddress + "]" else addr.hostAddress
                sb.append("endpoint=").append(shown).append(':').append(port).append('\n')
            }
            p.persistentKeepalive?.let {
                sb.append("persistent_keepalive_interval=").append(it).append('\n')
            }
            p.presharedKey?.let { sb.append("preshared_key=").append(Keys.toHex(it)).append('\n') }
        }
        return sb.toString()
    }

    /** Renders back to wg-quick text, so the editor shows a normalized version of what is stored. */
    fun toConfText(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = " + iface.privateKey)
        if (iface.addresses.isNotEmpty())
            appendLine("Address = " + iface.addresses.joinToString(", "))
        val allDns = iface.dnsServers + iface.dnsSearchDomains
        if (allDns.isNotEmpty()) appendLine("DNS = " + allDns.joinToString(", "))
        iface.mtu?.let { appendLine("MTU = " + it) }
        iface.listenPort?.let { appendLine("ListenPort = " + it) }
        if (iface.excludedApplications.isNotEmpty())
            appendLine("ExcludedApplications = " + iface.excludedApplications.joinToString(", "))
        if (iface.includedApplications.isNotEmpty())
            appendLine("IncludedApplications = " + iface.includedApplications.joinToString(", "))
        for (key in AMNEZIA_ORDER) {
            iface.amnezia[key.lowercase()]?.let { appendLine(key + " = " + it) }
        }
        for (p in peers) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + p.publicKey)
            p.presharedKey?.let { appendLine("PresharedKey = " + it) }
            if (p.allowedIps.isNotEmpty())
                appendLine("AllowedIPs = " + p.allowedIps.joinToString(", "))
            p.endpoint?.let { appendLine("Endpoint = " + it) }
            p.persistentKeepalive?.let { appendLine("PersistentKeepalive = " + it) }
        }
    }.trim()

    companion object {
        /** Config-file spelling -> UAPI key, covering every field amneziawg-go accepts. */
        private val AMNEZIA_UAPI = mapOf(
            "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
            "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
            "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
            "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
            "headerprotectionkey" to "header_protection_key",
            "contentpaddingaddition" to "content_padding_addition",
            "rekeyaftertime" to "rekey_after_time",
            "rekeytimeout" to "rekey_timeout",
            "rejectaftertime" to "reject_after_time",
            "keepalivetimeout" to "keepalive_timeout",
            "maxhandshakeattempts" to "max_handshake_attempts",
            "randomtrailers" to "random_trailers",
            "disablecookies" to "disable_cookies"
        )

        /** Canonical display order / spelling when writing a config back out. */
        val AMNEZIA_ORDER = listOf(
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4",
            "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition",
            "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
            "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies"
        )

        /** The fields a standard AmneziaWG profile sets; shown as the quick-edit form. */
        val AMNEZIA_CORE = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4")

        /** True for H1 = 1 … H4 = 4: the header is WireGuard's own message type. */
        private fun isDefaultHeader(key: String, value: String): Boolean =
            key.length == 2 && key[0] == 'h' && value.trim() == key.substring(1)

        /** AmneziaWG 1.5 keys. The bundled engine dropped them; saying so beats losing them silently. */
        private val LEGACY_KEYS = setOf("j1", "j2", "j3", "itime")

        fun isAmneziaKey(key: String): Boolean = AMNEZIA_UAPI.containsKey(key.lowercase())

        /** amneziawg-go parses these with strconv.ParseBool, which rejects on/off. */
        private fun uapiBool(v: String): String = when (v.trim().lowercase()) {
            "on", "1", "true", "t", "yes" -> "1"
            else -> "0"
        }

        fun splitHostPort(endpoint: String): Pair<String, Int> {
            val e = endpoint.trim()
            if (e.startsWith("[")) { // [v6]:port
                val host = e.substringAfter('[').substringBefore(']')
                val port = e.substringAfterLast("]:", "").toIntOrNull()
                    ?: throw ConfigException("Endpoint " + e + " is missing a port")
                if (port !in 1..65535) throw ConfigException("Endpoint port " + port + " is out of range")
                return host to port
            }
            val host = e.substringBeforeLast(':', "")
            val port = e.substringAfterLast(':', "").toIntOrNull()
            if (host.isBlank() || port == null)
                throw ConfigException("Endpoint " + e + " must be host:port")
            if (port !in 1..65535) throw ConfigException("Endpoint port " + port + " is out of range")
            return host to port
        }

        /**
         * Parses wg-quick / awg-quick INI text. Accepts both WireGuard and AmneziaWG keys,
         * is case-insensitive on key names, and tolerates # and ; comments.
         */
        fun parse(text: String): AwgConfig {
            var section = ""
            var ifaceSeen = false
            var privateKey = ""
            val addresses = mutableListOf<String>()
            val dns = mutableListOf<String>()
            val searchDomains = mutableListOf<String>()
            var mtu: Int? = null
            var listenPort: Int? = null
            val excluded = mutableListOf<String>()
            val included = mutableListOf<String>()
            val amnezia = linkedMapOf<String, String>()
            val ignored = mutableListOf<String>()

            val peers = mutableListOf<Peer>()
            var pPublic: String? = null
            var pPreshared: String? = null
            var pEndpoint: String? = null
            var pKeepalive: String? = null
            val pAllowed = mutableListOf<String>()

            fun flushPeer() {
                val pk = pPublic ?: return
                peers.add(Peer(pk, pPreshared, pEndpoint, pAllowed.toList(), pKeepalive))
                pPublic = null; pPreshared = null; pEndpoint = null; pKeepalive = null
                pAllowed.clear()
            }

            for (rawLine in text.lines()) {
                val line = stripComment(rawLine).trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    val name = line.trim('[', ']').trim().lowercase()
                    if (name == "peer") flushPeer()
                    section = name
                    if (name == "interface") ifaceSeen = true
                    continue
                }
                val key = line.substringBefore('=', "").trim().lowercase()
                val value = line.substringAfter('=', "").trim()
                if (key.isEmpty() || value.isEmpty()) continue

                when (section) {
                    "interface" -> when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> addresses += splitList(value)
                        "dns" -> splitList(value).forEach {
                            if (isIpLiteral(it)) dns += it else searchDomains += it
                        }
                        "mtu" -> mtu = value.toIntOrNull()
                            ?: throw ConfigException("MTU " + value + " is not a number")
                        "listenport" -> listenPort = value.toIntOrNull()
                            ?: throw ConfigException("ListenPort " + value + " is not a number")
                        "excludedapplications" -> excluded += splitList(value)
                        "includedapplications" -> included += splitList(value)
                        else -> when {
                            // "100 - 200" is a range too; the engine only reads "100-200".
                            AMNEZIA_UAPI.containsKey(key) -> amnezia[key] =
                                if (value.matches(Regex("[0-9 \\t-]+"))) value.filterNot { it.isWhitespace() } else value
                            key in LEGACY_KEYS -> ignored += line.substringBefore('=').trim()
                            key.matches(Regex("[hsi][0-9]+")) -> throw ConfigException("Unsupported Amnezia field: " + key.uppercase())
                        }
                    }
                    "peer" -> when (key) {
                        "publickey" -> pPublic = value
                        "presharedkey" -> pPreshared = value
                        "endpoint" -> pEndpoint = value
                        "allowedips" -> pAllowed += splitList(value)
                        "persistentkeepalive" -> pKeepalive =
                            if (value.equals("off", true)) null
                            else value.also { unsignedRange(it, "PersistentKeepalive", 65535) }
                    }
                }
            }
            flushPeer()

            if (!ifaceSeen) throw ConfigException("No [Interface] section found")
            if (privateKey.isBlank()) throw ConfigException("[Interface] is missing PrivateKey")
            if (privateKey.startsWith("PASTE"))
                throw ConfigException("Replace the PrivateKey placeholder with the key from your provider")
            Keys.validate(privateKey, "PrivateKey")
            if (addresses.isEmpty()) throw ConfigException("[Interface] is missing Address")
            addresses.forEach { validateCidr(it, "Address") }
            if (peers.isEmpty()) throw ConfigException("No [Peer] section found")
            peers.forEachIndexed { i, p ->
                if (p.publicKey.startsWith("PASTE"))
                    throw ConfigException("Replace the PublicKey placeholder with your server's public key")
                Keys.validate(p.publicKey, "Peer " + (i + 1) + " PublicKey")
                p.presharedKey?.let { Keys.validate(it, "Peer " + (i + 1) + " PresharedKey") }
                p.endpoint?.let { splitHostPort(it) }
                p.allowedIps.forEach { validateCidr(it, "Peer " + (i + 1) + " AllowedIPs") }
            }
            mtu?.let {
                if (it !in 576..9000) throw ConfigException("MTU " + it + " is out of range (576-9000)")
            }
            validateAmnezia(amnezia)

            return AwgConfig(
                Iface(
                    privateKey, addresses, dns, searchDomains, mtu, listenPort,
                    excluded, included, amnezia
                ),
                peers,
                ignored
            )
        }

        /**
         * Catches the AmneziaWG mistakes the engine rejects late (or silently tolerates), so the
         * user sees them at save time instead of as a tunnel that never handshakes.
         */
        private fun validateAmnezia(a: Map<String, String>) {
            if (a.isEmpty()) return
            a["headerprotectionkey"]?.let { Keys.validate(it, "HeaderProtectionKey") }
            for (k in listOf("contentpaddingaddition", "rekeyaftertime", "rekeytimeout",
                "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts")) {
                a[k]?.let { unsignedRange(it, k, 65535) }
            }
            // Junk packet sizes are scalar values; headers and timings also accept ranges.
            fun num(k: String): Int? = a[k]?.substringBefore('-')?.trim()?.toIntOrNull()
            for (k in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")) {
                val v = a[k] ?: continue
                if (v.toIntOrNull() == null)
                    throw ConfigException(k.uppercase() + " " + v + " is not a number")
                unsignedRange(v, k.uppercase(), 65535)
            }
            val jmin = num("jmin")
            val jmax = num("jmax")
            if (jmin != null && jmax != null && jmin > jmax)
                throw ConfigException("Jmin (" + jmin + ") must not exceed Jmax (" + jmax + ")")
            val headers = listOf("h1", "h2", "h3", "h4").mapNotNull { k ->
                a[k]?.let { k to unsignedRange(it, k.uppercase()) }
            }
            // The engine's one rule for headers: no two may overlap. Small values are fine —
            // WARP-style configs use WireGuard's own types (H1=1 … H4=4) on purpose.
            headers.forEachIndexed { i, (_, range) ->
                if (headers.take(i).any { (_, other) -> range.first <= other.last && other.first <= range.last })
                    throw ConfigException("H1-H4 must all be different from each other (ranges must not overlap)")
            }
        }

        private fun unsignedRange(value: String, field: String, max: Long = 0xffffffffL): LongRange {
            val parts = value.split('-')
            val numbers = parts.map { it.trim().toLongOrNull() }
            if (parts.size !in 1..2 || numbers.any { it == null || it !in 0..max })
                throw ConfigException("$field must be a number or range between 0 and $max")
            val low = numbers.first()!!
            val high = numbers.last()!!
            if (low > high) throw ConfigException("$field range start must not exceed its end")
            return low..high
        }

        // Signature packet expressions may contain literal # and ; inside <...>.
        private fun stripComment(line: String): String {
            var inTag = false
            for ((index, char) in line.withIndex()) {
                when (char) {
                    '<' -> inTag = true
                    '>' -> inTag = false
                    '#', ';' -> if (!inTag) return line.substring(0, index)
                }
            }
            return line
        }

        private fun splitList(v: String): List<String> =
            v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun isIpLiteral(s: String): Boolean =
            (s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' }) || s.contains(':')

        private fun validateCidr(v: String, field: String) {
            val host = v.substringBefore('/')
            val maskText = v.substringAfter('/', "")
            if (host.isBlank()) throw ConfigException(field + " " + v + " is not an IP")
            val v6 = host.contains(':')
            if (!v6) {
                val parts = host.split('.')
                if (parts.size != 4 || parts.any { (it.toIntOrNull() ?: -1) !in 0..255 })
                    throw ConfigException(field + " " + v + " is not a valid IPv4 address")
            }
            if (maskText.isNotEmpty()) {
                val mask = maskText.toIntOrNull()
                    ?: throw ConfigException(field + " " + v + " has a non-numeric prefix length")
                val max = if (v6) 128 else 32
                if (mask !in 0..max)
                    throw ConfigException(field + " " + v + " prefix must be 0-" + max)
            }
        }
    }
}

/**
 * Curve25519 key helpers. WireGuard configs carry base64; the UAPI wants hex.
 *
 * Kotlin's own Base64: java.util.Base64 needs API 26 and android.util.Base64 is a stub in unit
 * tests, so this is the only one that both runs on older phones and stays testable off-device.
 */
@OptIn(ExperimentalEncodingApi::class)
object Keys {

    fun decode(base64: String): ByteArray {
        val cleaned = base64.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) throw ConfigException("key is empty")
        // Some providers ship keys without the trailing padding.
        val padded = cleaned.padEnd(cleaned.length + (4 - cleaned.length % 4) % 4, '=')
        val bytes = try {
            Base64.decode(padded)
        } catch (e: Exception) {
            throw ConfigException("key is not valid base64")
        }
        if (bytes.size != 32)
            throw ConfigException("key must decode to 32 bytes (got " + bytes.size + ")")
        return bytes
    }

    fun validate(base64: String, field: String) {
        try {
            decode(base64)
        } catch (e: ConfigException) {
            throw ConfigException(field + ": " + e.message)
        }
    }

    fun toHex(base64: String): String =
        decode(base64).joinToString("") { String.format("%02x", it) }

    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    fun publicKeyOf(privateKeyBase64: String): String =
        encode(Curve25519.scalarBaseMult(decode(privateKeyBase64)))

    fun generatePrivateKey(): String {
        val b = ByteArray(32)
        java.security.SecureRandom().nextBytes(b)
        b[0] = (b[0].toInt() and 248).toByte()
        b[31] = ((b[31].toInt() and 127) or 64).toByte()
        return encode(b)
    }
}
