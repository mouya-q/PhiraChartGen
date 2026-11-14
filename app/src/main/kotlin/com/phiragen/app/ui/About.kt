package com.phiragen.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Lib(val name: String, val license: String, val use: String)

private val libs = listOf(
    Lib("Miuix (compose-miuix-ui)", "Apache-2.0", "界面组件与主题"),
    Lib("Jetpack Compose / Compose Multiplatform", "Apache-2.0", "界面框架"),
    Lib("AndroidX Activity, Lifecycle, Core", "Apache-2.0", "应用框架"),
    Lib("Kotlin 标准库与 kotlinx.coroutines", "Apache-2.0", "语言运行时与并发"),
    Lib("kotlinx.serialization", "Apache-2.0", "JSON 读写"),
)

@Composable
fun AboutScreen(dark: Boolean, onBack: () -> Unit, onLicenses: () -> Unit, versionName: String) {
    PScaffold(topBar = { PTopBar("关于", onBack) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 32.dp),
            ) {
                Section("PhiraChartGen") {
                    Column(Modifier.padding(16.dp)) {
                        Txt("版本 $versionName", style = MiuixTheme.textStyles.body1)
                        Summary("导入本地音频, 离线分析节奏并生成 Phira 可用的谱面包, 可选 AI 增强")
                    }
                }
                Section("隐私") {
                    Column(Modifier.padding(16.dp)) {
                        Summary("音频文件只在本机解码和分析, 不会上传")
                        Spacer(Modifier.height(6.dp))
                        Summary("启用 AI 增强后, 仅上传曲速与按乐句统计的能量和起音数量, 不含音频, 文件名与个人信息")
                        Spacer(Modifier.height(6.dp))
                        Summary("没有账号, 没有统计, 没有广告, 诊断日志只保存在本机")
                    }
                }
                Section("版权与免责声明") {
                    Column(Modifier.padding(16.dp)) {
                        Summary("请只对你拥有版权或获得授权的音乐制谱与分享")
                        Spacer(Modifier.height(6.dp))
                        Summary("自动生成的谱面是算法输出, 不保证符合官方或任何社区的审核要求")
                        Spacer(Modifier.height(6.dp))
                        Summary("本项目与 Phira, Phigros, Re:PhiEdit 及其权利方没有隶属关系")
                        Spacer(Modifier.height(6.dp))
                        Summary("软件按现状提供, 不附带任何担保")
                    }
                }
                Section("开源许可") {
                    NavRow("第三方开源许可证", "本应用使用的开源项目与许可证", onLicenses)
                }
            }
        }
    }
}

@Composable
fun LicensesScreen(dark: Boolean, onBack: () -> Unit) {
    PScaffold(topBar = { PTopBar("开源许可", onBack) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            PageBackground(dark)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 32.dp),
            ) {
                Section("第三方组件") {
                    for (library in libs) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Txt(library.name, style = MiuixTheme.textStyles.body1)
                            Summary("${library.license}  ·  ${library.use}")
                        }
                    }
                }
                Section("说明") {
                    Column(Modifier.padding(16.dp)) {
                        Summary("传递依赖以 Gradle 依赖报告为准, 完整说明见仓库 docs/THIRD_PARTY_LICENSES.md")
                    }
                }
            }
        }
    }
}
