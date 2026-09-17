package com.whitegame.app.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.data.AssetLoader
import com.whitegame.app.data.PlayCache
import com.whitegame.app.data.PlayInfo
import com.whitegame.app.data.PlayStore
import com.whitegame.app.data.RemoteCatalog
import com.whitegame.app.data.RemoteEndpoints
import com.whitegame.app.model.GameInfo
import com.whitegame.app.network.NetworkProber
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** How an installed app was recognised as a game — shown, so a wrong guess is explainable. */
enum class GameSource(val label: String) {
    CATALOG("In catalog"),
    PLAY("Google Play"),
    DEVICE("Detected")
}

/**
 * A game installed on this phone. [gameId] points into the games list when there are servers
 * to test (catalog entry or Play publisher hosts); null means installed but nothing to ping yet.
 */
data class DeviceGame(
    val packageName: String,
    val label: String,
    val gameId: String?,
    val source: GameSource,
    val genre: String = ""
) {
    /** Kept for callers that only care whether a catalog/test entry exists. */
    val catalogId: String? get() = gameId
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/**
 * "PUBG MOBILE" is "PUBG Mobile"; "Call of Duty" finds "Call of Duty: Mobile". Containment needs
 * five letters so short labels like "Go" do not match everything.
 * ponytail: substring match, swap for a package-name table if false matches show up.
 */
internal fun matchByName(label: String, catalog: List<GameInfo>): String? {
    val l = norm(label)
    if (l.length < 2) return null
    catalog.firstOrNull { norm(it.name) == l }?.let { return it.id }
    return catalog.firstOrNull {
        val n = norm(it.name)
        minOf(n.length, l.length) >= 5 && (n.contains(l) || l.contains(n))
    }?.id
}

/** Package prefixes of studios that only publish games — no network lookup needed for these. */
internal val GAME_PUBLISHERS = listOf(
    "com.tencent.ig", "com.tencent.tmgp", "com.tencent.lolm", "com.pubg.", "com.krafton.", "com.proximabeta.",
    "com.levelinfinite.", "com.garena.", "com.dts.", "com.activision.", "com.ea.", "com.gameloft.",
    "com.supercell.", "com.king.", "com.mihoyo.", "com.hoyoverse.", "com.cognosphere.", "com.netease.",
    "com.riotgames.", "com.epicgames.", "com.roblox.", "com.mojang.", "com.innersloth.", "com.nexon.",
    "com.ncsoft.", "com.netmarble.", "com.com2us.", "com.bandainamcoent.", "com.square_enix.", "com.sega.",
    "jp.konami.", "com.konami.", "com.kiloo.", "com.outfit7.", "com.miniclip.", "com.playrix.",
    "com.moonactive.", "com.zynga.", "com.igg.", "com.lilithgame.", "com.lilithgames.", "com.funplus.",
    "com.yostar", "com.nianticlabs.", "com.scopely.", "com.glu.", "com.halfbrick.", "com.rovio.",
    "com.voodoo.", "com.fingersoft.", "com.imangi.", "com.sybo.", "com.ubisoft.", "com.gravity.",
    "com.wemade.", "com.pearlabyss.", "com.kurogame.", "com.farlightgames.", "com.moonton.",
    "com.mobile.legends", "com.nintendo.", "com.firsttouchgames.", "com.axlebolt.", "com.chillyroom.",
    "com.miniclip.", "com.gamedevltd.", "com.pixel.gun3d", "com.vng.", "com.habby.", "com.tapblaze.",
    "com.kabam.", "com.jagex.", "com.blizzard.", "com.bethsoft.", "com.valvesoftware.", "com.tencent.tmgp"
)

internal fun isPublisherGame(pkg: String): Boolean {
    val p = pkg.lowercase()
    return GAME_PUBLISHERS.any { p.startsWith(it) }
}

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val assetLoader: AssetLoader,
    private val remoteCatalog: RemoteCatalog,
    private val tunnelStore: com.whitegame.app.connection.TunnelStore,
    private val tunnelController: com.whitegame.app.connection.TunnelController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val catalogPrefs = context.getSharedPreferences("catalog_settings", Context.MODE_PRIVATE)
    var catalogUrl by mutableStateOf(catalogPrefs.getString("url", "").orEmpty())
        private set
    fun updateCatalogUrl(value: String) {
        val clean = value.trim()
        if (clean.isNotEmpty() && runCatching { java.net.URI(clean).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false).not()) {
            updateMessage = "Enter a valid HTTPS catalog URL"
            return
        }
        catalogUrl = clean
        RemoteEndpoints.gamesUrl = clean
        catalogPrefs.edit().putString("url", clean).apply()
        updateMessage = if (clean.isEmpty()) "Using Google Play and the bundled catalog" else "Catalog URL saved. Tap Update to fetch games."
    }
    private val playCache = PlayCache(context)
    private var catalog: List<GameInfo> = emptyList()

    var games by mutableStateOf<List<GameInfo>>(emptyList())
        private set
    var deviceGames by mutableStateOf<List<DeviceGame>>(emptyList())
        private set
    var installedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var results by mutableStateOf<Map<String, Pair<String, String>>>(emptyMap())
        private set
    var busyId by mutableStateOf<String?>(null)
        private set
    var updateMessage by mutableStateOf("")
        private set
    var showInstalledOnly by mutableStateOf(true)
        private set
    var scanning by mutableStateOf(true)
        private set
    var updating by mutableStateOf(false)
        private set
    /** Package being looked up on Google Play on its own ("Find servers"). */
    var lookupPackage by mutableStateOf<String?>(null)
        private set

    private var scanJob: Job? = null

    init {
        RemoteEndpoints.gamesUrl = catalogUrl
        rescan()
    }

    fun toggleInstalledOnly() {
        showInstalledOnly = !showInstalledOnly
    }

    /** Cheap and silent: runs on every return to the screen, so a newly installed game appears. */
    fun rescan(quiet: Boolean = false) {
        if (scanJob?.isActive == true || updating || lookupPackage != null) return
        if (!quiet) scanning = true
        scanJob = viewModelScope.launch {
            try {

            val (list, found) = withContext(Dispatchers.IO) {
                catalog = assetLoader.loadGames()
                scanDevice()
            }
            games = list
            deviceGames = found
            installedIds = found.mapNotNull { it.gameId }.toSet()
            scanning = false
            if (!updating) updateMessage = summary()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage = "Could not refresh games: " + (e.message ?: "try again")
            } finally {
                scanning = false
            }
        }
    }

    /**
     * The Update button: rescans, then asks Google Play about installed apps nothing else could
     * classify — its genre says "game", its publisher domains give servers to test — and pulls
     * the hosted catalog when one is configured.
     */
    fun updateGamesList() {
        if (updating || lookupPackage != null) return
        updating = true
        updateMessage = "Scanning your apps…"
        viewModelScope.launch {
            try {
                scanJob?.join()

            withContext(Dispatchers.IO) { catalog = assetLoader.loadGames() }
            val candidates = withContext(Dispatchers.IO) { playCandidates() }
            var asked = 0
            var answered = 0
            var newGames = 0
            if (candidates.isNotEmpty()) {
                updateMessage = "Checking ${candidates.size} apps on Google Play…"
                val gate = Semaphore(4)
                val answers = withContext(Dispatchers.IO) {
                    candidates.map { pkg ->
                        async { gate.withPermit { pkg to PlayStore.fetch(pkg) } }
                    }.awaitAll()
                }
                asked = answers.size
                answers.forEach { (pkg, info) ->
                    if (info != null) {
                        answered++
                        playCache.put(info)
                        if (info.isGame) newGames++
                    }
                }
            }
            val remote = if (RemoteEndpoints.gamesUrl.isBlank()) null
            else withContext(Dispatchers.IO) { remoteCatalog.updateGames() }

            val (list, found) = withContext(Dispatchers.IO) {
                catalog = assetLoader.loadGames()
                scanDevice()
            }
            games = list
            deviceGames = found
            installedIds = found.mapNotNull { it.gameId }.toSet()
            results = emptyMap()
            updateMessage = buildString {
                append(summary())
                when {
                    asked == 0 -> append(" · every app already checked")
                    answered == 0 -> append(" · Google Play did not answer (it may be blocked on this network — try with a tunnel on)")
                    else -> append(" · Google Play: $answered of $asked apps checked, $newGames games")
                }
                remote?.let { append(if (it.ok) " · catalog " + it.message else " · catalog not updated: " + it.message) }
            }
            updating = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage = "Could not refresh games: " + (e.message ?: "try again")
            } finally {
                updating = false
            }
        }
    }

    /** "Find servers" for one detected game with nothing to test yet. */
    fun lookUp(pkg: String) {
        if (lookupPackage != null || updating) return
        lookupPackage = pkg
        viewModelScope.launch {
            try {
                scanJob?.join()

            val info = withContext(Dispatchers.IO) { PlayStore.fetch(pkg) }
            if (info == null) {
                updateMessage = "Google Play did not answer for this game — it may be blocked on this network"
            } else {
                playCache.put(info)
                val (list, found) = withContext(Dispatchers.IO) { scanDevice() }
                games = list
                deviceGames = found
                installedIds = found.mapNotNull { it.gameId }.toSet()
                updateMessage = if (info.hosts.isEmpty()) "Google Play lists no publisher websites for " + info.title.ifBlank { pkg }
                else "Found publisher websites for " + info.title + ": " + info.hosts.joinToString(", ")
            }
            lookupPackage = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage = "Could not refresh games: " + (e.message ?: "try again")
            } finally {
                lookupPackage = null
            }
        }
    }

    private fun summary(): String {
        if (deviceGames.isEmpty()) return "No games found yet · ${games.size} titles in the catalog"
        val testable = deviceGames.count { it.gameId != null }
        return "${deviceGames.size} games on this phone · $testable ready to test"
    }

    private fun launcherApps(): List<ApplicationInfo> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fromLauncher = (runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()) +
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) }.getOrDefault(emptyList()))
            .map { it.activityInfo.applicationInfo }
        // Some games only expose a leanback / game launcher entry; the catalog knows them by package.
        val fromCatalog = catalog.flatMap { it.packageNames }.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        }
        return (fromLauncher + fromCatalog).distinctBy { it.packageName }.filter { it.packageName != context.packageName }
    }

    private fun ApplicationInfo.isSystem(): Boolean =
        flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    @Suppress("DEPRECATION")
    private fun ApplicationInfo.flaggedAsGame(): Boolean =
        (Build.VERSION.SDK_INT >= 26 && category == ApplicationInfo.CATEGORY_GAME) ||
            flags and ApplicationInfo.FLAG_IS_GAME != 0

    /** Installed, user-facing apps that only Google Play can classify, and games still missing servers. */
    private fun playCandidates(): List<String> = launcherApps()
        .filter { !it.isSystem() }
        .filter { app ->
            val inCatalog = catalog.any { app.packageName in it.packageNames }
            val cached = playCache.get(app.packageName)
            when {
                inCatalog -> false
                cached != null -> false
                else -> true
            }
        }
        .map { it.packageName }
        .take(MAX_PLAY_LOOKUPS)

    /** Blocking. Returns the full games list (catalog + Play entries) and the installed games. */
    private fun scanDevice(): Pair<List<GameInfo>, List<DeviceGame>> {
        val pm = context.packageManager
        val byPackage = catalog.flatMap { g -> g.packageNames.map { it to g.id } }.toMap()
        val extra = mutableListOf<GameInfo>()
        val found = launcherApps().mapNotNull { app ->
            val pkg = app.packageName
            val play = playCache.get(pkg)
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            val known = byPackage[pkg]
            val isGame = known != null || app.flaggedAsGame() || isPublisherGame(pkg) || play?.isGame == true
            if (!isGame) return@mapNotNull null
            // A system app flagged as a game is a preinstalled demo, not something the user plays.
            if (known == null && app.isSystem() && play?.isGame != true) return@mapNotNull null

            val matched = known ?: matchByName(label, catalog) ?: play?.title?.let { matchByName(it, catalog) }
            when {
                matched != null -> DeviceGame(pkg, label, matched, GameSource.CATALOG, catalog.firstOrNull { it.id == matched }?.category.orEmpty())
                play != null && play.isGame && play.hosts.isNotEmpty() -> {
                    extra += playEntry(pkg, label, play)
                    DeviceGame(pkg, label, "play:$pkg", GameSource.PLAY, play.genre)
                }
                play != null && play.isGame -> DeviceGame(pkg, label, null, GameSource.PLAY, play.genre)
                else -> DeviceGame(pkg, label, null, GameSource.DEVICE)
            }
        }.sortedWith(compareBy<DeviceGame> { it.gameId == null }.thenBy { it.label.lowercase() })
        return (catalog + extra).distinctBy { it.id } to found
    }

    private fun playEntry(pkg: String, label: String, play: PlayInfo) = GameInfo(
        id = "play:$pkg",
        name = play.title.ifBlank { label },
        category = play.genre,
        hosts = play.hosts,
        tcpPorts = listOf(443),
        udpPorts = emptyList(),
        regions = emptyList(),
        pingNeed = when (play.category) {
            "GAME_ACTION", "GAME_RACING", "GAME_SPORTS" -> "<60ms"
            "GAME_STRATEGY", "GAME_ROLE_PLAYING", "GAME_ADVENTURE" -> "<100ms"
            else -> "<150ms"
        },
        notes = "Publisher websites only; not gameplay servers",
        packageNames = listOf(pkg)
    )

    fun testGame(g: GameInfo) {
        if (busyId != null) return
        busyId = g.id
        viewModelScope.launch {
            val labelAdvice = withContext(Dispatchers.IO) {
                val hosts = g.hosts.take(4)
                val pings = mutableListOf<Long>()
                var udpBlocked = 0
                for (h in hosts) {
                    val tcp = NetworkProber.probeTcp(h, g.tcpPorts.firstOrNull() ?: 443, 3, 2000)
                    tcp.avgMs?.let { pings.add(it) }
                    if (g.udpPorts.isNotEmpty()) {
                        val udp = NetworkProber.probeUdp(h, g.udpPorts.first(), 2, 700)
                        if (udp.status == "BLOCKED") udpBlocked++
                    }
                }
                val best = pings.minOrNull()
                val avg = if (pings.isNotEmpty()) pings.sum() / pings.size else null
                val label = when {
                    best == null -> "Unreachable"
                    best < 60 && udpBlocked == 0 -> "Excellent"
                    best < 90 && udpBlocked == 0 -> "Good"
                    best < 130 -> "Playable"
                    else -> "High ping"
                }
                val advice = "Best ${best ?: "—"}ms · avg ${avg ?: "—"}ms" +
                    (if (g.udpPorts.isNotEmpty()) " · UDP blocked $udpBlocked/${hosts.size}" else "") +
                    " · needs ${g.pingNeed}"
                label to advice
            }
            results = results + (g.id to labelAdvice)
            busyId = null
        }
    }

    data class RouteRow(val label: String, val samples: List<Long?> = emptyList(), val error: String? = null) {
        val stats get() = com.whitegame.app.network.ProbeStats.from(samples)
    }
    var comparisons by mutableStateOf<List<RouteRow>>(emptyList())
        private set
    var comparisonGame by mutableStateOf<String?>(null)
        private set
    var comparisonMessage by mutableStateOf("")
        private set
    val recommendedRoute: String get() = comparisons.filter { it.error == null && it.samples.size >= 3 && it.samples.any { sample -> sample != null } }
        .minByOrNull { row ->
            val values = row.samples.filterNotNull()
            val loss = row.samples.count { it == null } * 100 / row.samples.size
            com.whitegame.app.data.DnsData.distanceScore(values.average().toLong(), loss,
                row.stats.jitterMs)
        }?.label?.let { "Best measured route: $it" } ?: if (comparisons.any { it.samples.size >= 3 } || !comparing) "No route reached this endpoint" else "Collecting samples..."
    var comparing by mutableStateOf(false)
        private set
    private var comparisonJob: Job? = null

    fun stopComparison() { comparisonJob?.cancel() }
    fun dismissComparison() {
        if (comparing) return
        comparisonGame = null
        comparisons = emptyList()
    }

    fun compareGame(game: GameInfo) {
        if (comparisonJob?.isActive == true || comparing) return
        val host = game.hosts.firstOrNull() ?: return
        val port = 443
        comparisonGame = game.name
        comparing = true
        comparisons = emptyList()
        comparisonJob = viewModelScope.launch {
            var socksStarted = false
            try {
                val dns = withContext(Dispatchers.IO) {
                    val list = assetLoader.loadDns()
                    val preferred = tunnelController.preferredDns.value?.substringBefore(',')?.trim()
                    val chosen = listOfNotNull(preferred, "1.1.1.1", "8.8.8.8", "9.9.9.9").distinct()
                    chosen.take(4).mapIndexed { i, ip -> list.firstOrNull { it.ip == ip } ?: com.whitegame.app.model.DnsItem(i, ip, ip, "") }
                }
                val configs = tunnelStore.tunnels.value.filter { it.isXray }.take(4)
                val proxies = configs.map { com.whitegame.app.network.RouteProbe.freePort() to it.parseXray() }
                val active = tunnelController.state.value.isLive && tunnelController.activeTunnel.value?.isXray != true
                comparisonMessage = "$host:$port\nTLS handshake | last 20 probes" +
                    (if (active) "\nActive VPN routing may affect these results" else "")
                val socksError = if (proxies.isEmpty()) null else {
                    socksStarted = true
                    com.whitegame.app.xray.XrayBridge.startSocks(context, proxies)
                }
                comparisons = listOf(RouteRow("Current app route")) +
                    dns.map { RouteRow("DNS " + it.ip) } + configs.map { RouteRow(it.name, error = socksError) }
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val values = withContext(Dispatchers.IO) {
                        comparisons.indices.map { i -> async {
                            when {
                                i == 0 -> com.whitegame.app.network.RouteProbe.handshakeMs(host, port)
                                i <= dns.size -> {
                                    val answer = com.whitegame.app.network.DnsClient.query(dns[i - 1].ip, host)
                                    answer?.ips?.firstOrNull()?.let { com.whitegame.app.network.RouteProbe.handshakeMs(host, port, ip = it)?.plus(answer.ms) }
                                }
                                socksError != null -> null
                                else -> com.whitegame.app.network.RouteProbe.handshakeMs(host, port, socksPort = proxies[i - dns.size - 1].first)
                            }
                        } }.awaitAll()
                    }
                    comparisons = comparisons.mapIndexed { i, row -> row.copy(samples = (row.samples + values[i]).takeLast(20)) }
                    kotlinx.coroutines.delay(2000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                comparisonMessage = "Comparison failed: " + (e.message ?: "network error")
            } finally {
                if (socksStarted) withContext(kotlinx.coroutines.NonCancellable) { com.whitegame.app.xray.XrayBridge.stopSocks(context) }
                comparing = false
            }
        }
    }

    private companion object {
        /** Bounded so one tap never turns into hundreds of requests on a phone full of apps. */
        const val MAX_PLAY_LOOKUPS = 80
    }
}
