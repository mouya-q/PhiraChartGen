import numpy as np

SR = 22050

def synth(bpm=128.0, phase=0.37, dur=90.0, seed=1):
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    x = np.zeros(n)
    P = 60.0 / bpm
    truth = []
    def add(t, sig):
        i = int(t * SR)
        if i >= n: return
        j = min(n, i + len(sig))
        x[i:j] += sig[:j - i]
    def kick():
        t = np.arange(int(0.25 * SR)) / SR
        f = 50 + 110 * np.exp(-t * 28)
        return 0.9 * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 14)
    def snare():
        t = np.arange(int(0.18 * SR)) / SR
        return 0.5 * rng.standard_normal(len(t)) * np.exp(-t * 28)
    def hat():
        t = np.arange(int(0.05 * SR)) / SR
        h = rng.standard_normal(len(t))
        h = np.diff(h, prepend=0)
        return 0.18 * h * np.exp(-t * 90)
    def tone(freq, length, amp=0.25):
        t = np.arange(int(length * SR)) / SR
        env = np.minimum(1, t / 0.01) * np.minimum(1, (length - t) / 0.05)
        return amp * np.sin(2 * np.pi * freq * t) * env
    k, s, h = kick(), snare(), hat()
    nb = int((dur - phase) / P)
    for b in range(nb):
        t = phase + b * P
        sec = b // 16
        if t > 12: add(t, k); truth.append((t, 'kick'))
        if t > 12 and b % 2 == 1: add(t, s); truth.append((t, 'snare'))
        if t > 4:
            add(t, h); add(t + P / 2, h)
        # held bass pad every 4 beats from 20s
        if t > 20 and b % 4 == 0:
            add(t, tone(110, 3.5 * P))
            truth.append((t, 'hold'))
        # arpeggio 16ths in a middle section
        if 40 < t < 60:
            for q in range(4):
                add(t + q * P / 4, tone([440, 523, 659, 784][q], P / 5, 0.12))
                truth.append((t + q * P / 4, 'arp'))
    x += 0.002 * rng.standard_normal(n)
    x /= max(1e-9, np.max(np.abs(x))) * 1.05
    return x.astype(np.float32), truth, P
