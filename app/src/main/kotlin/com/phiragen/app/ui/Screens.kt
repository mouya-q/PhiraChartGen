package com.phiragen.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phiragen.app.Screen
import com.phiragen.app.UiState
import com.phiragen.core.ai.AiOutcome
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.Difficulty
import com.phiragen.core.chart.NoteType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

fun diffColor(d: Difficulty): Color = when (d) {
    Difficulty.EZ -> Palette.ez
    Difficulty.HD -> Palette.hd
    Difficulty.IN -> Palette.inn
    Difficulty.AT -> Palette.at
}

fun fmtTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

fun fmtSize(bytes: Long): String =
    if (bytes <= 0) "" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

// ============================================================ 首页

@Composable
fun HomeScreen(
    st: UiState,
    dark: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onToggle: (Difficulty) -> Unit,
    onFormat: (ChartFormat) -> Unit,
    onGenerate: () -> Unit,
    onResume: () -> Unit,
    onSettings: () -> Unit,
) {
    val cover = remember(st.audio?.cover) { st.audio?.cover?.asImageBitmap() }
    PScaffold(
        topBar = { PTopBar("Phira 谱面生成器", actions = { PTextButton("设置", onSettings) }) },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            GlassBackdrop(cover, st.settings.blurEnabled, dark)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val audio = st.audio
                if (audio == null) {
                    EmptyHome(st, dark, onPick, onResume)
                } else {
                    GlassCard(dark, Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.2f))) {
                                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Txt("♪", Modifier.align(Alignment.Center), MiuixTheme.textStyles.title1, MiuixTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(audio.title, style = MiuixTheme.textStyles.title3)
                                if (audio.artist.isNotEmpty()) Summary(audio.artist)
                                Summary(listOf(fmtTime(audio.durationMs / 1000.0), audio.extension.uppercase(), fmtSize(audio.sizeBytes)).filter { it.isNotEmpty() }.joinToString("  ·  "))
                            }
                            PTextButton("移除", onClear)
                        }
                    }
                    GlassCard(dark, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Txt("难度", style = MiuixTheme.textStyles.body1)
                            Summary("可多选, 每个难度生成一个独立谱面")
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (d in Difficulty.values()) {
                                    Chip(d.key, diffColor(d), d in st.settings.difficulties, { onToggle(d) }, Modifier.weight(1f))
                                }
                            }
                            if (st.settings.difficulties.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Banner("请至少选择一个难度", Palette.warn)
                            }
                        }
                    }
                    GlassCard(dark, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Txt("谱面格式", Modifier.padding(start = 16.dp, top = 10.dp), MiuixTheme.textStyles.body1)
                            Summary("推荐 RPE, 兼容性最好", Modifier.padding(start = 16.dp))
                            Segmented(
                                listOf(ChartFormat.RPE to "RPE", ChartFormat.OFFICIAL to "官方", ChartFormat.PEC to "PEC"),
                                st.settings.format, onFormat,
                            )
                        }
                    }
                    if (st.settings.ai.ready) Banner("AI 增强已开启, 生成时会上传分段统计数据 (不含音频)", Palette.hd)
                    PButton("开始生成", onGenerate, Modifier.fillMaxWidth().height(52.dp), enabled = st.settings.difficulties.isNotEmpty())
                    PButton("换一首", onPick, Modifier.fillMaxWidth(), primary = false)
                }
                if (st.loadingAudio) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Summary("正在读取音频信息")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EmptyHome(st: UiState, dark: Boolean, onPick: () -> Unit, onResume: () -> Unit) {
    Spacer(Modifier.height(36.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Txt("♪", style = MiuixTheme.textStyles.title1, color = MiuixTheme.colorScheme.primary) }
        Spacer(Modifier.height(18.dp))
        Txt("导入音频开始制谱", style = MiuixTheme.textStyles.title3)
        Spacer(Modifier.height(6.dp))
        Summary("支持 MP3, FLAC, OGG, WAV, M4A 等系统可解码的格式")
        Summary("音频只在本机处理, 不会被上传")
    }
    Spacer(Modifier.height(12.dp))
    PButton("选择音频文件", onPick, Modifier.fillMaxWidth().height(52.dp))
    if (st.hasSession) {
        GlassCard(dark, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("继续上次的曲目", style = MiuixTheme.textStyles.body1)
                    Summary("复用已保存的分析, 无需重新解码")
                }
                PButton("继续", onResume, primary = false)
            }
        }
    }
}

// ============================================================ 处理中

@Composable
fun WorkingScreen(st: UiState, dark: Boolean, onCancel: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    PScaffold(topBar = { PTopBar("正在生成") }) { pad ->
        Box(Modifier.fillMaxSize()) {
            GlassBackdrop(st.audio?.cover?.asImageBitmap(), st.settings.blurEnabled, dark)
            Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.Center) {
                GlassCard(dark, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (st.error != null) {
                            Txt("处理失败", style = MiuixTheme.textStyles.title3, color = Palette.bad)
                            Spacer(Modifier.height(8.dp))
                            Txt(st.error, align = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PButton("返回", onBack, Modifier.weight(1f), primary = false)
                                PButton("重试", onRetry, Modifier.weight(1f))
                            }
                        } else {
                            Txt(st.stage, style = MiuixTheme.textStyles.title3)
                            Spacer(Modifier.height(4.dp))
                            Summary(st.audio?.title ?: "")
                            Spacer(Modifier.height(18.dp))
                            LinearProgressIndicator(progress = st.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Summary("${(st.progress * 100).toInt()}%")
                            Spacer(Modifier.height(18.dp))
                            PButton("取消", onCancel, Modifier.fillMaxWidth(), primary = false)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ 结果

@Composable
fun ResultScreen(
    st: UiState,
    dark: Boolean,
    onBack: () -> Unit,
    onPreview: (Int) -> Unit,
    onExport: (Int) -> Unit,
    onShare: (Int) -> Unit,
    onExportAll: () -> Unit,
    onRegenerate: () -> Unit,
    onFormat: (ChartFormat) -> Unit,
    onSettings: () -> Unit,
) {
    val cover = remember(st.audio?.cover) { st.audio?.cover?.asImageBitmap() }
    PScaffold(topBar = { PTopBar("生成结果", onBack, actions = { PTextButton("设置", onSettings) }) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            GlassBackdrop(cover, st.settings.blurEnabled, dark)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassCard(dark, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Txt(st.audio?.title ?: "", style = MiuixTheme.textStyles.title3)
                        Summary(String.format(Locale.ROOT, "%.1f BPM  ·  %s  ·  %d 个谱面", st.bpm, fmtTime(st.durationSec), st.charts.size))
                        Spacer(Modifier.height(8.dp))
                        when (val ai = st.aiOutcome) {
                            is AiOutcome.Applied -> Banner("已应用 AI 编排建议 (${ai.windowCount} 段)", Palette.ok)
                            is AiOutcome.FellBack -> Banner("AI 增强未生效, 已使用离线结果: ${ai.reason}", Palette.warn)
                            else -> Unit
                        }
                    }
                }
                st.charts.forEachIndexed { i, chart ->
                    ChartCard(i, chart, st, dark, onPreview, onExport, onShare)
                }
                GlassCard(dark, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Txt("导出格式", Modifier.padding(start = 16.dp, top = 10.dp), MiuixTheme.textStyles.body1)
                        Segmented(
                            listOf(ChartFormat.RPE to "RPE", ChartFormat.OFFICIAL to "官方", ChartFormat.PEC to "PEC"),
                            selected = st.settings.format,
                            onSelect = onFormat,
                        )
                        Summary("导出的谱面包可直接在 Phira 中导入", Modifier.padding(start = 16.dp, bottom = 8.dp))
                    }
                }
                PButton("全部导出到文件夹", onExportAll, Modifier.fillMaxWidth().height(52.dp), enabled = !st.busyExport)
                PButton("按当前设置重新生成", onRegenerate, Modifier.fillMaxWidth(), primary = false)
                if (st.busyExport) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Summary("正在打包")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ChartCard(
    index: Int, chart: Chart, st: UiState, dark: Boolean,
    onPreview: (Int) -> Unit, onExport: (Int) -> Unit, onShare: (Int) -> Unit,
) {
    val color = diffColor(chart.profile.difficulty)
    val report = st.reports[index]
    GlassCard(dark, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Txt(chart.levelText, color = Color.White, style = MiuixTheme.textStyles.body1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt("${chart.noteCount} 个音符", style = MiuixTheme.textStyles.body1)
                    val nps = chart.noteCount / (chart.durationSec.coerceAtLeast(1.0))
                    Summary(String.format(Locale.ROOT, "平均 %.1f 个每秒  ·  %d 条判定线", nps, chart.profile.lineCount))
                }
            }
            Spacer(Modifier.height(8.dp))
            val counts = NoteType.values().map { t -> t to chart.notes.count { it.type == t } }.filter { it.second > 0 }
            Summary(counts.joinToString("   ") { (t, c) -> "${typeName(t)} $c" })
            if (report != null) {
                Spacer(Modifier.height(8.dp))
                if (report.ok) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Palette.ok); Spacer(Modifier.width(6.dp))
                        Summary("兼容性验证通过" + if (report.warnings.isNotEmpty()) " (${report.warnings.size} 条提示)" else "")
                    }
                    report.warnings.take(2).forEach { Summary(it) }
                } else {
                    Banner("验证未通过: " + report.errors.take(2).joinToString("; "), Palette.bad)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton("预览", { onPreview(index) }, Modifier.weight(1f), primary = false)
                PButton("导出", { onExport(index) }, Modifier.weight(1f), enabled = !st.busyExport)
                PButton("分享", { onShare(index) }, Modifier.weight(1f), primary = false, enabled = !st.busyExport)
            }
        }
    }
}

fun typeName(t: NoteType) = when (t) {
    NoteType.TAP -> "点击"
    NoteType.HOLD -> "长按"
    NoteType.FLICK -> "滑动"
    NoteType.DRAG -> "拖动"
}