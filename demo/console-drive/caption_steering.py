"""Add leg captions and a left/right level meter to a composed steering take.

Usage: python caption_steering.py <take dir> <composed.mp4> <out.mp4>
events.csv (from record-steering.ps1) holds each leg's start on the audio clock, which is also
the video clock of the composed file.
"""
import csv, subprocess, sys
from pathlib import Path

take, src, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve()
def wsl(p: Path) -> str:
    s = str(p).replace('\\', '/'); return '/mnt/' + s[0].lower() + s[2:]

TITLE = {
    'straight': ('직진', '소리가 가운데'),
    'lane-left': ('◀  왼쪽 차선 변경 · 25°', '살짝 왼쪽으로'),
    'curve-left': ('◀  왼쪽 커브 · 70°', '소리가 왼쪽으로 기울고 현악이 부풀어 오름'),
    'curve-right': ('오른쪽 커브 · 90°  ▶', '소리가 오른쪽으로 기울고 현악이 부풀어 오름'),
}
BOLD = '/mnt/c/Windows/Fonts/malgunbd.ttf'
REGULAR = '/mnt/c/Windows/Fonts/malgun.ttf'

events = list(csv.DictReader(open(take / 'events.csv', encoding='utf-8-sig')))
TOP, BOTTOM = 130, 90  # caption band above the screen, level meter band below
filters = [f"pad=1920:{1080 + TOP + BOTTOM}:0:{TOP}:color=0x0b1622"]
for a, b in zip(events, events[1:]):
    start, end = float(a['audio_s']), float(b['audio_s'])
    title, detail = TITLE[a['label']]
    when = f"enable='between(t,{start:.2f},{end:.2f})'"
    filters.append(f"drawtext=fontfile={BOLD}:text='{title}':fontsize=50:fontcolor=white:x=(w-text_w)/2:y=14:{when}")
    filters.append(f"drawtext=fontfile={REGULAR}:text='{detail}':fontsize=28:fontcolor=0xb9d7dd:x=(w-text_w)/2:y=80:{when}")
y0 = 1080 + TOP
filters.append(f"drawtext=fontfile={REGULAR}:text='D단 60 km/h · 핸들 각도만 변경 · 스테레오로 들어 보세요':"
               f"fontsize=24:fontcolor=0x8fb1bf:x=40:y={y0 + 32}")
filters.append(f"drawtext=fontfile={BOLD}:text='왼쪽 L':fontsize=22:fontcolor=0xb9d7dd:x=1060:y={y0 + 14}")
filters.append(f"drawtext=fontfile={BOLD}:text='오른쪽 R':fontsize=22:fontcolor=0xb9d7dd:x=1060:y={y0 + 52}")

graph = (f"[0:v]{','.join(filters)}[base];"
         "[0:a]asplit[a1][a2];"
         "[a2]showvolume=w=720:h=30:f=0.85:b=5:ds=lin:dm=0:p=1:o=h:t=0:v=0:c=0x66c9ff[meter];"
         f"[base][meter]overlay=x=1160:y={y0 + 10}[v]")
subprocess.run(['wsl', '-e', 'ffmpeg', '-v', 'error', '-y', '-i', wsl(src), '-filter_complex', graph,
                '-map', '[v]', '-map', '[a1]', '-c:v', 'libx264', '-preset', 'medium', '-crf', '19',
                '-profile:v', 'main', '-level', '4.1', '-bf', '0', '-refs', '1', '-r', '30',
                '-c:a', 'aac', '-ar', '48000', '-b:a', '192k', '-movflags', '+faststart', wsl(out)], check=True)
print(out)
