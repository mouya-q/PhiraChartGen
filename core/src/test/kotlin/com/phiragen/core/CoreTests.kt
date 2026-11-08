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
import com.phiragen.core.audio.Fft
import com.phiragen.core.audio.StreamResampler
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartGenerator
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
            assertTrue(c.notes.all { it.line in 0 until c.profile.lineCount })
            assertTrue(c.notes.all { it.x in c.profile.lanes })
        }
    }

    @Test
    fun holdsHavePositiveLengthAndLaneIsNotReused() {
        for (d in Difficulty.values()) {
            val c = gen(d)
            val holds = c.notes.filter { it.type == NoteType.HOLD }
            assertTrue(holds.all { it.endTime - it.time >= 0.4 })
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
    fun beatConversionRoundTrips() {
        val c = gen(Difficulty.HD)
        for (n in c.notes.take(50)) assertEquals(n.time, c.secOf(c.beatOf(n.time)), 1e-9)
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
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpReply {
            calls++
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
