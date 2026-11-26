package com.whitegame.desktop

import com.whitegame.app.model.DnsItem
import com.whitegame.app.network.DnsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DesktopDnsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun preservesEveryBundledEntryAndAddsPublishedIpv6Resolvers() {
        val bundled = WindowsCatalog.resolvers()
        val catalog = desktopDnsCatalog(bundled)
        assertEquals(bundled.map(DnsItem::ip), catalog.take(bundled.size).map(DesktopDnsResolver::address))
        assertEquals(catalog.size, catalog.map(DesktopDnsResolver::id).distinct().size)
        assertTrue(catalog.all { normalizedDnsAddress(it.address) != null })
        assertTrue(catalog.any { it.name == "Cloudflare" && it.address == "2606:4700:4700::1111" })
        assertTrue(catalog.any { it.name == "Google Public DNS" && it.address == "2001:4860:4860::8888" })
        assertTrue(catalog.any { it.name == "Quad9" && it.address == "2620:fe::fe" })
    }

    @Test
    fun keepsAliasesInTheCatalogButTestsEquivalentAddressesOnce() {
        val first = customDnsResolver("Office", "2001:db8::1")
        val second = customDnsResolver("Backup office", "2001:0DB8:0:0:0:0:0:1")
        val catalog = listOf(first, second)
        assertFalse(first.id == second.id)
        assertEquals(first.addressKey, second.addressKey)
        assertEquals(listOf(first.addressKey), catalog.dnsTargets().map(DnsItem::ip))
        assertNull(normalizedDnsAddress("localhost"))
        assertNull(normalizedDnsAddress("1.2.3"))
        assertNull(normalizedDnsAddress("1.2.3.256"))
        assertNull(normalizedDnsAddress("[2001:db8::1]:53"))
    }

    @Test
    fun persistsEditedCustomResolversAndRemoval() {
        val directory = temporary.newFolder().toPath()
        val first = customDnsResolver("My provider", "192.0.2.2")
        val store = DesktopStore(directory)
        store.saveDnsResolvers(listOf(first))
        assertEquals(listOf(first), DesktopStore(directory).dnsResolvers)
        val changed = customDnsResolver("My provider IPv6", "2001:db8::2", first.id)
        store.saveDnsResolvers(listOf(changed))
        assertEquals(listOf(changed), DesktopStore(directory).dnsResolvers)
        store.saveDnsResolvers(emptyList())
        assertTrue(DesktopStore(directory).dnsResolvers.isEmpty())
    }

    @Test
    fun queriesAnIpv6ResolverThroughUdp() {
        val server = runCatching { DatagramSocket(0, InetAddress.getByName("::1")) }.getOrNull()
        assumeNotNull(server)
        requireNotNull(server).use { socket ->
            socket.soTimeout = 3_000
            val reply = CompletableFuture.runAsync {
                val request = DatagramPacket(ByteArray(600), 600)
                socket.receive(request)
                val bytes = request.data.copyOf(request.length)
                bytes[2] = 0x81.toByte()
                bytes[3] = 0x80.toByte()
                bytes[7] = 1
                val answer = bytes + byteArrayOf(
                    0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 4
                )
                socket.send(DatagramPacket(answer, answer.size, request.address, request.port))
            }
            val result = DnsClient.query("::1", "example.com", timeoutMs = 3_000, port = socket.localPort)
            reply.get(4, TimeUnit.SECONDS)
            assertEquals(listOf("192.0.2.4"), result?.ips)
        }
    }
}
