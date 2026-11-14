# pcg-dsp

Phira 谱面生成器的原生音频分析引擎, 用 Rust 实现, 通过 JNI 供 `core` 模块调用

## 做什么

与 `core/src/main/kotlin/com/phiragen/core/audio/Analysis.kt` 逐步对应: 流式频谱, 三频段对数频谱流, 起音检测, 速度与相位估计. Kotlin 版本保留为回退与对照基准

## 构建

```
# 单元测试
cargo test

# 主机库, 供 Kotlin 对照测试加载
cargo build --release

# Android 各架构库 (需要 Android NDK 与 cargo-ndk)
cargo install cargo-ndk --locked
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../../app/src/main/jniLibs build --release
```

库放进 `app/src/main/jniLibs` 后重新构建 APK 即可. 没有这些文件时应用自动使用 Kotlin 引擎

## 对照测试

`core` 的 `NativeParityTest` 在 `native/pcg-dsp/target/release` 存在 `libpcg_dsp.so` 时, 对同一段合成音频比较两种实现 (帧数, 速度, 相位, 起音包络), 库不存在则跳过. 运行方式: 先 `cargo build --release`, 再 `gradle :core:test`

## 状态

此模块写成时开发环境没有 Rust 工具链, **尚未编译**. 首次构建可能需要修正少量编译错误
