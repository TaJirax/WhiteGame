package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.EnrollmentKey
import com.whitegame.app.connection.HostedProvisioning
import com.whitegame.app.connection.Keys
import com.whitegame.app.connection.WarpProvisioning
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.data.DnsData
import com.whitegame.app.data.SubscriptionSource
import com.whitegame.app.model.DnsItem
import com.whitegame.app.model.SubNode
import com.whitegame.app.network.CloudflareSpeedResult
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.network.RouteProbe
import com.whitegame.app.network.SpeedStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

internal class DesktopController(
    private val store: DesktopStore,
    private val releaseInstance: () -> Unit
) : DesktopActions {
    private val scope = CoroutineScope(SupervisorJob() + SwingDispatcher)
    private val tunnels = DesktopPlatform.network(store.directory.resolve("network"))
    private val window = DesktopWindow(this)
    private var operation: Job? = null
    private var applyingNetwork = false
    private var closing = false
    private var subscriptions = emptyList<SubNode>()
    private var endpointProfile: DesktopProfile? = null
    private var catalog = emptyList<WindowsGame>()
    private var installed: List<InstalledDesktopGame>? = null
    private val bundledResolvers = desktopDnsCatalog(WindowsCatalog.resolvers())
    private var state = DesktopState(
        dark = store.flag("dark", true),
        profiles = store.profiles,
        autoReconnect = store.flag("autoReconnect", true),
        killSwitch = store.flag("killSwitch", false),
        preferredDns = store.string("preferredDns").ifBlank { null },
        resolvers = bundledResolvers + store.dnsResolvers,
        subscriptionUrl = store.string("subscriptionUrl"),
        catalogUrl = store.string("catalogUrl"),
        reports = store.reports
    )

    fun show() {
        update { copy(selectedId = profiles.firstOrNull { it.id == store.string("selectedId") }?.id ?: profiles.firstOrNull()?.id) }
        window.isVisible = true
        run("Reading device information…") {
            refreshSession()
            inspect(samplePath = false)
            discoverGames()
        }
        scope.launch {
            while (true) {
                delay(4_000)
                if (state.phase.isLive && !applyingNetwork) refreshSession(samplePing = true)
            }
        }
    }

    override fun dispatch(action: DesktopAction) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { dispatch(action) }
            return
        }
        if (closing) {
            if (action is DesktopAction.SaveProfile) action.complete?.invoke("WhiteGame is closing.")
            return
        }
        when (action) {
            DesktopAction.Stop -> {
                if (applyingNetwork) notice("Waiting for the system to finish the network change.")
                else operation?.cancel()
                return
            }
            DesktopAction.Close -> {
                close()
                return
            }
            else -> Unit
        }
        if (operation?.isActive == true && action !is DesktopAction.Theme) {
            if (action is DesktopAction.SaveProfile) action.complete?.invoke("Wait for the current operation to finish.")
            return
        }
        try {
            when (action) {
                is DesktopAction.Theme -> preference("dark", action.dark) { copy(dark = action.dark) }
                is DesktopAction.SelectProfile -> {
                    if (state.profiles.any { it.id == action.id }) {
                        store.put("selectedId" to action.id)
                        update { copy(selectedId = action.id, endpointTests = emptyList()) }
                        endpointProfile = null
                    }
                }
                is DesktopAction.SaveProfile -> {
                    if (action.id != null) {
                        require(state.profiles.any { it.id == action.id }) { "This saved profile no longer exists." }
                        require(action.id != state.activeId || !state.phase.isLive) { "Disconnect this profile before editing it." }
                    }
                    saveProfile(action.config, action.name, action.id)
                    action.complete?.invoke(null)
                }
                is DesktopAction.ImportProfiles -> importProfiles(desktopConfigInputs(action.config), action.name)
                is DesktopAction.DeleteProfile -> deleteProfile(action.id)
                DesktopAction.Connect -> {
                    val profile = selected() ?: return notice("Choose a saved tunnel.")
                    run("Connecting tunnel…", networkChange = true) { connect(profile) }
                }
                DesktopAction.Disconnect -> run("Disconnecting tunnel…", networkChange = true) {
                    update { copy(phase = DesktopPhase.DISCONNECTING, connectionMessage = "Disconnecting…") }
                    try {
                        withContext(Dispatchers.IO) { tunnels.disconnect() }
                    } finally {
                        refreshSession()
                    }
                }
                is DesktopAction.AutoReconnect -> preference("autoReconnect", action.enabled) { copy(autoReconnect = action.enabled) }
                is DesktopAction.KillSwitch -> {
                    preference("killSwitch", action.enabled) { copy(killSwitch = action.enabled) }
                    if (state.phase.isLive) notice("Kill switch setting applies on the next connection.")
                }
                DesktopAction.AnalyzeNetwork -> run("Analyzing the network…") { analyzeNetwork() }
                DesktopAction.SpeedTest -> run("Measuring Cloudflare speed…") { speedTest() }
                is DesktopAction.TestConfigs -> run("Preparing config tests…") { testConfigs(action.config) }
                is DesktopAction.TestDns -> run("Measuring DNS…") { testDns(action) }
                is DesktopAction.SaveDnsResolver -> saveDnsResolver(action)
                is DesktopAction.DeleteDnsResolver -> deleteDnsResolver(action.id)
                is DesktopAction.ApplyDns -> run("Applying system DNS…", networkChange = true) { applyDns(action.address) }
                DesktopAction.DiscoverGames -> run("Scanning this PC for games…") { discoverGames() }
                is DesktopAction.TestGame -> run("Measuring game HTTPS…") { testGame(action.id, false) }
                is DesktopAction.CompareGame -> run("Measuring game routes…") { testGame(action.id, true) }
                is DesktopAction.UpdateCatalog -> run("Updating game catalog…") {
                    catalog = withContext(Dispatchers.IO) { WindowsCatalog.refresh(store.directory, action.url) }
                    preference("catalogUrl", action.url.trim()) { copy(catalogUrl = action.url.trim()) }
                    presentGames()
                    notice("Loaded ${catalog.size} games.")
                }
                is DesktopAction.CreateWarp -> run("Registering a WARP profile…") { createWarp(action.testEndpoints) }
                is DesktopAction.ScanEndpoints -> run("Testing WireGuard endpoints…") {
                    scanEndpoints(selected() ?: throw ConfigException("Select a WireGuard profile first."), action.endpoints)
                }
                is DesktopAction.UseEndpoint -> useEndpoint(action.endpoint)
                is DesktopAction.Enroll -> run("Enrolling a WireGuard profile…") { enroll(action) }
                is DesktopAction.LoadSubscription -> run("Loading subscription…") {
                    val url = action.url.trim()
                    subscriptions = withContext(Dispatchers.IO) { SubscriptionSource.download(url) }
                    preference("subscriptionUrl", url) { copy(subscriptionUrl = url) }
                    presentSubscriptions()
                    notice("Loaded ${subscriptions.size} subscription entries.")
                }
                DesktopAction.RankSubscription -> run("Ranking subscription…") {
                    disconnectedForTest()
                    subscriptions = DesktopProbes.rankSubscription(subscriptions) { tested, total ->
                        progress("Testing subscription · $tested/$total")
                    }
                    presentSubscriptions()
                }
                is DesktopAction.AddSubscriptionProfile -> {
                    val entry = subscriptions.firstOrNull { it.id == action.id } ?: return
                    saveProfile(entry.conf, entry.name)
                }
                DesktopAction.AddAllSubscriptionProfiles -> {
                    val entries = subscriptions.filter { it.conf.isNotBlank() }
                    importProfiles(entries.map { it.conf }, "", entries.map { it.name })
                }
                DesktopAction.InspectDevice -> run("Reading device information…") { inspect(samplePath = true) }
                is DesktopAction.SaveReport -> saveReport(action.kind)
                is DesktopAction.LoadReport -> loadReport(action.id)
                is DesktopAction.DeleteReport -> {
                    val next = state.reports.filterNot { it.id == action.id }
                    store.saveReports(next)
                    update { copy(reports = next) }
                }
                DesktopAction.Stop, DesktopAction.Close -> Unit
            }
        } catch (e: Exception) {
            val message = e.message ?: "The action could not be completed."
            if (action is DesktopAction.SaveProfile && action.complete != null) action.complete.invoke(message)
            else notice(message)
        }
    }

    private fun run(label: String, networkChange: Boolean = false, body: suspend () -> Unit) {
        operation = scope.launch {
            applyingNetwork = networkChange
            update { copy(busy = label, notice = "") }
            try {
                body()
            } catch (e: CancellationException) {
                notice("Stopped.")
                throw e
            } catch (e: Exception) {
                notice(e.message ?: "The operation could not be completed.")
            } finally {
                applyingNetwork = false
                update { copy(busy = null) }
            }
        }
    }

    private fun close() {
        closing = true
        scope.launch {
            val pending = operation
            if (!applyingNetwork) pending?.cancel()
            pending?.join()
            progress("Restoring network settings…")
            try {
                withContext(NonCancellable + Dispatchers.IO) { tunnels.close() }
                window.dispose()
                releaseInstance()
                scope.cancel()
            } catch (e: Exception) {
                closing = false
                update { copy(busy = null, notice = e.message ?: "Could not restore network settings.") }
            }
        }
    }

    private suspend fun connect(profile: DesktopProfile) {
        val previousId = state.activeId
        update { copy(phase = DesktopPhase.CONNECTING, activeId = profile.id, connectionMessage = "Waiting for the system…") }
        try {
            val dns = state.preferredDns?.let(::listOf).orEmpty()
            val killSwitch = state.killSwitch
            val reconnect = state.autoReconnect
            withContext(Dispatchers.IO) { tunnels.connect(profile.name, profile.config, killSwitch, reconnect, dns) }
            update { copy(connectionSince = System.currentTimeMillis(), latencyHistory = emptyList()) }
            refreshSession()
        } catch (e: Exception) {
            val remaining = withContext(Dispatchers.IO) { runCatching { tunnels.status() }.getOrNull() }
            if (remaining?.connected == true) {
                update { copy(phase = DesktopPhase.CONNECTED, activeId = previousId, connectionMessage = remaining.detail) }
            } else {
                update {
                    copy(phase = DesktopPhase.FAILED, activeId = null, connectionSince = null,
                        recoveryRequired = remaining?.phase == "recovery",
                        connectionMessage = e.message ?: "Could not start tunnel.")
                }
            }
            throw e
        }
    }

    private suspend fun refreshSession(samplePing: Boolean = false) {
        val activeId = state.activeId
        val sample = samplePing && state.profiles.any { it.id == activeId && it.fullTunnel }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val status = tunnels.status()
                val ping = if (sample && status.connected) RouteProbe.probe("speed.cloudflare.com").ms?.toDouble() else null
                status to ping
            }.getOrNull()
        } ?: return
        if (samplePing && (state.activeId != activeId || applyingNetwork)) return
        val (status, ping) = result
        val nextPhase = when (status.phase) {
            "starting" -> DesktopPhase.RECONNECTING
            "failed", "recovery" -> DesktopPhase.FAILED
            else -> if (status.connected) DesktopPhase.CONNECTED else DesktopPhase.DISCONNECTED
        }
        update {
            copy(
                activeId = activeId.takeIf { nextPhase.isLive },
                phase = nextPhase,
                recoveryRequired = status.phase == "recovery",
                connectionMessage = status.detail,
                connectionSince = connectionSince.takeIf { nextPhase.isLive },
                latencyHistory = if (sample && status.connected) (latencyHistory + ping).takeLast(40) else latencyHistory,
                session = listOf(
                    DesktopField("Handshake", status.lastHandshakeSeconds?.let { "$it s ago" } ?: "—"),
                    DesktopField("Downloaded", status.rxBytes?.let(::formatBytes) ?: "—"),
                    DesktopField("Uploaded", status.txBytes?.let(::formatBytes) ?: "—")
                )
            )
        }
    }

    private suspend fun speedTest() {
        update { copy(speed = DesktopMeasurement("speed", "Current route", status = "Measuring")) }
        try {
            val measured = DesktopProbes.speed { stage, partial ->
                progress(stage.label)
                update { copy(speed = partial.row("speed", "Current route", measuring = true)) }
            }
            update { copy(speed = measured.row("speed", "Current route")) }
        } catch (e: CancellationException) {
            update { copy(speed = speed?.copy(status = "Stopped")) }
            throw e
        } catch (e: Exception) {
            update { copy(speed = speed?.copy(status = "Failed", detail = e.message ?: "The speed test could not finish.")) }
            throw e
        }
    }

    private suspend fun testConfigs(input: String) {
        disconnectedForTest()
        val entries = desktopConfigInputs(input)
        val savedNames = state.profiles.associateBy { it.config.trim() }
        update { copy(configTests = emptyList()) }
        for ((index, entry) in entries.withIndex()) {
            val id = "config-$index"
            val profile = runCatching { desktopProfile(entry, savedNames[entry.trim()]?.name.orEmpty(), id) }.getOrNull()
            val name = profile?.name ?: "Config ${index + 1}"
            val row = try {
                if (profile == null) throw ConfigException("This profile could not be read.")
                DesktopProbes.configSpeed(profile.config) { stage, _ ->
                    progress("${stage.label} · ${index + 1}/${entries.size}")
                }.row(id, name, profile.endpoint, profile.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DesktopMeasurement(id, name, profile?.endpoint.orEmpty(), profile?.kind.orEmpty(),
                    detail = e.message ?: "Config test failed.", status = "Unavailable")
            }
            update { copy(configTests = configTests + row) }
        }
    }

    private suspend fun testDns(action: DesktopAction.TestDns) {
        val extra = action.extraResolvers.split(Regex("[,\\s]+")).filter(String::isNotBlank).mapIndexed { index, ip ->
            require(normalizedDnsAddress(ip) != null) { "Enter numeric IPv4 or IPv6 resolver addresses separated by spaces or commas." }
            DesktopDnsResolver("extra-$index", "Extra resolver", ip)
        }
        val selected = if (action.resolverId == null) state.resolvers + extra else listOfNotNull(
            state.resolvers.firstOrNull { it.id == action.resolverId }
                ?: state.dns.firstNotNullOfOrNull { it.savedDnsResolver()?.takeIf { row -> row.id == action.resolverId } }
        )
        require(selected.isNotEmpty()) { "Choose a DNS resolver to test." }
        updateDns(measureResolvers(selected.dnsTargets()), merge = action.resolverId != null)
    }

    private suspend fun analyzeNetwork() {
        update { copy(optimizer = null, suggestedDns = null) }
        val route = samplePath()
        update { copy(optimizer = route) }
        updateDns(measureResolvers(DnsData.scanCandidates(state.resolvers.dnsTargets())), merge = true)
    }

    private suspend fun measureResolvers(resolvers: List<DnsItem>): List<DnsItem> =
        DesktopProbes.dns(resolvers) { tested, total -> progress("Measuring resolvers · $tested/$total") }

    private fun updateDns(results: List<DnsItem>, merge: Boolean) {
        val rows = results.map { resolver ->
            DesktopMeasurement(resolver.ip, resolver.provider, resolver.ip, "DNS",
                resolver.pingMs?.toDouble(), resolver.jitterMs?.toDouble(), resolver.lossPct?.toDouble(),
                detail = resolver.notes, status = resolver.status)
        }
        update {
            val current = if (merge) {
                val changed = rows.map(DesktopMeasurement::id).toSet()
                (rows + dns.filterNot { (normalizedDnsAddress(it.endpoint) ?: it.id) in changed })
                    .sortedBy { DnsData.distanceScore(it.latencyMs?.toLong(), it.lossPercent?.toInt(), it.jitterMs?.toLong()) }
            } else rows
            copy(dns = current, suggestedDns = current.firstOrNull { it.latencyMs != null })
        }
    }

    private fun saveDnsResolver(action: DesktopAction.SaveDnsResolver) {
        val previous = state.resolvers.filter(DesktopDnsResolver::custom)
        if (action.id != null) require(previous.any { it.id == action.id }) { "Choose a custom resolver to edit." }
        val resolver = if (action.id == null) customDnsResolver(action.name, action.address)
            else customDnsResolver(action.name, action.address, action.id)
        require(previous.none { it.id != resolver.id && it.addressKey == resolver.addressKey }) {
            "A custom resolver with this address already exists."
        }
        val next = if (action.id == null) previous + resolver
            else previous.map { if (it.id == resolver.id) resolver else it }
        setCustomResolvers(next)
        notice("Saved ${resolver.name}.")
    }

    private fun deleteDnsResolver(id: String) {
        setCustomResolvers(state.resolvers.filter { it.custom && it.id != id })
        notice("Custom resolver removed.")
    }

    private fun setCustomResolvers(custom: List<DesktopDnsResolver>) {
        store.saveDnsResolvers(custom)
        val next = bundledResolvers + custom
        val addresses = next.map(DesktopDnsResolver::addressKey).toSet()
        update {
            val retained = dns.filter { (normalizedDnsAddress(it.endpoint) ?: it.id) in addresses }
            copy(resolvers = next, dns = retained, suggestedDns = retained.firstOrNull { it.latencyMs != null })
        }
    }

    private suspend fun samplePath(): DesktopMeasurement {
        val (samples, loss) = NetworkProber.livePingSuite()
        val average = samples.takeIf { it.isNotEmpty() }?.average()
        val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.takeIf { it.isNotEmpty() }?.average()
        val quality = when {
            average == null -> "Unavailable"
            loss > 10 -> "Unstable"
            average < 50 -> "Excellent"
            average < 90 -> "Good"
            average < 140 -> "Fair"
            else -> "High latency"
        }
        return DesktopMeasurement("route", "Current connection", protocol = "TCP",
            latencyMs = average, jitterMs = jitter, lossPercent = loss.toDouble(),
            detail = "Connection attempts to Cloudflare, Google and Quad9.", status = quality)
    }

    private suspend fun applyDns(address: String?) {
        val resolver = address?.let { requireNotNull(normalizedDnsAddress(it)) { "Select a valid resolver IP." } }
        val active = state.profiles.firstOrNull { it.id == state.activeId }
        if (active != null && state.phase.isLive) {
            withContext(Dispatchers.IO) { tunnels.restoreDns() }
            preference("preferredDns", resolver) { copy(preferredDns = resolver) }
            connect(active)
        } else {
            withContext(Dispatchers.IO) {
                if (resolver == null) tunnels.restoreDns() else tunnels.applyDns(listOf(resolver))
            }
            preference("preferredDns", resolver) { copy(preferredDns = resolver) }
        }
        notice(if (address == null) "Restored the default DNS settings." else "Using DNS $address.")
    }

    private suspend fun discoverGames() {
        if (catalog.isEmpty()) {
            progress("Reading game catalog…")
            catalog = withContext(Dispatchers.IO) { WindowsCatalog.games(store.directory) }
            presentGames()
        }
        progress("Scanning this PC for games…")
        installed = withContext(Dispatchers.IO) { WindowsCatalog.installedGames() }
        presentGames()
    }

    private fun presentGames() = update {
        copy(games = WindowsCatalog.presentGames(catalog, installed.orEmpty()), gamesScanned = installed != null)
    }

    private suspend fun testGame(id: String, compare: Boolean) {
        val game = catalog.firstOrNull { it.id == id } ?: return
        require(game.hosts.isNotEmpty()) { "This game has no catalog HTTPS endpoint." }
        val routes = buildList {
            add(ProbeRoute.Current)
            if (compare) {
                (listOfNotNull(state.preferredDns) + listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"))
                    .distinct().take(4).forEach { add(ProbeRoute.Resolver(it)) }
                if (!state.phase.isLive) state.profiles.take(4).forEach { add(ProbeRoute.Config(it.name, it.config)) }
            }
        }
        update { copy(gameTests = emptyList()) }
        val result = try {
            DesktopProbes.game(game.hosts, routes) { measured -> presentGameTests(game, measured, measuring = true) }
        } catch (e: CancellationException) {
            update { copy(gameTests = gameTests.map { it.copy(status = "Stopped") }) }
            throw e
        }
        presentGameTests(game, result)
        val message = result.recommendedRoute?.let { "Best measured route · $it" } ?: "No game endpoint answered."
        notice(if (compare && state.phase.isLive) "$message. Disconnect the tunnel to compare saved profiles." else message)
    }

    private fun presentGameTests(game: WindowsGame, result: GameTest, measuring: Boolean = false) = update {
        copy(gameTests = result.routes.mapIndexed { index, route ->
            val status = when {
                measuring -> "Measuring"
                route.stats.successful > 0 -> "Measured"
                else -> "No response"
            }
            val detail = buildString {
                append("${game.title} · ${route.stats.successful} of ${route.stats.attempts} probes responded")
                if (!measuring && route.error != null) append("\n${route.error}")
            }
            DesktopMeasurement("game-$index", route.label, result.host, "HTTPS", route.stats.latencyMs?.toDouble(),
                route.stats.jitterMs?.toDouble(), route.stats.lossPct?.toDouble(),
                detail = detail, status = status)
        })
    }

    private suspend fun createWarp(test: Boolean) {
        disconnectedForTest()
        val existing = state.profiles.firstOrNull { it.id == store.string("warpProfile") }
        if (existing != null) {
            store.put("selectedId" to existing.id)
            update { copy(selectedId = existing.id) }
            notice("Selected the existing WARP profile.")
            return
        }
        val key = persistentKey("warpKey")
        val created = withContext(Dispatchers.IO) { WarpProvisioning.register(key) }
        val profile = saveProfile(created.confText, "WARP")
        store.put("warpProfile" to profile.id)
        if (test) {
            scanEndpoints(profile, WireGuardProbe.setupEndpoints(created.endpoints).joinToString("\n"))
            state.endpointTests.firstOrNull { it.latencyMs != null }?.let { useEndpoint(it.endpoint) }
        }
    }

    private suspend fun scanEndpoints(profile: DesktopProfile, endpoints: String) {
        disconnectedForTest()
        val results = DesktopProbes.endpoints(profile.config, endpoints) { index, total, endpoint ->
            progress("Testing WireGuard endpoint $index/$total · $endpoint")
        }
        endpointProfile = profile
        update { copy(endpointTests = results.map {
            DesktopMeasurement(it.endpoint, it.endpoint, it.endpoint, "WireGuard", it.ms?.toDouble(),
                detail = it.error.orEmpty(), status = if (it.ms == null) "Unavailable" else "Measured")
        }) }
        notice(if (results.any { it.ms != null }) "Endpoint tests finished." else "No endpoint reached Cloudflare through WireGuard.")
    }

    private fun useEndpoint(endpoint: String) {
        val profile = selected()
        require(profile != null && profile == endpointProfile) { "Scan endpoints for the current profile first." }
        require(state.endpointTests.any { it.endpoint == endpoint && it.latencyMs != null }) { "Select a tested endpoint." }
        val config = AwgConfig.parse(profile.config)
        val updated = config.copy(peers = listOf(config.peers.single().copy(endpoint = endpoint)))
        endpointProfile = saveProfile(updated.toConfText(), profile.name, profile.id)
        notice("Saved endpoint $endpoint.")
    }

    private suspend fun enroll(action: DesktopAction.Enroll) {
        val url = HostedProvisioning.normalizeServer(action.url)
        require(action.token.isNotBlank()) { "Enter the enrollment token." }
        val key = persistentKey("enrollment:$url")
        val profile = withContext(Dispatchers.IO) { HostedProvisioning.register(url, action.token.trim(), key) }
        saveProfile(profile.confText, action.name.ifBlank { "Self-hosted WireGuard" })
    }

    private fun persistentKey(id: String): String = EnrollmentKey.loadOrCreate(
        read = { store.string(id).ifBlank { null } },
        write = { key -> store.put(id to key); true },
        generate = Keys::generatePrivateKey
    )

    private fun presentSubscriptions() = update {
        copy(subscription = subscriptions.map { entry ->
            val endpoint = if (':' in entry.host && !entry.host.startsWith("[")) "[${entry.host}]:${entry.port}"
                else "${entry.host}:${entry.port}"
            DesktopMeasurement(entry.id, entry.name, endpoint, entry.type,
                latencyMs = entry.pingMs?.toDouble(), lossPercent = entry.lossPct?.toDouble(),
                detail = entry.region, status = entry.status, canAdd = entry.conf.isNotBlank())
        })
    }

    private suspend fun inspect(samplePath: Boolean) {
        val (device, engines) = withContext(Dispatchers.IO) {
            val tools = listOf(
                "WireGuard" to DesktopEngines::wireGuard,
                "AmneziaWG" to DesktopEngines::amnezia,
                "Xray" to DesktopEngines::xray
            ).map { (name, resolve) ->
                DesktopField(name, runCatching { resolve().toString() }.getOrElse { it.message ?: "Unavailable" })
            }
            WindowsCatalog.device() to tools
        }
        update {
            copy(device = device, engines = engines, engineSummary = "WireGuard | AmneziaWG | Xray",
                devicePath = if (samplePath) emptyList() else devicePath)
        }
        if (samplePath) {
            progress("Sampling the current network path…")
            val route = samplePath()
            update { copy(devicePath = listOf(
                DesktopField("Connection quality", route.status),
                DesktopField("Average TCP delay", measurementValue(route.latencyMs, "ms", "Unavailable")),
                DesktopField("Jitter", measurementValue(route.jitterMs, "ms", "Unavailable")),
                DesktopField("Failed probes", measurementValue(route.lossPercent, "%", "Unavailable")),
                DesktopField("Targets", "Cloudflare, Google and Quad9")
            ), notice = "Device scan complete.") }
        }
    }

    private fun saveProfile(config: String, name: String, id: String? = null): DesktopProfile {
        val parsed = desktopProfile(config, name, id ?: UUID.randomUUID().toString())
        val identity = parsed.configurationIdentity()
        val profile = if (id == null) state.profiles.firstOrNull { it.configurationIdentity() == identity } ?: parsed else parsed
        val next = if (state.profiles.none { it.id == profile.id }) state.profiles + profile
            else state.profiles.map { if (it.id == profile.id) profile else it }
        store.saveProfiles(next, profile.id)
        update { copy(profiles = next, selectedId = profile.id, notice = "Saved ${profile.name}.") }
        return profile
    }

    private fun importProfiles(configs: List<String>, name: String, names: List<String> = emptyList()) {
        var next = state.profiles
        var selected = state.selectedId
        var accepted = 0
        val byConfig = linkedMapOf<String, DesktopProfile>()
        next.forEach { byConfig.putIfAbsent(it.configurationIdentity(), it) }
        configs.forEachIndexed { index, text ->
            val title = names.getOrNull(index) ?: name.takeIf { configs.size == 1 }.orEmpty()
            val profile = runCatching { desktopProfile(text, title) }.getOrNull() ?: return@forEachIndexed
            val existing = byConfig.putIfAbsent(profile.configurationIdentity(), profile)
            if (existing == null) next = next + profile
            selected = existing?.id ?: profile.id
            accepted++
        }
        require(accepted > 0) { "No valid WireGuard or Xray profiles were found." }
        store.saveProfiles(next, selected)
        update { copy(profiles = next, selectedId = selected, notice = "Imported $accepted of ${configs.size} profiles.") }
    }

    private fun deleteProfile(id: String) {
        require(id != state.activeId) { "Disconnect this profile before deleting it." }
        val next = state.profiles.filterNot { it.id == id }
        val selected = if (state.selectedId == id) next.firstOrNull()?.id else state.selectedId
        store.saveProfiles(next, selected)
        update { copy(profiles = next, selectedId = selected, notice = "Profile deleted.") }
    }

    private fun saveReport(kind: DesktopReportKind) {
        val rows = when (kind) {
            DesktopReportKind.DNS -> state.dns
            DesktopReportKind.CONFIGS -> state.configTests
            DesktopReportKind.GAMES -> state.gameTests
            DesktopReportKind.SPEED -> listOfNotNull(state.speed)
        }
        require(rows.isNotEmpty()) { "Run a test before saving its report." }
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val next = listOf(DesktopReport(UUID.randomUUID().toString(), kind, "${kind.label} · $date", rows)) + state.reports
        store.saveReports(next)
        update { copy(reports = next, notice = "Report saved.") }
    }

    private fun loadReport(id: String) {
        val report = state.reports.firstOrNull { it.id == id } ?: return
        update {
            when (report.kind) {
                DesktopReportKind.DNS -> copy(dns = report.rows)
                DesktopReportKind.CONFIGS -> copy(configTests = report.rows)
                DesktopReportKind.GAMES -> copy(gameTests = report.rows)
                DesktopReportKind.SPEED -> copy(speed = report.rows.firstOrNull())
            }.copy(notice = "Loaded ${report.title}.")
        }
    }

    private fun selected(): DesktopProfile? = state.profiles.firstOrNull { it.id == state.selectedId }

    private fun disconnectedForTest() {
        require(!state.phase.isLive && !state.recoveryRequired) {
            "Disconnect or restore the active WhiteGame tunnel before testing profiles."
        }
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1_048_576 -> "%.1f MiB".format(value / 1_048_576.0)
        value >= 1_024 -> "%.1f KiB".format(value / 1_024.0)
        else -> "$value B"
    }

    private val SpeedStage.label: String get() = when (this) {
        SpeedStage.LATENCY -> "Measuring HTTPS ping…"
        SpeedStage.DOWNLOAD -> "Measuring download speed…"
        SpeedStage.UPLOAD -> "Measuring upload speed…"
    }

    private fun CloudflareSpeedResult.row(
        id: String, name: String, endpoint: String = "", protocol: String = "HTTPS", measuring: Boolean = false
    ): DesktopMeasurement {
        val status = when {
            measuring -> "Measuring"
            latencyMs != null && downloadMbps != null && uploadMbps != null -> "Complete"
            latencyMs != null || downloadMbps != null || uploadMbps != null -> "Partial result"
            else -> "No response"
        }
        return DesktopMeasurement(id, name, endpoint, protocol, latencyMs, jitterMs,
            downloadMbps = downloadMbps, uploadMbps = uploadMbps,
            status = status)
    }

    private fun progress(text: String) = update { copy(busy = text) }
    private fun notice(text: String) = update { copy(notice = text) }
    private fun preference(key: String, value: Any?, change: DesktopState.() -> DesktopState) {
        store.put(key to value)
        update(change)
    }

    private fun update(change: DesktopState.() -> DesktopState) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { update(change) }
            return
        }
        state = state.change()
        window.render(state)
    }
}

private object SwingDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()
    override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
}
