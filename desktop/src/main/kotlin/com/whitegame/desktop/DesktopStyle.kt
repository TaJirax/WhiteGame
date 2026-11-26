package com.whitegame.desktop

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.JTextComponent

internal object DesktopSpace {
    const val xs = 4
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 24
    const val xxl = 32
    const val edge = 18
}

internal class DesktopStyle(val dark: Boolean) {
    val ink = color(0x000000, 0xF4F4F2)
    val panel = color(0x0B0B0C, 0xFFFFFF)
    val inset = color(0x141416, 0xEBEBEE)
    val line = color(0x232327, 0xD4D4D9)
    val lineSoft = color(0x1A1A1D, 0xE3E3E7)
    val trace = color(0xFFFFFF, 0x0B0B0C)
    val trace2 = color(0xA8A8AE, 0x45454B)
    val trace3 = color(0x777780, 0x626267)
    val brand = Font("Segoe UI", Font.BOLD, 28)
    val title = Font("Segoe UI", Font.BOLD, 19)
    val subtitle = Font("Segoe UI", Font.BOLD, 15)
    val body = Font("Segoe UI", Font.PLAIN, 14)
    val small = Font("Segoe UI", Font.PLAIN, 12)
    val buttonFont = Font("Segoe UI", Font.BOLD, 14)
    val mono = Font(Font.MONOSPACED, Font.PLAIN, 13)
    val monoSmall = mono.deriveFont(11f)
    val readout = mono.deriveFont(Font.BOLD, 46f)
    private val legendFont = Font("Segoe UI", Font.BOLD, 10).deriveFont(mapOf(TextAttribute.TRACKING to 0.10f))

    private fun color(darkRgb: Int, lightRgb: Int) = Color(if (dark) darkRgb else lightRgb)

    fun label(value: String, font: Font = body, foreground: Color = trace) = JLabel().apply {
        putClientProperty("html.disable", true)
        text = value
        this.font = font
        this.foreground = foreground
    }

    fun legend(value: String, foreground: Color = trace2) = label(value.uppercase(Locale.ROOT), legendFont, foreground)

    fun column(vararg children: JComponent, gap: Int = DesktopSpace.sm) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        children.forEachIndexed { index, child ->
            if (index != 0) add(Box.createVerticalStrut(gap))
            child.alignmentX = JComponent.LEFT_ALIGNMENT
            add(child)
        }
    }

    fun grid(vararg children: JComponent, gap: Int = DesktopSpace.sm) =
        JPanel(GridLayout(1, children.size, gap, 0)).apply {
            isOpaque = false
            for (child in children) add(child)
        }

    fun spacer(height: Int) = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(1, height)
        maximumSize = Dimension(Int.MAX_VALUE, height)
    }

    fun hairline() = JPanel().apply {
        background = line
        preferredSize = Dimension(1, 1)
        maximumSize = Dimension(Int.MAX_VALUE, 1)
    }

    fun split(left: JComponent, trailing: JComponent, gap: Int = DesktopSpace.md) =
        JPanel(BorderLayout(gap, 0)).apply {
            isOpaque = false
            add(left, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    fun box(content: JComponent, instrument: Boolean = false, padded: Boolean = true): JPanel =
        object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                if (padded) border = EmptyBorder(DesktopSpace.lg, DesktopSpace.lg, DesktopSpace.lg, DesktopSpace.lg)
                add(content, BorderLayout.CENTER)
            }
            override fun paintComponent(g: Graphics) {
                val brush = graphics(g)
                val radius = if (instrument) 8.0 else 28.0
                val outline = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, radius, radius)
                brush.color = panel
                brush.fill(outline)
                brush.color = line
                brush.draw(outline)
                if (instrument) drawBrackets(brush, width, height)
                brush.dispose()
                super.paintComponent(g)
            }
        }.apply { maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) }

    private fun drawBrackets(brush: Graphics2D, width: Int, height: Int) {
        val edge = 9
        val length = 13
        brush.color = trace2
        val right = width - edge
        val bottom = height - edge
        brush.drawLine(edge, edge, edge + length, edge)
        brush.drawLine(edge, edge, edge, edge + length)
        brush.drawLine(right, edge, right - length, edge)
        brush.drawLine(right, edge, right, edge + length)
        brush.drawLine(edge, bottom, edge + length, bottom)
        brush.drawLine(edge, bottom, edge, bottom - length)
        brush.drawLine(right, bottom, right - length, bottom)
        brush.drawLine(right, bottom, right, bottom - length)
    }

    fun section(title: String, description: String = ""): JComponent =
        if (description.isBlank()) column(legend(title), gap = DesktopSpace.xs)
        else column(legend(title), label(description, small, trace3), gap = DesktopSpace.xs)

    fun paragraph(value: String, rows: Int = 2, foreground: Color = trace2) = JTextArea(value, rows, 20).apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        font = small
        this.foreground = foreground
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun notice(value: String, rows: Int = 2) = box(paragraph(value, rows))

    fun dataRow(label: String, value: String): JComponent =
        split(this.label(label, body, trace2), this.label(value, mono)).apply {
            border = EmptyBorder(DesktopSpace.sm, DesktopSpace.md, DesktopSpace.sm, DesktopSpace.md)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    fun table(rows: List<DesktopField>): JComponent = box(
        column(*rows.flatMapIndexed { index, row ->
            if (index == 0) listOf(dataRow(row.label, row.value))
            else listOf(hairline(), dataRow(row.label, row.value))
        }.toTypedArray(), gap = 0),
        padded = false
    )

    fun button(value: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit): JButton =
        object : JButton() {
            init {
                putClientProperty("html.disable", true)
                text = value
                font = buttonFont
                foreground = if (primary) ink else trace
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isRolloverEnabled = true
                isEnabled = enabled
                border = EmptyBorder(DesktopSpace.xs, DesktopSpace.lg, DesktopSpace.xs, DesktopSpace.lg)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(super.getPreferredSize().width, 42)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addActionListener { action() }
            }
            override fun paintComponent(g: Graphics) {
                val brush = graphics(g)
                val bounds = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, 22.0, 22.0)
                brush.color = when {
                    !isEnabled -> inset
                    primary && model.isPressed -> trace2
                    primary -> trace
                    model.isRollover || hasFocus() -> inset
                    else -> panel
                }
                brush.fill(bounds)
                brush.color = if (!primary && hasFocus()) trace2 else line
                brush.draw(bounds)
                if (primary && hasFocus()) {
                    brush.color = ink
                    brush.draw(RoundRectangle2D.Double(3.5, 3.5, width - 7.0, height - 7.0, 17.0, 17.0))
                }
                brush.dispose()
                super.paintComponent(g)
            }
        }

    fun toggle(value: String, selected: Boolean, enabled: Boolean = true, action: (Boolean) -> Unit) =
        JCheckBox(value, selected).apply {
            isOpaque = false
            isEnabled = enabled
            foreground = trace
            font = body
            border = EmptyBorder(DesktopSpace.xs, 0, DesktopSpace.xs, 0)
            addActionListener { action(isSelected) }
        }

    fun field(value: String = "", columns: Int = 20) = JTextField(value, columns).apply {
        applyFieldStyle(this)
        preferredSize = Dimension(preferredSize.width, 37)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun password() = JPasswordField(20).apply {
        applyFieldStyle(this)
        preferredSize = Dimension(preferredSize.width, 37)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun editor(value: String, rows: Int) = JTextArea(value, rows, 38).apply {
        applyFieldStyle(this)
        tabSize = 4
    }

    private fun applyFieldStyle(field: JTextComponent) {
        field.background = inset
        field.foreground = trace
        field.caretColor = trace
        field.selectionColor = trace2
        field.selectedTextColor = ink
        field.font = mono
        field.border = BorderFactory.createCompoundBorder(
            LineBorder(line, 1, true), EmptyBorder(DesktopSpace.sm, DesktopSpace.md, DesktopSpace.sm, DesktopSpace.md)
        )
    }

    fun scroll(content: JComponent, height: Int? = null) = JScrollPane(content).apply {
        border = EmptyBorder(0, 0, 0, 0)
        isOpaque = false
        viewport.background = ink
        horizontalScrollBarPolicy = if (content is JTextArea) ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            else ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.setUI(monochromeScrollBar())
        horizontalScrollBar.setUI(monochromeScrollBar())
        verticalScrollBar.unitIncrement = 20
        if (height != null) {
            preferredSize = Dimension(1, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
        }
    }

    private fun monochromeScrollBar() = object : BasicScrollBarUI() {
        override fun configureScrollBarColors() {
            trackColor = ink
            trackHighlightColor = inset
            thumbColor = trace3
            thumbDarkShadowColor = trace3
            thumbHighlightColor = trace3
            thumbLightShadowColor = trace3
            scrollBarWidth = DesktopSpace.md
        }

        override fun createDecreaseButton(orientation: Int): JButton = spacerButton()

        override fun createIncreaseButton(orientation: Int): JButton = spacerButton()

        private fun spacerButton() = JButton().apply {
            preferredSize = Dimension(0, 0)
            isFocusable = false
        }
    }

    fun tab(value: String, symbol: String, active: Boolean, navigate: () -> Unit): JComponent =
        object : JButton() {
            init {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isRolloverEnabled = true
                margin = Insets(0, 0, 0, 0)
                toolTipText = value
                getAccessibleContext().accessibleName = value
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { navigate() }
            }
            override fun getPreferredSize() = Dimension(96, if (symbol.isEmpty()) 34 else 65)
            override fun paintComponent(g: Graphics) {
                val brush = graphics(g)
                if (hasFocus() || model.isRollover) {
                    brush.color = inset
                    brush.fillRoundRect(DesktopSpace.sm, 5, width - 2 * DesktopSpace.sm, height - 10, 16, 16)
                }
                brush.color = if (active) trace else trace3
                if (active) brush.fillRect((width - 28) / 2, 4, 28, 2)
                if (symbol.isNotEmpty()) {
                    brush.font = Font("Segoe UI Symbol", Font.PLAIN, 21)
                    brush.drawString(symbol, (width - brush.fontMetrics.stringWidth(symbol)) / 2, 34)
                }
                val titleText = value.uppercase(Locale.ROOT)
                brush.font = legendFont
                brush.drawString(titleText, (width - brush.fontMetrics.stringWidth(titleText)) / 2, if (symbol.isEmpty()) 23 else 54)
                brush.dispose()
            }
        }

    fun latencyTrace(samples: () -> List<Double?>) = object : JComponent() {
        override fun getPreferredSize() = Dimension(200, 150)
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun paintComponent(g: Graphics) {
            val brush = graphics(g)
            val top = DesktopSpace.md
            val bottom = height - DesktopSpace.sm
            brush.color = line
            brush.drawLine(0, bottom, width, bottom)
            brush.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(2f, 5f), 0f)
            brush.drawLine(0, (top + bottom) / 2, width, (top + bottom) / 2)
            brush.stroke = BasicStroke(2f)
            brush.color = trace
            val points = samples().takeLast(48)
            val scale = maxOf(120.0, points.filterNotNull().maxOrNull() ?: 0.0)
            for ((index, sample) in points.withIndex()) {
                if (sample == null) continue
                val x = width * (48 - points.size + index) / 47
                val y = (bottom - sample.coerceIn(0.0, scale) / scale * (bottom - top)).toInt()
                val previous = points.getOrNull(index - 1)
                if (previous == null) brush.fillOval(x - 2, y - 2, 4, 4)
                else {
                    val previousX = width * (47 - points.size + index) / 47
                    val previousY = (bottom - previous.coerceIn(0.0, scale) / scale * (bottom - top)).toInt()
                    brush.drawLine(previousX, previousY, x, y)
                }
            }
            brush.dispose()
        }
    }

    private fun graphics(g: Graphics) = (g.create() as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
}
