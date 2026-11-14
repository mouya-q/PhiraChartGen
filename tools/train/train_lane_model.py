#!/usr/bin/env python3
"""用玩家谱训练轨道选择小模型

输入: 一个目录, 其下每个子目录是一张 Phira 谱 (RPE 的 json + 音频 mp3/ogg/wav)
输出: lane_model.json 以及 Kotlin 参数文件 LaneModelData.kt

做法:
 1. 用 ffmpeg 解码音频, 运行原型的分析, 得到逐帧特征
 2. 自动估计谱面时间与音频时间的对齐偏移
 3. 取每张谱音符最多的一条线, 只保留线静止时的音符 (其它情况的布局受判定线运动影响)
 4. 特征: 起音强度, 频段占比, 亮度, 主导谱线, 低频倾向, 相对响度, 与上一个音的间隔, 上一个与上上个轨道, 是否乐段开头
 5. 左右镜像增强后训练多项逻辑回归, 按歌曲留一验证

依赖: numpy, scikit-learn, ffmpeg
用法: python3 tools/train/train_lane_model.py <谱面目录> [--kotlin core/src/main/kotlin/com/phiragen/core/chart/LaneModelData.kt]
"""
import argparse, json, math, os, subprocess, sys, warnings
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'prototype'))
from analysis import analyze, pick_peaks, FPS, SR, N  # noqa: E402

CLASS_X = np.array([-405.0, -202.5, 0.0, 202.5, 405.0])
AUDIO_EXT = ('.mp3', '.ogg', '.wav', '.flac', '.m4a')


def bt(b):
    return b[0] + b[1] / b[2] if b and b[2] else 0.0


def ease(t, u):
    u = min(1.0, max(0.0, u))
    if t == 2: return math.sin(u * math.pi / 2)
    if t == 3: return 1 - math.cos(u * math.pi / 2)
    if t == 4: return 1 - (1 - u) ** 2
    if t == 5: return u * u
    if t == 6: return -(math.cos(math.pi * u) - 1) / 2
    return u  # 其余缓动只用于判断主线是否静止, 线性近似足够


def channel_at(layers, key, beat):
    total = 0.0
    for layer in layers:
        if not layer: continue
        evs = layer.get(key) or []
        val = None
        for e in evs:
            s, en = bt(e['startTime']), bt(e['endTime'])
            if beat >= en: val = e['end']
            elif beat >= s:
                val = e['start'] + (e['end'] - e['start']) * ease(e.get('easingType', 1), (beat - s) / max(1e-9, en - s)); break
            else: break
        if val is None and evs: val = evs[0]['start']
        total += val or 0.0
    return total


def decode(path):
    out = subprocess.run(['ffmpeg', '-v', 'quiet', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'], capture_output=True).stdout
    return np.frombuffer(out, dtype=np.float32).copy()


def best_shift(times, a):
    pk = pick_peaks(a['env'], 0.045, 0.12)
    ot = pk / FPS + 0.75 * N / SR
    best = (-1.0, 0.0)
    for sh in np.arange(-0.6, 0.6, 0.005):
        t = times + sh
        idx = np.clip(np.searchsorted(ot, t), 1, len(ot) - 1)
        d = np.minimum(np.abs(ot[idx] - t), np.abs(ot[idx - 1] - t))
        sc = float(np.mean(d < 0.03))
        if sc > best[0]: best = (sc, float(sh))
    return best


def frame_feats(a, t, p90):
    n = len(a['env']); f = int(np.clip(t * FPS - 0.75 * N / SR * FPS, 0, n - 1))
    lo, hi = max(0, f - 2), min(n, f + 3)
    env = float(a['env'][lo:hi].max()); l = float(a['low'][lo:hi].max()); m = float(a['mid'][lo:hi].max()); h = float(a['high'][lo:hi].max())
    tot = l + m + h + 1e-9; j = min(n - 1, f + 2)
    cen = float(np.clip((math.log2(max(float(a['centroid'][j]), 60)) - math.log2(100)) / (math.log2(5000) - math.log2(100)), 0, 1))
    row = a['mag'][j, 3:190]
    return [env, l / tot, m / tot, h / tot, cen, float(np.argmax(row)) / 187.0, float(row[:60].sum() / (row.sum() + 1e-9)), float(a['rms'][j]) / p90]


def onehot(c):
    v = np.zeros(5)
    if c is not None: v[c] = 1
    return list(v)


def build_dataset(root):
    X, Y, G = [], [], []
    groups = {}
    for d in sorted(os.listdir(root)):
        p = os.path.join(root, d)
        if not os.path.isdir(p): continue
        jf = [f for f in os.listdir(p) if f.endswith('.json')]
        af = [f for f in os.listdir(p) if f.lower().endswith(AUDIO_EXT)]
        if not jf or not af: continue
        j = json.load(open(os.path.join(p, jf[0]), encoding='utf-8'))
        if 'judgeLineList' not in j or 'BPMList' not in j: continue
        x = decode(os.path.join(p, af[0]))
        if len(x) < SR * 20: continue
        a = analyze(x); dur = len(x) / SR
        hz = SR / N; freqs = np.arange(a['mag'].shape[1]) * hz
        a['centroid'] = ((a['mag'][:, 1:] * freqs[1:]).sum(1) / (a['mag'][:, 1:].sum(1) + 1e-9)).astype(np.float32)
        bpm = j['BPMList'][0]['bpm']; P = 60.0 / bpm
        L = j['judgeLineList']
        main = max(range(len(L)), key=lambda i: len(L[i].get('notes', [])))
        if L[main].get('father', -1) >= 0: continue
        times = np.array(sorted({bt(n['startTime']) * P for l in L for n in l.get('notes', [])}))
        hit, shift = best_shift(times, a)
        if hit < 0.4:
            print('跳过 %s: 音符与音频对不上 (命中率 %.0f%%)' % (d, 100 * hit)); continue
        gid = groups.setdefault((round(dur), round(bpm)), len(groups))
        p90 = float(np.percentile(a['rms'], 90)) + 1e-9
        layers = L[main].get('eventLayers', [])
        by = {}
        for n in L[main]['notes']: by.setdefault(round(bt(n['startTime']), 3), []).append(n)
        rng = np.random.default_rng(1)
        seq = []
        for t, g in sorted(by.items()):
            n = g[int(rng.integers(len(g)))]
            b = bt(n['startTime'])
            if abs(channel_at(layers, 'moveXEvents', b)) > 40 or abs(((channel_at(layers, 'rotateEvents', b) + 180) % 360) - 180) > 12: continue
            if abs(n['positionX']) > 480: continue
            c = int(np.argmin(np.abs(CLASS_X - np.clip(n['positionX'], -405, 405))))
            seq.append((t * P + shift, c))
        prev = prev2 = pt = None
        for ts, c in seq:
            dt = 1.0 if pt is None else min(2.0, max(0.05, ts - pt))
            feat = frame_feats(a, ts, p90) + [math.log(dt) / math.log(40) + 1.0] + onehot(prev) + onehot(prev2) + [1.0 if (pt is None or ts - pt > 1.5) else 0.0]
            X.append(feat); Y.append(c); G.append(gid)
            prev2, prev, pt = prev, c, ts
        print('%s: bpm %.2f 对齐命中率 %.0f%% 样本 %d' % (d, bpm, 100 * hit, len(seq)))
    return np.array(X), np.array(Y), np.array(G)


def mirror(X, Y):
    Xm = X.copy()
    Xm[:, 9:14] = X[:, 9:14][:, ::-1]; Xm[:, 14:19] = X[:, 14:19][:, ::-1]
    return Xm, 4 - Y


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('charts'); ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lane_model.json'))
    ap.add_argument('--kotlin', default=None)
    args = ap.parse_args()
    warnings.filterwarnings('ignore')
    from sklearn.linear_model import LogisticRegression
    from sklearn.preprocessing import StandardScaler
    from sklearn.metrics import log_loss
    X, Y, G = build_dataset(args.charts)
    if len(Y) < 500: sys.exit('样本太少 (%d), 无法训练' % len(Y))
    print('样本 %d, 歌曲 %d, 类别分布 %s' % (len(Y), len(set(G)), np.round(np.bincount(Y, minlength=5) / len(Y), 3)))
    if len(set(G)) > 1:
        ll = []
        for g in sorted(set(G)):
            tr, te = G != g, G == g
            Xm, Ym = mirror(X[tr], Y[tr]); Xa = np.vstack([X[tr], Xm]); Ya = np.concatenate([Y[tr], Ym])
            sc = StandardScaler().fit(Xa); m = LogisticRegression(C=1.0, max_iter=500).fit(sc.transform(Xa), Ya)
            ll.append(log_loss(Y[te], m.predict_proba(sc.transform(X[te])), labels=list(range(5))))
        print('按歌曲留一验证对数损失 %.3f (随机 %.3f)' % (np.mean(ll), math.log(5)))
    Xm, Ym = mirror(X, Y); Xa = np.vstack([X, Xm]); Ya = np.concatenate([Y, Ym])
    sc = StandardScaler().fit(Xa); m = LogisticRegression(C=1.0, max_iter=1000).fit(sc.transform(Xa), Ya)
    model = dict(features=20, classes=5, mean=[round(float(v), 6) for v in sc.mean_], std=[round(float(v), 6) for v in sc.scale_],
                 weights=[[round(float(v), 5) for v in r] for r in m.coef_], bias=[round(float(v), 5) for v in m.intercept_],
                 trained_on=dict(samples=int(len(Y)), songs=int(len(set(G))), mirror_augmented=True))
    json.dump(model, open(args.out, 'w'))
    print('已写入', args.out)
    if args.kotlin:
        arr = lambda v: 'doubleArrayOf(' + ', '.join(repr(float(x)) for x in v) + ')'
        src = ('package com.phiragen.core.chart\n\n/**\n * 轨道选择小模型的参数: 20 维特征到 5 个轨道类别的多项逻辑回归.\n'
               ' * 由 tools/train/train_lane_model.py 在玩家谱及其音乐上训练并导出 (镜像增强, 左右对称),\n * 不要手工修改\n */\n'
               'internal object LaneModelData {\n    val MEAN = %s\n    val STD = %s\n    val BIAS = %s\n    val WEIGHTS = arrayOf(\n%s\n    )\n}\n'
               % (arr(model['mean']), arr(model['std']), arr(model['bias']), ',\n'.join('        ' + arr(r) for r in model['weights'])))
        open(args.kotlin, 'w', encoding='utf-8').write(src)
        print('已写入', args.kotlin)


if __name__ == '__main__':
    main()
