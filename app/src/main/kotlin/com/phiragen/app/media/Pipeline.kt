package com.phiragen.app.media

import android.content.Context
import android.net.Uri
import com.phiragen.app.data.AppSettings
import com.phiragen.app.data.DiagLog
import com.phiragen.core.ai.AiClient
import com.phiragen.core.ai.AiEnhancer
import com.phiragen.core.ai.AiOutcome
import com.phiragen.core.audio.AnalysisCodec
import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.audio.AnalyzerFactory
import com.phiragen.core.audio.StreamResampler
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartGenerator
import com.phiragen.core.chart.ChartMeta
import com.phiragen.core.chart.DifficultyProfile
import com.phiragen.core.chart.GenerationOptions
import com.phiragen.core.chart.Guidance
import com.phiragen.core.export.PackAssets
import com.phiragen.core.export.PackBuilder
import com.phiragen.core.export.PackValidator
import com.phiragen.core.export.ValidationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class GenerationResult(val charts: List<Chart>, val ai: AiOutcome)

object Pipeline {
    private const val SESSION_BIN = "session.bin"

    /** 解码并分析, 全程在后台线程, 音频按块处理 */
    suspend fun analyze(context: Context, info: AudioInfo, useNative: Boolean = true, onProgress: (Float) -> Unit): AnalysisResult =
        withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            val spectrum = AnalyzerFactory.create(useNative)
            var resampler: StreamResampler? = null
            var channels = 2
            var mono = FloatArray(8192)
            val t0 = System.currentTimeMillis()
            PcmDecoder.decode(
                context, info.uri,
                onFormat = { f ->
                    channels = f.channels
                    resampler = StreamResampler(f.sampleRate, AnalysisConfig.SAMPLE_RATE) { buf, len -> spectrum.push(buf, len) }
                },
                onPcm = { pcm, count ->
                    val frames = count / channels
                    if (mono.size < frames) mono = FloatArray(frames)
                    for (i in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += pcm[i * channels + c]
                        mono[i] = s / (channels * 32768f)
                    }
                    resampler?.push(mono, frames)
                },
                onProgress = { onProgress(it) },
                isCancelled = { job?.isActive == false },
            )
            resampler?.flush()
            val result = spectrum.finish()
            DiagLog.i("analyze", "引擎=${spectrum.engine} 完成 耗时=${System.currentTimeMillis() - t0}ms 帧=${result.frameCount} bpm=${"%.1f".format(result.bpm)}")
            result
        }

    suspend fun enhance(settings: AppSettings, analysis: AnalysisResult): Pair<Guidance, AiOutcome> {
        if (!settings.ai.ready) return Guidance.NONE to AiOutcome.Disabled
        return withContext(Dispatchers.IO) { AiEnhancer.enhance(analysis, AiClient(settings.ai.toConfig())) }
    }

    suspend fun generate(analysis: AnalysisResult, settings: AppSettings, guidance: Guidance): List<Chart> =
        withContext(Dispatchers.Default) {
            val options = GenerationOptions(
                noteSpeed = settings.noteSpeed.toDouble(),
                snapToleranceSec = settings.snapToleranceMs / 1000.0,
                guidance = guidance,
            )
            settings.difficulties.sortedBy { it.ordinal }.map { ChartGenerator.generate(analysis, DifficultyProfile.of(it), options) }
        }

    // ---------- 会话缓存 ----------

    fun saveSession(context: Context, analysis: AnalysisResult, info: AudioInfo) {
        try {
            File(context.filesDir, SESSION_BIN).outputStream().use { AnalysisCodec.write(it, analysis) }
            context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("uri", info.uri.toString()).apply()
        } catch (e: Exception) {
            DiagLog.e("session", "保存会话失败", e)
        }
    }

    fun loadSession(context: Context): Pair<Uri, AnalysisResult>? = try {
        val uri = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("uri", null)
        val f = File(context.filesDir, SESSION_BIN)
        if (uri == null || !f.exists()) null else Uri.parse(uri) to f.inputStream().use { AnalysisCodec.read(it) }
    } catch (e: Exception) {
        DiagLog.e("session", "读取会话失败", e)
        null
    }

    fun clearSession(context: Context) {
        File(context.filesDir, SESSION_BIN).delete()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------- 导出 ----------

    /** 试听片段起点: 取响度最高的 15 秒窗口 */
    fun previewStart(a: AnalysisResult): Double {
        val win = (15 * a.fps).toInt()
        if (a.frameCount <= win) return 0.0
        var best = 0.0
        var bestStart = 0
        var sum = 0.0
        for (i in 0 until win) sum += a.rms[i]
        best = sum
        for (i in win until a.frameCount) {
            sum += a.rms[i] - a.rms[i - win]
            if (sum > best) { best = sum; bestStart = i - win + 1 }
        }
        return bestStart / a.fps
    }

    fun metaFor(info: AudioInfo, analysis: AnalysisResult, s: AppSettings) = ChartMeta(
        title = info.title,
        composer = info.artist.ifBlank { "未知" },
        charter = s.charter.ifBlank { "PhiraChartGen" },
        intro = "Generated by PhiraChartGen",
        previewStartSec = previewStart(analysis),
        offsetSec = s.offsetMs / 1000.0,
    )

    /** 音频已是 Phira 支持的格式则原样打包, 否则转为 WAV */
    fun prepareAssets(context: Context, info: AudioInfo, analysis: AnalysisResult, onProgress: (Float) -> Unit): PackAssets {
        val cover = CoverArt.render(info.title, info.cover, analysis.rms)
        val png = CoverArt.toPng(cover)
        if (info.extension in setOf("mp3", "ogg", "wav")) {
            return PackAssets("song.${info.extension}", { context.contentResolver.openInputStream(info.uri)!! }, png)
        }
        val dir = File(context.cacheDir, "transcode").apply { mkdirs() }
        val out = File(dir, "${info.uri.toString().hashCode().toUInt()}.wav")
        if (!out.exists() || out.length() < 1000) {
            var writer: WavWriter? = null
            val tmp = File(dir, out.name + ".part")
            try {
                PcmDecoder.decode(
                    context, info.uri,
                    onFormat = { f -> writer = WavWriter(tmp, f.sampleRate, f.channels) },
                    onPcm = { pcm, n -> writer?.write(pcm, n) },
                    onProgress = onProgress,
                    isCancelled = { false },
                )
                writer?.close()
                tmp.renameTo(out)
            } catch (e: Exception) {
                try { writer?.close() } catch (_: Exception) {}
                tmp.delete()
                throw e
            }
        }
        return PackAssets("song.wav", { out.inputStream() }, png)
    }

    fun writePack(context: Context, target: Uri, chart: Chart, meta: ChartMeta, format: ChartFormat, assets: PackAssets) {
        val os = context.contentResolver.openOutputStream(target, "wt") ?: throw AudioException("无法写入目标位置")
        os.buffered().use { PackBuilder.write(it, chart, meta, format, assets) }
    }

    fun validate(context: Context, uri: Uri): ValidationReport =
        context.contentResolver.openInputStream(uri)?.use { PackValidator.validate(it) }
            ?: ValidationReport(listOf("无法读取导出的文件"), emptyList())
}
