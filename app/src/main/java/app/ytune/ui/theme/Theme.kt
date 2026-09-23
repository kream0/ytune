package app.ytune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.ytune.R

/** Nothing OS inspired palette: pure black / off-white, greys, one signal red. */
@Immutable
data class Palette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val dotOff: Color,
    val inverse: Color,
    val onInverse: Color,
)

val NothingRed = Color(0xFFD71921)

val DarkPalette = Palette(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF0D0D0D),
    surfaceHigh = Color(0xFF1A1A1A),
    outline = Color(0xFF2B2B2B),
    text = Color(0xFFFFFFFF),
    textDim = Color(0xFF9B9B9B),
    textFaint = Color(0xFF5A5A5A),
    accent = NothingRed,
    dotOff = Color(0xFF262626),
    inverse = Color(0xFFFFFFFF),
    onInverse = Color(0xFF000000),
)

val LightPalette = Palette(
    isDark = false,
    background = Color(0xFFF1F1F1),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE4E4E4),
    outline = Color(0xFFD0D0D0),
    text = Color(0xFF0A0A0A),
    textDim = Color(0xFF626262),
    textFaint = Color(0xFFA3A3A3),
    accent = NothingRed,
    dotOff = Color(0xFFD6D6D6),
    inverse = Color(0xFF000000),
    onInverse = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Shorthand for the current palette. */
val P: Palette
    @Composable
    @ReadOnlyComposable
    get() = LocalPalette.current

object Fonts {
    /** Dot-matrix display face (Doto, rounded dots) standing in for Nothing's NDot. */
    val Dot = FontFamily(Font(R.font.doto_black, FontWeight.Black))
    val Sans = FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font(R.font.space_mono_regular, FontWeight.Normal),
        Font(R.font.space_mono_bold, FontWeight.Bold),
    )
}

object Type {
    val display = TextStyle(fontFamily = Fonts.Dot, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 2.sp)
    val displaySmall = TextStyle(fontFamily = Fonts.Dot, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp)
    val clock = TextStyle(fontFamily = Fonts.Dot, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
    val titleLarge = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
    val title = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Fonts.Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 1.2.sp)
    val labelBold = label.copy(fontWeight = FontWeight.Bold)
    val input = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
}

@Composable
fun YTuneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
            outline = p.outline,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
            outline = p.outline,
        )
    }
    val typography = Typography(
        bodyLarge = Type.body,
        bodyMedium = Type.body,
        titleMedium = Type.title,
        labelSmall = Type.label,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
