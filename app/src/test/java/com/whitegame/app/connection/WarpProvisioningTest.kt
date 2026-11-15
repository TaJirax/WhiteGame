package com.whitegame.app.connection

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class WarpProvisioningTest {
    private val privateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val serverKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    private fun response() = JSONObject("""{
      "config": {
        "client_id": "MYSE",
        "interface": {"addresses": {"v4":"172.16.0.2", "v6":"2606:4700:110:80a9::1"}},
        "peers": [{"public_key":"$serverKey", "endpoint": {
          "host":"engage.cloudflareclient.com:2408", "v4":"162.159.192.1:2408",
          "v6":"2606:4700:d0::1", "ports":[2408,500]
        }}]
      }
    }""")

    @Test fun requestSendsOnlyDerivedPublicKeyAndUtcTermsTimestamp() {
        val request = WarpProvisioning.request(Keys.publicKeyOf(privateKey), Date(0))
        assertEquals(Keys.publicKeyOf(privateKey), request.getString("key"))
        assertEquals("1970-01-01T00:00:00.000Z", request.getString("tos"))
        assertEquals("wireguard", request.getString("tunnel_type"))
        assertFalse(request.toString().contains(privateKey))
    }

    @Test fun responseBuildsRealDualStackProfileAndAllAdvertisedPorts() {
        val profile = WarpProvisioning.fromResponse(privateKey, response())
        val parsed = AwgConfig.parse(profile.confText)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:80a9::1/128"), parsed.iface.addresses)
        assertEquals(listOf(49,132,132), parsed.peers.single().reserved)
        assertEquals(serverKey, parsed.peers.single().publicKey)
        assertEquals(listOf("engage.cloudflareclient.com:2408", "162.159.192.1:2408", "162.159.192.1:500",
            "[2606:4700:d0::1]:2408", "[2606:4700:d0::1]:500"), profile.endpoints)
        assertEquals(privateKey, parsed.iface.privateKey)
    }

    @Test fun invalidResponseNeverCreatesInventedCredentials() {
        val examples = listOf(
            JSONObject(),
            response().put("key", serverKey),
            response().apply { getJSONObject("config").put("client_id", "AA==") },
            response().apply { getJSONObject("config").getJSONObject("interface").put("addresses", JSONObject()) },
            response().apply { getJSONObject("config").getJSONArray("peers").getJSONObject(0).put("public_key", "invalid") }
        )
        examples.forEach { assertTrue(runCatching { WarpProvisioning.fromResponse(privateKey, it) }.exceptionOrNull() is ConfigException) }
    }

    @Test fun registrationPostsPublicKeyAndDisconnectsOnSuccessOrError() {
        val connection = FakeConnection(200, response().toString())
        WarpProvisioning.register(privateKey) { connection }
        assertEquals("POST", connection.requestMethod)
        assertEquals(Keys.publicKeyOf(privateKey), JSONObject(connection.sent.toString("UTF-8")).getString("key"))
        assertFalse(connection.sent.toString("UTF-8").contains(privateKey))
        assertTrue(connection.disconnected)
        val limited = FakeConnection(429, "secret error response")
        val error = runCatching { WarpProvisioning.register(privateKey) { limited } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("rate limited"))
        assertFalse(error?.message.orEmpty().contains("secret error"))
        assertTrue(limited.disconnected)
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
