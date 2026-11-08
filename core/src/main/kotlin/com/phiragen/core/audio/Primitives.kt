package com.phiragen.core.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** 可增长的 Float 数组, 避免装箱 */
class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

object Stats {
    /** 线性插值百分位, 与 numpy 默认行为一致 */
    fun percentile(values: FloatArray, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.copyOf()
        s.sort()
        val pos = (q / 100.0) * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        val f = pos - lo
        return s[lo] * (1 - f) + s[hi] * f
    }

    /** 以零填充边界的居中滑动平均, 返回等长数组 */
    fun movingAverage(x: FloatArray, window: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        if (n == 0) return out
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        val half = window / 2
        for (i in 0 until n) {
            val lo = maxOf(0, i - half)
            val hi = minOf(n - 1, i + half)
            out[i] = ((prefix[hi + 1] - prefix[lo]) / window).toFloat()
        }
        return out
    }

    fun rmsOf(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size).toFloat()
    }
}

/**
 * 流式降采样并保持单声道
 * 采用面积加权的盒式平均, 兼具低通抗混叠作用, 适合起音分析
 */
class StreamResampler(inRate: Int, outRate: Int, private val sink: (FloatArray, Int) -> Unit) {
    private val ratio = inRate.toDouble() / outRate
    private val outBuf = FloatArray(4096)
    private var outCount = 0
    private var acc = 0.0
    private var accWeight = 0.0
    private var phase = 0.0

    /** 输入已混合的单声道样本 */
    fun push(mono: FloatArray, len: Int) {
        for (i in 0 until len) {
            var remaining = 1.0
            val v = mono[i].toDouble()
            while (remaining > 1e-12) {
                val need = ratio - phase
                val take = if (remaining < need) remaining else need
                acc += v * take
                accWeight += take
                phase += take
                remaining -= take
                if (phase >= ratio - 1e-12) {
                    emit((acc / accWeight).toFloat())
                    acc = 0.0
                    accWeight = 0.0
                    phase = 0.0
                }
            }
        }
    }

    fun flush() {
        if (accWeight > 0.5) emit((acc / accWeight).toFloat())
        acc = 0.0
        accWeight = 0.0
        phase = 0.0
        if (outCount > 0) {
            sink(outBuf, outCount)
            outCount = 0
        }
    }

    private fun emit(v: Float) {
        outBuf[outCount++] = v
        if (outCount == outBuf.size) {
            sink(outBuf, outCount)
            outCount = 0
        }
    }
}
