package com.whitegame.app.network

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProbeStatsTest {
    @Test fun emptySamplesAreUnknownNotZeroLoss() {
        val stats = ProbeStats.from(emptyList())
        assertNull(stats.latencyMs)
        assertNull(stats.lossPct)
        assertNull(stats.jitterMs)
    }
    @Test fun failedProbesCountInLossButNotLatency() {
        val stats = ProbeStats.from(listOf(100, 120, null, 200))
        assertEquals(140L, stats.latencyMs)
        assertEquals(25, stats.lossPct)
        assertEquals(20L, stats.jitterMs)
    }
    @Test fun oneSuccessfulProbeCannotClaimZeroJitter() {
        assertNull(ProbeStats.from(listOf(100, null)).jitterMs)
    }
    @Test fun localSocksAcceptanceIsNotReportedAsRemoteLatency() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream()
                    repeat(3) { input.read() }
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                    val header = ByteArray(5)
                    java.io.DataInputStream(input).readFully(header)
                    val address = ByteArray((header[4].toInt() and 255) + 2)
                    java.io.DataInputStream(input).readFully(address)
                    socket.getOutputStream().write(byteArrayOf(5,0,0,1,127,0,0,1,0,80))
                    // A proxy can acknowledge CONNECT before contacting the endpoint.
                    // Closing without a remote TLS response must fail the measurement.
                }
            }
            assertNull(RouteProbe.handshakeMs("example.com", socksPort = server.localPort, timeoutMs = 500))
            worker.join(2500)
            assertFalse(worker.isAlive)
        }
    }
}
