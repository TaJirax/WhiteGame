package com.whitegame.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One luminance ramp, no hue anywhere — run dark-on-light or light-on-dark.
 *
 * State is carried by fill weight (solid / dimmed+dashed / outline) and always paired with a
 * text label, so meaning never rests on the fill alone.
 */
private class Palette(
    val ink: Color, val panel: Color, val inset: Color, val line: Color, val lineSoft: Color,
    val trace: Color, val trace2: Color, val trace3: Color, val bevel: Color
)

private val DarkPalette = Palette(
    ink = Color(0xFF000000), panel = Color(0xFF0B0B0C), inset = Color(0xFF141416),
    line = Color(0xFF232327), lineSoft = Color(0xFF1A1A1D),
    trace = Color(0xFFFFFFFF), trace2 = Color(0xFFA8A8AE), trace3 = Color(0xFF5E5E66),
    bevel = Color(0x0FFFFFFF)
)

private val LightPalette = Palette(
    ink = Color(0xFFF4F4F2), panel = Color(0xFFFFFFFF), inset = Color(0xFFEBEBEE),
    line = Color(0xFFD4D4D9), lineSoft = Color(0xFFE3E3E7),
    trace = Color(0xFF0B0B0C), trace2 = Color(0xFF45454B), trace3 = Color(0xFF6E6E76),
    bevel = Color(0xB3FFFFFF)
)

/** Day / night choice. Unset follows the system; a tap on the header toggle pins it. */
object ThemeMode {
    var dark by mutableStateOf(true)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        dark = when (prefs(context).getString("theme", null)) {
            "light" -> false
            "dark" -> true
            else -> night
        }
    }

    fun toggle(context: Context) {
        dark = !dark
        prefs(context).edit().putString("theme", if (dark) "dark" else "light").apply()
    }
}

private val palette: Palette get() = if (ThemeMode.dark) DarkPalette else LightPalette

// Getters, not values: every read goes through ThemeMode's state, so a toggle recomposes.
val Ink: Color get() = palette.ink
val Panel: Color get() = palette.panel
val Inset: Color get() = palette.inset
val Line: Color get() = palette.line
val LineSoft: Color get() = palette.lineSoft
val Trace: Color get() = palette.trace
val Trace2: Color get() = palette.trace2
val Trace3: Color get() = palette.trace3

/** 1px top highlight that reads as a bevelled panel edge. Replaces drop shadows. */
val Bevel: Color get() = palette.bevel

// Kept so older call sites keep compiling while screens are migrated.
val BwBg get() = Ink
val BwBlack get() = Ink
val BwSurface get() = Panel
val BwSurface2 get() = Inset
val BwSurface3 get() = Line
val BwWhite get() = Trace
val BwTextP get() = Trace
val BwTextS get() = Trace2
val BwTextM get() = Trace3
val BwStroke get() = Line

/**
 * Two materials that are deliberately not interchangeable:
 * instruments read out (square, bracketed); controls get pressed (rounded, filled).
 */
object Shapes {
    val instrument = RoundedCornerShape(4.dp)
    val panel = RoundedCornerShape(14.dp)
    val control = RoundedCornerShape(14.dp)
    val inset = RoundedCornerShape(10.dp)
    val chip = RoundedCornerShape(8.dp)
}

/** 4dp rhythm used for every gap in the app. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Screen side margin. */
    val edge = 18.dp
}

private val Mono = FontFamily.Monospace

/**
 * Personality comes from treatment, not a downloaded face: extreme weights, extreme tracking,
 * and a strict split between mono (measured numbers) and sans (everything else).
 */
object Type {
    /** The hero number. Tabular so it does not jitter as digits change. */
    val readout = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Black,
        fontSize = 46.sp, letterSpacing = (-2).sp, lineHeight = 48.sp
    )
    val readoutSm = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, letterSpacing = (-1).sp, lineHeight = 28.sp
    )
    val display = TextStyle(
        fontWeight = FontWeight.ExtraBold, fontSize = 28.sp,
        letterSpacing = (-0.8).sp, lineHeight = 32.sp
    )
    val title = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 19.sp,
        letterSpacing = (-0.3).sp, lineHeight = 24.sp
    )
    val subtitle = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp
    )
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp)

    /** The signature label. Panel legends on real instruments are engraved in caps. */
    val legend = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 10.5.sp,
        letterSpacing = 1.4.sp, lineHeight = 14.sp
    )
    val mono = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp
    )
    val monoSm = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp
    )
    val button = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.2.sp
    )
}

/**
 * False when the device has animations turned off (developer options or a battery/accessibility
 * setting). Ambient loops check this so the app does not animate against the user's wishes.
 */
val LocalAnimationsEnabled: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

private fun scheme() = (if (ThemeMode.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = Trace,
    onPrimary = Ink,
    secondary = Trace2,
    onSecondary = Ink,
    background = Ink,
    onBackground = Trace,
    surface = Panel,
    onSurface = Trace,
    surfaceVariant = Inset,
    onSurfaceVariant = Trace2,
    outline = Line,
    error = Trace,
    onError = Ink
)

private val M3Typography = Typography(
    headlineLarge = Type.display,
    titleLarge = Type.title,
    titleMedium = Type.subtitle,
    bodyLarge = Type.body,
    bodyMedium = Type.small,
    labelLarge = Type.button,
    labelMedium = Type.legend
)

@Composable
fun WhiteGameTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animate = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
    CompositionLocalProvider(LocalAnimationsEnabled provides animate) {
        MaterialTheme(colorScheme = scheme(), typography = M3Typography, content = content)
    }
}
