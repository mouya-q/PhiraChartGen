package com.phiragen.core.chart

import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.audio.Stats
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 一段连续的参数变化, 位置以谱面拍为单位 */
class Seg(val start: Double, val end: Double, val from: Double, val to: Double, val easing: Int)

object Ease {
    /** 缓动编号与 RPE 一致: 1 线性, 2 正弦出, 3 正弦入, 4 二次出, 6 正弦入出, 20 回弹出 */
    fun apply(type: Int, u: Double): Double = when (type) {
        2 -> sin(u * PI / 2)
        3 -> 1 - cos(u * PI / 2)
        4 -> 1 - (1 - u) * (1 - u)
        6 -> 0.5 - cos(PI * u) / 2
        20 -> 1 + 2.70158 * (u - 1) * (u - 1) * (u - 1) + 1.70158 * (u - 1) * (u - 1)
        else -> u
    }
}

/** 单个参数的随时间变化, 由首尾相接的 Seg 组成 */
class Channel(initial: Double) {
    val segs = ArrayList<Seg>()
    var cursor = 0.0
        private set
    var value = initial
        private set

    fun to(beat: Double, v: Double, easing: Int = 6) {
        if (beat <= cursor + 1e-9) return
        segs.add(Seg(cursor, beat, value, v, easing))
        cursor = beat
        value = v
    }

    fun hold(beat: Double) = to(beat, value, 1)

    fun finish(end: Double) {
        if (cursor < end) hold(end)
    }

    fun at(beat: Double): Double {
        if (segs.isEmpty()) return value
        if (beat <= segs.first().start) return segs.first().from
        for (s in segs) {
            if (beat < s.end) {
                val u = (beat - s.start) / (s.end - s.start)
                return s.from + (s.to - s.from) * Ease.apply(s.easing, u)
            }
        }
        return segs.last().to
    }
}

/** 一条判定线的位置, 旋转, 透明度. 装饰线不承载音符, 只提供视觉效果 */
class Track(x0: Double, y0: Double, alpha0: Double, val carriesNotes: Boolean) {
    val x = Channel(x0)
    val y = Channel(y0)
    val rotation = Channel(0.0)
    val alpha = Channel(alpha0)
    /** 装饰线当前占用到的拍, 用于复用空闲的线 */
    var busyUntil = 0.0

    fun holdAll(beat: Double) {
        if (x.cursor < beat - 1e-9) x.hold(beat)
        if (y.cursor < beat - 1e-9) y.hold(beat)
        if (rotation.cursor < beat - 1e-9) rotation.hold(beat)
        if (alpha.cursor < beat - 1e-9) alpha.hold(beat)
    }
}

enum class PhraseStyle { STEADY, PULSE, SPLIT, SPIN }

/** 8 拍一个乐句, 记录该乐句内承载音符的判定线与采用的运动风格 */
class Phrase(
    val startBeat: Double, val endBeat: Double, val active: List<Int>,
    val style: PhraseStyle, val laneScale: Double, val energy: Double,
)

class Choreography(val tracks: List<Track>, val phrases: List<Phrase>, val endBeat: Double) {
    val lineCount: Int get() = tracks.size

    fun phraseAt(beat: Double): Phrase {
        val i = if (beat >= 1.0) ((beat - 1.0) / 8.0).toInt() else 0
        return phrases[i.coerceIn(0, phrases.size - 1)]
    }

    /** 轨道按顺序平均分给当前可见的判定线, 保证同一乐句内每条线负责连续的几条轨道 */
    fun lineFor(beat: Double, lane: Int, laneCount: Int): Int {
        val ph = phraseAt(beat)
        return ph.active[lane * ph.active.size / laneCount]
    }

    /** 该线在整个区间内是否都可见 */
    fun activeThrough(line: Int, b0: Double, b1: Double): Boolean {
        val i0 = ((maxOf(b0, 1.0) - 1.0) / 8.0).toInt()
        val i1 = ((maxOf(b1, 1.0) - 1.0) / 8.0).toInt()
        for (i in i0..minOf(i1, phrases.size - 1)) if (line !in phrases[i].active) return false
        return true
    }
}

/**
 * 判定线编排. 依据玩家谱的统计与手法设计:
 * 一条主线承载绝大多数音符并保持稳定 (每 2 拍一次倾斜回弹与小幅下沉),
 * 动画主要由不带音符的装饰线提供 (节拍闪烁扫线, 段落开头对称呼啸线, 向下淡出的回声线),
 * 高潮段偶尔拆成两条线或整体旋转, 音符空白期主线淡出
 */
object ChoreographyBuilder {
    private const val BASE_Y = -260.0

    private fun tilt(d: Difficulty) = when (d) { Difficulty.EZ -> 1.5; Difficulty.HD -> 2.5; else -> 3.0 }
    private fun dip(d: Difficulty) = when (d) { Difficulty.EZ -> 0.0; Difficulty.HD -> 12.0; Difficulty.IN -> 20.0; Difficulty.AT -> 24.0 }
    private fun decorBudget(d: Difficulty) = when (d) { Difficulty.EZ -> 0; Difficulty.HD -> 2; Difficulty.IN -> 6; Difficulty.AT -> 10 }

    /** 以整圈为基准的朝向, 避免旋转角度在多个乐句间漂移 */
    private fun baseRot(v: Double) = Math.round(v / 360.0) * 360.0

    /** 有 AI 指定的特效列表时以它为准, 否则使用离线规则 */
    private fun wantsEffect(cue: Set<EffectType>?, type: EffectType, auto: Boolean): Boolean =
        if (cue != null) type in cue else auto

    private fun steady(tr: Track, t0: Double, t1: Double, d: Difficulty, pulse: Boolean) {
        val rb = baseRot(tr.rotation.value)
        tr.x.to(t0 + 1, 0.0)
        tr.x.hold(t1)
        if (abs(tr.y.value - BASE_Y) > 1.0) tr.y.to(t0 + 1.0, BASE_Y)
        val amp = tilt(d)
        val depth = dip(d) * (if (pulse) 1.25 else 1.0)
        val stride = if (pulse) 1.0 else 2.0
        var sgn = 1.0
        var k = t0
        while (k + 2.0 <= t1 + 1e-9) {
            // 2 拍一组: 1 拍摆出, 过冲回弹, 归零
            tr.rotation.hold(k)
            tr.rotation.to(k + 1.0, rb + sgn * amp, 4)
            tr.rotation.to(k + 1.625, rb - sgn * amp / 3, 20)
            tr.rotation.to(k + 2.0, rb, 4)
            sgn = -sgn
            k += 2.0
        }
        if (depth > 0) {
            k = t0
            while (k + stride <= t1 + 1e-9) {
                tr.y.hold(k)
                tr.y.to(k + 0.25, BASE_Y - depth, 4)
                tr.y.to(if (pulse) k + stride - 0.1 else k + 1.5, BASE_Y, 4)
                k += stride
            }
        }
        tr.y.hold(t1)
        tr.rotation.hold(t1)
    }

    fun build(
        a: AnalysisResult, p: DifficultyProfile, phi: Double, beatSec: Double, durationSec: Double,
        bpm: Double, rng: Random, toBeat: (Double) -> Double, noteBeats: DoubleArray,
        guidance: Guidance = Guidance.NONE,
    ): Choreography {
        val d = p.difficulty
        val endBeat0 = toBeat(durationSec) + 8.0
        val n = ceil((endBeat0 - 1.0) / 8.0).toInt().coerceAtLeast(1)
        val p90 = Stats.percentile(a.rms, 90.0) + 1e-9
        val energies = DoubleArray(n) { i ->
            val s = phi + 8 * i * beatSec
            val f0 = a.frameAt(s)
            val f1 = maxOf(f0 + 1, minOf(a.frameCount, ((s + 8 * beatSec) * a.fps).toInt()))
            var sum = 0.0
            for (f in f0 until f1) sum += a.rms[f]
            (sum / (f1 - f0)) / p90
        }
        val main = Track(0.0, BASE_Y, 0.0, true)
        val split = Track(0.0, BASE_Y, 0.0, true)
        val phrases = ArrayList<Phrase>()
        var sincePiece = 9
        var dir = 1.0
        for (i in 0 until n) {
            val t0 = 1.0 + 8 * i
            val t1 = t0 + 8
            val e = energies[i]
            val cue = guidance.phrases[i]
            var style = PhraseStyle.STEADY
            val highLevel = d == Difficulty.IN || d == Difficulty.AT
            val wanted = cue?.style
            if (wanted != null) {
                // AI 指定的风格: 拆线与旋转只允许高难度, 且与上一次大动作间隔至少 3 个乐句
                style = when (wanted) {
                    PhraseStyle.PULSE -> if (d == Difficulty.EZ) PhraseStyle.STEADY else PhraseStyle.PULSE
                    PhraseStyle.SPLIT, PhraseStyle.SPIN ->
                        if (highLevel && sincePiece >= 3) wanted else if (d != Difficulty.EZ) PhraseStyle.PULSE else PhraseStyle.STEADY
                    else -> PhraseStyle.STEADY
                }
            } else if (highLevel && e > 0.85 && sincePiece >= 3 && rng.nextDouble() < 0.5) {
                style = if (rng.nextInt(2) == 0) PhraseStyle.SPLIT else PhraseStyle.SPIN
            } else if (e > 0.6 && d != Difficulty.EZ) {
                style = PhraseStyle.PULSE
            }
            dir = -dir
            var active = listOf(0)
            var scale = 1.0
            when (style) {
                PhraseStyle.STEADY, PhraseStyle.PULSE -> {
                    steady(main, t0, t1, d, style == PhraseStyle.PULSE)
                    sincePiece++
                }
                PhraseStyle.SPLIT -> {
                    active = listOf(0, 1)
                    val off = if (d == Difficulty.AT) 300.0 else 260.0
                    val tiltDeg = if (d == Difficulty.IN) 16.0 else 22.0
                    for ((j, sgn) in listOf(0 to -1.0, 1 to 1.0)) {
                        val t = if (j == 0) main else split
                        val rb = baseRot(t.rotation.value)
                        t.x.to(t0 + 1, sgn * off); t.x.hold(t1)
                        t.y.to(t0 + 1, BASE_Y + 10.0 * -sgn); t.y.to(t1, BASE_Y - 20.0 * -sgn)
                        t.rotation.to(t0 + 1, rb - sgn * tiltDeg); t.rotation.to(t1, rb - sgn * (tiltDeg - 5.0))
                    }
                    sincePiece = 0
                }
                PhraseStyle.SPIN -> {
                    scale = 0.7
                    val rb = baseRot(main.rotation.value)
                    main.x.to(t0 + 1, 0.0); main.x.hold(t1)
                    main.y.to(t0 + 1, -40.0); main.y.hold(t1)
                    if (d == Difficulty.IN) {
                        // 摆到 60 度再摆回, 结束时回到基准朝向
                        main.rotation.to(t0 + 4.5, rb + dir * 60.0); main.rotation.to(t1, rb)
                    } else {
                        // 整圈旋转, 结束时恰好转满 360 度
                        main.rotation.to(t1, rb + dir * 360.0)
                    }
                    sincePiece = 0
                }
            }
            if (1 !in active) {
                split.x.hold(t1); split.y.hold(t1); split.rotation.hold(t1)
            }
            phrases.add(Phrase(t0, t1, active, style, scale, e))
        }

        // 主线透明度: 音符之间的长空白期淡出, 音符出现前淡入
        val nb = noteBeats.sortedArray()
        val groups = ArrayList<DoubleArray>()
        for (b in nb) {
            if (groups.isNotEmpty() && b - groups.last()[1] < 10.0) groups.last()[1] = b else groups.add(doubleArrayOf(b, b))
        }
        val a0 = main.alpha
        for (g in groups) {
            a0.hold(maxOf(a0.cursor, g[0] - 4.0))
            a0.to(maxOf(a0.cursor + 0.01, g[0] - 2.0), 255.0, 3)
            a0.hold(g[1] + 1.0)
            a0.to(g[1] + 3.0, 0.0, 2)
        }
        // 分线透明度: 仅在使用它的乐句可见
        val sa = split.alpha
        for ((i, ph) in phrases.withIndex()) {
            val cur = 1 in ph.active
            val nxt = if (i + 1 < phrases.size) 1 in phrases[i + 1].active else false
            val prv = if (i > 0) 1 in phrases[i - 1].active else false
            if (cur && !prv) { sa.hold(ph.startBeat - 1.0); sa.to(ph.startBeat, 255.0, 6) }
            if (cur && !nxt) { sa.hold(ph.endBeat); sa.to(ph.endBeat + 1.0, 0.0, 6) }
        }
        val usedSplit = phrases.any { 1 in it.active }

        // 装饰线
        val decor = ArrayList<Track>()
        val budget = decorBudget(d)
        fun getLine(b: Double, dur: Double): Track? {
            for (t in decor) {
                if (t.busyUntil <= b - 0.01) { t.busyUntil = b + dur; t.holdAll(b); return t }
            }
            if (decor.size < budget) {
                val t = Track(0.0, 0.0, 0.0, false)
                t.busyUntil = b + dur
                t.holdAll(b)
                decor.add(t)
                return t
            }
            return null
        }
        /** 在透明状态下移到起点, 避免事件之间的数值跳变 */
        fun settle(t: Track, b: Double, x: Double, y: Double, r: Double) {
            t.x.to(b, x, 1); t.y.to(b, y, 1); t.rotation.to(b, r, 1)
        }
        var mirror = 1.0
        var lastBurst = -9
        if (budget > 0) {
            for ((i, ph) in phrases.withIndex()) {
                val e = ph.energy
                val prevE = if (i > 0) phrases[i - 1].energy else 0.0
                val t0 = ph.startBeat
                val cueEffects = guidance.phrases[i]?.effects
                if (wantsEffect(cueEffects, EffectType.SWEEPERS, i < 2 || (e > 0.9 && (d == Difficulty.IN || d == Difficulty.AT) && i % 2 == 0))) {
                    // 节拍闪烁扫线: 近竖直的线每 2 拍从一侧扫入, 透明度每拍脉冲一次
                    for (side in doubleArrayOf(1.0, -1.0)) {
                        val t = getLine(t0 - 0.06, 8.06) ?: break
                        for (k in 0 until 4) {
                            val b = t0 + 2 * k
                            val s2 = side * (if (k % 2 == 0) 1.0 else -1.0)
                            t.holdAll(b - 0.06)
                            settle(t, b, s2 * 600.0, 0.0, 80.0 * s2)
                            t.x.to(b + 2.0, s2 * 400.0, 2); t.y.hold(b + 2.0); t.rotation.to(b + 2.0, 85.0 * s2, 2)
                            t.alpha.to(b + 1.0, 120.0, 2); t.alpha.to(b + 2.0, 0.0, 3)
                        }
                        t.busyUntil = t0 + 8.0
                    }
                }
                if (wantsEffect(cueEffects, EffectType.WHOOSH, (e - prevE > 0.25 || e > 0.9) && i - lastBurst >= 2 && i > 0)) {
                    // 段落开头的对称呼啸扫线
                    for (side in doubleArrayOf(1.0, -1.0)) {
                        val t = getLine(t0 - 0.06, 1.81) ?: break
                        t.holdAll(t0 - 0.06)
                        settle(t, t0, side * 350.0 * mirror, side * 200.0, -315.0 * side * mirror)
                        t.x.to(t0 + 1.75, side * 700.0 * mirror, 2)
                        t.y.to(t0 + 1.75, side * 600.0, 2)
                        t.rotation.to(t0 + 1.75, -360.0 * side * mirror, 2)
                        t.alpha.to(t0 + 0.5, 120.0, 2); t.alpha.to(t0 + 1.75, 0.0, 2)
                    }
                    mirror = -mirror
                    lastBurst = i
                }
                if (wantsEffect(cueEffects, EffectType.ECHO, e > 0.75 && (d == Difficulty.IN || d == Difficulty.AT))) {
                    // 回声线: 从主线位置向下扩散并淡出, 每 1.5 拍一次
                    val t = getLine(t0 - 0.06, 7.56)
                    if (t != null) {
                        for (k in 0 until 5) {
                            val b = t0 + 1.5 * k
                            t.holdAll(b - 0.06)
                            settle(t, b, 0.0, BASE_Y, 0.0)
                            t.alpha.to(b + 0.02, 200.0, 1)
                            t.y.to(b + 1.5, BASE_Y - 200.0, 2); t.alpha.to(b + 1.5, 0.0, 2)
                            t.x.hold(b + 1.5); t.rotation.hold(b + 1.5)
                        }
                        t.busyUntil = t0 + 7.5
                    }
                }
            }
        }

        val out = ArrayList<Track>()
        out.add(main)
        if (usedSplit) out.add(split)
        out.addAll(decor)
        // 所有通道统一结束在乐句对齐的位置之后, 保证覆盖全曲且长度一致
        var endBeat = 1.0 + 8 * n
        for (t in out) for (c in listOf(t.x, t.y, t.rotation, t.alpha)) endBeat = maxOf(endBeat, c.cursor)
        for (t in out) for (c in listOf(t.x, t.y, t.rotation, t.alpha)) c.finish(endBeat)
        return Choreography(out, phrases, endBeat)
    }
}
