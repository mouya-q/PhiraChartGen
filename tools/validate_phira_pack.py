#!/usr/bin/env python3
"""Phira 谱面包校验器

用法: python3 validate_phira_pack.py <谱面包.zip|.pez> [...]
退出码: 0 全部通过, 1 存在错误
检查内容: 包结构 / info.yml 必填项 / 谱面文件格式 (RPE, PEC, Phigros 官方 JSON) / 音频与插图文件
"""
import json, re, sys, zipfile

REQUIRED_INFO = ["name", "difficulty", "level", "charter", "composer", "illustrator",
                 "chart", "music", "illustration", "previewStart", "aspectRatio",
                 "backgroundDim", "lineLength", "offset"]
AUDIO_EXT = (".mp3", ".ogg", ".wav")


def parse_yml(text):
    out = {}
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#") or ":" not in line:
            continue
        k, v = line.split(":", 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
            v = v[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        out[k.strip()] = v
    return out


def beat_val(b, errs, where):
    if not (isinstance(b, list) and len(b) == 3 and all(isinstance(i, int) for i in b)):
        errs.append("%s: 拍数必须是 3 个整数" % where)
        return 0.0
    if b[2] <= 0 or b[1] < 0 or b[0] < 0:
        errs.append("%s: 拍数取值非法 %s" % (where, b))
        return 0.0
    return b[0] + b[1] / b[2]


def check_rpe(d, errs, warns):
    for k in ("BPMList", "META", "judgeLineList"):
        if k not in d:
            errs.append("RPE 缺少字段 " + k)
    if errs:
        return
    bl = d["BPMList"]
    if not bl:
        errs.append("BPMList 为空")
        return
    prev = -1.0
    for i, b in enumerate(bl):
        st = beat_val(b.get("startTime"), errs, "BPMList[%d]" % i)
        if b.get("bpm", 0) <= 0:
            errs.append("BPMList[%d] bpm 必须大于 0" % i)
        if st < prev:
            errs.append("BPMList 未按时间排序")
        prev = st
    if beat_val(bl[0]["startTime"], errs, "BPMList[0]") != 0.0:
        errs.append("BPMList 首项必须从第 0 拍开始")
    total = 0
    for li, line in enumerate(d["judgeLineList"]):
        w = "line%d" % li
        layers = line.get("eventLayers") or []
        if not layers:
            errs.append(w + " 没有事件层")
        for yi, layer in enumerate(layers):
            for name in ("speedEvents", "moveXEvents", "moveYEvents", "rotateEvents", "alphaEvents"):
                evs = layer.get(name)
                if not evs:
                    errs.append("%s layer%d 缺少 %s" % (w, yi, name))
                    continue
                last_end = None
                for ei, e in enumerate(evs):
                    s = beat_val(e.get("startTime"), errs, "%s.%s[%d]" % (w, name, ei))
                    t = beat_val(e.get("endTime"), errs, "%s.%s[%d]" % (w, name, ei))
                    if t < s:
                        errs.append("%s.%s[%d] 结束早于开始" % (w, name, ei))
                    if last_end is not None and abs(s - last_end) > 1e-6:
                        warns.append("%s.%s[%d] 与上一事件不连续" % (w, name, ei))
                    last_end = t
        for ni, n in enumerate(line.get("notes", [])):
            s = beat_val(n.get("startTime"), errs, "%s.notes[%d]" % (w, ni))
            t = beat_val(n.get("endTime"), errs, "%s.notes[%d]" % (w, ni))
            if n.get("type") not in (1, 2, 3, 4):
                errs.append("%s.notes[%d] type 非法" % (w, ni))
            if n.get("type") == 2 and t <= s:
                errs.append("%s.notes[%d] Hold 时长必须大于 0" % (w, ni))
            if n.get("type") != 2 and abs(t - s) > 1e-9:
                errs.append("%s.notes[%d] 非 Hold 的结束时间必须等于开始时间" % (w, ni))
            if abs(n.get("positionX", 0)) > 675:
                warns.append("%s.notes[%d] positionX 超出屏幕" % (w, ni))
            total += 1
    if total == 0:
        errs.append("谱面没有任何音符")


def check_official(d, errs, warns):
    if d.get("formatVersion") not in (1, 3):
        errs.append("formatVersion 应为 1 或 3")
    lines = d.get("judgeLineList")
    if not lines:
        errs.append("judgeLineList 为空")
        return
    total = 0
    for li, line in enumerate(lines):
        for k in ("bpm", "notesAbove", "notesBelow", "speedEvents", "judgeLineMoveEvents",
                  "judgeLineRotateEvents", "judgeLineDisappearEvents"):
            if k not in line:
                errs.append("line%d 缺少 %s" % (li, k))
        if line.get("bpm", 0) <= 0:
            errs.append("line%d bpm 非法" % li)
        for k in ("notesAbove", "notesBelow"):
            for ni, n in enumerate(line.get(k, [])):
                if n.get("type") not in (1, 2, 3, 4):
                    errs.append("line%d.%s[%d] type 非法" % (li, k, ni))
                if n.get("type") == 2 and n.get("holdTime", 0) <= 0:
                    errs.append("line%d.%s[%d] Hold 时长必须大于 0" % (li, k, ni))
                if n.get("time", -1) < 0:
                    errs.append("line%d.%s[%d] 时间为负" % (li, k, ni))
                total += 1
    if total == 0:
        errs.append("谱面没有任何音符")


def check_pec(text, errs, warns):
    rows = text.splitlines()
    if not rows or not re.fullmatch(r"-?\d+", rows[0].strip()):
        errs.append("PEC 第一行必须是整数偏移 (毫秒)")
    if not any(r.startswith("bp ") for r in rows):
        errs.append("PEC 缺少 bp 行")
    notes = 0
    i = 1
    while i < len(rows):
        r = rows[i].split()
        if r and re.fullmatch(r"n[1-4]", r[0]):
            need = 7 if r[0] == "n2" else 6
            if len(r) != need:
                errs.append("PEC 第 %d 行音符字段数量错误" % (i + 1))
            if i + 2 >= len(rows) or not rows[i + 1].startswith("#") or not rows[i + 2].startswith("&"):
                errs.append("PEC 第 %d 行音符缺少 # 或 & 行" % (i + 1))
            notes += 1
            i += 3
            continue
        i += 1
    if notes == 0:
        errs.append("谱面没有任何音符")


def validate(path):
    errs, warns = [], []
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:
        return ["无法打开压缩包: %s" % e], []
    names = z.namelist()
    if any(n.endswith("/") or "/" in n for n in names):
        errs.append("压缩包内必须直接放文件, 不能含文件夹")
    if "info.yml" not in names:
        return errs + ["缺少 info.yml"], warns
    info = parse_yml(z.read("info.yml").decode("utf-8"))
    for k in REQUIRED_INFO:
        if k not in info:
            errs.append("info.yml 缺少 " + k)
    if errs:
        return errs, warns
    for k in ("chart", "music", "illustration"):
        if info[k] not in names:
            errs.append("info.yml 指向的文件不存在: %s" % info[k])
    if not info["music"].lower().endswith(AUDIO_EXT):
        errs.append("音频格式需为 mp3 / ogg / wav")
    if info["music"].lower().endswith(".mp3"):
        warns.append("mp3 可能存在不可预测的延迟, 必要时用 offset 校准")
    try:
        float(info["difficulty"])
        float(info["offset"])
    except ValueError:
        errs.append("difficulty / offset 必须是数字")
    if errs:
        return errs, warns
    raw = z.read(info["chart"]).decode("utf-8")
    if info["chart"].lower().endswith(".pec"):
        check_pec(raw, errs, warns)
    else:
        try:
            d = json.loads(raw)
        except Exception as e:
            return errs + ["谱面 JSON 解析失败: %s" % e], warns
        if "judgeLineList" in d and "BPMList" in d:
            check_rpe(d, errs, warns)
        elif "formatVersion" in d:
            check_official(d, errs, warns)
        else:
            errs.append("无法识别谱面格式")
    return errs, warns


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    bad = 0
    for p in argv[1:]:
        errs, warns = validate(p)
        print(("通过  " if not errs else "失败  ") + p)
        for w in warns:
            print("  警告: " + w)
        for e in errs:
            print("  错误: " + e)
        bad += bool(errs)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
