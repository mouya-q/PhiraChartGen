# 第三方开源许可证清单

## 直接依赖

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Miuix (top.yukonga.miuix.kmp:miuix-ui) | 0.9.2 | Apache-2.0 | 界面组件与主题 |
| Compose Multiplatform runtime, foundation, ui | 1.11.1 | Apache-2.0 | 界面框架 |
| androidx.activity:activity-compose | 1.10.1 | Apache-2.0 | Activity 集成 |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.9.1 | Apache-2.0 | 状态与生命周期 |
| androidx.core:core-ktx | 1.16.0 | Apache-2.0 | FileProvider 等 |
| kotlinx-coroutines | 1.10.2 | Apache-2.0 | 并发 |
| kotlinx-serialization-json | 1.9.0 | Apache-2.0 | JSON 读写 |
| Kotlin 标准库 | 2.4.0 | Apache-2.0 | 语言运行时 |

测试依赖: kotlin-test (Apache-2.0), JUnit Platform (EPL-2.0), 仅用于构建时测试, 不随应用分发

## 传递依赖

Miuix 与 Compose 会引入若干传递依赖, 其许可证以实际依赖树为准. 发布前请在联网环境执行并核对:

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## 未使用的库

出于许可证考虑, 项目没有使用 GPL 协议的音频分析库 (如 TarsosDSP, aubio), 见 ARCHITECTURE.md 的 ADR-1

## 参考资料

Phira 谱面包结构与 info.yml 字段参考社区文档 phira-docs, 仅作为格式说明参考, 未复制代码

## 许可证文本

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
