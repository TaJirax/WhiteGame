package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.data.DnsData
import com.whitegame.app.data.SubscriptionScan
import com.whitegame.app.model.DnsItem
import com.whitegame.app.model.SubNode
import com.whitegame.app.network.CloudflareSpeedResult
import com.whitegame.app.network.CloudflareSpeedTest
import com.whitegame.app.network.DnsClient
import com.whitegame.app.network.GameComparison
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.network.ProbeStats
import com.whitegame.app.network.RouteProbe
import com.whitegame.app.network.SpeedStage
import com.whitegame.app.xray.XrayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class EndpointTest(val endpoint: String, val ms: Long?, val error: String? = null)

internal sealed interface ProbeRoute {
    val label: String

    data object Current : ProbeRoute {
        override val label = "Current route"
    }

    data class Resolver(val ip: String, override val label: String = "DNS $ip") : ProbeRoute
    data class Config(override val label: String, val text: String) : ProbeRoute
}

internal data class GameRouteTest(val label: String, val stats: ProbeStats, val error: String? = null)

internal data class GameTest(val host: String, val checked: Int, val routes: List<GameRouteTest>) {
    val recommendedRoute: String?
        get() = routes.filter { it.stats.latencyMs != null }
            .minByOrNull { DnsData.distanceScore(it.stats.latencyMs, it.stats.lossPct, it.stats.jitterMs) }
            ?.label
}

internal object DesktopProbes {
    private const val SPEED_TIMEOUT_MS = 35_000L
    private const val START_TIMEOUT_MS = 5_000L
    private const val GAME_ROUTE_SAMPLES = 3
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    suspend fun speed(onProgress: (SpeedStage, CloudflareSpeedResult) -> Unit = { _, _ -> }): CloudflareSpeedResult =
        measureSpeed(null, onProgress)

    suspend fun configSpeed(
        config: String,
        onProgress: (SpeedStage, CloudflareSpeedResult) -> Unit = { _, _ -> }
    ): CloudflareSpeedResult {
        val parsed = outbound(config)
        return withSpeedRoute(DesktopEngines.xray(), parsed) { port -> measureSpeed(port, onProgress) }
    }

    private suspend fun measureSpeed(
        socksPort: Int?,
        onProgress: (SpeedStage, CloudflareSpeedResult) -> Unit
    ): CloudflareSpeedResult {
        var partial = CloudflareSpeedResult()
        val finished = CloudflareSpeedTest(socksPort = socksPort).use { client ->
            withTimeoutOrNull(SPEED_TIMEOUT_MS) {
                client.measure { stage, result ->
                    partial = result
                    onProgress(stage, result)
                }
            }
        }
        if (finished == null && partial.latencyMs == null) throw IOException("Cloudflare speed test timed out")
        return finished ?: partial
    }

    suspend fun endpoints(
        config: String,
        endpoints: String,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<EndpointTest> {
        val profile = AwgConfig.parse(config)
        if (profile.iface.amnezia.isNotEmpty() || profile.peers.size != 1) {
            throw ConfigException("Endpoint scanning requires a single standard WireGuard peer")
        }
        val peer = profile.peers.single()
        val candidates = WireGuardProbe.endpoints(endpoints.ifBlank { peer.endpoint.orEmpty() })
        val engine = DesktopEngines.xray()
        return candidates.mapIndexed { index, endpoint ->
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, candidates.size, endpoint)
            val candidate = profile.copy(peers = listOf(peer.copy(endpoint = endpoint)))
            try {
                val parsed = WireGuardProbe.forSpeedTest(candidate)
                EndpointTest(endpoint, routedPing(engine, parsed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConfigException) {
                EndpointTest(endpoint, null, e.message)
            } catch (_: IOException) {
                EndpointTest(endpoint, null, "No Cloudflare HTTPS response through this endpoint")
            }
        }.sortedBy { it.ms ?: Long.MAX_VALUE }
    }

    suspend fun dns(
        resolvers: List<DnsItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<DnsItem> {
        val distinct = resolvers.distinctBy(DnsItem::ip)
        val attempts = if (distinct.size == 1) 5 else 3
        val results = mutableListOf<DnsItem>()
        for (batch in distinct.chunked(24)) {
            results += withContext(Dispatchers.IO) {
                batch.map { resolver ->
                    async {
                        val samples = List(attempts) {
                            ensureActive()
                            DnsClient.query(resolver.ip, "example.com", timeoutMs = 1_500)?.ms
                        }
                        val stats = ProbeStats.from(samples)
                        val (score, status) = DnsData.scoreDns(stats.latencyMs, stats.lossPct ?: 100, stats.jitterMs)
                        resolver.copy(
                            pingMs = stats.latencyMs, lossPct = stats.lossPct, jitterMs = stats.jitterMs,
                            status = status, score = score
                        )
                    }
                }.awaitAll()
            }
            onProgress(results.size, distinct.size)
        }
        return results.sortedBy { DnsData.distanceScore(it.pingMs, it.lossPct, it.jitterMs) }
    }

    /** Keep each route on the same reachable publisher HTTPS endpoint. */
    suspend fun game(
        hosts: List<String>,
        routes: List<ProbeRoute> = listOf(ProbeRoute.Current),
        onProgress: (GameTest) -> Unit = {}
    ): GameTest {
        val targets = hosts.map(String::trim).filter(String::isNotEmpty).distinct().take(4)
        require(targets.isNotEmpty()) { "This game has no HTTPS endpoints in the catalog" }
        val requested = (listOf(ProbeRoute.Current) + routes).distinct()
        val prepared = requested.map { route ->
            if (route !is ProbeRoute.Config) return@map PreparedRoute(route)
            try {
                PreparedRoute(route, outbound(route.text))
            } catch (e: ConfigException) {
                PreparedRoute(route, error = e.message ?: "Unsupported tunnel configuration")
            }
        }
        val proxies = prepared.mapIndexedNotNull { index, route -> route.outbound?.let { index to it } }
        if (proxies.isEmpty()) return measureGame(targets, prepared, emptyMap(), onProgress)

        return try {
            val engine = try {
                DesktopEngines.xray()
            } catch (_: Exception) {
                throw XrayStartupException("Install Xray to measure saved tunnel routes")
            }
            val ports = withContext(Dispatchers.IO) { allocatePorts(proxies.size) }
            val assignments = proxies.mapIndexed { index, (route, _) -> route to ports[index] }.toMap()
            val config = gameSocksConfig(proxies.mapIndexed { index, (_, parsed) -> ports[index] to parsed }, targets)
            withSocks(engine, config, ports) { measureGame(targets, prepared, assignments, onProgress) }
        } catch (e: XrayStartupException) {
            val withoutProxy = prepared.map {
                if (it.outbound == null) it else it.copy(error = e.message)
            }
            measureGame(targets, withoutProxy, emptyMap(), onProgress)
        }
    }

    private data class PreparedRoute(
        val route: ProbeRoute,
        val outbound: XrayConfig.Parsed? = null,
        val error: String? = null
    )

    private suspend fun measureGame(
        targets: List<String>,
        routes: List<PreparedRoute>,
        proxyPorts: Map<Int, Int>,
        onProgress: (GameTest) -> Unit
    ): GameTest {
        suspend fun sample(host: String) = withContext(Dispatchers.IO) {
            routes.mapIndexed { index, prepared ->
                async {
                    val route = prepared.route
                    when (route) {
                        ProbeRoute.Current -> RouteProbe.probe(host)
                        is ProbeRoute.Resolver -> {
                            val answer = DnsClient.query(route.ip, host)
                            if (answer == null) RouteProbe.Result(failure = RouteProbe.Failure.DNS)
                            else RouteProbe.probe(host, ips = answer.ips).let { it.copy(ms = it.ms?.plus(answer.ms)) }
                        }
                        is ProbeRoute.Config -> proxyPorts[index]?.let { RouteProbe.probe(host, socksPort = it) }
                            ?: RouteProbe.Result(failure = RouteProbe.Failure.PROXY)
                    }
                }
            }.awaitAll()
        }

        val selected = GameComparison.selectEndpoint(targets, ::sample)
        val samples = mutableListOf(selected.results)
        fun report() = GameTest(selected.host, selected.checked, routes.mapIndexed { index, route ->
            val measurements = samples.map { it[index] }
            val stats = ProbeStats.from(measurements.map { it.ms })
            val lastFailure = measurements.last().failure?.label.takeIf { stats.successful == 0 }
            GameRouteTest(route.route.label, stats, route.error ?: lastFailure)
        })
        onProgress(report())
        repeat(GAME_ROUTE_SAMPLES - 1) {
            currentCoroutineContext().ensureActive()
            samples += sample(selected.host)
            onProgress(report())
        }
        return report()
    }

    suspend fun rankSubscription(
        nodes: List<SubNode>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<SubNode> = SubscriptionScan.rank(nodes.mapIndexed { index, node ->
        currentCoroutineContext().ensureActive()
        onProgress(index + 1, nodes.size)
        when (SubscriptionScan.probeFor(node)) {
            SubscriptionScan.Probe.PROXY, SubscriptionScan.Probe.WIREGUARD -> {
                try {
                    val parsed = outbound(node.conf)
                    val delay = routedPing(DesktopEngines.xray(), parsed)
                    SubscriptionScan.proxyResult(node, delay, successLabel = "Cloudflare HTTPS")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    SubscriptionScan.proxyResult(node, null, e.message)
                } catch (_: Exception) {
                    SubscriptionScan.proxyResult(node, null, "No Cloudflare HTTPS response through this config")
                }
            }
            SubscriptionScan.Probe.TUNNEL -> node.copy(
                pingMs = null, lossPct = null, score = 0, status = SubscriptionScan.tunnelStatus(node)
            )
            SubscriptionScan.Probe.TCP -> {
                val result = NetworkProber.probeTcp(node.host, node.port, 3, 2_500)
                node.copy(
                    pingMs = result.avgMs, lossPct = result.lossPct,
                    score = DnsData.scoreDns(result.avgMs, result.lossPct, result.jitterMs).first,
                    status = if (result.avgMs != null) "TCP endpoint only" else "No TCP response"
                )
            }
        }
    })

    private fun outbound(text: String): XrayConfig.Parsed {
        if (XrayConfig.looksLikeXray(text)) {
            val parsed = try {
                XrayConfig.parse(text)
            } catch (_: ConfigException) {
                throw ConfigException("Check the Xray config for missing or invalid fields")
            }
            if (parsed.protocol.lowercase() in setOf("freedom", "blackhole", "dns", "loopback")) {
                throw ConfigException("This config does not contain a proxy outbound")
            }
            return parsed
        }
        val profile = try {
            AwgConfig.parse(text)
        } catch (_: ConfigException) {
            throw ConfigException("Check the WireGuard config for missing or invalid fields")
        }
        if (profile.iface.amnezia.isNotEmpty()) {
            throw ConfigException("Connect this AmneziaWG profile, then run Network → Speed through its internet route")
        }
        return WireGuardProbe.forSpeedTest(profile)
    }

    private suspend fun routedPing(engine: Path, parsed: XrayConfig.Parsed): Long =
        withSpeedRoute(engine, parsed) { port ->
            CloudflareSpeedTest(socksPort = port).use { it.pingWithSetup() }
        }

    private suspend fun <T> withSpeedRoute(
        engine: Path,
        parsed: XrayConfig.Parsed,
        test: suspend (Int) -> T
    ): T {
        val port = withContext(Dispatchers.IO) { allocatePorts(1).single() }
        return withSocks(engine, XrayConfig.speedSocksConfig(port, parsed), listOf(port)) { test(port) }
    }

    private fun gameSocksConfig(proxies: List<Pair<Int, XrayConfig.Parsed>>, hosts: List<String>): String {
        val json = JSONObject(XrayConfig.socksConfig(proxies))
        val original = json.getJSONArray("outbounds")
        val outbounds = JSONArray().put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        for (i in 0 until original.length()) outbounds.put(original.getJSONObject(i))
        json.put("outbounds", outbounds)
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            rules.getJSONObject(i).put("domain", JSONArray(hosts.map { "full:$it" }))
                .put("network", "tcp").put("port", 443)
        }
        return json.toString()
    }

    private fun allocatePorts(count: Int): List<Int> {
        val sockets = ArrayList<ServerSocket>(count)
        try {
            repeat(count) { sockets += ServerSocket(0, 10, loopback) }
            return sockets.map(ServerSocket::getLocalPort)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private class XrayStartupException(message: String) : IOException(message)

    private suspend fun <T> withSocks(
        engine: Path,
        config: String,
        ports: List<Int>,
        test: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(engine.toString(), "run", "-config", "stdin:", "-format", "json")
                .directory(engine.toAbsolutePath().parent.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    // A user's Xray defaults must never be merged into the isolated test route.
                    environment().keys.removeIf {
                        it.equals("xray.location.confdir", ignoreCase = true) ||
                            it.equals("XRAY_LOCATION_CONFDIR", ignoreCase = true)
                    }
                }.start()
        } catch (_: IOException) {
            throw XrayStartupException("Could not start Xray for this measurement")
        }
        try {
            // Xray reads credentials from a pipe, so no test config has to be written to disk.
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(config) }
            } catch (_: IOException) {
                throw XrayStartupException("Xray rejected the test configuration")
            }
            val ready = withTimeoutOrNull(START_TIMEOUT_MS) {
                while (process.isAlive && !ports.all(::canConnect)) delay(50)
                process.isAlive
            }
            if (ready != true) throw XrayStartupException("Xray could not open the local test route")
            test()
        } finally {
            withContext(NonCancellable) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(loopback, port), 100) }
        true
    } catch (_: IOException) {
        false
    }
}
