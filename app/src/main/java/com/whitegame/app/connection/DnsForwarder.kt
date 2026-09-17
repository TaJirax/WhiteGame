package com.whitegame.app.connection

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.whitegame.app.network.DnsPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes a DNS server apply to the whole phone without a tunnel.
 *
 * Android has no API for an app to set system DNS, but every app resolves through the active
 * VPN's DNS server. So the DNS-only VPN announces [VIRTUAL_DNS], routes nothing except that
 * one address, and relays each query to the chosen resolver from a protected socket. All other
 * traffic never enters the TUN device and keeps its normal path and speed.
 *
 * Relays Android UDP queries, with upstream TCP fallback for truncated answers.
 */
class DnsForwarder(
    private val service: VpnService,
    private val tun: ParcelFileDescriptor,
    private val upstream: List<String>
) {
    private val running = AtomicBoolean(true)
    private val pool = Executors.newFixedThreadPool(6)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val virtualDns = InetAddress.getByName(VIRTUAL_DNS).address

    private val reader = Thread({
        val input = FileInputStream(tun.fileDescriptor)
        val buf = ByteArray(32767)
        val poll = StructPollfd().apply {
            fd = tun.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        while (running.get()) {
            try {
                // Poll with a timeout so stop() is noticed even when no query arrives.
                if (Os.poll(arrayOf(poll), 250) <= 0) continue
                val n = input.read(buf)
                if (n <= 0) continue
                val packet = DnsPacket.readUdp4(buf, n) ?: continue
                if (packet.dstPort != 53) continue
                pool.execute { relay(packet) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun read stopped: " + e.message)
                break
            }
        }
    }, "dns-forwarder")

    fun start() = reader.start()

    private fun relay(query: DnsPacket.UdpPacket) {
        for (server in upstream) {
            try {
                DatagramSocket().use { socket ->
                    check(service.protect(socket)) { "Could not protect DNS socket" }
                    socket.soTimeout = 3000
                    socket.connect(InetSocketAddress(InetAddress.getByName(server), 53))
                    socket.send(DatagramPacket(query.payload, query.payload.size))
                    val buf = ByteArray(4096)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    var answer = buf.copyOf(reply.length)
                    check(DnsPacket.id(answer) == DnsPacket.id(query.payload))
                    if (answer.size >= 12 && answer[2].toInt() and 2 != 0) {
                        answer = java.net.Socket().use { tcp ->
                            check(service.protect(tcp))
                            tcp.soTimeout = 3000
                            tcp.connect(InetSocketAddress(server, 53), 3000)
                            val out = java.io.DataOutputStream(tcp.getOutputStream())
                            out.writeShort(query.payload.size)
                            out.write(query.payload)
                            out.flush()
                            val input = java.io.DataInputStream(tcp.getInputStream())
                            val length = input.readUnsignedShort()
                            check(length in 12..32739)
                            ByteArray(length).also { input.readFully(it) }
                        }
                        check(DnsPacket.id(answer) == DnsPacket.id(query.payload))
                    }
                    if (!running.get()) return
                    val packet = DnsPacket.buildUdp4(virtualDns, 53, query.srcIp, query.srcPort, answer)
                    synchronized(output) { output.write(packet) }
                }
                return
            } catch (_: Exception) {
                // Next server in the list, if the user gave more than one.
            }
        }
    }

    fun stop() {
        running.set(false)
        pool.shutdownNow()
        runCatching { reader.join(600) }
        runCatching { tun.close() }
    }

    companion object {
        private const val TAG = "DnsForwarder"
        const val VIRTUAL_ADDRESS = "10.111.222.1"
        const val VIRTUAL_DNS = "10.111.222.2"
    }
}
