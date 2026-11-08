package com.phiragen.core.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 分析结果的二进制缓存, 用于进程被回收后恢复, 以及切换难度时免重复分析 */
object AnalysisCodec {
    private const val MAGIC = 0x50434741 // PCGA
    private const val VERSION = 1

    fun write(out: OutputStream, a: AnalysisResult) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeDouble(a.durationSec)
        d.writeDouble(a.bpm)
        d.writeDouble(a.phase)
        for (arr in listOf(a.env, a.low, a.mid, a.high, a.rms, a.centroidHz, a.lowMag)) {
            d.writeInt(arr.size)
            for (v in arr) d.writeFloat(v)
        }
        d.flush()
    }

    fun read(input: InputStream): AnalysisResult {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC) throw AnalysisException("缓存文件已损坏")
        if (d.readInt() != VERSION) throw AnalysisException("缓存版本不兼容")
        val duration = d.readDouble()
        val bpm = d.readDouble()
        val phase = d.readDouble()
        fun arr(): FloatArray {
            val n = d.readInt()
            if (n < 0 || n > 50_000_000) throw AnalysisException("缓存文件已损坏")
            return FloatArray(n) { d.readFloat() }
        }
        val env = arr(); val low = arr(); val mid = arr(); val high = arr()
        val rms = arr(); val cen = arr(); val mag = arr()
        return AnalysisResult(duration, bpm, phase, env, low, mid, high, rms, cen, mag)
    }
}
