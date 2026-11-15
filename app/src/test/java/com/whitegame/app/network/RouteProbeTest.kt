package com.whitegame.app.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class RouteProbeTest {
    @Test fun emptyDnsAnswerIsDiagnosedAsDnsFailure() {
        val result = RouteProbe.probe("example.com", ips = emptyList())
        assertNull(result.ms)
        assertEquals(RouteProbe.Failure.DNS, result.failure)
    }

    @Test fun refusedConnectionIsNotReportedAsLatency() {
        val port = ServerSocket(0).use { it.localPort }
        val result = RouteProbe.probe("localhost", port, ips = listOf("127.0.0.1"), timeoutMs = 500)
        assertNull(result.ms)
        assertEquals(RouteProbe.Failure.CONNECT, result.failure)
    }

    @Test fun triesSecondResolvedAddressAndReportsTheRemoteTlsFailure() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2500
            val worker = thread {
                server.accept().use { socket ->
                    socket.getOutputStream().write("not TLS".toByteArray())
                }
            }
            val result = RouteProbe.probe("localhost", server.localPort,
                ips = listOf("127.0.0.2", "127.0.0.1"), timeoutMs = 1000)
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNull(result.ms)
            assertEquals(RouteProbe.Failure.TLS, result.failure)
        }
    }

    @Test fun rejectedSocksNegotiationIsDiagnosedAsProxyFailure() {
        ServerSocket(0).use { server ->
            server.soTimeout = 2500
            val worker = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    repeat(3) { socket.getInputStream().read() }
                    socket.getOutputStream().write(byteArrayOf(5, -1))
                }
            }
            val result = RouteProbe.probe("example.com", socksPort = server.localPort, timeoutMs = 1000)
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNull(result.ms)
            assertEquals(RouteProbe.Failure.PROXY, result.failure)
        }
    }
}
