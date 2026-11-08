package com.phiragen.core

import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.audio.SpectrumStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 程序合成的测试音频: 四四拍底鼓, 反拍军鼓, 八分踩镲, 和一个长持续音垫底 */
object TestAudio {
    const val SR = AnalysisConfig.SAMPLE_RATE

    class Synth(val samples: FloatArray, val bpm: Double, val phase: Double)

    fun synth(bpm: Double = 128.0, phase: Double = 0.37, durationSec: Double = 40.0, seed: Long = 1L): Synth {
        val rng = Random(seed)
        val n = (durationSec * SR).toInt()
        val x = FloatArray(n)
        val period = 60.0 / bpm
        fun add(t: Double, sig: FloatArray) {
            val i = (t * SR).toInt()
            for (k in sig.indices) if (i + k < n) x[i + k] += sig[k]
        }
        val kick = FloatArray((0.25 * SR).toInt())
        var ph = 0.0
        for (i in kick.indices) {
            val t = i.toDouble() / SR
            val f = 50 + 110 * exp(-t * 28)
            ph += 2 * PI * f / SR
            kick[i] = (0.9 * sin(ph) * exp(-t * 14)).toFloat()
        }
        val snare = FloatArray((0.18 * SR).toInt()) { (0.5 * rng.nextGaussian() * exp(-(it.toDouble() / SR) * 28)).toFloat() }
        val hat = FloatArray((0.05 * SR).toInt())
        var prev = 0.0
        for (i in hat.indices) {
            val g = rng.nextGaussian()
            hat[i] = (0.18 * (g - prev) * exp(-(i.toDouble() / SR) * 90)).toFloat()
            prev = g
        }
        fun pad(freq: Double, len: Double): FloatArray = FloatArray((len * SR).toInt()) {
            val t = it.toDouble() / SR
            val env = min(1.0, t / 0.01) * min(1.0, (len - t) / 0.05)
            (0.25 * sin(2 * PI * freq * t) * env).toFloat()
        }
        val beats = ((durationSec - phase) / period).toInt()
        for (b in 0 until beats) {
            val t = phase + b * period
            if (t > 6) {
                add(t, kick)
                if (b % 2 == 1) add(t, snare)
            }
            if (t > 3) {
                add(t, hat)
                add(t + period / 2, hat)
            }
            if (t > 14 && b % 4 == 0) add(t, pad(110.0, 3.5 * period))
        }
        var peak = 1e-9f
        for (i in 0 until n) {
            x[i] += (0.002 * rng.nextGaussian()).toFloat()
            peak = max(peak, kotlin.math.abs(x[i]))
        }
        for (i in 0 until n) x[i] = x[i] / (peak * 1.05f)
        return Synth(x, bpm, phase)
    }

    fun analyze(s: Synth): AnalysisResult {
        val stream = SpectrumStream()
        var off = 0
        while (off < s.samples.size) {
            val len = min(4096, s.samples.size - off)
            stream.push(s.samples.copyOfRange(off, off + len), len)
            off += len
        }
        return stream.finish()
    }
}
