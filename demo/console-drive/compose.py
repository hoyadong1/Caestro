"""Compose the emulator screen (+ Pleos console) + app PCM into one video.
Audio is master: each output frame's audio time -> wall time (from pcm_bytes samples) -> nearest
emulator frame (webm PTS + screen start) and latest console screenshot (file name = wall ms)."""
import bisect, csv, json, os, shutil, subprocess, sys, wave
from pathlib import Path

take = Path(sys.argv[1]).resolve(); out = Path(sys.argv[2]).resolve()
# 'console': emulator + console side by side. 'scenario': app area only. 'full': the whole Pleos screen.
MODE = sys.argv[3] if len(sys.argv) > 3 else 'console'
FPS = 30
def wsl(p: Path) -> str:
    s = str(p).replace('\\', '/'); return '/mnt/' + s[0].lower() + s[2:]
def ff(*args, capture=False):
    r = subprocess.run(['wsl', '-e', *args], check=True, text=True, capture_output=capture); return r.stdout

meta = json.loads((take / 'meta.json').read_text(encoding='utf-8-sig'))
src = take / 'pcm.csv' if (take / 'pcm.csv').exists() else take / 'drive.csv'
rows = [r for r in csv.DictReader(open(src, encoding='utf-8-sig')) if r['pcm_bytes'].strip().isdigit()]
a2w = [(0.0, meta['play_start_ms'] / 1000)] + [((int(r['pcm_bytes']) - 44) / 176400, int(r['wall_ms']) / 1000) for r in rows]
a2w = sorted({a: w for a, w in a2w}.items())
def interp(pts, x):
    xs = [p[0] for p in pts]; i = bisect.bisect_left(xs, x)
    l, r = (pts[:2] if i <= 0 else pts[-2:] if i >= len(pts) else pts[i-1:i+1])
    return l[1] + (x - l[0]) * (r[1] - l[1]) / (r[0] - l[0])

with wave.open(str(take / 'app-audio.wav')) as w:
    dur = w.getnframes() / w.getframerate()

work = take / 'compose'; shutil.rmtree(work, ignore_errors=True)
emu_src = work / 'emu_src'; emu_out = work / 'emu'; con_out = work / 'con'
for d in (emu_src, emu_out, con_out): d.mkdir(parents=True)
st = float(ff('ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=start_time', '-of', 'csv=p=0', wsl(take / 'emulator.webm'), capture=True).split()[0] or 0)
ff('ffmpeg', '-v', 'error', '-i', wsl(take / 'emulator.webm'), '-vf', 'fps=60', '-q:v', '2', wsl(emu_src) + '/%06d.jpg')
emu = sorted(emu_src.glob('*.jpg')); n_emu = len(emu); emu = [(st + i / 60, p) for i, p in enumerate(emu)]; pts = [e[0] for e in emu]
con = sorted((int(p.stem) / 1000, p) for p in (take / 'console').glob('*.jpg')) if MODE == 'console' else []
con_t = [c[0] for c in con]
screen0 = meta['screen_start_ms'] / 1000

log = []
for f in range(round(dur * FPS)):
    a = (f + 0.5) / FPS; wall = interp(a2w, a)
    ei = max(0, min(n_emu - 1, bisect.bisect_right(pts, wall - screen0) - 1))
    ci = max(0, bisect.bisect_right(con_t, wall) - 1)
    shutil.copyfile(emu[ei][1], emu_out / f'{f+1:05d}.jpg')
    if con: shutil.copyfile(con[ci][1], con_out / f'{f+1:05d}.jpg')
    log.append((round(a, 3), round(wall - meta['play_start_ms'] / 1000, 3)))

if MODE == 'console':
    inputs = ['-framerate', str(FPS), '-i', wsl(emu_out) + '/%05d.jpg', '-framerate', str(FPS), '-i', wsl(con_out) + '/%05d.jpg']
    graph = '[0:v]scale=1080:608:flags=lanczos[e];[1:v]scale=840:525:flags=lanczos,pad=840:608:0:41:color=0x0b0f14[c];[e][c]hstack=inputs=2,format=yuv420p[v]'
elif MODE == 'full':
    inputs = ['-framerate', str(FPS), '-i', wsl(emu_out) + '/%05d.jpg']
    graph = '[0:v]scale=1920:1080:flags=lanczos,format=yuv420p[v]'
else:
    inputs = ['-framerate', str(FPS), '-i', wsl(emu_out) + '/%05d.jpg']
    # Right-hand app area of the Pleos screen (status bar and climate bar removed), from a 1920x1080 recording.
    graph = '[0:v]scale=1920:1080:flags=lanczos,crop=1260:908:640:67,format=yuv420p[v]'
audio_index = len(inputs) // 4
ff('ffmpeg', '-v', 'error', '-y', *inputs, '-i', wsl(take / 'app-audio.wav'),
   '-filter_complex', graph,
   '-map', '[v]', '-map', f'{audio_index}:a', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-profile:v', 'main', '-level', '4.1', '-bf', '0', '-refs', '1', '-r', '30',
   '-c:a', 'aac', '-ar', '48000', '-b:a', '192k', '-shortest', '-movflags', '+faststart', wsl(out))
(take / 'audio_to_wall.json').write_text(json.dumps(log))
print(f'{out}: {dur:.1f}s audio, {n_emu} emulator frames, {len(con)} console shots, drive wall {log[-1][1]:.1f}s')
