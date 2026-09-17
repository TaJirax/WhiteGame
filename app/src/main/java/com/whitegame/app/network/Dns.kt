package com.whitegame.app.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Real DNS over UDP. Pinging a resolver's IP says nothing about how fast it answers — many
 * block ICMP and still resolve in 20ms — so every DNS measurement in the app sends a query.
 */
object DnsClient {

    data class Answer(val ms: Long, val ips: List<String>)

    /** One A lookup of [host] against [server]; null when it does not answer in time. */
    fun query(server: String, host: String, timeoutMs: Int = 2000, protect: (DatagramSocket) -> Unit = {}): Answer? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = timeoutMs
                val id = (Math.random() * 65535).toInt()
                val q = DnsPacket.buildQuery(id, host)
                val t0 = System.nanoTime()
                socket.connect(InetSocketAddress(InetAddress.getByName(server), 53))
                socket.send(DatagramPacket(q, q.size))
                val buf = ByteArray(1500)
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val bytes = buf.copyOf(p.length)
                    // Late answers to an earlier query on a reused port are not ours.
                    if (DnsPacket.id(bytes) != id) continue
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
                    return Answer(ms, DnsPacket.parseARecords(bytes))
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Byte-level helpers: DNS messages, and the IPv4/UDP framing the DNS-only VPN reads and writes. */
object DnsPacket {

    fun id(msg: ByteArray): Int = if (msg.size < 2) -1 else ((msg[0].toInt() and 0xff) shl 8) or (msg[1].toInt() and 0xff)

    fun buildQuery(id: Int, host: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(0x01); out.write(0x00)          // recursion desired
        out.write(0); out.write(1)                 // one question
        repeat(6) { out.write(0) }                 // no answers / authority / additional
        host.trimEnd('.').split('.').forEach { label ->
            val b = label.toByteArray()
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1)                 // A
        out.write(0); out.write(1)                 // IN
        return out.toByteArray()
    }

    /** IPv4 addresses from the answer section. Tolerates compression pointers and junk. */
    fun parseARecords(msg: ByteArray): List<String> {
        if (msg.size < 12) return emptyList()
        fun u16(i: Int) = ((msg[i].toInt() and 0xff) shl 8) or (msg[i + 1].toInt() and 0xff)
        val qd = u16(4)
        val an = u16(6)
        var i = 12
        fun skipName() {
            while (i < msg.size) {
                val len = msg[i].toInt() and 0xff
                if (len == 0) { i += 1; return }
                if (len and 0xC0 == 0xC0) { i += 2; return }
                i += len + 1
            }
        }
        repeat(qd) { skipName(); i += 4 }
        val ips = mutableListOf<String>()
        repeat(an) {
            if (i >= msg.size) return ips
            skipName()
            if (i + 10 > msg.size) return ips
            val type = u16(i)
            val len = u16(i + 8)
            i += 10
            if (type == 1 && len == 4 && i + 4 <= msg.size) {
                ips += (0 until 4).joinToString(".") { (msg[i + it].toInt() and 0xff).toString() }
            }
            i += len
        }
        return ips
    }

    /** A UDP datagram read from the TUN device. */
    class UdpPacket(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    /** IPv4 + UDP only; anything else (TCP, IPv6, fragments) is not ours to answer. */
    fun readUdp4(buf: ByteArray, length: Int): UdpPacket? {
        if (length < 28) return null
        val b0 = buf[0].toInt() and 0xff
        if (b0 shr 4 != 4) return null
        val ihl = (b0 and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (buf[9].toInt() != 17) return null
        val fragment = ((buf[6].toInt() and 0x3f) shl 8) or (buf[7].toInt() and 0xff)
        if (fragment != 0 || (buf[6].toInt() and 0x20) != 0) return null
        val total = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
        val end = minOf(total, length)
        fun u16(i: Int) = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
        val udpLen = u16(ihl + 4)
        val payloadEnd = minOf(end, ihl + udpLen)
        if (payloadEnd < ihl + 8) return null
        return UdpPacket(
            buf.copyOfRange(12, 16), buf.copyOfRange(16, 20),
            u16(ihl), u16(ihl + 2),
            buf.copyOfRange(ihl + 8, payloadEnd)
        )
    }

    /** IPv4/UDP datagram from [src]:[srcPort] to [dst]:[dstPort]. UDP checksum 0 is legal on IPv4. */
    fun buildUdp4(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40                                   // don't fragment
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = (srcPort shr 8).toByte(); p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i + 1 < off + len) {
            sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < off + len) sum += (b[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }
}
