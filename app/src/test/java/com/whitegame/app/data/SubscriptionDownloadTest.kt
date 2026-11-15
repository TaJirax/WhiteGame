package com.whitegame.app.data

import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDownloadTest {
    private data class Reply(val status: Int = 200, val body: String = "", val location: String? = null)

    /** Tiny real HTTP fixture using only APIs available in Android's compile classpath. */
    private class Server(private val routes: Map<String, Reply>) : Closeable {
        private val listener = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val accept = AtomicReference<String>()
        val url = "http://127.0.0.1:${listener.localPort}"
        private val worker = Thread {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val target = input.readLine().split(' ')[1]
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Accept:", true)) accept.set(header.substringAfter(':').trim())
                    }
                    val reply = routes[target] ?: Reply(404)
                    val body = reply.body.toByteArray(Charsets.UTF_8)
                    val headers = buildString {
                        append("HTTP/1.1 ${reply.status} Response\r\n")
                        append("Content-Length: ${body.size}\r\nConnection: close\r\n")
                        reply.location?.let { append("Location: $it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                }
            } catch (error: Exception) {
                if (!listener.isClosed) failure.set(error)
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            listener.close()
            worker.join(1000)
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }

    @Test fun `loader follows redirects and decodes subscription response`() {
        val encoded = java.util.Base64.getEncoder().encodeToString("vless://id@edge.example.com:443#Server".toByteArray())
        Server(mapOf("/redirect" to Reply(302, location = "/list"), "/list" to Reply(body = encoded))).use { server ->
            val nodes = ConfigSubscription.download(server.url + "/redirect")
            assertTrue(server.accept.get().contains("text/plain"))
            assertEquals("Server", nodes.single().name)
            assertTrue(nodes.single().conf.startsWith("vless://"))
        }
    }

    @Test fun `loader reports the panel error rather than an empty list`() {
        Server(mapOf("/error" to Reply(403, "{\"message\":\"Subscription expired\"}"))).use { server ->
            val error = runCatching { ConfigSubscription.download(server.url + "/error") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("403"))
            assertTrue(error?.message.orEmpty().contains("Subscription expired"))
        }
    }
}
