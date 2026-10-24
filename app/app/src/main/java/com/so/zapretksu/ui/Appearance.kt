package com.so.zapretksu.ui

import android.content.Context
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.liquidGlassProvider
import kotlin.math.PI
import kotlin.math.sin

enum class ThemeMode(val label: String) {
    MD3("Material 3"),
    GLASS("Liquid Glass")
}

data class GlassTune(val blur: Float = 1f, val tint: Float = 1f, val rim: Float = 1f)

class Appearance(
    val mode: ThemeMode,
    val tune: GlassTune = GlassTune(),
    val setTune: (GlassTune) -> Unit = {},
    val wallpaperColors: Boolean = true,
    val seed: Int = DEFAULT_SEED,
    val setWallpaperColors: (Boolean) -> Unit = {},
    val setSeed: (Int) -> Unit = {},
    val optimized: Boolean = false,
    val setOptimized: (Boolean) -> Unit = {},
    val set: (ThemeMode) -> Unit
)

val LocalAppearance = compositionLocalOf { Appearance(ThemeMode.MD3, GlassTune(), {}) { } }

val LocalGlassState = staticCompositionLocalOf<LiquidGlassProviderState?> { null }

val LocalSnapshot = compositionLocalOf<State<ZapretCtl.Snapshot?>?> { null }

val LocalFlatGlass = compositionLocalOf { false }

object ThemePrefs {
    private const val FILE = "zapret_ui"
    private const val KEY = "theme"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): ThemeMode {
        val name = prefs(context).getString(KEY, ThemeMode.MD3.name)
        return ThemeMode.values().firstOrNull { it.name == name } ?: ThemeMode.MD3
    }

    fun save(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
    }

    fun loadTune(context: Context): GlassTune {
        val p = prefs(context)
        fun f(key: String) = p.getFloat(key, 1f).coerceIn(0.5f, 1.5f)
        return GlassTune(blur = f("tune_blur"), tint = f("tune_tint"), rim = f("tune_rim"))
    }

    fun loadWallpaper(context: Context): Boolean = prefs(context).getBoolean("wallpaper_colors", true)

    fun saveWallpaper(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wallpaper_colors", on).apply()
    }

    fun loadSeed(context: Context): Int = prefs(context).getInt("seed_color", DEFAULT_SEED)

    fun saveSeed(context: Context, seed: Int) {
        prefs(context).edit().putInt("seed_color", seed).apply()
    }

    fun loadOptimized(context: Context): Boolean = prefs(context).getBoolean("optimized", false)

    fun saveOptimized(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("optimized", on).apply()
    }

    fun saveTune(context: Context, t: GlassTune) {
        prefs(context).edit()
            .putFloat("tune_blur", t.blur)
            .putFloat("tune_tint", t.tint)
            .putFloat("tune_rim", t.rim)
            .apply()
    }
}

private const val DRIFT_PERIOD_MS = 20_944

object MotionPrefs {
    @Volatile
    var optimized: Boolean = false
}

fun <T> zapretSpring(
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium
): FiniteAnimationSpec<T> =
    if (MotionPrefs.optimized) snap<T>() else spring<T>(dampingRatio, stiffness)

fun zapretEnter(): EnterTransition =
    if (MotionPrefs.optimized) EnterTransition.None
    else fadeIn(zapretTween()) + expandVertically(zapretTween())

fun zapretExit(): ExitTransition =
    if (MotionPrefs.optimized) ExitTransition.None
    else fadeOut(zapretTween()) + shrinkVertically(zapretTween())

fun <T> zapretTween(
    durationMillis: Int = 300,
    delayMillis: Int = 0,
    easing: Easing = FastOutSlowInEasing
): FiniteAnimationSpec<T> =
    if (MotionPrefs.optimized) {
        snap<T>(delayMillis = delayMillis)
    } else {
        androidx.compose.animation.core.tween<T>(durationMillis, delayMillis, easing)
    }

@Composable
fun AmbientBackdrop(state: LiquidGlassProviderState, glass: Boolean) {
    val optimized = LocalAppearance.current.optimized
    val angleState = if (optimized) {
        null
    } else {
        rememberInfiniteTransition(label = "ambient").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = DRIFT_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ambientAngle"
        )
    }
    val drift: () -> Float = { (sin(angleState?.value ?: 0f) + 1f) / 2f }

    Box(
        Modifier
            .fillMaxSize()
            .liquidGlassProvider(state)
            .then(if (glass) Modifier.background(Brush.verticalGradient(GlassTokens.Backdrop)) else Modifier)
    ) {
        if (glass) {
            Box(Modifier.matchParentSize()) {
                Orb(Color(0xFF3FB6FF), 320.dp, Alignment.TopStart, drift, -90f, 46f, 30f, 40f)
                Orb(Color(0xFFA78BFA), 300.dp, Alignment.CenterEnd, drift, 70f, -56f, -30f, 48f)
                Orb(Color(0xFF5EEAD4), 260.dp, Alignment.BottomStart, drift, 10f, 70f, -60f, -36f)
                Orb(Color(0xFFFF8FD1), 200.dp, Alignment.TopEnd, drift, -40f, -30f, 120f, 30f)
            }
        }
    }
}

@Composable
private fun BoxScope.Orb(
    color: Color,
    diameter: Dp,
    align: Alignment,
    drift: () -> Float,
    x0: Float,
    dx: Float,
    y0: Float,
    dy: Float
) {
    Box(
        Modifier
            .align(align)
            .size(diameter)
            .offset {
                val d = drift()
                IntOffset((x0 + dx * d).dp.roundToPx(), (y0 + dy * d).dp.roundToPx())
            }
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
    )
}
