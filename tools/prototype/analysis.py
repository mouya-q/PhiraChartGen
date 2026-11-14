import numpy as np

SR = 22050
N = 1024
HOP = 256
FPS = SR / HOP

def stft_logmag(x):
    win = np.hanning(N).astype(np.float32)
    nf = 1 + (len(x) - N) // HOP
    idx = np.arange(N)[None, :] + HOP * np.arange(nf)[:, None]
    fr = x[idx] * win
    mag = np.abs(np.fft.rfft(fr, axis=1))
    return np.log1p(100.0 * mag), mag

def band_edges():
    # bins: 22050/1024 = 21.5 Hz per bin
    hz = SR / N
    lo = int(250 / hz); mid = int(2500 / hz)
    return lo, mid

def onset_envelope(logmag):
    d = np.maximum(0, np.diff(logmag, axis=0, prepend=logmag[:1]))
    lo, mid = band_edges()
    low = d[:, 1:lo].sum(1) / max(1, lo - 1)
    midb = d[:, lo:mid].sum(1) / (mid - lo)
    high = d[:, mid:].sum(1) / (d.shape[1] - mid)
    return low, midb, high

def local_norm(e, win=43):
    # subtract moving median-ish (use moving mean for speed) then normalize
    k = np.ones(win) / win
    m = np.convolve(e, k, mode='same')
    y = np.maximum(0, e - m)
    g = np.percentile(y, 97) + 1e-9
    w = int(FPS * 1.5)
    local = np.sqrt(np.convolve(y * y, np.ones(w) / w, mode='same'))
    scale = np.maximum(local * 3.0, 0.25 * g)
    return np.minimum(1.5, y / scale)

def pick_peaks(env, min_gap_s=0.045, thr=0.12):
    w = 3
    peaks = []
    last = -1e9
    for i in range(w, len(env) - w):
        v = env[i]
        if v < thr: continue
        if v >= env[i - w:i + w + 1].max():
            if i - last >= min_gap_s * FPS:
                peaks.append(i); last = i
            elif peaks and v > env[peaks[-1]]:
                peaks[-1] = i; last = i
    return np.array(peaks, dtype=int)

def parabolic(env, i):
    if i <= 0 or i >= len(env) - 1: return float(i)
    a, b, c = env[i - 1], env[i], env[i + 1]
    d = a - 2 * b + c
    return i + (0.5 * (a - c) / d if abs(d) > 1e-12 else 0.0)

def _kern(d):
    u = 1 - (d / 0.06) ** 2
    return np.where(u > 0, u * u, 0.0)

def _align(times, st, bpm, step=0.01):
    P = 60.0 / bpm; best = (-1.0, 0.0)
    for phi in np.arange(0, P, step):
        d = ((times - phi + P / 2) % P) - P / 2; s = (st * _kern(d)).sum()
        if s > best[0]: best = (s, phi)
    for phi in np.arange(best[1] - step, best[1] + step, step / 4):
        phi %= P; d = ((times - phi + P / 2) % P) - P / 2; s = (st * _kern(d)).sum()
        if s > best[0]: best = (s, phi)
    return best[0] / st.sum(), best[1]

def _acf_candidates(env, k=8):
    e = env - env.mean(); n = len(e); f = np.fft.rfft(e, 2 * n); ac = np.fft.irfft(f * np.conj(f))[:n]; ac /= ac[0] + 1e-9
    bp = np.arange(60, 200.01, 0.5); arr = []
    for bpm in bp:
        lag = 60.0 / bpm * FPS; s = 0.0
        for mult, w in ((1, 1.0), (2, 0.5), (4, 0.25)):
            l = lag * mult; i0 = int(l)
            if i0 + 1 >= n: continue
            fr = l - i0; s += w * (ac[i0] * (1 - fr) + ac[i0 + 1] * fr)
        arr.append(s)
    arr = np.array(arr); idx = [i for i in range(2, len(arr) - 2) if arr[i] == arr[i - 2:i + 3].max()]
    idx.sort(key=lambda i: -arr[i]); return [bp[i] for i in idx[:k]]

def estimate_tempo(env, peaks_t, peaks_s):
    """候选速度 (自相关峰值及其 2 倍, 1/2, 3/2, 2/3 等) 逐个精细对齐, 以强起音落在拍格上的比例为主要依据"""
    if len(peaks_t) < 8: return 120.0, 0.0, 0.5
    o = np.argsort(-peaks_s)[:1200]; t = np.asarray(peaks_t)[o]; s = np.asarray(peaks_s, float)[o]
    cands = set()
    for b in _acf_candidates(env):
        for r in (1, 2, 0.5, 1.5, 2 / 3, 4 / 3, 0.75):
            x = b * r
            if 70 <= x <= 200: cands.add(round(x, 1))
    cl = []
    for c in sorted(cands):
        if not cl or c - cl[-1] > 0.6: cl.append(c)
    res = []
    for b in cl:
        coarse = max((_align(t, s, x, 0.02)[0], x) for x in np.arange(b - 1.5, b + 1.5001, 0.25))
        fine = max((_align(t, s, x, 0.01)[0], x) for x in np.arange(coarse[1] - 0.25, coarse[1] + 0.2501, 0.05))
        frac, x = fine; ph = _align(t, s, x)[1]
        res.append((frac * np.exp(-0.5 * (np.log2(x / 125.0)) ** 2), x, ph))
    res.sort(reverse=True)
    _, bpm, phi = res[0]; P = 60.0 / bpm
    return bpm, phi % P, P

def analyze(x):
    lm, mag = stft_logmag(x)
    low, mid, high = onset_envelope(lm)
    env_all = local_norm(0.5 * low + 1.0 * mid + 1.0 * high)
    pk = pick_peaks(env_all)
    # flux of frame i peaks when an onset enters the tail of its window, measured bias ~ 0.75*N
    pk_t = np.array([parabolic(env_all, i) / FPS + (0.75 * N) / SR for i in pk])
    pk_s = env_all[pk]
    bpm, phi, P = estimate_tempo(env_all, pk_t, pk_s)
    rms = np.sqrt((mag ** 2).mean(1))
    hz = SR / N; freqs = np.arange(mag.shape[1]) * hz
    centroid = ((mag[:, 1:] * freqs[1:]).sum(1) / (mag[:, 1:].sum(1) + 1e-9)).astype(np.float32)
    return dict(env=env_all, low=local_norm(low), mid=local_norm(mid), high=local_norm(high),
                peaks=pk, t=pk_t, s=pk_s, bpm=bpm, phi=phi, P=P, rms=rms, mag=mag, centroid=centroid)
