//! 流式频谱分析, 起音检测, 速度与相位估计. 与 Kotlin 版 Analysis.kt 逐步对应.

use crate::fft::Fft;
use std::cmp::Ordering;
use std::collections::BTreeSet;
use std::f64::consts::PI;

pub const SAMPLE_RATE: usize = 22050;
pub const FRAME: usize = 1024;
pub const HOP: usize = 256;
pub const BINS: usize = FRAME / 2 + 1;
/// 保留的低频谱线数量 (约 4 kHz 以内), 用于持续音追踪
pub const KEEP_BINS: usize = 190;
pub const FPS: f64 = SAMPLE_RATE as f64 / HOP as f64;
const MIN_BPM: f64 = 60.0;
const MAX_BPM: f64 = 200.0;

pub struct Analysis {
    pub duration: f64,
    pub bpm: f64,
    pub phase: f64,
    pub env: Vec<f32>,
    pub low: Vec<f32>,
    pub mid: Vec<f32>,
    pub high: Vec<f32>,
    pub rms: Vec<f32>,
    pub centroid: Vec<f32>,
    pub low_mag: Vec<f32>,
}

pub struct SpectrumStream {
    fft: Fft,
    window: Vec<f64>,
    ring: Vec<f32>,
    pos: usize,
    total: u64,
    re: Vec<f64>,
    im: Vec<f64>,
    prev_log: Vec<f64>,
    has_prev: bool,
    lo_bin: usize,
    mid_bin: usize,
    low: Vec<f32>,
    mid: Vec<f32>,
    high: Vec<f32>,
    rms: Vec<f32>,
    centroid: Vec<f32>,
    low_mag: Vec<f32>,
    samples_seen: u64,
}

impl Default for SpectrumStream {
    fn default() -> Self {
        Self::new()
    }
}

impl SpectrumStream {
    pub fn new() -> SpectrumStream {
        let hz = SAMPLE_RATE as f64 / FRAME as f64;
        SpectrumStream {
            fft: Fft::new(FRAME),
            window: (0..FRAME)
                .map(|i| 0.5 - 0.5 * (2.0 * PI * (i as f64) / (FRAME as f64)).cos())
                .collect(),
            ring: vec![0.0; FRAME],
            pos: 0,
            total: 0,
            re: vec![0.0; FRAME],
            im: vec![0.0; FRAME],
            prev_log: vec![0.0; BINS],
            has_prev: false,
            lo_bin: (250.0 / hz) as usize,
            mid_bin: (2500.0 / hz) as usize,
            low: Vec::new(),
            mid: Vec::new(),
            high: Vec::new(),
            rms: Vec::new(),
            centroid: Vec::new(),
            low_mag: Vec::new(),
            samples_seen: 0,
        }
    }

    pub fn push(&mut self, x: &[f32]) {
        for &s in x {
            self.ring[self.pos] = s;
            self.pos = (self.pos + 1) & (FRAME - 1);
            self.total += 1;
            self.samples_seen += 1;
            if self.total >= FRAME as u64 && (self.total - FRAME as u64) % HOP as u64 == 0 {
                self.frame();
            }
        }
    }

    fn frame(&mut self) {
        for i in 0..FRAME {
            self.re[i] = self.ring[(self.pos + i) & (FRAME - 1)] as f64 * self.window[i];
            self.im[i] = 0.0;
        }
        self.fft.transform(&mut self.re, &mut self.im);
        let hz = SAMPLE_RATE as f64 / FRAME as f64;
        let mut low_sum = 0.0f64;
        let mut mid_sum = 0.0f64;
        let mut high_sum = 0.0f64;
        let mut sq = 0.0f64;
        let mut wsum = 0.0f64;
        let mut mag_sum = 0.0f64;
        for b in 0..BINS {
            let m = self.re[b].hypot(self.im[b]);
            sq += m * m;
            let lg = (1.0 + 100.0 * m).ln();
            if self.has_prev {
                let d = lg - self.prev_log[b];
                if d > 0.0 {
                    if b >= 1 && b < self.lo_bin {
                        low_sum += d;
                    } else if b >= self.lo_bin && b < self.mid_bin {
                        mid_sum += d;
                    } else if b >= self.mid_bin {
                        high_sum += d;
                    }
                }
            }
            self.prev_log[b] = lg;
            if b >= 1 {
                wsum += m * (b as f64) * hz;
                mag_sum += m;
            }
            if b < KEEP_BINS {
                self.low_mag.push(m as f32);
            }
        }
        self.has_prev = true;
        let low_div = (self.lo_bin as i64 - 1).max(1) as f64;
        self.low.push((low_sum / low_div) as f32);
        self.mid.push((mid_sum / (self.mid_bin - self.lo_bin) as f64) as f32);
        self.high.push((high_sum / (BINS - self.mid_bin) as f64) as f32);
        self.rms.push((sq / BINS as f64).sqrt() as f32);
        self.centroid.push((wsum / (mag_sum + 1e-9)) as f32);
    }

    pub fn finish(self) -> Result<Analysis, String> {
        if self.rms.len() < 64 {
            return Err("音频太短, 至少需要约 1 秒".to_string());
        }
        let duration = self.samples_seen as f64 / SAMPLE_RATE as f64;
        let low_n = local_norm(&self.low);
        let mid_n = local_norm(&self.mid);
        let high_n = local_norm(&self.high);
        let combined: Vec<f32> = (0..self.low.len())
            .map(|i| 0.5 * self.low[i] + self.mid[i] + self.high[i])
            .collect();
        let env = local_norm(&combined);
        let peaks = pick_peaks(&env, 0.045, 0.12);
        let (bpm, phase) = estimate_tempo(&env, &peaks);
        Ok(Analysis {
            duration,
            bpm,
            phase,
            env,
            low: low_n,
            mid: mid_n,
            high: high_n,
            rms: self.rms,
            centroid: self.centroid,
            low_mag: self.low_mag,
        })
    }
}

/// 线性插值百分位, 与 numpy 默认行为一致
fn percentile(values: &[f32], q: f64) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut s: Vec<f32> = values.to_vec();
    s.sort_by(|a, b| a.partial_cmp(b).unwrap_or(Ordering::Equal));
    let pos = (q / 100.0) * (s.len() - 1) as f64;
    let lo = pos.floor() as usize;
    let hi = pos.ceil() as usize;
    let f = pos - lo as f64;
    s[lo] as f64 * (1.0 - f) + s[hi] as f64 * f
}

/// 以零填充边界的居中滑动平均, 返回等长数组
fn moving_average(x: &[f32], window: usize) -> Vec<f32> {
    let n = x.len();
    let mut out = vec![0.0f32; n];
    if n == 0 {
        return out;
    }
    let mut prefix = vec![0.0f64; n + 1];
    for i in 0..n {
        prefix[i + 1] = prefix[i] + x[i] as f64;
    }
    let half = window / 2;
    for i in 0..n {
        let lo = i.saturating_sub(half);
        let hi = (i + half).min(n - 1);
        out[i] = ((prefix[hi + 1] - prefix[lo]) / window as f64) as f32;
    }
    out
}

/// 去除缓慢变化的底噪后按局部能量归一化
fn local_norm(e: &[f32]) -> Vec<f32> {
    let n = e.len();
    let mean = moving_average(e, 43);
    let y: Vec<f32> = (0..n).map(|i| (e[i] - mean[i]).max(0.0)).collect();
    let g = percentile(&y, 97.0) + 1e-9;
    let w = ((FPS * 1.5) as usize) | 1;
    let sq: Vec<f32> = y.iter().map(|v| v * v).collect();
    let local = moving_average(&sq, w);
    (0..n)
        .map(|i| {
            let scale = ((local[i] as f64).sqrt() * 3.0).max(0.25 * g);
            (y[i] as f64 / scale).min(1.5) as f32
        })
        .collect()
}

/// 局部极大值检测, min_gap_sec 内只保留更强的一个
pub fn pick_peaks(env: &[f32], min_gap_sec: f64, thr: f64) -> Vec<usize> {
    let w = 3usize;
    let mut peaks: Vec<usize> = Vec::new();
    if env.len() <= 2 * w {
        return peaks;
    }
    let mut last: i64 = -1_000_000_000;
    let min_gap = min_gap_sec * FPS;
    for i in w..env.len() - w {
        let v = env[i];
        if (v as f64) < thr {
            continue;
        }
        let mut is_max = true;
        for j in i - w..=i + w {
            if env[j] > v {
                is_max = false;
                break;
            }
        }
        if !is_max {
            continue;
        }
        if ((i as i64 - last) as f64) >= min_gap {
            peaks.push(i);
            last = i as i64;
        } else if let Some(&lp) = peaks.last() {
            if v > env[lp] {
                let l = peaks.len();
                peaks[l - 1] = i;
                last = i as i64;
            }
        }
    }
    peaks
}

fn parabolic(env: &[f32], i: usize) -> f64 {
    if i == 0 || i >= env.len() - 1 {
        return i as f64;
    }
    let a = env[i - 1] as f64;
    let b = env[i] as f64;
    let c = env[i + 1] as f64;
    let d = a - 2.0 * b + c;
    if d.abs() > 1e-12 {
        i as f64 + 0.5 * (a - c) / d
    } else {
        i as f64
    }
}

/// 帧内起音的实际时间, 0.75 帧长为实测校准出的固定偏置
fn peak_time_sec(env: &[f32], i: usize) -> f64 {
    parabolic(env, i) / FPS + 0.75 * FRAME as f64 / SAMPLE_RATE as f64
}

const WIDTH: f64 = 0.06;

fn score(times: &[f64], st: &[f64], period: f64, phi: f64) -> f64 {
    let half = period / 2.0;
    let mut s = 0.0;
    for k in 0..times.len() {
        let mut d = (times[k] - phi + half) % period;
        if d < 0.0 {
            d += period;
        }
        d -= half;
        if d > -WIDTH && d < WIDTH {
            let u = 1.0 - (d / WIDTH) * (d / WIDTH);
            s += st[k] * u * u;
        }
    }
    s
}

/// 返回 (拍格对齐比例, 相位)
fn align(times: &[f64], st: &[f64], total: f64, bpm: f64, step: f64) -> (f64, f64) {
    let period = 60.0 / bpm;
    let mut best = -1.0;
    let mut best_phi = 0.0;
    let mut phi = 0.0;
    while phi < period {
        let s = score(times, st, period, phi);
        if s > best {
            best = s;
            best_phi = phi;
        }
        phi += step;
    }
    let limit = best_phi + step;
    let mut p = best_phi - step;
    while p < limit {
        let pp = ((p % period) + period) % period;
        let s = score(times, st, period, pp);
        if s > best {
            best = s;
            best_phi = pp;
        }
        p += step / 4.0;
    }
    (best / total, best_phi)
}

/// 自相关得分最高的若干个局部峰值对应的速度
fn autocorr_candidates(env: &[f32], count: usize) -> Vec<f64> {
    let n = env.len();
    let mean = env.iter().map(|&v| v as f64).sum::<f64>() / n as f64;
    let size = (2 * n).next_power_of_two();
    let mut re = vec![0.0f64; size];
    let mut im = vec![0.0f64; size];
    for i in 0..n {
        re[i] = env[i] as f64 - mean;
    }
    let fft = Fft::new(size);
    fft.transform(&mut re, &mut im);
    for i in 0..size {
        re[i] = re[i] * re[i] + im[i] * im[i];
        im[i] = 0.0;
    }
    fft.transform(&mut re, &mut im);
    let zero = re[0] / size as f64 + 1e-9;
    let ac: Vec<f64> = (0..n).map(|i| re[i] / size as f64 / zero).collect();

    let mut bpms: Vec<f64> = Vec::new();
    let mut scores: Vec<f64> = Vec::new();
    let mut bpm = MIN_BPM;
    while bpm <= MAX_BPM + 1e-9 {
        let lag = 60.0 / bpm * FPS;
        let mut s = 0.0;
        let mults = [1.0f64, 2.0, 4.0];
        let weights = [1.0f64, 0.5, 0.25];
        for k in 0..3 {
            let l = lag * mults[k];
            let i0 = l as usize;
            if i0 + 1 >= n {
                continue;
            }
            let fr = l - i0 as f64;
            s += weights[k] * (ac[i0] * (1.0 - fr) + ac[i0 + 1] * fr);
        }
        bpms.push(bpm);
        scores.push(s);
        bpm += 0.5;
    }
    let mut peaks: Vec<usize> = Vec::new();
    if scores.len() > 4 {
        for i in 2..scores.len() - 2 {
            let mut is_max = true;
            for j in i - 2..=i + 2 {
                if scores[j] > scores[i] {
                    is_max = false;
                    break;
                }
            }
            if is_max {
                peaks.push(i);
            }
        }
    }
    peaks.sort_by(|&a, &b| scores[b].partial_cmp(&scores[a]).unwrap_or(Ordering::Equal));
    peaks.into_iter().take(count).map(|i| bpms[i]).collect()
}

/// 候选速度逐个精细对齐, 以强起音落在拍格上的比例为主要依据
pub fn estimate_tempo(env: &[f32], peaks: &[usize]) -> (f64, f64) {
    if peaks.len() < 8 {
        return (120.0, 0.0);
    }
    let mut order: Vec<usize> = peaks.to_vec();
    order.sort_by(|&a, &b| env[b].partial_cmp(&env[a]).unwrap_or(Ordering::Equal));
    order.truncate(1200);
    let times: Vec<f64> = order.iter().map(|&i| peak_time_sec(env, i)).collect();
    let strengths: Vec<f64> = order.iter().map(|&i| env[i] as f64).collect();
    let total: f64 = strengths.iter().sum();
    if total <= 1e-9 {
        return (120.0, 0.0);
    }

    let mut raw: BTreeSet<i64> = BTreeSet::new();
    for b in autocorr_candidates(env, 8) {
        for r in [1.0f64, 2.0, 0.5, 1.5, 2.0 / 3.0, 4.0 / 3.0, 0.75] {
            let x = b * r;
            if (70.0..=200.0).contains(&x) {
                raw.insert((x * 10.0).round() as i64);
            }
        }
    }
    let mut candidates: Vec<f64> = Vec::new();
    for t in raw {
        let c = t as f64 / 10.0;
        match candidates.last() {
            Some(&last) if c - last <= 0.6 => {}
            _ => candidates.push(c),
        }
    }

    let mut best_score = -1.0;
    let mut best_bpm = 120.0;
    let mut best_phase = 0.0;
    for &b in &candidates {
        let mut coarse_best = -1.0;
        let mut coarse_x = b;
        let mut x = b - 1.5;
        while x <= b + 1.5 + 1e-9 {
            let f = align(&times, &strengths, total, x, 0.02).0;
            if f > coarse_best {
                coarse_best = f;
                coarse_x = x;
            }
            x += 0.25;
        }
        let mut fine_best = -1.0;
        let mut fine_x = coarse_x;
        let mut phase = 0.0;
        x = coarse_x - 0.25;
        while x <= coarse_x + 0.25 + 1e-9 {
            let (f, ph) = align(&times, &strengths, total, x, 0.01);
            if f > fine_best {
                fine_best = f;
                fine_x = x;
                phase = ph;
            }
            x += 0.05;
        }
        let z = (fine_x / 125.0).ln() / 2.0f64.ln();
        let s = fine_best * (-0.5 * z * z).exp();
        if s > best_score {
            best_score = s;
            best_bpm = fine_x;
            best_phase = phase;
        }
    }
    let period = 60.0 / best_bpm;
    (best_bpm, ((best_phase % period) + period) % period)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 程序合成的测试音频: 底鼓, 反拍军鼓, 八分踩镲
    fn synth(bpm: f64, phase: f64, seconds: f64) -> Vec<f32> {
        let n = (seconds * SAMPLE_RATE as f64) as usize;
        let mut x = vec![0.0f32; n];
        let mut seed: u32 = 12345;
        let mut noise = move || {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            ((seed >> 8) as f32 / 8388608.0) - 1.0
        };
        let period = 60.0 / bpm;
        let beats = ((seconds - phase) / period) as usize;
        for b in 0..beats {
            let t = phase + b as f64 * period;
            let start = (t * SAMPLE_RATE as f64) as usize;
            // 底鼓
            let mut ph = 0.0f64;
            for i in 0..(0.25 * SAMPLE_RATE as f64) as usize {
                if start + i >= n {
                    break;
                }
                let tt = i as f64 / SAMPLE_RATE as f64;
                let f = 50.0 + 110.0 * (-tt * 28.0).exp();
                ph += 2.0 * PI * f / SAMPLE_RATE as f64;
                x[start + i] += (0.9 * ph.sin() * (-tt * 14.0).exp()) as f32;
            }
            // 反拍军鼓
            if b % 2 == 1 {
                for i in 0..(0.18 * SAMPLE_RATE as f64) as usize {
                    if start + i >= n {
                        break;
                    }
                    let tt = i as f64 / SAMPLE_RATE as f64;
                    x[start + i] += (0.5 * noise() as f64 * (-tt * 28.0).exp()) as f32;
                }
            }
            // 每半拍一个踩镲
            for h in 0..2 {
                let hs = start + (h as f64 * period / 2.0 * SAMPLE_RATE as f64) as usize;
                for i in 0..(0.05 * SAMPLE_RATE as f64) as usize {
                    if hs + i >= n {
                        break;
                    }
                    let tt = i as f64 / SAMPLE_RATE as f64;
                    x[hs + i] += (0.18 * noise() as f64 * (-tt * 90.0).exp()) as f32;
                }
            }
        }
        let peak = x.iter().fold(1e-9f32, |m, v| m.max(v.abs()));
        for v in x.iter_mut() {
            *v /= peak * 1.05;
        }
        x
    }

    fn run(x: &[f32]) -> Analysis {
        let mut s = SpectrumStream::new();
        for chunk in x.chunks(4096) {
            s.push(chunk);
        }
        s.finish().expect("分析应当成功")
    }

    #[test]
    fn tempo_is_recovered() {
        for bpm in [128.0, 150.0, 174.0] {
            let a = run(&synth(bpm, 0.37, 40.0));
            assert!((a.bpm - bpm).abs() < 0.8, "期望 {} 实际 {}", bpm, a.bpm);
        }
    }

    #[test]
    fn phase_matches_the_half_beat_grid() {
        let a = run(&synth(128.0, 0.37, 40.0));
        let half = 60.0 / a.bpm / 2.0;
        let mut d = (a.phase - 0.37) % half;
        if d < 0.0 {
            d += half;
        }
        let err = d.min(half - d);
        assert!(err < 0.03, "相位误差 {}", err);
    }

    #[test]
    fn frame_arrays_have_equal_length() {
        let a = run(&synth(128.0, 0.0, 10.0));
        let n = a.env.len();
        assert!(n > 100);
        assert_eq!(a.low.len(), n);
        assert_eq!(a.mid.len(), n);
        assert_eq!(a.high.len(), n);
        assert_eq!(a.rms.len(), n);
        assert_eq!(a.centroid.len(), n);
        assert_eq!(a.low_mag.len(), n * KEEP_BINS);
    }

    #[test]
    fn too_short_audio_is_rejected() {
        let mut s = SpectrumStream::new();
        s.push(&vec![0.0f32; 5000]);
        assert!(s.finish().is_err());
    }

    #[test]
    fn chunking_does_not_change_the_result() {
        let x = synth(128.0, 0.2, 12.0);
        let a = run(&x);
        let mut s = SpectrumStream::new();
        for chunk in x.chunks(997) {
            s.push(chunk);
        }
        let b = s.finish().unwrap();
        assert_eq!(a.env.len(), b.env.len());
        for i in 0..a.env.len() {
            assert!((a.env[i] - b.env[i]).abs() < 1e-6);
        }
    }

    #[test]
    fn silence_does_not_crash() {
        let a = run(&vec![0.0f32; SAMPLE_RATE * 5]);
        assert!(a.env.iter().all(|v| v.is_finite()));
    }
}
