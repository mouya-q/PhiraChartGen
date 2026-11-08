package com.phiragen.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.zip.ZipInputStream

class ValidationReport(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * 谱面包兼容性校验. 规则来自 Phira 官方文档中的谱面包结构, info.yml 字段以及三种谱面格式
 * 通过校验说明结构合规, 不等同于在 Phira 内实机验证
 */
object PackValidator {
    private val required = listOf(
        "name", "difficulty", "level", "charter", "composer", "illustrator", "chart", "music",
        "illustration", "previewStart", "aspectRatio", "backgroundDim", "lineLength", "offset",
    )

    fun validate(zipStream: InputStream): ValidationReport {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val files = HashMap<String, ByteArray?>()
        try {
            ZipInputStream(zipStream).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory || name.contains('/')) errors.add("压缩包内必须直接放文件, 不能含文件夹")
                    // 只保留文本类小文件的内容, 音频与图片只记录存在
                    val keep = name == "info.yml" || name.endsWith(".json") || name.endsWith(".pec")
                    files[name] = if (keep) z.readBytes() else null
                }
            }
        } catch (e: Exception) {
            return ValidationReport(listOf("无法读取压缩包: ${e.message}"), emptyList())
        }
        val infoBytes = files["info.yml"] ?: return ValidationReport(errors + "缺少 info.yml", warnings)
        val info = parseYml(String(infoBytes, Charsets.UTF_8))
        for (k in required) if (k !in info) errors.add("info.yml 缺少 $k")
        if (errors.isNotEmpty()) return ValidationReport(errors, warnings)
        for (k in listOf("chart", "music", "illustration")) {
            if (info[k] !in files.keys) errors.add("info.yml 指向的文件不存在: ${info[k]}")
        }
        val music = info.getValue("music").lowercase()
        if (!(music.endsWith(".mp3") || music.endsWith(".ogg") || music.endsWith(".wav"))) errors.add("音频格式需为 mp3 / ogg / wav")
        if (music.endsWith(".mp3")) warnings.add("mp3 可能存在不可预测的延迟, 必要时用 offset 校准")
        if (info.getValue("difficulty").toDoubleOrNull() == null || info.getValue("offset").toDoubleOrNull() == null) {
            errors.add("difficulty / offset 必须是数字")
        }
        if (errors.isNotEmpty()) return ValidationReport(errors, warnings)

        val chartName = info.getValue("chart")
        val raw = files[chartName]?.let { String(it, Charsets.UTF_8) }
        if (raw == null) {
            errors.add("无法读取谱面文件")
        } else if (chartName.lowercase().endsWith(".pec")) {
            checkPec(raw, errors)
        } else {
            val root = try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                errors.add("谱面 JSON 解析失败: ${e.message}")
                null
            }
            if (root != null) {
                when {
                    "judgeLineList" in root && "BPMList" in root -> checkRpe(root, errors, warnings)
                    "formatVersion" in root -> checkOfficial(root, errors)
                    else -> errors.add("无法识别谱面格式")
                }
            }
        }
        return ValidationReport(errors, warnings)
    }

    private fun parseYml(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.trimStart().startsWith("#") || !line.contains(':')) continue
            val idx = line.indexOf(':')
            var v = line.substring(idx + 1).trim()
            if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
                v = v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            out[line.substring(0, idx).trim()] = v
        }
        return out
    }

    private fun beatValue(e: JsonElement?, errors: MutableList<String>, where: String): Double {
        val arr = (e as? JsonArray)
        val ints = arr?.map { (it as? JsonPrimitive)?.intOrNull }
        if (arr == null || arr.size != 3 || ints!!.any { it == null }) {
            errors.add("$where: 拍数必须是 3 个整数")
            return 0.0
        }
        val a = ints[0]!!; val b = ints[1]!!; val c = ints[2]!!
        if (c <= 0 || b < 0 || a < 0) {
            errors.add("$where: 拍数取值非法")
            return 0.0
        }
        return a + b.toDouble() / c
    }

    private fun checkRpe(root: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        val bpmList = root["BPMList"]!!.jsonArray
        if (bpmList.isEmpty()) { errors.add("BPMList 为空"); return }
        var prev = -1.0
        for ((i, b) in bpmList.withIndex()) {
            val o = b.jsonObject
            val st = beatValue(o["startTime"], errors, "BPMList[$i]")
            if ((o["bpm"]?.jsonPrimitive?.doubleOrNull ?: 0.0) <= 0.0) errors.add("BPMList[$i] bpm 必须大于 0")
            if (st < prev) errors.add("BPMList 未按时间排序")
            prev = st
        }
        if (beatValue(bpmList[0].jsonObject["startTime"], errors, "BPMList[0]") != 0.0) errors.add("BPMList 首项必须从第 0 拍开始")
        var total = 0
        for ((li, lineEl) in root["judgeLineList"]!!.jsonArray.withIndex()) {
            val line = lineEl.jsonObject
            val w = "line$li"
            val layers = line["eventLayers"] as? JsonArray
            if (layers == null || layers.isEmpty()) errors.add("$w 没有事件层")
            layers?.forEachIndexed { yi, layerEl ->
                val layer = layerEl.jsonObject
                for (name in listOf("speedEvents", "moveXEvents", "moveYEvents", "rotateEvents", "alphaEvents")) {
                    val evs = layer[name] as? JsonArray
                    if (evs == null || evs.isEmpty()) { errors.add("$w layer$yi 缺少 $name"); continue }
                    var lastEnd: Double? = null
                    for ((ei, ev) in evs.withIndex()) {
                        val o = ev.jsonObject
                        val s = beatValue(o["startTime"], errors, "$w.$name[$ei]")
                        val t = beatValue(o["endTime"], errors, "$w.$name[$ei]")
                        if (t < s) errors.add("$w.$name[$ei] 结束早于开始")
                        if (lastEnd != null && Math.abs(s - lastEnd) > 1e-6) warnings.add("$w.$name[$ei] 与上一事件不连续")
                        lastEnd = t
                    }
                }
            }
            for ((ni, nEl) in (line["notes"] as? JsonArray ?: JsonArray(emptyList())).withIndex()) {
                val n = nEl.jsonObject
                val s = beatValue(n["startTime"], errors, "$w.notes[$ni]")
                val t = beatValue(n["endTime"], errors, "$w.notes[$ni]")
                val type = n["type"]?.jsonPrimitive?.intOrNull
                if (type == null || type !in 1..4) errors.add("$w.notes[$ni] type 非法")
                if (type == 2 && t <= s) errors.add("$w.notes[$ni] Hold 时长必须大于 0")
                if (type != 2 && Math.abs(t - s) > 1e-9) errors.add("$w.notes[$ni] 非 Hold 的结束时间必须等于开始时间")
                if (Math.abs(n["positionX"]?.jsonPrimitive?.doubleOrNull ?: 0.0) > 675.0) warnings.add("$w.notes[$ni] positionX 超出屏幕")
                total++
            }
        }
        if (total == 0) errors.add("谱面没有任何音符")
    }

    private fun checkOfficial(root: JsonObject, errors: MutableList<String>) {
        val fv = root["formatVersion"]?.jsonPrimitive?.intOrNull
        if (fv != 1 && fv != 3) errors.add("formatVersion 应为 1 或 3")
        val lines = root["judgeLineList"] as? JsonArray
        if (lines == null || lines.isEmpty()) { errors.add("judgeLineList 为空"); return }
        var total = 0
        for ((li, lineEl) in lines.withIndex()) {
            val line = lineEl.jsonObject
            for (k in listOf("bpm", "notesAbove", "notesBelow", "speedEvents", "judgeLineMoveEvents", "judgeLineRotateEvents", "judgeLineDisappearEvents")) {
                if (k !in line) errors.add("line$li 缺少 $k")
            }
            if ((line["bpm"]?.jsonPrimitive?.doubleOrNull ?: 0.0) <= 0.0) errors.add("line$li bpm 非法")
            for (k in listOf("notesAbove", "notesBelow")) {
                for ((ni, nEl) in (line[k] as? JsonArray ?: JsonArray(emptyList())).withIndex()) {
                    val n = nEl.jsonObject
                    val type = n["type"]?.jsonPrimitive?.intOrNull
                    if (type == null || type !in 1..4) errors.add("line$li.$k[$ni] type 非法")
                    if (type == 2 && (n["holdTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0) <= 0.0) errors.add("line$li.$k[$ni] Hold 时长必须大于 0")
                    if ((n["time"]?.jsonPrimitive?.intOrNull ?: -1) < 0) errors.add("line$li.$k[$ni] 时间为负")
                    total++
                }
            }
        }
        if (total == 0) errors.add("谱面没有任何音符")
    }

    private fun checkPec(text: String, errors: MutableList<String>) {
        val rows = text.lines()
        if (rows.isEmpty() || rows[0].trim().toIntOrNull() == null) errors.add("PEC 第一行必须是整数偏移 (毫秒)")
        if (rows.none { it.startsWith("bp ") }) errors.add("PEC 缺少 bp 行")
        var notes = 0
        var i = 1
        val noteRe = Regex("n[1-4]")
        while (i < rows.size) {
            val r = rows[i].trim().split(Regex("\\s+"))
            if (r.isNotEmpty() && noteRe.matches(r[0])) {
                val need = if (r[0] == "n2") 7 else 6
                if (r.size != need) errors.add("PEC 第 ${i + 1} 行音符字段数量错误")
                if (i + 2 >= rows.size || !rows[i + 1].startsWith("#") || !rows[i + 2].startsWith("&")) {
                    errors.add("PEC 第 ${i + 1} 行音符缺少 # 或 & 行")
                }
                notes++
                i += 3
                continue
            }
            i++
        }
        if (notes == 0) errors.add("谱面没有任何音符")
    }
}
