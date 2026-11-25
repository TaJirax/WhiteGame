package com.whitegame.app.data

import android.content.Context
import com.whitegame.app.model.SubNode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the user's own subscription. There is deliberately no built-in list: a hard-coded
 * third-party link hands people servers nobody here controls, and the keys it carries are
 * useless the day that link rots — which is exactly what happened to the old one.
 *
 * Understands what a WireGuard subscription actually ships:
 *  - one or more `[Interface]` blocks (plain .conf text),
 *  - sing-box JSON (`endpoints` / `outbounds` of type wireguard),
 *  - Xray share links (vless/vmess/trojan/ss/hysteria2), one per line,
 *  - either of those wrapped in base64,
 *  - bare `host:port` lines, which can only be ranked by ping, not connected to.
 */
@Singleton
class ConfigSubscription @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("wg_subscription", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    fun fetch(from: String = url): List<SubNode> = download(from)

    companion object {
        private const val KEY_URL = "sub_url"

        internal fun download(from: String): List<SubNode> = SubscriptionSource.download(from)

        fun parse(body: String, depth: Int = 0): List<SubNode> = SubscriptionSource.parse(body, depth)
    }
}
