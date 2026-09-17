package com.whitegame.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.data.AssetLoader
import com.whitegame.app.model.ProxyNode
import com.whitegame.app.model.TcpProbeResult
import com.whitegame.app.model.UdpProbeResult
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.network.RegionAnalyzer
import com.whitegame.app.parser.ConfigParser
import com.whitegame.app.repository.ResultsRepository
import com.whitegame.app.repository.SavedProxySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TestViewModel @Inject constructor(
    private val assetLoader: AssetLoader,
    private val tunnelStore: com.whitegame.app.connection.TunnelStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val resultsRepo: ResultsRepository
) : ViewModel() {

    val savedConfigs = tunnelStore.tunnels

    var text by mutableStateOf("")
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
    var inputMode by mutableStateOf(0) // 0=paste links, 1=WG file, 2=WARP file

    private var job: Job? = null
    @Volatile private var stopFlag = false
    private val gameHosts by lazy {
        assetLoader.loadGames().flatMap { it.hosts }.distinct()
    }

    init { refreshHistory() }

    fun refreshHistory() {
        savedSnapshots = resultsRepo.listProxySnapshots()
    }

    fun onTextChange(v: String) {
        text = v
        nodes = emptyList()
    }

    fun setMode(m: Int) {
        inputMode = m
    }

    fun stop() {
        stopFlag = true
        job?.cancel()
        busy = false
        status = "stopped"
    }

    fun startTest() {
        if (busy) return
        if (text.isBlank()) {
            status = "paste config / WireGuard / WARP text first"
            return
        }
        stopFlag = false
        nodes = emptyList()
        busy = true
        job = viewModelScope.launch {
            try {
                val input = text.trim()
                val blocks = if (input.startsWith("{") || input.contains("[Interface]", true)) listOf(input)
                    else input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val output = mutableListOf<ProxyNode>()
                for ((index, block) in blocks.withIndex()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    status = "Testing ${index + 1}/${blocks.size}"
                    if (com.whitegame.app.xray.XrayConfig.looksLikeXray(block)) {
                        val parsed = com.whitegame.app.xray.XrayConfig.parse(block)
                        val samples = mutableListOf<Long?>()
                        var reason = ""
                        repeat(5) {
                            val result = com.whitegame.app.xray.XrayBridge.delay(context, parsed)
                            samples += result.getOrNull()
                            result.exceptionOrNull()?.message?.let { reason = it }
                        }
                        val measurements = com.whitegame.app.network.ProbeStats.from(samples)
                        val good = samples.filterNotNull()
                        val loss = samples.count { it == null } * 100 / samples.size
                        val avg = good.takeIf { it.isNotEmpty() }?.average()?.toLong()
                        val advice = if (avg == null) "Xray connection failed: $reason"
                            else "Authenticated HTTPS through this config | $loss% failed attempts. UDP was not tested."
                        output += ProxyNode(index + 1, parsed.protocol.uppercase(), parsed.host, parsed.port,
                            parsed.name, parsed.security, parsed.network, "",
                            reachable = avg != null, tcpMs = avg, tlsMs = avg, tcpLoss = loss, tcpJitter = measurements.jitterMs,
                            udpStatus = "Not tested", dropAdvice = advice,
                            detail = advice, caption = advice,
                            stabilityScore = com.whitegame.app.data.DnsData.scoreDns(avg, loss, measurements.jitterMs).first, dropRisk = if (loss == 0) "Low" else if (loss < 100) "High" else "Critical")
                    } else {
                        val parsed = ConfigParser.parseAll(block)
                        for (node in parsed) {
                            val result = withContext(Dispatchers.IO) { NetworkProber.probeTcp(node.host, node.port, 3, 2500) }
                            val advice = "Endpoint check only; connect this config to verify authentication and game traffic. UDP silence does not prove failure."
                            output += node.copy(index = output.size + 1, tcpMs = result.avgMs,
                                tcpLoss = result.lossPct, tcpJitter = result.jitterMs,
                                reachable = result.avgMs != null, udpStatus = "Not tested",
                                dropAdvice = advice, caption = advice, detail = advice)
                        }
                    }
                    nodes = output.toList()
                }
                status = if (output.isEmpty()) "No supported config found" else "Finished | ${output.size} configs tested"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Test failed: " + (e.message ?: "invalid config")
            } finally {
                busy = false
            }
        }
    }

    fun saveCurrent(title: String = "") {
        if (nodes.isEmpty() || nodes.none { it.reachable != null }) {
            saveMessage = "nothing to save"
            return
        }
        resultsRepo.saveProxy(title, nodes)
        refreshHistory()
        saveMessage = "saved (${nodes.size} nodes)"
    }

    fun deleteSnapshot(id: String) {
        resultsRepo.deleteProxy(id)
        refreshHistory()
    }

    fun loadSnapshot(id: String) {
        val snap = resultsRepo.loadProxy(id) ?: return
        nodes = snap.nodes
        status = "loaded: ${snap.title}"
    }

    private fun scoreNode(
        tcp: TcpProbeResult?, tls: Long?, resolve: Long?, proto: String,
        udp: UdpProbeResult?, tcp443: TcpProbeResult?, stun: UdpProbeResult?
    ): Pair<String, String> {
        val primary = tls ?: tcp?.avgMs
        if (primary == null && (udp == null || udp.status == "FAIL"))
            return "Not suitable" to "Host unreachable."
        if (udp?.status == "BLOCKED" && (stun?.status == "BLOCKED" || stun?.status == "FAIL"))
            return "Not suitable for games" to "UDP blocked."
        var score = 100
        if (primary != null) {
            score -= when {
                primary > 200 -> 40; primary > 120 -> 25; primary > 80 -> 12; primary > 50 -> 5; else -> 0
            }
        }
        tcp?.let {
            score -= it.lossPct / 3
            score -= ((it.jitterMs ?: 0) / 4).toInt()
        }
        when (udp?.status) {
            "BLOCKED" -> score -= 35
            "FAIL" -> score -= 20
            "NO_REPLY" -> score -= 4
            "REPLIES" -> udp.avgMs?.let { u ->
                score -= when { u > 150 -> 18; u > 100 -> 10; u > 70 -> 5; else -> 0 }
            }
        }
        when (stun?.status) {
            "REPLIES" -> score += 5
            "BLOCKED", "FAIL" -> score -= 20
        }
        score = score.coerceIn(0, 100)
        val label = when {
            score >= 80 -> "Excellent for games"
            score >= 65 -> "Good for games"
            score >= 45 -> "OK for casual"
            score >= 25 -> "Poor for competitive"
            else -> "Not suitable for games"
        }
        return label to "Score $score/100 · UDP ${udp?.status ?: "—"} · STUN ${stun?.status ?: "—"}"
    }
}
