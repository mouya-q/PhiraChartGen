package com.phiragen.core.export

import com.phiragen.core.chart.Channel
import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartMeta
import com.phiragen.core.chart.Note
import com.phiragen.core.chart.NoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

private const val DEN = 960

internal fun beatTriple(beat: Double): JsonArray {
    var b = if (beat < 0) 0.0 else beat
    var whole = Math.floor(b).toInt()
    var num = Math.round((b - whole) * DEN).toInt()
    if (num >= DEN) { whole += 1; num = 0 }
    val g = gcd(num, DEN)
    return buildJsonArray {
        add(JsonPrimitive(whole))
        add(JsonPrimitive(num / g))
        add(JsonPrimitive(DEN / g))
    }
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun fmt(v: Double, digits: Int = 3): String = String.format(Locale.ROOT, "%.${digits}f", v)

// ------------------------------------------------------------------ RPE

object RpeExporter {
    private fun event(start: Double, end: Double, from: Double, to: Double, easing: Int): JsonObject = buildJsonObject {
        put("bezier", 0)
        putJsonArray("bezierPoints") { repeat(4) { add(JsonPrimitive(0.0)) } }
        put("easingLeft", 0.0)
        put("easingRight", 1.0)
        put("easingType", easing)
        put("end", to)
        put("endTime", beatTriple(end))
        put("linkgroup", 0)
        put("start", from)
        put("startTime", beatTriple(start))
    }

    private fun events(ch: Channel): JsonArray = JsonArray(ch.segs.map { event(it.start, it.end, it.from, it.to, it.easing) })

    private fun controls(key: String, value: Double): JsonArray = buildJsonArray {
        for (x in listOf(0.0, 9999999.0)) {
            add(buildJsonObject {
                put("easing", 1)
                put(key, value)
                put("x", x)
            })
        }
    }

    fun toJson(chart: Chart, meta: ChartMeta, assets: PackNames): JsonObject {
        val lead = 60.0 / chart.firstBeatSec
        return buildJsonObject {
            putJsonArray("BPMList") {
                add(buildJsonObject {
                    put("bpm", Math.round(lead * 1e6) / 1e6)
                    put("startTime", beatTriple(0.0))
                })
                add(buildJsonObject {
                    put("bpm", Math.round(chart.bpm * 1e6) / 1e6)
                    put("startTime", beatTriple(1.0))
                })
            }
            put("META", buildJsonObject {
                put("RPEVersion", 160)
                put("background", assets.illustration)
                put("charter", meta.charter)
                put("composer", meta.composer)
                put("id", "pcg-" + meta.title.filter { it.isLetterOrDigit() }.ifEmpty { "chart" })
                put("illustration", meta.illustrator)
                put("level", chart.levelText)
                put("name", meta.title)
                put("offset", 0)
                put("song", assets.music)
            })
            putJsonArray("judgeLineGroup") { add(JsonPrimitive("Default")) }
            putJsonArray("judgeLineList") {
                for (li in 0 until chart.lineCount) add(lineJson(chart, li))
            }
            put("multiLineString", (1..chart.lineCount).joinToString(" "))
            put("multiScale", 1.0)
            put("xybind", false)
        }
    }

    private fun lineJson(chart: Chart, li: Int): JsonObject {
        val t = chart.choreography.tracks[li]
        val endBeat = chart.choreography.endBeat
        val lineNotes = chart.notes.filter { it.line == li }
        return buildJsonObject {
            put("Group", 0)
            put("Name", "Line ${li + 1}")
            put("Texture", "line.png")
            putJsonArray("anchor") { add(JsonPrimitive(0.5)); add(JsonPrimitive(0.5)) }
            put("bpmfactor", 1.0)
            putJsonArray("eventLayers") {
                add(buildJsonObject {
                    putJsonArray("speedEvents") {
                        add(buildJsonObject {
                            put("end", chart.noteSpeed)
                            put("endTime", beatTriple(endBeat))
                            put("linkgroup", 0)
                            put("start", chart.noteSpeed)
                            put("startTime", beatTriple(0.0))
                        })
                    }
                    put("moveXEvents", events(t.x))
                    put("moveYEvents", events(t.y))
                    put("rotateEvents", events(t.rotation))
                    put("alphaEvents", events(t.alpha))
                })
            }
            put("extended", buildJsonObject {
                putJsonArray("inclineEvents") { add(event(0.0, 1.0, 0.0, 0.0, 0)) }
            })
            put("father", -1)
            put("isCover", 1)
            put("isGif", false)
            putJsonArray("notes") { for (n in lineNotes) add(noteJson(chart, n)) }
            put("numOfNotes", lineNotes.count { it.type != NoteType.HOLD })
            put("posControl", controls("pos", 1.0))
            put("sizeControl", controls("size", 1.0))
            put("skewControl", controls("skew", 0.0))
            put("yControl", controls("y", 1.0))
            put("alphaControl", controls("alpha", 1.0))
            put("zOrder", 0)
            put("rotateWithFather", true)
        }
    }

    private fun noteJson(chart: Chart, n: Note): JsonObject = buildJsonObject {
        put("above", 1)
        put("alpha", 255)
        put("startTime", beatTriple(chart.beatOf(n.time)))
        put("endTime", beatTriple(chart.beatOf(if (n.type == NoteType.HOLD) n.endTime else n.time)))
        put("isFake", 0)
        put("positionX", n.x)
        put("size", 1.0)
        put("speed", 1.0)
        put("type", n.type.id)
        put("visibleTime", 999999.0)
        put("yOffset", 0.0)
    }
}

/**
 * 官方 JSON 与 PEC 的事件只能表达线性变化 (PEC 的 cm 同时带 x 与 y),
 * 这里把多条通道合并到统一时间轴, 缓动段切成若干线性小段近似
 */
internal object Sampling {
    class Piece(val start: Double, val end: Double)

    fun pieces(vararg channels: Channel): List<Piece> {
        val marks = java.util.TreeSet<Double>()
        for (c in channels) for (s in c.segs) { marks.add(s.start); marks.add(s.end) }
        val list = marks.toList()
        val out = ArrayList<Piece>()
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            val varying = channels.any { c -> abs(c.at(a) - c.at(b)) > 1e-9 || c.segs.any { it.easing != 1 && it.start < b && it.end > a && it.from != it.to } }
            val count = if (varying) Math.ceil((b - a) * 1.5).toInt().coerceIn(2, 12) else 1
            for (k in 0 until count) out.add(Piece(a + (b - a) * k / count, a + (b - a) * (k + 1) / count))
        }
        return out
    }

    private fun abs(v: Double) = Math.abs(v)
}

// ------------------------------------------------------------------ Phigros 官方

object OfficialExporter {
    private const val FAR = 1_000_000_000
    private const val BEFORE = -999_999

    fun toJson(chart: Chart): JsonObject {
        val unit = chart.beatSec / 32.0
        val speed = chart.noteSpeed / 4.5
        fun u(beat: Double) = Math.round(chart.secOf(beat) / unit).toInt()
        fun fx(x: Double) = Math.round((0.5 + x / 1350.0) * 1e5) / 1e5
        fun fy(y: Double) = Math.round((y + 450.0) / 900.0 * 1e5) / 1e5
        return buildJsonObject {
            put("formatVersion", 3)
            put("offset", 0.0)
            put("numOfNotes", chart.noteCount)
            putJsonArray("judgeLineList") {
                for (li in 0 until chart.lineCount) {
                    val t = chart.choreography.tracks[li]
                    add(buildJsonObject {
                        put("bpm", Math.round(chart.bpm * 1e6) / 1e6)
                        putJsonArray("notesAbove") {
                            for (n in chart.notes.filter { it.line == li }) {
                                add(buildJsonObject {
                                    put("type", n.type.id)
                                    put("time", Math.round(n.time / unit).toInt())
                                    val hold = if (n.type == NoteType.HOLD) (n.endTime - n.time) / unit else 0.0
                                    put("holdTime", Math.round(hold * 1000.0) / 1000.0)
                                    put("positionX", Math.round(n.x / 75.9375 * 10000.0) / 10000.0)
                                    put("speed", 1.0)
                                    put("floorPosition", Math.round(speed * n.time * 10000.0) / 10000.0)
                                })
                            }
                        }
                        putJsonArray("notesBelow") {}
                        putJsonArray("speedEvents") {
                            add(buildJsonObject {
                                put("startTime", 0)
                                put("endTime", FAR)
                                put("value", Math.round(speed * 1e6) / 1e6)
                                put("floorPosition", 0.0)
                            })
                        }
                        putJsonArray("judgeLineMoveEvents") {
                            val ps = Sampling.pieces(t.x, t.y)
                            fun move(s: Int, e: Int, x0: Double, x1: Double, y0: Double, y1: Double) = add(buildJsonObject {
                                put("startTime", s); put("endTime", e)
                                put("start", x0); put("end", x1)
                                put("start2", y0); put("end2", y1)
                            })
                            move(BEFORE, 0, fx(t.x.at(0.0)), fx(t.x.at(0.0)), fy(t.y.at(0.0)), fy(t.y.at(0.0)))
                            for (p in ps) move(u(p.start), u(p.end), fx(t.x.at(p.start)), fx(t.x.at(p.end)), fy(t.y.at(p.start)), fy(t.y.at(p.end)))
                            val last = chart.choreography.endBeat
                            move(u(last), FAR, fx(t.x.at(last)), fx(t.x.at(last)), fy(t.y.at(last)), fy(t.y.at(last)))
                        }
                        putJsonArray("judgeLineRotateEvents") {
                            fun rot(s: Int, e: Int, a0: Double, a1: Double) = add(buildJsonObject {
                                put("startTime", s); put("endTime", e)
                                put("start", Math.round(a0 * 1000.0) / 1000.0); put("end", Math.round(a1 * 1000.0) / 1000.0)
                            })
                            rot(BEFORE, 0, t.rotation.at(0.0), t.rotation.at(0.0))
                            for (p in Sampling.pieces(t.rotation)) rot(u(p.start), u(p.end), t.rotation.at(p.start), t.rotation.at(p.end))
                            val last = chart.choreography.endBeat
                            rot(u(last), FAR, t.rotation.at(last), t.rotation.at(last))
                        }
                        putJsonArray("judgeLineDisappearEvents") {
                            fun dis(s: Int, e: Int, a0: Double, a1: Double) = add(buildJsonObject {
                                put("startTime", s); put("endTime", e)
                                put("start", Math.round(a0 / 255.0 * 1000.0) / 1000.0); put("end", Math.round(a1 / 255.0 * 1000.0) / 1000.0)
                            })
                            dis(BEFORE, 0, t.alpha.at(0.0), t.alpha.at(0.0))
                            for (p in Sampling.pieces(t.alpha)) dis(u(p.start), u(p.end), t.alpha.at(p.start), t.alpha.at(p.end))
                            val last = chart.choreography.endBeat
                            dis(u(last), FAR, t.alpha.at(last), t.alpha.at(last))
                        }
                    })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PEC

object PecExporter {
    private fun px(x: Double) = x * 1024.0 / 675.0
    private fun py(y: Double) = y * 700.0 / 450.0

    fun toText(chart: Chart): String {
        val sb = StringBuilder()
        sb.append("0\n")
        sb.append("bp 0.000 ").append(fmt(60.0 / chart.firstBeatSec)).append('\n')
        sb.append("bp 1.000 ").append(fmt(chart.bpm)).append('\n')
        for (li in 0 until chart.lineCount) {
            val t = chart.choreography.tracks[li]
            sb.append("cv $li 0.000 ").append(fmt(chart.noteSpeed)).append('\n')
            sb.append("cp $li 0.000 ").append(fmt(px(t.x.at(0.0)))).append(' ').append(fmt(py(t.y.at(0.0)))).append('\n')
            sb.append("ca $li 0.000 ").append(fmt(t.alpha.at(0.0), 0)).append('\n')
            sb.append("cd $li 0.000 ").append(fmt(-t.rotation.at(0.0))).append('\n')
            for (p in Sampling.pieces(t.x, t.y)) {
                sb.append("cm $li ").append(fmt(p.start)).append(' ').append(fmt(p.end)).append(' ')
                    .append(fmt(px(t.x.at(p.end)))).append(' ').append(fmt(py(t.y.at(p.end)))).append(" 1\n")
            }
            for (p in Sampling.pieces(t.rotation)) {
                sb.append("cr $li ").append(fmt(p.start)).append(' ').append(fmt(p.end)).append(' ')
                    .append(fmt(-t.rotation.at(p.end))).append(" 1\n")
            }
            for (p in Sampling.pieces(t.alpha)) {
                if (Math.abs(t.alpha.at(p.start) - t.alpha.at(p.end)) < 1e-9) continue
                sb.append("cf $li ").append(fmt(p.start)).append(' ').append(fmt(p.end)).append(' ')
                    .append(fmt(t.alpha.at(p.end), 0)).append('\n')
            }
        }
        for (n in chart.notes) {
            val x = px(n.x)
            val b = chart.beatOf(n.time)
            if (n.type == NoteType.HOLD) {
                sb.append("n2 ${n.line} ").append(fmt(b)).append(' ').append(fmt(chart.beatOf(n.endTime)))
                    .append(' ').append(fmt(x)).append(" 1 0\n")
            } else {
                sb.append("n${n.type.id} ${n.line} ").append(fmt(b)).append(' ').append(fmt(x)).append(" 1 0\n")
            }
            sb.append("# 1.00\n& 1.00\n")
        }
        return sb.toString()
    }
}

// ------------------------------------------------------------------ info.yml

/** 谱面包内的文件名 */
data class PackNames(val music: String, val illustration: String, val chart: String)

object InfoYml {
    private fun q(s: String): String {
        val clean = s.replace("\r", " ").replace("\n", " ")
        return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun write(chart: Chart, meta: ChartMeta, names: PackNames): String {
        val sb = StringBuilder()
        sb.append("name: ").append(q(meta.title)).append('\n')
        sb.append("difficulty: ").append(fmt(chart.difficultyValue, 1)).append('\n')
        sb.append("level: ").append(q(chart.levelText)).append('\n')
        sb.append("charter: ").append(q(meta.charter)).append('\n')
        sb.append("composer: ").append(q(meta.composer)).append('\n')
        sb.append("illustrator: ").append(q(meta.illustrator)).append('\n')
        sb.append("chart: ").append(names.chart).append('\n')
        sb.append("music: ").append(names.music).append('\n')
        sb.append("illustration: ").append(names.illustration).append('\n')
        sb.append("previewStart: ").append(fmt(meta.previewStartSec, 2)).append('\n')
        sb.append("aspectRatio: 1.7777778\n")
        sb.append("backgroundDim: 0.6\n")
        sb.append("lineLength: 6.0\n")
        sb.append("offset: ").append(fmt(meta.offsetSec)).append('\n')
        sb.append("tags: []\n")
        sb.append("intro: ").append(q(meta.intro)).append('\n')
        sb.append("holdPartialCover: false\n")
        return sb.toString()
    }
}

object ChartSerializer {
    private val json = kotlinx.serialization.json.Json { prettyPrint = false }

    fun serialize(chart: Chart, meta: ChartMeta, format: ChartFormat, names: PackNames): String = when (format) {
        ChartFormat.RPE -> json.encodeToString(JsonObject.serializer(), RpeExporter.toJson(chart, meta, names))
        ChartFormat.OFFICIAL -> json.encodeToString(JsonObject.serializer(), OfficialExporter.toJson(chart))
        ChartFormat.PEC -> PecExporter.toText(chart)
    }
}
