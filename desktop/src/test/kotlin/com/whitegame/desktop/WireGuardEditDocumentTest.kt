package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WireGuardEditDocumentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun editsValuesWithoutChangingOtherPeersAndProviderLines() {
        val source = """
            # Provider profile 100%
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
            DNS = 1.1.1.1 # shared resolver
            DNS = old.example
            Table = 100

            [Peer]
            PublicKey = $peerA
            PresharedKey = $preshared
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 10.7.0.0/24

            [Peer]
            PublicKey = $peerB
            Endpoint = [2001:db8::22]:51820
            AllowedIPs = 2001:db8:1::/64
        """.trimIndent()
        val document = WireGuardEditDocument.parse(source)
        assertEquals(listOf("Interface", "Peer 1", "Peer 2"), document.sections.map { it.title })
        assertTrue(document.sections[0].fields.first { it.key == "PrivateKey" }.secret)
        assertTrue(document.sections[1].fields.first { it.key == "PresharedKey" }.secret)
        assertEquals(source, document.render(listOf(emptyMap(), emptyMap(), emptyMap())))

        val edited = document.render(listOf(
            mapOf("DNS" to "9.9.9.9, new.example", "MTU" to "1380"),
            mapOf("Endpoint" to "vpn.example:51900"),
            emptyMap()
        ))
        val parsed = AwgConfig.parse(edited)
        assertEquals(listOf("9.9.9.9"), parsed.iface.dnsServers)
        assertEquals(listOf("new.example"), parsed.iface.dnsSearchDomains)
        assertEquals(1380, parsed.iface.mtu)
        assertEquals("vpn.example:51900", parsed.peers[0].endpoint)
        assertEquals(preshared, parsed.peers[0].presharedKey)
        assertEquals(AwgConfig.parse(source).peers[1], parsed.peers[1])
        assertTrue(edited.contains("# Provider profile 100%"))
        assertTrue(edited.contains("DNS = 9.9.9.9, new.example # shared resolver"))
        assertTrue(edited.contains("Table = 100"))
        assertFalse(edited.contains("DNS = old.example"))

        val directory = folder.newFolder("profiles").toPath()
        val saved = desktopProfile(edited)
        assertEquals("WireGuard", saved.name)
        DesktopStore(directory).saveProfiles(listOf(saved), saved.id)
        assertEquals(edited, DesktopStore(directory).profiles.single().config)
        assertEquals(
            saved.configurationIdentity(),
            desktopProfile(parsed.toConfText()).configurationIdentity()
        )
    }

    @Test
    fun preservesAmneziaPacketFieldsAndCanChangeAndClearReservedOverrides() {
        val amnezia = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
            H1 = 11
            I1 = <b #;> # provider note
            RekeyAfterTime = 100

            [Peer]
            PublicKey = $peerA
        """.trimIndent()
        val fields = WireGuardEditDocument.parse(amnezia)
        val advanced = fields.sections[0].fields.associate { it.key to it.value }
        assertEquals("<b #;>", advanced["I1"])
        assertTrue(advanced.containsKey("DisableCookies"))
        val edited = fields.render(listOf(mapOf("I1" to "<b ;#>", "H1" to "12"), emptyMap()))
        assertTrue(edited.contains("I1 = <b ;#> # provider note"))
        assertEquals("100", AwgConfig.parse(edited).iface.amnezia["rekeyaftertime"])
        assertEquals("12", AwgConfig.parse(edited).iface.amnezia["h1"])

        val warp = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
            Reserved = 1, 2, 3

            [Peer]
            PublicKey = $peerA
            Reserved = 7, 8, 9

            [Peer]
            PublicKey = $peerB
        """.trimIndent()
        val reserved = WireGuardEditDocument.parse(warp)
        assertEquals("1, 2, 3", reserved.sections[0].fields.first { it.key == "Reserved" }.value)
        assertEquals("", reserved.sections[2].fields.first { it.key == "Reserved" }.value)
        val applied = reserved.render(listOf(mapOf("Reserved" to "4, 5, 6"), mapOf("Reserved" to ""), emptyMap()))
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), AwgConfig.parse(applied).peers.map { it.reserved })
    }

    @Test
    fun refusesToDeleteRequiredFieldsOrInsertMultilineValues() {
        val config = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32

            [Peer]
            PublicKey = $peerA
        """.trimIndent()
        val document = WireGuardEditDocument.parse(config)
        assertThrows(ConfigException::class.java) {
            document.render(listOf(emptyMap(), mapOf("PublicKey" to "")))
        }
        assertThrows(ConfigException::class.java) {
            document.render(listOf(mapOf("DNS" to "1.1.1.1\nMTU = 1380"), emptyMap()))
        }
    }

    private companion object {
        val privateKey = Keys.encode(ByteArray(32) { 1 })
        val peerA = Keys.encode(ByteArray(32) { 2 })
        val peerB = Keys.encode(ByteArray(32) { 3 })
        val preshared = Keys.encode(ByteArray(32) { 4 })
    }
}
