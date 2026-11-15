package com.whitegame.app.connection

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class HostedProvisioningTest {
    private val privateKey = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="
    private val token = "client-enrollment-credential-1234567890"
    private fun fixture(): JSONObject {
        val relative = "server/tests/fixtures/enrollment.json"
        val file = listOf(File(relative), File("../$relative")).firstOrNull { it.exists() }
            ?: error("Shared server enrollment fixture is missing")
        return JSONObject(file.readText())
    }

    @Test fun serverUrlsRequireHttpsAndNormalizeIdentityForRetries() {
        assertEquals("https://provision.example.com", HostedProvisioning.normalizeServer(" HTTPS://PROVISION.EXAMPLE.COM:443/ "))
        assertEquals("https://server.example:8443/provision", HostedProvisioning.normalizeServer("https://server.example:8443/provision/"))
        assertEquals("https://[::1]:8443", HostedProvisioning.normalizeServer("https://[::1]:8443/"))
        listOf("http://server.example", "https://user:token@server.example", "https://server.example?q=token",
            "https://server.example#token", "https://server.example:0", "https://server.example/../other",
            "https://server.example/%2e%2e").forEach { assertTrue(it, runCatching { HostedProvisioning.normalizeServer(it) }.isFailure) }
    }

    @Test fun serverContractBuildsUsableConfigWithoutInventingPrivateCredentials() {
        val response = fixture()
        val profile = HostedProvisioning.fromResponse(privateKey, response)
        val config = AwgConfig.parse(profile.confText)
        assertEquals(response.getString("public_key"), config.publicKey)
        assertEquals(privateKey, config.iface.privateKey)
        assertEquals(listOf("10.8.0.2/32"), config.iface.addresses)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), config.iface.dnsServers)
        assertEquals(response.getString("server_public_key"), config.peers.single().publicKey)
        assertEquals("vpn.example.com:51820", profile.endpoint)
        assertTrue(config.isFullTunnel)
        assertEquals(TunnelKind.WIREGUARD, config.kind)
    }

    @Test fun splitTunnelAndIpv6EndpointArePreserved() {
        val profile = HostedProvisioning.fromResponse(privateKey, fixture()
            .put("endpoint", "[2001:db8::1]:51820").put("allowed_ips", JSONArray(listOf("192.168.20.0/24"))))
        val config = AwgConfig.parse(profile.confText)
        assertEquals("[2001:db8::1]:51820", config.primaryEndpoint)
        assertEquals(listOf("192.168.20.0/24"), config.allowedIps)
        assertFalse(config.isFullTunnel)
    }

    @Test fun malformedOrMismatchedSettingsCannotInjectConfigFields() {
        val invalid = listOf(
            fixture().put("public_key", fixture().getString("server_public_key")),
            fixture().put("server_public_key", "invalid"),
            fixture().put("endpoint", "vpn.example.com:51820\nPersistentKeepalive = 0"),
            fixture().put("endpoint", "2001:db8::1:51820"),
            fixture().put("endpoint", "vpn.example.com:51820/ignored"),
            fixture().put("dns", JSONArray(listOf("resolver.example"))),
            fixture().put("dns", JSONArray(listOf("999.1.1.1"))),
            fixture().put("addresses", JSONArray(listOf("10.8.0.2/32\nDNS=8.8.8.8"))),
            fixture().put("addresses", JSONArray(listOf("abcd:xyz::1/64"))),
            fixture().put("allowed_ips", JSONArray(listOf("0.0.0.0/99"))),
            fixture().put("mtu", 0),
            fixture().put("mtu", 1280.5),
            fixture().put("persistent_keepalive", "25"),
            fixture().put("persistent_keepalive", 65536),
            JSONObject()
        )
        invalid.forEach { assertTrue(runCatching { HostedProvisioning.fromResponse(privateKey, it) }.exceptionOrNull() is ConfigException) }
    }

    @Test fun enrollmentPostsPublicKeyWithBearerHeaderAndNoRedirects() {
        val connection = FakeConnection(201, fixture().toString())
        HostedProvisioning.register(token, privateKey) { connection }
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer $token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        val body = connection.sent.toString("UTF-8")
        assertEquals(Keys.publicKeyOf(privateKey), JSONObject(body).getString("public_key"))
        assertEquals(setOf("public_key", "name"), JSONObject(body).keys().asSequence().toSet())
        assertFalse(body.contains(privateKey))
        assertFalse(body.contains(token))
        assertTrue(connection.disconnected)
    }

    @Test fun errorsAreActionableWithoutEchoingServerSecrets() {
        for (code in listOf(302,401,403,409,429,500,503)) {
            val connection = FakeConnection(code, "private-key-server-error-$token")
            val error = runCatching { HostedProvisioning.register(token, privateKey) { connection } }.exceptionOrNull()
            assertTrue(error is ConfigException)
            assertFalse(error?.message.orEmpty().contains(token))
            assertTrue(connection.disconnected)
        }
        var opened = false
        assertTrue(runCatching { HostedProvisioning.register("invalid\r\nheader", privateKey) { opened = true; FakeConnection(200, "") } }.isFailure)
        assertFalse(opened)
    }

    @Test fun responseLimitAndLostResponseAreSafeToRetryWithSamePublicKey() {
        val huge = FakeConnection(200, "x".repeat(32769))
        assertTrue(runCatching { HostedProvisioning.register(token, privateKey) { huge } }.exceptionOrNull()?.message.orEmpty().contains("too large"))
        val first = FakeConnection(200, "lost response")
        assertTrue(runCatching { HostedProvisioning.register(token, privateKey) { first } }.isFailure)
        val retry = FakeConnection(200, fixture().toString())
        HostedProvisioning.register(token, privateKey) { retry }
        assertEquals(first.sent.toString("UTF-8"), retry.sent.toString("UTF-8"))
    }

    private class FakeConnection(private val status: Int, private val response: String) : HttpURLConnection(URL("https://example.test")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getResponseCode() = status
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
}
