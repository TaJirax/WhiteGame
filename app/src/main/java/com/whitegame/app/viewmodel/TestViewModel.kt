package com.whitegame.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.TunnelController
import com.whitegame.app.connection.TunnelStore
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.model.ProxyNode
import com.whitegame.app.network.CloudflareSpeedResult
import com.whitegame.app.network.CloudflareSpeedTest
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.network.SpeedStage
import com.whitegame.app.parser.ConfigParser
import com.whitegame.app.repository.ResultsRepository
import com.whitegame.app.repository.SavedProxySnapshot
import com.whitegame.app.xray.XrayBridge
import com.whitegame.app.xray.XrayConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToLong

@HiltViewModel
class TestViewModel @Inject constructor(
    tunnelStore: TunnelStore,
    private val tunnel: TunnelController,
    @ApplicationContext private val context: Context,
    private val resultsRepo: ResultsRepository
) : ViewModel() {

    val savedConfigs = tunnelStore.tunnels

    var text by mutableStateOf("")
        private set
    var nodes by mutableStateOf<List<ProxyNode>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var savedSnapshots by mutableStateOf<List<SavedProxySnapshot>>(emptyList())
        private set
    var saveMessage by mutableStateOf("")
        private set

    private var job: Job? = null

    init { refreshHistory() }

    fun refreshHistory() {
        savedSnapshots = resultsRepo.listProxySnapshots()
    }

    fun onTextChange(value: String) {
        if (busy) return
        text = value
        nodes = emptyList()
        saveMessage = ""
        status = ""
    }

    fun stop() {
        if (!busy) return
        status = "Stopping…"
        job?.cancel()
    }

    fun startTest() {
        if (busy) return
        if (text.isBlank()) {
            status = "Paste a config or load a saved tunnel."
            return
        }
        if (tunnel.state.value.isLive) {
            status = "Disconnect the active WhiteGame tunnel before testing configs."
            return
        }
        val input = text.trim()
        val savedNames = savedConfigs.value.associate { it.confText.trim() to it.name }
        nodes = emptyList()
        saveMessage = ""
        busy = true
        job = viewModelScope.launch {
            var tunnelStarted = false
            val run = coroutineContext.job
            val watcher = launch {
                tunnel.state.first { it.isLive }
                tunnelStarted = true
                run.cancel()
            }
            try {
                val blocks = when {
                    input.startsWith("{") -> listOf(input)
                    hasWireGuardInterface(input) ->
                        TunnelStore.splitConfigs(input) { it.startsWith("tuic://", ignoreCase = true) }
                    else -> input.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                }
                val output = mutableListOf<ProxyNode>()
                for ((i, block) in blocks.withIndex()) {
                    ensureActive()
                    val position = "${i + 1}/${blocks.size}"
                    status = "Preparing config $position…"
                    val results = testConfig(block, position, savedNames[block.trim()])
                    ensureActive()
                    results.forEach { output += it.copy(index = output.size + 1) }
                    nodes = output.toList()
                }
                status = if (output.isEmpty()) "No supported config found." else "Finished · ${output.size} results"
            } catch (e: CancellationException) {
                status = if (tunnelStarted) "Test stopped because a WhiteGame tunnel started." else "Test stopped."
                throw e
            } catch (_: Exception) {
                status = "Config test could not finish."
            } finally {
                watcher.cancel()
                busy = false
            }
        }
    }

    private suspend fun testConfig(block: String, position: String, savedName: String?): List<ProxyNode> {
        val xray = XrayConfig.looksLikeXray(block)
        val wireGuard = !xray && (AwgConfig.isShareLink(block) || hasWireGuardInterface(block))
        if (xray || wireGuard) {
            var node = ProxyNode(
                0, if (wireGuard) "WireGuard" else "Xray", "", 0,
                savedName ?: "Config ${position.substringBefore('/')}", "", "", "", isConfigTest = true
            )
            try {
                val route = if (wireGuard) {
                    val config = AwgConfig.parse(block)
                    val endpoint = config.primaryEndpoint?.let { AwgConfig.splitHostPort(it) }
                    node = node.copy(
                        protocol = config.kind.label,
                        name = savedName ?: AwgConfig.shareLinkName(block) ?: config.kind.label,
                        host = endpoint?.first.orEmpty(),
                        port = endpoint?.second ?: 0,
                        network = "udp"
                    )
                    WireGuardProbe.forSpeedTest(config)
                } else {
                    val config = XrayConfig.parse(block)
                    node = node.copy(
                        protocol = config.protocol.uppercase(), name = savedName ?: config.name,
                        host = config.host, port = config.port, security = config.security, network = config.network
                    )
                    config
                }
                return listOf(measure(node, route, position))
            } catch (e: ConfigException) {
                // Parser errors can contain input fragments. Show specific messages only after parsing.
                val detail = if (node.network.isEmpty()) "Check the config for missing or invalid fields."
                    else e.message ?: "This config could not be tested."
                return listOf(node.withReport(detail))
            }
        }

        return ConfigParser.parseAll(block).map { node ->
            status = "Checking endpoint $position…"
            val result = withContext(Dispatchers.IO) { NetworkProber.probeTcp(node.host, node.port, 3, 2500) }
            node.copy(
                rawPreview = "", tcpMs = result.avgMs, tcpLoss = result.lossPct,
                tcpJitter = result.jitterMs, reachable = result.avgMs != null
            ).withReport("TCP endpoint check only. Speed tests require a supported tunnel config.")
        }
    }

    private suspend fun measure(
        node: ProxyNode,
        route: XrayConfig.Parsed,
        position: String
    ): ProxyNode {
        var result = CloudflareSpeedResult()
        val detail = try {
            val finished = XrayBridge.withTestSocks(context, route) { port ->
                CloudflareSpeedTest(socksPort = port).use { client ->
                    withTimeoutOrNull(35_000) {
                        client.measure { stage, partial ->
                            result = partial
                            val metric = when (stage) {
                                SpeedStage.LATENCY -> "ping"
                                SpeedStage.DOWNLOAD -> "download speed"
                                SpeedStage.UPLOAD -> "upload speed"
                            }
                            status = "Measuring $metric · config $position"
                        }
                    }
                }
            }
            if (finished != null) result = finished
            when {
                finished == null -> "Cloudflare test timed out."
                result.downloadMbps == null || result.uploadMbps == null -> "Cloudflare returned a partial result."
                else -> "Measured through this config using Cloudflare HTTPS."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Could not reach Cloudflare through this config. Check the tunnel endpoint and network."
        } catch (e: IllegalStateException) {
            e.message ?: "Could not start the config test."
        }
        val ping = result.latencyMs?.roundToLong()
        val metrics = buildList {
            ping?.let { add("Ping: $it ms") }
            result.downloadMbps?.let { add("Download: ${"%.2f".format(it)} Mbps") }
            result.uploadMbps?.let { add("Upload: ${"%.2f".format(it)} Mbps") }
        }
        return node.copy(
            tlsMs = ping, tcpMs = ping, tcpJitter = result.jitterMs?.roundToLong(),
            downloadMbps = result.downloadMbps, uploadMbps = result.uploadMbps,
            reachable = ping != null
        ).withReport(detail, metrics)
    }

    private fun ProxyNode.withReport(message: String, metrics: List<String> = emptyList()): ProxyNode = copy(
        dropAdvice = message,
        detail = message,
        caption = (listOf("$name · $protocol") + metrics + message).joinToString("\n")
    )

    private fun hasWireGuardInterface(text: String): Boolean =
        text.lineSequence().any { it.trim().equals("[Interface]", ignoreCase = true) }

    fun saveCurrent(title: String = "") {
        if (busy) return
        if (nodes.isEmpty() || nodes.none { it.reachable != null }) {
            saveMessage = "Nothing to save."
            return
        }
        resultsRepo.saveProxy(title, nodes)
        refreshHistory()
        saveMessage = "Saved ${nodes.size} results."
    }

    fun deleteSnapshot(id: String) {
        resultsRepo.deleteProxy(id)
        refreshHistory()
    }

    fun loadSnapshot(id: String) {
        if (busy) return
        val snap = resultsRepo.loadProxy(id) ?: return
        nodes = snap.nodes
        status = "Loaded ${snap.title}"
    }
}
