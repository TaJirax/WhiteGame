package com.whitegame.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whitegame.app.ui.theme.Bevel
import com.whitegame.app.ui.theme.Ink
import com.whitegame.app.ui.theme.Inset
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.LocalAnimationsEnabled
import com.whitegame.app.ui.theme.Panel
import com.whitegame.app.ui.theme.Shapes
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Trace3
import com.whitegame.app.ui.theme.Type

// ---------------------------------------------------------------- text

/** The signature label: engraved-caps panel legend. */
@Composable
fun Legend(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Trace3
) = Text(text.uppercase(), modifier, color = color, style = Type.legend)

/** Section heading: legend over an optional sentence of plain guidance. */
@Composable
fun SectionHeader(
    legend: String,
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Legend(legend)
            if (title != null) {
                Spacer(Modifier.height(3.dp))
                Text(title, color = Trace2, style = Type.small)
            }
        }
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------- surfaces

/**
 * A control surface: rounded, filled, meant to be pressed or to hold pressable things.
 * Depth comes from a bevelled top edge rather than a shadow.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = Space.lg,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && onClick != null) 0.985f else 1f,
        spring(dampingRatio = 0.75f, stiffness = 900f), label = "panelScale"
    )
    Column(
        modifier
            .scale(scale)
            .clip(Shapes.panel)
            .background(Panel)
            .border(1.dp, Line, Shapes.panel)
            .drawBehind {
                drawLine(Bevel, Offset(14f, 0.5f), Offset(size.width - 14f, 0.5f), 1.5f)
            }
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction, indication = null,
                    role = Role.Button, onClick = onClick
                ) else Modifier
            )
            .padding(padding),
        content = content
    )
}

/**
 * An instrument surface: square, corner-bracketed, it reads out rather than being pressed.
 * The brackets are the app's one piece of HUD vernacular and appear nowhere else.
 */
@Composable
fun Instrument(
    modifier: Modifier = Modifier,
    padding: Dp = Space.lg,
    bracketColor: Color = Trace3,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(Shapes.instrument)
            .background(Panel)
            .drawBehind {
                val arm = 13.dp.toPx()
                val w = 1.5f
                val inset = 6.dp.toPx()
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(bracketColor, Offset(x, y), Offset(x + dx * arm, y), w)
                    drawLine(bracketColor, Offset(x, y), Offset(x, y + dy * arm), w)
                }
                corner(inset, inset, 1f, 1f)
                corner(size.width - inset, inset, -1f, 1f)
                corner(inset, size.height - inset, 1f, -1f)
                corner(size.width - inset, size.height - inset, -1f, -1f)
            }
            .padding(padding),
        content = content
    )
}

/** A flat grouped list. Rows inside are separated by hairlines, spec-sheet style. */
@Composable
fun RowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(Shapes.panel)
            .background(Panel)
            .border(1.dp, Line, Shapes.panel),
        content = content
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = Space.lg) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(Line)
    )
}

/** Label on the left, value on the right — the spec-sheet row. */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = Type.mono,
    valueColor: Color = Trace,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.lg, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Trace2, style = Type.body, modifier = Modifier.weight(1f))
        Text(
            value, color = valueColor, style = valueStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (trailing != null) {
            Spacer(Modifier.width(Space.sm))
            trailing()
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 800f), label = "press"
    )
    return scale
}

/** Filled action. White fill reads as "this is the thing to do". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    Box(
        modifier
            .heightIn(min = height)
            .height(height)
            .scale(pressScale(interaction))
            .clip(Shapes.control)
            .background(if (active) Trace else Inset)
            .clickable(
                interactionSource = interaction, indication = null,
                enabled = active, role = Role.Button, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Ink, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) Ink else Trace3)
                    Spacer(Modifier.width(Space.sm))
                }
                Text(text, color = if (enabled) Ink else Trace3, style = Type.button)
            }
        }
    }
}

/** Outlined action. Same weight of intent, lower visual priority. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border by animateColorAsState(if (pressed) Trace2 else Line, tween(120), label = "secBorder")
    val fg = if (enabled) Trace else Trace3
    Box(
        modifier
            .heightIn(min = height)
            .height(height)
            .scale(pressScale(interaction))
            .clip(Shapes.control)
            .background(if (pressed) Inset else Color.Transparent)
            .border(1.dp, border, Shapes.control)
            .clickable(
                interactionSource = interaction, indication = null,
                enabled = enabled && !loading, role = Role.Button, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Trace, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(18.dp), tint = fg)
                    Spacer(Modifier.width(Space.sm))
                }
                Text(text, color = fg, style = Type.button)
            }
        }
    }
}

/** Compact icon-only action for row-level affordances. Keeps a 44dp touch target. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Trace2
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .scale(pressScale(interaction))
            .clip(Shapes.chip)
            .clickable(
                interactionSource = interaction, indication = null,
                role = Role.Button, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, Modifier.size(19.dp), tint = tint)
    }
}

/** Selectable filter chip. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(if (selected) Trace else Color.Transparent, tween(140), label = "chipBg")
    val fg by animateColorAsState(if (selected) Ink else Trace2, tween(140), label = "chipFg")
    val border by animateColorAsState(if (selected) Trace else Line, tween(140), label = "chipBorder")
    Box(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(Shapes.chip)
            .background(bg)
            .border(1.dp, border, Shapes.chip)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, color = fg, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

/** Segmented control — one row, hairline-bounded, active segment filled. */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Shapes.chip)
            .border(1.dp, Line, Shapes.chip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(if (selected) Trace else Color.Transparent, tween(150), label = "segBg")
            val fg by animateColorAsState(if (selected) Ink else Trace2, tween(150), label = "segFg")
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = fg,
                    style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Monochrome switch. The knob position carries the state; the label always says it too. */
@Composable
fun Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackBg by animateColorAsState(
        if (!enabled) Inset else if (checked) Trace else Color.Transparent, tween(160), label = "swTrack"
    )
    val border by animateColorAsState(if (checked && enabled) Trace else Line, tween(160), label = "swBorder")
    val knobX by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 700f), label = "swKnob")
    Box(
        modifier
            .size(48.dp, 30.dp)
            .clickable(
                enabled = enabled, role = Role.Switch, indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(46.dp, 26.dp)
                .clip(CircleShape)
                .background(trackBg)
                .border(1.dp, border, CircleShape)
        )
        Box(
            Modifier
                .offset(x = 4.dp + 20.dp * knobX)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Ink else Trace2)
        )
    }
}

/** Row with a label, a one-line explanation and a switch. */
@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = Space.lg, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Space.md)) {
            Text(title, color = if (enabled) Trace else Trace3, style = Type.subtitle)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Trace3, style = Type.small)
        }
        Toggle(checked, onCheckedChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------- input

/** Text field with a legend above it and an error line below. */
@Composable
fun Field(
    legend: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    mono: Boolean = false,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier) {
        Legend(legend)
        Spacer(Modifier.height(Space.sm))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Shapes.inset)
                .background(Inset)
                .border(1.dp, if (error != null) Trace2 else Line, Shapes.inset)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).heightIn(min = minHeight - 24.dp)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder, color = Trace3,
                        style = if (mono) Type.mono else Type.body
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = (if (mono) Type.mono else Type.body).copy(color = Trace),
                    cursorBrush = SolidColor(Trace),
                    keyboardOptions = keyboardOptions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(Space.sm))
                trailing()
            }
        }
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(Trace))
                Spacer(Modifier.width(6.dp))
                Text(error.orEmpty(), color = Trace, style = Type.small)
            }
        }
    }
}

// ---------------------------------------------------------------- status

/** Fill weight encodes quality; the text beside it always says the same thing in words. */
enum class Quality { Good, Caution, Bad, Unknown }

@Composable
fun StatusPill(text: String, quality: Quality, modifier: Modifier = Modifier) {
    val dashed = quality == Quality.Caution
    val bg = when (quality) {
        Quality.Good -> Trace
        Quality.Caution -> Trace.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    val fg = when (quality) {
        Quality.Good, Quality.Caution -> Ink
        Quality.Bad -> Trace2
        Quality.Unknown -> Trace3
    }
    Box(
        modifier
            .clip(Shapes.chip)
            .background(bg)
            .then(
                if (bg == Color.Transparent || dashed) Modifier.drawBehind {
                    drawRoundRect(
                        color = if (quality == Quality.Bad) Trace3 else Ink,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = if (dashed)
                                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                            else null
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                } else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, color = fg, style = Type.legend)
    }
}

/** Big number with its unit, typeset as one readout. */
@Composable
fun Metric(
    value: String,
    unit: String,
    legend: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Trace
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, style = Type.readoutSm)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = Trace3, style = Type.monoSm, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Legend(legend)
    }
}

/** Three dots that breathe while work is in flight. Stops when the system disables animation. */
@Composable
fun WorkingDots(modifier: Modifier = Modifier, color: Color = Trace) {
    val animate = LocalAnimationsEnabled.current
    if (!animate) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { Box(Modifier.size(4.dp).clip(CircleShape).background(color)) }
        }
        return
    }
    val infinite = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by infinite.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(560, delayMillis = i * 130, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(Modifier.size(4.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** Inline progress strip used above long-running lists. */
@Composable
fun ProgressStrip(active: Boolean, label: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = active || label.isNotBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Shapes.chip)
                .background(Inset)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = if (active) Trace else Trace2, style = Type.small,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (active) {
                Spacer(Modifier.width(Space.md))
                WorkingDots()
            }
        }
    }
}

/** An empty screen is an invitation to act, so it always carries the action. */
@Composable
fun EmptyState(
    legend: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Legend(legend)
        Spacer(Modifier.height(Space.sm))
        Text(
            message, color = Trace2, style = Type.body,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg)
        )
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

/** Notice line for a result or a problem the user needs to read. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    if (text.isBlank()) return
    Row(
        modifier
            .fillMaxWidth()
            .clip(Shapes.chip)
            .background(if (emphasis) Inset else Color.Transparent)
            .then(if (emphasis) Modifier.border(1.dp, Line, Shapes.chip) else Modifier)
            .padding(horizontal = if (emphasis) 12.dp else 0.dp, vertical = if (emphasis) 10.dp else 2.dp)
    ) {
        Text(text, color = if (emphasis) Trace else Trace3, style = Type.small)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun RowScope.HSpace(w: Dp) = Spacer(Modifier.width(w))
