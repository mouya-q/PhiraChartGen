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

def line_events(ch, line_idx):
    """returns dict of event lists in beats: rotate, moveY"""
    p = ch['p']; P = ch['P']; phi = ch['phi']
    end_beat = beat_of(ch['dur'], ch) + 8
    base_y = -150 if p.lines == 1 else (-110 if line_idx == 0 else -270)
    rot, my = [], []
    b = 1.0
    sign = 1 if line_idx == 0 else -1
    if p.amp > 0:
        while b < end_beat:
            rot.append((b, b + 8, sign * p.amp, -sign * p.amp)); sign = -sign
            b += 8
    if p.key in ('IN', 'AT'):
        bob = 25 if p.key == 'IN' else 45
        b = 1.0; s_ = 1
        while b < end_beat:
            my.append((b, b + 16, base_y + s_ * bob, base_y - s_ * bob)); s_ = -s_
            b += 16
    return base_y, rot, my, end_beat

def ev(st, en, a, b, easing=1):
    return {"bezier": 0, "bezierPoints": [0.0, 0.0, 0.0, 0.0], "easingLeft": 0.0, "easingRight": 1.0,
            "easingType": easing, "end": b, "endTime": beat_triple(en), "linkgroup": 0, "start": a, "startTime": beat_triple(st)}

def fill_const(a_val, st, en):
    return [ev(st, en, a_val, a_val)]

def seq(events, st, en, default):
    """build contiguous event list covering [st,en] from (s,e,a,b) tuples"""
    out = []
    cur = st; last = events[0][2] if events else default
    for (s, e, a, b) in events:
        if s > cur: out.append(ev(cur, s, last, last))
        out.append(ev(s, e, a, b, 6)); cur = e; last = b
    if cur < en: out.append(ev(cur, en, last, last))
    return out

def rpe(ch, info, speed=10.0):
    p = ch['p']; P = ch['P']; phi = ch['phi']
    lead = 60.0 / phi
    bpmlist = [{"bpm": round(lead, 6), "startTime": [0, 0, 1]}, {"bpm": round(ch['bpm'], 6), "startTime": [1, 0, 1]}]
    lines = []
    for li in range(p.lines):
        base_y, rot, my, end_beat = line_events(ch, li)
        layer = {
            "speedEvents": [{"end": speed, "endTime": beat_triple(end_beat), "linkgroup": 0, "start": speed, "startTime": [0, 0, 1]}],
            "moveXEvents": fill_const(0.0, 0.0, end_beat),
            "moveYEvents": seq(my, 0.0, end_beat, base_y) if my else fill_const(float(base_y), 0.0, end_beat),
            "rotateEvents": seq(rot, 0.0, end_beat, 0.0) if rot else fill_const(0.0, 0.0, end_beat),
            "alphaEvents": fill_const(255, 0.0, end_beat),
        }
        # first moveY should start at base_y
        if my:
            layer["moveYEvents"] = seq([(s, e, a, b) for (s, e, a, b) in my], 0.0, end_beat, base_y)
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
            "extended": {"inclineEvents": [{"bezier": 0, "bezierPoints": [0.0, 0.0, 0.0, 0.0], "easingLeft": 0.0, "easingRight": 1.0, "easingType": 0, "end": 0.0, "endTime": [1, 0, 1], "linkgroup": 0, "start": 0.0, "startTime": [0, 0, 1]}]},
            "father": -1, "isCover": 1, "isGif": False, "notes": notes,
            "numOfNotes": sum(1 for n in nl if n.type != 2), "posControl": [{"easing": 1, "pos": 1.0, "x": 0.0}, {"easing": 1, "pos": 1.0, "x": 9999999.0}],
            "sizeControl": [{"easing": 1, "size": 1.0, "x": 0.0}, {"easing": 1, "size": 1.0, "x": 9999999.0}],
            "skewControl": [{"easing": 1, "skew": 0.0, "x": 0.0}, {"easing": 1, "skew": 0.0, "x": 9999999.0}],
            "yControl": [{"easing": 1, "x": 0.0, "y": 1.0}, {"easing": 1, "x": 9999999.0, "y": 1.0}],
            "alphaControl": [{"alpha": 1.0, "easing": 1, "x": 0.0}, {"alpha": 1.0, "easing": 1, "x": 9999999.0}],
            "zOrder": 0, "rotateWithFather": True})
    return {"BPMList": bpmlist,
            "META": {"RPEVersion": 160, "background": info['illustration'], "charter": info['charter'], "composer": info['composer'],
                     "id": "pcg-%s" % info['name'].replace(' ', '_'), "illustration": info['illustrator'], "level": info['level'],
                     "name": info['name'], "offset": 0, "song": info['music']},
            "judgeLineGroup": ["Default"], "judgeLineList": lines, "multiLineString": " ".join(str(i + 1) for i in range(p.lines)), "multiScale": 1.0, "xybind": False}

def official(ch, info, speed=10.0):
    p = ch['p']; P = ch['P']
    unit = P / 32.0
    sp = speed / 4.5
    lines = []
    for li in range(p.lines):
        base_y, rot, my, end_beat = line_events(ch, li)
        def tu(t): return int(round(t / unit))
        end_u = 1000000000
        def ypos(y): return (y + 450.0) / 900.0
        # move events (x const 0.5, y piecewise)
        mv = []
        cur_u = -999999; cur_y = ypos(my[0][2] if my else base_y)
        for (s, e, a, b) in my:
            su, eu = tu((beat_of(s, ch)) and (ch['phi'] + (s - 1) * P)), tu(ch['phi'] + (e - 1) * P)
            if su > cur_u: mv.append(dict(startTime=cur_u, endTime=su, start=0.5, end=0.5, start2=cur_y, end2=cur_y))
            mv.append(dict(startTime=su, endTime=eu, start=0.5, end=0.5, start2=ypos(a), end2=ypos(b))); cur_u, cur_y = eu, ypos(b)
        mv.append(dict(startTime=cur_u, endTime=end_u, start=0.5, end=0.5, start2=cur_y, end2=cur_y))
        rt = []
        cur_u = -999999; cur_a = rot[0][2] if rot else 0.0
        for (s, e, a, b) in rot:
            su, eu = tu(ch['phi'] + (s - 1) * P), tu(ch['phi'] + (e - 1) * P)
            if su > cur_u: rt.append(dict(startTime=cur_u, endTime=su, start=cur_a, end=cur_a))
            rt.append(dict(startTime=su, endTime=eu, start=a, end=b)); cur_u, cur_a = eu, b
        rt.append(dict(startTime=cur_u, endTime=end_u, start=cur_a, end=cur_a))
        spd = [dict(startTime=0, endTime=end_u, value=round(sp, 6), floorPosition=0.0)]
        dis = [dict(startTime=-999999, endTime=end_u, start=1.0, end=1.0)]
        above = []
        for n in [n for n in ch['notes'] if n.line == li]:
            tsec = n.t
            hold = (n.end - n.t) / unit if n.type == 2 else 0.0
            above.append(dict(type=n.type, time=tu(tsec), holdTime=round(hold, 3), positionX=round(n.x / 75.9375, 4),
                              speed=1.0, floorPosition=round(sp * tsec, 4)))
        lines.append(dict(bpm=round(ch['bpm'], 6), notesAbove=above, notesBelow=[], speedEvents=spd,
                          judgeLineMoveEvents=mv, judgeLineRotateEvents=rt, judgeLineDisappearEvents=dis))
    return {"formatVersion": 3, "offset": 0.0, "numOfNotes": sum(1 for n in ch['notes']), "judgeLineList": lines}

def pec(ch, info, speed=10.0):
    p = ch['p']; P = ch['P']; phi = ch['phi']
    L = ["0", "bp 0.000 %.3f" % (60.0 / phi), "bp 1.000 %.3f" % ch['bpm']]
    for li in range(p.lines):
        base_y, rot, my, end_beat = line_events(ch, li)
        L.append("cv %d 0.000 %.3f" % (li, speed))
        y0 = my[0][2] if my else base_y
        r0 = rot[0][2] if rot else 0.0
        L.append("cp %d 0.000 0.000 %.3f" % (li, y0 * 700.0 / 450.0))
        L.append("ca %d 0.000 255" % li)
        L.append("cd %d 0.000 %.3f" % (li, -r0))
        for (s, e, a, b) in my:
            L.append("cm %d %.3f %.3f 0.000 %.3f 6" % (li, s, e, b * 700.0 / 450.0))
        for (s, e, a, b) in rot:
            L.append("cr %d %.3f %.3f %.3f 6" % (li, s, e, -b))
    for n in ch['notes']:
        li = n.line
        b = beat_of(n.t, ch)
        x = n.x * 1024.0 / 675.0
        if n.type == 2:
            L.append("n2 %d %.3f %.3f %.3f 1 0" % (li, b, beat_of(n.end, ch), x))
        else:
            L.append("n%d %d %.3f %.3f 1 0" % (n.type, li, b, x))
        L.append("# 1.00")
        L.append("& 1.00")
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
