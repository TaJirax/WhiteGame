package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class DesktopStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsMixedAndEncodedProfilesWithoutPromotingBareEndpoints() {
        val mixed = "$wireGuard\n$xray\nprobe-only.example.com:443"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mixed.toByteArray())
        val imported = listOf(mixed, "\uFEFF$encoded").map { input ->
            desktopConfigInputs(input).mapIndexed { index, config -> desktopProfile(config, id = "profile-$index") }
        }

        assertEquals(imported.first(), imported.last())
        assertEquals(listOf("[2001:db8::10]:51820", "edge.example.com:443"), imported.first().map { it.endpoint })
        assertEquals(listOf("WireGuard", "Xray"), imported.first().map { it.kind })
        val peer = AwgConfig.parse(imported.first().first().config).peers.single()
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer.allowedIps)
        assertEquals(xray, imported.first().last().config)
        assertThrows(ConfigException::class.java) { desktopConfigInputs(" \uFEFF ") }
    }

    @Test
    fun persistsProfilesSelectionPreferencesAndOptionalReportMetrics() {
        val directory = temporaryFolder.newFolder("whitegame").toPath()
        val first = desktopProfile(wireGuard, "Home", "home")
        val second = desktopProfile(xray, "Office", "office")
        val reports = listOf(
            DesktopReport("config-report", DesktopReportKind.CONFIGS, "Evening", listOf(
                DesktopMeasurement("office", "Office", second.endpoint, "Xray", 31.4, null, null,
                    18.25, null, "Download complete", "Measured"),
                DesktopMeasurement("home", "Home", first.endpoint, "WireGuard", 0.0,
                    lossPercent = 0.0, status = "Measured")
            ))
        )

        DesktopStore(directory).apply {
            saveProfiles(listOf(first, second), second.id)
            put("dark" to false, "preferredDns" to "1.1.1.1")
            saveReports(reports)
        }

        val reopened = DesktopStore(directory)
        assertEquals(listOf(first, second), reopened.profiles)
        assertEquals(second.id, reopened.string("selectedId"))
        assertFalse(reopened.flag("dark", true))
        assertEquals("1.1.1.1", reopened.string("preferredDns"))
        assertEquals(reports, reopened.reports)

        reopened.saveProfiles(emptyList(), null)
        reopened.put("preferredDns" to null)
        val cleared = DesktopStore(directory)
        assertTrue(cleared.profiles.isEmpty())
        assertEquals("", cleared.string("selectedId"))
        assertEquals("", cleared.string("preferredDns"))
        assertEquals(reports, cleared.reports)
    }

    private companion object {
        val wireGuard = """
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.7.0.2/32, 2001:db8::2/128

            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = [2001:db8::10]:51820
        """.trimIndent()
        const val xray = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443?security=tls#Office"
    }
}
