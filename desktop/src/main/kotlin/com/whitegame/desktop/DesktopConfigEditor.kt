package com.whitegame.desktop

import com.whitegame.app.connection.ConfigException
import com.whitegame.app.xray.XrayConfig
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.text.JTextComponent

/** Fields and raw text share one draft, so switching modes preserves all pending edits. */
internal class DesktopConfigEditor(
    private val style: DesktopStyle,
    text: String,
    openFields: Boolean,
    private val reportError: (String) -> Unit
) {
    private val raw = style.editor(text, 12).apply {
        name = "tunnel-config-text"
        accessibleContext.accessibleName = "Config text"
    }
    private val tabs = JPanel(BorderLayout()).apply { isOpaque = false }
    private val view = JPanel(BorderLayout()).apply { isOpaque = false }
    private var fields = if (openFields) runCatching { FieldEditor(style, WireGuardEditDocument.parse(text)) }.getOrNull()
        else null

    val component = style.column(tabs, view)

    init {
        refresh()
    }

    fun text(): String = fields?.render() ?: raw.text

    fun hasDraft(): Boolean = fields != null || raw.text.isNotBlank()

    fun replace(text: String) {
        clearFields()
        raw.text = text
        raw.caretPosition = 0
        refresh()
    }

    private fun showFields() {
        if (fields != null) return
        if (XrayConfig.looksLikeXray(raw.text)) {
            reportError("Edit Xray share links or JSON outbounds in Config text.")
            return
        }
        fields = try {
            FieldEditor(style, WireGuardEditDocument.parse(raw.text))
        } catch (e: IllegalArgumentException) {
            reportError("Enter a valid WireGuard config first.")
            return
        } catch (e: ConfigException) {
            reportError(e.message ?: "Enter a valid WireGuard config first.")
            return
        }
        refresh()
    }

    private fun showText() {
        val editor = fields ?: return
        try {
            raw.text = editor.render()
        } catch (e: ConfigException) {
            reportError(e.message ?: "Could not display the draft as text.")
            return
        }
        raw.caretPosition = 0
        clearFields()
        refresh()
    }

    private fun clearFields() {
        fields?.clearSecrets()
        fields = null
    }

    private fun refresh() {
        tabs.removeAll()
        tabs.add(style.grid(
            style.tab("WireGuard fields", "", fields != null, ::showFields),
            style.tab("Config text", "", fields == null, ::showText)
        ))
        view.removeAll()
        val body = fields?.component ?: raw
        view.add(style.scroll(body, height = 315))
        component.revalidate()
        component.repaint()
    }

    private class FieldEditor(style: DesktopStyle, private val document: WireGuardEditDocument) {
        private val editors = document.sections.mapIndexed { index, section ->
            section.fields.associate { field ->
                val input = if (field.secret) style.password().apply { text = field.value } else style.field(field.value)
                input.name = "tunnel-field-$index-${field.key}"
                input.accessibleContext.accessibleName = "${section.title} ${field.key}"
                field.key to input
            }
        }

        val component = style.column(*document.sections.zip(editors).map { (section, inputs) ->
            val controls = section.fields.map { field ->
                style.column(style.legend(field.key), inputs.getValue(field.key), gap = DesktopSpace.xs)
            }
            style.box(style.column(
                style.label(section.title, style.subtitle),
                *controls.chunked(2).map {
                    style.grid(it[0], it.getOrElse(1) { style.spacer(1) })
                }.toTypedArray()
            ))
        }.toTypedArray()).apply {
            border = javax.swing.border.EmptyBorder(0, 0, 0, DesktopSpace.sm)
        }

        fun render(): String = document.render(editors.map { section ->
            section.mapValues { valueOf(it.value) }
        })

        fun clearSecrets() {
            editors.forEach { section -> section.values.filterIsInstance<JPasswordField>().forEach { it.text = "" } }
        }

        private fun valueOf(input: JTextComponent): String {
            if (input !is JPasswordField) return input.text
            val password = input.password
            return try { String(password) } finally { password.fill('\u0000') }
        }
    }
}
