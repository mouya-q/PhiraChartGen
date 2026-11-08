# Phira 谱面生成器

在 Android 上导入本地音频, 离线分析节奏, 一键生成可在 Phira 中导入的谱面包. 可选接入大模型做编排增强, 不可用时自动回退离线生成.

## 功能

- 音频导入与分块解码, 使用系统解码器, 支持 MP3, FLAC, OGG, WAV, M4A 等
- 离线分析: 起音检测, BPM 估计, 第一拍对齐
- 四档难度 EZ, HD, IN, AT, 含点击, 长按, 滑动, 拖动, 同时按键
- 导出 RPE JSON, Phigros 官方 JSON, PEC 三种格式, 在界面中选择
- 内置兼容性验证, 另附独立校验脚本
- 谱面预览与试听
- 可选 AI 增强: 配置, 连接测试, 超时重试, 失败自动回退
- Miuix 界面, 深浅色, 玻璃模糊背景

## 构建配置基线

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle 插件 | 9.5.0 | 9.0 起 Kotlin 支持内置, `:app` 不再应用 `kotlin-android` |
| Kotlin | 2.4.20 | 用于 `:core` 的 JVM 与序列化插件 |
| JDK | 17 | |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | compileSdk 由 Miuix 0.9.4 的 AAR metadata 下限决定 |
| Compose Multiplatform | 1.12.0 | Miuix 0.9.4 的编译基线 |
| Miuix | 0.9.4 | 仅 `:app` 使用, 经 `ui/Kit.kt` 单文件封装 |

版本集中在 `gradle/libs.versions.toml`, 以上组合已按各构件在 Maven 仓库中的实际元数据核对过。

## 快速开始

1. 用 Android Studio 打开本目录, 等待同步完成
2. 运行 `:core:test` 确认核心模块测试通过
3. 运行 `app` 安装到设备 (Android 8.0 及以上)

详细步骤见 [docs/BUILD.md](docs/BUILD.md).

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/BUILD.md](docs/BUILD.md) | 构建指南与云端 CI 说明 |
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | 用户手册 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构说明与决策记录 |
| [docs/VALIDATION.md](docs/VALIDATION.md) | Phira 兼容性验证方法与状态 |
| [docs/PRIVACY.md](docs/PRIVACY.md) | 隐私政策 |
| [docs/AI_DATA_USE.md](docs/AI_DATA_USE.md) | AI 数据使用声明 |
| [docs/COPYRIGHT_DISCLAIMER.md](docs/COPYRIGHT_DISCLAIMER.md) | 版权与免责声明 |
| [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md) | 第三方开源许可证清单 |
| [docs/FAQ.md](docs/FAQ.md) | 常见问题 |
| [CHANGELOG.md](CHANGELOG.md) | 变更日志 |

## 许可

MIT, 见 [LICENSE](LICENSE)