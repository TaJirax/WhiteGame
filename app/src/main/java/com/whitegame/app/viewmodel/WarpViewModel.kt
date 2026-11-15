package com.whitegame.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.connection.Keys
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

    fun updateEndpoints(value: String) {
        if (busy) return
        endpointsText = value
        measurements = emptyList()
    }

    fun create(acceptedTerms: Boolean) {
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
                message = "WARP saved and selected. Scan its endpoints, then connect."
            } catch (e: CancellationException) { throw e }
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
        measurements = emptyList()
        scannedId = selected.id
        scannedConf = selected.confText
        job = viewModelScope.launch {
            try {
                for ((i, endpoint) in candidates.withIndex()) {
                    ensureActive()
                    message = "Testing ${i + 1}/${candidates.size} · $endpoint"
                    val result = XrayBridge.delay(context, WireGuardProbe.parsed(config, endpoint))
                    ensureActive()
                    if (tunnel.state.value.isLive) {
                        measurements = emptyList()
                        message = "Tunnel started during the scan. Disconnect it and scan again."
                        return@launch
                    }
                    measurements = (measurements + Measurement(endpoint, result.getOrNull(), result.exceptionOrNull()?.message.orEmpty()))
                        .sortedBy { it.ms ?: Long.MAX_VALUE }
                }
                val best = measurements.firstOrNull { it.ms != null }
                message = if (best == null) "No endpoint completed a WireGuard HTTPS test. Check the keys and UDP path."
                    else "Best: ${best.endpoint} · ${best.ms}ms HTTPS through WireGuard"
            } catch (e: CancellationException) { message = "Scan stopped"; throw e }
            catch (e: Exception) { message = e.message ?: "Endpoint scan failed" }
            finally { busy = false }
        }
    }

    fun useBest() {
        val best = measurements.firstOrNull { it.ms != null } ?: return
        val saved = store.find(scannedId) ?: return
        if (saved.confText != scannedConf) { message = "Profile changed. Scan again before applying an endpoint."; return }
        val config = saved.parse()
        val updated = config.copy(peers = listOf(config.peers.single().copy(endpoint = best.endpoint)))
        store.save(saved.name, updated.toConfText(), saved.id)
        store.select(saved.id)
        message = "Saved ${best.endpoint}. Connect the profile to use it."
    }

    fun stop() { job?.cancel(); message = "Stopping after the current tunnel test…" }
}
