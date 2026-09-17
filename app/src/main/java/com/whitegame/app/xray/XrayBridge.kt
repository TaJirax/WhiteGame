package com.whitegame.app.xray

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import com.whitegame.app.connection.TunnelBus
import com.whitegame.app.connection.TunnelStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's side of the `:xray` process. Turns [XrayVpnService] broadcasts into the same
 * [TunnelBus] events the WireGuard service emits, so one controller drives both, and gives
 * suspend access to [XrayTestService].
 */
object XrayBridge {
    const val ACTION_EVENT = "com.whitegame.app.XRAY_EVENT"
    const val EXTRA_EVENT = "event"
    const val EVENT_CONNECTED = "connected"
    const val EVENT_DISCONNECTED = "disconnected"
    const val EVENT_FAILED = "failed"
    const val EVENT_STATS = "stats"
    const val EVENT_PROBLEM = "problem"
    const val EXTRA_ID = "id"
    const val EXTRA_USER = "user"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_RX = "rx"
    const val EXTRA_TX = "tx"
    const val EXTRA_HANDSHAKE = "handshake"
    const val EXTRA_LATENCY = "latency"

    /** The bundled core targets Android 7.0+. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 24

    const val UNSUPPORTED = "Xray needs Android 7.0 or newer"

    @Volatile var isRunning = false
        private set
    @Volatile var latencyMs: Long? = null
        private set
    @Volatile var lastError = ""
        private set

    private var registered = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L

    fun register(context: Context) {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) = onEvent(intent)
            },
            IntentFilter(ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun onEvent(intent: Intent) {
        when (intent.getStringExtra(EXTRA_EVENT)) {
            EVENT_CONNECTED -> {
                isRunning = true
                lastError = ""
                lastRx = 0; lastTx = 0; lastAt = 0
                TunnelBus.emit(TunnelBus.Event.Connected(intent.getStringExtra(EXTRA_ID).orEmpty(), "", xray = true))
            }
            EVENT_DISCONNECTED -> {
                // Stops the app asked for are already reflected; only report outside ones
                // (the notification's Disconnect, another VPN taking over).
                if (!isRunning) return
                isRunning = false
                latencyMs = null
                TunnelBus.emit(TunnelBus.Event.Disconnected("Disconnected", intent.getBooleanExtra(EXTRA_USER, false), xray = true))
            }
            EVENT_FAILED -> {
                isRunning = false
                latencyMs = null
                lastError = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                TunnelBus.emit(TunnelBus.Event.Failed(lastError, xray = true))
            }
            EVENT_PROBLEM -> lastError = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
            EVENT_STATS -> {
                if (!isRunning) return
                val rx = intent.getLongExtra(EXTRA_RX, 0)
                val tx = intent.getLongExtra(EXTRA_TX, 0)
                val now = System.currentTimeMillis()
                val dt = ((now - lastAt).coerceAtLeast(1)) / 1000.0
                val rates = lastAt != 0L
                val stats = TunnelStats(
                    rxBytes = rx,
                    txBytes = tx,
                    lastHandshakeSec = intent.getLongExtra(EXTRA_HANDSHAKE, 0),
                    rxBps = if (rates) ((rx - lastRx).coerceAtLeast(0) / dt).toLong() else 0,
                    txBps = if (rates) ((tx - lastTx).coerceAtLeast(0) / dt).toLong() else 0
                )
                lastRx = rx; lastTx = tx; lastAt = now
                latencyMs = intent.getLongExtra(EXTRA_LATENCY, -1).takeIf { it >= 0 }
                TunnelBus.emit(TunnelBus.Event.Stats(stats, xray = true))
            }
        }
    }

    fun connect(context: Context, id: String, name: String, parsed: XrayConfig.Parsed, dns: List<String>) {
        register(context)
        if (!isSupported) {
            lastError = UNSUPPORTED
            TunnelBus.emit(TunnelBus.Event.Failed(UNSUPPORTED, xray = true))
            return
        }
        lastError = ""
        val intent = XrayVpnService.connectIntent(context, id, name, XrayConfig.tunConfig(parsed, dns), dns)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun disconnect(context: Context, user: Boolean) {
        isRunning = false
        latencyMs = null
        runCatching { context.startService(XrayVpnService.disconnectIntent(context, user)) }
    }

    // ------------------------------------------------------------------ tests

    private var messenger: Messenger? = null
    private var binding: CompletableDeferred<Messenger?>? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Bundle>>()
    private val ids = AtomicLong()

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val data = msg.data ?: return
            pending.remove(data.getLong(XrayTestService.KEY_REQUEST))?.complete(data)
        }
    })

    private suspend fun service(context: Context): Messenger? {
        messenger?.let { return it }
        val wait = binding ?: CompletableDeferred<Messenger?>().also { d ->
            binding = d
            val ok = context.applicationContext.bindService(
                Intent(context, XrayTestService::class.java),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        messenger = binder?.let(::Messenger)
                        d.complete(messenger)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        // The :xray process died; the next call binds again.
                        messenger = null
                        binding = null
                        pending.values.forEach { it.complete(Bundle().apply { putString(XrayTestService.KEY_ERROR, "Xray process stopped") }) }
                        pending.clear()
                    }
                },
                Context.BIND_AUTO_CREATE
            )
            if (!ok) {
                binding = null
                d.complete(null)
            }
        }
        return withTimeoutOrNull(8000) { wait.await() }
    }

    private suspend fun call(context: Context, what: Int, config: String, timeoutMs: Long): Bundle {
        if (!isSupported) return Bundle().apply { putString(XrayTestService.KEY_ERROR, UNSUPPORTED) }
        val m = service(context) ?: return Bundle().apply { putString(XrayTestService.KEY_ERROR, "Xray test service unavailable") }
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Bundle>()
        pending[id] = reply
        val msg = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putLong(XrayTestService.KEY_REQUEST, id)
                putString(XrayTestService.KEY_CONFIG, config)
            }
            replyTo = replies
        }
        try {
            m.send(msg)
        } catch (e: Exception) {
            pending.remove(id)
            messenger = null
            binding = null
            return Bundle().apply { putString(XrayTestService.KEY_ERROR, "Xray process not reachable") }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { reply.await() }
                ?: Bundle().apply { putString(XrayTestService.KEY_ERROR, "timed out") }
        } finally { pending.remove(id) }
    }

    /** Real HTTPS round trip through [parsed]. Failure carries the core's reason. */
    suspend fun delay(context: Context, parsed: XrayConfig.Parsed): Result<Long> {
        val r = call(context, XrayTestService.MSG_DELAY, XrayConfig.delayConfig(parsed), 30_000)
        val error = r.getString(XrayTestService.KEY_ERROR)
        return if (error == null && r.getLong(XrayTestService.KEY_MS, -1) >= 0) Result.success(r.getLong(XrayTestService.KEY_MS))
        else Result.failure(IllegalStateException(error ?: "no response"))
    }

    /** Starts one SOCKS5 port per config; returns null on success or the core's error. */
    suspend fun startSocks(context: Context, proxies: List<Pair<Int, XrayConfig.Parsed>>): String? =
        call(context, XrayTestService.MSG_SOCKS_START, XrayConfig.socksConfig(proxies), 15_000)
            .getString(XrayTestService.KEY_ERROR)

    suspend fun stopSocks(context: Context) {
        if (messenger == null) return
        call(context, XrayTestService.MSG_SOCKS_STOP, "", 5_000)
    }
}
