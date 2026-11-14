//! Phira 谱面生成器的原生音频分析引擎.
//!
//! 算法与 Kotlin 实现 (core/audio/Analysis.kt) 逐步对应:
//! 22.05 kHz 单声道 PCM 流式进入, 逐帧计算三频段对数频谱流, 响度, 频谱质心与 4 kHz 以内谱线,
//! 结束时做局部归一化, 起音检测, 速度与相位估计.
//! Kotlin 版本保留为回退与对照基准.

pub mod analysis;
pub mod fft;
mod jni_bridge;
