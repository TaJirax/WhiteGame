package com.whitegame.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

internal class DesktopPages(
    private val ui: DesktopStyle,
    private val model: DesktopState,
    private val drafts: DesktopDrafts,
    private val dispatch: (DesktopAction) -> Unit,
    owner: JFrame,
    private val testProfile: (DesktopProfile) -> Unit
) {
    private val dialogs = DesktopDialogs(owner, ui, dispatch)
    private val idle get() = model.busy == null
    private val selected get() = model.profiles.firstOrNull { it.id == model.selectedId }
    private val canTestProfiles get() = idle && !model.phase.isLive && model.phase != DesktopPhase.DISCONNECTING

    fun connect(
        openTunnels: () -> Unit,
        observeUptime: (JLabel) -> Unit,
        observeSession: ((DesktopState) -> Unit) -> Unit
    ): JComponent {
        val ready = model.phase == DesktopPhase.CONNECTED
        val isLive = model.phase.isLive
        val activeProfile = model.profiles.firstOrNull { it.id == model.activeId }
        val stateTrace = if (ready) ui.trace else ui.trace2
        val latency = ui.label("", ui.readout)
        val connectionTitle = if (ready) {
            ui.split(
                ui.column(
                    ui.legend("Latency"),
                    latency
                ),
                ui.column(ui.legend("Uptime"), ui.label(uptime(model.connectionSince), ui.mono).also(observeUptime))
            )
        } else {
            ui.column(
                ui.label(selected?.name ?: "No tunnel selected", ui.title),
                ui.paragraph(model.connectionMessage, rows = 2)
            )
        }
        val traceLegend = ui.legend("Latency")
        var previousSamples: List<Double?>? = null
        val trace = ui.latencyTrace { previousSamples.orEmpty() }
        val session = JPanel(BorderLayout()).apply { isOpaque = false }
        var previousSession: List<DesktopField>? = null
        fun updateSession(state: DesktopState) {
            val samples = state.latencyHistory
            if (samples != previousSamples) {
                previousSamples = samples
                val measured = samples.filterNotNull()
                latency.text = metric(samples.lastOrNull(), "ms")
                traceLegend.text = if (measured.isEmpty()) "LATENCY" else
                    "MIN ${metric(measured.minOrNull(), "ms")} · AVG ${metric(measured.average(), "ms")} · MAX ${metric(measured.maxOrNull(), "ms")}"
                trace.repaint()
            }
            val counters = state.session.ifEmpty {
                listOf(
                    DesktopField("Handshake", "None yet"),
                    DesktopField("Downloaded", "0 B"),
                    DesktopField("Uploaded", "0 B"),
                    DesktopField("Endpoint", activeProfile?.endpoint ?: "—"),
                    DesktopField("DNS override", state.preferredDns ?: "System")
                )
            }
            if (counters != previousSession) {
                previousSession = counters
                session.removeAll()
                session.add(ui.table(counters), BorderLayout.CENTER)
                session.revalidate()
                session.repaint()
            }
        }
        updateSession(model)
        observeSession(::updateSession)
        val instrument = ui.box(ui.column(
            ui.legend("●   ${model.phase.label}", stateTrace),
            connectionTitle,
            trace,
            traceLegend
        ), instrument = true)

        val connectAction = when {
            model.profiles.isEmpty() && !model.recoveryRequired -> ui.box(ui.column(
                ui.legend("No tunnels yet"),
                ui.paragraph("Add a WireGuard, AmneziaWG or Xray config. Paste a profile or import one from a file."),
                ui.button("Add a tunnel", primary = true, action = openTunnels)
            ))
            else -> ui.column(
                ui.button(
                    when {
                        model.phase == DesktopPhase.CONNECTING -> "Connecting…"
                        isLive -> "Disconnect"
                        model.phase == DesktopPhase.DISCONNECTING -> "Disconnecting…"
                        model.recoveryRequired -> "Restore network settings"
                        else -> "Connect"
                    },
                    primary = !isLive,
                    enabled = idle && model.phase != DesktopPhase.CONNECTING &&
                        model.phase != DesktopPhase.DISCONNECTING && (isLive || model.recoveryRequired || selected != null)
                ) {
                    dispatch(if (isLive || model.recoveryRequired) DesktopAction.Disconnect else DesktopAction.Connect)
                },
                ui.box(ui.split(
                    ui.column(
                        ui.legend("Tunnel"),
                        ui.label(selected?.name ?: "None selected", ui.subtitle),
                        ui.label(selected?.let { "${it.kind} · ${it.endpoint.ifBlank { "No endpoint" }}" }.orEmpty(), ui.monoSmall, ui.trace2)
                    ),
                    ui.button("Choose", enabled = !isLive && idle) {
                        dialogs.chooseProfile(model.profiles, "Choose a tunnel") { dispatch(DesktopAction.SelectProfile(it.id)) }
                    }
                ))
            )
        }
        val recommendation = model.suggestedDns?.let {
            ui.box(ui.split(
                ui.column(ui.legend("Recommended DNS"), ui.label(it.name, ui.subtitle),
                    ui.label("${it.endpoint} · ${metric(it.latencyMs, "ms")}", ui.monoSmall)),
                ui.button("Use DNS", enabled = idle && model.preferredDns != it.endpoint) {
                    dispatch(DesktopAction.ApplyDns(it.endpoint))
                }
            ))
        }
        val networkOptimizer = ui.column(*listOfNotNull(
            ui.section("Network optimizer", "Measure first, then choose a route"),
            ui.button("Analyze network", enabled = idle) { dispatch(DesktopAction.AnalyzeNetwork) },
            model.optimizer?.let { measurement(it, latencyLabel = "Average connection") },
            recommendation
        ).toTypedArray())
        return twoColumns(
            ui.column(instrument, connectAction, networkOptimizer, gap = DesktopSpace.md),
            ui.column(
                ui.section("Session", "Starts when a tunnel comes up"),
                session,
                ui.spacer(DesktopSpace.sm),
                ui.section("Connection options"),
                if (DesktopPlatform.linux) ui.notice("Xray uses the bundled engine. WireGuard and AmneziaWG use installed tunnel tools.")
                else ui.box(ui.column(
                    ui.toggle("Auto-reconnect", model.autoReconnect) { dispatch(DesktopAction.AutoReconnect(it)) },
                    ui.hairline(),
                    ui.toggle("Kill switch", model.killSwitch, enabled = !isLive && idle) { dispatch(DesktopAction.KillSwitch(it)) }
                ))
            )
        )
    }

    fun tunnels(): JComponent {
        val controls = ui.grid(
            ui.button("New tunnel", primary = true, enabled = idle) { dialogs.editProfile() },
            ui.button("Import file", enabled = idle) {
                dialogs.openConfig { name, text -> dispatch(DesktopAction.ImportProfiles(text, name)) }
            }
        )
        val saved = model.profiles.map(::profile)
        val profileList = ui.column(
            ui.section("Saved", if (saved.size == 1) "1 profile" else "${saved.size} profiles"),
            controls,
            *saved.ifEmpty { listOf(ui.notice("Saved tunnel profiles will appear here.")) }.toTypedArray(),
            gap = DesktopSpace.md
        )
        val subscription = ui.field(drafts.subscription ?: model.subscriptionUrl).apply {
            name = "subscription-url"
            onEdit { drafts.subscription = it }
            isEnabled = idle
        }
        val setup = ui.column(
            ui.section("Provisioning"),
            ui.grid(
                ui.button("Self-hosted setup", enabled = idle) { dialogs.enroll() },
                ui.button("WARP setup", enabled = idle) { dialogs.createWarp() }
            ),
            ui.spacer(DesktopSpace.sm),
            ui.section("WireGuard endpoint scan", "Enter an endpoint per line. IPv6 endpoints use [address]:port."),
            editArea("Test endpoints", "endpoint-draft", drafts.endpoints, rows = 3) { drafts.endpoints = it },
            ui.grid(
                ui.button("Scan endpoints", enabled = canTestProfiles && selected != null) {
                    dispatch(DesktopAction.ScanEndpoints(drafts.endpoints))
                },
                ui.button("Use best", enabled = canTestProfiles && model.endpointTests.any { it.latencyMs != null }) {
                    model.endpointTests.filter { it.latencyMs != null }.minByOrNull { it.latencyMs!! }?.let {
                        dispatch(DesktopAction.UseEndpoint(it.endpoint.ifBlank { it.name }))
                    }
                }
            ),
            *model.endpointTests.map { measurement(it, latencyLabel = "HTTPS delay") }.toTypedArray(),
            ui.spacer(DesktopSpace.sm),
            ui.section("Subscription", "Your own server list"),
            ui.legend("Subscription URL"),
            subscription,
            ui.grid(
                ui.button("Load list", enabled = idle) { dispatch(DesktopAction.LoadSubscription(subscription.text.trim())) },
                ui.button("Rank by ping", enabled = canTestProfiles && model.subscription.isNotEmpty()) {
                    dispatch(DesktopAction.RankSubscription)
                }
            ),
            *listOfNotNull(
                ui.button("Add all profiles", enabled = idle) { dispatch(DesktopAction.AddAllSubscriptionProfiles) }
                    .takeIf { model.subscription.any(DesktopMeasurement::canAdd) }
            ).toTypedArray(),
            *model.subscription.map { item ->
                measurement(item, extra = ui.button("Add profile", enabled = idle) {
                    dispatch(DesktopAction.AddSubscriptionProfile(item.id))
                }.takeIf { item.canAdd })
            }.toTypedArray()
        )
        return twoColumns(profileList, setup)
    }

    private fun profile(profile: DesktopProfile): JComponent {
        val current = profile.id == model.selectedId
        val active = profile.id == model.activeId && model.phase.isLive
        val profileStatus = when {
            active -> "Connected"
            current -> "Selected"
            else -> null
        }
        val title = ui.column(
            ui.label(profile.name, ui.subtitle),
            ui.label(profile.endpoint.ifBlank { "No endpoint" }, ui.monoSmall, ui.trace2),
            gap = DesktopSpace.xs
        )
        val header = if (profileStatus == null) title else ui.split(title, ui.legend(profileStatus, ui.trace))
        val actions = arrayListOf(
            ui.button(if (current) "Selected" else "Select", enabled = idle && !active && !current) {
                dispatch(DesktopAction.SelectProfile(profile.id))
            },
            ui.button("Test", enabled = canTestProfiles) { testProfile(profile) },
            ui.button("Edit", enabled = idle && !active) { dialogs.editProfile(profile) },
            ui.button("Delete", enabled = idle && !active) { dialogs.deleteProfile(profile) }
        )
        val export = if (!profile.kind.equals("Xray", ignoreCase = true)) {
            ui.button("Export config") { dialogs.export(profile) }
        } else ui.button("Copy config") { dialogs.copy(profile.config) }
        val routing = if (profile.fullTunnel) "All traffic" else "Split by AllowedIPs"
        return ui.box(ui.column(
            header,
            ui.label("${profile.kind} · $routing", ui.small, ui.trace3),
            ui.grid(*actions.toTypedArray()),
            export,
            gap = DesktopSpace.sm
        ))
    }

    fun network(tab: DesktopNetworkTab): JComponent = when (tab) {
        DesktopNetworkTab.DNS -> dns()
        DesktopNetworkTab.CONFIGS -> configs()
        DesktopNetworkTab.GAMES -> games()
        DesktopNetworkTab.SPEED -> speed()
    }

    private fun dns(): JComponent = desktopDnsPage(ui, model, drafts, dialogs, dispatch)

    private fun configs(): JComponent {
        val left = ui.column(
            ui.section("Config tests", "HTTPS ping, download and upload through each profile"),
            ui.grid(
                ui.button("Load saved config", enabled = idle && model.profiles.isNotEmpty()) {
                    dialogs.chooseProfile(model.profiles, "Load a saved config") { profile ->
                        updateConfigEditor(profile.config)
                    }
                },
                ui.button("Open file", enabled = idle) {
                    dialogs.openConfig { _, text -> updateConfigEditor(text) }
                }
            ),
            editArea("WireGuard configs, Xray share links or JSON", "config-draft", drafts.configs, rows = 9) { drafts.configs = it },
            ui.notice("Disconnect WhiteGame first. A test can use up to 18 MB of data per config."),
            ui.grid(
                ui.button("Test ping & speed", primary = true, enabled = canTestProfiles) {
                    dispatch(DesktopAction.TestConfigs(drafts.configs))
                },
                saveButton(DesktopReportKind.CONFIGS, model.configTests.isNotEmpty())
            )
        )
        val results = ui.column(
            ui.section("Config results"),
            *model.configTests.map { measurement(it, forceBandwidth = true, extra = copyButton(it)) }
                .ifEmpty { listOf(ui.notice("Run a profile test to see its latency, download and upload.")) }.toTypedArray()
        )
        return twoColumns(left, results)
    }

    private var configEditor: JTextArea? = null

    private fun updateConfigEditor(value: String) {
        drafts.configs = value
        configEditor?.text = value
    }

    private fun speed(): JComponent {
        val actions = ui.column(
            ui.section("Cloudflare Speed Test"),
            ui.notice("The test measures the route available to this PC. It uses up to 18 MB of data and shares the route’s IP address with Cloudflare."),
            ui.grid(
                ui.button("Run speed test", primary = true, enabled = idle) { dispatch(DesktopAction.SpeedTest) },
                saveButton(DesktopReportKind.SPEED, model.speed != null)
            )
        )
        val result = model.speed?.let {
            measurement(it, forceBandwidth = true, extra = if (it.status == "Measuring") null else copyButton(it))
        } ?: ui.notice("Run a speed test to see this connection’s latency, download and upload.")
        return twoColumns(actions, ui.column(ui.section("Speed results"), result))
    }

    private fun games(): JComponent {
        val search = ui.field(drafts.gameSearch).apply { name = "game-search" }
        val list = DesktopScrollContent().apply { isOpaque = false }
        val installedCount = model.games.count(DesktopGame::installed)
        fun updateMatches() {
            val keyword = search.text.trim()
            val matching = model.games.filter { game ->
                (!drafts.installedOnly || game.installed) &&
                    (keyword.isEmpty() || game.title.contains(keyword, ignoreCase = true) ||
                        game.detail.contains(keyword, ignoreCase = true))
            }
            val rows = matching.map(::game)
            list.removeAll()
            list.add(ui.column(*rows.ifEmpty {
                listOf(ui.notice(when {
                    drafts.installedOnly && !model.gamesScanned -> "Scan this PC to find installed games."
                    drafts.installedOnly && installedCount == 0 -> "No installed games were found on this PC."
                    model.games.isEmpty() -> "Scan this PC or update the game catalog."
                    else -> "No games match your search."
                }))
            }.toTypedArray()), BorderLayout.NORTH)
            list.revalidate()
            list.repaint()
        }
        val gameList = ui.scroll(list, height = 300).apply { name = "game-browser" }
        val previousScroll = drafts.gameScrollPosition
        SwingUtilities.invokeLater {
            if (!gameList.isShowing) return@invokeLater
            gameList.verticalScrollBar.value = previousScroll
            gameList.verticalScrollBar.addAdjustmentListener { drafts.gameScrollPosition = it.value }
        }
        fun searchFromTop() {
            updateMatches()
            gameList.verticalScrollBar.value = 0
            drafts.gameScrollPosition = 0
        }
        search.onEdit {
            drafts.gameSearch = it
            searchFromTop()
        }
        updateMatches()
        val catalog = ui.field(drafts.catalog ?: model.catalogUrl).apply {
            name = "catalog-url"
            onEdit { drafts.catalog = it }
            isEnabled = idle
        }
        val browser = ui.column(
            ui.section("Games", "Discover installed titles and compare server latency"),
            ui.grid(
                ui.button("Scan for games", primary = true, enabled = idle) {
                    drafts.installedOnly = true
                    searchFromTop()
                    dispatch(DesktopAction.DiscoverGames)
                },
                ui.toggle(if (model.gamesScanned) "Installed only ($installedCount)" else "Installed only", drafts.installedOnly) {
                    drafts.installedOnly = it
                    searchFromTop()
                }
            ),
            ui.legend("Search games"),
            search,
            if (model.gamesScanned) ui.label("$installedCount installed games · ${model.games.size} titles", ui.small, ui.trace2)
            else ui.label("Installed games have not been scanned yet", ui.small, ui.trace2),
            gameList
        )
        val results = ui.column(
            ui.section("Game results", "HTTPS connection time to publisher endpoints"),
            saveButton(DesktopReportKind.GAMES, model.gameTests.isNotEmpty()),
            *model.gameTests.map { measurement(it, latencyLabel = "HTTPS delay", extra = copyButton(it)) }
                .ifEmpty { listOf(ui.notice("Choose Test latency or Compare routes on a game to see its results.")) }.toTypedArray(),
            ui.spacer(DesktopSpace.sm),
            ui.section("Catalog settings"),
            ui.legend("Catalog URL"),
            catalog,
            ui.button("Update catalog", enabled = idle) { dispatch(DesktopAction.UpdateCatalog(catalog.text.trim())) }
        )
        return twoColumns(browser, results)
    }

    private fun game(game: DesktopGame): JComponent {
        val group = ui.column(
            ui.legend(if (game.installed) "Installed" else "Catalog"),
            ui.resultText(game.title, ui.subtitle, ui.trace),
            *listOfNotNull(game.detail.takeIf(String::isNotBlank)?.let { ui.resultText(it) }).toTypedArray(),
            if (game.canTest) ui.grid(
                ui.button("Test latency", enabled = idle) { dispatch(DesktopAction.TestGame(game.id)) },
                ui.button("Compare routes", enabled = idle) { dispatch(DesktopAction.CompareGame(game.id)) }
            ) else ui.label("No catalog test endpoint", ui.small, ui.trace2)
        )
        return ui.box(group)
    }

    fun device(loadReport: (DesktopReport) -> Unit): JComponent {
        val state = ui.column(
            ui.section("Device scan"),
            ui.button("Run device scan", primary = true, enabled = idle) { dispatch(DesktopAction.InspectDevice) },
            ui.spacer(DesktopSpace.sm),
            ui.section("Network path", "TCP probes to public resolvers"),
            if (model.devicePath.isEmpty()) ui.notice("Run the device scan to measure the current connection.") else ui.table(model.devicePath),
            ui.spacer(DesktopSpace.sm),
            ui.section("PC details"),
            if (model.device.isEmpty()) ui.notice("Run the device scan to see hardware and adapter information.") else deviceDetails(model.device),
            ui.spacer(DesktopSpace.sm),
            ui.section("Engines"),
            if (model.engines.isEmpty()) ui.notice("Run the device scan to check available tunnel engines.") else deviceDetails(model.engines)
        )
        val reports = ui.column(
            ui.section("Saved reports", "DNS, configs, games and speed tests"),
            *model.reports.map { report ->
                ui.box(ui.column(
                    ui.label(report.title, ui.subtitle),
                    ui.label("${report.kind.label} · ${report.rows.size} results", ui.small, ui.trace2),
                    ui.grid(
                        ui.button("Copy") { dialogs.copy(report.rows.joinToString("\n\n", transform = ::asText)) },
                        ui.button("Load", enabled = idle) { loadReport(report) },
                        ui.button("Delete", enabled = idle) {
                            if (dialogs.confirm("Delete ${report.title}?", "Delete report")) dispatch(DesktopAction.DeleteReport(report.id))
                        }
                    )
                ))
            }.ifEmpty { listOf(ui.notice("Save a network test to view its report here.")) }.toTypedArray()
        )
        return twoColumns(state, reports)
    }

    private fun deviceDetails(rows: List<DesktopField>) = ui.box(ui.column(
        *rows.flatMapIndexed { index, row ->
            val field = ui.column(ui.legend(row.label), ui.resultText(row.value, ui.mono, ui.trace), gap = DesktopSpace.xs)
            if (index == 0) listOf(field) else listOf(ui.hairline(), field)
        }.toTypedArray()
    ))

    private fun measurement(
        result: DesktopMeasurement,
        latencyLabel: String = "HTTPS ping",
        forceBandwidth: Boolean = false,
        extra: JComponent? = null
    ): JComponent {
        val missing = if (result.status == "Measuring") "Pending" else "Unavailable"
        val fields = buildList {
            add(DesktopField(latencyLabel, measurementValue(result.latencyMs, "ms", missing)))
            if (forceBandwidth || result.downloadMbps != null || result.uploadMbps != null) {
                add(DesktopField("Download", measurementValue(result.downloadMbps, "Mbps", missing)))
                add(DesktopField("Upload", measurementValue(result.uploadMbps, "Mbps", missing)))
            }
            if (forceBandwidth || result.jitterMs != null) add(DesktopField("Jitter", measurementValue(result.jitterMs, "ms", missing)))
            if (result.lossPercent != null) add(DesktopField("Failed probes", measurementValue(result.lossPercent, "%")))
        }
        val name = ui.resultText(result.name, ui.subtitle, ui.trace)
        val headline = if (result.status.isBlank()) name else ui.column(name, ui.resultText(result.status), gap = DesktopSpace.xs)
        val subtitle = listOf(result.protocol, result.endpoint.takeUnless { it == result.name }.orEmpty())
            .filter(String::isNotBlank).joinToString(" · ")
        return ui.box(ui.column(*listOfNotNull(
            headline,
            subtitle.takeIf(String::isNotBlank)?.let { ui.resultText(it, ui.monoSmall) },
            ui.table(fields),
            result.detail.takeIf(String::isNotBlank)?.let { ui.resultText(it) },
            extra
        ).toTypedArray()))
    }

    private fun saveButton(kind: DesktopReportKind, enabled: Boolean) =
        ui.button("Save report", enabled = idle && enabled) { dispatch(DesktopAction.SaveReport(kind)) }

    private fun copyButton(result: DesktopMeasurement) = ui.button("Copy report") { dialogs.copy(asText(result)) }

    private fun asText(result: DesktopMeasurement): String = buildList {
        add(result.name)
        if (result.status.isNotBlank()) add("Status: ${result.status}")
        if (result.protocol.isNotBlank()) add("Protocol: ${result.protocol}")
        if (result.endpoint.isNotBlank()) add("Endpoint: ${result.endpoint}")
        result.latencyMs?.let { add("Latency: ${metric(it, "ms")}") }
        result.jitterMs?.let { add("Jitter: ${metric(it, "ms")}") }
        result.lossPercent?.let { add("Failed attempts: ${metric(it, "%")}") }
        result.downloadMbps?.let { add("Download: ${metric(it, "Mbps")}") }
        result.uploadMbps?.let { add("Upload: ${metric(it, "Mbps")}") }
        if (result.detail.isNotBlank()) add(result.detail)
    }.joinToString("\n")

    private fun editArea(
        label: String,
        fieldId: String,
        value: String,
        rows: Int,
        onChange: (String) -> Unit
    ): JComponent {
        val editor = ui.editor(value, rows).apply {
            name = fieldId
            isEnabled = idle
            onEdit(onChange)
        }
        if (fieldId == "config-draft") configEditor = editor
        return ui.column(ui.legend(label), ui.scroll(editor, height = (rows + 1) * DesktopSpace.lg))
    }

    private fun twoColumns(left: JComponent, right: JComponent) = ui.grid(
        JPanel(BorderLayout()).apply { isOpaque = false; add(left, BorderLayout.NORTH) },
        JPanel(BorderLayout()).apply { isOpaque = false; add(right, BorderLayout.NORTH) },
        gap = DesktopSpace.xl
    )

    private fun metric(value: Double?, unit: String): String = measurementValue(value, unit)
}

/** Wrap result text at the minimum desktop column width and scroll longer diagnostic messages. */
internal fun DesktopStyle.resultText(value: String, font: Font = small, foreground: Color = trace2): JComponent {
    val text = paragraph(value, rows = 0, foreground = foreground).apply {
        this.font = font
        columns = 0
        setSize(300, Short.MAX_VALUE.toInt())
    }
    val lineHeight = text.getFontMetrics(font).height
    val height = text.preferredSize.height.coerceAtLeast(lineHeight)
    text.maximumSize = Dimension(Int.MAX_VALUE, height)
    return if (height > lineHeight * 4) scroll(text, height = lineHeight * 4).apply {
        text.isFocusable = true
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    } else text
}
