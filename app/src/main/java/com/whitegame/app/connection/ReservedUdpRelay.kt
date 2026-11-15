package com.whitegame.app.connection

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WARP's Reserved extension is not implemented by the bundled AmneziaWG engine. This local
 * transport adapter sets those bytes on outgoing WireGuard messages, and removes them before
 * returning replies to the engine. Signature/junk packets pass through unchanged. The remote
 * socket MUST be protected before it connects, so it cannot route back into the VPN.
 */
class ReservedUdpRelay internal constructor(
    remote: InetSocketAddress,
    private val reserved: List<Int>,
    socketFactory: (SocketAddress?) -> DatagramSocket,
    protect: (DatagramSocket) -> Boolean
) : Closeable {
    constructor(remote: InetSocketAddress, reserved: List<Int>, protect: (DatagramSocket) -> Boolean) :
        this(remote, reserved, { DatagramSocket(it) }, protect)

    private val running = AtomicBoolean(false)
    private val local: DatagramSocket
    private val upstream: DatagramSocket
    @Volatile private var engineAddress: SocketAddress? = null

    val endpoint: String get() = "127.0.0.1:${local.localPort}"

    init {
        require(reserved.size == 3 && reserved.all { it in 0..255 }) { "Reserved must contain three bytes" }
        val localSocket = socketFactory(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        var remoteSocket: DatagramSocket? = null
        try {
            remoteSocket = socketFactory(null)
            if (!protect(remoteSocket)) throw ConfigException("Could not protect the WARP UDP socket")
            remoteSocket.bind(InetSocketAddress(0))
            remoteSocket.connect(remote)
            local = localSocket
            upstream = remoteSocket
        } catch (e: Exception) {
            localSocket.close()
            remoteSocket?.close()
            throw e
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker("warp-reserved-out") {
            val packet = DatagramPacket(ByteArray(65535), 65535)
            while (running.get()) {
                packet.length = packet.data.size
                local.receive(packet)
                if (!packet.address.isLoopbackAddress) continue
                // Pin to the first engine socket; a different local sender is not this peer.
                if (engineAddress == null) engineAddress = packet.socketAddress
                if (engineAddress != packet.socketAddress) continue
                applyOutgoing(packet.data, packet.length, reserved)
                upstream.send(DatagramPacket(packet.data, packet.length))
            }
        }
        worker("warp-reserved-in") {
            val packet = DatagramPacket(ByteArray(65535), 65535)
            while (running.get()) {
                packet.length = packet.data.size
                upstream.receive(packet)
                val engine = engineAddress ?: continue
                clearIncoming(packet.data, packet.length)
                local.send(DatagramPacket(packet.data, packet.length, engine))
            }
        }
    }

    private fun worker(name: String, block: () -> Unit) {
        Thread({
            try {
                while (running.get()) {
                    try { block(); break }
                    catch (_: PortUnreachableException) {
                        // A single ICMP failure must not stop WireGuard's later UDP retries.
                    }
                }
            } catch (_: SocketException) {
                // Closing both sockets interrupts blocked receives on disconnect.
            } catch (_: Exception) {
                // A failed adapter must not keep the other worker/socket alive.
            } finally {
                close()
            }
        }, name).apply { isDaemon = true; start() }
    }

    override fun close() {
        running.set(false)
        local.close()
        upstream.close()
    }

    companion object {
        private fun isWireGuard(data: ByteArray, length: Int): Boolean {
            if (length < 4) return false
            return when (data[0].toInt() and 0xff) {
                1 -> length == 148
                2 -> length == 92
                3 -> length == 64
                4 -> length >= 32
                else -> false
            }
        }

        internal fun applyOutgoing(data: ByteArray, length: Int, reserved: List<Int>) {
            if (!isWireGuard(data, length) || (1..3).any { data[it] != 0.toByte() }) return
            reserved.forEachIndexed { i, byte -> data[i + 1] = byte.toByte() }
        }

        internal fun clearIncoming(data: ByteArray, length: Int) {
            if (isWireGuard(data, length)) for (i in 1..3) data[i] = 0
        }
    }
}
