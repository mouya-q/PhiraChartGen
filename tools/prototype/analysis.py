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

def estimate_tempo(env, peaks_t, peaks_s):
    e = env - env.mean()
    n = len(e)
    f = np.fft.rfft(e, 2 * n)
    ac = np.fft.irfft(f * np.conj(f))[:n]
    ac /= ac[0] + 1e-9
    best = None
    cands = []
    for bpm in np.arange(60, 200.01, 0.5):
        lag = 60.0 / bpm * FPS
        s = 0.0
        for mult, wgt in ((1, 1.0), (2, 0.5), (4, 0.25)):
            l = lag * mult
            i0 = int(l)
            if i0 + 1 >= n: continue
            fr = l - i0
            s += wgt * (ac[i0] * (1 - fr) + ac[i0 + 1] * fr)
        prior = np.exp(-0.5 * (np.log2(bpm / 120.0) / 0.9) ** 2)
        cands.append((s * prior, bpm))
    cands.sort(reverse=True)
    coarse = cands[0][1]
    # refine on onset times
    def align(bpm):
        P = 60.0 / bpm
        sig = 0.025
        best_phi, best_s = 0.0, -1
        for phi in np.arange(0, P, 0.005):
            d = ((peaks_t - phi + P / 4) % (P / 2)) - P / 4  # half-beat grid
            s = (peaks_s * np.exp(-d * d / (2 * sig * sig))).sum()
            if s > best_s: best_s, best_phi = s, phi
        return best_s, best_phi
    bs, bb, bp = -1, coarse, 0
    for bpm in np.arange(coarse - 3, coarse + 3.001, 0.05):
        s, phi = align(bpm)
        if s > bs: bs, bb, bp = s, bpm, phi
    # fine phase
    P = 60.0 / bb
    return bb, bp % P, P

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
    return dict(env=env_all, low=local_norm(low), mid=local_norm(mid), high=local_norm(high),
                peaks=pk, t=pk_t, s=pk_s, bpm=bpm, phi=phi, P=P, rms=rms, mag=mag)
