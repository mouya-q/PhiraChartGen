package com.phiragen.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.phiragen.app.UiState
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.NoteType
import com.phiragen.core.export.LineMotion
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 与导出共用 LineMotion, 预览看到的判定线运动即导出结果 */
private fun evalSegs(segs: List<LineMotion.Seg>, beat: Double, initial: Double): Double {
    if (segs.isEmpty()) return initial
    if (beat <= segs.first().start) return segs.first().from
    for (s in segs) {
        if (beat < s.end) {
            val u = ((beat - s.start) / (s.end - s.start)).coerceIn(0.0, 1.0)
            return s.from + (s.to - s.from) * (0.5 - cos(PI * u) / 2)
        }
    }
    return segs.last().to
}

@Composable
fun PreviewScreen(st: UiState, index: Int, dark: Boolean, onBack: () -> Unit) {
    val chart = st.charts.getOrNull(index)
    val audio = st.audio
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableDoubleStateOf(0.0) }
    val total = max(1.0, (audio?.durationMs ?: 0L) / 1000.0)

    DisposableEffect(audio?.uri) {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, audio!!.uri)
            mp.prepare()
            player = mp
        } catch (e: Exception) {
            failed = true
        }
        onDispose {
            try { mp.release() } catch (_: Exception) {}
            player = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
    }
    LaunchedEffect(playing, player) {
        val mp = player ?: return@LaunchedEffect
        if (playing) {
            mp.start()
            while (playing) {
                withFrameNanos { }
                pos = mp.currentPosition / 1000.0
                if (!mp.isPlaying) { playing = false }
            }
        } else if (mp.isPlaying) mp.pause()
    }

    PScaffold(topBar = { PTopBar("谱面预览", onBack) }) { pad ->
        if (chart == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Summary("谱面不存在") }
            return@PScaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(chart.levelText, Modifier.weight(1f), MiuixTheme.textStyles.title3, diffColor(chart.profile.difficulty))
                Summary("${chart.notes.count { it.time <= pos }} / ${chart.noteCount}")
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B0F1A))) {
                Canvas(Modifier.fillMaxSize()) { drawChart(chart, pos) }
            }
            if (failed) Banner("无法播放该音频, 仍可拖动进度查看谱面", Palette.warn)
            Slider(
                value = pos.toFloat().coerceIn(0f, total.toFloat()),
                onValueChange = {
                    pos = it.toDouble()
                    player?.seekTo((it * 1000).toInt())
                },
                valueRange = 0f..total.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Summary(fmtTime(pos)); Summary(fmtTime(total))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PButton(if (playing) "暂停" else "播放", { if (player != null) playing = !playing }, Modifier.weight(1f), enabled = player != null)
                PButton("回到开头", { pos = 0.0; player?.seekTo(0) }, Modifier.weight(1f), primary = false)
            }
            Summary("预览按谱面的实际时间与判定线运动绘制, 不含打击音效")
        }
    }
}

private fun DrawScope.drawChart(chart: Chart, t: Double) {
    val scale = size.width / 1350f
    val cx = size.width / 2
    val cy = size.height / 2
    val unitsPerSec = chart.noteSpeed.toFloat() * 120f
    val beat = chart.beatOf(t)
    for (li in 0 until chart.profile.lineCount) {
        val m = LineMotion(chart, li)
        val y = evalSegs(m.bob, beat, m.baseY).toFloat()
        val theta = evalSegs(m.rotation, beat, 0.0)
        val rad = theta * PI / 180.0
        val d = Offset(cos(rad).toFloat(), -sin(rad).toFloat())
        val u = Offset(-sin(rad).toFloat(), -cos(rad).toFloat())
        val p = Offset(cx, cy - y * scale)
        val half = size.width * 0.5f
        drawLine(Color(0xCCFFF59D), p - d * half, p + d * half, strokeWidth = 3f)
        for (n in chart.notes) {
            if (n.line != li) continue
            val end = if (n.type == NoteType.HOLD) n.endTime else n.time
            if (end < t - 0.3) continue
            val dist = ((n.time - t).toFloat() * unitsPerSec * scale).coerceAtLeast(if (n.type == NoteType.HOLD && t < end) 0f else -1e9f)
            if (dist > size.height * 1.2f) break
            if (n.time < t && n.type != NoteType.HOLD) {
                val a = ((0.25 - (t - n.time)) / 0.25).toFloat().coerceIn(0f, 1f)
                val c = p + d * (n.x.toFloat() * scale)
                drawCircle(Color(0xFFFFFFFF).copy(alpha = a * 0.8f), radius = (1f - a) * 36f * scale * 3 + 8f, center = c, style = Stroke(2f))
                continue
            }
            val head = p + d * (n.x.toFloat() * scale) + u * dist
            val w = 150f * scale
            val h = 20f * scale
            val color = when (n.type) {
                NoteType.TAP -> Color(0xFF4FC3F7)
                NoteType.HOLD -> Color(0xFF9CCC65)
                NoteType.FLICK -> Color(0xFFEF5350)
                NoteType.DRAG -> Color(0xFFFFEE58)
            }
            if (n.type == NoteType.HOLD) {
                val tailDist = ((n.endTime - t).toFloat() * unitsPerSec * scale)
                val tail = p + d * (n.x.toFloat() * scale) + u * tailDist
                drawLine(color.copy(alpha = 0.55f), head, tail, strokeWidth = w * 0.7f)
            }
            rotate(degrees = (-theta).toFloat(), pivot = head) {
                drawRoundRect(color, Offset(head.x - w / 2, head.y - h / 2), Size(w, h), CornerRadius(h / 2))
            }
        }
    }
}
