package com.so.zapretksu.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

object GlassTokens {
    val Ink = Color(0xFFF4F7FF)
    val InkMuted = Color(0xFFBFCCE4)
    val Accent = Color(0xFF8FD3FF)
    val AccentWarm = Color(0xFFD7B8FF)
    val Live = Color(0xFF7CF2B5)
    val Danger = Color(0xFFFF9A9A)
    val Off = Color(0xFF8E9BB8)
    val RimHigh = Color(0xCCFFFFFF)
    val RimMid = Color(0x40FFFFFF)
    val RimLow = Color(0x0DFFFFFF)
    val Sheen = Color(0x1AFFFFFF)
    val FillTop = Color(0x2EFFFFFF)
    val FillBottom = Color(0x12FFFFFF)
    val Well = Color(0x66000918)
    val Panel = Color(0xCC0C1830)
    val PanelTint = Color(0xB30C1830)
    val Backdrop = listOf(Color(0xFF07101F), Color(0xFF140C2E), Color(0xFF05171F))
    val CardRadius: Dp = 24.dp
    val HeroRadius: Dp = 28.dp
    val TileRadius: Dp = 12.dp
    val CardPadding: Dp = 18.dp
}

private val GlassScheme: ColorScheme = darkColorScheme(
    primary = GlassTokens.Accent,
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0x3360B8F0),
    onPrimaryContainer = Color(0xFFE6F4FF),
    secondary = GlassTokens.AccentWarm,
    onSecondary = Color(0xFF2B1550),
    secondaryContainer = Color(0x33B388FF),
    onSecondaryContainer = Color(0xFFF1E6FF),
    tertiary = GlassTokens.Live,
    onTertiary = Color(0xFF00321E),
    tertiaryContainer = Color(0x337CF2B5),
    onTertiaryContainer = Color(0xFFD8FFEC),
    background = Color(0xFF07101F),
    onBackground = GlassTokens.Ink,
    surface = Color(0xFF0E1A30),
    onSurface = GlassTokens.Ink,
    surfaceVariant = Color(0xFF1B2843),
    onSurfaceVariant = GlassTokens.InkMuted,
    surfaceContainerLowest = Color(0x14FFFFFF),
    surfaceContainerLow = Color(0x1AFFFFFF),
    surfaceContainer = Color(0x22FFFFFF),
    surfaceContainerHigh = Color(0x2AFFFFFF),
    surfaceContainerHighest = Color(0x33FFFFFF),
    outline = Color(0x80FFFFFF),
    outlineVariant = Color(0x33FFFFFF),
    error = GlassTokens.Danger,
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0x33FF9A9A),
    onErrorContainer = Color(0xFFFFE4E4)
)

val Md3Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

val Md3Typography = Typography(
    displaySmall = TextStyle(
        fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp
    ),
    headlineLarge = TextStyle(
        fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp
    )
)

val Md3CardShape = RoundedCornerShape(20.dp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

const val DEFAULT_SEED: Int = 0xFF0060AA.toInt()

fun seedScheme(seed: Int, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val h = hsl[0]
    val s = hsl[1]
    val accent = maxOf(s, 0.4f)
    val hT = (h + 60f) % 360f
    fun tone(hue: Float, sat: Float, light: Float): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f))))
    val white = Color.White

    return if (!dark) {
        lightColorScheme(
            primary = tone(h, accent, 0.40f),
            onPrimary = white,
            primaryContainer = tone(h, accent, 0.90f),
            onPrimaryContainer = tone(h, accent, 0.10f),
            secondary = tone(h, s * 0.25f, 0.42f),
            onSecondary = white,
            secondaryContainer = tone(h, s * 0.25f, 0.88f),
            onSecondaryContainer = tone(h, s * 0.3f, 0.12f),
            tertiary = tone(hT, 0.5f, 0.40f),
            onTertiary = white,
            tertiaryContainer = tone(hT, 0.5f, 0.88f),
            onTertiaryContainer = tone(hT, 0.6f, 0.12f),
            background = tone(h, s * 0.08f, 0.98f),
            onBackground = tone(h, s * 0.1f, 0.10f),
            surface = tone(h, s * 0.08f, 0.98f),
            onSurface = tone(h, s * 0.1f, 0.10f),
            surfaceVariant = tone(h, s * 0.12f, 0.90f),
            onSurfaceVariant = tone(h, s * 0.12f, 0.30f),
            surfaceContainerLowest = white,
            surfaceContainerLow = tone(h, s * 0.06f, 0.97f),
            surfaceContainer = tone(h, s * 0.08f, 0.95f),
            surfaceContainerHigh = tone(h, s * 0.08f, 0.93f),
            surfaceContainerHighest = tone(h, s * 0.1f, 0.91f),
            outline = tone(h, s * 0.1f, 0.50f),
            outlineVariant = tone(h, s * 0.1f, 0.80f),
            error = Color(0xFFBA1A1A),
            onError = white,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002)
        )
    } else {
        darkColorScheme(
            primary = tone(h, accent, 0.80f),
            onPrimary = tone(h, accent, 0.20f),
            primaryContainer = tone(h, accent, 0.30f),
            onPrimaryContainer = tone(h, accent * 0.6f, 0.90f),
            secondary = tone(h, s * 0.25f, 0.75f),
            onSecondary = tone(h, s * 0.25f, 0.20f),
            secondaryContainer = tone(h, s * 0.25f, 0.30f),
            onSecondaryContainer = tone(h, s * 0.2f, 0.90f),
            tertiary = tone(hT, 0.5f, 0.80f),
            onTertiary = tone(hT, 0.5f, 0.20f),
            tertiaryContainer = tone(hT, 0.5f, 0.30f),
            onTertiaryContainer = tone(hT, 0.5f, 0.90f),
            background = tone(h, s * 0.1f, 0.07f),
            onBackground = tone(h, s * 0.05f, 0.90f),
            surface = tone(h, s * 0.1f, 0.07f),
            onSurface = tone(h, s * 0.05f, 0.90f),
            surfaceVariant = tone(h, s * 0.12f, 0.25f),
            onSurfaceVariant = tone(h, s * 0.1f, 0.80f),
            surfaceContainerLowest = tone(h, s * 0.1f, 0.05f),
            surfaceContainerLow = tone(h, s * 0.1f, 0.09f),
            surfaceContainer = tone(h, s * 0.1f, 0.12f),
            surfaceContainerHigh = tone(h, s * 0.1f, 0.16f),
            surfaceContainerHighest = tone(h, s * 0.1f, 0.20f),
            outline = tone(h, s * 0.1f, 0.58f),
            outlineVariant = tone(h, s * 0.1f, 0.30f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6)
        )
    }
}

@Composable
fun ZapretKsuTheme(mode: ThemeMode, wallpaper: Boolean, seed: Int, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    val scheme = when (mode) {
        ThemeMode.GLASS -> GlassScheme
        ThemeMode.MD3 -> if (wallpaper && dynamicColorSupported) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            seedScheme(seed, dark)
        }
    }

    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (mode == ThemeMode.MD3) Md3Typography else Typography(),
            shapes = AppShapes,
            content = content
        )
    }
}
