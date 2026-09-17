package com.whitegame.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.data.AssetLoader
import com.whitegame.app.data.RemoteCatalog
import com.whitegame.app.data.DnsData
import com.whitegame.app.model.DnsItem
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.repository.ResultsRepository
import com.whitegame.app.repository.SavedDnsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DnsViewModel @Inject constructor(
    private val assetLoader: AssetLoader,
    private val remoteCatalog: RemoteCatalog,
    private val resultsRepo: ResultsRepository
) : ViewModel() {

    var rows by mutableStateOf<List<DnsItem>>(emptyList())
        private set
    var nearest by mutableStateOf<List<DnsItem>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var savedSnapshots by mutableStateOf<List<SavedDnsSnapshot>>(emptyList())
        private set
    var saveMessage by mutableStateOf("")
        private set
    var updateMessage by mutableStateOf("")
        private set

    private var scanJob: Job? = null
    @Volatile private var stopFlag = false

    init {
        reloadFromAssets()
        refreshHistory()
    }

    fun reloadFromAssets() {
        rows = assetLoader.loadDns().map { it.copy() }
        updateMessage = "DNS list loaded · ${rows.size} entries"
    }

    var updating by mutableStateOf(false)
        private set

    fun updateDnsList() {
        if (updating) return
        updating = true
        updateMessage = "Downloading DNS catalog…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { remoteCatalog.updateDns() }
            if (result.ok) {
                reloadFromAssets()
                nearest = emptyList()
                updateMessage = result.message + if (result.version.isNotBlank()) " · v${result.version}" else ""
            } else {
                // keep current list; still reload assets as offline fallback notice
                updateMessage = result.message + " · using local list (${rows.size})"
            }
            updating = false
        }
    }

    fun refreshHistory() {
        savedSnapshots = resultsRepo.listDnsSnapshots().filter { it.items.any { d -> d.pingMs != null } }
    }

    fun stopScan() {
        stopFlag = true
        scanJob?.cancel()
        scanning = false
        progress = "stopped"
    }

    fun startScan() {
        if (scanning) return
        stopFlag = false
        scanning = true
        progress = "starting…"
        nearest = emptyList()
        rows = rows.map {
            it.copy(pingMs = null, lossPct = null, jitterMs = null, status = "—", score = 0,
                gameLabel = "", gameAdvice = "")
        }
        scanJob = viewModelScope.launch {
            try {
                val updated = rows.map { it.copy() }.toMutableList()
                for ((bi, batch) in updated.chunked(8).withIndex()) {
                    if (stopFlag || !isActive) break
                    progress = "batch ${bi + 1} · ${batch.size} hosts"
                    val results = withContext(Dispatchers.IO) {
                        batch.map { d ->
                            async {
                                if (stopFlag) return@async d.ip to listOf<Any?>(null, 100, null, 0, "STOPPED", "")
                                val (avg, loss, jitter) = NetworkProber.probeDns(d.ip, 5)
                                val (score, label) = DnsData.scoreDns(avg, loss, jitter)
                                val advice = when {
                                    avg == null -> "Unreachable — skip for login."
                                    loss >= 20 -> "High loss — bad for game login."
                                    score >= 80 -> "Strong for in-game login / stores."
                                    else -> "Usable if nothing better nearby."
                                }
                                d.ip to listOf(avg, loss, jitter, score, label, advice)
                            }
                        }.awaitAll()
                    }
                    if (stopFlag || !isActive) break
                    results.forEach { (ip, data) ->
                        val ix = updated.indexOfFirst { it.ip == ip }
                        if (ix >= 0) {
                            updated[ix] = updated[ix].copy(
                                pingMs = data[0] as Long?,
                                lossPct = data[1] as Int,
                                jitterMs = data[2] as Long?,
                                score = data[3] as Int,
                                status = data[4] as String,
                                gameLabel = data[4] as String,
                                gameAdvice = data[5] as String
                            )
                        }
                    }
                    rows = updated.toList()
                }
                nearest = updated
                    .filter { it.pingMs != null }
                    .sortedBy { DnsData.distanceScore(it.pingMs, it.lossPct, it.jitterMs) }
                    .take(8)
                progress = if (stopFlag) "stopped" else "done · ${updated.count { it.pingMs != null }} reachable"
            } finally {
                scanning = false
            }
        }
    }

    fun saveCurrent(title: String = "") {
        val measured = rows.filter { it.pingMs != null }
        if (measured.isEmpty()) {
            saveMessage = "run a scan first"
            return
        }
        resultsRepo.saveDns(title, measured)
        refreshHistory()
        saveMessage = "saved (${measured.size})"
    }

    fun deleteSnapshot(id: String) {
        resultsRepo.deleteDns(id)
        refreshHistory()
    }

    fun loadSnapshot(id: String) {
        val snap = resultsRepo.loadDns(id) ?: return
        rows = snap.items
        nearest = snap.items.filter { it.pingMs != null }
            .sortedBy { DnsData.distanceScore(it.pingMs, it.lossPct, it.jitterMs) }
            .take(8)
        progress = "loaded: ${snap.title}"
    }
}
