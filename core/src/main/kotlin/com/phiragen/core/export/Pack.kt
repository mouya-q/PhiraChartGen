package com.phiragen.core.export

import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartMeta
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 谱面包所需素材. 音频以流提供, 分块写入不整体读入内存 */
class PackAssets(
    /** 带扩展名的音频文件名, 只能是 mp3 / ogg / wav */
    val musicName: String,
    val openMusic: () -> InputStream,
    val illustrationPng: ByteArray,
)

object PackBuilder {
    const val ILLUSTRATION_NAME = "background.png"

    fun write(out: OutputStream, chart: Chart, meta: ChartMeta, format: ChartFormat, assets: PackAssets) {
        val names = PackNames(assets.musicName, ILLUSTRATION_NAME, format.chartFileName)
        val chartText = ChartSerializer.serialize(chart, meta, format, names)
        val zip = ZipOutputStream(out)
        zip.use { z ->
            z.putNextEntry(ZipEntry("info.yml"))
            z.write(InfoYml.write(chart, meta, names).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry(names.chart))
            z.write(chartText.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry(names.music))
            assets.openMusic().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    z.write(buf, 0, n)
                }
            }
            z.closeEntry()
            z.putNextEntry(ZipEntry(names.illustration))
            z.write(assets.illustrationPng)
            z.closeEntry()
        }
    }

    /** 清理文件名中不适合出现在文件系统里的字符 */
    fun safeFileName(title: String, suffix: String): String {
        val base = title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().ifEmpty { "chart" }
        return "$base-$suffix.zip"
    }
}
