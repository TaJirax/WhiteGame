package com.whitegame.app.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.whitegame.app.MainActivity
import com.whitegame.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.GoBackend
import java.net.InetAddress
import javax.inject.Inject

/**
 * Runs a real WireGuard / AmneziaWG tunnel through the bundled amneziawg-go engine.
 *
 * Everything that can fail (missing native lib, bad config, unresolvable endpoint, denied VPN
 * permission, engine refusing the config) is reported through [TunnelBus] as a message the UI
 * can show, rather than being swallowed into a tunnel that looks up but carries no traffic.
 */
@AndroidEntryPoint
class WhiteGameVpnService : VpnService() {

    @Inject lateinit var store: TunnelStore

    // Serialize lifecycle changes with Android's service callbacks. DNS alone runs off-main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectJob: Job? = null
    private var statsJob: Job? = null

    @Volatile private var handle: Int = -1
    private var dnsForwarder: DnsForwarder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                bringDown("Disconnected", stopRequested = intent.getBooleanExtra(EXTRA_STOP_REQUESTED, true))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                val tunnelId = intent.getStringExtra(EXTRA_TUNNEL_ID).orEmpty()
                val killSwitch = intent.getBooleanExtra(EXTRA_KILL_SWITCH, false)
                val dnsOverride = intent.getStringExtra(EXTRA_DNS)
                val tunnel = store.find(tunnelId)
                // Must go foreground before anything can fail: the system kills a service that
                // was started as foreground and does not post its notification in time.
                startForeground(NOTIF_ID, buildNotification(tunnel?.name ?: "Tunnel", "Connecting…"))
                if (tunnel == null) {
                    fail("Tunnel not found — pick one in Tunnels")
                    return START_NOT_STICKY
                }
                connectJob?.cancel()
                connectJob = scope.launch { bringUp(tunnel, killSwitch, dnsOverride) }
                // Not sticky: a redelivered null intent carries no tunnel to bring back up.
                return START_NOT_STICKY
            }

            ACTION_DNS_START -> {
                val dns = intent.getStringExtra(EXTRA_DNS).orEmpty()
                startForeground(NOTIF_ID, buildNotification("DNS " + dns, "Applied to every app"))
                scope.launch { bringUpDns(dns) }
                return START_NOT_STICKY
            }

            ACTION_DNS_STOP -> {
                stopDns(null)
                if (handle == -1) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    /** DNS-only VPN: see [DnsForwarder]. A running tunnel already applies the DNS itself. */
    private suspend fun bringUpDns(dns: String) {
        if (handle != -1) return
        val servers = dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (servers.isEmpty()) return stopDns("No DNS server given")
        dnsForwarder?.stop()
        dnsForwarder = null
        val tun = try {
            Builder()
                .setSession("DNS " + servers.first())
                .addAddress(DnsForwarder.VIRTUAL_ADDRESS, 32)
                .addDnsServer(DnsForwarder.VIRTUAL_DNS)
                .addRoute(DnsForwarder.VIRTUAL_DNS, 32)
                // Everything that is not a DNS query keeps its normal path.
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
                .setMtu(1500)
                .setBlocking(true)
                .setConfigureIntent(activityIntent())
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
                .establish()
        } catch (e: Exception) {
            null
        } ?: return stopDns("Could not start DNS mode — allow the VPN request and try again")
        dnsForwarder = withContext(Dispatchers.IO) { DnsForwarder(this@WhiteGameVpnService, tun, servers).also { it.start() } }
        systemDns = dns
        TunnelBus.emit(TunnelBus.Event.SystemDns(dns))
        updateNotification("DNS " + servers.first(), "Applied to every app · closes with the app")
    }

    /** Ends DNS-only mode; [error] non-null reports why it could not stay up. */
    private fun stopDns(error: String?) {
        val f = dnsForwarder
        dnsForwarder = null
        f?.stop()
        if (f != null || error != null) {
            systemDns = null
            TunnelBus.emit(TunnelBus.Event.SystemDns(null, error.orEmpty()))
        }
        if (error != null && handle == -1) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Swiping the app away ends DNS-only mode, so DNS goes back to Android's own. A tunnel is a
     * deliberate connection and stays up, as before.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (dnsForwarder != null && handle == -1) {
            stopDns(null)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun bringUp(tunnel: SavedTunnel, killSwitch: Boolean, dnsOverride: String?) {
        lastError = ""
        if (!GoBackend.isAvailable) {
            fail("Tunnel engine unavailable: " + GoBackend.loadError.ifBlank { "libwg-go.so missing" })
            return
        }
        // The tunnel carries the DNS choice itself; the DNS-only VPN would only be revoked.
        stopDns(null)
        // Switching profiles while one is up must replace it, not be ignored.
        if (handle != -1) {
            Log.i(TAG, "replacing the running tunnel")
            val old = handle
            handle = -1
            statsJob?.cancel()
            runCatching { GoBackend.awgTurnOff(old) }
        }

        val config = try {
            tunnel.parse()
        } catch (e: ConfigException) {
            fail("Config error: " + (e.message ?: "invalid"))
            return
        }

        // The engine's UAPI only accepts IP literals, so endpoints must resolve first.
        // Mobile networks often need a moment after handover before DNS answers.
        val resolved = mutableMapOf<String, InetAddress>()
        for (peer in config.peers) {
            val host = peer.endpoint?.let { AwgConfig.splitHostPort(it).first } ?: continue
            var addr: InetAddress? = null
            for (attempt in 0 until DNS_RETRIES) {
                addr = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                }
                if (addr != null) break
                delay(1000)
            }
            if (addr == null) {
                fail("Cannot resolve " + host + " — check your internet connection")
                return
            }
            resolved[host] = addr
        }

        val uapi = try {
            config.toUapi { resolved[it] }
        } catch (e: ConfigException) {
            fail("Config error: " + (e.message ?: "invalid"))
            return
        }

        val builder = Builder()
        builder.setSession(tunnel.name)

        try {
            for (pkg in config.iface.excludedApplications) {
                runCatching { builder.addDisallowedApplication(pkg) }
            }
            for (pkg in config.iface.includedApplications) {
                runCatching { builder.addAllowedApplication(pkg) }
            }

            for (addr in config.iface.addresses) {
                val (ip, prefix) = splitCidr(addr) ?: continue
                builder.addAddress(ip, prefix)
            }

            val dnsServers = dnsOverride?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() } ?: config.iface.dnsServers
            for (dns in dnsServers) runCatching { builder.addDnsServer(dns) }
            for (domain in config.iface.dnsSearchDomains) runCatching { builder.addSearchDomain(domain) }

            var sawDefaultRoute = false
            for (peer in config.peers) {
                for (allowed in peer.allowedIps) {
                    val (ip, prefix) = splitCidr(allowed) ?: continue
                    if (prefix == 0) sawDefaultRoute = true
                    runCatching { builder.addRoute(ip, prefix) }
                }
            }

            // Kill-switch semantics: with a single default-route peer the tunnel captures
            // everything, so leaving both families unallowed drops traffic when it is down.
            // Otherwise, let traffic outside the tunnel's routes keep flowing.
            val lockdown = killSwitch || (sawDefaultRoute && config.peers.size == 1)
            if (!lockdown) {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            }

            builder.setMtu(config.iface.mtu ?: 1280)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setUnderlyingNetworks(null)
            builder.setBlocking(true)
            builder.setConfigureIntent(activityIntent())
        } catch (e: Exception) {
            fail("Cannot configure tunnel: " + (e.message ?: e.javaClass.simpleName))
            return
        }

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            fail("VPN establish failed: " + (e.message ?: e.javaClass.simpleName))
            return
        }
        if (tun == null) {
            fail("VPN permission was revoked")
            return
        }

        // The engine takes ownership of the descriptor and closes it on turn-off.
        val newHandle = try {
            GoBackend.awgTurnOn(tunnel.name, tun.detachFd(), uapi)
        } catch (t: Throwable) {
            fail("Engine error: " + (t.message ?: t.javaClass.simpleName))
            return
        }
        if (newHandle < 0) {
            fail("Engine rejected the configuration (code " + newHandle + ")")
            return
        }
        handle = newHandle

        // Keep the engine's own UDP socket outside the tunnel, or packets loop forever.
        runCatching { GoBackend.awgGetSocketV4(handle).takeIf { it > 0 }?.let { protect(it) } }
        runCatching { GoBackend.awgGetSocketV6(handle).takeIf { it > 0 }?.let { protect(it) } }

        store.markUsed(tunnel.id)
        isRunning = true
        activeTunnelId = tunnel.id
        activeTunnelName = tunnel.name
        activeKind = config.kind
        lastError = ""
        Log.i(TAG, "up: " + tunnel.name + " (" + config.kind.label + ") engine=" + GoBackend.versionOrError())
        TunnelBus.emit(TunnelBus.Event.Connected(tunnel.id, tunnel.name))
        updateNotification(tunnel.name, "Connected")
        startStatsJob(tunnel.name)
    }

    /** Polls the engine for byte counters and handshake time; also drives the notification text. */
    private fun startStatsJob(name: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastRx = 0L
            var lastTx = 0L
            var lastAt = System.currentTimeMillis()
            while (isActive && handle != -1) {
                val stats = readStats()
                if (stats != null) {
                    val now = System.currentTimeMillis()
                    val dt = ((now - lastAt).coerceAtLeast(1)).toDouble() / 1000.0
                    val withRates =
                        if (lastRx == 0L && lastTx == 0L) stats
                        else stats.copy(
                            rxBps = (((stats.rxBytes - lastRx).coerceAtLeast(0)) / dt).toLong(),
                            txBps = (((stats.txBytes - lastTx).coerceAtLeast(0)) / dt).toLong()
                        )
                    lastRx = stats.rxBytes
                    lastTx = stats.txBytes
                    lastAt = now
                    TunnelBus.emit(TunnelBus.Event.Stats(withRates))
                    updateNotification(
                        name,
                        if (!withRates.hasHandshake) "Handshaking…"
                        else if (withRates.isHealthy) "Connected · " + formatBytes(withRates.rxBytes) +
                            " in · " + formatBytes(withRates.txBytes) + " out"
                        // Stale only means nothing has been sent lately; the controller decides
                        // whether the path is actually dead.
                        else "Connected · idle"
                    )
                }
                delay(2000)
            }
        }
    }

    private fun readStats(): TunnelStats? {
        val h = handle
        if (h == -1) return null
        val text = runCatching { GoBackend.awgGetConfig(h) }.getOrNull() ?: return null
        var rx = 0L
        var tx = 0L
        var handshake = 0L
        for (line in text.lineSequence()) {
            val key = line.substringBefore('=', "")
            val value = line.substringAfter('=', "")
            when (key) {
                "rx_bytes" -> rx += value.toLongOrNull() ?: 0
                "tx_bytes" -> tx += value.toLongOrNull() ?: 0
                "last_handshake_time_sec" -> {
                    val v = value.toLongOrNull() ?: 0
                    if (v > handshake) handshake = v
                }
            }
        }
        return TunnelStats(rx, tx, handshake)
    }

    private fun bringDown(reason: String, stopRequested: Boolean = false) {
        connectJob?.cancel()
        connectJob = null
        statsJob?.cancel()
        statsJob = null
        val h = handle
        handle = -1
        isRunning = false
        activeTunnelId = ""
        activeTunnelName = ""
        activeKind = null
        if (h != -1) {
            runCatching { GoBackend.awgTurnOff(h) }
        }
        if (h != -1 || stopRequested)
            TunnelBus.emit(TunnelBus.Event.Disconnected(reason, stopRequested))
        // External stops must be reported even during DNS lookup, before a handle exists.
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        lastError = message
        isRunning = false
        activeTunnelId = ""
        activeTunnelName = ""
        activeKind = null
        TunnelBus.emit(TunnelBus.Event.Failed(message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Android may invoke this on a Binder thread.
        scope.launch {
            if (dnsForwarder != null) stopDns(null)
            bringDown("VPN permission revoked", stopRequested = true)
            ServiceCompat.stopForeground(this@WhiteGameVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopDns(null)
        bringDown("Service stopped")
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(title: String, text: String): Notification {
        ensureChannel()
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WhiteGameVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentIntent(activityIntent())
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "WhiteGameVpn"
        private const val DNS_RETRIES = 3

        const val ACTION_CONNECT = "com.whitegame.app.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.whitegame.app.VPN_DISCONNECT"
        const val ACTION_DNS_START = "com.whitegame.app.DNS_START"
        const val ACTION_DNS_STOP = "com.whitegame.app.DNS_STOP"
        const val EXTRA_TUNNEL_ID = "tunnel_id"
        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_DNS = "dns"
        private const val EXTRA_STOP_REQUESTED = "external_stop_requested"
        const val CHANNEL_ID = "whitegame_tunnel"
        const val NOTIF_ID = 42

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastError: String = ""
            private set
        @Volatile var activeTunnelId: String = ""
            private set
        @Volatile var activeTunnelName: String = ""
            private set
        @Volatile var activeKind: TunnelKind? = null
            private set
        /** The DNS applied system-wide by DNS-only mode, null when that mode is off. */
        @Volatile var systemDns: String? = null
            private set

        fun dnsStartIntent(ctx: Context, dns: String): Intent =
            Intent(ctx, WhiteGameVpnService::class.java).setAction(ACTION_DNS_START).putExtra(EXTRA_DNS, dns)

        fun dnsStopIntent(ctx: Context): Intent =
            Intent(ctx, WhiteGameVpnService::class.java).setAction(ACTION_DNS_STOP)

        fun connectIntent(ctx: Context, tunnelId: String, killSwitch: Boolean, dns: String?): Intent =
            Intent(ctx, WhiteGameVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_TUNNEL_ID, tunnelId)
                putExtra(EXTRA_KILL_SWITCH, killSwitch)
                putExtra(EXTRA_DNS, dns)
            }

        fun disconnectIntent(ctx: Context): Intent =
            Intent(ctx, WhiteGameVpnService::class.java).apply {
                action = ACTION_DISCONNECT
                // The controller already records its own stop intent. A delayed event must
                // not cancel a newer explicit connect request.
                putExtra(EXTRA_STOP_REQUESTED, false)
            }

        fun splitCidr(value: String): Pair<String, Int>? {
            val ip = value.substringBefore('/').trim()
            if (ip.isEmpty()) return null
            val v6 = ip.contains(':')
            val prefix = value.substringAfter('/', "").trim().toIntOrNull()
                ?: (if (v6) 128 else 32)
            return ip to prefix
        }

        fun formatBytes(b: Long): String = when {
            b >= 1_073_741_824 -> String.format("%.1f GB", b / 1_073_741_824.0)
            b >= 1_048_576 -> String.format("%.1f MB", b / 1_048_576.0)
            b >= 1024 -> String.format("%.0f KB", b / 1024.0)
            else -> b.toString() + " B"
        }
    }
}
