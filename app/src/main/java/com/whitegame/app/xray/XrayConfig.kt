package com.whitegame.app.xray

import com.whitegame.app.connection.ConfigException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Turns share links (vless / vmess / trojan / ss / hysteria2) or an Xray JSON config into the
 * JSON the bundled Xray core runs.
 *
 * Pure Kotlin on purpose: this runs in the main process, while the Go core itself only ever
 * loads in the :xray process (see [XrayVpnService]).
 */
object XrayConfig {

    /** One proxy, ready to be dropped into any of the configs built below. */
    data class Parsed(
        val protocol: String,
        val name: String,
        val host: String,
        val port: Int,
        val network: String,
        val security: String,
        val outbound: JSONObject
    ) {
        val endpoint: String get() = (if (host.contains(':')) "[$host]" else host) + ":" + port
    }

    const val DELAY_URL = "https://www.gstatic.com/generate_204"

    private val SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://")

    /** Private and loopback ranges never go through the proxy. CIDRs, so no geoip.dat needed. */
    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128"
    )

    fun looksLikeXray(text: String): Boolean {
        val t = text.trim()
        if (SCHEMES.any { t.startsWith(it, ignoreCase = true) }) return true
        return (t.startsWith("{") && t.contains("\"outbounds\"")) ||
            (t.startsWith("{") && t.contains("\"protocol\"") && !t.contains("[Interface]"))
    }

    /** @throws ConfigException with a message meant for the user. */
    fun parse(text: String): Parsed {
        val t = text.trim()
        val lower = t.lowercase()
        return try {
            when {
                lower.startsWith("vless://") -> vless(t)
                lower.startsWith("vmess://") -> vmess(t)
                lower.startsWith("trojan://") -> trojan(t)
                lower.startsWith("ss://") -> shadowsocks(t)
                lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> hysteria2(t)
                t.startsWith("{") -> fromJson(t)
                else -> throw ConfigException("Not an Xray link or config")
            }
        } catch (e: ConfigException) {
            throw e
        } catch (e: Exception) {
            throw ConfigException("Could not read this link: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------------ configs

    /** Outbound only — what a one-shot delay test needs. */
    fun delayConfig(p: Parsed): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "none"))
        .put("outbounds", JSONArray().put(p.outbound.withTag("proxy")))
        .toString()

    /** Full-device tunnel: Xray's own TUN inbound reads the VpnService descriptor. */
    fun tunConfig(p: Parsed, dns: List<String>, mtu: Int = 1500): String {
        val sniff = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            // Keep the destination IP: games talk to raw IPs and must reach that exact server.
            .put("routeOnly", true)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put("policy", JSONObject()
                .put("levels", JSONObject().put("8", JSONObject()
                    .put("handshake", 4).put("connIdle", 300)
                    .put("uplinkOnly", 1).put("downlinkOnly", 1)))
                .put("system", JSONObject()
                    .put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "tun").put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", mtu).put("userLevel", 8))
                .put("sniffing", sniff)))
            .put("outbounds", JSONArray()
                .put(p.outbound.withTag("proxy"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("dns", JSONObject().put("servers", JSONArray(dns)))
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray(PRIVATE_CIDRS))
                    .put("outboundTag", "direct"))))
            .toString()
    }

    /**
     * One local SOCKS5 port per proxy, all in one core, so live comparisons can open real
     * connections through every config at once without starting a core per sample.
     */
    fun socksConfig(proxies: List<Pair<Int, Parsed>>): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        proxies.forEachIndexed { i, (port, p) ->
            inbounds.put(JSONObject()
                .put("tag", "in$i").put("listen", "127.0.0.1").put("port", port)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            outbounds.put(p.outbound.withTag("proxy$i"))
            rules.put(JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("in$i"))
                .put("outboundTag", "proxy$i"))
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
            .toString()
    }

    private fun JSONObject.withTag(tag: String): JSONObject = JSONObject(toString()).put("tag", tag)

    // ------------------------------------------------------------------ links

    private class Link(val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    /** scheme://user@host:port?query#name, tolerant of the unescaped characters links carry. */
    private fun link(raw: String, defaultPort: Int): Link {
        var body = raw.substringAfter("://")
        val name = decode(body.substringAfter('#', ""))
        body = body.substringBefore('#')
        val query = body.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
        val authority = body.substringBefore('?').trimEnd('/')
        val user = decode(authority.substringBeforeLast('@', ""))
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']') to
                (hostPort.substringAfter("]:", "").toIntOrNull() ?: defaultPort)
        } else if (hostPort.count { it == ':' } == 1) {
            hostPort.substringBefore(':') to (hostPort.substringAfter(':').toIntOrNull() ?: -1)
        } else {
            hostPort to defaultPort
        }
        if (host.isBlank()) throw ConfigException("The link has no server address")
        if (port !in 1..65535) throw ConfigException("The link has no valid port")
        return Link(user, host, port, query, name)
    }

    // '+' is literal in share links (base64, paths); URLDecoder would turn it into a space.
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun vless(raw: String): Parsed {
        val l = link(raw, 443)
        if (l.user.isBlank()) throw ConfigException("VLESS link is missing its UUID")
        val settings = server(l.host, l.port)
            .put("id", l.user)
            .put("encryption", l.query["encryption"].orEmpty().ifBlank { "none" })
        l.query["flow"]?.takeIf { it.isNotBlank() }?.let { settings.put("flow", it) }
        return build("vless", l.name.ifBlank { "VLESS " + l.host }, l.host, l.port, settings, l.query, "none")
    }

    private fun trojan(raw: String): Parsed {
        val l = link(raw, 443)
        if (l.user.isBlank()) throw ConfigException("Trojan link is missing its password")
        val settings = server(l.host, l.port).put("password", l.user)
        l.query["flow"]?.takeIf { it.isNotBlank() }?.let { settings.put("flow", it) }
        return build("trojan", l.name.ifBlank { "Trojan " + l.host }, l.host, l.port, settings, l.query, "tls")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(s: String): String {
        val clean = s.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        return String(Base64.decode(clean.padEnd(clean.length + (4 - clean.length % 4) % 4, '=')))
    }

    private fun vmess(raw: String): Parsed {
        val body = raw.substringAfter("://")
        // Some clients export vmess in the vless-style URI form instead of base64 JSON.
        if (body.contains('@') && body.contains('?')) {
            val l = link(raw, 443)
            val settings = server(l.host, l.port).put("id", l.user).put("security", "auto")
            return build("vmess", l.name.ifBlank { "VMess " + l.host }, l.host, l.port, settings, l.query, "none")
        }
        val j = JSONObject(b64(body.substringBefore('#')))
        val host = j.optString("add")
        val port = j.optString("port").trim().toIntOrNull() ?: j.optInt("port", -1)
        val id = j.optString("id")
        if (host.isBlank() || port !in 1..65535 || id.isBlank())
            throw ConfigException("VMess link is missing its address, port or id")
        val net = j.optString("net").ifBlank { "tcp" }
        val q = mutableMapOf(
            "type" to net,
            "headerType" to j.optString("type"),
            "host" to j.optString("host"),
            "path" to j.optString("path"),
            "security" to j.optString("tls"),
            "sni" to j.optString("sni"),
            "fp" to j.optString("fp"),
            "alpn" to j.optString("alpn"),
            "allowInsecure" to j.optString("insecure", j.optString("allowInsecure"))
        )
        if (net == "grpc") {
            q["serviceName"] = j.optString("path")
            q["authority"] = j.optString("host")
            q["mode"] = j.optString("type")
        }
        if (net == "kcp") q["seed"] = j.optString("path")
        val settings = server(host, port).put("id", id).put("security", j.optString("scy").ifBlank { "auto" })
        return build("vmess", j.optString("ps").ifBlank { "VMess $host" }, host, port, settings, q, "none")
    }

    private fun shadowsocks(raw: String): Parsed {
        val body = raw.substringAfter("://")
        val name = decode(body.substringAfter('#', ""))
        val main = body.substringBefore('#')
        val (method, password, hostPortQuery) = if (main.substringBefore('?').contains('@')) {
            // SIP002: userinfo is base64(method:password) or percent-encoded method:password.
            val userinfo = main.substringBeforeLast('@')
            val plain = decode(userinfo).let { if (it.contains(':')) it else b64(it) }
            Triple(plain.substringBefore(':'), plain.substringAfter(':'), main.substringAfterLast('@'))
        } else {
            // Legacy: the whole method:password@host:port is base64.
            val plain = b64(main.substringBefore('?'))
            val creds = plain.substringBeforeLast('@')
            Triple(creds.substringBefore(':'), creds.substringAfter(':'), plain.substringAfterLast('@'))
        }
        if (method.isBlank() || password.isBlank()) throw ConfigException("Shadowsocks link is missing method or password")
        val l = link("ss://x@" + hostPortQuery, 8388)
        val q = mutableMapOf<String, String>()
        // v2ray-plugin (what BlueKnight panels emit) is websocket, optionally over TLS.
        val plugin = l.query["plugin"].orEmpty()
        if (plugin.startsWith("v2ray-plugin") || plugin.startsWith("xray-plugin")) {
            val opts = plugin.split(';').drop(1)
            q["type"] = "ws"
            opts.forEach { o ->
                when {
                    o == "tls" -> q["security"] = "tls"
                    o.startsWith("host=") -> q["host"] = o.substringAfter('=')
                    o.startsWith("path=") -> q["path"] = o.substringAfter('=')
                }
            }
        } else if (plugin.contains("obfs=http")) {
            q["type"] = "tcp"
            q["headerType"] = "http"
            plugin.split(';').forEach { o ->
                if (o.startsWith("obfs-host=")) q["host"] = o.substringAfter('=')
            }
        }
        val settings = server(l.host, l.port).put("method", method.lowercase()).put("password", password)
        return build("shadowsocks", name.ifBlank { "Shadowsocks " + l.host }, l.host, l.port, settings, q, "none")
    }

    private fun hysteria2(raw: String): Parsed {
        val l = link(raw, 443)
        val settings = server(l.host, l.port).put("version", 2)
        val stream = JSONObject()
            .put("network", "hysteria")
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", l.user))
            .put("security", "tls")
            .put("tlsSettings", JSONObject()
                .put("serverName", l.query["sni"].orEmpty().ifBlank { l.host })
                .put("alpn", JSONArray().put("h3"))
                .put("allowInsecure", insecure(l.query)))
        l.query["obfs-password"]?.takeIf { it.isNotBlank() }?.let { pw ->
            stream.put("finalmask", JSONObject().put("udp", JSONArray().put(JSONObject()
                .put("type", "salamander")
                .put("settings", JSONObject().put("password", pw)))))
        }
        val outbound = JSONObject().put("protocol", "hysteria").put("settings", settings).put("streamSettings", stream)
        return Parsed("hysteria2", l.name.ifBlank { "Hysteria2 " + l.host }, l.host, l.port, "hysteria", "tls", outbound)
    }

    private fun server(host: String, port: Int) = JSONObject().put("address", host).put("port", port).put("level", 8)

    private fun insecure(q: Map<String, String>) =
        listOf("insecure", "allowInsecure", "allow_insecure").any { q[it] == "1" || q[it] == "true" }

    /** Transport + TLS/REALITY from the query map shared by vless, vmess, trojan and ss. */
    private fun build(
        protocol: String, name: String, host: String, port: Int,
        settings: JSONObject, q: Map<String, String>, defaultSecurity: String
    ): Parsed {
        val network = q["type"].orEmpty().ifBlank { "tcp" }.let { if (it == "raw") "tcp" else it }
        val stream = JSONObject().put("network", network)
        val hostHeader = q["host"].orEmpty()
        val path = q["path"].orEmpty().ifBlank { "/" }
        when (network) {
            "tcp" -> {
                val header = JSONObject().put("type", "none")
                if (q["headerType"] == "http") {
                    header.put("type", "http").put("request", JSONObject()
                        .put("version", "1.1").put("method", "GET")
                        .put("path", JSONArray(path.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                        .put("headers", JSONObject().apply {
                            if (hostHeader.isNotBlank()) put("Host", JSONArray(hostHeader.split(',').map { it.trim() }))
                        }))
                }
                stream.put("tcpSettings", JSONObject().put("header", header))
            }
            "ws" -> stream.put("wsSettings", JSONObject().put("path", path).put("host", hostHeader))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", path).put("host", hostHeader))
            "xhttp", "splithttp" -> {
                stream.put("network", "xhttp")
                val x = JSONObject().put("path", path).put("host", hostHeader)
                q["mode"]?.takeIf { it.isNotBlank() }?.let { x.put("mode", it) }
                q["extra"]?.takeIf { it.isNotBlank() }?.let { runCatching { x.put("extra", JSONObject(it)) } }
                stream.put("xhttpSettings", x)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", q["serviceName"].orEmpty())
                .put("authority", q["authority"].orEmpty())
                .put("multiMode", q["mode"] == "multi"))
            "h2", "http" -> {
                stream.put("network", "h2")
                stream.put("httpSettings", JSONObject()
                    .put("path", path)
                    .put("host", JSONArray(hostHeader.split(',').map { it.trim() }.filter { it.isNotEmpty() })))
            }
            "kcp" -> stream.put("kcpSettings", JSONObject())
            else -> throw ConfigException("Transport \"$network\" is not supported")
        }

        val security = q["security"].orEmpty().ifBlank { defaultSecurity }.lowercase()
        if (security == "tls" || security == "reality") {
            val sni = q["sni"].orEmpty()
                .ifBlank { hostHeader.substringBefore(',').trim() }
                .ifBlank { if (host.any { it.isLetter() } && !host.contains(':')) host else "" }
            val tls = JSONObject()
            if (sni.isNotBlank()) tls.put("serverName", sni)
            q["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
            q["alpn"]?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray(it.split(',').map { a -> a.trim() }.filter { a -> a.isNotEmpty() }))
            }
            if (security == "tls") {
                tls.put("allowInsecure", insecure(q))
                stream.put("security", "tls").put("tlsSettings", tls)
            } else {
                val pbk = q["pbk"].orEmpty()
                if (pbk.isBlank()) throw ConfigException("REALITY link is missing its public key (pbk)")
                tls.put("publicKey", pbk)
                if (!tls.has("fingerprint")) tls.put("fingerprint", "chrome")
                q["sid"]?.let { tls.put("shortId", it) }
                q["spx"]?.takeIf { it.isNotBlank() }?.let { tls.put("spiderX", it) }
                q["pqv"]?.takeIf { it.isNotBlank() }?.let { tls.put("mldsa65Verify", it) }
                stream.put("security", "reality").put("realitySettings", tls)
            }
        }
        val outbound = JSONObject()
            .put("protocol", protocol)
            .put("settings", settings)
            .put("streamSettings", stream)
        return Parsed(protocol, name, host, port, stream.getString("network"), if (stream.has("security")) security else "none", outbound)
    }

    /** A full Xray config (first proxy outbound wins) or a single outbound object. */
    private fun fromJson(text: String): Parsed {
        val root = JSONObject(text)
        val outbound = root.optJSONArray("outbounds")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("protocol") !in setOf("freedom", "blackhole", "dns", "loopback") }
        } ?: root.takeIf { it.has("protocol") }
            ?: throw ConfigException("No proxy outbound in this JSON")
        val protocol = outbound.optString("protocol")
        if (protocol.isBlank()) throw ConfigException("The outbound has no protocol")
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val first = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: settings
        val host = first.optString("address")
        val port = first.optInt("port", -1)
        if (host.isBlank() || port !in 1..65535) throw ConfigException("The outbound has no server address and port")
        val stream = outbound.optJSONObject("streamSettings")
        val clean = JSONObject(outbound.toString()).apply { remove("tag") }
        val name = root.optString("remarks").ifBlank { "$protocol $host" }
        return Parsed(
            protocol, name, host, port,
            stream?.optString("network").orEmpty().ifBlank { "tcp" },
            stream?.optString("security").orEmpty().ifBlank { "none" },
            clean
        )
    }
}
