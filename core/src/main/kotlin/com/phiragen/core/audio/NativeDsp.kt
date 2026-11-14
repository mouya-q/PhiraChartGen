package com.phiragen.core.audio

/** 频谱分析的统一入口: Kotlin 实现与原生实现都满足它 */
interface SpectrumSink {
    /** 输入 22.05 kHz 单声道 PCM */
    fun push(x: FloatArray, len: Int)

    fun finish(): AnalysisResult

    /** 实际使用的引擎名称, 用于诊断 */
    val engine: String
}

/**
 * Rust 原生分析引擎的 JNI 绑定 (native/pcg-dsp).
 * 库文件不存在或加载失败时 [isAvailable] 为 false, 调用方应使用 Kotlin 实现
 */
object NativeDsp {
    private var loaded: Boolean? = null

    @Synchronized
    fun isAvailable(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("pcg_dsp")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativePush(handle: Long, samples: FloatArray, len: Int)

    /** 0 成功, 1 音频太短, 其他为内部错误 */
    @JvmStatic external fun nativeFinish(handle: Long): Int

    /** [时长, 速度, 相位, 帧数] */
    @JvmStatic external fun nativeMeta(handle: Long): DoubleArray?

    /** which: 0 env, 1 low, 2 mid, 3 high, 4 rms, 5 centroid, 6 lowMag */
    @JvmStatic external fun nativeArray(handle: Long, which: Int): FloatArray?
    @JvmStatic external fun nativeDestroy(handle: Long)
}

/** 原生引擎的流式封装, 结束后把结果数组复制回 JVM */
class NativeSpectrumStream : SpectrumSink {
    private var handle: Long = NativeDsp.nativeCreate()
    override val engine: String = "native"

    init {
        if (handle == 0L) throw AnalysisException("原生分析引擎初始化失败")
    }

    override fun push(x: FloatArray, len: Int) {
        check(handle != 0L) { "分析已结束" }
        NativeDsp.nativePush(handle, x, len)
    }

    override fun finish(): AnalysisResult {
        val h = handle
        try {
            when (val code = NativeDsp.nativeFinish(h)) {
                0 -> Unit
                1 -> throw AnalysisException("音频太短, 至少需要约 1 秒")
                else -> throw AnalysisException("原生分析引擎出错 ($code)")
            }
            val meta = NativeDsp.nativeMeta(h) ?: throw AnalysisException("原生分析引擎没有返回结果")
            fun arr(which: Int) = NativeDsp.nativeArray(h, which) ?: throw AnalysisException("原生分析引擎没有返回结果")
            return AnalysisResult(
                durationSec = meta[0],
                bpm = meta[1],
                phase = meta[2],
                env = arr(0),
                low = arr(1),
                mid = arr(2),
                high = arr(3),
                rms = arr(4),
                centroidHz = arr(5),
                lowMag = arr(6),
            )
        } finally {
            NativeDsp.nativeDestroy(h)
            handle = 0L
        }
    }
}

object AnalyzerFactory {
    /** 优先使用原生引擎, 不可用或初始化失败时回退到 Kotlin 实现 */
    fun create(preferNative: Boolean): SpectrumSink {
        if (preferNative && NativeDsp.isAvailable()) {
            try {
                return NativeSpectrumStream()
            } catch (e: Throwable) {
                // 加载成功但初始化失败, 同样回退
            }
        }
        return SpectrumStream()
    }
}
