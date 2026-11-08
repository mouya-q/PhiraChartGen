package com.phiragen.core.chart

import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisException
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.audio.PeakPicker
import com.phiragen.core.audio.Stats
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Random

/** 一个起音候选及其听感特征 */
data class Onset(
    val time: Double,
    val strength: Double,
    /** 高频占比 */
    val highShare: Double,
    /** 低频占比 */
    val lowShare: Double,
    /** 频谱质心归一化到 0..1, 越高越亮 */
    val brightness: Double,
    /** 新出现的持续音长度 (秒) */
    val sustainSec: Double,
    val sustainBin: Int,
    val frame: Int,
)

/** AI 或用户给出的分段编排倾向, 只做倍率修正, 不改变音符的时间来源 */
data class GuidanceWindow(val startSec: Double, val endSec: Double, val density: Double)

class Guidance(val windows: List<GuidanceWindow>) {
    fun densityAt(sec: Double): Double {
        for (w in windows) if (sec >= w.startSec && sec < w.endSec) return w.density.coerceIn(0.3, 1.6)
        return 1.0
    }

    companion object {
        val NONE = Guidance(emptyList())
    }
}

data class GenerationOptions(
    val seed: Long = 7L,
    val noteSpeed: Double = DEFAULT_NOTE_SPEED,
    /** 软吸附容差 (秒), 为 0 表示不吸附 */
    val snapToleranceSec: Double = 0.035,
    val guidance: Guidance = Guidance.NONE,
)

object ChartGenerator {

    fun generate(a: AnalysisResult, profile: DifficultyProfile, options: GenerationOptions = GenerationOptions()): Chart {
        val beat = a.beatSec
        var phi = a.phase % beat
        if (phi < 0.02) phi += beat
        val dur = a.durationSec

        val onsets = extractOnsets(a, profile.peakThreshold)
        val chosen = select(onsets, a, profile, phi, beat, dur, options)
        if (chosen.isEmpty()) throw AnalysisException("没有检测到足够的节奏起伏, 请换一段节奏更明显的音频")
        val build = buildNotes(chosen, a, profile, phi, beat, dur, options)
        val (value, level) = estimateLevel(build, dur)
        return Chart(profile, build, a.bpm, phi, dur, value, level, options.noteSpeed)
    }

    // ---------- 候选提取 ----------

    fun extractOnsets(a: AnalysisResult, threshold: Double): List<Onset> {
        val peaks = PeakPicker.pick(a.env, 0.045, threshold)
        val n = a.frameCount
        val keep = AnalysisConfig.KEEP_BINS
        val out = ArrayList<Onset>(peaks.size)
        for (i in peaks) {
            val t = PeakPicker.peakTimeSec(a.env, i)
            val l = a.low[i].toDouble()
            val m = a.mid[i].toDouble()
            val h = a.high[i].toDouble()
            val tot = l + m + h + 1e-9
            val j = min(n - 1, i + 2)
            val cen = a.centroidHz[j].toDouble()
            val bright = ((log2(max(cen, 60.0)) - log2(100.0)) / (log2(5000.0) - log2(100.0))).coerceIn(0.0, 1.0)

            val i4 = min(n - 1, i + 4)
            val ib = max(0, i - 3)
            val rowBase = i4 * keep
            val bins = ArrayList<Int>()
            for (b in 3 until 188) {
                val v = a.lowMag[rowBase + b]
                if (v >= a.lowMag[rowBase + b - 1] && v >= a.lowMag[rowBase + b + 1]) bins.add(b)
            }
            bins.sortByDescending { a.lowMag[rowBase + it] }
            var sus = 0.0
            var sb = -1
            for (b in bins.take(4)) {
                val v = a.lowMag[rowBase + b]
                val before = maxOf3(a.lowMag, ib * keep + b - 1, ib * keep + b, ib * keep + b + 1)
                if (v / (before + 1e-6f) < 1.8f) continue
                var f = i4
                while (f < n - 1 && (f - i4) / AnalysisConfig.FPS < 4.0 &&
                    maxOf3(a.lowMag, f * keep + b - 1, f * keep + b, f * keep + b + 1) >= 0.5f * v
                ) f++
                val len = (f - i4) / AnalysisConfig.FPS
                if (len > sus) {
                    sus = len
                    sb = b
                }
            }
            out.add(Onset(t, a.env[i].toDouble(), h / tot, l / tot, bright, sus, sb, i))
        }
        return out
    }

    private fun maxOf3(arr: FloatArray, i: Int, j: Int, k: Int): Float = max(arr[i], max(arr[j], arr[k]))
    private fun log2(x: Double): Double = Math.log(x) / Math.log(2.0)

    // ---------- 网格与吸附 ----------

    fun snap(t: Double, phi: Double, beat: Double, division: Int, tol: Double): Double {
        if (tol <= 0.0) return t
        val step = beat / division
        val k = Math.round((t - phi) / step)
        val ts = phi + k * step
        return if (abs(ts - t) <= tol) ts else t
    }

    // ---------- 选取 ----------

    private fun select(
        cands: List<Onset>, a: AnalysisResult, p: DifficultyProfile, phi: Double, beat: Double,
        dur: Double, o: GenerationOptions,
    ): List<Onset> {
        val rms = a.rms
        val peak = rms.maxOrNull() ?: 0f
        val activeVals = rms.filter { it > 0.05f * peak }.toFloatArray()
        val active = if (activeVals.isNotEmpty()) Stats.percentile(activeVals, 50.0) else 1.0

        val snapped = cands.map { it.copy(time = snap(it.time, phi, beat, p.division, o.snapToleranceSec)) }
            .sortedBy { it.time }
        val dd = ArrayList<Onset>()
        for (c in snapped) {
            if (dd.isNotEmpty() && c.time - dd.last().time < 0.03) {
                if (c.strength > dd.last().strength) dd[dd.size - 1] = c
            } else dd.add(c)
        }

        val window = 3.0
        val chosen = ArrayList<Onset>()
        var w = 1.2
        while (w < dur - 0.5) {
            val lo = w
            val hi = min(dur - 0.5, w + window)
            val seg = dd.filter { it.time >= lo && it.time < hi }
            val f0 = a.frameAt(lo)
            val f1 = max(f0 + 1, a.frameAt(hi))
            var sum = 0.0
            for (f in f0 until min(f1, rms.size)) sum += rms[f]
            val mr = sum / max(1, min(f1, rms.size) - f0)
            val rel = 20.0 * log10((mr + 1e-9) / (active + 1e-9))
            if (rel < -38.0) { w += window; continue }
            val ef = (1.0 + rel / 36.0).coerceIn(0.45, 1.3)
            val guide = o.guidance.densityAt((lo + hi) / 2)
            val k = (p.nps * (hi - lo) * ef * guide).roundToInt()
            val acc = ArrayList<Onset>()
            val nearby = chosen.filter { it.time > lo - 1 }
            for (c in seg.sortedByDescending { it.strength }) {
                if (acc.size >= k) break
                if (acc.all { abs(c.time - it.time) >= p.minGapSec } && nearby.all { abs(c.time - it.time) >= p.minGapSec }) acc.add(c)
            }
            if (acc.size < 0.6 * k && ef > 0.5) {
                // 起音不足时在半拍网格上补点, 保证疏松段落也有规律的节奏骨架
                val positions = ArrayList<Pair<Double, Double>>()
                var kk = ceil((lo - phi) / (beat / 2)).toInt()
                while (phi + kk * (beat / 2) < hi) {
                    val tt = phi + kk * (beat / 2)
                    if (tt >= lo) positions.add(a.env[a.frameAt(tt)].toDouble() to tt)
                    kk++
                }
                for ((_, tt) in positions.sortedByDescending { it.first }) {
                    if (acc.size >= (0.6 * k).toInt()) break
                    val gap = max(p.minGapSec, 0.2)
                    if (acc.all { abs(tt - it.time) >= gap } && nearby.all { abs(tt - it.time) >= gap }) {
                        acc.add(Onset(tt, 0.3, 0.33, 0.33, 0.5, 0.0, -1, a.frameAt(tt)))
                    }
                }
            }
            chosen.addAll(acc)
            w += window
        }
        chosen.sortBy { it.time }
        return chosen
    }

    // ---------- 音符构建 ----------

    private fun buildNotes(
        chosenIn: List<Onset>, a: AnalysisResult, p: DifficultyProfile, phi: Double, beat: Double,
        dur: Double, o: GenerationOptions,
    ): List<Note> {
        val rng = Random(o.seed)
        var chosen = chosenIn
        var n = chosen.size
        var types = Array(n) { NoteType.TAP }
        var ends = DoubleArray(n) { chosen[it].time }

        // Hold: 新出现并持续存在的谱线
        val holdCands = ArrayList<Triple<Double, Int, Double>>()
        for (i in 0 until n) {
            val c = chosen[i]
            if (c.sustainSec >= 0.45 && c.sustainBin > 0) {
                var e = c.time + min(c.sustainSec, 4 * beat)
                e = snap(e, phi, beat, p.division, 0.05)
                if (e - c.time >= 0.4) holdCands.add(Triple(c.sustainSec, i, e))
            }
        }
        holdCands.sortByDescending { it.first }
        val drop = HashSet<Int>()
        val taken = ArrayList<Pair<Double, Double>>()
        val holdBudget = (p.holdRatio * n).toInt()
        var holdCount = 0
        for ((_, i, e) in holdCands) {
            if (holdCount >= holdBudget) break
            val s = chosen[i].time
            if (taken.any { s < it.second && e > it.first }) continue
            types[i] = NoteType.HOLD
            ends[i] = e
            taken.add(s to e)
            holdCount++
            if (p.difficulty == Difficulty.EZ) {
                for (j in i + 1 until n) {
                    if (chosen[j].time <= e + 0.05) drop.add(j) else break
                }
            }
        }
        if (drop.isNotEmpty()) {
            val keep = (0 until n).filter { it !in drop }
            val c2 = keep.map { chosen[it] }
            val t2 = keep.map { types[it] }.toTypedArray()
            val e2 = DoubleArray(keep.size) { ends[keep[it]] }
            chosen = c2; types = t2; ends = e2; n = chosen.size
        }

        // Flick: 强重音且偏亮
        if (p.flickRatio > 0 && n > 0) {
            val thr = Stats.percentile(FloatArray(n) { chosen[it].strength.toFloat() }, 85.0)
            val cand = (0 until n).filter { types[it] == NoteType.TAP && chosen[it].strength >= thr && chosen[it].highShare >= 0.35 }
            for (i in cand.take((p.flickRatio * n).toInt())) types[i] = NoteType.FLICK
        }

        // Drag: 密集连打中除首个外的音符
        if (p.dragRatio > 0) {
            val runs = ArrayList<List<Int>>()
            var run = ArrayList<Int>()
            for (i in 0 until n) {
                if (run.isNotEmpty() && chosen[i].time - chosen[run.last()].time <= 0.17 && types[i] == NoteType.TAP) {
                    run.add(i)
                } else {
                    if (run.size >= 3) runs.add(run)
                    run = if (types[i] == NoteType.TAP) arrayListOf(i) else ArrayList()
                }
            }
            if (run.size >= 3) runs.add(run)
            var budget = (p.dragRatio * n).toInt()
            for (r in runs) {
                for (i in r.drop(1)) {
                    if (budget <= 0) break
                    types[i] = NoteType.DRAG
                    budget--
                }
            }
        }

        // 轨道分配
        val laneCount = p.lanes.size
        val laneOf = IntArray(n)
        val lock = DoubleArray(laneCount) { -1.0 }
        val holdSpans = ArrayList<Triple<Int, Double, Double>>()
        var prev = laneCount / 2
        var prevT = -9.0
        for (i in 0 until n) {
            val c = chosen[i]
            val pref = c.brightness * (laneCount - 1)
            var best = -1
            var bestScore = Double.MAX_VALUE
            for (ln in 0 until laneCount) {
                if (lock[ln] > c.time - 0.02) continue
                var sc = abs(ln - pref) + rng.nextDouble() * 0.35
                if (c.time - prevT < 0.3 && ln == prev) sc += 2.5 else if (ln == prev) sc += 0.8
                val jump = abs(ln - prev)
                if (types[i] == NoteType.DRAG) {
                    if (jump > 1) sc += 3.0
                    sc += 0.5 * jump
                } else {
                    val lim = if (laneCount > 4) 2 else 1
                    if (jump > lim) sc += 1.5 * (jump - lim)
                }
                if (sc < bestScore) { bestScore = sc; best = ln }
            }
            if (best < 0) {
                var mi = 0
                for (k in 1 until laneCount) if (lock[k] < lock[mi]) mi = k
                best = mi
            }
            laneOf[i] = best
            if (types[i] == NoteType.HOLD) {
                lock[best] = ends[i] + 0.05
                holdSpans.add(Triple(best, c.time, ends[i] + 0.05))
            }
            prev = best
            prevT = c.time
        }

        // 判定线分配与音符
        val notes = ArrayList<Note>(n + n / 8)
        for (i in 0 until n) {
            val b = (chosen[i].time - phi) / beat
            val line = if (p.lineCount == 1) 0 else (Math.floorMod(Math.floor(b / 8).toInt(), 2))
            notes.add(Note(chosen[i].time, ends[i], types[i], p.lanes[laneOf[i]], line))
        }

        // 同时按键: 仅高难度, 在强重音处补一个拉开距离的 Tap
        if (p.chordRatio > 0 && n > 0) {
            val thr = Stats.percentile(FloatArray(n) { chosen[it].strength.toFloat() }, 85.0)
            val budget = (p.chordRatio * n).toInt()
            var added = 0
            val extra = ArrayList<Note>()
            for (i in 0 until n) {
                val c = chosen[i]
                if ((types[i] == NoteType.TAP || types[i] == NoteType.FLICK) && c.strength >= thr &&
                    rng.nextDouble() < p.chordRatio / 0.15 * 0.6 && added < budget
                ) {
                    val a0 = laneOf[i]
                    val opts = (0 until laneCount).filter { l ->
                        abs(l - a0) >= 2 && holdSpans.none { it.first == l && c.time > it.second - 0.02 && c.time < it.third }
                    }
                    if (opts.isNotEmpty()) {
                        val ln = opts[rng.nextInt(opts.size)]
                        extra.add(Note(c.time, c.time, NoteType.TAP, p.lanes[ln], notes[i].line))
                        added++
                    }
                }
            }
            notes.addAll(extra)
        }
        notes.sortWith(compareBy<Note> { it.time }.thenBy { it.x })
        return notes
    }

    // ---------- 难度估计 ----------

    fun estimateLevel(notes: List<Note>, dur: Double): Pair<Double, Int> {
        if (notes.isEmpty()) return 1.0 to 1
        val first = notes.first().time
        val last = notes.last().time
        val span = max(10.0, last - first)
        val nps = notes.size / span
        val bins = IntArray(((dur + 3.0) / 3.0).toInt() + 1)
        for (nn in notes) bins[min(bins.size - 1, (nn.time / 3.0).toInt())]++
        val rates = bins.filter { it > 0 }.map { (it / 3.0).toFloat() }.toFloatArray()
        val p95 = if (rates.isNotEmpty()) Stats.percentile(rates, 95.0) else 0.0
        var chords = 0
        for (i in 1 until notes.size) if (abs(notes[i].time - notes[i - 1].time) < 1e-6) chords++
        val chordRatio = chords.toDouble() / notes.size
        val dragRatio = notes.count { it.type == NoteType.DRAG }.toDouble() / notes.size
        val v = (1.0 + 1.55 * nps + 0.5 * p95 + 2.0 * chordRatio + 1.5 * dragRatio).coerceIn(1.0, 17.0)
        val rounded = Math.round(v * 10.0) / 10.0
        return rounded to Math.round(v).toInt()
    }
}
