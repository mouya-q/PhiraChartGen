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
