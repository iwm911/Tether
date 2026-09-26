package app.tether.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.tether.R
import app.tether.core.ThemeMode

/*
 * Tether design language — "warm terminal".
 * Charcoal & cream paper, one clay accent (Claude's), serif display type for a literary calm,
 * Inter for UI, JetBrains Mono for anything the machine said. Hairline borders instead of shadows.
 */

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val Serif = FontFamily(
    Font(R.font.source_serif_regular, FontWeight.Normal),
    Font(R.font.source_serif_italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic),
    Font(R.font.source_serif_medium, FontWeight.Medium),
    Font(R.font.source_serif_semibold, FontWeight.SemiBold),
    Font(R.font.source_serif_semibold_italic, FontWeight.SemiBold, androidx.compose.ui.text.font.FontStyle.Italic),
)
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

object Palette {
    val Clay = Color(0xFFD97757)
    val ClayDeep = Color(0xFFC0613F)
    val ClaySoft = Color(0xFFF2B89F)
    val Ink = Color(0xFF262624)
    val Cream = Color(0xFFFAF9F5)
}

/** Colours Material's scheme has no slot for. Read via [TetherTheme.colors]. */
@Immutable
data class TetherColors(
    val isDark: Boolean,
    val clay: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val faint: Color,
    val hairline: Color,
    val codeBg: Color,
    val codeText: Color,
    val diffAddBg: Color,
    val diffAddText: Color,
    val diffDelBg: Color,
    val diffDelText: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val shimmer: Color,
    val scrim: Color,
    /** Soft filled card surface (Home cards, grouped settings). */
    val card: Color,
    /** Card edge: none in dark, a whisper in light. */
    val cardBorder: Color,
    /** The hero composer's surface. */
    val composer: Color,
    val composerBorder: Color,
    /** Quiet filled control background (secondary buttons, chips). */
    val subtleFill: Color,
    // syntax
    val synKeyword: Color,
    val synString: Color,
    val synComment: Color,
    val synNumber: Color,
    val synType: Color,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.Clay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A2E22),
    onPrimaryContainer = Color(0xFFF6C7B3),
    secondary = Color(0xFFC2C0B6),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF3A3935),
    onSecondaryContainer = Color(0xFFF0EEE6),
    tertiary = Color(0xFF8FB0DA),
    onTertiary = Color(0xFF0D1B2B),
    background = Palette.Ink,
    onBackground = Color(0xFFFAF9F5),
    surface = Palette.Ink,
    onSurface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFF30302E),
    onSurfaceVariant = Color(0xFFC2C0B6),
    surfaceContainerLowest = Color(0xFF1A1A18),
    surfaceContainerLow = Color(0xFF2B2B29),
    surfaceContainer = Color(0xFF30302E),
    surfaceContainerHigh = Color(0xFF363633),
    surfaceContainerHighest = Color(0xFF3E3E3A),
    surfaceBright = Color(0xFF44443F),
    outline = Color(0xFF55544E),
    outlineVariant = Color(0xFF3A3A36),
    error = Color(0xFFEE7A6F),
    onError = Color(0xFF2A0B08),
    errorContainer = Color(0xFF4A2521),
    onErrorContainer = Color(0xFFF9C4BD),
    inverseSurface = Color(0xFFFAF9F5),
    inverseOnSurface = Color(0xFF262624),
    inversePrimary = Palette.ClayDeep,
    scrim = Color(0xFF000000),
)

private val LightScheme = lightColorScheme(
    primary = Palette.ClayDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8E3D9),
    onPrimaryContainer = Color(0xFF4A200F),
    secondary = Color(0xFF5E5D59),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0EEE6),
    onSecondaryContainer = Color(0xFF141413),
    tertiary = Color(0xFF3F6FA8),
    onTertiary = Color(0xFFFFFFFF),
    background = Palette.Cream,
    onBackground = Color(0xFF141413),
    surface = Palette.Cream,
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFF0EEE6),
    onSurfaceVariant = Color(0xFF5E5D59),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF5F4ED),
    surfaceContainerHigh = Color(0xFFF0EEE6),
    surfaceContainerHighest = Color(0xFFE8E6DC),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFFD1CFC5),
    outlineVariant = Color(0xFFE8E6DC),
    error = Color(0xFFB8322A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF410E0A),
    inverseSurface = Color(0xFF30302E),
    inverseOnSurface = Color(0xFFFAF9F5),
    inversePrimary = Palette.ClaySoft,
    scrim = Color(0xFF000000),
)

private val DarkExtra = TetherColors(
    isDark = true,
    clay = Palette.Clay,
    success = Color(0xFF8CC084),
    warning = Color(0xFFE8B25C),
    danger = Color(0xFFEE7A6F),
    info = Color(0xFF7FA7D9),
    faint = Color(0xFF9C9A92),
    hairline = Color(0xFF3A3A36),
    codeBg = Color(0xFF1F1E1D),
    codeText = Color(0xFFE8E6DC),
    diffAddBg = Color(0xFF1B2E1D),
    diffAddText = Color(0xFFA6DB9E),
    diffDelBg = Color(0xFF3A1D1B),
    diffDelText = Color(0xFFF3A59D),
    userBubble = Color(0xFF1D1D1B),
    onUserBubble = Color(0xFFF0EEE6),
    shimmer = Color(0xFFF6C7B3),
    scrim = Color(0xB3000000),
    card = Color(0xFF2F2F2C),
    cardBorder = Color.Transparent,
    composer = Color(0xFF30302E),
    composerBorder = Color(0xFF3D3D39),
    subtleFill = Color(0xFF363633),
    synKeyword = Color(0xFFE39A7B),
    synString = Color(0xFFA6CF8E),
    synComment = Color(0xFF8A8880),
    synNumber = Color(0xFFD9B77F),
    synType = Color(0xFF8FB5E0),
)

private val LightExtra = TetherColors(
    isDark = false,
    clay = Palette.ClayDeep,
    success = Color(0xFF3F8A3A),
    warning = Color(0xFFB07512),
    danger = Color(0xFFB8322A),
    info = Color(0xFF3F6FA8),
    faint = Color(0xFF8A8880),
    hairline = Color(0xFFE8E6DC),
    codeBg = Color(0xFFF2F0E8),
    codeText = Color(0xFF2B2824),
    diffAddBg = Color(0xFFE2F2DE),
    diffAddText = Color(0xFF235E1F),
    diffDelBg = Color(0xFFF9E0DC),
    diffDelText = Color(0xFF8E2219),
    userBubble = Color(0xFFF0EEE6),
    onUserBubble = Color(0xFF1F1E1D),
    shimmer = Palette.ClayDeep,
    scrim = Color(0x66000000),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFEDEBE3),
    composer = Color(0xFFFFFFFF),
    composerBorder = Color(0xFFE6E3D9),
    subtleFill = Color(0xFFF0EEE6),
    synKeyword = Color(0xFFB4502D),
    synString = Color(0xFF3F7A2E),
    synComment = Color(0xFF9A948A),
    synNumber = Color(0xFF946217),
    synType = Color(0xFF2F5F99),
)

@Immutable
data class TetherType(
    val mono: TextStyle,
    val monoSmall: TextStyle,
    val eyebrow: TextStyle,
    /** Quiet sentence-case group label ("Working", "Recent"). */
    val section: TextStyle,
    /** Assistant prose. */
    val prose: TextStyle,
)

private fun typography() = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

private val TetherTypeValue = TetherType(
    mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    monoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 16.sp),
    eyebrow = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.9.sp),
    section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    prose = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 16.5.sp, lineHeight = 26.sp),
)

val TetherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Spacing scale — use these, not ad-hoc dp values. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 40.dp
    /** Horizontal screen gutter. */
    val gutter = 20.dp
}

/** Motion tokens. */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    const val Short = 160
    const val Medium = 280
    const val Long = 450
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
}

val LocalTetherColors = staticCompositionLocalOf { DarkExtra }
val LocalTetherType = staticCompositionLocalOf { TetherTypeValue }

object TetherTheme {
    val colors: TetherColors @Composable get() = LocalTetherColors.current
    val type: TetherType @Composable get() = LocalTetherType.current
}

@Composable
fun TetherTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    val extra = if (dark) DarkExtra else LightExtra
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalTetherColors provides extra, LocalTetherType provides TetherTypeValue) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = TetherShapes) {
            // Screens draw on plain backgrounds (no Surface), so give un-coloured Text/Icon a
            // theme-aware default instead of Compose's Color.Black.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides scheme.onBackground,
                content = content,
            )
        }
    }
}
