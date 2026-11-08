package com.phiragen.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phiragen.app.AiTestState
import com.phiragen.app.UiState
import com.phiragen.app.data.AppSettings
import com.phiragen.app.data.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun SettingsScreen(
    st: UiState,
    dark: Boolean,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onTestAi: () -> Unit,
    onClearKey: () -> Unit,
    onClearCache: () -> Unit,
    onShareLog: () -> Unit,
    onAbout: () -> Unit,
) {
    val s = st.settings
    var consentDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }

    PScaffold(topBar = { PTopBar("设置", onBack) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 32.dp)) {
            Section("外观") {
                Segmented(ThemeMode.values().map { it to it.label }, s.theme) { v -> onChange { it.copy(theme = v) } }
                SettingsDivider()
                PSwitchRow(
                    "转场模糊",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "页面切换时使用柔和的背景高斯模糊"
                    } else {
                        "当前系统版本不支持背景高斯模糊, 页面切换动画仍可正常使用"
                    },
                    s.transitionBlurEnabled,
                ) { v -> onChange { it.copy(transitionBlurEnabled = v) } }
            }

            Section("谱面") {
                PSliderRow(
                    "音符流速", String.format(Locale.ROOT, "%.1f", s.noteSpeed), s.noteSpeed, 5f..20f,
                    { v -> onChange { it.copy(noteSpeed = Math.round(v * 2f) / 2f) } },
                    "数值越大音符下落越快, 默认 10",
                )
                SettingsDivider()
                PSliderRow(
                    "网格吸附", if (s.snapToleranceMs == 0) "关闭" else "${s.snapToleranceMs} 毫秒", s.snapToleranceMs.toFloat(), 0f..80f,
                    { v -> onChange { it.copy(snapToleranceMs = v.toInt()) } },
                    "音符靠近节拍网格时对齐, 现场演奏的曲目可调小或关闭",
                )
                SettingsDivider()
                PSliderRow(
                    "谱面延迟", "${s.offsetMs} 毫秒", s.offsetMs.toFloat(), -300f..300f,
                    { v -> onChange { it.copy(offsetMs = v.toInt()) } },
                    "正值表示谱面更晚开始, 用于校准 MP3 等格式的解码延迟",
                )
                SettingsDivider()
                PField("谱师署名", s.charter) { v -> onChange { it.copy(charter = v.take(40)) } }
                Spacer(Modifier.height(6.dp))
            }

            Section("AI 增强 (可选)") {
                PSwitchRow(
                    "启用 AI 增强", "生成时请求大模型给出分段密度建议, 失败会自动回退离线生成",
                    s.ai.enabled,
                ) { v ->
                    if (v && !s.ai.consented) consentDialog = true
                    else onChange { it.copy(ai = it.ai.copy(enabled = v)) }
                }
                SettingsDivider()
                PField("接口地址", s.ai.baseUrl) { v -> onChange { it.copy(ai = it.ai.copy(baseUrl = v)) } }
                SettingsDivider()
                PField("模型名称", s.ai.model) { v -> onChange { it.copy(ai = it.ai.copy(model = v)) } }
                SettingsDivider()
                PField("接口密钥", s.ai.apiKey, secret = true) { v -> onChange { it.copy(ai = it.ai.copy(apiKey = v.trim())) } }
                SettingsDivider()
                PSliderRow(
                    "请求超时", "${s.ai.timeoutSec} 秒", s.ai.timeoutSec.toFloat(), 5f..60f,
                    { v -> onChange { it.copy(ai = it.ai.copy(timeoutSec = v.toInt())) } },
                )
                SettingsDivider()
                PSliderRow(
                    "失败重试", "${s.ai.retries} 次", s.ai.retries.toFloat(), 0f..4f,
                    { v -> onChange { it.copy(ai = it.ai.copy(retries = v.toInt())) } },
                )
                val formError = s.ai.toConfig().validate()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (val t = st.aiTest) {
                        AiTestState.Idle -> if (formError != null && (s.ai.apiKey.isNotEmpty() || s.ai.model.isNotEmpty())) Summary(formError)
                        AiTestState.Testing -> Summary("正在测试连接")
                        AiTestState.Ok -> Txt("连接成功", color = Palette.ok)
                        is AiTestState.Failed -> Txt("连接失败: ${t.reason}", color = Palette.bad)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PButton("测试连接", onTestAi, Modifier.weight(1f), enabled = st.aiTest != AiTestState.Testing)
                        PButton("清除密钥", onClearKey, Modifier.weight(1f), primary = false, enabled = s.ai.apiKey.isNotEmpty())
                    }
                    Spacer(Modifier.height(6.dp))
                    Summary("密钥使用系统 Keystore 加密保存, 不会出现在日志中")
                }
            }

            Section("数据与权限") {
                Column(Modifier.padding(16.dp)) {
                    Txt("权限说明", style = MiuixTheme.textStyles.body1)
                    Summary("文件访问: 通过系统文件选择器授权, 只读取你选中的音频, 无需存储权限")
                    Summary("网络: 仅在启用 AI 增强时使用, 离线生成不联网")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PButton("清理缓存", { clearDialog = true }, Modifier.weight(1f), primary = false)
                        PButton("导出诊断日志", onShareLog, Modifier.weight(1f), primary = false)
                    }
                }
            }

            Section("关于") {
                PSwitchRowLike("关于与声明", "版本, 隐私, 免责声明, 开源许可", onAbout)
            }
            }
        }
    }

    if (consentDialog) {
        ConfirmDialog(
            title = "AI 数据使用声明",
            body = "启用后, 生成谱面时会向你填写的接口发送曲速, 时长, 以及按时间分段的能量和起音数量统计\n\n" +
                "不会发送音频内容, 文件名, 曲名, 艺术家或任何个人信息\n\n" +
                "请求由你配置的第三方服务处理, 其数据政策由服务方决定, 你可以随时在这里关闭",
            confirm = "同意并启用", cancel = "取消",
            onConfirm = {
                consentDialog = false
                onChange { it.copy(ai = it.ai.copy(enabled = true, consented = true)) }
            },
            onDismiss = { consentDialog = false },
        )
    }
    if (clearDialog) {
        ConfirmDialog(
            title = "清理缓存",
            body = "将删除上次会话的分析缓存与转码文件, 不影响已导出的谱面",
            confirm = "清理", cancel = "取消",
            onConfirm = { clearDialog = false; onClearCache() },
            onDismiss = { clearDialog = false },
        )
    }
}

@Composable
private fun PSwitchRowLike(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, style = MiuixTheme.textStyles.body1)
            Summary(summary)
        }
        PTextButton("查看", onClick)
    }
}