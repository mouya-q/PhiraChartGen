# 构建与验证状态

快照日期: 2026-11-08

## 已在 GitHub Actions 验证

`Android CI` 工作流跑在 `ubuntu-latest` 上, JDK 17, Gradle 9.6.0, 使用 runner 镜像预装的
Android SDK. 最近一次全绿运行是
[`42435442528`](https://github.com/mouya-q/PhiraChartGen/actions/runs/42435442528)
(提交 `e521bca9dd`), 三个任务全部通过:

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 核心模块单元测试 `gradle :core:test` | 通过 | 任务 `核心模块单元测试` completed/success |
| 样例谱面包校验 | 通过 | 12 个 `samples/*.zip` 全部通过 `tools/validate_phira_pack.py` |
| 构建 APK `gradle :app:assembleDebug` | 通过 | 产物 `phira-chart-gen-debug`, `app-debug.apk`, 10,689,677 字节 |

首次全绿之前, 构建连续失败过两轮, 根因已定位并修复:

| 任务 | 报错 | 根因 | 修复 |
| --- | --- | --- | --- |
| `:app:checkDebugAarMetadata` | 18 项 AAR metadata 问题, `miuix-ui-android:0.9.4` 要求编译到 android-37 | `compileSdk` 为 36 | `compileSdk = 37` (targetSdk 仍为 36) |
| `:app:compileDebugKotlin` | `No value passed for parameter 'onSelect'` | `Kit.kt` 中 `Segmented`/`PField` 的回调参数排在 `modifier`/`secret` 之前, 尾随 lambda 绑定到了最后一个参数 | 回调参数统一移到最后 |
| `:app:compileDebugKotlin` | `-Xjvm-default` 弃用告警 | Kotlin 2.2 起已改名 | 移除该编译参数 |

## 本机未验证

本机容器没有 Android 工具链与设备, 以下项目**全部未运行**:

| 检查 | 状态 | 原因 |
| --- | --- | --- |
| 本机 Gradle 构建与 APK | 未运行 | 无 Android SDK / JDK 工具链 |
| APK 安装与运行冒烟 | 未运行 | 无设备 / 模拟器 / adb |
| Phira 实机导入与游玩 | 未运行 | 无 Phira 运行环境 |
| 真实音乐上的算法效果 | 未运行 | 仅有合成音频的算法验证 |

## 核心算法验证方法

见 [VALIDATION.md](VALIDATION.md). 简要说明: 用程序合成的、已知 BPM 与相位的音频驱动整条
流水线, 再检查导出的谱面是否落在预期的节拍网格上.

## 2026-11-08 界面重构后的附加检查

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 样例谱面包校验 | 通过 | 12 个样例 ZIP 全部通过 `tools/validate_phira_pack.py` |
| Kotlin 源码语法扫描 | 未发现语法级诊断 | 使用容器内 Kotlin 1.9.0 做解析扫描, 缺少项目依赖 classpath, 不代表完整类型检查 |
| UI 文案扫描 | 通过 | 检查本次 UI 源码中的中文句末标点与新增文案 |
| 核心模块回归 | 未重新运行 | 核心模块源码未改动, 仍需在完整 Gradle 环境运行单元测试 |
| Android APK 构建 | 未运行 | 当前环境没有 Gradle, Android SDK 与 Gradle Wrapper |
| 设备动效验收 | 未运行 | 当前环境没有 Android 设备或模拟器, 预测性返回与实时模糊仍需真机验收 |

本次重构没有修改 `core` 模块, 也没有修改谱面导出与 Python 校验器

## 2026-11-08 后续界面修订

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 样例谱面包校验 | 通过 | 12 个样例 ZIP 全部通过 |
| Android XML 资源解析 | 通过 | Manifest、主题、图标、字符串及 FileProvider XML 均可解析 |
| 应用显示名称 | 已更新 | Launcher 标签为 `PhiraChartGen` |
| 图标资源 | 已更新 | 自适应图标使用白色背景与蓝/深色谱面符号 |
| 返回与次级操作 | 已调整 | 移除通用顶部文字返回入口, 次级操作改为箭头形式 |
| 预览音频准备 | 已调整 | 使用 `MediaPlayer.prepareAsync()` 避免同步解码阻塞 UI 线程 |
| 完整 Kotlin/Gradle 编译 | 未运行 | 当前容器没有 Gradle 与 Android SDK |
| 真机转场验收 | 未运行 | 需要在目标设备上确认帧连续性、模糊和预测性返回手感 |

## 合并更新 (判定线编排, AI 兼容, 玩家谱研究)

此次更新在上述已通过 CI 的版本上叠加, 界面与构建配置保持不变:

- `core` 新增 `Choreography.kt`, 重写 `Generator.kt`, `Model.kt`, `Formats.kt`, `ai/Ai.kt` 与测试, 界面只改了 `Preview.kt` 与 `Screens.kt` 中读取判定线数据的两处
- **这些改动尚未经过 CI 编译与测试**, 需要在下一次 CI 里确认. 算法与导出结构已在 Python 原型中验证 (通道连续, 音符不出屏, 12 个样例包通过校验脚本), 但 Kotlin 代码没有被编译过
- 新增依赖: 无

