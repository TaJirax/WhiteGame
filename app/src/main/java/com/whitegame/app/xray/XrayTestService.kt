package com.whitegame.app.xray

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.Executors

/**
 * Xray measurements for the app, run in the `:xray` process next to [XrayVpnService]:
 *  - a one-shot real delay through one config (the Configs test),
 *  - a long-lived core exposing one local SOCKS5 port per config (live game comparisons).
 *
 * Plain Messenger protocol; [XrayBridge] is the only client.
 */
class XrayTestService : Service() {

    private val thread = HandlerThread("xray-test").apply { start() }
    private val work = Executors.newSingleThreadExecutor()
    private var socksCore: CoreController? = null

    private val messenger = Messenger(object : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            val data = Bundle(msg.data)
            val what = msg.what
            work.execute {
                val result = Bundle().apply { putLong(KEY_REQUEST, data.getLong(KEY_REQUEST)) }
                try {
                    Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                    when (what) {
                        MSG_DELAY -> result.putLong(KEY_MS, Libv2ray.measureOutboundDelay(
                            data.getString(KEY_CONFIG).orEmpty(), data.getString(KEY_URL) ?: XrayConfig.DELAY_URL
                        ))
                        MSG_SOCKS_START -> synchronized(this@XrayTestService) {
                            socksCore?.let { runCatching { it.stopLoop() } }
                            socksCore = Libv2ray.newCoreController(Quiet).also {
                                it.startLoop(data.getString(KEY_CONFIG).orEmpty(), 0)
                            }
                        }
                        MSG_SOCKS_STOP -> synchronized(this@XrayTestService) {
                            socksCore?.let { runCatching { it.stopLoop() } }
                            socksCore = null
                        }
                    }
                } catch (t: Throwable) {
                    result.putString(KEY_ERROR, shorten(t.message ?: t.javaClass.simpleName))
                }
                runCatching { replyTo.send(Message.obtain(null, what).apply { this.data = result }) }
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(this) {
            socksCore?.let { runCatching { it.stopLoop() } }
            socksCore = null
        }
        return false
    }

    override fun onDestroy() {
        synchronized(this) { socksCore?.let { runCatching { it.stopLoop() } }; socksCore = null }
        work.shutdownNow()
        thread.quitSafely()
        super.onDestroy()
    }

    /** Go errors chain every layer ("dial tcp …: i/o timeout"); the last part is the useful one. */
    private fun shorten(message: String): String =
        message.substringAfterLast(" > ").substringAfterLast(": ").take(90).ifBlank { message.take(90) }

    private object Quiet : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long = 0
        override fun shutdown(): Long = 0
        override fun startup(): Long = 0
    }

    companion object {
        const val MSG_DELAY = 1
        const val MSG_SOCKS_START = 2
        const val MSG_SOCKS_STOP = 3
        const val KEY_REQUEST = "req"
        const val KEY_CONFIG = "config"
        const val KEY_URL = "url"
        const val KEY_MS = "ms"
        const val KEY_ERROR = "error"
    }
}
