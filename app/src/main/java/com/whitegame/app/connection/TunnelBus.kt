package com.whitegame.app.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Counters read back from the engine's UAPI, summed across peers. */
data class TunnelStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    /** Unix seconds of the most recent handshake; 0 means none yet. */
    val lastHandshakeSec: Long = 0,
    val rxBps: Long = 0,
    val txBps: Long = 0
) {
    val handshakeAgeSec: Long?
        get() = if (lastHandshakeSec <= 0) null
        else (System.currentTimeMillis() / 1000 - lastHandshakeSec).coerceAtLeast(0)

    /** WireGuard rekeys every ~2 min; nothing for 3+ min means the path is gone. */
    val isHealthy: Boolean get() = (handshakeAgeSec ?: Long.MAX_VALUE) < 180

    val hasHandshake: Boolean get() = lastHandshakeSec > 0

    /**
     * Dead means "switch to another tunnel", so it has to be more than a stale handshake:
     * WireGuard rekeys only when traffic flows, so an idle tunnel goes stale while still
     * working. Only a stale handshake with nothing coming back over [quietMs] is really gone.
     */
    fun isPathDead(quietMs: Long, deadAfterMs: Long): Boolean =
        hasHandshake && !isHealthy && quietMs >= deadAfterMs
}

/** Cross-layer events between the VPN services and the controller / UI. */
object TunnelBus {
    /** [xray] says which backend sent it, so a late event from the one being replaced is ignored. */
    sealed class Event {
        abstract val xray: Boolean
        data class Connected(val tunnelId: String, val name: String, override val xray: Boolean = false) : Event()
        data class Disconnected(val reason: String, val stopRequested: Boolean = false, override val xray: Boolean = false) : Event()
        data class Failed(val message: String, override val xray: Boolean = false) : Event()
        data class Stats(val stats: TunnelStats, override val xray: Boolean = false) : Event()
        /** The DNS-only VPN came up ([dns] set) or went away (null), with an optional problem. */
        data class SystemDns(val dns: String?, val error: String = "", override val xray: Boolean = false) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emit(e: Event) {
        _events.tryEmit(e)
    }
}
