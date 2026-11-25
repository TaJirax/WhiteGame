package com.whitegame.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

class CloudflareSpeedTestTest {
    @Test
    fun rejectedSocksRouteNeverFallsBackToDirectRequests() = runBlocking {
        MockWebServer().use { origin ->
            origin.enqueue(MockResponse())
            ServerSocket().use { socks ->
                socks.bind(InetSocketAddress("127.0.0.1", 0))
                socks.soTimeout = 5_000
                val handshake = async(Dispatchers.IO) {
                    socks.accept().use { conn ->
                        conn.soTimeout = 2_000
                        val input = DataInputStream(conn.getInputStream())
                        val version = input.readUnsignedByte()
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        conn.getOutputStream().write(byteArrayOf(5, -1))
                        version
                    }
                }
                val failure = CloudflareSpeedTest(
                    origin.url("/").toString().removeSuffix("/"), socksPort = socks.localPort
                ).use { client -> runCatching { client.measure { _, _ -> } }.exceptionOrNull() }
                assertTrue(failure is IOException)
                assertEquals(5, handshake.await())
                assertEquals(0, origin.requestCount)
            }
        }
    }

    @Test
    fun measuresCloudflareEndpointsWithBoundedPayloads() = runBlocking {
        withSpeedServer { url, downloads, uploads ->
            val stages = mutableListOf<SpeedStage>()
            val result = CloudflareSpeedTest(url).measure { stage, _ -> stages += stage }

            assertEquals(listOf(SpeedStage.LATENCY, SpeedStage.DOWNLOAD, SpeedStage.UPLOAD), stages)
            assertEquals(6, downloads.count { it == 0 })
            assertTrue(downloads.any { it > 0 })
            assertTrue(downloads.sum() <= 11_100_000)
            assertTrue(uploads.isNotEmpty())
            assertTrue(uploads.sum() <= 6_100_000)
            assertTrue(result.latencyMs!! >= 0)
            assertTrue(result.jitterMs!! >= 0)
            assertTrue(result.downloadMbps!! > 0)
            assertTrue(result.uploadMbps!! > 0)
        }
    }

    @Test
    fun keepsOtherMeasurementsWhenDownloadSizeIsWrong() = runBlocking {
        withSpeedServer(truncateDownloads = true) { url, downloads, _ ->
            val result = CloudflareSpeedTest(url).measure { _, _ -> }

            assertEquals(listOf(100_000), downloads.filter { it > 0 })
            assertNull(result.downloadMbps)
            assertTrue(result.latencyMs!! >= 0)
            assertTrue(result.uploadMbps!! > 0)
        }
    }

    private suspend fun withSpeedServer(
        truncateDownloads: Boolean = false,
        test: suspend (String, Collection<Int>, Collection<Int>) -> Unit
    ) {
        val downloads = ConcurrentLinkedQueue<Int>()
        val uploads = ConcurrentLinkedQueue<Int>()
        MockWebServer().use { server ->
            server.bodyLimit = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                    val size = url.queryParameter("bytes")?.toIntOrNull()
                        ?: return MockResponse().setResponseCode(400)
                    return when {
                        url.encodedPath == "/__down" && request.method == "GET" -> {
                            downloads += size
                            val sent = if (truncateDownloads) size / 2 else size
                            MockResponse().setBody(Buffer().write(ByteArray(sent)))
                        }
                        url.encodedPath == "/__up" && request.method == "POST" -> {
                            val received = request.bodySize.toInt()
                            uploads += received
                            MockResponse()
                                .addHeader("cf-meta-upload-bytes", received)
                                .setResponseCode(if (size == received) 200 else 400)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            test(server.url("/").toString().removeSuffix("/"), downloads, uploads)
        }
    }
}
