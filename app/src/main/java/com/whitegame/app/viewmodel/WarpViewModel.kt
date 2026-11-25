package com.whitegame.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.Keys
import com.whitegame.app.connection.SavedTunnel
import com.whitegame.app.connection.TunnelController
import com.whitegame.app.connection.TunnelStore
import com.whitegame.app.connection.WarpProvisioning
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.xray.XrayBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WarpViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TunnelStore,
    private val tunnel: TunnelController
) : ViewModel() {
    data class Measurement(val endpoint: String, val ms: Long?, val error: String = "")
    private val prefs = context.getSharedPreferences("warp_setup", Context.MODE_PRIVATE)
    var busy by mutableStateOf(false)
        private set
    var registering by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var endpointsText by mutableStateOf(prefs.getString("endpoints", "").orEmpty())
        private set
    var measurements by mutableStateOf<List<Measurement>>(emptyList())
        private set
    private var scannedId: String? = null
    private var scannedConf: String? = null
    private var job: Job? = null
    val supportsEndpointTests: Boolean get() = XrayBridge.isSupported

    fun updateEndpoints(value: String) {
        if (busy) return
        endpointsText = value
        measurements = emptyList()
    }

    fun create(acceptedTerms: Boolean, testAfterSetup: Boolean) {
        if (busy) return
        if (!acceptedTerms) { message = "Accept Cloudflare's terms before registering"; return }
        prefs.getString("tunnel", null)?.let { id -> store.find(id)?.let {
            store.select(id)
            message = "Your WARP profile is already saved. Scan endpoints or connect it."
            return
        } }
        busy = true
        registering = true
        message = "Generating a local key and registering WARP…"
        job = viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { WarpProvisioning.register(Keys.generatePrivateKey()) }
                val saved = store.save("WARP", profile.confText)
                store.select(saved.id)
                endpointsText = profile.endpoints.joinToString("\n")
                prefs.edit().putString("tunnel", saved.id).putString("endpoints", endpointsText).apply()
                measurements = emptyList()
                message = "WARP profile saved and selected."
                if (testAfterSetup && supportsEndpointTests) {
                    registering = false
                    if (tunnel.state.value.isLive) {
                        message = "WARP profile saved. Disconnect the active tunnel to test its endpoints."
                    } else {
                        measureEndpoints(saved, saved.parse(), WireGuardProbe.setupEndpoints(profile.endpoints))
                        if (measurements.any { it.ms != null }) applyBest(select = false)
                        else message = "WARP profile saved. None of the tested endpoints completed a WireGuard HTTPS test."
                    }
                }
            } catch (e: CancellationException) {
                if (!registering) message = "WARP profile saved. Endpoint test stopped."
                throw e
            }
            catch (e: Exception) { message = e.message ?: "WARP setup failed. Try another network or import a config." }
            finally { busy = false; registering = false }
        }
    }

    fun scan() {
        if (busy) return
        if (tunnel.state.value.isLive) { message = "Disconnect the active tunnel before scanning endpoints"; return }
        val selected = store.selected ?: run { message = "Select a saved WireGuard profile first"; return }
        if (selected.isXray) { message = "Select a WireGuard profile to scan endpoints"; return }
        val config = runCatching { selected.parse() }.getOrElse { message = it.message.orEmpty(); return }
        val candidates = runCatching {
            WireGuardProbe.endpoints(endpointsText.ifBlank { config.primaryEndpoint.orEmpty() }).also {
                WireGuardProbe.parsed(config, it.first())
            }
        }.getOrElse { message = it.message.orEmpty(); return }
        if (!XrayBridge.isSupported) { message = XrayBridge.UNSUPPORTED; return }
        busy = true
        job = viewModelScope.launch {
            try {
                measureEndpoints(selected, config, candidates)
                val best = measurements.firstOrNull { it.ms != null }
                message = if (best == null) "No endpoint completed a WireGuard HTTPS test. Check the keys and UDP path."
                    else "Best: ${best.endpoint} · ${best.ms}ms HTTPS through WireGuard"
            } catch (e: CancellationException) { message = "Scan stopped"; throw e }
            catch (e: Exception) { message = e.message ?: "Endpoint scan failed" }
            finally { busy = false }
        }
    }

    private suspend fun measureEndpoints(saved: SavedTunnel, config: AwgConfig, candidates: List<String>) {
        measurements = emptyList()
        scannedId = saved.id
        scannedConf = saved.confText
        for ((index, endpoint) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            ensureScanTarget(saved)
            message = "Testing ${index + 1}/${candidates.size} · $endpoint"
            val result = XrayBridge.delay(context, WireGuardProbe.parsed(config, endpoint))
            currentCoroutineContext().ensureActive()
            ensureScanTarget(saved)
            measurements = (measurements + Measurement(endpoint, result.getOrNull(), result.exceptionOrNull()?.message.orEmpty()))
                .sortedBy { it.ms ?: Long.MAX_VALUE }
        }
    }

    private fun ensureScanTarget(saved: SavedTunnel) {
        val problem = when {
            tunnel.state.value.isLive -> "Tunnel started during the scan. Disconnect it and scan again."
            store.find(saved.id)?.confText != saved.confText -> "Profile changed. Select a profile and scan again."
            else -> return
        }
        measurements = emptyList()
        throw ConfigException(problem)
    }

    fun useBest() = applyBest(select = true)

    private fun applyBest(select: Boolean) {
        val best = measurements.firstOrNull { it.ms != null } ?: return
        val saved = store.find(scannedId)
        if (saved == null || saved.confText != scannedConf) { message = "Profile changed. Scan again before applying an endpoint."; return }
        val config = saved.parse()
        val updated = config.copy(peers = listOf(config.peers.single().copy(endpoint = best.endpoint)))
        val updatedTunnel = store.save(saved.name, updated.toConfText(), saved.id)
        scannedConf = updatedTunnel.confText
        if (select) store.select(saved.id)
        message = "Saved ${best.endpoint} · ${best.ms} ms. Connect the profile to use it."
    }

    fun stop() { job?.cancel(); message = "Stopping after the current tunnel test…" }
}
