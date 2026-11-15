package com.whitegame.app.connection

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

class ReservedUdpRelayTest {
    private val bytes = listOf(49, 132, 132)

    @Test fun forwardsRealUdpDatagramsAndRestoresNativeWireGuardHeaders() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            DatagramSocket(0, loopback).use { engine ->
                server.soTimeout = 2000
                engine.soTimeout = 2000
                var protectedBeforeConnect = false
                ReservedUdpRelay(InetSocketAddress(loopback, server.localPort), bytes) {
                    protectedBeforeConnect = !it.isConnected
                    true
                }.use { relay ->
                    assertTrue(protectedBeforeConnect)
                    relay.start()
                    val endpoint = AwgConfig.splitHostPort(relay.endpoint)
                    val outgoing = ByteArray(148) { (it + 9).toByte() }.apply {
                        this[0] = 1
                        for (i in 1..3) this[i] = 0
                    }
                    engine.send(DatagramPacket(outgoing, outgoing.size, loopback, endpoint.second))
                    val received = DatagramPacket(ByteArray(2000), 2000)
                    server.receive(received)
                    val expected = outgoing.copyOf().apply { bytes.forEachIndexed { i, b -> this[i + 1] = b.toByte() } }
                    assertArrayEquals(expected, received.data.copyOf(received.length))

                    val response = ByteArray(92) { (it + 21).toByte() }.apply {
                        this[0] = 2
                        bytes.forEachIndexed { i, b -> this[i + 1] = b.toByte() }
                    }
                    server.send(DatagramPacket(response, response.size, received.socketAddress))
                    engine.receive(received)
                    val plain = response.copyOf().apply { for (i in 1..3) this[i] = 0 }
                    assertArrayEquals(plain, received.data.copyOf(received.length))
                }
            }
        }
    }

    @Test fun leavesJunkAndSignaturePacketsUnmodified() {
        for (data in listOf(byteArrayOf(1, 0, 0), ByteArray(80).apply { this[0] = 1 },
            byteArrayOf(0xce.toByte(), 0, 0, 0, 1), ByteArray(148).apply { this[0] = 1; this[1] = 3 })) {
            val expected = data.copyOf()
            ReservedUdpRelay.applyOutgoing(data, data.size, bytes)
            assertArrayEquals(expected, data)
        }
    }

    @Test fun refusesAnUnprotectedRemoteSocket() {
        try {
            ReservedUdpRelay(InetSocketAddress("127.0.0.1", 51820), bytes) { false }
            fail("The remote socket must be protected before use")
        } catch (e: ConfigException) { assertTrue(e.message!!.contains("protect")) }
    }

    @Test fun closesTheLocalSocketWhenRemoteSocketAllocationFails() {
        var allocated: DatagramSocket? = null
        var calls = 0
        try {
            ReservedUdpRelay(InetSocketAddress("127.0.0.1", 51820), bytes, { address ->
                if (++calls == 2) throw SocketException("simulated allocation failure")
                DatagramSocket(address).also { allocated = it }
            }, { true })
            fail("Expected remote allocation failure")
        } catch (_: SocketException) {
            assertNotNull(allocated)
            assertTrue("Already allocated local socket must be closed", allocated!!.isClosed)
        } finally { allocated?.close() }
    }
}
