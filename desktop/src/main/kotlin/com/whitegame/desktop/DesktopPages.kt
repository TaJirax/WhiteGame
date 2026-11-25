package com.whitegame.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

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

    fun connect(openTunnels: () -> Unit, observeUptime: (JLabel) -> Unit): JComponent {
        val ready = model.phase == DesktopPhase.CONNECTED
        val isLive = model.phase.isLive
        val activeProfile = model.profiles.firstOrNull { it.id == model.activeId }
        val measured = model.latencyHistory.filterNotNull()
        val latest = model.latencyHistory.lastOrNull()
        val stateTrace = if (ready) ui.trace else ui.trace2
        val connectionTitle = if (ready) {
            ui.split(
                ui.column(
                    ui.legend("Latency"),
                    ui.label(metric(latest, "ms"), ui.readout)
                ),
                ui.column(ui.legend("Uptime"), ui.label(uptime(model.connectionSince), ui.mono).also(observeUptime))
            )
        } else {
            ui.column(
                ui.label(selected?.name ?: "No tunnel selected", ui.title),
                ui.paragraph(model.connectionMessage, rows = 2)
            )
        }
        val traceLegend = if (measured.isEmpty()) "Latency" else
            "Min ${metric(measured.minOrNull(), "ms")} · Avg ${metric(measured.average(), "ms")} · Max ${metric(measured.maxOrNull(), "ms")}"
        val instrument = ui.box(ui.column(
            ui.legend("●   ${model.phase.label}", stateTrace),
            connectionTitle,
            ui.latencyTrace(model.latencyHistory),
            ui.legend(traceLegend)
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
                        model.recoveryRequired -> "Restore Windows network"
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
        val session = model.session.ifEmpty {
            listOf(
                DesktopField("Handshake", "None yet"),
                DesktopField("Downloaded", "0 B"),
                DesktopField("Uploaded", "0 B"),
                DesktopField("Endpoint", activeProfile?.endpoint ?: "—"),
                DesktopField("DNS override", model.preferredDns ?: "System")
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
                ui.table(session),
                ui.spacer(DesktopSpace.sm),
                ui.section("Connection options"),
                ui.box(ui.column(
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

    fun network(tab: DesktopNetworkTab, openTab: (DesktopNetworkTab) -> Unit): JComponent {
        val bar = ui.box(
            ui.grid(*DesktopNetworkTab.entries.map { destination ->
                ui.tab(destination.label, "", tab == destination) { openTab(destination) }
            }.toTypedArray()),
            padded = false
        )
        val content = when (tab) {
            DesktopNetworkTab.DNS -> dns()
            DesktopNetworkTab.CONFIGS -> configs()
            DesktopNetworkTab.GAMES -> games()
            DesktopNetworkTab.SPEED -> speed()
        }
        return ui.column(bar, content, gap = DesktopSpace.md)
    }

    private fun dns(): JComponent {
        val dnsOverride = model.preferredDns
        val actions = ui.column(
            ui.section("DNS resolvers", "Measure latency, jitter, and failed DNS queries"),
            ui.box(ui.column(
                ui.dataRow("DNS override", dnsOverride ?: "System DNS"),
                ui.button("Restore system DNS", enabled = idle && dnsOverride != null) { dispatch(DesktopAction.ApplyDns(null)) }
            )),
            editArea("Additional resolver IPs", "dns-draft", drafts.resolvers, rows = 3) { drafts.resolvers = it },
            ui.grid(
                ui.button("Test DNS resolvers", primary = true, enabled = idle) { dispatch(DesktopAction.TestDns(drafts.resolvers)) },
                saveButton(DesktopReportKind.DNS, model.dns.isNotEmpty())
            )
        )
        val results = if (model.dns.isEmpty()) ui.notice("Run the resolver test to compare DNS response times.")
        else ui.column(
            ui.section("Resolver results", "${model.dns.size} measured"),
            *model.dns.map { row ->
                val ip = row.endpoint.ifBlank { row.name }
                measurement(row, latencyLabel = "DNS latency", extra = ui.button(
                    if (ip == dnsOverride) "Applied" else "Use DNS",
                    enabled = idle && row.latencyMs != null && ip != dnsOverride
                ) { dispatch(DesktopAction.ApplyDns(ip)) })
            }.toTypedArray()
        )
        return twoColumns(actions, results)
    }

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
        val results = if (model.configTests.isEmpty()) ui.notice("Measured profiles will appear here with their speed and ping.")
        else ui.column(
            ui.section("Config results"),
            *model.configTests.map { measurement(it, forceBandwidth = true, extra = copyButton(it)) }.toTypedArray()
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
        val result = model.speed ?: DesktopMeasurement("speed", "Speed results")
        return twoColumns(actions, measurement(result, forceBandwidth = true, extra = model.speed?.let(::copyButton)))
    }

    private fun games(): JComponent {
        val search = ui.field(drafts.gameSearch).apply { name = "game-search" }
        val list = JPanel(BorderLayout()).apply { isOpaque = false }
        fun updateMatches() {
            val keyword = search.text.trim()
            val matching = model.games.filter { game ->
                (!drafts.installedOnly || game.installed) &&
                    (keyword.isEmpty() || game.title.contains(keyword, ignoreCase = true))
            }
            val rows = matching.map(::game)
            list.removeAll()
            list.add(ui.column(*rows.ifEmpty {
                listOf(ui.notice(if (model.games.isEmpty()) "Scan installed games or update the catalog." else "No matching games."))
            }.toTypedArray()), BorderLayout.NORTH)
            list.revalidate()
            list.repaint()
        }
        search.onEdit {
            drafts.gameSearch = it
            updateMatches()
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
                ui.button("Scan installed games", primary = true, enabled = idle) { dispatch(DesktopAction.DiscoverGames) },
                ui.toggle("Installed only", drafts.installedOnly) {
                    drafts.installedOnly = it
                    updateMatches()
                }
            ),
            ui.legend("Search games"),
            search,
            list
        )
        val results = ui.column(
            ui.section("Game results", "HTTPS measurements to catalog endpoints"),
            saveButton(DesktopReportKind.GAMES, model.gameTests.isNotEmpty()),
            *model.gameTests.map { measurement(it, extra = copyButton(it)) }.toTypedArray(),
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
            ui.split(ui.label(game.title, ui.subtitle), ui.legend(if (game.installed) "Installed" else "Catalog")),
            *listOfNotNull(game.detail.takeIf(String::isNotBlank)?.let { ui.paragraph(it, rows = 1) }).toTypedArray(),
            ui.grid(
                ui.button("Test latency", enabled = idle && game.canTest) { dispatch(DesktopAction.TestGame(game.id)) },
                ui.button("Compare routes", enabled = idle && game.canTest) { dispatch(DesktopAction.CompareGame(game.id)) }
            )
        )
        return ui.box(group)
    }

    fun device(loadReport: (DesktopReport) -> Unit): JComponent {
        val state = ui.column(
            ui.section("PC and network"),
            ui.button("Run device scan", primary = true, enabled = idle) { dispatch(DesktopAction.InspectDevice) },
            if (model.device.isEmpty()) ui.notice("Inspect this PC to see its network and hardware details.") else ui.table(model.device),
            *model.devicePath.takeIf { it.isNotEmpty() }?.let { arrayOf(ui.section("Network path"), ui.table(it)) }.orEmpty(),
            ui.spacer(DesktopSpace.sm),
            ui.section("Engines"),
            if (model.engines.isEmpty()) ui.notice("Inspect this PC to check available tunnel engines.") else ui.table(model.engines)
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

    private fun measurement(
        result: DesktopMeasurement,
        latencyLabel: String = "HTTPS ping",
        forceBandwidth: Boolean = false,
        extra: JComponent? = null
    ): JComponent {
        val fields = buildList {
            add(DesktopField(latencyLabel, metric(result.latencyMs, "ms")))
            if (forceBandwidth || result.downloadMbps != null || result.uploadMbps != null) {
                add(DesktopField("Download", metric(result.downloadMbps, "Mbps")))
                add(DesktopField("Upload", metric(result.uploadMbps, "Mbps")))
            }
            if (forceBandwidth || result.jitterMs != null) add(DesktopField("Jitter", metric(result.jitterMs, "ms")))
            if (result.lossPercent != null) add(DesktopField("Failed attempts", metric(result.lossPercent, "%")))
        }
        val headline = ui.split(ui.label(result.name, ui.subtitle), ui.legend(result.status))
        val subtitle = listOf(result.protocol, result.endpoint).filter(String::isNotBlank).joinToString(" · ")
        return ui.box(ui.column(*listOfNotNull(
            headline,
            subtitle.takeIf(String::isNotBlank)?.let { ui.label(it, ui.monoSmall, ui.trace2) },
            ui.table(fields),
            result.detail.takeIf(String::isNotBlank)?.let { ui.paragraph(it, rows = 2) },
            extra
        ).toTypedArray()))
    }

    private fun saveButton(kind: DesktopReportKind, enabled: Boolean) =
        ui.button("Save report", enabled = idle && enabled) { dispatch(DesktopAction.SaveReport(kind)) }

    private fun copyButton(result: DesktopMeasurement) = ui.button("Copy report") { dialogs.copy(asText(result)) }

    private fun asText(result: DesktopMeasurement): String = buildList {
        add(result.name)
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

    private fun metric(value: Double?, unit: String): String {
        if (value == null || !value.isFinite()) return "—"
        val rendered = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
        return if (unit == "%") "$rendered%" else "$rendered $unit"
    }
}
