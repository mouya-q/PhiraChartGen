package com.phiragen.app.data

import android.content.Context
import com.phiragen.core.ai.AiConfig
import com.phiragen.core.chart.ChartFormat
import com.phiragen.core.chart.Difficulty

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

data class AiSettings(
    val enabled: Boolean = false,
    /** 用户已阅读数据使用声明并同意 */
    val consented: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiKey: String = "",
    val timeoutSec: Int = 20,
    val retries: Int = 2,
) {
    fun toConfig() = AiConfig(baseUrl.trim(), apiKey, model.trim(), timeoutSec * 1000, retries)
    val ready: Boolean get() = enabled && consented && toConfig().validate() == null
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val format: ChartFormat = ChartFormat.RPE,
    val difficulties: Set<Difficulty> = setOf(Difficulty.EZ, Difficulty.HD, Difficulty.IN, Difficulty.AT),
    val noteSpeed: Float = 10f,
    val snapToleranceMs: Int = 35,
    val offsetMs: Int = 0,
    val charter: String = "PhiraChartGen",
    val blurEnabled: Boolean = true,
    val transitionBlurEnabled: Boolean = true,
    val ai: AiSettings = AiSettings(),
)

/** 设置存储. 密钥只以加密形式落盘 */
class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val diffs = sp.getStringSet("difficulties", null)
            ?.mapNotNull { k -> Difficulty.values().firstOrNull { it.key == k } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: AppSettings().difficulties
        val encKey = sp.getString("ai_key", "") ?: ""
        return AppSettings(
            theme = ThemeMode.values().getOrElse(sp.getInt("theme", 0)) { ThemeMode.SYSTEM },
            format = ChartFormat.values().getOrElse(sp.getInt("format", 0)) { ChartFormat.RPE },
            difficulties = diffs,
            noteSpeed = sp.getFloat("speed", 10f).coerceIn(5f, 20f),
            snapToleranceMs = sp.getInt("snap", 35).coerceIn(0, 80),
            offsetMs = sp.getInt("offset", 0).coerceIn(-500, 500),
            charter = sp.getString("charter", "PhiraChartGen")?.take(40) ?: "PhiraChartGen",
            blurEnabled = sp.getBoolean("blur", true),
            transitionBlurEnabled = sp.getBoolean("transition_blur", true),
            ai = AiSettings(
                enabled = sp.getBoolean("ai_enabled", false),
                consented = sp.getBoolean("ai_consent", false),
                baseUrl = sp.getString("ai_url", "https://api.openai.com/v1") ?: "",
                model = sp.getString("ai_model", "") ?: "",
                apiKey = if (encKey.isEmpty()) "" else KeyVault.decrypt(encKey),
                timeoutSec = sp.getInt("ai_timeout", 20).coerceIn(3, 120),
                retries = sp.getInt("ai_retries", 2).coerceIn(0, 5),
            ),
        )
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putInt("theme", s.theme.ordinal)
            .putInt("format", s.format.ordinal)
            .putStringSet("difficulties", s.difficulties.map { it.key }.toSet())
            .putFloat("speed", s.noteSpeed)
            .putInt("snap", s.snapToleranceMs)
            .putInt("offset", s.offsetMs)
            .putString("charter", s.charter)
            .putBoolean("blur", s.blurEnabled)
            .putBoolean("transition_blur", s.transitionBlurEnabled)
            .putBoolean("ai_enabled", s.ai.enabled)
            .putBoolean("ai_consent", s.ai.consented)
            .putString("ai_url", s.ai.baseUrl)
            .putString("ai_model", s.ai.model)
            .putString("ai_key", if (s.ai.apiKey.isEmpty()) "" else KeyVault.encrypt(s.ai.apiKey))
            .putInt("ai_timeout", s.ai.timeoutSec)
            .putInt("ai_retries", s.ai.retries)
            .apply()
    }
}
