package com.phiragen.core.chart

enum class NoteType(val id: Int) { TAP(1), HOLD(2), FLICK(3), DRAG(4) }

enum class Difficulty(val key: String) { EZ("EZ"), HD("HD"), IN("IN"), AT("AT") }

/** 单个音符, 时间均为音频时间轴上的秒 */
data class Note(
    val time: Double,
    val endTime: Double,
    val type: NoteType,
    /** 相对判定线中心的横向坐标, 单位与 RPE 一致 (屏幕宽 1350) */
    val x: Double,
    val line: Int,
)

/** 难度档位参数 */
data class DifficultyProfile(
    val difficulty: Difficulty,
    /** 目标每秒音符数 */
    val nps: Double,
    val minGapSec: Double,
    /** 每拍细分数, 用于网格吸附 */
    val division: Int,
    val lanes: List<Double>,
    val chordRatio: Double,
    val holdRatio: Double,
    val flickRatio: Double,
    val dragRatio: Double,
    val lineCount: Int,
    /** 判定线摆动角度 */
    val swayDegrees: Double,
    /** 起音检测阈值, 越低候选越多 */
    val peakThreshold: Double,
) {
    companion object {
        val EZ = DifficultyProfile(Difficulty.EZ, 1.4, 0.38, 2, listOf(-360.0, 0.0, 360.0), 0.0, 0.12, 0.0, 0.0, 1, 0.0, 0.14)
        val HD = DifficultyProfile(Difficulty.HD, 2.6, 0.22, 2, listOf(-420.0, -140.0, 140.0, 420.0), 0.0, 0.15, 0.05, 0.0, 1, 2.5, 0.10)
        val IN = DifficultyProfile(Difficulty.IN, 4.4, 0.125, 4, listOf(-540.0, -270.0, 0.0, 270.0, 540.0), 0.08, 0.12, 0.07, 0.15, 2, 3.0, 0.07)
        val AT = DifficultyProfile(Difficulty.AT, 6.4, 0.09, 4, listOf(-540.0, -270.0, 0.0, 270.0, 540.0), 0.15, 0.10, 0.09, 0.25, 2, 5.0, 0.05)

        fun of(d: Difficulty): DifficultyProfile = when (d) {
            Difficulty.EZ -> EZ
            Difficulty.HD -> HD
            Difficulty.IN -> IN
            Difficulty.AT -> AT
        }
    }
}

/** 速度, 单位与 RPE 相同, 10 为默认值 */
const val DEFAULT_NOTE_SPEED = 10.0

/** 生成完成的谱面, 与具体导出格式无关 */
class Chart(
    val profile: DifficultyProfile,
    val notes: List<Note>,
    val bpm: Double,
    /** 前导节拍长度, 第一拍对齐到音乐的该时刻 (秒), 大于等于 0.02 */
    val firstBeatSec: Double,
    val durationSec: Double,
    /** 内部难度数值, 写入 info.yml 的 difficulty */
    val difficultyValue: Double,
    val level: Int,
    val noteSpeed: Double = DEFAULT_NOTE_SPEED,
) {
    val beatSec: Double get() = 60.0 / bpm
    val levelText: String get() = "${profile.difficulty.key} Lv.$level"

    /** 秒转为谱面拍数, 第 0 到第 1 拍是长度为 firstBeatSec 的前导拍 */
    fun beatOf(sec: Double): Double =
        if (sec < firstBeatSec) sec / firstBeatSec else 1.0 + (sec - firstBeatSec) / beatSec

    /** 谱面拍数转秒, 与 beatOf 互逆 */
    fun secOf(beat: Double): Double =
        if (beat < 1.0) beat * firstBeatSec else firstBeatSec + (beat - 1.0) * beatSec

    val noteCount: Int get() = notes.size
    val holdCount: Int get() = notes.count { it.type == NoteType.HOLD }
}

/** 谱面包元数据 */
data class ChartMeta(
    val title: String,
    val composer: String,
    val charter: String = "PhiraChartGen",
    val illustrator: String = "PhiraChartGen",
    val intro: String = "",
    val previewStartSec: Double = 0.0,
    /** 谱面延迟 (秒), 正值表示谱面更晚开始, 写入 info.yml */
    val offsetSec: Double = 0.0,
)

enum class ChartFormat(val displayName: String, val chartFileName: String) {
    RPE("RPE JSON", "chart.json"),
    OFFICIAL("Phigros 官方 JSON", "chart.json"),
    PEC("PEC 文本", "chart.pec"),
}
