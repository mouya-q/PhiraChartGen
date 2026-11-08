package com.phiragen.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** 生成 Phira 需要的插图, 优先使用音频内嵌封面, 否则按曲名生成渐变加波形图 */
object CoverArt {
    private const val W = 1280
    private const val H = 720

    fun render(title: String, embedded: Bitmap?, energy: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (embedded != null) {
            val scale = max(W.toFloat() / embedded.width, H.toFloat() / embedded.height)
            val w = embedded.width * scale
            val h = embedded.height * scale
            c.drawBitmap(embedded, null, RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
            val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
            val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.7f, 0.22f))
            val p = Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
            drawWave(c, energy)
        }
        val shade = Paint().apply {
            shader = LinearGradient(0f, H * 0.45f, 0f, H.toFloat(), 0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), shade)
        return bmp
    }

    private fun drawWave(c: Canvas, energy: FloatArray) {
        if (energy.isEmpty()) return
        val bars = 96
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        val bw = W.toFloat() / bars
        val per = max(1, energy.size / bars)
        var peak = 1e-6f
        for (v in energy) peak = max(peak, v)
        for (i in 0 until bars) {
            var s = 0f
            var cnt = 0
            for (k in i * per until min(energy.size, (i + 1) * per)) { s += energy[k]; cnt++ }
            val v = if (cnt > 0) s / cnt / peak else 0f
            val h = 24f + v * 260f
            c.drawRoundRect(RectF(i * bw + 4, H / 2f - h / 2, (i + 1) * bw - 4, H / 2f + h / 2), 8f, 8f, paint)
        }
    }

    fun toPng(b: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
