import sys, os, json, zipfile, io
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from synth import *; from analysis import *; from gen import *; import export as E
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'samples')
os.makedirs(out, exist_ok=True)
x, truth, P0 = synth(bpm=128.0, phase=0.37, dur=40.0)
a = analyze(x); dur = len(x) / SR
E.write_wav('/tmp/pcg_demo.wav', x, SR)
wav = open('/tmp/pcg_demo.wav', 'rb').read()
# tiny valid 1x1 PNG
import base64
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==')
for key, p in PARAMS.items():
    ch = make_chart(a, p, dur)
    for fmt in ('rpe', 'official', 'pec'):
        info = dict(name='Demo Beat', difficulty=ch['difficulty'], level=E.level_text(ch), charter='PhiraChartGen', composer='Synthetic',
                    illustrator='PhiraChartGen', music='song.wav', illustration='background.png', previewStart=8.0,
                    chart={'rpe': 'chart.json', 'official': 'chart.json', 'pec': 'chart.pec'}[fmt], offset=0.0, intro='Generated demo')
        if fmt == 'rpe': body = json.dumps(E.rpe(ch, info), ensure_ascii=False, separators=(',', ':'))
        elif fmt == 'official': body = json.dumps(E.official(ch, info), separators=(',', ':'))
        else: body = E.pec(ch, info)
        path = '%s/demo_%s_%s.zip' % (out, key.lower(), fmt)
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('info.yml', E.yml(info)); z.writestr(info['chart'], body)
            z.writestr('song.wav', wav); z.writestr('background.png', png)
        print(path, len(body))
