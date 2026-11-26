package com.whitegame.desktop

import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

internal fun desktopDnsPage(
    ui: DesktopStyle,
    model: DesktopState,
    drafts: DesktopDrafts,
    dialogs: DesktopDialogs,
    dispatch: (DesktopAction) -> Unit
): JComponent {
    val idle = model.busy == null
    val appliedIp = model.preferredDns?.let(::normalizedDnsAddress)
    val reports = model.dns.associateBy { normalizedDnsAddress(it.endpoint) ?: it.id }
    val catalogIps = model.resolvers.map(DesktopDnsResolver::addressKey).toSet()
    val historical = model.dns.mapNotNull(DesktopMeasurement::savedDnsResolver)
        .filterNot { it.addressKey in catalogIps }.distinctBy(DesktopDnsResolver::addressKey)
    val ranks = reports.keys.withIndex().associate { (index, ip) -> ip to index }
    val resolvers = (model.resolvers + historical).sortedWith(
        compareBy<DesktopDnsResolver> { ranks[it.addressKey] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }
    )
    val search = ui.field(drafts.dnsSearch).apply {
        name = "dns-provider-search"
        accessibleContext.accessibleName = "Find DNS provider or IP"
    }
    val extraIps = ui.field(drafts.extraResolverIps).apply {
        name = "dns-extra-ips"
        accessibleContext.accessibleName = "Extra resolver IPs for this scan"
        onEdit { drafts.extraResolverIps = it }
        isEnabled = idle
    }
    val matches = ui.label("")
    val details = JPanel(BorderLayout()).apply { isOpaque = false }
    var filtering = false
    val list = JList<DesktopDnsResolver>().apply {
        name = "dns-provider-list"
        accessibleContext.accessibleName = "DNS providers"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = ui.inset
        foreground = ui.trace
        fixedCellHeight = 44
        cellRenderer = object : ListCellRenderer<DesktopDnsResolver> {
            private val provider = ui.label("", ui.body)
            private val address = ui.label("", ui.monoSmall)
            private val outcome = ui.label("", ui.monoSmall)
            private val row = JPanel(BorderLayout(DesktopSpace.sm, 0)).apply {
                border = EmptyBorder(0, DesktopSpace.sm, 0, DesktopSpace.sm)
                add(ui.column(provider, address, gap = 0), BorderLayout.CENTER)
                add(outcome, BorderLayout.EAST)
            }

            override fun getListCellRendererComponent(
                list: JList<out DesktopDnsResolver>, value: DesktopDnsResolver?, index: Int,
                selected: Boolean, hasFocus: Boolean
            ): JComponent {
                val report = value?.let { reports[it.addressKey] }
                provider.text = value?.name.orEmpty()
                address.text = value?.address.orEmpty()
                outcome.text = when {
                    value == null -> ""
                    report == null -> "Untested"
                    report.latencyMs == null -> "No response"
                    else -> measurementValue(report.latencyMs, "ms")
                }
                provider.foreground = if (selected) ui.ink else ui.trace
                address.foreground = if (selected) ui.ink else ui.trace2
                outcome.foreground = provider.foreground
                row.background = if (selected) ui.trace else ui.inset
                row.accessibleContext.accessibleName = "${provider.text}, ${address.text}, ${outcome.text}"
                return row
            }
        }
    }

    fun renderSelection() {
        val entry = list.selectedValue
        if (entry != null) drafts.dnsResolverId = entry.id
        details.removeAll()
        details.add(if (entry == null) ui.notice("No DNS resolvers match the current filters.") else {
            val report = reports[entry.addressKey]
            val installed = entry.addressKey == appliedIp
            val facts = buildList {
                add(DesktopField("Address", entry.address))
                if (installed) add(DesktopField("DNS override", "Applied"))
                add(DesktopField("Test result", when {
                    report == null -> "Untested"
                    report.latencyMs == null -> "No response"
                    else -> report.status.ifBlank { "Responded" }
                }))
                if (report != null) {
                    add(DesktopField("DNS latency", measurementValue(report.latencyMs, "ms", "Unavailable")))
                    add(DesktopField("Jitter", measurementValue(report.jitterMs, "ms", "Unavailable")))
                    add(DesktopField("Failed queries", measurementValue(report.lossPercent, "%", "Unavailable")))
                }
            }
            ui.column(*listOfNotNull(
                ui.section("Selected resolver"),
                ui.resultText(entry.name, ui.subtitle, ui.trace),
                entry.notes.takeIf(String::isNotBlank)?.let { ui.resultText(it) },
                ui.table(facts),
                ui.grid(
                    ui.button("Test resolver", enabled = idle) {
                        dispatch(DesktopAction.TestDns(resolverId = entry.id))
                    },
                    ui.button(if (installed) "Applied" else "Use DNS", enabled = idle && !installed) {
                        dispatch(DesktopAction.ApplyDns(entry.address))
                    }
                ),
                if (entry.custom) ui.grid(
                    ui.button("Edit resolver", enabled = idle) { dialogs.editDnsResolver(entry) },
                    ui.button("Remove", enabled = idle) {
                        if (dialogs.confirm("Remove ${entry.name} from the DNS catalog?", "Remove resolver")) {
                            dispatch(DesktopAction.DeleteDnsResolver(entry.id))
                        }
                    }
                ) else ui.button("Copy to custom", enabled = idle) {
                    dialogs.editDnsResolver(entry, copy = true)
                }
            ).toTypedArray())
        }, BorderLayout.NORTH)
        details.revalidate()
        details.repaint()
    }

    fun filterProviders() {
        val previous = drafts.dnsResolverId
        val keyword = search.text.trim()
        val ip = normalizedDnsAddress(keyword)
        val entries = resolvers.filter {
            (!drafts.dnsResultsOnly || reports.containsKey(it.addressKey)) && (keyword.isEmpty() ||
                it.name.contains(keyword, ignoreCase = true) ||
                it.address.contains(keyword, ignoreCase = true) ||
                it.notes.contains(keyword, ignoreCase = true) ||
                (ip != null && it.addressKey == ip))
        }
        filtering = true
        list.setListData(entries.toTypedArray())
        val position = entries.indexOfFirst { it.id == previous }
        if (entries.isNotEmpty()) {
            list.selectedIndex = position.takeIf { it >= 0 } ?: 0
            if (position >= 0) list.ensureIndexIsVisible(position)
        }
        filtering = false
        matches.text = "Showing ${entries.size} of ${resolvers.size} resolvers"
        renderSelection()
    }

    list.addListSelectionListener { if (!filtering && !it.valueIsAdjusting) renderSelection() }
    search.onEdit {
        drafts.dnsSearch = it
        filterProviders()
    }
    filterProviders()
    val summary = model.resolvers.firstOrNull { it.addressKey == appliedIp }?.address
        ?: model.preferredDns ?: "System DNS"
    val responded = reports.values.count { it.latencyMs != null }
    val controls = ui.column(
        ui.section("DNS resolvers", "Measure IPv4 and IPv6 resolver response times"),
        ui.box(ui.column(
            ui.dataRow("DNS override", summary),
            ui.button("Restore system DNS", enabled = idle && model.preferredDns != null) {
                dispatch(DesktopAction.ApplyDns(null))
            }
        )),
        ui.grid(
            ui.button("Test all resolvers", primary = true, enabled = idle && model.resolvers.isNotEmpty()) {
                dispatch(DesktopAction.TestDns(extraResolvers = extraIps.text))
            },
            ui.button("Add resolver", enabled = idle) { dialogs.editDnsResolver() }
        ),
        ui.legend("Extra resolver IPs for this scan"),
        extraIps,
        ui.legend("Find provider or IP"),
        search,
        ui.toggle("Show tested resolvers only", drafts.dnsResultsOnly, reports.isNotEmpty() || drafts.dnsResultsOnly) {
            drafts.dnsResultsOnly = it
            filterProviders()
        },
        if (reports.isEmpty()) ui.label("No DNS tests yet", ui.small, ui.trace2) else
            ui.label("${reports.size} tested · $responded responded · ${reports.size - responded} no response", ui.small, ui.trace2),
        matches,
        ui.scroll(list, height = list.fixedCellHeight * 6),
        ui.button("Save report", enabled = idle && reports.isNotEmpty()) {
            dispatch(DesktopAction.SaveReport(DesktopReportKind.DNS))
        }
    )
    return ui.grid(controls, details, gap = DesktopSpace.lg)
}
