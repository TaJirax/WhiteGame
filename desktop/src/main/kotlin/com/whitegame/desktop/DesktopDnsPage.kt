package com.whitegame.desktop

import java.awt.BorderLayout
import java.awt.Component
import java.text.NumberFormat
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
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
    val historical = model.dns.mapNotNull { row ->
        val ip = normalizedDnsAddress(row.endpoint) ?: return@mapNotNull null
        if (ip in catalogIps) null else DesktopDnsResolver("report-$ip", row.name, row.endpoint, row.detail)
    }
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
    val list = JList<DesktopDnsResolver>().apply {
        name = "dns-provider-list"
        accessibleContext.accessibleName = "DNS providers"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = ui.inset
        foreground = ui.trace
        fixedCellHeight = DesktopSpace.xxl
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, "", index, selected, hasFocus)
                val entry = value as? DesktopDnsResolver
                putClientProperty("html.disable", true)
                text = entry?.let {
                    val status = reports[it.addressKey]?.let { row ->
                        row.latencyMs?.let { ping -> dnsReadout(ping, "ms") } ?: row.status
                    } ?: "Untested"
                    "${it.name} · ${it.address} · $status"
                }.orEmpty()
                font = ui.small
                foreground = if (selected) ui.ink else ui.trace
                background = if (selected) ui.trace else ui.inset
                border = EmptyBorder(0, DesktopSpace.sm, 0, DesktopSpace.sm)
                return this
            }
        }
    }

    fun renderSelection() {
        val entry = list.selectedValue
        drafts.dnsResolverId = entry?.id
        details.removeAll()
        details.add(if (entry == null) ui.notice("Choose a DNS resolver from the catalog.") else {
            val report = reports[entry.addressKey]
            val installed = entry.addressKey == appliedIp
            val isCatalogEntry = !entry.id.startsWith("report-")
            val facts = listOf(
                DesktopField("Address", entry.address),
                DesktopField("Status", if (installed) "Applied" else report?.status ?: "Untested"),
                DesktopField("DNS latency", dnsReadout(report?.latencyMs, "ms")),
                DesktopField("Jitter", dnsReadout(report?.jitterMs, "ms")),
                DesktopField("Failed attempts", dnsReadout(report?.lossPercent, "%"))
            )
            ui.column(*listOfNotNull(
                ui.section("Selected resolver", entry.name),
                entry.notes.takeIf(String::isNotBlank)?.let { ui.paragraph(it, rows = 1) },
                ui.table(facts),
                ui.grid(
                    ui.button("Test resolver", enabled = idle && isCatalogEntry) {
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
            keyword.isEmpty() ||
                it.name.contains(keyword, ignoreCase = true) ||
                it.address.contains(keyword, ignoreCase = true) ||
                it.notes.contains(keyword, ignoreCase = true) ||
                (ip != null && it.addressKey == ip)
        }
        list.setListData(entries.toTypedArray())
        val position = entries.indexOfFirst { it.id == previous }
        if (entries.isNotEmpty()) {
            list.selectedIndex = position.takeIf { it >= 0 } ?: 0
            if (position >= 0) list.ensureIndexIsVisible(position)
        }
        matches.text = "${entries.size} of ${resolvers.size} resolvers"
        renderSelection()
    }

    list.addListSelectionListener { if (!it.valueIsAdjusting) renderSelection() }
    search.onEdit {
        drafts.dnsSearch = it
        filterProviders()
    }
    filterProviders()
    val summary = model.resolvers.firstOrNull { it.addressKey == appliedIp }?.address
        ?: model.preferredDns ?: "System DNS"
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
        matches,
        ui.scroll(list, height = DesktopSpace.xxl * 8),
        ui.button("Save report", enabled = idle && reports.isNotEmpty()) {
            dispatch(DesktopAction.SaveReport(DesktopReportKind.DNS))
        }
    )
    return ui.grid(controls, details, gap = DesktopSpace.lg)
}

private fun dnsReadout(value: Double?, unit: String): String = value?.let {
    val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    format.format(it) + if (unit == "%") unit else " $unit"
} ?: "—"
