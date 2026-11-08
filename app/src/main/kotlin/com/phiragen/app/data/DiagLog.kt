package com.phiragen.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志. 只记录事件, 阶段, 耗时和异常类型,
 * 不写入文件名, 路径, 曲名, 密钥或接口地址
 */
object DiagLog {
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        file = File(context.cacheDir, "diag.log")
    }

    @Synchronized
    fun i(tag: String, msg: String) = write("I", tag, msg, null)

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.writeText("")
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(' ').append(msg).append('\n')
            if (t != null) {
                sb.append("  ").append(t.javaClass.name).append('\n')
                for (el in t.stackTrace.take(12)) sb.append("    at ").append(el.toString()).append('\n')
            }
            f.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun read(): String = try {
        file?.takeIf { it.exists() }?.readText() ?: ""
    } catch (_: Exception) {
        ""
    }

    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
    }
}
