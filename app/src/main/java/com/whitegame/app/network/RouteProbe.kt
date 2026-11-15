package com.whitegame.app.network

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import android.os.Build
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * One comparable number for every way of reaching a game server: the time to open a connection
 * to it and finish a TLS handshake. It covers the round trips that decide how a route feels,
 * and it works the same directly, via a DNS server's answer, or through a proxy — so the rows
 * of a comparison can be read against each other.
 */
object RouteProbe {

    enum class Failure(val label: String) {
        DNS("DNS lookup failed"), CONNECT("TCP connection failed"),
        PROXY("Proxy connection failed"), TLS("TLS handshake failed")
    }
    data class Result(val ms: Long? = null, val failure: Failure? = null)

    private val tls = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory

    /**
     * Milliseconds to [host]:[port] with TLS done, or null. [ip] pins the address (a DNS server's
     * answer); [socksPort] sends it through a local SOCKS5 proxy (an Xray config) instead.
     */
    fun handshakeMs(host: String, port: Int = 443, ip: String? = null, socksPort: Int? = null, timeoutMs: Int = 3500): Long? =
        probe(host, port, ip?.let(::listOf), socksPort, timeoutMs).ms

    /** Try the resolver's addresses within one budget; a stale first answer need not fail a route. */
    fun probe(host: String, port: Int = 443, ips: List<String>? = null, socksPort: Int? = null, timeoutMs: Int = 3500): Result {
        val started = System.nanoTime()
        val targets = if (socksPort != null) listOf<InetAddress?>(null) else try {
            (ips?.flatMap { InetAddress.getAllByName(it).toList() }
                ?: InetAddress.getAllByName(host).toList()).distinct().take(4)
        } catch (_: Exception) { return Result(failure = Failure.DNS) }
        if (targets.isEmpty()) return Result(failure = Failure.DNS)
        fun remainingMs() = (timeoutMs - (System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        var failure = if (socksPort == null) Failure.CONNECT else Failure.PROXY
        for ((index, target) in targets.withIndex()) {
            if ((System.nanoTime() - started) / 1_000_000L >= timeoutMs) break
            var phase = if (socksPort == null) Failure.CONNECT else Failure.PROXY
            try {
                Socket().use { raw ->
                    raw.soTimeout = remainingMs()
                    if (socksPort != null) {
                        raw.connect(InetSocketAddress("127.0.0.1", socksPort), minOf(1000, remainingMs()))
                        socks5Connect(raw, host, port)
                    } else {
                        raw.connect(InetSocketAddress(target, port), (remainingMs() / (targets.size - index)).coerceAtLeast(1))
                    }
                    phase = Failure.TLS
                    (tls.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
                        ssl.soTimeout = remainingMs()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        }
                        ssl.startHandshake()
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N &&
                            !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                            throw SSLPeerUnverifiedException("TLS hostname verification failed")
                        }
                        return Result(ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1))
                    }
                }
            } catch (_: Exception) {
                // A TLS error is more useful than a later address refusing TCP.
                if (failure != Failure.TLS) failure = phase
            }
        }
        return Result(failure = failure)
    }

    /** Minimal SOCKS5 CONNECT with a domain name, so the proxy resolves it the way games would. */
    private fun socks5Connect(s: Socket, host: String, port: Int) {
        val out = s.getOutputStream()
        val input = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        val hello = input.readExactly(2)
        if (hello[0].toInt() != 5 || hello[1].toInt() != 0) error("SOCKS refused")
        val name = host.toByteArray(Charsets.UTF_8)
        require(name.size in 1..255)
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val head = input.readExactly(4)
        if (head[0].toInt() != 5 || head[1].toInt() != 0) error("SOCKS connect failed")
        when (head[3].toInt()) {
            1 -> input.readExactly(4 + 2)
            3 -> {
                val length = input.read()
                require(length >= 0) { "SOCKS reply closed" }
                input.readExactly(length + 2)
            }
            4 -> input.readExactly(16 + 2)
            else -> error("Invalid SOCKS address type")
        }
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(b, off, n - off)
            if (r < 0) error("closed")
            off += r
        }
        return b
    }

    /** A free local port for a SOCKS inbound. The tiny race with other apps is acceptable here. */
    fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
