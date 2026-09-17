package com.whitegame.app.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.whitegame.app.MainActivity
import com.whitegame.app.R
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Full-device tunnel through the bundled Xray core.
 *
 * Runs in the `:xray` process. The AmneziaWG engine is a Go shared library too, and two Go
 * runtimes in one process is unsupported territory (signal handlers, thread state), so the
 * Xray core never loads next to it. State goes back to the app as broadcasts, which
 * [XrayBridge] turns into the same TunnelBus events the WireGuard service emits.
 */
class XrayVpnService : VpnService() {

    // One thread for lifecycle, so connect / disconnect / revoke never interleave.
    private val lifecycle = Executors.newSingleThreadExecutor()
    private val monitor = java.util.concurrent.ScheduledThreadPoolExecutor(2)
    private val monitorTasks = mutableListOf<ScheduledFuture<*>>()

    private var controller: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunnelId = ""
    private var tunnelName = ""

    @Volatile private var rx = 0L
    @Volatile private var tx = 0L
    @Volatile private var lastOkSec = 0L
    @Volatile private var latency = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Xray" }
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val dns = intent.getStringArrayListExtra(EXTRA_DNS).orEmpty()
                startForeground(NOTIF_ID, notification(name, "Connecting…"))
                lifecycle.execute { bringUp(id, name, config, dns) }
            }
            ACTION_DISCONNECT -> lifecycle.execute {
                bringDown()
                emit(XrayBridge.EVENT_DISCONNECTED) { putExtra(XrayBridge.EXTRA_USER, intent.getBooleanExtra(EXTRA_USER, true)) }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun bringUp(id: String, name: String, config: String, dns: List<String>) {
        bringDown()
        tunnelId = id
        tunnelName = name
        rx = 0; tx = 0; lastOkSec = 0; latency = -1

        val core = try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            Libv2ray.newCoreController(Callbacks)
        } catch (t: Throwable) {
            return fail("Xray core unavailable on this device: " + (t.message ?: t.javaClass.simpleName))
        }

        val pfd = try {
            Builder().apply {
                setSession(name)
                setMtu(MTU)
                addAddress("172.19.0.1", 30)
                addRoute("0.0.0.0", 0)
                addAddress("fdfe:dcba:9876::1", 126)
                addRoute("::", 0)
                (dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }).forEach { runCatching { addDnsServer(it) } }
                // The core's own sockets must leave outside the tunnel. Excluding the app does
                // that for every one of them without protecting sockets one by one.
                addDisallowedApplication(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
                setConfigureIntent(openApp())
            }.establish()
        } catch (e: Exception) {
            return fail("VPN establish failed: " + (e.message ?: e.javaClass.simpleName))
        } ?: return fail("VPN permission is not granted")

        try {
            core.startLoop(config, pfd.fd)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            return fail("Xray rejected the config: " + (t.message ?: t.javaClass.simpleName))
        }
        controller = core
        tun = pfd
        Log.i(TAG, "up: $name")
        emit(XrayBridge.EVENT_CONNECTED) { putExtra(XrayBridge.EXTRA_ID, id) }
        updateNotification(name, "Checking the path…")
        startMonitoring()
    }

    /**
     * Counters every 2s; a real HTTPS round trip through the core every 5s. That round trip is
     * the "handshake" the controller waits for: Xray has no handshake of its own to read.
     */
    private fun startMonitoring() {
        monitorTasks += monitor.scheduleWithFixedDelay({
            val c = controller ?: return@scheduleWithFixedDelay
            runCatching {
                c.queryAllOutboundTrafficStats().split(';').forEach { entry ->
                    val parts = entry.split(',')
                    if (parts.size == 3 && parts[0] == "proxy") {
                        val v = parts[2].trim().toLongOrNull() ?: 0L
                        if (parts[1] == "downlink") rx += v else if (parts[1] == "uplink") tx += v
                    }
                }
            }
            emit(XrayBridge.EVENT_STATS) {
                putExtra(XrayBridge.EXTRA_RX, rx)
                putExtra(XrayBridge.EXTRA_TX, tx)
                putExtra(XrayBridge.EXTRA_HANDSHAKE, lastOkSec)
                putExtra(XrayBridge.EXTRA_LATENCY, latency)
            }
        }, 1, 2, TimeUnit.SECONDS)

        monitorTasks += monitor.scheduleWithFixedDelay({
            val c = controller ?: return@scheduleWithFixedDelay
            try {
                latency = c.measureDelay(XrayConfig.DELAY_URL)
                lastOkSec = System.currentTimeMillis() / 1000
                updateNotification(tunnelName, "Connected · ${latency}ms")
            } catch (t: Throwable) {
                latency = -1
                emit(XrayBridge.EVENT_PROBLEM) { putExtra(XrayBridge.EXTRA_MESSAGE, "No response through the server: " + (t.message ?: "timeout")) }
                if (lastOkSec == 0L) updateNotification(tunnelName, "Waiting for the server…")
            }
        }, 0, 5, TimeUnit.SECONDS)
    }

    private fun bringDown() {
        monitorTasks.forEach { it.cancel(false) }
        monitorTasks.clear()
        controller?.let { runCatching { it.stopLoop() } }
        controller = null
        // Close the descriptor only after the core let go of it.
        tun?.let { runCatching { it.close() } }
        tun = null
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        bringDown()
        emit(XrayBridge.EVENT_FAILED) { putExtra(XrayBridge.EXTRA_MESSAGE, message) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        lifecycle.execute {
            bringDown()
            // Another VPN (the WireGuard service or the DNS mode included) took over.
            emit(XrayBridge.EVENT_DISCONNECTED) { putExtra(XrayBridge.EXTRA_USER, false) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        lifecycle.execute { bringDown() }
        lifecycle.shutdown()
        monitor.shutdownNow()
        super.onDestroy()
    }

    private fun emit(event: String, extras: Intent.() -> Unit = {}) {
        sendBroadcast(Intent(XrayBridge.ACTION_EVENT).setPackage(packageName)
            .putExtra(XrayBridge.EXTRA_EVENT, event).apply(extras))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, XrayVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentIntent(openApp())
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text)) }
    }

    /** The core reports lifecycle through this; the service already tracks all of it. */
    private object Callbacks : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long = 0
        override fun shutdown(): Long = 0
        override fun startup(): Long = 0
    }

    companion object {
        private const val TAG = "XrayVpn"
        private const val MTU = 1500
        const val ACTION_CONNECT = "com.whitegame.app.XRAY_CONNECT"
        const val ACTION_DISCONNECT = "com.whitegame.app.XRAY_DISCONNECT"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_DNS = "dns"
        const val EXTRA_USER = "user"
        private const val CHANNEL_ID = "whitegame_tunnel"
        private const val NOTIF_ID = 43

        fun connectIntent(ctx: Context, id: String, name: String, config: String, dns: List<String>): Intent =
            Intent(ctx, XrayVpnService::class.java).setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name).putExtra(EXTRA_CONFIG, config)
                .putStringArrayListExtra(EXTRA_DNS, ArrayList(dns))

        fun disconnectIntent(ctx: Context, user: Boolean): Intent =
            Intent(ctx, XrayVpnService::class.java).setAction(ACTION_DISCONNECT).putExtra(EXTRA_USER, user)
    }
}
