package com.phiragen.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.delay

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
        val dark = isDarkNow(st.settings.theme)
        var exportIndex by remember { mutableIntStateOf(-1) }

        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // 部分来源不支持持久授权, 仅影响恢复上次会话
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

        BackHandler(enabled = st.back.isNotEmpty() || st.screen is Screen.Working) { vm.back() }

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

        Box(Modifier.fillMaxSize()) {
            when (val s = st.screen) {
                Screen.Home -> HomeScreen(
                    st, dark, launchPick, vm::clearAudio,
                    { d -> vm.updateSettings { it.copy(difficulties = if (d in it.difficulties) it.difficulties - d else it.difficulties + d) } },
                    { f -> vm.updateSettings { it.copy(format = f) } },
                    vm::generate, vm::resume, { vm.go(Screen.Settings) },
                )
                Screen.Working -> WorkingScreen(st, dark, vm::cancel, vm::generate, { vm.back() })
                Screen.Results -> ResultScreen(
                    st, dark, { vm.back() },
                    { i -> vm.go(Screen.Preview(i)) },
                    { i -> exportIndex = i; createDoc.launch(vm.suggestedName(i)) },
                    vm::share,
                    { pickTree.launch(null) },
                    vm::regenerate,
                    { f -> vm.updateSettings { it.copy(format = f) } },
                    { vm.go(Screen.Settings) },
                )
                is Screen.Preview -> PreviewScreen(st, s.index, dark) { vm.back() }
                Screen.Settings -> com.phiragen.app.ui.SettingsScreen(
                    st, { vm.back() }, vm::updateSettings, vm::testAi, vm::clearAiKey, vm::clearCache,
                    { shareLog() }, { vm.go(Screen.About) },
                )
                Screen.About -> AboutScreen({ vm.back() }, { vm.go(Screen.Licenses) }, BuildInfo.versionName(context))
                Screen.Licenses -> LicensesScreen { vm.back() }
            }
            if (st.message != null) {
                Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                    Banner(st.message, Palette.hd)
                }
            }
        }
    }

    private fun shareLog() {
        val log = DiagLog.read().ifEmpty { "暂无日志" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(send, "导出诊断日志"))
    }
}

object BuildInfo {
    fun versionName(c: android.content.Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
