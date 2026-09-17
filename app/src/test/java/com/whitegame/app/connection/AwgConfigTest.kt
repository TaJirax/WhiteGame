package com.whitegame.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/**
 * The UAPI payload is where a config quietly becomes a tunnel that never handshakes: a hostname
 * left unresolved, a key still in base64, a peer section opened by the wrong field. These pin
 * the shape the engine actually accepts.
 */
class AwgConfigTest {

    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val peerKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="

    /** Resolution is stubbed: unit tests must not depend on DNS. */
    private val resolver: (String) -> InetAddress? = { host ->
        when (host) {
            "server.example.com" -> InetAddress.getByAddress(host, byteArrayOf(203.toByte(), 0, 113, 7))
            else -> null
        }
    }

    private fun wireGuardConf(extra: String = "") = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.7.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 8.8.8.8
        MTU = 1280
        $extra

        [Peer]
        PublicKey = $peerKey
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = server.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun parsesAWireGuardConfig() {
        val c = AwgConfig.parse(wireGuardConf())
        assertEquals(TunnelKind.WIREGUARD, c.kind)
        assertEquals(listOf("10.7.0.2/32", "fd00::2/128"), c.iface.addresses)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), c.iface.dnsServers)
        assertEquals(1280, c.iface.mtu)
        assertEquals(1, c.peers.size)
        assertEquals("server.example.com:51820", c.peers[0].endpoint)
        assertEquals("25", c.peers[0].persistentKeepalive)
        assertTrue(c.isFullTunnel)
    }

    @Test
    fun emitsUapiWithHexKeysAndAResolvedEndpoint() {
        val uapi = AwgConfig.parse(wireGuardConf()).toUapi(resolver)
        val lines = uapi.trim().lines()

        // Hex, not base64 — the engine rejects base64 outright.
        assertEquals(
            "private_key=c809f3e5317e9575c9b5ed78b638b7ce530dabe85ddab614220241801ddf0669",
            lines.first()
        )
        assertTrue("peer must be opened by public_key", lines.contains("public_key=" +
            "c53201039adba14be71f886da1d8dbe9eebded08cb111b75340078999aa9f038"))
        // A hostname here would be silently dropped by the engine.
        assertTrue("endpoint must be an IP literal", lines.contains("endpoint=203.0.113.7:51820"))
        assertTrue(lines.contains("replace_peers=true"))
        assertTrue(lines.contains("allowed_ip=0.0.0.0/0"))
        assertTrue(lines.contains("allowed_ip=::/0"))
        assertTrue(lines.contains("persistent_keepalive_interval=25"))
    }

    @Test
    fun refusesToBuildUapiWhenTheEndpointCannotBeResolved() {
        val conf = wireGuardConf().replace("server.example.com", "nowhere.invalid")
        try {
            AwgConfig.parse(conf).toUapi(resolver)
            fail("expected a ConfigException")
        } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("nowhere.invalid"))
        }
    }

    // ---------------- AmneziaWG ----------------

    private fun amneziaConf() = wireGuardConf(
        """
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 50
        S2 = 100
        H1 = 1234567
        H2 = 2345678
        H3 = 3456789
        H4 = 4567890
        """.trimIndent()
    )

    @Test
    fun parsesAmneziaObfuscationFields() {
        val c = AwgConfig.parse(amneziaConf())
        assertEquals(TunnelKind.AMNEZIA, c.kind)
        assertEquals("4", c.iface.amnezia["jc"])
        assertEquals("1234567", c.iface.amnezia["h1"])
        assertEquals(9, c.iface.amnezia.size)
    }

    @Test
    fun emitsAmneziaFieldsInUapi() {
        val uapi = AwgConfig.parse(amneziaConf()).toUapi(resolver)
        listOf("jc=4", "jmin=40", "jmax=70", "s1=50", "s2=100",
               "h1=1234567", "h2=2345678", "h3=3456789", "h4=4567890").forEach {
            assertTrue("missing $it", uapi.lines().contains(it))
        }
        // Obfuscation is device-level and must land before the first peer opens.
        assertTrue(uapi.indexOf("jc=4") < uapi.indexOf("public_key="))
    }

    @Test
    fun aPlainWireGuardConfigEmitsNoObfuscationKeys() {
        val uapi = AwgConfig.parse(wireGuardConf()).toUapi(resolver)
        listOf("jc=", "jmin=", "s1=", "h1=").forEach {
            assertTrue("$it should be absent", !uapi.contains(it))
        }
    }

    @Test
    fun convertsOnOffBooleansToWhatGoAccepts() {
        val conf = wireGuardConf("RandomTrailers = on\nDisableCookies = off")
        val uapi = AwgConfig.parse(conf).toUapi(resolver)
        // strconv.ParseBool rejects "on"/"off".
        assertTrue(uapi.lines().contains("random_trailers=1"))
        assertTrue(uapi.lines().contains("disable_cookies=0"))
    }

    // ---------------- validation ----------------

    @Test
    fun roundTripsAmnezia31SettingsAndKeepaliveRanges() {
        val conf = wireGuardConf("""
            H1 = 3000000000-3000000010
            H2 = 3100000000-3100000010
            H3 = 3200000000-3200000010
            H4 = 4294967290-4294967295
            S3 = 12
            S4 = 24
            I1 = <b 0x1234><r 8>
            HeaderProtectionKey = $privateKey
            ContentPaddingAddition = 0-64
            RekeyAfterTime = 100-120
            RekeyTimeout = 4-6
            RejectAfterTime = 160-180
            KeepaliveTimeout = 8-12
            MaxHandshakeAttempts = 15-20
            RandomTrailers = true
            DisableCookies = false
        """.trimIndent()).replace("PersistentKeepalive = 25", "PersistentKeepalive = 22-30")
        val parsed = AwgConfig.parse(conf)
        assertEquals("22-30", parsed.peers.single().persistentKeepalive)
        assertEquals(parsed, AwgConfig.parse(parsed.toConfText()))
        val uapi = parsed.toUapi(resolver)
        listOf("persistent_keepalive_interval=22-30", "h4=4294967290-4294967295",
            "header_protection_key=" + Keys.toHex(privateKey), "content_padding_addition=0-64",
            "rekey_after_time=100-120", "rekey_timeout=4-6", "reject_after_time=160-180",
            "keepalive_timeout=8-12", "max_handshake_attempts=15-20", "random_trailers=1",
            "disable_cookies=0", "s3=12", "s4=24", "i1=<b 0x1234><r 8>").forEach {
            assertTrue("Missing $it", it in uapi.lines())
        }
    }

    @Test
    fun rejectsMalformed31RangesAndHeaderKeys() {
        expectError(wireGuardConf().replace("PersistentKeepalive = 25", "PersistentKeepalive = 30-22"), "range")
        expectError(wireGuardConf("H1 = 4294967296"), "range")
        expectError(wireGuardConf("H1 = 100-200\nH2 = 150-250"), "overlap")
        expectError(wireGuardConf("HeaderProtectionKey = invalid"), "HeaderProtectionKey")
        expectError(wireGuardConf("RekeyTimeout = 9-3"), "range")
    }

    @Test
    fun preservesCommentCharactersInsideSignatureExpressions() {
        val parsed = AwgConfig.parse(wireGuardConf("I1 = <b 0x23><r 8> # trailing comment"))
        assertEquals("<b 0x23><r 8>", parsed.iface.amnezia["i1"])
        val literal = AwgConfig.parse(wireGuardConf("I1 = <str a#b;c> ; comment"))
        assertEquals("<str a#b;c>", literal.iface.amnezia["i1"])
    }

    private fun expectError(conf: String, fragment: String) {
        try {
            AwgConfig.parse(conf)
            fail("expected a ConfigException mentioning \"$fragment\"")
        } catch (e: ConfigException) {
            assertTrue(
                "message was: " + e.message,
                e.message!!.contains(fragment, ignoreCase = true)
            )
        }
    }

    @Test
    fun rejectsConfigsTheEngineWouldSilentlyFailOn() {
        expectError("PrivateKey = $privateKey", "No [Interface]")
        expectError("[Interface]\nAddress = 10.0.0.2/32", "PrivateKey")
        expectError("[Interface]\nPrivateKey = nonsense!\nAddress = 10.0.0.2/32", "base64")
        expectError("[Interface]\nPrivateKey = $privateKey", "Address")
        expectError("[Interface]\nPrivateKey = $privateKey\nAddress = 10.0.0.2/32", "No [Peer]")
        expectError(wireGuardConf().replace("MTU = 1280", "MTU = 70"), "out of range")
        expectError(wireGuardConf().replace("Address = 10.7.0.2/32", "Address = 10.7.0.999/32"), "valid IPv4")
        expectError(wireGuardConf().replace(":51820", ""), "host:port")
    }

    @Test
    fun tellsTheUserToReplaceTemplatePlaceholders() {
        expectError(
            wireGuardConf().replace(privateKey, "PASTE_YOUR_PRIVATE_KEY"),
            "Replace the PrivateKey placeholder"
        )
        expectError(
            wireGuardConf().replace(peerKey, "PASTE_SERVER_PUBLIC_KEY"),
            "Replace the PublicKey placeholder"
        )
    }

    /** Every shipped template must be a starting point that reports what is still missing. */
    @Test
    fun everyTemplateParsesOnceItsPlaceholdersAreReplaced() {
        for (kind in TunnelKind.values().filter { it != TunnelKind.XRAY }) {
            val filled = TunnelStore.template(kind)
                .replace("PASTE_YOUR_WARP_PRIVATE_KEY", privateKey)
                .replace("PASTE_YOUR_PRIVATE_KEY", privateKey)
                .replace("PASTE_SERVER_PUBLIC_KEY", peerKey)
            val parsed = AwgConfig.parse(filled)
            assertEquals("template for " + kind, kind, parsed.kind)
            assertTrue(parsed.peers.isNotEmpty())
        }
    }

    @Test
    fun acceptsWarpAmneziaWithWireGuardsOwnHeaders() {
        // What the BlueKnight panel's /sub/amnezia emits for Cloudflare WARP, Reserved included.
        val conf = wireGuardConf("Jc = 5\nJmin = 10\nJmax = 50\nS1 = 15\nS2 = 25\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4") +
            "\nReserved = 12,34,56"
        val c = AwgConfig.parse(conf)
        assertEquals(TunnelKind.AMNEZIA, c.kind)
        val uapi = c.toUapi(resolver)
        assertTrue(uapi.contains("jc=5"))
        // Hn = n is WireGuard's default; it is not sent to the engine at all.
        assertTrue(uapi.lines().none { it.matches(Regex("h[1-4]=.*")) })
    }

    @Test
    fun rejectsAmneziaHeadersTheEngineWouldReject() {
        // The current engine permits small non-overlapping headers.
        assertEquals("3", AwgConfig.parse(wireGuardConf("H1 = 3\nH2 = 9\nH3 = 10\nH4 = 11")).iface.amnezia["h1"])
        // Duplicated headers make packet types ambiguous.
        expectError(wireGuardConf("H1 = 9\nH2 = 9\nH3 = 10\nH4 = 11"), "different from each other")
        expectError(wireGuardConf("Jmin = 90\nJmax = 40"), "must not exceed")
        expectError(wireGuardConf("Jc = many"), "not a number")
    }

    // ---------------- round trip ----------------

    @Test
    fun roundTripsThroughTextWithoutLosingObfuscation() {
        val once = AwgConfig.parse(amneziaConf())
        val twice = AwgConfig.parse(once.toConfText())
        assertEquals(once.iface.amnezia, twice.iface.amnezia)
        assertEquals(once.iface.addresses, twice.iface.addresses)
        assertEquals(once.iface.dnsServers, twice.iface.dnsServers)
        assertEquals(once.peers, twice.peers)
        assertEquals(once.toUapi(resolver), twice.toUapi(resolver))
    }

    @Test
    fun toleratesCommentsOddCasingAndSpacing() {
        val messy = """
            # my provider's config
            [interface]
              privatekey=$privateKey
            ADDRESS  =  10.7.0.2/32   ; inline comment
            dns = 1.1.1.1

            [PEER]
            PublicKey   =   $peerKey
            allowedips = 0.0.0.0/0
            endpoint = server.example.com:51820
        """.trimIndent()
        val c = AwgConfig.parse(messy)
        assertEquals(listOf("10.7.0.2/32"), c.iface.addresses)
        assertEquals(1, c.peers.size)
        assertNull(c.peers[0].persistentKeepalive)
    }

    @Test
    fun separatesDnsServersFromSearchDomains() {
        val c = AwgConfig.parse(wireGuardConf().replace("DNS = 1.1.1.1, 8.8.8.8", "DNS = 1.1.1.1, corp.internal"))
        assertEquals(listOf("1.1.1.1"), c.iface.dnsServers)
        assertEquals(listOf("corp.internal"), c.iface.dnsSearchDomains)
    }

    @Test
    fun readsEveryPeerInAMultiPeerConfig() {
        val conf = wireGuardConf() + """

            [Peer]
            PublicKey = $privateKey
            AllowedIPs = 10.9.0.0/24
        """.trimIndent()
        val c = AwgConfig.parse(conf)
        assertEquals(2, c.peers.size)
        assertEquals(listOf("10.9.0.0/24"), c.peers[1].allowedIps)
        val uapi = c.toUapi(resolver)
        assertEquals(2, uapi.lines().count { it.startsWith("public_key=") })
    }

    @Test
    fun derivesThePublicKeyShownToTheUser() {
        // Cross-checked against Go's crypto/ecdh X25519 for this private key.
        assertEquals(
            "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=",
            AwgConfig.parse(wireGuardConf()).publicKey
        )
    }
    @Test fun acceptsModernRangesAndPreservesSignatureExpressions() {
        val config = AwgConfig.parse(wireGuardConf("H1 = 100 - 200\nH2 = 300-400\nS3 = 4\nS4 = 8\nI1 = <b 0x1234>\nPersistentKeepalive = 20-30"))
        val uapi = config.toUapi(resolver)
        assertTrue(uapi.contains("h1=100-200"))
        assertTrue(uapi.contains("s3=4"))
        assertTrue(uapi.contains("i1=<b 0x1234>"))
        assertEquals(config.iface.amnezia, AwgConfig.parse(config.toConfText()).iface.amnezia)
    }

    @Test fun rejectsOverlappingRangesAndInvalidPadding() {
        expectError(wireGuardConf("H1 = 10-20\nH2 = 20-30"), "overlap")
        expectError(wireGuardConf("S3 = -1"), "between")
        expectError(wireGuardConf("S4 = 65536"), "between")
        expectError(wireGuardConf("H5 = 123"), "Unsupported")
    }

}
