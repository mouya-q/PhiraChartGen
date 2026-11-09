import numpy as np, math
import choreo
from dataclasses import dataclass, field
from analysis import FPS, SR, N, HOP

@dataclass
class Params:
    key: str; name: str; nps: float; min_gap: float; div: int; lanes: list
    chord: float; hold: float; flick: float; drag: float; lines: int = 1; amp: float = 0; thr: float = 0.12

PARAMS = {
 'EZ': Params('EZ','EZ',1.5,0.38,2,[-270,0,270],0.0,0.12,0.0,0.0,1,0,0.14),
 'HD': Params('HD','HD',3.0,0.22,2,[-405,-135,135,405],0.06,0.15,0.05,0.05,1,2.5,0.10),
 'IN': Params('IN','IN',5.0,0.125,4,[-405,-202.5,0,202.5,405],0.18,0.14,0.07,0.2,2,3.0,0.07),
 'AT': Params('AT','AT',7.5,0.09,4,[-405,-202.5,0,202.5,405],0.30,0.14,0.09,0.3,2,5.0,0.05),
}

@dataclass
class Note:
    t: float; end: float; type: int; x: float; line: int; fake: int = 0
# type: 1 tap 2 hold 3 flick 4 drag

def norm_phase(phi, P):
    phi = phi % P
    return phi if phi >= 0.02 else phi + P

def lattice_snap(t, phi, P, div, tol=0.035):
    step = P / div
    k = round((t - phi) / step)
    ts = phi + k * step
    return ts if abs(ts - t) <= tol else t

def features(a, dur, thr):
    from analysis import pick_peaks, parabolic
    env, low, mid, high, rms, mag = a['env'], a['low'], a['mid'], a['high'], a['rms'], a['mag']
    nb = mag.shape[1]
    freqs = np.arange(nb) * SR / N
    pk = pick_peaks(env, 0.045, thr)
    nfr = len(rms)
    out = []
    for i in pk:
        t = parabolic(env, i) / FPS + (0.75 * N) / SR
        s_ = float(env[i])
        l, m, h = low[i], mid[i], high[i]
        tot = l + m + h + 1e-9
        j = min(nfr - 1, i + 2)
        spec = mag[j, 1:]
        cen = float((spec * freqs[1:]).sum() / (spec.sum() + 1e-9))
        cn = float(np.clip((math.log2(max(cen, 60)) - math.log2(100)) / (math.log2(5000) - math.log2(100)), 0, 1))
        # new partial that stays alive -> sustain length in seconds
        i4 = min(nfr - 1, i + 4); ib = max(0, i - 3)
        row = mag[i4, :190]
        pk_bins = [b for b in range(3, 188) if row[b] >= row[b - 1] and row[b] >= row[b + 1]]
        pk_bins.sort(key=lambda b: -row[b])
        sus, sb = 0.0, -1
        for b in pk_bins[:4]:
            if row[b] / (mag[ib, b - 1:b + 2].max() + 1e-6) < 1.8: continue
            ref = row[b]; f = i4
            while f < nfr - 1 and (f - i4) / FPS < 4.0 and mag[f, b - 1:b + 2].max() >= 0.5 * ref: f += 1
            ln = (f - i4) / FPS
            if ln > sus: sus, sb = ln, b
        out.append(dict(t=float(t), s=s_, hi=float(h / tot), lo=float(l / tot), cen=cn, sus=sus, sb=sb, fi=int(i)))
    return out

def partial_end(a, c, limit_t):
    mag = a['mag']; b = c['sb']; i4 = min(len(mag) - 1, c['fi'] + 4)
    ref = mag[i4, b - 1:b + 2].max()
    f = i4
    while f < len(mag) - 1 and f / FPS + 0.0 < limit_t:
        if mag[f, max(0, b - 1):b + 2].max() < 0.45 * ref: break
        f += 1
    return f / FPS + (0.75 * N) / SR - 0.1

def rms_at(a, t):
    i = int(np.clip(t * FPS, 0, len(a['rms']) - 1))
    return a['rms'][i]

def select(cands, a, p, phi, P, dur):
    rms = a['rms']
    active = np.median(rms[rms > 0.05 * rms.max()]) if np.any(rms > 0.05 * rms.max()) else 1.0
    snapped = []
    for c in cands:
        t = lattice_snap(c['t'], phi, P, p.div)
        snapped.append(dict(c, t=t))
    snapped.sort(key=lambda c: c['t'])
    # dedupe near-equal times
    dd = []
    for c in snapped:
        if dd and c['t'] - dd[-1]['t'] < 0.03:
            if c['s'] > dd[-1]['s']: dd[-1] = c
        else: dd.append(c)
    W = 3.0
    chosen = []
    t0 = 1.2
    w = t0
    step = P / p.div
    while w < dur - 0.5:
        lo, hi = w, min(dur - 0.5, w + W)
        seg = [c for c in dd if lo <= c['t'] < hi]
        mr = float(np.mean(rms[int(lo * FPS):max(int(lo * FPS) + 1, int(hi * FPS))])) if int(lo * FPS) < len(rms) else 0
        rel = 20 * math.log10((mr + 1e-9) / (active + 1e-9))
        if rel < -38: w += W; continue
        ef = float(np.clip(1.0 + rel / 36.0, 0.45, 1.3))
        K = int(round(p.nps * (hi - lo) * ef))
        acc = []
        for c in sorted(seg, key=lambda c: -c['s']):
            if len(acc) >= K: break
            if all(abs(c['t'] - o['t']) >= p.min_gap for o in acc + [x for x in chosen if lo - 1 < x['t']]):
                acc.append(c)
        if len(acc) < 0.6 * K and ef > 0.5:
            # beat fill on lattice positions with the strongest local envelope
            kk0 = int(math.ceil((lo - phi) / (P / 2)))
            pos = []
            k = kk0
            while phi + k * (P / 2) < hi:
                tt = phi + k * (P / 2)
                if tt >= lo:
                    pos.append((float(a['env'][min(len(a['env']) - 1, int(tt * FPS))]), tt))
                k += 1
            for _, tt in sorted(pos, reverse=True):
                if len(acc) >= int(0.6 * K): break
                if all(abs(tt - o['t']) >= max(p.min_gap, 0.2) for o in acc + [x for x in chosen if lo - 1 < x['t']]):
                    acc.append(dict(t=tt, s=0.3, hi=0.33, lo=0.33, cen=0.5, sus=0.0, sb=-1, fi=int(tt * FPS)))
        chosen += acc
        w += W
    chosen.sort(key=lambda c: c['t'])
    return chosen

def build_notes(chosen, a, p, phi, P, dur, seed, choreo_ch):
    rng = np.random.default_rng(seed)
    n = len(chosen)
    types = [1] * n
    ends = [c['t'] for c in chosen]
    # holds
    hold_cands = []
    for i, c in enumerate(chosen):
        if c['sus'] >= 0.45 and c['sb'] > 0:
            e = c['t'] + min(c['sus'], 2 * P)
            e = lattice_snap(e, phi, P, p.div, 0.05)
            if e - c['t'] >= max(0.25, 0.5 * P): hold_cands.append((c['sus'], i, e))
    hold_cands.sort(reverse=True)
    drop = set()
    taken = []
    for _, i, e in hold_cands:
        if len([1 for t in types if t == 2]) >= int(p.hold * n): break
        if any(chosen[i]['t'] < te and e > ts for ts, te in taken): continue
        types[i] = 2; ends[i] = e; taken.append((chosen[i]['t'], e))
        if p.key == 'EZ':
            for j in range(i + 1, n):
                if chosen[j]['t'] <= e + 0.05: drop.add(j)
                else: break
    if drop:
        keep = [j for j in range(n) if j not in drop]
        chosen = [chosen[j] for j in keep]; types = [types[j] for j in keep]; ends = [ends[j] for j in keep]; n = len(chosen)
    # flick
    if p.flick > 0:
        ss = np.array([c['s'] for c in chosen]) if n else np.array([0])
        thr = np.percentile(ss, 85)
        cand = [i for i, c in enumerate(chosen) if types[i] == 1 and c['s'] >= thr and c['hi'] >= 0.35]
        for i in cand[:int(p.flick * n)]: types[i] = 3
    # drag runs
    if p.drag > 0:
        run = []
        runs = []
        for i in range(n):
            if run and chosen[i]['t'] - chosen[run[-1]]['t'] <= 0.17 and types[i] == 1: run.append(i)
            else:
                if len(run) >= 3: runs.append(run)
                run = [i] if types[i] == 1 else []
        if len(run) >= 3: runs.append(run)
        budget = int(p.drag * n)
        for r in runs:
            for i in r[1:]:
                if budget <= 0: break
                types[i] = 4; budget -= 1
    # lanes
    L = len(p.lanes)
    lane_of = [0] * n
    lock = [-1.0] * L
    prev = L // 2; prevt = -9
    same = 0
    for i, c in enumerate(chosen):
        pref = c['cen'] * (L - 1)
        best, bs = None, 1e9
        for ln in range(L):
            if lock[ln] > c['t'] - 0.02: continue
            sc = abs(ln - pref) * 1.0 + rng.random() * 0.35
            if c['t'] - prevt < 0.3 and ln == prev: sc += 2.5
            elif ln == prev: sc += 0.8
            jump = abs(ln - prev)
            if types[i] == 4: sc += 3.0 * (jump > 1) + 0.5 * jump
            else:
                lim = 2 if L > 4 else 1
                if jump > lim: sc += 1.5 * (jump - lim)
            if sc < bs: bs, best = sc, ln
        if best is None: best = int(np.argmin(lock))
        lane_of[i] = best
        if types[i] == 2: lock[best] = ends[i] + 0.05
        prev, prevt = best, c['t']
    # lines and relative x from choreography
    def beat_of(t): return t / phi if t < phi else 1.0 + (t - phi) / P
    notes = []
    def place(t, end, typ, lane):
        b = beat_of(t); ph = choreo.phrase_at(choreo_ch, b)
        line = choreo.line_for(choreo_ch, b, lane, L)
        if typ == 2:
            if not choreo.active_through(choreo_ch, line, b, beat_of(end)):
                end = phi + (ph.t1 - 1.0) * P - 0.05
                if end - t < 0.25: typ = 1; end = t
        else: end = t
        rel = p.lanes[lane] * ph.lane_scale - choreo_ch['tracks'][line].x.at(b)
        return Note(t, end, typ, max(-640.0, min(640.0, rel)), line)
    for i, c in enumerate(chosen):
        notes.append(place(c['t'], ends[i], types[i], lane_of[i]))
    # chords
    if p.chord > 0:
        ss = np.array([c['s'] for c in chosen])
        thr = np.percentile(ss, 85)
        k = 0
        for i, c in enumerate(chosen):
            if types[i] in (1, 3) and c['s'] >= thr and rng.random() < p.chord / 0.15 * 0.6:
                a_ = lane_of[i]
                opts = [l for l in range(L) if abs(l - a_) >= 2 and lock[l] <= c['t'] - 0.02 + 1e-9]
                busy = any(abs(c['t'] - o.t) < 1e-6 and o is not notes[i] for o in notes[max(0, i - 2):i + 3])
                if opts and not busy and k < int(p.chord * n):
                    mirror = L - 1 - a_
                    ln = mirror if (mirror in opts and rng.random() < 0.6) else opts[int(rng.integers(len(opts)))]
                    notes.append(place(c['t'], c['t'], 1, ln)); k += 1
    notes.sort(key=lambda x: (x.t, x.x))
    return notes

def level_of(notes, dur):
    if not notes: return 1.0, 1
    ts = np.array([n.t for n in notes])
    span = max(10.0, ts.max() - ts.min())
    nps = len(notes) / span
    wins = np.histogram(ts, bins=np.arange(0, dur + 3, 3.0))[0] / 3.0
    p95 = np.percentile(wins[wins > 0], 95) if np.any(wins > 0) else 0
    chord = sum(1 for i in range(1, len(notes)) if abs(notes[i].t - notes[i - 1].t) < 1e-6) / len(notes)
    drag = sum(1 for n in notes if n.type == 4) / len(notes)
    v = 1.0 + 1.55 * nps + 0.5 * p95 + 2.0 * chord + 1.5 * drag
    v = float(np.clip(v, 1, 17))
    return round(v, 1), int(round(v))

def make_chart(a, p, dur, seed=7):
    phi = norm_phase(a['phi'], a['P']); P = a['P']
    cands = features(a, dur, p.thr)
    chosen = select(cands, a, p, phi, P, dur)
    tb = lambda t: t / phi if t < phi else 1.0 + (t - phi) / P
    cho = choreo.build(a['rms'], FPS, p, phi, P, dur, np.random.default_rng(seed + 101), a['bpm'], tb, [tb(c['t']) for c in chosen])
    notes = build_notes(chosen, a, p, phi, P, dur, seed, cho)
    diff, lvl = level_of(notes, dur)
    return dict(p=p, notes=notes, choreo=cho, bpm=a['bpm'], phi=phi, P=P, dur=dur, difficulty=diff, level=lvl)
