package com.phiragen.core

import com.phiragen.core.ai.AiClient
import com.phiragen.core.ai.AiConfig
import com.phiragen.core.ai.AiEnhancer
import com.phiragen.core.ai.AiErrorKind
import com.phiragen.core.ai.AiException
import com.phiragen.core.ai.AiOutcome
import com.phiragen.core.ai.HttpReply
import com.phiragen.core.ai.HttpTransport
import com.phiragen.core.audio.AnalysisCodec
import com.phiragen.core.audio.AnalyzerFactory
import com.phiragen.core.audio.NativeDsp
import com.phiragen.core.audio.Fft
import com.phiragen.core.audio.StreamResampler
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartGenerator
import com.phiragen.core.chart.GenerationOptions
import com.phiragen.core.chart.LaneModel
import com.phiragen.core.chart.ChartMeta
import com.phiragen.core.chart.Difficulty
import com.phiragen.core.chart.DifficultyProfile
import com.phiragen.core.chart.NoteType
import com.phiragen.core.export.PackAssets
import com.phiragen.core.export.PackBuilder
import com.phiragen.core.export.PackValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {
    @Test
    fun sineEnergyLandsInExpectedBin() {
        val n = 1024
        val fft = Fft(n)
        val re = DoubleArray(n) { sin(2 * PI * 50 * it / n) }
        val im = DoubleArray(n)
        fft.transform(re, im)
        var best = 0
        var bestMag = 0.0
        for (k in 1 until n / 2) {
            val m = Math.hypot(re[k], im[k])
            if (m > bestMag) { bestMag = m; best = k }
        }
        assertEquals(50, best)
    }

    @Test
    fun resamplerHalvesLength() {
        val out = ArrayList<Float>()
        val r = StreamResampler(44100, 22050) { buf, len -> for (i in 0 until len) out.add(buf[i]) }
        r.push(FloatArray(44100) { 1f }, 44100)
        r.flush()
        assertTrue(abs(out.size - 22050) <= 2)
        assertTrue(out.all { abs(it - 1f) < 1e-4f })
    }
}

class AnalysisTest {
    private val synth = TestAudio.synth()
    private val analysis = TestAudio.analyze(synth)

    @Test
    fun tempoIsRecovered() {
        assertTrue(abs(analysis.bpm - synth.bpm) < 0.6, "bpm=${analysis.bpm}")
    }

    @Test
    fun tempoIsRecoveredAtOtherSpeeds() {
        for (bpm in listOf(110.0, 150.0, 174.0)) {
            val a = TestAudio.analyze(TestAudio.synth(bpm = bpm, phase = 0.2, durationSec = 50.0))
            assertTrue(abs(a.bpm - bpm) < 0.8, "expected $bpm got ${a.bpm}")
        }
    }

    @Test
    fun beatPhaseIsConsistentWithHalfBeatGrid() {
        val half = analysis.beatSec / 2
        var d = (analysis.phase - synth.phase) % half
        if (d < 0) d += half
        val err = minOf(d, half - d)
        assertTrue(err < 0.03, "phase error=$err")
    }

    @Test
    fun cacheRoundTrip() {
        val bos = ByteArrayOutputStream()
        AnalysisCodec.write(bos, analysis)
        val back = AnalysisCodec.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(analysis.bpm, back.bpm)
        assertEquals(analysis.frameCount, back.frameCount)
        assertTrue(analysis.env.contentEquals(back.env))
    }
}

class NativeParityTest {
    /** 已构建 Rust 库时, 对同一段音频比较原生与 Kotlin 两种实现; 库不存在则跳过 */
    @Test
    fun nativeEngineMatchesKotlinEngine() {
        if (!NativeDsp.isAvailable()) return
        val synth = TestAudio.synth(bpm = 128.0, phase = 0.37, durationSec = 30.0)
        val kotlinResult = TestAudio.analyze(synth)
        val nativeStream = AnalyzerFactory.create(preferNative = true)
        assertEquals("native", nativeStream.engine)
        var off = 0
        while (off < synth.samples.size) {
            val len = minOf(4096, synth.samples.size - off)
            nativeStream.push(synth.samples.copyOfRange(off, off + len), len)
            off += len
        }
        val nativeResult = nativeStream.finish()
        assertEquals(kotlinResult.frameCount, nativeResult.frameCount)
        assertTrue(abs(kotlinResult.bpm - nativeResult.bpm) < 0.2, "bpm ${kotlinResult.bpm} vs ${nativeResult.bpm}")
        val half = kotlinResult.beatSec / 2
        var d = (kotlinResult.phase - nativeResult.phase) % half
        if (d < 0) d += half
        assertTrue(minOf(d, half - d) < 0.01, "phase ${kotlinResult.phase} vs ${nativeResult.phase}")
        var maxDiff = 0f
        for (i in 0 until kotlinResult.frameCount) maxDiff = maxOf(maxDiff, abs(kotlinResult.env[i] - nativeResult.env[i]))
        assertTrue(maxDiff < 1e-3f, "env max diff $maxDiff")
        assertEquals(kotlinResult.lowMag.size, nativeResult.lowMag.size)
    }

    @Test
    fun factoryFallsBackToKotlinWhenNativeIsNotRequested() {
        assertEquals("kotlin", AnalyzerFactory.create(preferNative = false).engine)
    }
}

class LaneModelTest {
    @Test
    fun probabilitiesMatchTheTrainingScriptOutput() {
        // 数值来自 Python 对同一参数的计算, 用于确认权重与归一化被正确移植
        val f1 = doubleArrayOf(0.4963, 0.2011, 0.5094, 0.2895, 0.8697, 0.0, 0.6488, 0.8278, 0.1971, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val e1 = doubleArrayOf(0.174881, 0.251549, 0.18495, 0.200719, 0.187901)
        val p1 = LaneModel.probs(f1)
        for (i in e1.indices) assertEquals(e1[i], p1[i], 2e-3)
        val f2 = doubleArrayOf(0.3482, 0.4233, 0.2766, 0.3001, 0.9029, 0.0, 0.7014, 0.9255, 0.385, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val e2 = doubleArrayOf(0.185717, 0.23235, 0.13878, 0.248493, 0.194659)
        val p2 = LaneModel.probs(f2)
        for (i in e2.indices) assertEquals(e2[i], p2[i], 2e-3)
    }

    @Test
    fun modelIsLeftRightSymmetric() {
        val f = doubleArrayOf(0.5, 0.3, 0.4, 0.3, 0.7, 0.2, 0.5, 0.9, 0.4, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val mirrored = f.copyOf()
        for (k in 0 until 5) { mirrored[9 + k] = f[9 + 4 - k]; mirrored[14 + k] = f[14 + 4 - k] }
        val a = LaneModel.probs(f)
        val b = LaneModel.probs(mirrored)
        for (k in 0 until 5) assertEquals(a[k], b[4 - k], 5e-3)
    }

    @Test
    fun classMapKeepsProbabilityMass() {
        for (lanes in listOf(DifficultyProfile.EZ.lanes, DifficultyProfile.HD.lanes, DifficultyProfile.IN.lanes)) {
            val m = LaneModel.classMap(lanes)
            for (row in m) assertEquals(1.0, row.sum(), 1e-9)
        }
    }
}

class GeneratorTest {
    private val analysis = TestAudio.analyze(TestAudio.synth())

    private fun gen(d: Difficulty) = ChartGenerator.generate(analysis, DifficultyProfile.of(d))

    @Test
    fun difficultiesAreLayeredByDensity() {
        val counts = Difficulty.values().map { gen(it).noteCount }
        for (i in 1 until counts.size) assertTrue(counts[i] >= counts[i - 1], "counts=$counts")
        assertTrue(counts.first() < counts.last())
    }

    @Test
    fun notesAreOrderedAndInsideTheSong() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            assertTrue(c.notes.isNotEmpty())
            assertTrue(c.notes.zipWithNext().all { (a, b) -> a.time <= b.time })
            assertTrue(c.notes.all { it.time >= 1.0 && it.time <= analysis.durationSec })
            assertTrue(c.notes.all { it.line in 0 until c.lineCount })
            assertTrue(c.notes.all { abs(it.x) <= 640.0 })
        }
    }

    @Test
    fun holdsHavePositiveLengthAndLaneIsNotReused() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            val holds = c.notes.filter { it.type == NoteType.HOLD }
            assertTrue(holds.all { it.endTime - it.time >= 0.25 - 1e-9 })
            for (h in holds) {
                val clash = c.notes.any { it !== h && it.x == h.x && it.line == h.line && it.time > h.time && it.time < h.endTime }
                assertTrue(!clash, "note inside hold lane")
            }
        }
    }

    @Test
    fun easyChartHasNoFlickOrDragAndNoChords() {
        val c = gen(Difficulty.EZ)
        assertTrue(c.notes.none { it.type == NoteType.FLICK || it.type == NoteType.DRAG })
        assertEquals(c.notes.size, c.notes.map { it.time }.toSet().size)
    }

    @Test
    fun lanesAreUsedEvenlyLeftToRight() {
        for (d in listOf(Difficulty.HD, Difficulty.IN, Difficulty.AT)) {
            val c = gen(d)
            val xs = c.notes.filter { it.line == 0 }.map { it.x }
            val left = xs.count { it < -1.0 }
            val right = xs.count { it > 1.0 }
            val ratio = left.toDouble() / right.coerceAtLeast(1)
            assertTrue(ratio in 0.7..1.43, "$d left=$left right=$right")
        }
    }

    @Test
    fun beatConversionRoundTrips() {
        val c = gen(Difficulty.HD)
        for (n in c.notes.take(50)) assertEquals(n.time, c.secOf(c.beatOf(n.time)), 1e-9)
    }

    @Test
    fun channelsAreContiguousAndCoverTheWholeSong() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            val end = c.choreography.endBeat
            for (t in c.choreography.tracks) {
                for (ch in listOf(t.x, t.y, t.rotation, t.alpha)) {
                    assertTrue(ch.segs.isNotEmpty())
                    assertEquals(0.0, ch.segs.first().start, 1e-9)
                    assertEquals(end, ch.segs.last().end, 1e-9)
                    assertTrue(ch.segs.zipWithNext().all { (a, b) -> abs(a.end - b.start) < 1e-9 && a.end > a.start })
                    // 数值也必须连续, 否则在三种格式里都会出现瞬移
                    assertTrue(ch.segs.zipWithNext().all { (a, b) -> abs(a.to - b.from) < 1e-6 })
                }
            }
        }
    }

    @Test
    fun notesStayOnScreenWhenTheyReachTheirLine() {
        for (d in Difficulty.values()) {
            for (seed in 1L..4L) {
                val c = ChartGenerator.generate(analysis, DifficultyProfile.of(d), GenerationOptions(seed = seed))
                for (n in c.notes) {
                    val b = c.beatOf(n.time)
                    val t = c.choreography.tracks[n.line]
                    val th = Math.toRadians(t.rotation.at(b))
                    val sx = t.x.at(b) + n.x * Math.cos(th)
                    val sy = t.y.at(b) + n.x * Math.sin(th)
                    assertTrue(abs(sx) <= 700.0 && abs(sy) <= 460.0, "$d seed=$seed sx=$sx sy=$sy")
                }
            }
        }
    }

    @Test
    fun noNoteIsPlacedOnAHiddenLine() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            for (n in c.notes) {
                val b = c.beatOf(n.time)
                assertTrue(n.line in c.choreography.phraseAt(b).active)
            }
        }
    }

    @Test
    fun notesOnlyRideVisibleNoteCarryingLines() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            for (n in c.notes) {
                val t = c.choreography.tracks[n.line]
                assertTrue(t.carriesNotes, "$d note on a decoration line")
                assertTrue(t.alpha.at(c.beatOf(n.time)) >= 200.0, "$d note on a faded line")
            }
        }
    }

    @Test
    fun decorationLinesAppearOnlyOnHigherDifficulties() {
        assertEquals(0, gen(Difficulty.EZ).choreography.tracks.count { !it.carriesNotes })
        assertTrue(gen(Difficulty.HD).choreography.tracks.any { !it.carriesNotes })
        assertTrue(gen(Difficulty.AT).choreography.tracks.count { !it.carriesNotes } >= 2)
    }

    @Test
    fun mainLineCarriesMostNotes() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            val onMain = c.notes.count { it.line == 0 }
            assertTrue(onMain >= c.notes.size * 0.8, "$d main=$onMain total=${c.notes.size}")
        }
    }

    @Test
    fun higherDifficultiesUseMoreLineMovement() {
        val ez = gen(Difficulty.EZ).choreography
        assertEquals(1, ez.lineCount)
        assertTrue(ez.phrases.all { it.active == listOf(0) })
    }

    @Test
    fun guidanceStyleAndEffectCuesAreHonored() {
        val g = com.phiragen.core.chart.Guidance(
            emptyList(),
            mapOf(
                0 to com.phiragen.core.chart.PhraseCue(effects = emptySet()),
                1 to com.phiragen.core.chart.PhraseCue(style = com.phiragen.core.chart.PhraseStyle.SPIN),
            ),
        )
        val c = ChartGenerator.generate(analysis, DifficultyProfile.AT, GenerationOptions(guidance = g))
        assertEquals(com.phiragen.core.chart.PhraseStyle.SPIN, c.choreography.phrases[1].style)
        // 第一个乐句指定了空特效列表, 因此不应再自动出现扫线
        val firstBeat = c.choreography.phrases[0].startBeat
        val decorInFirst = c.choreography.tracks.filter { !it.carriesNotes }
            .count { t -> t.alpha.segs.any { it.start >= firstBeat - 0.1 && it.start < firstBeat + 8 && it.to > 1.0 } }
        assertEquals(0, decorInFirst)
    }

    @Test
    fun cuesCannotForceBigMovesOnEasyCharts() {
        val g = com.phiragen.core.chart.Guidance(
            emptyList(),
            mapOf(1 to com.phiragen.core.chart.PhraseCue(style = com.phiragen.core.chart.PhraseStyle.SPLIT)),
        )
        val c = ChartGenerator.generate(analysis, DifficultyProfile.HD, GenerationOptions(guidance = g))
        assertTrue(c.choreography.phrases.none { it.style == com.phiragen.core.chart.PhraseStyle.SPLIT || it.style == com.phiragen.core.chart.PhraseStyle.SPIN })
    }

    @Test
    fun silenceIsRejectedWithClearMessage() {
        val silent = TestAudio.Synth(FloatArray(TestAudio.SR * 20), 120.0, 0.0)
        val a = TestAudio.analyze(silent)
        val ex = assertFailsWith<com.phiragen.core.audio.AnalysisException> { ChartGenerator.generate(a, DifficultyProfile.EZ) }
        assertTrue(ex.message!!.isNotEmpty())
    }
}

class ExportTest {
    private val analysis = TestAudio.analyze(TestAudio.synth())
    private val png = byteArrayOf(1, 2, 3)
    private val meta = ChartMeta(title = "Test \"Song\"", composer = "Tester")

    @Test
    fun everyFormatAndDifficultyPassesValidator() {
        for (d in Difficulty.values()) {
            val chart = ChartGenerator.generate(analysis, DifficultyProfile.of(d))
            for (f in ChartFormat.values()) {
                val bos = ByteArrayOutputStream()
                val assets = PackAssets("song.wav", { ByteArrayInputStream(ByteArray(100)) }, png)
                PackBuilder.write(bos, chart, meta, f, assets)
                val report = PackValidator.validate(ByteArrayInputStream(bos.toByteArray()))
                assertTrue(report.ok, "$d $f: ${report.errors}")
            }
        }
    }

    @Test
    fun validatorRejectsMissingMusicFile() {
        val chart = ChartGenerator.generate(analysis, DifficultyProfile.EZ)
        val bos = ByteArrayOutputStream()
        PackBuilder.write(bos, chart, meta, ChartFormat.RPE, PackAssets("song.ogg", { ByteArrayInputStream(ByteArray(10)) }, png))
        // 去掉音频条目后重新打包
        val src = java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray()))
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            while (true) {
                val e = src.nextEntry ?: break
                if (e.name == "song.ogg") continue
                z.putNextEntry(java.util.zip.ZipEntry(e.name)); z.write(src.readBytes()); z.closeEntry()
            }
        }
        val report = PackValidator.validate(ByteArrayInputStream(out.toByteArray()))
        assertTrue(!report.ok)
    }

    @Test
    fun safeFileNameStripsSeparators() {
        assertEquals("a_b_c-EZ.zip", PackBuilder.safeFileName("a/b\\c", "EZ"))
    }
}

class AiTest {
    private val analysis = TestAudio.analyze(TestAudio.synth())
    private val cfg = AiConfig("https://example.test/v1", "k", "m", 5000, 2)

    private class Fake(val replies: MutableList<() -> HttpReply>) : HttpTransport {
        var calls = 0
        val urls = ArrayList<String>()
        val bodies = ArrayList<String>()
        var lastHeaders: Map<String, String> = emptyMap()
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpReply {
            calls++
            urls.add(url); bodies.add(body); lastHeaders = headers
            return replies.removeAt(0)()
        }
    }

    private fun ok(content: String) = HttpReply(200, """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}""")

    @Test
    fun appliesGuidanceWhenModelAnswersWell() {
        val t = Fake(mutableListOf({ ok("""好的 {"windows":[{"start":0,"end":10,"density":0.5},{"start":10,"end":20,"density":9}]}""") }))
        val (g, outcome) = AiEnhancer.enhance(analysis, AiClient(cfg, t) {})
        assertTrue(outcome is AiOutcome.Applied)
        assertEquals(1.6, g.densityAt(15.0), 1e-9)
        assertEquals(0.5, g.densityAt(5.0), 1e-9)
    }

    @Test
    fun fallsBackOnMalformedAnswer() {
        val t = Fake(mutableListOf({ ok("无法完成") }))
        val (g, outcome) = AiEnhancer.enhance(analysis, AiClient(cfg, t) {})
        assertTrue(outcome is AiOutcome.FellBack)
        assertEquals(1.0, g.densityAt(5.0))
    }

    @Test
    fun retriesTimeoutsThenSucceeds() {
        val t = Fake(mutableListOf({ throw SocketTimeoutException() }, { throw IOException() }, { ok("ok") }))
        assertEquals("ok", AiClient(cfg, t) {}.chat("s", "u"))
        assertEquals(3, t.calls)
    }

    @Test
    fun doesNotRetryAuthErrors() {
        val t = Fake(mutableListOf({ HttpReply(401, "") }, { ok("ok") }))
        val ex = assertFailsWith<AiException> { AiClient(cfg, t) {}.chat("s", "u") }
        assertEquals(AiErrorKind.AUTH, ex.kind)
        assertEquals(1, t.calls)
    }

    @Test
    fun givesUpAfterConfiguredRetries() {
        val t = Fake(mutableListOf({ HttpReply(503, "") }, { HttpReply(503, "") }, { HttpReply(503, "") }, { ok("late") }))
        assertFailsWith<AiException> { AiClient(cfg, t) {}.chat("s", "u") }
        assertEquals(3, t.calls)
    }

    @Test
    fun parsesFencedJsonAfterProseWithStrayBraces() {
        val reply = "好的, 我的方案 {仅供参考} 如下:\n```json\n" +
            "{\"phrases\":[{\"i\":1,\"style\":\"spin\",\"density\":1.4,\"effects\":[\"WHOOSH\",\"ECHO\",\"SWEEPERS\"]}]}\n```\n希望有帮助"
        val g = AiEnhancer.parseGuidance(reply, analysis)
        val cue = g.phrases[1]!!
        assertEquals(com.phiragen.core.chart.PhraseStyle.SPIN, cue.style)
        assertEquals(1.4, cue.density!!, 1e-9)
        assertEquals(2, cue.effects!!.size)
    }

    @Test
    fun ignoresThinkingBlocksAndOutOfRangePhrases() {
        val reply = "<think>先想想 {\"phrases\": 1}</think>{\"phrases\":[{\"i\":999,\"density\":1.2},{\"i\":2,\"density\":9}]}"
        val g = AiEnhancer.parseGuidance(reply, analysis)
        assertEquals(setOf(2), g.phrases.keys)
        assertEquals(1.5, g.phrases[2]!!.density!!, 1e-9)
    }

    @Test
    fun parseFailureShowsTheStartOfTheModelReply() {
        val ex = assertFailsWith<AiException> { AiEnhancer.parseGuidance("抱歉, 我无法完成这个任务", analysis) }
        assertTrue(ex.message!!.contains("抱歉"))
    }

    @Test
    fun dropsJsonModeWhenServerRejectsIt() {
        val err = HttpReply(400, """{"error":{"message":"response_format is not supported by this model"}}""")
        val t = Fake(mutableListOf({ err }, { ok("{}") }))
        AiClient(cfg, t) {}.chat("s", "u", jsonMode = true)
        assertTrue(t.bodies[0].contains("response_format") && !t.bodies[1].contains("response_format"))
    }

    @Test
    fun enhanceUsesRichSchemaEndToEnd() {
        val t = Fake(mutableListOf({ ok("""{"phrases":[{"i":0,"style":"STEADY","density":0.5,"effects":["SWEEPERS"]},{"i":3,"style":"PULSE"}]}""") }))
        val (g, outcome) = AiEnhancer.enhance(analysis, AiClient(cfg, t) {})
        assertTrue(outcome is AiOutcome.Applied)
        assertEquals(1, (outcome as AiOutcome.Applied).effects)
        assertTrue(t.bodies[0].contains("response_format"))
        assertTrue(g.densityAt(0.5) < 1.0)
    }

    @Test
    fun sendsUserAgentAndBearerKey() {
        val t = Fake(mutableListOf({ ok("ok") }))
        AiClient(cfg, t) {}.chat("s", "u")
        assertTrue(t.lastHeaders["User-Agent"]!!.startsWith("PhiraChartGen"))
        assertEquals("Bearer k", t.lastHeaders["Authorization"])
        assertEquals("https://example.test/v1/chat/completions", t.urls[0])
    }

    @Test
    fun acceptsPastedFullEndpointAndTrailingSlash() {
        val t = Fake(mutableListOf({ ok("ok") }, { ok("ok") }))
        AiClient(cfg.copy(baseUrl = "https://example.test/v1/chat/completions"), t) {}.chat("s", "u")
        AiClient(cfg.copy(baseUrl = " https://example.test/v1/ "), t) {}.chat("s", "u")
        assertEquals(listOf("https://example.test/v1/chat/completions", "https://example.test/v1/chat/completions"), t.urls)
    }

    @Test
    fun switchesToMaxCompletionTokensWhenServerRequiresIt() {
        val err = HttpReply(400, """{"error":{"message":"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."}}""")
        val t = Fake(mutableListOf({ err }, { ok("ok") }))
        assertEquals("ok", AiClient(cfg, t) {}.chat("s", "u"))
        assertTrue(t.bodies[0].contains("max_tokens") && !t.bodies[0].contains("max_completion_tokens"))
        assertTrue(t.bodies[1].contains("max_completion_tokens"))
    }

    @Test
    fun dropsTemperatureWhenServerRejectsIt() {
        val err = HttpReply(400, """{"error":{"message":"Unsupported value: 'temperature' does not support 0.3 with this model."}}""")
        val t = Fake(mutableListOf({ err }, { ok("ok") }))
        assertEquals("ok", AiClient(cfg, t) {}.chat("s", "u"))
        assertTrue(t.bodies[0].contains("temperature") && !t.bodies[1].contains("temperature"))
    }

    @Test
    fun addsV1WhenHostOnlyAndServerReturns404() {
        val t = Fake(mutableListOf({ HttpReply(404, "") }, { ok("ok") }))
        assertEquals("ok", AiClient(cfg.copy(baseUrl = "https://api.example.test"), t) {}.chat("s", "u"))
        assertEquals("https://api.example.test/v1/chat/completions", t.urls[1])
    }

    @Test
    fun connectionTestAcceptsEmptyContentFromReasoningModels() {
        val t = Fake(mutableListOf({ ok("") }))
        assertEquals(null, AiClient(cfg, t) {}.test())
    }

    @Test
    fun errorMessagesCarryServerDetailButNeverTheKey() {
        val t = Fake(mutableListOf({ HttpReply(403, """{"error":{"message":"region blocked for key sk-secret-12345"}}""") }))
        val ex = assertFailsWith<AiException> { AiClient(cfg.copy(apiKey = "sk-secret-12345"), t) {}.chat("s", "u") }
        assertTrue(ex.message!!.contains("region blocked"))
        assertTrue(!ex.message!!.contains("sk-secret-12345"))
    }

    @Test
    fun plainHttpOnlyAllowedForLocalHosts() {
        assertEquals(null, AiConfig("http://192.168.1.20:8000/v1", "k", "m").validate())
        assertEquals(null, AiConfig("http://localhost:11434/v1", "k", "m").validate())
        assertTrue(AiConfig("http://api.example.com/v1", "k", "m").validate() != null)
    }

    @Test
    fun configValidation() {
        assertTrue(AiConfig("ftp://x", "k", "m").validate() != null)
        assertTrue(AiConfig("https://a.b/v1", "", "m").validate() != null)
        assertEquals(null, cfg.validate())
    }

    @Test
    fun summaryContainsNoFileOrPersonalData() {
        val s = AiEnhancer.buildSummary(analysis)
        assertTrue(s.contains("bpm") && s.contains("segments"))
        assertTrue(!s.contains("/") && !s.contains("uri", ignoreCase = true))
    }
}
