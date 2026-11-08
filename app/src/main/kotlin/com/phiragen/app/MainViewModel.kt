package com.phiragen.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phiragen.app.data.AppSettings
import com.phiragen.app.data.DiagLog
import com.phiragen.app.data.SettingsStore
import com.phiragen.app.media.AudioException
import com.phiragen.app.media.AudioInfo
import com.phiragen.app.media.AudioProbe
import com.phiragen.app.media.Pipeline
import com.phiragen.core.ai.AiClient
import com.phiragen.core.ai.AiOutcome
import com.phiragen.core.audio.AnalysisException
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.Guidance
import com.phiragen.core.export.PackBuilder
import com.phiragen.core.export.ValidationReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Working : Screen
    data object Results : Screen
    data class Preview(val index: Int) : Screen
    data object Settings : Screen
    data object About : Screen
    data object Licenses : Screen
}

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data object Ok : AiTestState
    data class Failed(val reason: String) : AiTestState
}

data class UiState(
    val screen: Screen = Screen.Home,
    val settings: AppSettings = AppSettings(),
    val audio: AudioInfo? = null,
    val loadingAudio: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val charts: List<Chart> = emptyList(),
    val aiOutcome: AiOutcome? = null,
    val error: String? = null,
    val message: String? = null,
    val busyExport: Boolean = false,
    val reports: Map<Int, ValidationReport> = emptyMap(),
    val hasSession: Boolean = false,
    val bpm: Double = 0.0,
    val durationSec: Double = 0.0,
    val aiTest: AiTestState = AiTestState.Idle,
    val shareUri: Uri? = null,
    /** 导航历史, 用于系统返回键 */
    val back: List<Screen> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var analysis: AnalysisResult? = null
    private var guidance: Guidance = Guidance.NONE

    init {
        _state.update { it.copy(hasSession = Pipeline.loadSession(app) != null) }
    }

    private val ctx get() = getApplication<Application>()

    // ---------- 导航 ----------

    fun go(s: Screen) = _state.update { it.copy(screen = s, back = it.back + it.screen) }

    /** 返回上一页, 无历史时返回 false 交给系统处理 */
    fun back(): Boolean {
        val st = _state.value
        if (st.screen is Screen.Working && job?.isActive == true) {
            cancel()
            return true
        }
        if (st.back.isEmpty()) return false
        val prev = st.back.last()
        val target = if (prev is Screen.Working) st.back.lastOrNull { it !is Screen.Working } ?: Screen.Home else prev
        _state.update { it.copy(screen = target, back = it.back.dropLast(1).filter { s -> s !is Screen.Working }) }
        return true
    }

    fun toastShown() = _state.update { it.copy(message = null) }
    fun shareHandled() = _state.update { it.copy(shareUri = null) }
    private fun toast(m: String) = _state.update { it.copy(message = m) }

    // ---------- 设置 ----------

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val next = f(_state.value.settings)
        store.save(next)
        _state.update { it.copy(settings = next) }
    }

    // ---------- 导入 ----------

    fun pickAudio(uri: Uri) {
        _state.update { it.copy(loadingAudio = true, error = null) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { AudioProbe.probe(ctx, uri) }
                analysis = null
                _state.update { it.copy(audio = info, loadingAudio = false, charts = emptyList(), reports = emptyMap()) }
            } catch (e: AudioException) {
                _state.update { it.copy(loadingAudio = false, message = e.message) }
            }
        }
    }

    fun clearAudio() {
        analysis = null
        _state.update { it.copy(audio = null, charts = emptyList(), reports = emptyMap()) }
    }

    // ---------- 生成 ----------

    fun generate() {
        val audio = _state.value.audio ?: return
        if (_state.value.settings.difficulties.isEmpty()) {
            toast("请至少选择一个难度")
            return
        }
        job?.cancel()
        _state.update {
            it.copy(screen = Screen.Working, back = listOf(Screen.Home), error = null, progress = 0f,
                stage = "解码并分析节奏", charts = emptyList(), reports = emptyMap(), aiOutcome = null)
        }
        job = viewModelScope.launch { runGeneration(audio, reuse = analysis) }
    }

    /** 恢复上次会话: 复用分析缓存, 不再重新解码 */
    fun resume() {
        val saved = Pipeline.loadSession(ctx)
        if (saved == null) {
            _state.update { it.copy(hasSession = false, message = "没有可恢复的内容") }
            return
        }
        job?.cancel()
        _state.update { it.copy(screen = Screen.Working, back = listOf(Screen.Home), error = null, progress = 0f, stage = "读取上次的分析") }
        job = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { AudioProbe.probe(ctx, saved.first) }
                _state.update { it.copy(audio = info) }
                analysis = saved.second
                runGeneration(info, reuse = saved.second)
            } catch (e: AudioException) {
                Pipeline.clearSession(ctx)
                _state.update { it.copy(hasSession = false, error = "原音频已无法访问, 请重新导入 (${e.message})") }
            } catch (e: SecurityException) {
                Pipeline.clearSession(ctx)
                _state.update { it.copy(hasSession = false, error = "原音频的访问权限已失效, 请重新导入") }
            }
        }
    }

    private suspend fun runGeneration(audio: AudioInfo, reuse: AnalysisResult?) {
        try {
            val a = reuse ?: Pipeline.analyze(ctx, audio) { p -> _state.update { it.copy(progress = p * 0.7f) } }
            analysis = a
            _state.update { it.copy(bpm = a.bpm, durationSec = a.durationSec) }
            if (reuse == null) {
                Pipeline.saveSession(ctx, a, audio)
                _state.update { it.copy(hasSession = true) }
            }
            val s = _state.value.settings
            _state.update { it.copy(progress = 0.75f, stage = if (s.ai.ready) "AI 增强编排" else "生成谱面") }
            val (g, outcome) = Pipeline.enhance(s, a)
            guidance = g
            _state.update { it.copy(progress = 0.85f, stage = "生成谱面", aiOutcome = outcome) }
            val charts = Pipeline.generate(a, s, g)
            _state.update {
                it.copy(screen = Screen.Results, back = listOf(Screen.Home), charts = charts, progress = 1f, aiOutcome = outcome)
            }
            DiagLog.i("generate", "完成 谱面=${charts.size} AI=${outcome.javaClass.simpleName}")
        } catch (e: CancellationException) {
            _state.update { it.copy(screen = Screen.Home, back = emptyList(), message = "已取消") }
            throw e
        } catch (e: AudioException) {
            fail(e.message ?: "音频处理失败", e)
        } catch (e: AnalysisException) {
            fail(e.message ?: "分析失败", e)
        } catch (e: OutOfMemoryError) {
            fail("内存不足, 请换一段较短的音频", e)
        } catch (e: Exception) {
            fail("处理失败, 请重试", e)
        }
    }

    private fun fail(msg: String, e: Throwable) {
        DiagLog.e("generate", msg, e)
        _state.update { it.copy(error = msg) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 在结果页修改设置后用已有分析重新生成 */
    fun regenerate() {
        val a = analysis ?: return
        val audio = _state.value.audio ?: return
        job?.cancel()
        _state.update { it.copy(screen = Screen.Working, back = listOf(Screen.Home), error = null, progress = 0.75f, stage = "生成谱面", reports = emptyMap()) }
        job = viewModelScope.launch { runGeneration(audio, reuse = a) }
    }

    // ---------- 导出 ----------

    fun suggestedName(index: Int): String {
        val st = _state.value
        val chart = st.charts.getOrNull(index) ?: return "chart.zip"
        return PackBuilder.safeFileName(st.audio?.title ?: "chart", chart.profile.difficulty.key)
    }

    fun exportOne(index: Int, target: Uri) {
        val st = _state.value
        val chart = st.charts.getOrNull(index) ?: return
        val audio = st.audio ?: return
        val a = analysis ?: return
        runExport {
            val assets = Pipeline.prepareAssets(ctx, audio, a) { }
            Pipeline.writePack(ctx, target, chart, Pipeline.metaFor(audio, a, st.settings), st.settings.format, assets)
            val report = Pipeline.validate(ctx, target)
            _state.update { it.copy(reports = it.reports + (index to report)) }
            toast(if (report.ok) "已导出并通过兼容性验证" else "已导出, 但验证发现问题")
        }
    }

    fun exportAll(tree: Uri) {
        val st = _state.value
        val audio = st.audio ?: return
        val a = analysis ?: return
        runExport {
            val assets = Pipeline.prepareAssets(ctx, audio, a) { }
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            var okCount = 0
            for ((i, chart) in st.charts.withIndex()) {
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/zip", suggestedName(i))
                    ?: throw AudioException("无法在所选文件夹创建文件")
                Pipeline.writePack(ctx, doc, chart, Pipeline.metaFor(audio, a, st.settings), st.settings.format, assets)
                val report = Pipeline.validate(ctx, doc)
                if (report.ok) okCount++
                _state.update { it.copy(reports = it.reports + (i to report)) }
            }
            toast("已导出 ${st.charts.size} 个谱面, ${okCount} 个通过验证")
        }
    }

    fun share(index: Int) {
        val st = _state.value
        val chart = st.charts.getOrNull(index) ?: return
        val audio = st.audio ?: return
        val a = analysis ?: return
        runExport {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, suggestedName(index))
            val assets = Pipeline.prepareAssets(ctx, audio, a) { }
            f.outputStream().buffered().use { PackBuilder.write(it, chart, Pipeline.metaFor(audio, a, st.settings), st.settings.format, assets) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            _state.update { it.copy(shareUri = uri) }
        }
    }

    private fun runExport(block: suspend () -> Unit) {
        if (_state.value.busyExport) return
        _state.update { it.copy(busyExport = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                DiagLog.e("export", "导出失败", e)
                toast(if (e is AudioException) e.message ?: "导出失败" else "导出失败, 请检查存储空间后重试")
            } finally {
                _state.update { it.copy(busyExport = false) }
            }
        }
    }

    // ---------- AI ----------

    fun testAi() {
        val ai = _state.value.settings.ai
        val bad = ai.toConfig().validate()
        if (bad != null) {
            _state.update { it.copy(aiTest = AiTestState.Failed(bad)) }
            return
        }
        _state.update { it.copy(aiTest = AiTestState.Testing) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = AiClient(ai.toConfig()).test()
            _state.update { it.copy(aiTest = if (r == null) AiTestState.Ok else AiTestState.Failed(r)) }
        }
    }

    // ---------- 数据 ----------

    fun clearCache() {
        Pipeline.clearSession(ctx)
        File(ctx.cacheDir, "transcode").deleteRecursively()
        File(ctx.cacheDir, "share").deleteRecursively()
        _state.update { it.copy(hasSession = false, message = "缓存已清理") }
    }

    fun clearAiKey() = updateSettings { it.copy(ai = it.ai.copy(apiKey = "", enabled = false)) }
}
