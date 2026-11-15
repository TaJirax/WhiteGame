package com.whitegame.app.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DnsProbeTest {
    private fun answer(query: ByteArray): ByteArray = query.copyOf().apply {
        this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1
    } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203.toByte(), 0, 113, 7)

    private fun fixture(count: Int = 1, replies: (ByteArray, Int) -> List<ByteArray>, check: (Int) -> Unit) {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val failure = AtomicReference<Throwable?>()
            val worker = thread {
                try {
                    repeat(count) { index ->
                        val request = DatagramPacket(ByteArray(512), 512)
                        server.receive(request)
                        replies(request.data.copyOf(request.length), index).forEach { reply ->
                            server.send(DatagramPacket(reply, reply.size, request.address, request.port))
                        }
                    }
                } catch (t: Throwable) { failure.set(t) }
            }
            try { check(server.localPort) } finally { worker.join(3500) }
            assertFalse("DNS fixture did not finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("DNS fixture failed", it) }
        }
    }

    @Test fun validatesRealUdpDnsAnswerAndIgnoresStaleTransaction() = fixture(replies = { query, _ ->
        val stale = answer(query).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        listOf(stale, answer(query))
    }) { port ->
        val result = DnsClient.query("127.0.0.1", "example.com", 1000, port = port)
        assertNotNull(result)
        assertEquals(listOf("203.0.113.7"), result!!.ips)
        assertTrue(result.ms >= 1)
    }

    @Test fun successfulDnsQueriesProduceLatencyInsteadOfIcmpTiming() = fixture(3, { query, _ -> listOf(answer(query)) }) { port ->
        val (latency, loss, _) = runBlocking { NetworkProber.probeDns("127.0.0.1", 3, 500, port) }
        assertNotNull(latency)
        assertEquals(0, loss)
    }

    @Test fun fastNegativeReplyCountsAsFailureInDnsRanking() = fixture(3, { query, index ->
        listOf(answer(query).apply { if (index == 1) this[3] = 0x83.toByte() })
    }) { port ->
        val (latency, loss, jitter) = runBlocking { NetworkProber.probeDns("127.0.0.1", 3, 500, port) }
        assertNotNull(latency)
        assertEquals(33, loss)
        assertNull("Separated responses cannot claim measured jitter", jitter)
    }

    @Test fun noDnsReplyCannotTurnLoopbackReachabilityIntoSuccess() = fixture(replies = { _, _ -> emptyList() }) { port ->
        val (latency, loss, jitter) = runBlocking { NetworkProber.probeDns("127.0.0.1", 1, 100, port) }
        assertNull(latency)
        assertEquals(100, loss)
        assertNull(jitter)
    }

    @Test fun rejectsErrorsTruncationWrongQuestionsAndQueryEchoes() {
        val query = DnsPacket.buildQuery(42, "example.com")
        val invalid = listOf(
            answer(query).apply { this[3] = 0x83.toByte() }, // NXDOMAIN
            answer(query).apply { this[3] = 0x82.toByte() }, // SERVFAIL
            answer(query).apply { this[2] = 0x83.toByte() }, // truncated
            answer(query).apply { this[13] = 'x'.code.toByte() },
            query,
            byteArrayOf(0, 42)
        )
        invalid.forEach { assertFalse(DnsPacket.isAnswerTo(it, query)) }
    }
}
