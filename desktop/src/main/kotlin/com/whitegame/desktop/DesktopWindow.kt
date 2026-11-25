package com.whitegame.desktop

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

internal enum class DesktopPage(val label: String, val icon: String) {
    CONNECT("Connect", "ϟ"), TUNNELS("Tunnels", "▱"), NETWORK("Network", "⌁"), DEVICE("Device", "▣")
}

internal enum class DesktopNetworkTab(val label: String) {
    DNS("DNS"), CONFIGS("Configs"), GAMES("Games"), SPEED("Speed")
}

internal class DesktopDrafts {
    var configs = ""
    var resolvers = ""
    var endpoints = ""
    var subscription: String? = null
    var catalog: String? = null
    var gameSearch = ""
    var installedOnly = false
}

internal class DesktopWindow(private val actions: DesktopActions) : JFrame("WhiteGame") {
    private var state = DesktopState()
    private var page = DesktopPage.CONNECT
    private var networkTab = DesktopNetworkTab.DNS
    private val drafts = DesktopDrafts()
    private var visibleHash: List<Any?>? = null
    private var scroll: JScrollPane? = null
    private var uptimeLabel: javax.swing.JLabel? = null
    private val clock = Timer(1000) {
        uptimeLabel?.text = uptime(state.connectionSince)
    }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        javaClass.getResource("/logo_bw.png")?.let { iconImage = ImageIcon(it).image }
        minimumSize = Dimension(880, 610)
        size = Dimension(1120, 810)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = actions.dispatch(DesktopAction.Close)
            override fun windowClosed(e: WindowEvent) = clock.stop()
        })
        clock.start()
        refresh()
    }

    /** Background engine callbacks can call this method directly. */
    fun render(update: DesktopState) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { render(update) }
            return
        }
        state = update
        refresh()
    }

    private fun refresh(force: Boolean = false, fromTop: Boolean = false) {
        val fingerprint = visibleState()
        if (!force && fingerprint == visibleHash) return
        visibleHash = fingerprint
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JTextComponent
        val retainedFocus = focus?.takeIf { SwingUtilities.isDescendingFrom(it, this) }
        val position = if (fromTop) 0 else scroll?.verticalScrollBar?.value ?: 0
        val activeStyle = DesktopStyle(state.dark)
        uptimeLabel = null
        val ui = DesktopPages(activeStyle, state, drafts, actions::dispatch, this, ::showConfigTest)
        val body = when (page) {
            DesktopPage.CONNECT -> ui.connect(openTunnels = { navigate(DesktopPage.TUNNELS) }) { uptimeLabel = it }
            DesktopPage.TUNNELS -> ui.tunnels()
            DesktopPage.NETWORK -> ui.network(networkTab, ::openTab)
            DesktopPage.DEVICE -> ui.device(::openReport)
        }
        val shell = JPanel(BorderLayout()).apply { background = activeStyle.ink }
        val header = activeStyle.column(
            header(activeStyle),
            activeStyle.hairline(),
            gap = DesktopSpace.md
        ).apply { border = EmptyBorder(DesktopSpace.edge, DesktopSpace.edge, DesktopSpace.sm, DesktopSpace.edge) }
        shell.add(header, BorderLayout.NORTH)

        val content = ScreenContent().apply {
            isOpaque = false
            border = EmptyBorder(DesktopSpace.sm, DesktopSpace.edge, DesktopSpace.xl, DesktopSpace.edge)
            add(activeStyle.column(*listOfNotNull(
                state.busy?.let { status ->
                    activeStyle.box(activeStyle.split(
                        activeStyle.column(activeStyle.legend("Working"), activeStyle.paragraph(status, rows = 1)),
                        activeStyle.button("Stop") { actions.dispatch(DesktopAction.Stop) }
                    ))
                },
                state.notice.takeIf(String::isNotBlank)?.let { activeStyle.notice(it) },
                body
            ).toTypedArray(), gap = DesktopSpace.md), BorderLayout.NORTH)
        }
        val pane = activeStyle.scroll(content)
        scroll = pane
        shell.add(pane, BorderLayout.CENTER)
        val tabs = DesktopPage.entries.map { destination ->
            activeStyle.tab(destination.label, destination.icon, page == destination) { navigate(destination) }
        }
        val nav = activeStyle.column(activeStyle.hairline(), activeStyle.grid(*tabs.toTypedArray()), gap = 0)
        shell.add(nav, BorderLayout.SOUTH)
        contentPane = shell
        revalidate()
        repaint()
        SwingUtilities.invokeLater {
            if (scroll !== pane) return@invokeLater
            pane.verticalScrollBar.value = position
            val fieldName = retainedFocus?.name
            if (!fromTop && fieldName != null) {
                val replacement = findNamed(content, fieldName) as? JTextComponent
                if (replacement != null && replacement.isEnabled) {
                    replacement.requestFocusInWindow()
                    replacement.caretPosition = retainedFocus.caretPosition.coerceAtMost(replacement.document.length)
                }
            }
        }
    }

    private fun header(style: DesktopStyle): JComponent {
        val (title, subtitle) = when (page) {
            DesktopPage.CONNECT -> "White²" to "WireGuard | AmneziaWG | Xray"
            DesktopPage.TUNNELS -> "Tunnels" to "WireGuard, AmneziaWG and Xray profiles"
            DesktopPage.NETWORK -> "Network" to "Measure the path your games actually take"
            DesktopPage.DEVICE -> "Device" to "PC, network and saved results"
        }
        val text = style.column(style.label(title, style.brand), style.label(subtitle, style.small, style.trace2), gap = DesktopSpace.xs)
        val theme = style.button(if (state.dark) "Light mode" else "Dark mode") {
            actions.dispatch(DesktopAction.Theme(!state.dark))
        }.apply {
            val action = if (state.dark) "Switch to light mode" else "Switch to dark mode"
            toolTipText = action
            accessibleContext.accessibleName = action
        }
        val engines = if (page != DesktopPage.CONNECT || state.engineSummary.isBlank()) theme
        else style.split(style.label(state.engineSummary, style.monoSmall, style.trace2), theme)
        return style.split(text, engines)
    }

    private fun navigate(destination: DesktopPage) {
        if (destination == page) return
        page = destination
        refresh(force = true, fromTop = true)
    }

    private fun openTab(tab: DesktopNetworkTab) {
        if (tab == networkTab) return
        networkTab = tab
        refresh(force = true, fromTop = true)
    }

    private fun showConfigTest(profile: DesktopProfile) {
        drafts.configs = profile.config
        page = DesktopPage.NETWORK
        networkTab = DesktopNetworkTab.CONFIGS
        refresh(force = true, fromTop = true)
        actions.dispatch(DesktopAction.TestConfigs(profile.config))
    }

    private fun openReport(report: DesktopReport) {
        networkTab = when (report.kind) {
            DesktopReportKind.DNS -> DesktopNetworkTab.DNS
            DesktopReportKind.CONFIGS -> DesktopNetworkTab.CONFIGS
            DesktopReportKind.GAMES -> DesktopNetworkTab.GAMES
            DesktopReportKind.SPEED -> DesktopNetworkTab.SPEED
        }
        navigate(DesktopPage.NETWORK)
        actions.dispatch(DesktopAction.LoadReport(report.id))
    }

    private fun visibleState(): List<Any?> {
        val shared = listOf(page, state.dark, state.notice, state.busy, state.phase)
        return shared + when (page) {
            DesktopPage.CONNECT -> listOf(
                state.profiles, state.selectedId, state.activeId, state.connectionMessage, state.connectionSince,
                state.latencyHistory, state.session, state.engineSummary, state.preferredDns, state.autoReconnect, state.killSwitch,
                state.recoveryRequired, state.optimizer, state.suggestedDns
            )
            DesktopPage.TUNNELS -> listOf(
                state.profiles, state.selectedId, state.endpointTests, state.subscription, state.subscriptionUrl
            )
            DesktopPage.NETWORK -> listOf(networkTab) + when (networkTab) {
                DesktopNetworkTab.DNS -> listOf(state.dns, state.preferredDns)
                DesktopNetworkTab.CONFIGS -> listOf(state.configTests, state.profiles)
                DesktopNetworkTab.GAMES -> listOf(state.games, state.gameTests, state.catalogUrl)
                DesktopNetworkTab.SPEED -> listOf(state.speed)
            }
            DesktopPage.DEVICE -> listOf(state.device, state.devicePath, state.engines, state.reports)
        }
    }

    private fun findNamed(root: Component, id: String): Component? {
        if (root.name == id) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { findNamed(it, id) }
    }

    private class ScreenContent : JPanel(BorderLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            DesktopSpace.xl
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height - DesktopSpace.xl else visibleRect.width
    }
}

internal fun uptime(connectedSince: Long?): String {
    if (connectedSince == null) return "—"
    val seconds = ((System.currentTimeMillis() - connectedSince) / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

internal fun JTextComponent.onEdit(update: (String) -> Unit) {
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = update(text)
        override fun removeUpdate(e: DocumentEvent) = update(text)
        override fun changedUpdate(e: DocumentEvent) = update(text)
    })
}
