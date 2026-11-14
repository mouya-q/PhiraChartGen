package com.phiragen.core.chart

import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 轨道选择模型.
 * 在 10 张玩家谱上, 用真人把音符放在哪个轨道的数据训练, 按歌曲留一验证:
 * 对数损失 1.581 (随机 1.609), 创作者的选择本身随机性很大, 模型能学到的是转移习惯与左右分布.
 * 采样后的统计与真人接近 (左/中/右 39/23/38 对 40/22/38, 重复率 26% 对 27%, 平均跳距 1.34 对 1.33)
 */
object LaneModel {
    const val FEATURES = 20
    val CLASS_X = doubleArrayOf(-405.0, -202.5, 0.0, 202.5, 405.0)

    fun classOf(laneX: Double): Int {
        var best = 0
        for (c in CLASS_X.indices) if (abs(CLASS_X[c] - laneX) < abs(CLASS_X[best] - laneX)) best = c
        return best
    }

    /** 5 个训练类别分给当前难度的轨道: 每个类别分给最近的轨道, 距离相同则平分 */
    fun classMap(lanes: List<Double>): Array<DoubleArray> {
        val m = Array(CLASS_X.size) { DoubleArray(lanes.size) }
        for (c in CLASS_X.indices) {
            var minD = Double.MAX_VALUE
            for (x in lanes) minD = min(minD, abs(x - CLASS_X[c]))
            val near = lanes.indices.filter { abs(abs(lanes[it] - CLASS_X[c]) - minD) < 1e-9 }
            for (i in near) m[c][i] = 1.0 / near.size
        }
        return m
    }

    /**
     * 特征顺序: 起音强度, 低/中/高频占比, 亮度, 主导谱线, 低频倾向, 相对响度, 与上一个音的间隔,
     * 上一个轨道 (5), 上上个轨道 (5), 是否为乐段开头.
     * prevClass 与 prev2Class 为 -1 表示没有
     */
    fun features(a: AnalysisResult, t: Double, prevClass: Int, prev2Class: Int, prevTime: Double, p90: Double): DoubleArray {
        val n = a.frameCount
        val fps = AnalysisConfig.FPS
        val shift = 0.75 * AnalysisConfig.FRAME / AnalysisConfig.SAMPLE_RATE * fps
        val f = (t * fps - shift).coerceIn(0.0, (n - 1).toDouble()).toInt()
        val lo = max(0, f - 2)
        val hi = min(n, f + 3)
        var env = 0.0
        var l = 0.0
        var m = 0.0
        var h = 0.0
        for (k in lo until hi) {
            env = max(env, a.env[k].toDouble())
            l = max(l, a.low[k].toDouble())
            m = max(m, a.mid[k].toDouble())
            h = max(h, a.high[k].toDouble())
        }
        val tot = l + m + h + 1e-9
        val j = min(n - 1, f + 2)
        val cen = ((ln(max(a.centroidHz[j].toDouble(), 60.0)) / ln(2.0) - ln(100.0) / ln(2.0)) /
            (ln(5000.0) / ln(2.0) - ln(100.0) / ln(2.0))).coerceIn(0.0, 1.0)
        val keep = AnalysisConfig.KEEP_BINS
        var dom = 0
        var domV = -1.0
        var sum = 0.0
        var low = 0.0
        for (b in 3 until keep) {
            val v = a.lowMag[j * keep + b].toDouble()
            sum += v
            if (b - 3 < 60) low += v
            if (v > domV) { domV = v; dom = b - 3 }
        }
        val loud = a.rms[j] / p90
        val dt = if (prevTime.isNaN()) 1.0 else min(2.0, max(0.05, t - prevTime))
        val out = DoubleArray(FEATURES)
        out[0] = env
        out[1] = l / tot
        out[2] = m / tot
        out[3] = h / tot
        out[4] = cen
        out[5] = dom / 187.0
        out[6] = low / (sum + 1e-9)
        out[7] = loud
        out[8] = ln(dt) / ln(40.0) + 1.0
        if (prevClass >= 0) out[9 + prevClass] = 1.0
        if (prev2Class >= 0) out[14 + prev2Class] = 1.0
        out[19] = if (prevTime.isNaN() || t - prevTime > 1.5) 1.0 else 0.0
        return out
    }

    fun probs(f: DoubleArray): DoubleArray {
        val logits = DoubleArray(CLASS_X.size)
        for (c in logits.indices) {
            var s = LaneModelData.BIAS[c]
            for (k in 0 until FEATURES) s += LaneModelData.WEIGHTS[c][k] * (f[k] - LaneModelData.MEAN[k]) / LaneModelData.STD[k]
            logits[c] = s
        }
        val mx = logits.max()
        var total = 0.0
        for (c in logits.indices) { logits[c] = exp(logits[c] - mx); total += logits[c] }
        for (c in logits.indices) logits[c] /= total
        return logits
    }
}
