package com.whitegame.desktop

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
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

internal class DesktopNavigation {
    data class Location(val page: DesktopPage, val tab: DesktopNetworkTab, val scrollPosition: Int = 0)

    private val history = ArrayDeque<Location>()
    var current = Location(DesktopPage.CONNECT, DesktopNetworkTab.DNS)
        private set
    val canGoBack get() = history.isNotEmpty()

    fun open(page: DesktopPage, tab: DesktopNetworkTab, scrollPosition: Int): Boolean {
        if (page == current.page && tab == current.tab) return false
        history.addLast(current.copy(scrollPosition = scrollPosition))
        current = Location(page, tab)
        return true
    }

    fun goBack(): Boolean {
        current = history.removeLastOrNull() ?: return false
        return true
    }
}

internal class DesktopDrafts {
    var configs = ""
    var dnsSearch = ""
    var extraResolverIps = ""
    var dnsResolverId: String? = null
    var dnsResultsOnly = false
    var endpoints = ""
    var subscription: String? = null
    var catalog: String? = null
    var gameSearch = ""
    var gameScrollPosition = 0
    var installedOnly = false
}

internal class DesktopWindow(private val actions: DesktopActions) : JFrame("WhiteGame") {
    private var state = DesktopState()
    private val navigation = DesktopNavigation()
    private val page get() = navigation.current.page
    private val networkTab get() = navigation.current.tab
    private val drafts = DesktopDrafts()
    private var view = WindowView(DesktopStyle(state.dark), ::stop)
    private var bodyState: List<Any?>? = null
    private var headerState: List<Any?>? = null
    private var tabState: Pair<DesktopPage, DesktopNetworkTab>? = null
    private var refreshPending = false
    private var uptimeLabel: javax.swing.JLabel? = null
    private var sessionView: ((DesktopState) -> Unit)? = null
    private val clock = Timer(1000) {
        uptimeLabel?.let { label ->
            val time = uptime(state.connectionSince)
            if (label.text != time) label.text = time
        }
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
        for (key in listOf("ESCAPE", "alt LEFT")) {
            rootPane.registerKeyboardAction({ goBack() }, KeyStroke.getKeyStroke(key), JComponent.WHEN_IN_FOCUSED_WINDOW)
        }
        contentPane = view.shell
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
        if (!refreshPending) {
            refreshPending = true
            SwingUtilities.invokeLater {
                refreshPending = false
                refresh()
            }
        }
    }

    private fun refresh(scrollPosition: Int? = null) {
        val body = visibleState()
        val headerKey = listOf(page, navigation.canGoBack, state.engineSummary.takeIf { page == DesktopPage.CONNECT })
        val tabs = page to networkTab
        val paletteChanged = view.style.dark != state.dark
        val replaceBody = paletteChanged || bodyState != body
        val rebuildChrome = paletteChanged || headerState != headerKey || tabState != tabs
        val oldPosition = scrollPosition ?: view.pane.verticalScrollBar.value
        val focus = if (replaceBody && scrollPosition == null) focusedEditor() else null

        if (paletteChanged) {
            view = WindowView(DesktopStyle(state.dark), ::stop)
            contentPane = view.shell
        }
        val style = view.style
        if (paletteChanged || headerState != headerKey) {
            view.header.replace(style.column(header(style), style.hairline(), gap = DesktopSpace.md))
            headerState = headerKey
        }
        if (paletteChanged || tabState != tabs) {
            val mainTabs = DesktopPage.entries.map { destination ->
                style.tab(destination.label, destination.icon, page == destination) { navigate(destination) }
            }
            view.navigation.replace(style.column(style.hairline(), style.grid(*mainTabs.toTypedArray()), gap = 0))
            view.networkTabs.isVisible = page == DesktopPage.NETWORK
            if (view.networkTabs.isVisible) {
                val sections = DesktopNetworkTab.entries.map { section ->
                    style.tab(section.label, "", networkTab == section) { navigate(DesktopPage.NETWORK, section) }
                }
                view.networkTabs.replace(style.box(style.grid(*sections.toTypedArray()), padded = false))
            }
            tabState = tabs
        }
        if (replaceBody) {
            uptimeLabel = null
            sessionView = null
            val pages = DesktopPages(style, state, drafts, actions::dispatch, this, ::showConfigTest)
            view.content.replace(when (page) {
                DesktopPage.CONNECT -> pages.connect(
                    openTunnels = { navigate(DesktopPage.TUNNELS) },
                    observeUptime = { uptimeLabel = it },
                    observeSession = { sessionView = it }
                )
                DesktopPage.TUNNELS -> pages.tunnels()
                DesktopPage.NETWORK -> pages.network(networkTab)
                DesktopPage.DEVICE -> pages.device(::openReport)
            }, BorderLayout.NORTH)
            bodyState = body
        } else {
            sessionView?.invoke(state)
        }
        val feedbackChanged = view.showFeedback(state.busy, state.notice)
        if (replaceBody || rebuildChrome || feedbackChanged || scrollPosition != null) {
            validate()
            if (view.pane.verticalScrollBar.value != oldPosition) view.pane.verticalScrollBar.value = oldPosition
            focus?.let { selection ->
                val replacement = findNamed(view.content, selection.name) as? JTextComponent
                if (replacement?.isEnabled == true) {
                    replacement.requestFocusInWindow()
                    replacement.caret.setDot(selection.mark.coerceAtMost(replacement.document.length))
                    replacement.caret.moveDot(selection.dot.coerceAtMost(replacement.document.length))
                }
            }
        }
    }

    private data class EditorSelection(val name: String, val dot: Int, val mark: Int)

    private fun focusedEditor(): EditorSelection? {
        val field = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JTextComponent
        val name = field?.name ?: return null
        if (!SwingUtilities.isDescendingFrom(field, view.content)) return null
        return EditorSelection(name, field.caret.dot, field.caret.mark)
    }

    private fun stop() = actions.dispatch(DesktopAction.Stop)

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
        val controls = if (!navigation.canGoBack) engines else style.split(
            style.button("Back", action = ::goBack).apply { toolTipText = "Go back (Alt+Left or Esc)" },
            engines
        )
        return style.split(text, controls)
    }

    private fun navigate(destination: DesktopPage, tab: DesktopNetworkTab = networkTab) {
        val currentScroll = view.pane.verticalScrollBar.value
        if (navigation.open(destination, tab, currentScroll)) refreshNavigation()
    }

    private fun goBack() {
        if (navigation.goBack()) refreshNavigation()
    }

    private fun refreshNavigation() {
        refresh(scrollPosition = navigation.current.scrollPosition)
    }

    private fun showConfigTest(profile: DesktopProfile) {
        drafts.configs = profile.config
        navigate(DesktopPage.NETWORK, DesktopNetworkTab.CONFIGS)
        actions.dispatch(DesktopAction.TestConfigs(profile.config))
    }

    private fun openReport(report: DesktopReport) {
        val tab = when (report.kind) {
            DesktopReportKind.DNS -> {
                drafts.dnsSearch = ""
                drafts.dnsResultsOnly = true
                DesktopNetworkTab.DNS
            }
            DesktopReportKind.CONFIGS -> DesktopNetworkTab.CONFIGS
            DesktopReportKind.GAMES -> DesktopNetworkTab.GAMES
            DesktopReportKind.SPEED -> DesktopNetworkTab.SPEED
        }
        navigate(DesktopPage.NETWORK, tab)
        actions.dispatch(DesktopAction.LoadReport(report.id))
    }

    private fun visibleState(): List<Any?> {
        val shared = listOf(page, state.busy != null)
        return shared + when (page) {
            DesktopPage.CONNECT -> listOf(
                state.profiles, state.selectedId, state.activeId, state.phase, state.connectionMessage,
                state.preferredDns, state.autoReconnect, state.killSwitch,
                state.recoveryRequired, state.optimizer, state.suggestedDns
            )
            DesktopPage.TUNNELS -> listOf(
                state.profiles, state.selectedId, state.activeId, state.phase, state.endpointTests,
                state.subscription, state.subscriptionUrl
            )
            DesktopPage.NETWORK -> listOf(networkTab) + when (networkTab) {
                DesktopNetworkTab.DNS -> listOf(state.resolvers, state.dns, state.preferredDns)
                DesktopNetworkTab.CONFIGS -> listOf(state.configTests, state.profiles, state.phase)
                DesktopNetworkTab.GAMES -> listOf(state.games, state.gamesScanned, state.gameTests, state.catalogUrl)
                DesktopNetworkTab.SPEED -> listOf(state.speed)
            }
            DesktopPage.DEVICE -> listOf(state.device, state.devicePath, state.engines, state.reports)
        }
    }

    private fun findNamed(root: Component, id: String): Component? {
        if (root.name == id) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { findNamed(it, id) }
    }

    private class WindowView(val style: DesktopStyle, onStop: () -> Unit) {
        val header = slot().apply { border = EmptyBorder(DesktopSpace.edge, DesktopSpace.edge, DesktopSpace.sm, DesktopSpace.edge) }
        val networkTabs = slot().apply { border = EmptyBorder(0, DesktopSpace.edge, DesktopSpace.sm, DesktopSpace.edge) }
        val navigation = slot()
        val content = DesktopScrollContent().apply {
            isOpaque = false
            border = EmptyBorder(DesktopSpace.sm, DesktopSpace.edge, DesktopSpace.xl, DesktopSpace.edge)
        }
        val pane = style.scroll(content)
        private val message = style.paragraph("", rows = 2, foreground = style.trace)
        private val stop = style.button("Stop", action = onStop)
        private val feedback = slot().apply {
            isVisible = false
            border = EmptyBorder(0, DesktopSpace.edge, DesktopSpace.sm, DesktopSpace.edge)
            add(style.box(style.split(message, stop).apply {
                border = EmptyBorder(DesktopSpace.xs, DesktopSpace.md, DesktopSpace.xs, DesktopSpace.xs)
            }, padded = false), BorderLayout.CENTER)
        }
        val shell = JPanel(BorderLayout()).apply {
            background = style.ink
            add(slot().apply {
                add(header, BorderLayout.NORTH)
                add(networkTabs, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(pane, BorderLayout.CENTER)
            add(slot().apply {
                add(feedback, BorderLayout.NORTH)
                add(navigation, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }

        fun showFeedback(busy: String?, notice: String): Boolean {
            val text = listOfNotNull(busy, notice.takeIf(String::isNotBlank)).joinToString("\n")
            val visible = text.isNotBlank()
            val stoppable = busy != null
            val layoutChanged = feedback.isVisible != visible || stop.isVisible != stoppable
            if (message.text != text) {
                message.text = text
                message.toolTipText = text.takeIf(String::isNotBlank)
            }
            feedback.isVisible = visible
            stop.isVisible = stoppable
            return layoutChanged
        }

        private fun slot() = JPanel(BorderLayout()).apply { isOpaque = false }
    }

}

internal class DesktopScrollContent : JPanel(BorderLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = DesktopSpace.xl
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) (visibleRect.height - DesktopSpace.xl).coerceAtLeast(DesktopSpace.xl)
        else visibleRect.width
}

private fun JPanel.replace(value: JComponent, position: String = BorderLayout.CENTER) {
    removeAll()
    add(value, position)
    revalidate()
    repaint()
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
