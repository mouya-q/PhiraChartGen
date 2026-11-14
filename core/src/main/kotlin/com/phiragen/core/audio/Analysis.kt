package com.phiragen.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 分析使用的固定参数 */
object AnalysisConfig {
    const val SAMPLE_RATE = 22050
    const val FRAME = 1024
    const val HOP = 256
    const val FPS = SAMPLE_RATE.toDouble() / HOP
    const val BINS = FRAME / 2 + 1
    /** 保留的低频谱线数量 (约 4 kHz 以内), 用于持续音追踪 */
    const val KEEP_BINS = 190
    const val MIN_BPM = 60.0
    const val MAX_BPM = 200.0
}

/** 分析结果, 只保存逐帧特征而非完整频谱, 长音频也能控制内存 */
class AnalysisResult(
    val durationSec: Double,
    val bpm: Double,
    /** 第一拍相位 (秒), 范围 [0, 一拍时长) */
    val phase: Double,
    val env: FloatArray,
    val low: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    val rms: FloatArray,
    val centroidHz: FloatArray,
    val lowMag: FloatArray,
) {
    val frameCount: Int get() = env.size
    val beatSec: Double get() = 60.0 / bpm
    val fps: Double get() = AnalysisConfig.FPS

    fun frameAt(sec: Double): Int = (sec * fps).toInt().coerceIn(0, max(0, frameCount - 1))
}

class AnalysisException(message: String) : Exception(message)

/** 逐块输入 22.05kHz 单声道 PCM, 每满一帧立刻计算特征 */
class SpectrumStream : SpectrumSink {
    override val engine: String = "kotlin"

    private val n = AnalysisConfig.FRAME
    private val hop = AnalysisConfig.HOP
    private val fft = Fft(n)
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    private val ring = FloatArray(n)
    private var pos = 0
    private var total = 0L
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val prevLog = DoubleArray(AnalysisConfig.BINS)
    private var hasPrev = false
    private val loBin = (250.0 / (AnalysisConfig.SAMPLE_RATE.toDouble() / n)).toInt()
    private val midBin = (2500.0 / (AnalysisConfig.SAMPLE_RATE.toDouble() / n)).toInt()

    private val low = FloatList()
    private val mid = FloatList()
    private val high = FloatList()
    private val rms = FloatList()
    private val centroid = FloatList()
    private val lowMag = FloatList(AnalysisConfig.KEEP_BINS * 256)

    var samplesSeen: Long = 0
        private set

    override fun push(x: FloatArray, len: Int) {
        for (k in 0 until len) {
            ring[pos] = x[k]
            pos = (pos + 1) and (n - 1)
            total++
            samplesSeen++
            if (total >= n && (total - n) % hop == 0L) frame()
        }
    }

    private fun frame() {
        for (i in 0 until n) {
            re[i] = ring[(pos + i) and (n - 1)] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val bins = AnalysisConfig.BINS
        var lowSum = 0.0
        var midSum = 0.0
        var highSum = 0.0
        var sq = 0.0
        var wsum = 0.0
        var magSum = 0.0
        val hz = AnalysisConfig.SAMPLE_RATE.toDouble() / n
        for (b in 0 until bins) {
            val m = Math.hypot(re[b], im[b])
            sq += m * m
            val lg = ln(1.0 + 100.0 * m)
            if (hasPrev) {
                val d = lg - prevLog[b]
                if (d > 0) {
                    if (b in 1 until loBin) lowSum += d
                    else if (b in loBin until midBin) midSum += d
                    else if (b >= midBin) highSum += d
                }
            }
            prevLog[b] = lg
            if (b >= 1) {
                wsum += m * b * hz
                magSum += m
            }
            if (b < AnalysisConfig.KEEP_BINS) lowMag.add(m.toFloat())
        }
        hasPrev = true
        low.add((lowSum / max(1, loBin - 1)).toFloat())
        mid.add((midSum / (midBin - loBin)).toFloat())
        high.add((highSum / (bins - midBin)).toFloat())
        rms.add(sqrt(sq / bins).toFloat())
        centroid.add((wsum / (magSum + 1e-9)).toFloat())
    }

    override fun finish(): AnalysisResult {
        if (rms.size < 64) throw AnalysisException("音频太短, 至少需要约 1 秒")
        val duration = samplesSeen.toDouble() / AnalysisConfig.SAMPLE_RATE
        val lowN = localNorm(low.toArray())
        val midN = localNorm(mid.toArray())
        val highN = localNorm(high.toArray())
        val combined = FloatArray(low.size) { 0.5f * low.data[it] + mid.data[it] + high.data[it] }
        val env = localNorm(combined)
        val peaks = PeakPicker.pick(env, 0.045, 0.12)
        val tempo = TempoEstimator.estimate(env, peaks)
        return AnalysisResult(
            durationSec = duration,
            bpm = tempo.bpm,
            phase = tempo.phase,
            env = env,
            low = lowN,
            mid = midN,
            high = highN,
            rms = rms.toArray(),
            centroidHz = centroid.toArray(),
            lowMag = lowMag.toArray(),
        )
    }

    companion object {
        /** 去除缓慢变化的底噪后按局部能量归一化 */
        fun localNorm(e: FloatArray): FloatArray {
            val n = e.size
            val mean = Stats.movingAverage(e, 43)
            val y = FloatArray(n) { max(0f, e[it] - mean[it]) }
            val g = Stats.percentile(y, 97.0) + 1e-9
            val w = (AnalysisConfig.FPS * 1.5).toInt() or 1
            val sq = FloatArray(n) { y[it] * y[it] }
            val local = Stats.movingAverage(sq, w)
            return FloatArray(n) {
                val scale = max(sqrt(local[it].toDouble()) * 3.0, 0.25 * g)
                min(1.5, y[it] / scale).toFloat()
            }
        }
    }
}

object PeakPicker {
    /** 局部极大值检测, minGapSec 内只保留更强的一个 */
    fun pick(env: FloatArray, minGapSec: Double, thr: Double): IntArray {
        val w = 3
        val peaks = ArrayList<Int>()
        var last = -1_000_000_000
        val minGap = minGapSec * AnalysisConfig.FPS
        for (i in w until env.size - w) {
            val v = env[i]
            if (v < thr) continue
            var isMax = true
            for (j in i - w..i + w) {
                if (env[j] > v) { isMax = false; break }
            }
            if (!isMax) continue
            if (i - last >= minGap) {
                peaks.add(i)
                last = i
            } else if (peaks.isNotEmpty() && v > env[peaks.last()]) {
                peaks[peaks.size - 1] = i
                last = i
            }
        }
        return peaks.toIntArray()
    }

    /** 抛物线插值得到亚帧精度位置 */
    fun parabolic(env: FloatArray, i: Int): Double {
        if (i <= 0 || i >= env.size - 1) return i.toDouble()
        val a = env[i - 1].toDouble()
        val b = env[i].toDouble()
        val c = env[i + 1].toDouble()
        val d = a - 2 * b + c
        return if (abs(d) > 1e-12) i + 0.5 * (a - c) / d else i.toDouble()
    }

    /** 帧内起音的实际时间, 0.75 帧长为实测校准出的固定偏置 */
    fun peakTimeSec(env: FloatArray, i: Int): Double =
        parabolic(env, i) / AnalysisConfig.FPS + 0.75 * AnalysisConfig.FRAME / AnalysisConfig.SAMPLE_RATE
}

data class TempoResult(val bpm: Double, val phase: Double)

/**
 * 速度与相位估计.
 * 先用自相关找出若干候选速度, 再补上它们的 2 倍, 1/2, 3/2, 2/3 等相关速度,
 * 逐个候选精细对齐后, 以 "强起音落在拍格上的比例" 为主要依据选择.
 * 在 10 首带有标准速度的真实歌曲上验证过, 旧方法会把 160 误判为 2/3 倍的 106.65
 */
object TempoEstimator {
    private const val WIDTH = 0.06

    private fun score(times: DoubleArray, st: DoubleArray, period: Double, phi: Double): Double {
        var s = 0.0
        val half = period / 2
        for (k in times.indices) {
            var d = (times[k] - phi + half) % period
            if (d < 0) d += period
            d -= half
            if (d > -WIDTH && d < WIDTH) {
                val u = 1 - (d / WIDTH) * (d / WIDTH)
                s += st[k] * u * u
            }
        }
        return s
    }

    private class Aligned(val frac: Double, val phase: Double)

    private fun align(times: DoubleArray, st: DoubleArray, total: Double, bpm: Double, step: Double): Aligned {
        val period = 60.0 / bpm
        var best = -1.0
        var bestPhi = 0.0
        var phi = 0.0
        while (phi < period) {
            val s = score(times, st, period, phi)
            if (s > best) { best = s; bestPhi = phi }
            phi += step
        }
        var p = bestPhi - step
        val limit = bestPhi + step
        while (p < limit) {
            val pp = ((p % period) + period) % period
            val s = score(times, st, period, pp)
            if (s > best) { best = s; bestPhi = pp }
            p += step / 4
        }
        return Aligned(best / total, bestPhi)
    }

    /** 自相关得分最高的若干个局部峰值对应的速度 */
    private fun autocorrCandidates(env: FloatArray, count: Int): List<Double> {
        val fps = AnalysisConfig.FPS
        val n = env.size
        var mean = 0.0
        for (v in env) mean += v
        mean /= n
        val size = Fft.nextPow2(2 * n)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until n) re[i] = env[i] - mean
        val fft = Fft(size)
        fft.transform(re, im)
        for (i in 0 until size) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val zero = re[0] / size + 1e-9
        val ac = DoubleArray(n) { re[it] / size / zero }

        val bpms = ArrayList<Double>()
        val scores = ArrayList<Double>()
        var bpm = AnalysisConfig.MIN_BPM
        while (bpm <= AnalysisConfig.MAX_BPM + 1e-9) {
            val lag = 60.0 / bpm * fps
            var s = 0.0
            val mults = doubleArrayOf(1.0, 2.0, 4.0)
            val weights = doubleArrayOf(1.0, 0.5, 0.25)
            for (k in mults.indices) {
                val l = lag * mults[k]
                val i0 = l.toInt()
                if (i0 + 1 >= n) continue
                val fr = l - i0
                s += weights[k] * (ac[i0] * (1 - fr) + ac[i0 + 1] * fr)
            }
            bpms.add(bpm)
            scores.add(s)
            bpm += 0.5
        }
        val peaks = ArrayList<Int>()
        for (i in 2 until scores.size - 2) {
            var isMax = true
            for (j in i - 2..i + 2) if (scores[j] > scores[i]) { isMax = false; break }
            if (isMax) peaks.add(i)
        }
        peaks.sortByDescending { scores[it] }
        return peaks.take(count).map { bpms[it] }
    }

    fun estimate(env: FloatArray, peaks: IntArray): TempoResult {
        if (peaks.size < 8) return TempoResult(120.0, 0.0)

        // 取最强的若干起音做对齐, 控制长歌曲的计算量
        val order = peaks.sortedByDescending { env[it] }.take(1200)
        val times = DoubleArray(order.size) { PeakPicker.peakTimeSec(env, order[it]) }
        val strengths = DoubleArray(order.size) { env[order[it]].toDouble() }
        val total = strengths.sum()
        if (total <= 1e-9) return TempoResult(120.0, 0.0)

        val raw = java.util.TreeSet<Double>()
        for (b in autocorrCandidates(env, 8)) {
            for (r in doubleArrayOf(1.0, 2.0, 0.5, 1.5, 2.0 / 3.0, 4.0 / 3.0, 0.75)) {
                val x = b * r
                if (x >= 70.0 && x <= 200.0) raw.add(Math.round(x * 10.0) / 10.0)
            }
        }
        val candidates = ArrayList<Double>()
        for (c in raw) if (candidates.isEmpty() || c - candidates.last() > 0.6) candidates.add(c)

        var bestScore = -1.0
        var bestBpm = 120.0
        var bestPhase = 0.0
        for (b in candidates) {
            var coarseBest = -1.0
            var coarseX = b
            var x = b - 1.5
            while (x <= b + 1.5 + 1e-9) {
                val f = align(times, strengths, total, x, 0.02).frac
                if (f > coarseBest) { coarseBest = f; coarseX = x }
                x += 0.25
            }
            var fineBest = -1.0
            var fineX = coarseX
            var phase = 0.0
            x = coarseX - 0.25
            while (x <= coarseX + 0.25 + 1e-9) {
                val al = align(times, strengths, total, x, 0.01)
                if (al.frac > fineBest) { fineBest = al.frac; fineX = x; phase = al.phase }
                x += 0.05
            }
            val z = Math.log(fineX / 125.0) / Math.log(2.0)
            val s = fineBest * exp(-0.5 * z * z)
            if (s > bestScore) { bestScore = s; bestBpm = fineX; bestPhase = phase }
        }
        val period = 60.0 / bestBpm
        return TempoResult(bestBpm, ((bestPhase % period) + period) % period)
    }
}
