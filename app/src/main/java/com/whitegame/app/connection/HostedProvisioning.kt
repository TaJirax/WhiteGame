package com.whitegame.app.connection

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.Inet6Address
import java.net.InetAddress

/** Enrolls a phone's public key on the user's own WireGuard host. */
object HostedProvisioning {
    data class Profile(val confText: String, val endpoint: String)

    fun normalizeServer(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) {
            throw ConfigException("Enter the server's HTTPS URL")
        }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0)
            throw ConfigException("Use an HTTPS server URL without credentials, query parameters or a fragment")
        if (uri.rawPath.orEmpty().split('/').any { it == "." || it == ".." } || uri.rawPath.orEmpty().contains('%'))
            throw ConfigException("Use the server's plain HTTPS base URL")
        val host = uri.host.lowercase()
        val authority = host + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://" + authority + uri.rawPath.orEmpty().trimEnd('/')
    }

    fun register(server: String, token: String, privateKey: String): Profile {
        val base = normalizeServer(server)
        return register(token, privateKey) { URL("$base/v1/peers").openConnection() as HttpURLConnection }
    }

    internal fun register(token: String, privateKey: String, connect: () -> HttpURLConnection): Profile {
        val bearer = token.trim()
        if (bearer.isEmpty() || bearer.length > 4096 || bearer.any { it.code !in 33..126 })
            throw ConfigException("Enter the server's enrollment token")
        val publicKey = Keys.publicKeyOf(privateKey)
        val bytes = JSONObject().put("public_key", publicKey).put("name", "WhiteGame")
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connect()
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.outputStream.use { it.write(bytes) }
            when (val code = connection.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw ConfigException("Server refused enrollment. Check the enrollment token with your administrator.")
                409 -> throw ConfigException("Server address pool or peer state conflicts. Ask the administrator to check the server.")
                429 -> throw ConfigException("Enrollment rate limited. Try again later.")
                503, 507 -> throw ConfigException("Server cannot provision a peer now. Check its WireGuard interface and address pool.")
                in 300..399 -> throw ConfigException("Server redirected enrollment. Enter its final HTTPS base URL.")
                else -> throw ConfigException("Server enrollment failed (HTTP $code)")
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val data = CharArray(32769)
                var used = 0
                while (used < data.size) {
                    val count = reader.read(data, used, data.size - used)
                    if (count < 0) break
                    used += count
                }
                if (used == data.size) throw ConfigException("Server enrollment response is too large")
                String(data, 0, used)
            }
            fromResponse(privateKey, JSONObject(response))
        } catch (e: ConfigException) { throw e }
        catch (_: javax.net.ssl.SSLException) { throw ConfigException("Could not verify the server's HTTPS certificate") }
        catch (_: Exception) { throw ConfigException("Could not complete enrollment. Check the server URL and connection, then retry with the same device key.") }
        finally { connection.disconnect() }
    }

    fun fromResponse(privateKey: String, response: JSONObject): Profile {
        try {
            if (response.getString("public_key") != Keys.publicKeyOf(privateKey))
                throw ConfigException("Server returned a different device public key")
            fun values(key: String, required: Boolean = true): List<String> {
                val array = response.optJSONArray(key)
                if (array == null && required) throw ConfigException("Server response is missing $key")
                val items = array?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()
                if ((required && items.isEmpty()) || items.any { it.isBlank() || it.any { c -> c.isWhitespace() || c in "#;=,[]" } })
                    throw ConfigException("Server returned invalid $key")
                return items
            }
            val addresses = values("addresses")
            val dns = values("dns", required = false)
            fun validIp(value: String): Boolean = if (':' in value) {
                value.matches(Regex("[0-9a-fA-F:.]+")) && runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
            } else value.split('.').let { parts ->
                parts.size == 4 && parts.all { part -> part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 }
            }
            fun validCidr(value: String): Boolean {
                if (value.count { it == '/' } != 1) return false
                val ip = value.substringBefore('/')
                val prefix = value.substringAfter('/').toIntOrNull() ?: return false
                return validIp(ip) && prefix in 0..(if (':' in ip) 128 else 32)
            }
            if (addresses.any { !validCidr(it) }) throw ConfigException("Server returned invalid addresses")
            if (dns.any { !validIp(it) })
                throw ConfigException("Server DNS must contain IP addresses")
            val routes = values("allowed_ips")
            if (routes.any { !validCidr(it) }) throw ConfigException("Server returned invalid allowed_ips")
            val endpoint = response.getString("endpoint")
            val endpointUri = URI("udp://$endpoint")
            if (endpointUri.host.isNullOrBlank() || endpointUri.port !in 1..65535 || endpointUri.rawUserInfo != null ||
                !endpointUri.rawPath.isNullOrEmpty() || endpointUri.rawQuery != null || endpointUri.rawFragment != null)
                throw ConfigException("Server returned an invalid endpoint")
            val serverKey = response.getString("server_public_key")
            Keys.toHex(serverKey)
            fun integer(key: String): Int {
                val value = response.get(key) as? Number ?: throw ConfigException("Server returned invalid $key")
                val result = value.toInt()
                if (value.toDouble() != result.toDouble()) throw ConfigException("Server returned invalid $key")
                return result
            }
            val mtu = integer("mtu")
            val keepalive = integer("persistent_keepalive")
            if (keepalive !in 0..65535) throw ConfigException("Server returned invalid persistent_keepalive")
            val config = AwgConfig.parse(buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                appendLine("Address = ${addresses.joinToString(", ")}")
                if (dns.isNotEmpty()) appendLine("DNS = ${dns.joinToString(", ")}")
                appendLine("MTU = $mtu")
                appendLine("[Peer]")
                appendLine("PublicKey = $serverKey")
                appendLine("Endpoint = $endpoint")
                appendLine("AllowedIPs = ${routes.joinToString(", ")}")
                appendLine("PersistentKeepalive = $keepalive")
            })
            return Profile(config.toConfText(), endpoint)
        } catch (e: ConfigException) { throw e }
        catch (_: Exception) { throw ConfigException("Server returned an invalid enrollment response") }
    }
}
