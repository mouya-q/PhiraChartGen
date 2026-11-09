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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.phiragen.app.UiState
import com.phiragen.core.ai.AiOutcome
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.Difficulty
import com.phiragen.core.chart.NoteType
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
    PScaffold(topBar = {}) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PageTitle(
                    title = "PhiraChartGen",
                    subtitle = "本地音频分析与谱面生成",
                    action = { PTextButton("›", onSettings) },
                )

                val audio = st.audio
                if (audio == null) {
                    EmptyHome(st, dark, onPick, onResume)
                } else {
                    SurfaceCard(dark, Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(17.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(68.dp).clip(RoundedCornerShape(16.dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
                            ) {
                                if (cover != null) {
                                    Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    IconMusicMark(Modifier.align(Alignment.Center))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Txt(audio.title, style = MiuixTheme.textStyles.title3)
                                if (audio.artist.isNotEmpty()) Summary(audio.artist)
                                Summary(
                                    listOf(
                                        fmtTime(audio.durationMs / 1000.0),
                                        audio.extension.uppercase(),
                                        fmtSize(audio.sizeBytes),
                                    ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                                )
                            }
                            PTextButton("移除", onClear)
                        }
                    }

                    SurfaceCard(dark, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Txt("难度", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
                            Summary("可多选, 每个难度生成一个独立谱面")
                            Spacer(Modifier.height(5.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Difficulty.values().forEach { difficulty ->
                                    Chip(
                                        difficulty.key,
                                        diffColor(difficulty),
                                        difficulty in st.settings.difficulties,
                                        { onToggle(difficulty) },
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                            if (st.settings.difficulties.isEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Banner("请至少选择一个难度", Palette.warn)
                            }
                        }
                    }

                    SurfaceCard(dark, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(top = 15.dp, bottom = 5.dp)) {
                            Txt(
                                "谱面格式",
                                Modifier.padding(horizontal = 18.dp),
                                MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Summary("推荐 RPE, 兼容性较好", Modifier.padding(horizontal = 18.dp, vertical = 3.dp))
                            Segmented(
                                listOf(ChartFormat.RPE to "RPE", ChartFormat.OFFICIAL to "官方", ChartFormat.PEC to "PEC"),
                                selected = st.settings.format,
                                onSelect = onFormat,
                            )
                        }
                    }

                    if (st.settings.ai.ready) {
                        Banner("AI 增强已开启, 生成时会上传分段统计数据 (不含音频)", Palette.hd)
                    }

                    PButton(
                        "开始生成",
                        onGenerate,
                        Modifier.fillMaxWidth().height(54.dp),
                        enabled = st.settings.difficulties.isNotEmpty() && !st.loadingAudio,
                    )
                    PButton("更换歌曲", onPick, Modifier.fillMaxWidth().height(48.dp), primary = false)
                }

                if (st.loadingAudio) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Summary("正在读取音频信息")
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun EmptyHome(st: UiState, dark: Boolean, onPick: () -> Unit, onResume: () -> Unit) {
    Spacer(Modifier.height(22.dp))
    SurfaceCard(dark, Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                IconMusicMark()
            }
            Spacer(Modifier.height(3.dp))
            Txt("导入音频开始制谱", style = MiuixTheme.textStyles.title3)
            Summary("支持 MP3, FLAC, OGG, WAV, M4A 等系统可解码格式", align = TextAlign.Center)
            Summary("音频只在本机处理, 不会被上传", align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            PButton("选择音频文件", onPick, Modifier.fillMaxWidth().height(52.dp))
        }
    }
    if (st.hasSession) {
        SurfaceCard(dark, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Txt("继续上次的曲目", style = MiuixTheme.textStyles.body1)
                    Summary("复用已保存的分析, 无需重新解码")
                }
                PTextButton("›", onResume)
            }
        }
    }
}

@Composable
fun WorkingScreen(st: UiState, dark: Boolean, onCancel: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    PScaffold(topBar = { PTopBar("正在生成") }) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                SurfaceCard(dark, Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(21.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (st.error != null) {
                            Txt("处理失败", style = MiuixTheme.textStyles.title3, color = Palette.bad)
                            Txt(st.error, align = TextAlign.Center)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PTextButton("›", onBack, Modifier.weight(1f))
                                PButton("重试", onRetry, Modifier.weight(1f))
                            }
                        } else {
                            Box(
                                Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Txt("♪", style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.primary)
                            }
                            Txt(st.stage.ifBlank { "准备生成" }, style = MiuixTheme.textStyles.title3)
                            Summary(st.audio?.title ?: "")
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = st.progress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Summary("处理进度")
                                Txt("${(st.progress * 100).toInt()}%", color = MiuixTheme.colorScheme.primary)
                            }
                            PButton("取消生成", onCancel, Modifier.fillMaxWidth(), primary = false)
                        }
                    }
                }
            }
        }
    }
}

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
    PScaffold(topBar = { PTopBar("生成结果", onBack, actions = { PTextButton("›", onSettings) }) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SurfaceCard(dark, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Txt(st.audio?.title ?: "", style = MiuixTheme.textStyles.title3)
                        Summary(
                            String.format(
                                Locale.ROOT,
                                "%.1f BPM  ·  %s  ·  %d 个谱面",
                                st.bpm,
                                fmtTime(st.durationSec),
                                st.charts.size,
                            ),
                        )
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
                SurfaceCard(dark, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(top = 15.dp, bottom = 5.dp)) {
                        Txt(
                            "导出格式",
                            Modifier.padding(horizontal = 18.dp),
                            MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Segmented(
                            listOf(ChartFormat.RPE to "RPE", ChartFormat.OFFICIAL to "官方", ChartFormat.PEC to "PEC"),
                            selected = st.settings.format,
                            onSelect = onFormat,
                        )
                        Summary("导出的谱面包可直接在 Phira 中导入", Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                    }
                }
                PButton("全部导出到文件夹", onExportAll, Modifier.fillMaxWidth().height(52.dp), enabled = !st.busyExport)
                PButton("按当前设置重新生成", onRegenerate, Modifier.fillMaxWidth(), primary = false)
                if (st.busyExport) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Summary("正在打包")
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun ChartCard(
    index: Int,
    chart: Chart,
    st: UiState,
    dark: Boolean,
    onPreview: (Int) -> Unit,
    onExport: (Int) -> Unit,
    onShare: (Int) -> Unit,
) {
    val color = diffColor(chart.profile.difficulty)
    val report = st.reports[index]
    SurfaceCard(dark, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.13f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Txt(chart.levelText, color = color, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("${chart.noteCount} 个音符", style = MiuixTheme.textStyles.body1)
                    val nps = chart.noteCount / chart.durationSec.coerceAtLeast(1.0)
                    Summary(String.format(Locale.ROOT, "平均 %.1f 个每秒  ·  %d 条判定线", nps, chart.lineCount))
                }
            }
            val counts = NoteType.values().map { type -> type to chart.notes.count { it.type == type } }
                .filter { it.second > 0 }
            Summary(counts.joinToString("   ") { (type, count) -> "${typeName(type)} $count" })
            if (report != null) {
                if (report.ok) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Palette.ok)
                        Spacer(Modifier.width(7.dp))
                        Summary("兼容性验证通过" + if (report.warnings.isNotEmpty()) " (${report.warnings.size} 条提示)" else "")
                    }
                    report.warnings.take(2).forEach { Summary(it) }
                } else {
                    Banner("验证未通过: " + report.errors.take(2).joinToString("; "), Palette.bad)
                }
            }
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
