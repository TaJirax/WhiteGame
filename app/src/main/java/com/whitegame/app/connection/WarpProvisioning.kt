package com.whitegame.app.connection

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Consumer WARP registration. Only the public key leaves the device. No fabricated credentials. */
object WarpProvisioning {
    const val REGISTRATION_URL = "https://api.cloudflareclient.com/v0a5641/reg"

    data class Profile(val confText: String, val endpoints: List<String>)

    fun request(publicKey: String, now: Date = Date()): JSONObject {
        Keys.toHex(publicKey)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        return JSONObject().put("key", publicKey).put("key_type", "curve25519")
            .put("tunnel_type", "wireguard").put("tos", timestamp).put("locale", "en_US")
            .put("model", "WhiteGame").put("os_version", "16.0.0")
            .put("fcm_token", "").put("install_id", "").put("serial_number", "")
    }

    /** Call only after the user accepts Cloudflare's terms in the setup panel. */
    fun register(privateKey: String): Profile = register(privateKey) {
        URL(REGISTRATION_URL).openConnection() as HttpURLConnection
    }

    internal fun register(privateKey: String, connect: () -> HttpURLConnection): Profile {
        val body = request(Keys.publicKeyOf(privateKey)).toString().toByteArray(Charsets.UTF_8)
        val connection = connect()
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "1.1.1.1/6.38.9-5641 (Android 16.0.0)")
            connection.setRequestProperty("CF-Client-Version", "a-6.38.9-5641")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw ConfigException(
                when (status) {
                    429 -> "WARP registration rate limited. Try again later."
                    403 -> "Cloudflare refused registration. Import a provider config or try another network."
                    else -> "WARP registration failed (HTTP $status). Try another network or import a config."
                }
            )
            val text = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(262145)
                var used = 0
                while (used < chars.size) {
                    val count = reader.read(chars, used, chars.size - used)
                    if (count < 0) break
                    used += count
                }
                if (used == chars.size) throw ConfigException("WARP response is too large")
                String(chars, 0, used)
            }
            fromResponse(privateKey, JSONObject(text))
        } finally { connection.disconnect() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fromResponse(privateKey: String, response: JSONObject): Profile {
        try {
            Keys.toHex(privateKey)
            response.optString("key").takeIf { it.isNotBlank() }?.let {
                if (it != Keys.publicKeyOf(privateKey)) throw ConfigException("WARP returned a different device key")
            }
            val config = response.getJSONObject("config")
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val local = listOf("v4" to "/32", "v6" to "/128").mapNotNull { (key, suffix) ->
                addresses.optString(key).trim().takeIf { it.isNotEmpty() }?.let {
                    if ('/' in it) it else it + suffix
                }
            }
            if (local.isEmpty()) throw ConfigException("WARP returned no client address")
            val clientId = Base64.decode(config.getString("client_id"))
            if (clientId.size != 3) throw ConfigException("WARP returned an invalid client ID")
            val peers = config.getJSONArray("peers")
            if (peers.length() == 0) throw ConfigException("WARP returned no peers")
            val peer = peers.getJSONObject(0)
            val endpoint = peer.getJSONObject("endpoint")
            val ports = endpoint.optJSONArray("ports")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }.onEach {
                    if (it !in 1..65535) throw ConfigException("WARP returned an invalid port")
                }
            }.orEmpty().ifEmpty { listOf(2408) }
            val candidates = buildList {
                endpoint.optString("host").takeIf { it.isNotBlank() }?.let { host ->
                    add(asEndpoint(host, ports.first(), preservePort = true))
                }
                listOf("v4", "v6").forEach { family ->
                    endpoint.optString(family).takeIf { it.isNotBlank() }?.let { host ->
                        ports.forEach { add(asEndpoint(host, it)) }
                    }
                }
            }.distinct()
            if (candidates.isEmpty()) throw ConfigException("WARP returned no endpoint")
            val text = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                appendLine("Address = ${local.joinToString(", ")}")
                appendLine("DNS = 1.1.1.1, 1.0.0.1")
                appendLine("MTU = 1280")
                appendLine("[Peer]")
                appendLine("PublicKey = ${peer.getString("public_key")}")
                appendLine("Endpoint = ${candidates.first()}")
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                appendLine("PersistentKeepalive = 25")
                appendLine("Reserved = ${clientId.joinToString(", ") { (it.toInt() and 255).toString() }}")
            }
            return Profile(AwgConfig.parse(text).toConfText(), candidates)
        } catch (e: ConfigException) { throw e }
        catch (_: Exception) { throw ConfigException("Could not read WARP registration. Import a config or try again later.") }
    }

    private fun asEndpoint(host: String, port: Int, preservePort: Boolean = false): String {
        // API revisions return either a host:port or a bare v4/v6 address.
        if (host.startsWith("[") || host.count { it == ':' } == 1) {
            runCatching { AwgConfig.splitHostPort(host) }.getOrNull()?.let { (name, originalPort) ->
                return (if (':' in name) "[$name]" else name) + ":${if (preservePort) originalPort else port}"
            }
        }
        return (if (':' in host) "[${host.removeSurrounding("[", "]")}]" else host) + ":$port"
    }
}
