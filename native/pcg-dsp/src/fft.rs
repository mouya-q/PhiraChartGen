//! 基于基 2 的原地复数 FFT, 长度必须是 2 的幂.

use std::f64::consts::PI;

pub struct Fft {
    n: usize,
    cos: Vec<f64>,
    sin: Vec<f64>,
    rev: Vec<usize>,
}

impl Fft {
    pub fn new(n: usize) -> Fft {
        assert!(n >= 2 && n.is_power_of_two(), "FFT 长度必须是 2 的幂");
        let mut cos = Vec::with_capacity(n / 2);
        let mut sin = Vec::with_capacity(n / 2);
        for i in 0..n / 2 {
            let a = -2.0 * PI * (i as f64) / (n as f64);
            cos.push(a.cos());
            sin.push(a.sin());
        }
        let bits = n.trailing_zeros();
        let mut rev = vec![0usize; n];
        for (i, slot) in rev.iter_mut().enumerate() {
            *slot = i.reverse_bits() >> (usize::BITS - bits);
        }
        Fft { n, cos, sin, rev }
    }

    pub fn transform(&self, re: &mut [f64], im: &mut [f64]) {
        let n = self.n;
        assert!(re.len() >= n && im.len() >= n);
        for i in 0..n {
            let j = self.rev[i];
            if j > i {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut len = 2;
        while len <= n {
            let half = len / 2;
            let step = n / len;
            let mut start = 0;
            while start < n {
                let mut k = 0;
                for j in start..start + half {
                    let wr = self.cos[k];
                    let wi = self.sin[k];
                    let xr = re[j + half] * wr - im[j + half] * wi;
                    let xi = re[j + half] * wi + im[j + half] * wr;
                    re[j + half] = re[j] - xr;
                    im[j + half] = im[j] - xi;
                    re[j] += xr;
                    im[j] += xi;
                    k += step;
                }
                start += len;
            }
            len <<= 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sine_energy_lands_in_expected_bin() {
        let n = 1024;
        let fft = Fft::new(n);
        let mut re: Vec<f64> = (0..n).map(|i| (2.0 * PI * 50.0 * i as f64 / n as f64).sin()).collect();
        let mut im = vec![0.0; n];
        fft.transform(&mut re, &mut im);
        let mut best = 1;
        let mut best_mag = 0.0;
        for k in 1..n / 2 {
            let m = re[k].hypot(im[k]);
            if m > best_mag {
                best_mag = m;
                best = k;
            }
        }
        assert_eq!(best, 50);
    }
}
