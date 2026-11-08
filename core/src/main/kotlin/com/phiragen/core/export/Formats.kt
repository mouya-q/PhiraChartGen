package com.phiragen.core.export

import com.phiragen.core.chart.Chart
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.ChartMeta
import com.phiragen.core.chart.Difficulty
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

/** 判定线的运动设计, 三种导出格式共用 */
class LineMotion(chart: Chart, line: Int) {
    class Seg(val start: Double, val end: Double, val from: Double, val to: Double)

    val baseY: Double = when {
        chart.profile.lineCount == 1 -> -150.0
        line == 0 -> -110.0
        else -> -270.0
    }
    val endBeat: Double = chart.beatOf(chart.durationSec) + 8.0
    val rotation = ArrayList<Seg>()
    val bob = ArrayList<Seg>()

    init {
        val amp = chart.profile.swayDegrees
        if (amp > 0) {
            var sign = if (line == 0) 1.0 else -1.0
            var b = 1.0
            while (b < endBeat) {
                rotation.add(Seg(b, b + 8, sign * amp, -sign * amp))
                sign = -sign
                b += 8.0
            }
        }
        if (chart.profile.difficulty == Difficulty.IN || chart.profile.difficulty == Difficulty.AT) {
            val amount = if (chart.profile.difficulty == Difficulty.IN) 25.0 else 45.0
            var s = 1.0
            var b = 1.0
            while (b < endBeat) {
                bob.add(Seg(b, b + 16, baseY + s * amount, baseY - s * amount))
                s = -s
                b += 16.0
            }
        }
    }

    val initialY: Double get() = if (bob.isNotEmpty()) bob.first().from else baseY
    val initialRotation: Double get() = if (rotation.isNotEmpty()) rotation.first().from else 0.0
}

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

    private fun constant(value: Double, end: Double): JsonArray = JsonArray(listOf(event(0.0, end, value, value, 1)))

    private fun contiguous(segs: List<LineMotion.Seg>, endBeat: Double, fallback: Double): JsonArray {
        if (segs.isEmpty()) return constant(fallback, endBeat)
        val out = ArrayList<JsonElement>()
        var cur = 0.0
        var last = segs.first().from
        for (s in segs) {
            if (s.start > cur) out.add(event(cur, s.start, last, last, 1))
            out.add(event(s.start, s.end, s.from, s.to, 6))
            cur = s.end
            last = s.to
        }
        if (cur < endBeat) out.add(event(cur, endBeat, last, last, 1))
        return JsonArray(out)
    }

    private fun controls(key: String, value: Double, xKey: String = "x"): JsonArray = buildJsonArray {
        for (x in listOf(0.0, 9999999.0)) {
            add(buildJsonObject {
                put("easing", 1)
                put(key, value)
                put(xKey, x)
            })
        }
    }

    fun toJson(chart: Chart, meta: ChartMeta, assets: PackNames): JsonObject {
        val speed = chart.noteSpeed
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
                for (li in 0 until chart.profile.lineCount) add(lineJson(chart, li, speed))
            }
            put("multiLineString", (1..chart.profile.lineCount).joinToString(" "))
            put("multiScale", 1.0)
            put("xybind", false)
        }
    }

    private fun lineJson(chart: Chart, li: Int, speed: Double): JsonObject {
        val m = LineMotion(chart, li)
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
                            put("end", speed)
                            put("endTime", beatTriple(m.endBeat))
                            put("linkgroup", 0)
                            put("start", speed)
                            put("startTime", beatTriple(0.0))
                        })
                    }
                    put("moveXEvents", constant(0.0, m.endBeat))
                    put("moveYEvents", contiguous(m.bob, m.endBeat, m.baseY))
                    put("rotateEvents", contiguous(m.rotation, m.endBeat, 0.0))
                    put("alphaEvents", constant(255.0, m.endBeat))
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

// ------------------------------------------------------------------ Phigros 官方

object OfficialExporter {
    private const val FAR = 1_000_000_000
    private const val BEFORE = -999_999

    fun toJson(chart: Chart): JsonObject {
        val unit = chart.beatSec / 32.0
        val speed = chart.noteSpeed / 4.5
        fun u(sec: Double) = Math.round(sec / unit).toInt()
        fun ypos(y: Double) = (y + 450.0) / 900.0
        return buildJsonObject {
            put("formatVersion", 3)
            put("offset", 0.0)
            put("numOfNotes", chart.noteCount)
            putJsonArray("judgeLineList") {
                for (li in 0 until chart.profile.lineCount) {
                    val m = LineMotion(chart, li)
                    add(buildJsonObject {
                        put("bpm", Math.round(chart.bpm * 1e6) / 1e6)
                        putJsonArray("notesAbove") {
                            for (n in chart.notes.filter { it.line == li }) {
                                add(buildJsonObject {
                                    put("type", n.type.id)
                                    put("time", u(n.time))
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
                            var curU = BEFORE
                            var curY = ypos(m.initialY)
                            fun move(s: Int, e: Int, y0: Double, y1: Double) = add(buildJsonObject {
                                put("startTime", s); put("endTime", e)
                                put("start", 0.5); put("end", 0.5)
                                put("start2", y0); put("end2", y1)
                            })
                            for (seg in m.bob) {
                                val su = u(chart.secOf(seg.start))
                                val eu = u(chart.secOf(seg.end))
                                if (su > curU) move(curU, su, curY, curY)
                                move(su, eu, ypos(seg.from), ypos(seg.to))
                                curU = eu; curY = ypos(seg.to)
                            }
                            move(curU, FAR, curY, curY)
                        }
                        putJsonArray("judgeLineRotateEvents") {
                            var curU = BEFORE
                            var curA = m.initialRotation
                            fun rot(s: Int, e: Int, a0: Double, a1: Double) = add(buildJsonObject {
                                put("startTime", s); put("endTime", e)
                                put("start", a0); put("end", a1)
                            })
                            for (seg in m.rotation) {
                                val su = u(chart.secOf(seg.start))
                                val eu = u(chart.secOf(seg.end))
                                if (su > curU) rot(curU, su, curA, curA)
                                rot(su, eu, seg.from, seg.to)
                                curU = eu; curA = seg.to
                            }
                            rot(curU, FAR, curA, curA)
                        }
                        putJsonArray("judgeLineDisappearEvents") {
                            add(buildJsonObject {
                                put("startTime", BEFORE); put("endTime", FAR)
                                put("start", 1.0); put("end", 1.0)
                            })
                        }
                    })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PEC

object PecExporter {
    fun toText(chart: Chart): String {
        val sb = StringBuilder()
        val speed = chart.noteSpeed
        sb.append("0\n")
        sb.append("bp 0.000 ").append(fmt(60.0 / chart.firstBeatSec)).append('\n')
        sb.append("bp 1.000 ").append(fmt(chart.bpm)).append('\n')
        for (li in 0 until chart.profile.lineCount) {
            val m = LineMotion(chart, li)
            sb.append("cv $li 0.000 ").append(fmt(speed)).append('\n')
            sb.append("cp $li 0.000 0.000 ").append(fmt(m.initialY * 700.0 / 450.0)).append('\n')
            sb.append("ca $li 0.000 255\n")
            sb.append("cd $li 0.000 ").append(fmt(-m.initialRotation)).append('\n')
            for (s in m.bob) {
                sb.append("cm $li ").append(fmt(s.start)).append(' ').append(fmt(s.end))
                    .append(" 0.000 ").append(fmt(s.to * 700.0 / 450.0)).append(" 6\n")
            }
            for (s in m.rotation) {
                sb.append("cr $li ").append(fmt(s.start)).append(' ').append(fmt(s.end))
                    .append(' ').append(fmt(-s.to)).append(" 6\n")
            }
        }
        for (n in chart.notes) {
            val x = n.x * 1024.0 / 675.0
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
