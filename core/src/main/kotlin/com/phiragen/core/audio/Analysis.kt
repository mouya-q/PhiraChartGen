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
class SpectrumStream {
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

    fun push(x: FloatArray, len: Int) {
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

    fun finish(): AnalysisResult {
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

object TempoEstimator {
    fun estimate(env: FloatArray, peaks: IntArray): TempoResult {
        if (peaks.size < 8) return TempoResult(120.0, 0.0)
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
        val ac = DoubleArray(n) { re[it] / size }
        val zero = ac[0] + 1e-9
        for (i in 0 until n) ac[i] /= zero

        var bestScore = -1.0
        var coarse = 120.0
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
            val z = ln(bpm / 120.0) / ln(2.0) / 0.9
            val score = s * exp(-0.5 * z * z)
            if (score > bestScore) {
                bestScore = score
                coarse = bpm
            }
            bpm += 0.5
        }

        // 取最强的若干起音做精细对齐, 控制长歌曲的计算量
        val order = peaks.sortedByDescending { env[it] }.take(3000)
        val times = DoubleArray(order.size) { PeakPicker.peakTimeSec(env, order[it]) }
        val strengths = DoubleArray(order.size) { env[order[it]].toDouble() }

        var bestS = -1.0
        var bestBpm = coarse
        var bestPhase = 0.0
        var cand = coarse - 3.0
        while (cand <= coarse + 3.0 + 1e-9) {
            val period = 60.0 / cand
            val sig = 0.025
            var phi = 0.0
            var localBest = -1.0
            var localPhi = 0.0
            while (phi < period) {
                var s = 0.0
                for (k in times.indices) {
                    val raw = times[k] - phi + period / 4
                    val half = period / 2
                    val d = ((raw % half) + half) % half - period / 4
                    s += strengths[k] * exp(-d * d / (2 * sig * sig))
                }
                if (s > localBest) {
                    localBest = s
                    localPhi = phi
                }
                phi += 0.005
            }
            if (localBest > bestS) {
                bestS = localBest
                bestBpm = cand
                bestPhase = localPhi
            }
            cand += 0.05
        }
        val period = 60.0 / bestBpm
        return TempoResult(bestBpm, ((bestPhase % period) + period) % period)
    }
}
