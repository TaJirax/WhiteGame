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

    private val tls = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory

    /**
     * Milliseconds to [host]:[port] with TLS done, or null. [ip] pins the address (a DNS server's
     * answer); [socksPort] sends it through a local SOCKS5 proxy (an Xray config) instead.
     */
    fun handshakeMs(host: String, port: Int = 443, ip: String? = null, socksPort: Int? = null, timeoutMs: Int = 3500): Long? {
        var raw: Socket? = null
        return try {
            val t0 = System.nanoTime()
            val target = if (socksPort == null) InetAddress.getByName(ip ?: host) else null
            raw = Socket().apply { soTimeout = timeoutMs }
            if (socksPort != null) {
                raw.connect(InetSocketAddress("127.0.0.1", socksPort), 1000)
                socks5Connect(raw, host, port)
            } else {
                raw.connect(InetSocketAddress(target, port), timeoutMs)
            }
            val ssl = tls.createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            ssl.startHandshake()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N &&
                !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                throw SSLPeerUnverifiedException("TLS hostname verification failed")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000L
            runCatching { ssl.close() }
            ms.coerceAtLeast(1)
        } catch (_: Exception) {
            runCatching { raw?.close() }
            null
        }
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
            3 -> input.readExactly((input.read() and 0xff) + 2)
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
