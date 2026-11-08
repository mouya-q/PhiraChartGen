package com.phiragen.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.phiragen.app.data.DiagLog
import com.phiragen.app.ui.AboutScreen
import com.phiragen.app.ui.AppTheme
import com.phiragen.app.ui.Banner
import com.phiragen.app.ui.HomeScreen
import com.phiragen.app.ui.LicensesScreen
import com.phiragen.app.ui.Palette
import com.phiragen.app.ui.PreviewScreen
import com.phiragen.app.ui.ResultScreen
import com.phiragen.app.ui.SettingsScreen
import com.phiragen.app.ui.WorkingScreen
import com.phiragen.app.ui.isDarkNow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val st by vm.state.collectAsStateWithLifecycle()
            AppTheme(st.settings.theme) { Root(st) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 接收其他应用分享过来的音频 */
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) vm.pickAudio(uri)
        }
    }

    @Composable
    private fun Root(st: UiState) {
        val context = LocalContext.current
        val view = androidx.compose.ui.platform.LocalView.current
        val dark = isDarkNow(st.settings.theme)
        val useTransitionBlur = st.settings.transitionBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        var exportIndex by remember { mutableIntStateOf(-1) }
        var displayedScreen by remember { mutableStateOf(st.screen) }
        var lastBackStack by remember { mutableStateOf(st.back) }
        var outgoingScreen by remember { mutableStateOf<Screen?>(null) }
        var transitionMode by remember { mutableIntStateOf(1) }
        var skipNextTransition by remember { mutableStateOf(false) }
        var predictiveDirection by remember { mutableFloatStateOf(1f) }
        var predictiveCommitted by remember { mutableStateOf(false) }
        var predictiveFrom by remember { mutableStateOf<Screen?>(null) }
        var predictiveTarget by remember { mutableStateOf<Screen?>(null) }
        val navigationProgress = remember { Animatable(1f) }
        val predictiveProgress = remember { Animatable(0f) }

        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // 部分来源不支持持久授权
                }
                vm.pickAudio(uri)
            }
        }
        val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null && exportIndex >= 0) vm.exportOne(exportIndex, uri)
        }
        val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) vm.exportAll(uri)
        }
        val launchPick = { pickAudio.launch(arrayOf("audio/*", "application/ogg")) }
        val launchExport = { index: Int ->
            exportIndex = index
            createDoc.launch(vm.suggestedName(index))
        }

        LaunchedEffect(st.screen) {
            if (st.screen != displayedScreen) {
                val previous = displayedScreen
                val isPop = lastBackStack.lastOrNull { it !is Screen.Working } == st.screen
                val skip = skipNextTransition
                skipNextTransition = false

                if (!skip) {
                    outgoingScreen = if (previous is Screen.Working) {
                        lastBackStack.lastOrNull { it !is Screen.Working } ?: previous
                    } else {
                        previous
                    }
                    transitionMode = if (isPop) -1 else 1
                    navigationProgress.snapTo(0f)
                }

                displayedScreen = st.screen
                lastBackStack = st.back

                if (skip) {
                    outgoingScreen = null
                    navigationProgress.snapTo(1f)
                } else {
                    navigationProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                    )
                    outgoingScreen = null
                }
            } else {
                lastBackStack = st.back
            }
        }

        PredictiveBackHandler(enabled = st.back.isNotEmpty() || st.screen is Screen.Working) { events ->
            try {
                events.collect { event ->
                    if (predictiveProgress.value <= 0.001f) {
                        predictiveDirection = if (event.touchX > widthPxForBack() * 0.5f) -1f else 1f
                    }
                    predictiveProgress.snapTo(event.progress)
                }
                val target = st.back.lastOrNull { it !is Screen.Working }
                if (predictiveProgress.value > 0.001f && target != null) {
                    predictiveFrom = st.screen
                    predictiveTarget = target
                    predictiveCommitted = true
                    skipNextTransition = true
                }
                vm.back()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                withContext(NonCancellable) {
                    if (predictiveCommitted) {
                        predictiveProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                        predictiveTarget?.let { target ->
                            withTimeoutOrNull(2000) {
                                vm.state.first { it.screen == target }
                            }
                        }
                        predictiveProgress.snapTo(0f)
                        predictiveCommitted = false
                        predictiveFrom = null
                        predictiveTarget = null
                    } else {
                        predictiveProgress.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
            }
        }

        LaunchedEffect(st.shareUri) {
            val uri = st.shareUri ?: return@LaunchedEffect
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享谱面包"))
            vm.shareHandled()
        }
        LaunchedEffect(st.message) {
            if (st.message != null) {
                delay(3200)
                vm.toastShown()
            }
        }


        val renderPage: @Composable (Screen, Modifier, Boolean) -> Unit = { target, pageModifier, isBackground ->
            MainScreenLayer(
                screen = target,
                st = st,
                dark = dark,
                modifier = pageModifier,
                backgroundLayer = isBackground,
                vm = vm,
                launchPick = launchPick,
                launchExport = launchExport,
                launchExportAll = { pickTree.launch(null) },
                context = context,
                shareLog = ::shareLog,
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val gestureProgress = predictiveProgress.value.coerceIn(0f, 1f)
            val transition = navigationProgress.value.coerceIn(0f, 1f)
            val backTarget = st.back.lastOrNull { it !is Screen.Working }

            when {
                predictiveCommitted && predictiveFrom != null && predictiveTarget != null -> {
                    renderPage(
                        predictiveTarget!!,
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                scaleX = 0.965f + 0.035f * gestureProgress
                                scaleY = 0.965f + 0.035f * gestureProgress
                                alpha = 0.92f + 0.08f * gestureProgress
                            }
                            .then(if (useTransitionBlur) Modifier.blur(8.dp * (1f - gestureProgress)) else Modifier),
                        true,
                    )
                    renderPage(
                        predictiveFrom!!,
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = widthPx * predictiveDirection * gestureProgress
                            scaleX = 1f - 0.025f * gestureProgress
                            scaleY = 1f - 0.025f * gestureProgress
                            alpha = 1f - 0.08f * gestureProgress
                        },
                        false,
                    )
                }
                gestureProgress > 0.001f && backTarget != null -> {
                    renderPage(
                        backTarget,
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                scaleX = 0.965f + 0.035f * gestureProgress
                                scaleY = 0.965f + 0.035f * gestureProgress
                                alpha = 0.92f + 0.08f * gestureProgress
                            }
                            .then(if (useTransitionBlur) Modifier.blur(8.dp * (1f - gestureProgress)) else Modifier),
                        true,
                    )
                    renderPage(
                        st.screen,
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = widthPx * predictiveDirection * gestureProgress
                            scaleX = 1f - 0.025f * gestureProgress
                            scaleY = 1f - 0.025f * gestureProgress
                            alpha = 1f - 0.06f * gestureProgress
                        },
                        false,
                    )
                }
                outgoingScreen != null && transition < 1f && transitionMode > 0 -> {
                    renderPage(
                        outgoingScreen!!,
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1f - 0.035f * transition
                                scaleY = 1f - 0.035f * transition
                                alpha = 1f - 0.045f * transition
                            }
                            .then(if (useTransitionBlur) Modifier.blur(7.dp * transition) else Modifier),
                        true,
                    )
                    renderPage(
                        st.screen,
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = widthPx * 0.14f * (1f - transition)
                            alpha = 0.92f + 0.08f * transition
                        },
                        false,
                    )
                }
                outgoingScreen != null && transition < 1f && transitionMode < 0 -> {
                    renderPage(
                        st.screen,
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                scaleX = 0.965f + 0.035f * transition
                                scaleY = 0.965f + 0.035f * transition
                            }
                            .then(if (useTransitionBlur) Modifier.blur(7.dp * (1f - transition)) else Modifier),
                        true,
                    )
                    renderPage(
                        outgoingScreen!!,
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = widthPx * transition
                            scaleX = 1f - 0.025f * transition
                            scaleY = 1f - 0.025f * transition
                            alpha = 1f - 0.08f * transition
                        },
                        true,
                    )
                }
                else -> renderPage(st.screen, Modifier.fillMaxSize(), false)
            }

            if (st.message != null) {
                Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                    Banner(st.message, Palette.hd)
                }
            }
        }
    }

    private fun widthPxForBack(): Float = resources.displayMetrics.widthPixels.toFloat()

    private fun shareLog() {
        val log = DiagLog.read().ifEmpty { "暂无日志" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(send, "导出诊断日志"))
    }
}


@Composable
private fun MainScreenLayer(
    screen: Screen,
    st: UiState,
    dark: Boolean,
    modifier: Modifier,
    backgroundLayer: Boolean,
    vm: MainViewModel,
    launchPick: () -> Unit,
    launchExport: (Int) -> Unit,
    launchExportAll: () -> Unit,
    context: Context,
    shareLog: () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        when (screen) {
            Screen.Home -> HomeScreen(
                st, dark, launchPick, vm::clearAudio,
                { difficulty ->
                    vm.updateSettings {
                        it.copy(
                            difficulties = if (difficulty in it.difficulties) {
                                it.difficulties - difficulty
                            } else {
                                it.difficulties + difficulty
                            },
                        )
                    }
                },
                { format -> vm.updateSettings { it.copy(format = format) } },
                vm::generate,
                vm::resume,
                { vm.go(Screen.Settings) },
            )
            Screen.Working -> WorkingScreen(st, dark, vm::cancel, vm::generate, { vm.back() })
            Screen.Results -> ResultScreen(
                st, dark, { vm.back() },
                { index -> vm.go(Screen.Preview(index)) },
                launchExport,
                vm::share,
                launchExportAll,
                vm::regenerate,
                { format -> vm.updateSettings { it.copy(format = format) } },
                { vm.go(Screen.Settings) },
            )
            is Screen.Preview -> PreviewScreen(
                st, screen.index, dark, { vm.back() }, playbackEnabled = !backgroundLayer,
            )
            Screen.Settings -> SettingsScreen(
                st, dark, { vm.back() }, vm::updateSettings, vm::testAi, vm::clearAiKey, vm::clearCache,
                shareLog, { vm.go(Screen.About) },
            )
            Screen.About -> AboutScreen(dark, { vm.back() }, { vm.go(Screen.Licenses) }, BuildInfo.versionName(context))
            Screen.Licenses -> LicensesScreen(dark) { vm.back() }
        }
    }
}

object BuildInfo {
    fun versionName(c: android.content.Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
