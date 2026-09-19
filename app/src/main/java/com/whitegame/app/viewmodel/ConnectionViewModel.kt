package com.whitegame.app.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
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
import com.whitegame.app.connection.TunnelKind
import com.whitegame.app.connection.TunnelStats
import com.whitegame.app.connection.TunnelStore
import com.whitegame.app.data.AssetLoader
import com.whitegame.app.data.ConfigSubscription
import com.whitegame.app.data.DnsData
import com.whitegame.app.model.IspScanResult
import com.whitegame.app.model.RecommendedDns
import com.whitegame.app.model.SubNode
import com.whitegame.app.model.TunnelState
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.xray.XrayBridge
import com.whitegame.app.xray.XrayConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetLoader: AssetLoader,
    private val tunnel: TunnelController,
    private val store: TunnelStore,
    private val subscription: ConfigSubscription
) : ViewModel() {

    // ---- tunnel state, mirrored from the controller ----
    var tunnels by mutableStateOf<List<SavedTunnel>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf(TunnelState.DISCONNECTED)
        private set
    var statusLine by mutableStateOf("Not connected")
        private set
    var errorText by mutableStateOf("")
        private set
    var activeTunnel by mutableStateOf<SavedTunnel?>(null)
        private set
    var stats by mutableStateOf(TunnelStats())
        private set
    var latencyMs by mutableStateOf<Long?>(null)
        private set
    var connectedSince by mutableStateOf<Long?>(null)
        private set
    var killSwitch by mutableStateOf(false)
        private set
    var autoReconnect by mutableStateOf(true)
        private set

    // ---- editor / import ----
    var editorMessage by mutableStateOf("")
        private set
    var editorError by mutableStateOf("")
        private set

    // ---- scans ----
    var busyScan by mutableStateOf(false)
        private set
    var busySub by mutableStateOf(false)
        private set
    var isp by mutableStateOf<IspScanResult?>(null)
        private set
    var bestDns by mutableStateOf<List<RecommendedDns>>(emptyList())
        private set
    var subNodes by mutableStateOf<List<SubNode>>(emptyList())
        private set
    var subMessage by mutableStateOf("")
        private set
    var subUrl by mutableStateOf("")
        private set
    var preferredDns by mutableStateOf<String?>(null)
        private set
    var dnsMessage by mutableStateOf("")
        private set
    /** The DNS DNS-only mode is applying to every app right now; null when off. */
    var systemDns by mutableStateOf<String?>(null)
        private set

    val engineVersion: String get() = tunnel.engineVersion
    val engineAvailable: Boolean get() = tunnel.engineAvailable

    val isConnected: Boolean get() = state == TunnelState.CONNECTED
    val isBusy: Boolean get() = state.isBusy
    val selected: SavedTunnel? get() = tunnels.firstOrNull { it.id == selectedId }

    /** Xray tunnels run on their own core, so a missing WireGuard engine does not block them. */
    val canConnectSelected: Boolean
        get() = selected?.let { it.isXray || tunnel.engineAvailable } == true

    init {
        viewModelScope.launch { store.tunnels.collectLatest { tunnels = it } }
        viewModelScope.launch { store.selectedId.collectLatest { selectedId = it } }
        viewModelScope.launch { tunnel.state.collectLatest { state = it } }
        viewModelScope.launch { tunnel.status.collectLatest { statusLine = it } }
        viewModelScope.launch { tunnel.error.collectLatest { errorText = it } }
        viewModelScope.launch { tunnel.activeTunnel.collectLatest { activeTunnel = it } }
        viewModelScope.launch { tunnel.stats.collectLatest { stats = it } }
        viewModelScope.launch { tunnel.latencyMs.collectLatest { latencyMs = it } }
        viewModelScope.launch { tunnel.connectedSince.collectLatest { connectedSince = it } }
        viewModelScope.launch { tunnel.killSwitch.collectLatest { killSwitch = it } }
        viewModelScope.launch { tunnel.autoReconnect.collectLatest { autoReconnect = it } }
        viewModelScope.launch { tunnel.preferredDns.collectLatest { preferredDns = it } }
        viewModelScope.launch {
            tunnel.systemDns.collectLatest {
                val wasActive = systemDns != null
                systemDns = it
                if (it == null && wasActive) {
                    dnsMessage = if (state.isLive) "Using the tunnel's own DNS" else "Back to Android's default DNS"
                } else refreshDnsMessage()
            }
        }
        viewModelScope.launch {
            tunnel.dnsProblem.collectLatest { if (it.isNotBlank()) dnsMessage = it }
        }
        subUrl = subscription.url
    }

    // ---------- tunnel actions ----------

    fun select(id: String) = store.select(id)

    fun updateKillSwitch(v: Boolean) = tunnel.setKillSwitch(v)
    fun updateAutoReconnect(v: Boolean) = tunnel.setAutoReconnect(v)

    /**
     * Connect the selected tunnel, asking for VPN consent first if needed.
     * [launcher] receives the system consent dialog; [onVpnPermissionResult] resumes afterwards.
     */
    fun connect(launcher: ActivityResultLauncher<Intent>?) {
        val id = selectedId
        if (id == null) {
            errorText = "Add a tunnel first"
            return
        }
        connectId(id, launcher)
    }

    private fun connectId(id: String, launcher: ActivityResultLauncher<Intent>?) {
        pendingSystemDns = false
        val prepare = tunnel.prepareVpnIntent()
        if (prepare != null) {
            pendingConnectId = id
            if (launcher == null) {
                errorText = "VPN permission required"
                return
            }
            launcher.launch(prepare)
            return
        }
        tunnel.connect(id)
    }

    private var pendingConnectId: String? = null
    private var pendingSystemDns = false

    fun onVpnPermissionResult(resultCode: Int) {
        val id = pendingConnectId
        val dns = pendingSystemDns
        pendingConnectId = null
        pendingSystemDns = false
        if (resultCode != Activity.RESULT_OK) {
            if (dns) dnsMessage = "DNS not applied — Android needs the VPN permission to change DNS for every app"
            else errorText = "VPN permission denied"
            return
        }
        if (id != null) tunnel.connect(id)
        if (dns) tunnel.startSystemDns()
    }

    fun disconnect() {
        pendingConnectId = null
        pendingSystemDns = false
        tunnel.disconnect()
    }

    fun toggle(launcher: ActivityResultLauncher<Intent>?) {
        if (state.isLive) disconnect() else connect(launcher)
    }

    fun connectTo(id: String, launcher: ActivityResultLauncher<Intent>?) {
        store.select(id)
        if (state.isLive) tunnel.disconnect()
        connectId(id, launcher)
    }

    // ---------- editor ----------

    fun templateFor(kind: TunnelKind): String = TunnelStore.template(kind)

    fun newPrivateKey(): String = Keys.generatePrivateKey()

    /** Derives the public key for a pasted private key, for the "send this to your server" line. */
    fun publicKeyFor(privateKey: String): String? =
        runCatching { Keys.publicKeyOf(privateKey) }.getOrNull()

    /** Validates without saving, so the editor can show problems as the user types. */
    fun validate(confText: String): String? = try {
        if (XrayConfig.looksLikeXray(confText)) XrayConfig.parse(confText) else AwgConfig.parse(confText)
        null
    } catch (e: ConfigException) {
        e.message ?: "Invalid config"
    } catch (e: Exception) {
        e.message ?: "Invalid config"
    }

    fun saveTunnel(name: String, confText: String, id: String? = null): Boolean {
        return try {
            val saved = store.save(name, confText, id)
            editorError = ""
            editorMessage = "Saved " + saved.name + " · " + saved.kind.label
            true
        } catch (e: Exception) {
            editorError = e.message ?: "Could not save this config"
            editorMessage = ""
            false
        }
    }

    fun importText(text: String, nameHint: String = ""): Boolean {
        return try {
            val saved = store.importMany(text, nameHint)
            editorError = ""
            editorMessage = "Imported " + saved.size + " tunnel" + (if (saved.size == 1) "" else "s")
            true
        } catch (e: Exception) {
            editorError = e.message ?: "Could not read that config"
            editorMessage = ""
            false
        }
    }

    fun deleteTunnel(id: String) {
        if (activeTunnel?.id == id) tunnel.disconnect()
        store.delete(id)
        editorMessage = "Tunnel deleted"
    }

    fun renameTunnel(id: String, name: String) = store.rename(id, name)

    fun clearEditorMessages() {
        editorMessage = ""
        editorError = ""
    }

    fun parsed(t: SavedTunnel): AwgConfig? = if (t.isXray) null else runCatching { t.parse() }.getOrNull()

    fun parsedXray(t: SavedTunnel): XrayConfig.Parsed? = if (!t.isXray) null else runCatching { t.parseXray() }.getOrNull()

    /**
     * Whether a saved tunnel still reads back. [parsed] returns null for every Xray tunnel by
     * design -- an Xray link has no AwgConfig -- so the row cannot use it to judge validity
     * without calling all of them broken.
     */
    fun isValid(t: SavedTunnel): Boolean =
        if (t.isXray) parsedXray(t) != null else parsed(t) != null

    /** Keys the AmneziaWG engine will not use, so the editor can say so instead of dropping them silently. */
    fun ignoredKeys(confText: String): List<String> =
        if (XrayConfig.looksLikeXray(confText)) emptyList()
        else runCatching { AwgConfig.parse(confText).ignoredKeys }.getOrDefault(emptyList())

    // ---------- scans ----------

    /** The list is whatever the user points at — nothing is fetched from a built-in link. */
    fun updateSubUrl(value: String) {
        subUrl = value
        subscription.url = value
    }

    fun refreshSubscription() {
        if (busySub) return
        busySub = true
        subMessage = "Loading configs…"
        viewModelScope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) { subscription.fetch(subUrl) }
                subNodes = nodes
                val usable = nodes.count { it.conf.isNotBlank() }
                subMessage = "Loaded " + nodes.size + " server" + (if (nodes.size == 1) "" else "s") +
                    if (usable > 0) " · " + usable + " can be added as tunnels"
                    else " · ping only: this list carries no keys"
            } catch (e: Exception) {
                subMessage = "Subscription error: " + (e.message ?: "failed")
            } finally {
                busySub = false
            }
        }
    }

    /** Saves one subscription server as a real tunnel and selects it, ready to connect. */
    /**
     * Real round trip through the node's own proxy.
     *
     * A bare TCP connect only proves that something on the path completed a handshake:
     * transparent proxies and DPI middleboxes answer one in a few milliseconds for a server
     * on another continent, which is why every node used to rank at 3-8ms. Fetching a real
     * 204 through the outbound cannot be answered by anything but the server itself.
     */
    private suspend fun measureThroughProxy(n: SubNode): SubNode {
        val parsed = runCatching { XrayConfig.parse(n.conf) }.getOrNull()
            ?: return n.copy(pingMs = null, lossPct = 100, score = 0, status = "Bad config")
        // A dead node otherwise sits on the core's 30s timeout and stalls the queue.
        val ms = withTimeoutOrNull(9_000) {
            XrayBridge.delay(context, parsed).getOrNull()
        }
        return n.copy(
            pingMs = ms,
            lossPct = if (ms != null) 0 else 100,
            score = if (ms != null) DnsData.scoreDns(ms, 0, 0L).first else 0,
            status = if (ms != null) "OK" else "Unreachable"
        )
    }

    fun importNode(node: SubNode) {
        if (node.conf.isBlank()) {
            subMessage = "That entry has no key material — it can only be pinged"
            return
        }
        try {
            val saved = store.save(node.name.take(40), node.conf)
            store.select(saved.id)
            subMessage = "Added " + saved.name + " · selected, hit Connect"
        } catch (e: Exception) {
            subMessage = "Could not add: " + (e.message ?: "invalid config")
        }
    }

    fun importAllNodes() {
        val usable = subNodes.filter { it.conf.isNotBlank() }
        if (usable.isEmpty()) {
            subMessage = "Nothing to add — this list carries no keys"
            return
        }
        var added = 0
        usable.forEach { n ->
            runCatching { store.save(n.name.take(40), n.conf) }.onSuccess { added++ }
        }
        subMessage = "Added " + added + " of " + usable.size + " servers"
    }

    fun scanSubscriptionNodes() {
        if (busyScan) return
        if (subNodes.isEmpty()) {
            subMessage = "Load the subscription first"
            return
        }
        busyScan = true
        subMessage = "Scanning " + subNodes.size + " nodes…"
        viewModelScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) {
                    val gate = kotlinx.coroutines.sync.Semaphore(4)
                    // The test core handles one request at a time, so proxy measurements
                    // queue rather than run four deep.
                    val proxyGate = kotlinx.coroutines.sync.Semaphore(1)
                    subNodes.map { n ->
                        async {
                            if (XrayConfig.looksLikeXray(n.conf)) proxyGate.withPermit {
                                measureThroughProxy(n)
                            } else gate.withPermit {
                                val r = NetworkProber.probeTcp(n.host, n.port, 3, 2500)
                                n.copy(
                                    pingMs = r.avgMs,
                                    lossPct = r.lossPct,
                                    score = DnsData.scoreDns(r.avgMs, r.lossPct, r.jitterMs).first,
                                    status = if (r.avgMs != null) "OK" else "Unreachable"
                                )
                            }
                        }
                    }.awaitAll()
                }
                subNodes = scanned.sortedByDescending { it.score }
                val best = subNodes.firstOrNull { it.pingMs != null }
                subMessage = if (best != null)
                    "Best: " + best.flag + " " + best.name.take(24) + " · " + best.pingMs + "ms"
                else "No reachable nodes"
            } catch (e: Exception) {
                subMessage = "Scan failed: " + (e.message ?: "error")
            } finally {
                busyScan = false
            }
        }
    }

    fun scanIspAndDns() {
        if (busyScan) return
        busyScan = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val (samples, loss) = NetworkProber.livePingSuite(8)
                    val avg = if (samples.isNotEmpty()) samples.sum() / samples.size else null
                    IspScanResult(
                        networkType = "Active",
                        ispHint = "App network path (Xray excludes app probes)",
                        livePingMs = samples.lastOrNull(),
                        avgPingMs = avg,
                        minPingMs = samples.minOrNull(),
                        maxPingMs = samples.maxOrNull(),
                        lossPct = loss,
                        quality = when {
                            avg == null -> "Unknown"
                            avg < 50 -> "Excellent"
                            avg < 90 -> "Good"
                            avg < 140 -> "Fair"
                            else -> "Poor"
                        },
                        advice = when {
                            avg == null -> "No probe answered. Check connectivity, then compare another network or tunnel."
                            loss > 5 -> "Some connection attempts failed. Compare Wi-Fi and mobile data before changing DNS."
                            samples.maxOrNull()!! - samples.minOrNull()!! > 50 -> "Latency varies. Pause background transfers and compare a stronger Wi-Fi signal."
                            avg > 140 -> "High round-trip time. Compare a closer game region or a saved tunnel in Games."
                            else -> "Connection looks steady. Use Games to compare routes to your specific game."
                        },
                        samples = samples
                    )
                }
                val dns = withContext(Dispatchers.IO) {
                    val gate = kotlinx.coroutines.sync.Semaphore(4)
                    assetLoader.loadDns().take(30).map { d -> async { gate.withPermit {
                        val (avg, loss, jitter) = NetworkProber.probeDns(d.ip, 3)
                        if (avg == null) return@withPermit null
                        val (score, _) = DnsData.scoreDns(avg, loss, jitter)
                        RecommendedDns(d.ip, d.provider, avg, jitter, loss, score, d.notes)
                    } } }.awaitAll().filterNotNull().sortedByDescending { it.score }.take(8)
                }
                isp = result
                bestDns = dns
            } catch (e: Exception) {
                errorText = e.message ?: "Scan failed"
            } finally {
                busyScan = false
            }
        }
    }

    /**
     * Applies a DNS server. With a tunnel up it goes into the tunnel; otherwise DNS-only mode
     * applies it to every app until the app is closed. Blank goes back to Android's own DNS.
     * [launcher] shows the one-time VPN consent DNS-only mode needs.
     */
    fun usePreferredDns(ip: String?, launcher: ActivityResultLauncher<Intent>?) {
        pendingSystemDns = false
        pendingConnectId = null
        val error = tunnel.setPreferredDns(ip)
        if (error != null) {
            dnsMessage = error
            return
        }
        if (ip.isNullOrBlank()) {
            dnsMessage = if (state.isLive) "Using the tunnel's own DNS" else "Back to Android's default DNS"
            return
        }
        if (state.isLive) {
            dnsMessage = "DNS set to " + ip.trim() + " · reapplying to the live tunnel"
            return
        }
        val consent = tunnel.prepareVpnIntent()
        if (consent != null) {
            if (launcher == null) {
                dnsMessage = "VPN permission required to change DNS"
                return
            }
            pendingSystemDns = true
            launcher.launch(consent)
            return
        }
        dnsMessage = "Applying " + ip.trim() + " to every app…"
        tunnel.startSystemDns()
    }

    fun toggleDns(ip: String, launcher: ActivityResultLauncher<Intent>?) =
        usePreferredDns(if (preferredDns == ip) null else ip, launcher)

    private fun refreshDnsMessage() {
        val active = systemDns ?: return
        dnsMessage = "DNS " + active + " is active for every app · it switches back to Android's DNS when you close the app"
    }

    fun onAppClosed() = tunnel.onAppClosed()
}
