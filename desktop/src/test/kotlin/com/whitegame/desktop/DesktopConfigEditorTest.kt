package com.whitegame.desktop

import com.whitegame.app.connection.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class DesktopConfigEditorTest {
    @Test
    fun editsFlowBetweenModesAndInvalidTextStaysInTheEditor() {
        SwingUtilities.invokeAndWait {
            val privateKey = Keys.encode(ByteArray(32) { 1 })
            val peerKey = Keys.encode(ByteArray(32) { 2 })
            val source = """
                [Interface]
                PrivateKey = $privateKey
                Address = 10.0.0.2/32
                # Keep this comment

                [Peer]
                PublicKey = $peerKey
                Endpoint = original.example:51820
            """.trimIndent()
            val errors = mutableListOf<String>()
            val editor = DesktopConfigEditor(DesktopStyle(true), source, openFields = true) { errors.add(it) }
            fun components() = descendants(editor.component)
            fun tab(label: String) = components().filterIsInstance<JButton>()
                .first { it.accessibleContext.accessibleName == label }

            val endpoint = components().filterIsInstance<JTextField>().first { it.name == "tunnel-field-1-Endpoint" }
            endpoint.text = "updated.example:1234"
            tab("Config text").doClick(0)
            val raw = components().filterIsInstance<JTextArea>().single { it.name == "tunnel-config-text" }
            assertTrue(raw.text.contains("Endpoint = updated.example:1234"))
            assertTrue(raw.text.contains("# Keep this comment"))

            raw.text = raw.text.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/42")
            val invalid = raw.text
            tab("WireGuard fields").doClick(0)
            assertEquals(1, errors.size)
            assertEquals(invalid, editor.text())

            raw.text = raw.text.replace("/42", "/24")
            tab("WireGuard fields").doClick(0)
            val address = components().filterIsInstance<JTextField>().first { it.name == "tunnel-field-0-Address" }
            assertEquals("10.0.0.2/24", address.text)
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
