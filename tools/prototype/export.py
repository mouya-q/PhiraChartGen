import json, math, io, zipfile, wave
import numpy as np
from math import gcd

DEN = 960

def beat_of(t, ch):
    phi, P = ch['phi'], ch['P']
    return t / phi if t < phi else 1.0 + (t - phi) / P

def beat_triple(b):
    if b < 0: b = 0.0
    whole = int(math.floor(b))
    num = int(round((b - whole) * DEN))
    if num >= DEN: whole += 1; num = 0
    g = gcd(num, DEN)
    return [whole, num // g, DEN // g]

def seg_events(chan):
    return [ev_(s, en, f, t, ez) for (s, en, f, t, ez) in chan.segs]

def ev_(st, en, a, b, easing):
    return {"bezier": 0, "bezierPoints": [0.0, 0.0, 0.0, 0.0], "easingLeft": 0.0, "easingRight": 1.0,
            "easingType": easing, "end": b, "endTime": beat_triple(en), "linkgroup": 0, "start": a, "startTime": beat_triple(st)}

def controls(key, val):
    return [{"easing": 1, key: val, "x": 0.0}, {"easing": 1, key: val, "x": 9999999.0}]

def rpe(ch, info, speed=10.0):
    phi = ch['phi']; cho = ch['choreo']
    lead = 60.0 / phi
    bpmlist = [{"bpm": round(lead, 6), "startTime": [0, 0, 1]}, {"bpm": round(ch['bpm'], 6), "startTime": [1, 0, 1]}]
    lines = []
    for li, t in enumerate(cho['tracks']):
        layer = {
            "speedEvents": [{"end": speed, "endTime": beat_triple(cho['end_beat']), "linkgroup": 0, "start": speed, "startTime": [0, 0, 1]}],
            "moveXEvents": seg_events(t.x), "moveYEvents": seg_events(t.y),
            "rotateEvents": seg_events(t.rot), "alphaEvents": seg_events(t.alpha)}
        nl = [n for n in ch['notes'] if n.line == li]
        notes = []
        for n in nl:
            notes.append({"above": 1, "alpha": 255, "startTime": beat_triple(beat_of(n.t, ch)),
                          "endTime": beat_triple(beat_of(n.end if n.type == 2 else n.t, ch)),
                          "isFake": 0, "positionX": float(n.x), "size": 1.0, "speed": 1.0, "type": n.type,
                          "visibleTime": 999999.0, "yOffset": 0.0})
        lines.append({
            "Group": 0, "Name": "Line %d" % (li + 1), "Texture": "line.png", "anchor": [0.5, 0.5],
            "bpmfactor": 1.0, "eventLayers": [layer],
            "extended": {"inclineEvents": [ev_(0.0, 1.0, 0.0, 0.0, 0)]},
            "father": -1, "isCover": 1, "isGif": False, "notes": notes,
            "numOfNotes": sum(1 for n in nl if n.type != 2),
            "posControl": controls("pos", 1.0), "sizeControl": controls("size", 1.0), "skewControl": controls("skew", 0.0),
            "yControl": controls("y", 1.0), "alphaControl": controls("alpha", 1.0), "zOrder": 0, "rotateWithFather": True})
    return {"BPMList": bpmlist,
            "META": {"RPEVersion": 160, "background": info['illustration'], "charter": info['charter'], "composer": info['composer'],
                     "id": "pcg-%s" % info['name'].replace(' ', '_'), "illustration": info['illustrator'], "level": info['level'],
                     "name": info['name'], "offset": 0, "song": info['music']},
            "judgeLineGroup": ["Default"], "judgeLineList": lines,
            "multiLineString": " ".join(str(i + 1) for i in range(len(lines))), "multiScale": 1.0, "xybind": False}

def pieces(*chans):
    marks = sorted(set(m for c in chans for s in c.segs for m in (s[0], s[1])))
    out = []
    for a, b in zip(marks, marks[1:]):
        varying = any(abs(c.at(a) - c.at(b)) > 1e-9 for c in chans)
        cnt = max(2, min(12, int(math.ceil((b - a) * 1.5)))) if varying else 1
        for k in range(cnt): out.append((a + (b - a) * k / cnt, a + (b - a) * (k + 1) / cnt))
    return out

def sec_of(beat, ch):
    return beat * ch['phi'] if beat < 1.0 else ch['phi'] + (beat - 1.0) * ch['P']

def official(ch, info, speed=10.0):
    P = ch['P']; unit = P / 32.0; sp = speed / 4.5; cho = ch['choreo']
    FAR = 1000000000; BEFORE = -999999
    u = lambda beat: int(round(sec_of(beat, ch) / unit))
    fx = lambda x: round(0.5 + x / 1350.0, 5); fy = lambda y: round((y + 450.0) / 900.0, 5)
    lines = []
    for li, t in enumerate(cho['tracks']):
        last = cho['end_beat']
        mv = [dict(startTime=BEFORE, endTime=0, start=fx(t.x.at(0)), end=fx(t.x.at(0)), start2=fy(t.y.at(0)), end2=fy(t.y.at(0)))]
        for a, b in pieces(t.x, t.y):
            mv.append(dict(startTime=u(a), endTime=u(b), start=fx(t.x.at(a)), end=fx(t.x.at(b)), start2=fy(t.y.at(a)), end2=fy(t.y.at(b))))
        mv.append(dict(startTime=u(last), endTime=FAR, start=fx(t.x.at(last)), end=fx(t.x.at(last)), start2=fy(t.y.at(last)), end2=fy(t.y.at(last))))
        rt = [dict(startTime=BEFORE, endTime=0, start=round(t.rot.at(0), 3), end=round(t.rot.at(0), 3))]
        for a, b in pieces(t.rot): rt.append(dict(startTime=u(a), endTime=u(b), start=round(t.rot.at(a), 3), end=round(t.rot.at(b), 3)))
        rt.append(dict(startTime=u(last), endTime=FAR, start=round(t.rot.at(last), 3), end=round(t.rot.at(last), 3)))
        ds = [dict(startTime=BEFORE, endTime=0, start=round(t.alpha.at(0) / 255, 3), end=round(t.alpha.at(0) / 255, 3))]
        for a, b in pieces(t.alpha): ds.append(dict(startTime=u(a), endTime=u(b), start=round(t.alpha.at(a) / 255, 3), end=round(t.alpha.at(b) / 255, 3)))
        ds.append(dict(startTime=u(last), endTime=FAR, start=round(t.alpha.at(last) / 255, 3), end=round(t.alpha.at(last) / 255, 3)))
        above = []
        for n in [n for n in ch['notes'] if n.line == li]:
            hold = (n.end - n.t) / unit if n.type == 2 else 0.0
            above.append(dict(type=n.type, time=int(round(n.t / unit)), holdTime=round(hold, 3), positionX=round(n.x / 75.9375, 4),
                              speed=1.0, floorPosition=round(sp * n.t, 4)))
        lines.append(dict(bpm=round(ch['bpm'], 6), notesAbove=above, notesBelow=[],
                          speedEvents=[dict(startTime=0, endTime=FAR, value=round(sp, 6), floorPosition=0.0)],
                          judgeLineMoveEvents=mv, judgeLineRotateEvents=rt, judgeLineDisappearEvents=ds))
    return {"formatVersion": 3, "offset": 0.0, "numOfNotes": len(ch['notes']), "judgeLineList": lines}

def pec(ch, info, speed=10.0):
    phi = ch['phi']; cho = ch['choreo']
    px = lambda x: x * 1024.0 / 675.0; py = lambda y: y * 700.0 / 450.0
    L = ["0", "bp 0.000 %.3f" % (60.0 / phi), "bp 1.000 %.3f" % ch['bpm']]
    for li, t in enumerate(cho['tracks']):
        L.append("cv %d 0.000 %.3f" % (li, speed))
        L.append("cp %d 0.000 %.3f %.3f" % (li, px(t.x.at(0)), py(t.y.at(0))))
        L.append("ca %d 0.000 %d" % (li, round(t.alpha.at(0))))
        L.append("cd %d 0.000 %.3f" % (li, -t.rot.at(0)))
        for a, b in pieces(t.x, t.y): L.append("cm %d %.3f %.3f %.3f %.3f 1" % (li, a, b, px(t.x.at(b)), py(t.y.at(b))))
        for a, b in pieces(t.rot): L.append("cr %d %.3f %.3f %.3f 1" % (li, a, b, -t.rot.at(b)))
        for a, b in pieces(t.alpha):
            if abs(t.alpha.at(a) - t.alpha.at(b)) < 1e-9: continue
            L.append("cf %d %.3f %.3f %d" % (li, a, b, round(t.alpha.at(b))))
    for n in ch['notes']:
        b = beat_of(n.t, ch); x = px(n.x)
        if n.type == 2: L.append("n2 %d %.3f %.3f %.3f 1 0" % (n.line, b, beat_of(n.end, ch), x))
        else: L.append("n%d %d %.3f %.3f 1 0" % (n.type, n.line, b, x))
        L.append("# 1.00"); L.append("& 1.00")
    return "\n".join(L) + "\n"

def level_text(ch):
    return "%s Lv.%d" % (ch['p'].key, ch['level'])

def write_wav(path, x, sr):
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())

def yml(info):
    def q(s): return '"%s"' % str(s).replace('\\', '\\\\').replace('"', '\\"')
    lines = ["name: " + q(info['name']), "difficulty: %.1f" % info['difficulty'], "level: " + q(info['level']),
             "charter: " + q(info['charter']), "composer: " + q(info['composer']), "illustrator: " + q(info['illustrator']),
             "chart: " + info['chart'], "music: " + info['music'], "illustration: " + info['illustration'],
             "previewStart: %.2f" % info['previewStart'], "aspectRatio: 1.7777778", "backgroundDim: 0.6", "lineLength: 6.0",
             "offset: %.3f" % info.get('offset', 0.0), "tags: []", "intro: " + q(info.get('intro', '')), "holdPartialCover: false"]
    return "\n".join(lines) + "\n"
