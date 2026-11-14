"""判定线编排 v2: 依据玩家谱的统计与手法重写
主线承载绝大多数音符并保持稳定; 动画主要由不带音符的装饰线提供; 高潮段偶尔拆线或旋转"""
import math
import numpy as np

def ease(t, u):
    if t == 2: return math.sin(u * math.pi / 2)
    if t == 3: return 1 - math.cos(u * math.pi / 2)
    if t == 4: return 1 - (1 - u) ** 2
    if t == 6: return 0.5 - math.cos(math.pi * u) / 2
    if t == 20: return 1 + 2.70158 * (u - 1) ** 3 + 1.70158 * (u - 1) ** 2
    return u

class Chan:
    def __init__(self, init):
        self.value = init; self.cursor = 0.0; self.segs = []
    def to(self, beat, v, easing=6):
        if beat <= self.cursor + 1e-9: return
        self.segs.append((self.cursor, beat, self.value, v, easing)); self.cursor = beat; self.value = v
    def hold(self, beat): self.to(beat, self.value, 1)
    def finish(self, end):
        if self.cursor < end: self.hold(end)
    def at(self, beat):
        if not self.segs: return self.value
        if beat <= self.segs[0][0]: return self.segs[0][2]
        for s, e, a, b, ez in self.segs:
            if beat < e: return a + (b - a) * ease(ez, (beat - s) / (e - s))
        return self.segs[-1][3]

class Track:
    def __init__(self, x0=0.0, y0=-260.0, a0=0.0, notes=True):
        self.x = Chan(x0); self.y = Chan(y0); self.rot = Chan(0.0); self.alpha = Chan(a0); self.carries_notes = notes
    def hold_all(self, b):
        for c in (self.x, self.y, self.rot, self.alpha):
            if c.cursor < b - 1e-9: c.hold(b)

class Phrase:
    def __init__(self, t0, t1, active, style, lane_scale=1.0, energy=0.0):
        self.t0 = t0; self.t1 = t1; self.active = active; self.style = style; self.lane_scale = lane_scale; self.energy = energy

BASE_Y = -260.0
TILT = {'EZ': 1.5, 'HD': 2.5, 'IN': 3.0, 'AT': 3.0}
DIP = {'EZ': 0.0, 'HD': 12.0, 'IN': 20.0, 'AT': 24.0}
DECOR = {'EZ': 0, 'HD': 2, 'IN': 6, 'AT': 10}
def base_rot(v): return round(v / 360.0) * 360.0

def steady(tr, t0, t1, key, pulse):
    x, y, r = tr.x, tr.y, tr.rot
    rb = base_rot(r.value)
    x.to(t0 + 1, 0.0); x.hold(t1)
    if abs(y.value - BASE_Y) > 1.0: y.to(t0 + 1.0, BASE_Y)
    amp = TILT[key]; D = DIP[key] * (1.25 if pulse else 1.0)
    stride = 1.0 if pulse else 2.0
    sgn = 1.0
    k = t0
    while k + 2.0 <= t1 + 1e-9:
        # 2 拍一组的倾斜: 1 拍摆出, 过冲回弹, 归零
        r.hold(k); r.to(k + 1.0, rb + sgn * amp, 4); r.to(k + 1.625, rb - sgn * amp / 3, 20); r.to(k + 2.0, rb, 4)
        sgn = -sgn; k += 2.0
    if D > 0:
        k = t0
        while k + stride <= t1 + 1e-9:
            y.hold(k); y.to(k + 0.25, BASE_Y - D, 4); y.to(k + stride - 0.1 if pulse else k + 1.5, BASE_Y, 4)
            k += stride
    y.hold(t1); r.hold(t1)

def build(a_rms, fps, p, phi, P, dur, rng, bpm, to_beat, note_beats):
    key = p.key
    end_beat = to_beat(dur) + 8.0
    n = int(math.ceil((end_beat - 1.0) / 8.0))
    p90 = float(np.percentile(a_rms, 90)) + 1e-9
    energies = []
    for i in range(n):
        s = phi + 8 * i * P; e = s + 8 * P
        f0 = int(np.clip(s * fps, 0, len(a_rms) - 1)); f1 = int(np.clip(e * fps, f0 + 1, len(a_rms)))
        energies.append(float(np.mean(a_rms[f0:f1])) / p90)
    main = Track(0, BASE_Y, 0.0); split = Track(0, BASE_Y, 0.0)
    tracks = [main, split]
    phrases = []
    since_piece = 9; dir_ = 1.0
    for i in range(n):
        t0 = 1.0 + 8 * i; t1 = t0 + 8; e = energies[i]
        style = 'STEADY'; active = [0]; scale = 1.0
        if key in ('IN', 'AT') and e > 0.85 and since_piece >= 3 and rng.random() < 0.5:
            style = ['SPLIT', 'SPIN'][int(rng.integers(2))]
        elif e > 0.6 and key != 'EZ': style = 'PULSE'
        dir_ = -dir_
        if style in ('STEADY', 'PULSE'):
            steady(main, t0, t1, key, style == 'PULSE'); since_piece += 1
        elif style == 'SPLIT':
            active = [0, 1]; off = 260.0 if key != 'AT' else 300.0; tilt = 16.0 if key == 'IN' else 22.0
            for j, sgn in ((0, -1.0), (1, 1.0)):
                t = tracks[j]; rb = base_rot(t.rot.value)
                t.x.to(t0 + 1, sgn * off); t.x.hold(t1)
                t.y.to(t0 + 1, BASE_Y + 10.0 * (-sgn), 6); t.y.to(t1, BASE_Y - 20.0 * (-sgn), 6)
                t.rot.to(t0 + 1, rb - sgn * tilt); t.rot.to(t1, rb - sgn * (tilt - 5.0))
            since_piece = 0
        else:  # SPIN
            scale = 0.7; t = main; rb = base_rot(t.rot.value)
            t.x.to(t0 + 1, 0.0); t.x.hold(t1)
            t.y.to(t0 + 1, -40.0, 6); t.y.hold(t1)
            if key == 'IN':
                t.rot.to(t0 + 4.5, rb + dir_ * 60.0, 6); t.rot.to(t1, rb, 6)
            else:
                t.rot.to(t1, rb + dir_ * 360.0, 6)
            since_piece = 0
        if 1 not in active:
            for c in (split.x, split.y, split.rot): c.hold(t1)
        phrases.append(Phrase(t0, t1, active, style, scale, e))
    # ---- 主线透明度: 音符之间的长空白期淡出, 音符出现前淡入
    nb = sorted(note_beats)
    groups = []
    for b in nb:
        if groups and b - groups[-1][1] < 10.0: groups[-1][1] = b
        else: groups.append([b, b])
    a = main.alpha
    for g0, g1 in groups:
        a.hold(max(a.cursor, g0 - 4.0)); a.to(max(a.cursor + 0.01, g0 - 2.0), 255.0, 3)
        a.hold(g1 + 1.0); a.to(g1 + 3.0, 0.0, 2)
    # ---- 分线透明度: 仅在使用它的乐句可见
    sa = split.alpha
    for i, ph in enumerate(phrases):
        cur = 1 in ph.active; nxt = (1 in phrases[i + 1].active) if i + 1 < len(phrases) else False
        prv = (1 in phrases[i - 1].active) if i > 0 else False
        if cur and not prv: sa.hold(ph.t0 - 1.0); sa.to(ph.t0, 255.0, 6)
        if cur and not nxt: sa.hold(ph.t1); sa.to(ph.t1 + 1.0, 0.0, 6)
    used_split = any(1 in ph.active for ph in phrases)
    # ---- 装饰线 (不带音符, 提供视觉效果)
    decor = []
    budget = DECOR[key]
    def get_line(b, dur_b):
        for d in decor:
            if d.busy <= b - 0.01:
                d.busy = b + dur_b; d.hold_all(b); return d
        if len(decor) < budget:
            d = Track(0, 0, 0.0, notes=False); d.busy = b + dur_b; d.hold_all(b); decor.append(d); return d
        return None
    def settle(d, b, x, y, r):
        """在透明状态下 0.05 拍内移到起点, 避免事件间的数值跳变"""
        d.x.to(b, x, 1); d.y.to(b, y, 1); d.rot.to(b, r, 1)
    first_note = nb[0] if nb else 1e9
    mirror = 1.0
    last_burst = -9
    for i, ph in enumerate(phrases):
        if budget == 0: break
        e = ph.energy; prev_e = phrases[i - 1].energy if i > 0 else 0.0
        t0 = ph.t0
        if i < 2 or (e > 0.9 and key in ('IN', 'AT') and i % 2 == 0):
            # 节拍闪烁扫线: 近竖直的线每 2 拍从一侧扫入, 透明度每拍脉冲一次
            for side in (1.0, -1.0):
                d = get_line(t0 - 0.06, 8.06)
                if d is None: break
                for k in range(4):
                    b = t0 + 2 * k
                    s2 = side * (1.0 if k % 2 == 0 else -1.0)
                    d.hold_all(b - 0.06)
                    settle(d, b, s2 * 600.0, 0.0, 80.0 * s2)
                    d.x.to(b + 2.0, s2 * 400.0, 2); d.y.hold(b + 2.0); d.rot.to(b + 2.0, 85.0 * s2, 2)
                    d.alpha.to(b + 1.0, 120.0, 2); d.alpha.to(b + 2.0, 0.0, 3)
                d.busy = t0 + 8.0
        if (e - prev_e > 0.25 or e > 0.9) and i - last_burst >= 2 and i > 0:
            # 段落开头的对称呼啸扫线
            for side in (1.0, -1.0):
                d = get_line(t0 - 0.06, 1.81)
                if d is None: break
                d.hold_all(t0 - 0.06)
                settle(d, t0, side * 350.0 * mirror, side * 200.0, -315.0 * side * mirror)
                d.x.to(t0 + 1.75, side * 700.0 * mirror, 2); d.y.to(t0 + 1.75, side * 600.0, 2); d.rot.to(t0 + 1.75, -360.0 * side * mirror, 2)
                d.alpha.to(t0 + 0.5, 120.0, 2); d.alpha.to(t0 + 1.75, 0.0, 2)
            mirror = -mirror; last_burst = i
        if e > 0.75 and key in ('IN', 'AT'):
            # 回声线: 从主线位置向下扩散并淡出, 每 1.5 拍一次
            d = get_line(t0 - 0.06, 7.56)
            if d is not None:
                for k in range(5):
                    b = t0 + 1.5 * k
                    d.hold_all(b - 0.06)
                    settle(d, b, 0.0, BASE_Y, 0.0)
                    d.alpha.to(b + 0.02, 200.0, 1)
                    d.y.to(b + 1.5, BASE_Y - 200.0, 2); d.alpha.to(b + 1.5, 0.0, 2); d.x.hold(b + 1.5); d.rot.hold(b + 1.5)
                d.busy = t0 + 7.5
    out = [main] + ([split] if used_split else []) + decor
    # 所有通道统一结束在乐句对齐的位置之后, 保证覆盖全曲且长度一致
    end_beat = max([1.0 + 8 * n] + [c.cursor for t in out for c in (t.x, t.y, t.rot, t.alpha)])
    for t in out:
        for c in (t.x, t.y, t.rot, t.alpha): c.finish(end_beat)
    # 记录用于音符分配的线编号映射 (分线编号 1 若未使用则不存在)
    return dict(tracks=out, phrases=phrases, end_beat=end_beat, note_lines=2 if used_split else 1)

def phrase_at(ch, beat):
    i = int((beat - 1.0) // 8) if beat >= 1.0 else 0
    return ch['phrases'][max(0, min(i, len(ch['phrases']) - 1))]
def line_for(ch, beat, lane, lane_count):
    ph = phrase_at(ch, beat)
    return ph.active[lane * len(ph.active) // lane_count]
def active_through(ch, line, b0, b1):
    i0 = int((max(b0, 1.0) - 1.0) // 8); i1 = int((max(b1, 1.0) - 1.0) // 8)
    for i in range(i0, min(i1, len(ch['phrases']) - 1) + 1):
        if line not in ch['phrases'][i].active: return False
    return True
