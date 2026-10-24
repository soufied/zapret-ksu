package com.so.zapretksu

import com.so.zapretksu.ui.LocalFlatGlass
import com.so.zapretksu.ui.LocalSnapshot
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import com.so.zapretksu.ui.tuned
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.delay
import dev.liquidglass.compose.GlassStyle
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ui.AmbientBackdrop
import com.so.zapretksu.ui.AppSnackbar
import com.so.zapretksu.ui.Appearance
import com.so.zapretksu.ui.AboutScreen
import com.so.zapretksu.ui.AppsScreen
import com.so.zapretksu.ui.BlockcheckScreen
import com.so.zapretksu.ui.ComponentsScreen
import com.so.zapretksu.ui.DnsScreen
import com.so.zapretksu.ui.FluidNavBar
import com.so.zapretksu.ui.HomeScreen
import com.so.zapretksu.ui.LocalAppearance
import com.so.zapretksu.ui.LocalGlassState
import com.so.zapretksu.ui.LogsScreen
import com.so.zapretksu.ui.ListsScreen
import com.so.zapretksu.ui.Route
import com.so.zapretksu.ui.RootGate
import com.so.zapretksu.ui.SettingsScreen
import com.so.zapretksu.ui.ThemeMode
import com.so.zapretksu.ui.ThemePrefs
import com.so.zapretksu.ui.ToolsScreen
import com.so.zapretksu.ui.Md3Emphasized
import com.so.zapretksu.ui.TgProxyScreen
import com.so.zapretksu.ui.ZapretKsuTheme
import com.so.zapretksu.ui.MotionPrefs
import com.so.zapretksu.ui.zapretTween
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.rememberLiquidGlassProviderState
import dev.liquidglass.compose.components.GlassSurface
import dev.liquidglass.core.GlassRenderTier
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(ThemePrefs.load(context)) }
    var tune by remember { mutableStateOf(ThemePrefs.loadTune(context)) }
    var wallpaper by remember { mutableStateOf(ThemePrefs.loadWallpaper(context)) }
    var seed by remember { mutableStateOf(ThemePrefs.loadSeed(context)) }
    var optimized by remember { mutableStateOf(ThemePrefs.loadOptimized(context)) }
    val appearance = Appearance(
        mode = mode,
        tune = tune,
        setTune = { t ->
            tune = t
            ThemePrefs.saveTune(context, t)
        },
        wallpaperColors = wallpaper,
        seed = seed,
        setWallpaperColors = { on ->
            wallpaper = on
            ThemePrefs.saveWallpaper(context, on)
        },
        setSeed = { c ->
            seed = c
            ThemePrefs.saveSeed(context, c)
        },
        optimized = optimized,
        setOptimized = { on ->
            optimized = on
            ThemePrefs.saveOptimized(context, on)
        }
    ) { m ->
        mode = m
        ThemePrefs.save(context, m)
    }
    val glassState = rememberLiquidGlassProviderState()
    MotionPrefs.optimized = optimized
    glassState.requestedTier = if (optimized) GlassRenderTier.SCRIM else null
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalGlassState provides glassState
    ) {
        ZapretKsuTheme(mode, wallpaper, seed) {
            App()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS
    val optimized = LocalAppearance.current.optimized
    val glassState = LocalGlassState.current
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(true) }
    var route by remember { mutableStateOf(Route.Home) }
    var bcRunning by remember { mutableStateOf(false) }

    val snap = remember { mutableStateOf<ZapretCtl.Snapshot?>(null) }
    LaunchedEffect(rooted) {
        if (rooted != true) return@LaunchedEffect
        while (true) {
            val s = ZapretCtl.snapshot(logLines = 0)
            snap.value = s
            bcRunning = s.blockcheck["bc_state"] == "running"
            val busy = bcRunning || s.update["update_state"] == "running"
            delay(if (busy) 1500L else 4000L)
        }
    }
    LaunchedEffect(bcRunning) {
        if (bcRunning && route != Route.Blockcheck) route = Route.Blockcheck
    }
    val snackbarState = remember { SnackbarHostState() }
    fun go(target: Route) {
        if (bcRunning && target != Route.Blockcheck) {
            scope.launch {
                val result = snackbarState.showSnackbar(
                    message = "Blockcheck is running. Wait for it to finish or cancel it.",
                    actionLabel = "Cancel",
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) ZapretCtl.bcCancel()
            }
        } else {
            route = target
        }
    }

    fun check() {
        scope.launch {
            checking = true
            rooted = ZapretCtl.hasRoot()
            checking = false
        }
    }

    LaunchedEffect(Unit) { check() }

    BackHandler(enabled = rooted != true) { }
    BackHandler(enabled = rooted == true && !route.isTopLevel) { go(route.parent) }

    CompositionLocalProvider(LocalSnapshot provides snap) {
    Box(Modifier.fillMaxSize()) {
        if (glassState != null) {
            AmbientBackdrop(glassState, glass)
        }

        Scaffold(
            modifier = Modifier,
            containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarState) { AppSnackbar(it) } },
            topBar = {
                if (glass && glassState != null) {
                    GlassTopBar(route, glassState) { go(route.parent) }
                } else {
                    MaterialTopBar(route) { go(route.parent) }
                }
            },
            bottomBar = {
                if (rooted == true && (route.isTopLevel || bcRunning)) {
                    FluidNavBar(route, if (glass) glassState else null) { go(it) }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (rooted == true) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            if (optimized) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val tabs = Route.TOP_LEVEL
                                val forward = if (targetState.isTopLevel && initialState.isTopLevel) {
                                    tabs.indexOf(targetState) > tabs.indexOf(initialState)
                                } else {
                                    !targetState.isTopLevel
                                }
                                val dir = if (forward) 1 else -1
                                (slideInHorizontally(zapretTween(320, easing = Md3Emphasized)) { it * dir / 8 } +
                                    fadeIn(zapretTween(260))) togetherWith
                                    (slideOutHorizontally(zapretTween(320, easing = Md3Emphasized)) { -it * dir / 8 } +
                                        fadeOut(zapretTween(180)))
                            }
                        },
                        label = "route"
                    ) { current ->
                        RouteScreen(current) { go(it) }
                    }
                }
            }
        }
    }
    }

    if (rooted != true) {
        RootGate(checking = checking, onRetry = { check() })
    }
}

@Composable
private fun RouteScreen(route: Route, onOpen: (Route) -> Unit) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Settings -> SettingsScreen(onOpen = onOpen)
        Route.Tools -> ToolsScreen(onOpen = onOpen)
        Route.Logs -> LogsScreen()
        Route.Apps -> AppsScreen()
        Route.Updates -> ComponentsScreen()
        Route.Blockcheck -> CompositionLocalProvider(LocalFlatGlass provides true) { BlockcheckScreen() }
        Route.Dns -> DnsScreen()
        Route.Lists -> CompositionLocalProvider(LocalFlatGlass provides true) { ListsScreen() }
        Route.Tg -> TgProxyScreen()
        Route.About -> AboutScreen()
    }
}

@Composable
private fun GlassTopBar(route: Route, state: LiquidGlassProviderState, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            style = GlassStyle.IOS26Regular.tuned(LocalAppearance.current.tune),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!route.isTopLevel) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    route.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialTopBar(route: Route, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(route.title, style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = {
            if (!route.isTopLevel) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
    )
}

internal fun Route.icon(): ImageVector = when (this) {
    Route.Home -> Icons.Filled.Home
    Route.Settings -> Icons.Filled.Settings
    Route.Tools -> Icons.Filled.Build
    Route.Logs -> Icons.Filled.Info
    Route.Apps -> Icons.Filled.Apps
    Route.Updates -> Icons.Filled.Download
    Route.Blockcheck -> Icons.Filled.Search
    Route.Dns -> Icons.Filled.Lock
    Route.Lists -> Icons.Filled.Edit
    Route.Tg -> Icons.AutoMirrored.Filled.Send
    Route.About -> Icons.Filled.Info
}
