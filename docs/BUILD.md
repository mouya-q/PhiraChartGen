# 构建指南

## 环境要求

- JDK 17
- Gradle 9.6.0 (命令行或 Android Studio 自带均可)
- Android SDK 36, 构建工具由 Android Studio 自动安装
- 可联网访问 Google Maven 与 Maven Central

## 工具链基线

| 组件 | 版本 |
| --- | --- |
| Gradle | 9.6.0 |
| Android Gradle 插件 | 9.5.0 |
| Kotlin | 2.4.20 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

`compileSdk` 取 37 而不是 36, 是因为 Miuix 0.9.4 的 AAR metadata 要求使用方至少编译到
`android-37`; 否则 `:app:checkDebugAarMetadata` 会直接失败. `targetSdk` 仍保持 36, 两者可以
独立升级.

AGP 9 起 Kotlin 支持内置, `:app` 模块**不再**应用 `org.jetbrains.kotlin.android`;
若在构建输出里看到

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
```

说明有人在 `:app` 里把这个插件加回来了, 删掉即可. `gradle.properties` 中的
`android.builtInKotlin=true` 与 `android.newDsl=true` 与 AGP 9 的默认值一致, 显式写出
是为了让基线在设计文件里可见.

`:core` 是纯 Kotlin/JVM 模块, 仍然显式使用 `org.jetbrains.kotlin.jvm` 与
`org.jetbrains.kotlin.plugin.serialization`, 因此 `libs.versions.toml` 中的 `kotlin`
版本必须与 AGP 携带的 KGP 保持同一代 (当前都是 2.4.x), 否则序列化插件与内置编译器会打架.

## 在 Android Studio 中构建

1. 打开仓库根目录, 等待 Gradle 同步
2. 仓库提供 `gradle/wrapper/gradle-wrapper.properties`, Android Studio 会按其中的版本下载 Gradle
3. 运行配置选择 `app`, 点击运行安装到设备或模拟器

## 命令行构建

仓库没有附带 `gradle-wrapper.jar`, 因此 `./gradlew` 不可用. 两种做法:

**使用本机 Gradle**

```
gradle :core:test
gradle :app:assembleDebug
```

**生成 wrapper**

```
gradle wrapper --gradle-version 9.6.0
./gradlew :core:test
```

APK 输出在 `app/build/outputs/apk/`

## 运行测试

```
gradle :core:test
python3 tools/validate_phira_pack.py samples/*.zip
```

核心模块是纯 JVM 工程, 测试用 JUnit Platform (JUnit 5), 不依赖 Android 设备.

## 发布签名

在仓库根目录创建 `keystore.properties` (已被 gitignore):

```
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有该文件时, release 包使用 debug 签名, 仅供自测.

## GitHub Actions

`.github/workflows/ci.yml` 包含三个任务: 核心单元测试, 样例谱面包校验, 构建 APK 并上传产物.
工作流使用 `gradle/actions/setup-gradle` 指定 Gradle 9.6.0, 不依赖 wrapper jar, JDK 固定 17
(与上表的工具链基线一致).

两个曾在同类项目里踩到的 CI 坑, 本工作流已经绕开:

- `android-actions/setup-android@v3` 默认会安装已下架的 `tools` 包, cmdline-tools 16.x 起
  `sdkmanager` 直接以 1 退出, 整个 job 在构建前就失败. 工作流只请求 `packages: 'platform-tools'`.
- `sdkmanager "platforms;android-36"` 之类的精确包名不一定存在; SDK 平台通常由 runner 镜像
  预装, 因此工作流打印已装包清单, 而不是尝试安装.

## 常见构建问题

- **找不到 Miuix 构件**: 确认仓库包含 `mavenCentral()`. Miuix 以 KMP 多目标发布, Android 目标
  是 `miuix-ui-android`, 版本目录里写 `miuix-ui` 即可, Gradle 会按变体解析.
- **Compose 与 Miuix 版本漂移**: Miuix 0.9.4 的编译基线是 Compose Multiplatform 1.12.0.
  单独升级 Compose 会造成 `NoSuchMethodError` 之类的运行期崩溃, 两者要一起升.
- **AGP 与 Gradle 版本不匹配**: 以 `libs.versions.toml` 与 `gradle/wrapper` 中的版本为准, 同步升级.
- **内存不足**: 在 `gradle.properties` 中调大 `org.gradle.jvmargs`.