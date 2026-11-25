package com.whitegame.app.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class CloudflareSpeedResult(
    val latencyMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null
)

enum class SpeedStage { LATENCY, DOWNLOAD, UPLOAD }

/** Native client for Cloudflare's public Speed Test measurement endpoints. */
internal class CloudflareSpeedTest(
    private val origin: String = "https://speed.cloudflare.com",
    socksPort: Int? = null
) : Closeable {
    private class Timing {
        var requestStart = 0L
        var headersReceived = 0L
    }

    private val client = OkHttpClient.Builder()
        .apply { socksPort?.let { proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it))) } }
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val timing = checkNotNull(request.tag(Timing::class.java))
            // The connection is established before this interceptor runs.
            timing.requestStart = System.nanoTime()
            chain.proceed(request).also { timing.headersReceived = System.nanoTime() }
        }
        .build()

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** A single HTTPS request with no payload. Includes setup when the connection is cold. */
    suspend fun pingWithSetup(): Long {
        val start = System.nanoTime()
        latency()
        return elapsedMs(start, System.nanoTime()).toLong().coerceAtLeast(1)
    }

    suspend fun measure(onProgress: (SpeedStage, CloudflareSpeedResult) -> Unit): CloudflareSpeedResult {
        var result = CloudflareSpeedResult()
        onProgress(SpeedStage.LATENCY, result)
        // The first request warms the connection and still serves as a reachability check.
        val firstSample = latency()
        val samples = List(5) {
            try {
                latency()
            } catch (_: IOException) {
                null
            }
        }
        val sorted = samples.filterNotNull().ifEmpty { listOf(firstSample) }.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val differences = samples.zipWithNext().mapNotNull { (a, b) ->
            if (a != null && b != null) abs(a - b) else null
        }
        result = result.copy(
            latencyMs = median,
            jitterMs = differences.takeIf { it.isNotEmpty() }?.average()
        )
        onProgress(SpeedStage.DOWNLOAD, result)
        result = result.copy(downloadMbps = bandwidth(downloadSizes, ::download))
        onProgress(SpeedStage.UPLOAD, result)
        return result.copy(uploadMbps = bandwidth(uploadSizes, ::upload))
    }

    private suspend fun latency(): Double = request("/__down?bytes=0") { response, timing ->
        val roundTrip = elapsedMs(timing.requestStart, timing.headersReceived)
        (roundTrip - serverTimeMs(response)).coerceAtLeast(0.0)
    }

    private suspend fun download(size: Int): Double = request("/__down?bytes=$size") { response, timing ->
        val buffer = ByteArray(32 * 1024)
        val body = response.body ?: throw IOException("Cloudflare returned no download data")
        val contentLength = body.contentLength()
        if (contentLength >= 0 && contentLength != size.toLong()) {
            throw IOException("Cloudflare returned an incorrect download size")
        }
        val input = body.byteStream()
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read == -1) throw IOException("Cloudflare returned an incomplete download")
            remaining -= read
        }
        val finished = System.nanoTime()
        val serverTime = serverTimeMs(response).coerceAtMost(elapsedMs(timing.requestStart, timing.headersReceived))
        elapsedMs(timing.requestStart, finished) - serverTime
    }

    private suspend fun upload(size: Int): Double = request(
        path = "/__up?bytes=$size",
        body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size.toLong()
            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(32 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size)
                    sink.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    ) { response, timing ->
        response.header("cf-meta-upload-bytes")?.let {
            if (it.toLongOrNull() != size.toLong()) throw IOException("Cloudflare received an incomplete upload")
        }
        elapsedMs(timing.requestStart, timing.headersReceived)
    }

    private suspend fun bandwidth(sizes: List<Int>, transfer: suspend (Int) -> Double): Double? {
        var result: Double? = null
        for (size in sizes) {
            val millis = try {
                transfer(size)
            } catch (_: IOException) {
                break
            }
            if (millis <= 0) continue
            result = size * 8 / (millis * 1_000)
            if (millis >= 1_000) break
        }
        return result
    }

    private suspend fun <T> request(
        path: String,
        body: RequestBody? = null,
        read: (Response, Timing) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val timing = Timing()
        val request = Request.Builder()
            .url(origin + path)
            .tag(Timing::class.java, timing)
            .header("Cache-Control", "no-cache, no-store")
            .header("Accept-Encoding", "identity")
            .method(if (body == null) "GET" else "POST", body)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Cloudflare returned HTTP ${it.code}")
                        read(it, timing)
                    }
                })
            }
        })
    }

    companion object {
        private val downloadSizes = listOf(100_000, 1_000_000, 10_000_000)
        private val uploadSizes = listOf(100_000, 1_000_000, 5_000_000)
        private val requestDuration = Regex("""(?:^|,\s*)cfReq(?:uest)?Dur(?:ation)?;\s*dur=([\d.]+)""", RegexOption.IGNORE_CASE)
        private val speedTiming = Regex("""(?:^|,\s*)cfSpeed[A-Z]*;\s*dur=([\d.]+)""", RegexOption.IGNORE_CASE)

        private fun elapsedMs(start: Long, end: Long): Double = (end - start) / 1_000_000.0

        private fun serverTimeMs(response: Response): Double {
            val timings = response.headers("Server-Timing").joinToString(",")
            val total = requestDuration.find(timings)?.groupValues?.get(1)?.toDoubleOrNull()
            if (total != null && total.isFinite() && total > 0) return total
            return speedTiming.findAll(timings).sumOf {
                it.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0
            }
        }
    }
}
