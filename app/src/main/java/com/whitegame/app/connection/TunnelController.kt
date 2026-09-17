package com.whitegame.app.connection

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.whitegame.app.model.TunnelState
import com.whitegame.app.xray.XrayBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.amnezia.awg.GoBackend
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the tunnel state machine: start/stop requests, connect timeout, auto-reconnect on a
 * dead path, and the single source of truth the UI observes.
 *
 * Two tunnel backends feed it the same [TunnelBus] events — the AmneziaWG engine in
 * [WhiteGameVpnService] and the Xray core behind [XrayBridge] — plus DNS-only mode, which
 * applies a DNS server to the whole phone while no tunnel is up.
 */
@Singleton
class TunnelController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TunnelStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TunnelState.DISCONNECTED)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _activeTunnel = MutableStateFlow<SavedTunnel?>(null)
    val activeTunnel: StateFlow<SavedTunnel?> = _activeTunnel.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** Round-trip measured *through* the tunnel — the number that matters for games. */
    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _killSwitch = MutableStateFlow(false)
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    /**
     * The DNS the user applied this session. It lives only as long as the app: closing the app
     * hands DNS back to Android, so it is deliberately not persisted.
     */
    private val _preferredDns = MutableStateFlow<String?>(WhiteGameVpnService.systemDns)
    val preferredDns: StateFlow<String?> = _preferredDns.asStateFlow()

    /** Non-null while DNS-only mode is applying [preferredDns] to every app. */
    private val _systemDns = MutableStateFlow(WhiteGameVpnService.systemDns)
    val systemDns: StateFlow<String?> = _systemDns.asStateFlow()

    private val _dnsProblem = MutableStateFlow("")
    val dnsProblem: StateFlow<String> = _dnsProblem.asStateFlow()

    private val prefs = context.getSharedPreferences("wg_tunnel_prefs", Context.MODE_PRIVATE)
    private var lastTunnelId: String? = null
    private var reconnectAttempts = 0
    private var userStopped = false
    private var watchdog: Job? = null
    private var pingJob: Job? = null
    private var restartJob: Job? = null
    private var dnsJob: Job? = null

    /** Which backend the current attempt uses; events from the other one are stale. */
    private var usingXray = false

    /** Last rx counter seen, and when it last moved — the real proof a path is alive. */
    private var lastTraffic = 0L
    private var lastTrafficAt = 0L

    val engineVersion: String get() = GoBackend.versionOrError()
    val engineAvailable: Boolean get() = GoBackend.isAvailable

    init {
        _killSwitch.value = prefs.getBoolean("kill_switch", false)
        _autoReconnect.value = prefs.getBoolean("auto_reconnect", true)
        XrayBridge.register(context)

        scope.launch {
            TunnelBus.events.collect { ev ->
                if (ev is TunnelBus.Event.SystemDns) {
                    _systemDns.value = ev.dns
                    _dnsProblem.value = ev.error
                    return@collect
                }
                if (ev.xray != usingXray) return@collect
                when (ev) {
                    is TunnelBus.Event.Connected -> {
                        if (userStopped) return@collect
                        resetTrafficWindow()
                        _state.value = TunnelState.CONNECTING
                        _status.value = if (usingXray) "Reaching the server…" else "Handshaking…"
                        _error.value = ""
                        _activeTunnel.value = store.find(ev.tunnelId)
                        _connectedSince.value = System.currentTimeMillis()
                    }

                    is TunnelBus.Event.Disconnected -> {
                        if (ev.stopRequested) {
                            userStopped = true
                            restartJob?.cancel()
                        }
                        watchdog?.cancel()
                        stopPingLoop()
                        _state.value = TunnelState.DISCONNECTED
                        _status.value = ev.reason
                        _activeTunnel.value = null
                        _connectedSince.value = null
                        _stats.value = TunnelStats()
                        resumeSystemDnsSoon()
                    }

                    is TunnelBus.Event.Failed -> {
                        watchdog?.cancel()
                        stopPingLoop()
                        _state.value = TunnelState.FAILED
                        _status.value = ev.message
                        _error.value = ev.message
                        _activeTunnel.value = null
                        _connectedSince.value = null
                        _stats.value = TunnelStats()
                    }

                    is TunnelBus.Event.Stats -> onStats(ev.stats)
                    is TunnelBus.Event.SystemDns -> Unit
                }
            }
        }
    }

    private val backendRunning: Boolean
        get() = if (usingXray) XrayBridge.isRunning else WhiteGameVpnService.isRunning

    /**
     * A tunnel only counts as connected once a handshake lands — a TUN device that never
     * completes one looks "up" while carrying nothing. (For Xray, the "handshake" is the last
     * successful round trip through the server.)
     *
     * Past the first handshake, staleness alone is not proof of death: WireGuard only rekeys
     * when there is traffic, so an idle tunnel ages out of [TunnelStats.isHealthy] while still
     * being perfectly usable. Tearing that down is what threw people off the tunnel at random.
     * A path counts as dead only when the handshake is stale *and* no bytes have moved through
     * it for [DEAD_PATH_MS] — then, and only then, is it worth switching.
     */
    private fun onStats(s: TunnelStats) {
        if (userStopped || !_state.value.isLive || !backendRunning) return
        _stats.value = s
        val now = System.currentTimeMillis()
        // Received bytes only: the app keeps pinging through the tunnel, so tx rises even
        // into a black hole. Bytes coming back are the only proof the far end is still there.
        val moved = s.rxBytes
        if (moved != lastTraffic || lastTrafficAt == 0L) {
            lastTraffic = moved
            lastTrafficAt = now
        }
        val quietMs = now - lastTrafficAt

        when {
            !s.hasHandshake -> {
                _state.value = TunnelState.CONNECTING
                _status.value = if (usingXray) "Reaching the server…" else "Handshaking…"
            }

            !s.isPathDead(quietMs, DEAD_PATH_MS) -> {
                watchdog?.cancel()
                if (_state.value != TunnelState.CONNECTED) {
                    reconnectAttempts = 0
                    startPingLoop()
                }
                _state.value = TunnelState.CONNECTED
                _status.value = if (s.isHealthy) "Connected" else "Connected · idle"
            }

            else -> {
                _status.value = "Path dead for " + (quietMs / 1000) + "s"
                val alreadyRetrying = _state.value == TunnelState.RECONNECTING
                if (_autoReconnect.value && !userStopped && !alreadyRetrying) {
                    _state.value = TunnelState.RECONNECTING
                    restartJob?.cancel()
                    restartJob = scope.launch { reconnect() }
                }
            }
        }
    }

    /**
     * Samples latency through the tunnel. WireGuard routes the app's own traffic into the
     * tunnel, so a plain connect measures the path games take. Xray excludes the app (its core
     * sockets must bypass the tunnel), so there the core's own round trip is the number.
     */
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                _latencyMs.value = if (usingXray) XrayBridge.latencyMs
                else com.whitegame.app.network.NetworkProber.probeTcp("1.1.1.1", 443, 2, 1500).avgMs
                delay(if (usingXray) 2000 else 4000)
            }
        }
    }

    private fun resetTrafficWindow() {
        lastTraffic = 0L
        lastTrafficAt = 0L
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
        _latencyMs.value = null
    }

    fun setKillSwitch(v: Boolean) {
        _killSwitch.value = v
        prefs.edit().putBoolean("kill_switch", v).apply()
    }

    fun setAutoReconnect(v: Boolean) {
        _autoReconnect.value = v
        prefs.edit().putBoolean("auto_reconnect", v).apply()
        if (!v) restartJob?.cancel()
    }

    /**
     * Sets (or clears, with null/blank) the DNS choice. A live tunnel is rebuilt right away —
     * the DNS servers are baked into the TUN device at establish time. With no tunnel, DNS-only
     * mode carries the change (the caller starts it once VPN consent is in hand).
     *
     * @return an error message when [ip] is not usable, null when it was accepted.
     */
    fun setPreferredDns(ip: String?): String? {
        val clean = ip?.trim().orEmpty()
        val value = clean.ifBlank { null }
        if (value != null) {
            val bad = value.split(",").map { it.trim() }.firstOrNull { !isIpLiteral(it) }
            if (bad != null) return "\"" + bad + "\" is not an IP address"
        }
        _dnsProblem.value = ""
        if (value == _preferredDns.value) return null
        _preferredDns.value = value
        val id = _activeTunnel.value?.id ?: lastTunnelId
        if (id != null && _state.value.isLive) {
            _status.value = "Applying DNS…"
            restartJob?.cancel()
            restartJob = scope.launch {
                delay(400)
                if (!userStopped) startConnection(id)
            }
        } else if (value == null) {
            stopSystemDns()
        }
        return null
    }

    /** Applies [preferredDns] to every app while no tunnel is up. Needs VPN consent already given. */
    fun startSystemDns() {
        val dns = _preferredDns.value ?: return
        if (_state.value.isLive) return
        dnsJob?.cancel()
        runCatching {
            val intent = WhiteGameVpnService.dnsStartIntent(context, dns)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.onFailure { _dnsProblem.value = "Cannot start DNS mode: " + (it.message ?: it.javaClass.simpleName) }
    }

    fun stopSystemDns() {
        dnsJob?.cancel()
        if (_systemDns.value == null && WhiteGameVpnService.systemDns == null) return
        runCatching { context.startService(WhiteGameVpnService.dnsStopIntent(context)) }
        _systemDns.value = null
    }

    /** After a tunnel goes down, the DNS the user chose keeps applying system-wide. */
    private fun resumeSystemDnsSoon() {
        if (_preferredDns.value == null || VpnService.prepare(context) != null) return
        dnsJob?.cancel()
        dnsJob = scope.launch {
            // Let the tunnel service finish stopping before the DNS-only VPN starts in it.
            delay(1200)
            if (!_state.value.isLive) startSystemDns()
        }
    }

    /** The app is closing: DNS goes back to Android's own. A live tunnel is left alone. */
    fun onAppClosed() {
        stopSystemDns()
        setPreferredDns(null)
    }

    /** Non-null when the user still has to approve the VPN. Hand it to an activity launcher. */
    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    fun connect(tunnelId: String) {
        restartJob?.cancel()
        reconnectAttempts = 0
        userStopped = false
        startConnection(tunnelId)
    }

    private fun startConnection(tunnelId: String) {
        val tunnel = store.find(tunnelId)
        if (tunnel == null) {
            _state.value = TunnelState.FAILED
            _error.value = "That tunnel no longer exists"
            _status.value = _error.value
            return
        }
        if (!tunnel.isXray && !GoBackend.isAvailable) {
            _state.value = TunnelState.FAILED
            _error.value = "Tunnel engine unavailable: " +
                GoBackend.loadError.ifBlank { "libwg-go.so missing for this ABI" }
            _status.value = _error.value
            return
        }
        // Catch a bad saved config before spinning up a service that can only fail.
        tunnel.problem()?.let {
            _state.value = TunnelState.FAILED
            _error.value = "Config error: $it"
            _status.value = _error.value
            return
        }

        dnsJob?.cancel()
        // Only one VPN runs at a time: hand over explicitly instead of letting Android revoke.
        if (tunnel.isXray) {
            if (WhiteGameVpnService.isRunning || _systemDns.value != null) {
                runCatching { context.startService(WhiteGameVpnService.disconnectIntent(context)) }
                stopSystemDns()
            }
        } else if (XrayBridge.isRunning || usingXray) {
            XrayBridge.disconnect(context, user = false)
        }
        usingXray = tunnel.isXray

        userStopped = false
        resetTrafficWindow()
        lastTunnelId = tunnelId
        _state.value = TunnelState.CONNECTING
        _status.value = "Starting " + tunnel.name + "…"
        _error.value = ""

        try {
            if (tunnel.isXray) {
                val dns = _preferredDns.value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                XrayBridge.connect(context, tunnel.id, tunnel.name, tunnel.parseXray(), dns)
            } else {
                val intent = WhiteGameVpnService.connectIntent(
                    context, tunnelId, _killSwitch.value, _preferredDns.value
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        } catch (e: Exception) {
            _state.value = TunnelState.FAILED
            _error.value = "Cannot start VPN service: " + (e.message ?: e.javaClass.simpleName)
            _status.value = _error.value
            return
        }

        watchdog?.cancel()
        watchdog = scope.launch {
            delay(if (tunnel.isXray) XRAY_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
            if (_state.value == TunnelState.CONNECTING || _state.value == TunnelState.RECONNECTING) {
                val why = (if (usingXray) XrayBridge.lastError else WhiteGameVpnService.lastError).ifBlank {
                    if (usingXray) "The server did not answer — check the link and your connection"
                    else "No handshake — check the server key, endpoint and your connection"
                }
                _state.value = TunnelState.FAILED
                _error.value = why
                _status.value = why
                stopBackend()
            }
        }
    }

    private fun stopBackend() {
        if (usingXray) XrayBridge.disconnect(context, user = true)
        else runCatching { context.startService(WhiteGameVpnService.disconnectIntent(context)) }
    }

    fun disconnect() {
        userStopped = true
        restartJob?.cancel()
        watchdog?.cancel()
        stopPingLoop()
        _status.value = "Disconnecting…"
        stopBackend()
        _state.value = TunnelState.DISCONNECTED
        _status.value = "Not connected"
        _activeTunnel.value = null
        _connectedSince.value = null
        _stats.value = TunnelStats()
        resumeSystemDnsSoon()
    }

    fun toggle(tunnelId: String) {
        if (_state.value == TunnelState.CONNECTED ||
            _state.value == TunnelState.CONNECTING ||
            _state.value == TunnelState.RECONNECTING
        ) disconnect() else connect(tunnelId)
    }

    private suspend fun reconnect() {
        val id = lastTunnelId ?: return
        if (userStopped || reconnectAttempts >= MAX_RECONNECTS) {
            if (reconnectAttempts >= MAX_RECONNECTS) {
                _state.value = TunnelState.FAILED
                _error.value = "Reconnect failed after " + MAX_RECONNECTS + " tries"
                _status.value = _error.value
                stopBackend()
            }
            return
        }
        reconnectAttempts++
        _status.value = "Reconnecting (" + reconnectAttempts + "/" + MAX_RECONNECTS + ")…"
        // Back off: a handover that has not settled yet fails every attempt made too early.
        delay(600L * reconnectAttempts)
        if (!userStopped && _autoReconnect.value) startConnection(id)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** First HTTPS round trip through a proxy can legitimately take a while on a slow path. */
        private const val XRAY_CONNECT_TIMEOUT_MS = 30_000L
        private const val MAX_RECONNECTS = 3

        /** No handshake *and* not a byte through the tunnel for this long means the path is gone. */
        private const val DEAD_PATH_MS = 45_000L

        /**
         * VpnService.Builder takes IP literals only, and resolving a hostname to check would
         * block the caller, so this is a pure syntax check.
         */
        fun isIpLiteral(v: String): Boolean {
            if (v.isBlank()) return false
            if (v.contains(':')) return v.matches(Regex("[0-9A-Fa-f:]{2,45}")) && !v.contains(":::")
            val parts = v.split('.')
            return parts.size == 4 && parts.all { p ->
                p.length in 1..3 && p.all { it.isDigit() } && (p.toIntOrNull() ?: -1) in 0..255
            }
        }
    }
}
