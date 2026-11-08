package com.phiragen.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 基于基 2 的原地复数 FFT, 长度必须是 2 的幂 */
class Fft(val size: Int) {
    private val cosT = DoubleArray(size / 2)
    private val sinT = DoubleArray(size / 2)
    private val rev = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT 长度必须是 2 的幂" }
        for (i in 0 until size / 2) {
            val a = -2.0 * PI * i / size
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            rev[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        fun nextPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
