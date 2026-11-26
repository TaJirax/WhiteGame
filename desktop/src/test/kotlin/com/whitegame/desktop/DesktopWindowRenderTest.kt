package com.whitegame.desktop

import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopWindowRenderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun scanProgressKeepsViewportAndControlsMounted() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        var state = DesktopState(busy = "Measuring resolvers · 1/514")
        lateinit var window: DesktopWindow
        SwingUtilities.invokeAndWait {
            window = DesktopWindow { action ->
                if (action is DesktopAction.Theme) {
                    state = state.copy(dark = action.dark)
                    window.render(state)
                }
            }
        }
        try {
            lateinit var shell: Container
            lateinit var scroll: JScrollPane
            lateinit var body: Component
            lateinit var theme: JButton
            SwingUtilities.invokeAndWait {
                window.isVisible = true
                window.button("Network").doClick(0)
                window.render(state)
            }
            SwingUtilities.invokeAndWait {
                shell = window.contentPane
                scroll = shell.descendants().filterIsInstance<JScrollPane>().first()
                body = (scroll.viewport.view as Container).getComponent(0)
                theme = window.button("Switch to light mode")
                state = state.copy(busy = "Measuring resolvers · 240/514")
                window.render(state)
            }
            SwingUtilities.invokeAndWait {
                assertSame(shell, window.contentPane)
                assertSame(body, (scroll.viewport.view as Container).getComponent(0))
                assertSame(scroll, shell.descendants().filterIsInstance<JScrollPane>().first())
                assertSame(theme, window.button("Switch to light mode"))
                assertTrue(shell.descendants().filterIsInstance<JTextArea>().any { it.text == state.busy })
                theme.doClick(0)
            }
            SwingUtilities.invokeAndWait {
                assertEquals(DesktopStyle(false).ink, window.contentPane.background)
                window.button("Switch to dark mode")
            }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    @Test
    fun themePreferenceIsSavedWhileTheControllerIsBusy() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(DesktopPlatform.linux)
        val store = DesktopStore(folder.newFolder().toPath())
        val closed = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            val controller = DesktopController(store) { closed.countDown() }
            try {
                controller.show()
                controller.dispatch(DesktopAction.Theme(false))
                assertFalse(DesktopStore(store.directory).flag("dark", true))
            } finally {
                controller.dispatch(DesktopAction.Close)
            }
        }
        assertTrue(closed.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun dnsResultsShowFailuresAndKeepExtraResolversSelectable() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val actions = mutableListOf<DesktopAction>()
        val state = DesktopState(
            resolvers = listOf(DesktopDnsResolver("catalog", "Example catalog resolver", "192.0.2.10")),
            dns = listOf(
                DesktopMeasurement("192.0.2.11", "Example extra resolver", "192.0.2.11", latencyMs = 12.0),
                DesktopMeasurement("192.0.2.10", "Example catalog resolver", "192.0.2.10", lossPercent = 100.0)
            )
        )
        lateinit var window: DesktopWindow
        SwingUtilities.invokeAndWait {
            window = DesktopWindow { actions.add(it) }
        }
        try {
            SwingUtilities.invokeAndWait {
                window.isVisible = true
                window.button("Network").doClick(0)
                window.render(state)
            }
            SwingUtilities.invokeAndWait {
                val labels = window.contentPane.descendants().filterIsInstance<JLabel>().map { it.text }.toList()
                assertTrue(labels.any { it.contains("2 tested") && it.contains("1 no response") })
                val list = window.contentPane.descendants().filterIsInstance<JList<*>>().single()
                assertEquals(2, list.model.size)
                list.selectedIndex = 0
                window.button("Test resolver").doClick(0)
                assertEquals(DesktopAction.TestDns(resolverId = "report-192.0.2.11"), actions.single())
                list.selectedIndex = 1
                assertTrue(window.contentPane.descendants().filterIsInstance<JLabel>().any { it.text == "No response" })
            }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun DesktopWindow.button(name: String) = contentPane.descendants().filterIsInstance<JButton>()
        .first { it.accessibleContext.accessibleName == name }

    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            for (child in this@descendants.components) yieldAll(child.descendants())
        }
    }
}
