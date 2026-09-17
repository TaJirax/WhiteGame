package com.whitegame.app.network

import com.whitegame.app.model.TcpProbeResult
import com.whitegame.app.model.UdpProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

object NetworkProber {

    suspend fun resolve(host: String, timeoutMs: Int = 1500): Long? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs.toLong() + 200) {
            try {
                measureTimeMillis { InetAddress.getByName(host) }.coerceIn(1, 9999)
            } catch (_: Exception) { null }
        }
    }

    suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int = 2500): Long? = withContext(Dispatchers.IO) {
        if (port <= 0) return@withContext null
        withTimeoutOrNull(timeoutMs.toLong() + 400) {
            try {
                measureTimeMillis {
                    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                }.coerceIn(1, 9999)
            } catch (_: Exception) { null }
        }
    }

    suspend fun probeTcp(
        host: String,
        port: Int,
        probes: Int = 5,
        timeoutMs: Int = 2000
    ): TcpProbeResult = withContext(Dispatchers.IO) {
        if (port <= 0) return@withContext TcpProbeResult(null, 100, null)
        val samples = mutableListOf<Long>()
        repeat(probes) {
            val ms = try {
                measureTimeMillis {
                    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                }.coerceIn(1, 9999)
            } catch (_: Exception) { null }
            if (ms != null) samples.add(ms)
            try { Thread.sleep(40) } catch (_: Exception) {}
        }
        val loss = ((probes - samples.size) * 100) / probes
        val avg = if (samples.isNotEmpty()) samples.sum() / samples.size else null
        val jitter = when {
            samples.size >= 2 -> samples.maxOrNull()!! - samples.minOrNull()!!
            samples.size == 1 -> 0L
            else -> null
        }
        TcpProbeResult(avg, loss, jitter, samples.minOrNull(), samples.maxOrNull())
    }

    suspend fun tlsConnect(host: String, port: Int, timeoutMs: Int = 3000): Long? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs.toLong() + 500) {
            try {
                measureTimeMillis {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, host, port, true) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                    ssl.close()
                }.coerceIn(1, 9999)
            } catch (_: Exception) { null }
        }
    }

    /**
     * UDP probe. Most game/proxy ports do not echo.
     * NO_REPLY still means packets left the device (path not hard-blocked).
     */
    suspend fun probeUdp(
        host: String,
        port: Int,
        probes: Int = 5,
        timeoutMs: Int = 900
    ): UdpProbeResult = withContext(Dispatchers.IO) {
        if (port <= 0 || port > 65535) return@withContext UdpProbeResult(null, 100, null, "FAIL")
        val rtts = mutableListOf<Long>()
        var blocked = 0
        var sendFail = 0
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return@withContext UdpProbeResult(null, 100, null, "FAIL")
        }
        val payload = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x57, 0x47, 0x55, 0x44 // "WGUD"
        )
        repeat(probes) {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.soTimeout = timeoutMs
                sock.connect(addr, port)
                val packet = DatagramPacket(payload, payload.size, addr, port)
                val t0 = System.nanoTime()
                sock.send(packet)
                val buf = ByteArray(128)
                try {
                    sock.receive(DatagramPacket(buf, buf.size))
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceIn(1, 9999)
                    rtts.add(ms)
                } catch (_: java.net.SocketTimeoutException) {
                    // no reply
                } catch (_: java.net.PortUnreachableException) {
                    blocked++
                }
            } catch (_: java.net.PortUnreachableException) {
                blocked++
            } catch (_: Exception) {
                sendFail++
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
            try { Thread.sleep(50) } catch (_: Exception) {}
        }
        val loss = (((probes - rtts.size) * 100) / probes).coerceIn(0, 100)
        val avg = if (rtts.isNotEmpty()) rtts.sum() / rtts.size else null
        val jitter = when {
            rtts.size >= 2 -> rtts.maxOrNull()!! - rtts.minOrNull()!!
            rtts.size == 1 -> 0L
            else -> null
        }
        val status = when {
            rtts.isNotEmpty() -> "REPLIES"
            blocked > probes / 2 -> "BLOCKED"
            sendFail == probes -> "FAIL"
            else -> "NO_REPLY"
        }
        UdpProbeResult(avg, loss, jitter, status, rtts.minOrNull(), rtts.maxOrNull())
    }

    suspend fun probeDns(ip: String, probes: Int = 5): Triple<Long?, Int, Long?> = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()
        repeat(probes) {
            val ms = try {
                val t = measureTimeMillis { InetAddress.getByName(ip).isReachable(1000) }
                if (t < 1000) t else null
            } catch (_: Exception) {
                try { measureTimeMillis { InetAddress.getByName(ip) } } catch (_: Exception) { null }
            }
            if (ms != null) samples.add(ms)
            try { Thread.sleep(60) } catch (_: Exception) {}
        }
        val loss = ((probes - samples.size) * 100) / probes
        val avg = if (samples.isNotEmpty()) samples.sum() / samples.size else null
        val jitter = if (samples.size >= 2) samples.maxOrNull()!! - samples.minOrNull()!!
        else if (samples.size == 1) 0L else null
        Triple(avg, loss, jitter)
    }

    /** Full path suite used by proxy / game testing */
    suspend fun probeGamePath(host: String, port: Int): Map<String, Any?> = withContext(Dispatchers.IO) {
        val resolve = resolve(host)
        val tcpMain = if (port > 0) probeTcp(host, port, 5, 2000) else TcpProbeResult(null, 100, null)
        val tcp443 = probeTcp(host, 443, 3, 2000)
        val udpMain = if (port > 0) probeUdp(host, port, 5, 900) else UdpProbeResult(null, 100, null, "FAIL")
        val udp443 = probeUdp(host, 443, 3, 800)
        val udp53 = probeUdp(host, 53, 3, 800)
        val stun = probeUdp("stun.l.google.com", 19302, 3, 1000)
        mapOf(
            "resolve" to resolve,
            "tcp" to tcpMain,
            "tcp443" to tcp443,
            "udp" to udpMain,
            "udp443" to udp443,
            "udp53" to udp53,
            "stun" to stun
        )
    }

    /** Live latency samples to public edge for Device tab */
    suspend fun livePingSuite(probes: Int = 8): Pair<List<Long>, Int> = withContext(Dispatchers.IO) {
        val hosts = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        val samples = mutableListOf<Long>()
        var fails = 0
        repeat(probes) { i ->
            val host = hosts[i % hosts.size]
            val r = probeTcp(host, 443, 1, 1500)
            if (r.avgMs != null) samples.add(r.avgMs) else fails++
            try { Thread.sleep(40) } catch (_: Exception) {}
        }
        val loss = if (probes == 0) 100 else (fails * 100) / probes
        samples to loss
    }
}
