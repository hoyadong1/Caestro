"""Turn the picked VSCO-2-CE files into the app's sample bank.

Each instrument becomes assets/samples/<id>.pcm (16-bit mono, 24 kHz, notes back to back) plus a line per
note in assets/samples/bank.txt:  <id> <kind> <rootHz> <offset> <length> <loopStart> <loopEnd>
(offsets in samples within the .pcm; loopStart = loopEnd = -1 for one-shots).
Sustained notes get a crossfaded loop so a held note can last as long as the score asks.
"""
import json, os, re, sys, wave, math
import numpy as np

RATE = 24000
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
pick = json.load(open(os.path.join(HERE, 'pick.json'), encoding='utf-8'))
NOTE = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}

def read(path):
    with wave.open(path) as w:
        ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if width == 2: x = np.frombuffer(raw, '<i2').astype(np.float64) / 32768
    elif width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v >= 1 << 23, v - (1 << 24), v)
        x = v.astype(np.float64) / (1 << 23)
    elif width == 4: x = np.frombuffer(raw, '<i4').astype(np.float64) / 2 ** 31
    else: raise ValueError(width)
    x = x.reshape(-1, ch).mean(axis=1)
    return x, rate

def resample(x, src, dst):
    if src == dst: return x
    # Band-limit before decimating: windowed-sinc low-pass at 0.45 * dst.
    cutoff = 0.45 * dst / src
    taps = 63
    n = np.arange(taps) - (taps - 1) / 2
    h = 2 * cutoff * np.sinc(2 * cutoff * n) * np.blackman(taps)
    y = np.convolve(x, h / h.sum(), mode='same')
    t = np.arange(0, len(y) - 1, src / dst)
    i = t.astype(int); f = t - i
    return y[i] * (1 - f) + y[i + 1] * f

def name_midi(path):
    m = re.search(r'_([A-G])(#)?(-?\d)(?:_|\.)', os.path.basename(path))
    if not m: return None
    return NOTE[m.group(1)] + (1 if m.group(2) else 0) + 12 * (int(m.group(3)) + 1)

def f0_score(seg, f):
    """Harmonic sum of the magnitude spectrum at f, 2f, ... (normalised)."""
    spec, freqs = seg
    s = 0.0
    for h in range(1, 7):
        k = int(round(f * h / freqs))
        if k + 2 >= len(spec): break
        s += spec[k - 2:k + 3].max() / h
    return s

def spectrum(x, rate, start, dur=0.6):
    a = int(start * rate); b = a + int(dur * rate)
    s = x[a:b] * np.hanning(b - a)
    n = 1 << 17
    return np.abs(np.fft.rfft(s, n)), rate / n

def detect(x, rate, guess, octaves=(-24, -12, 0, 12)):
    """Fundamental near the guessed MIDI note (any octave in -2..+1), refined to a few cents."""
    onset = np.argmax(np.abs(x) > 0.05 * np.abs(x).max())
    seg = spectrum(x, rate, onset / rate + 0.12)
    best = None
    for octave in octaves:
        for cents in range(-60, 61, 2):
            f = 440 * 2 ** ((guess + octave - 69 + cents / 100) / 12)
            if f < 25 or f > 5000: continue
            sc = f0_score(seg, f)
            # Prefer the lowest octave whose fundamental is really present.
            spec, df = seg
            k = int(round(f / df))
            fund = spec[max(0, k - 2):k + 3].max() / (spec.max() + 1e-12)
            score = sc * (1.0 if fund > 0.08 else 0.2)
            if best is None or score > best[0]: best = (score, f)
    return best[1]

def trim_start(x):
    peak = np.abs(x).max()
    i = int(np.argmax(np.abs(x) > peak * 0.02))
    return x[max(0, i - int(0.004 * RATE)):]

def make_loop(x, f0, start, length):
    """Crossfade the end of [start, start+length) into the region before start, cut at whole periods."""
    period = RATE / f0
    length = max(int(round(int(length / period) * period)), int(period))
    end = start + length
    fade = min(int(0.12 * RATE), start)
    a = x[end - fade:end].copy(); b = x[start - fade:start]
    w = np.linspace(0, 1, fade)
    y = x.copy()
    y[end - fade:end] = a * np.sqrt(1 - w) + b * np.sqrt(w)
    return y[:end], start, end

OCTAVE = {}
for iid, spec in pick.items():
    if spec['kind'] == 'perc': continue
    votes = []
    for key, rel in spec['notes'].items():
        x, rate = read(os.path.join(HERE, 'raw', rel.replace('/', '__')))
        x = trim_start(resample(x, rate, RATE))
        if key.isdigit() and iid == 'piano': g = int(key)
        elif iid == 'organ': g = int(re.search(r'_(\d{3})_', rel).group(1)) - 86
        else: g = name_midi(rel)
        f = detect(x, RATE, g)
        votes.append(int(round((12 * math.log2(f / 440) + 69 - g) / 12)) * 12)
    OCTAVE[iid] = max(set(votes), key=votes.count)
    print(iid, 'octave offset', OCTAVE[iid], votes, file=sys.stderr)
pcm = {}
lines = []
for iid, spec in pick.items():
    kind = spec['kind']
    chunks = []
    offset = 0
    for key, rel in spec['notes'].items():
        path = os.path.join(HERE, 'raw', rel.replace('/', '__'))
        x, rate = read(path)
        x = resample(x, rate, RATE)
        x = trim_start(x)
        if kind == 'perc':
            x = x[:int(RATE * (2.5 if 'timpani' in key or 'cymbal' in key else 1.2))]
            root = 0.0
        else:
            if key.isdigit() and iid == 'piano': guess = int(key)
            elif iid == 'organ': guess = int(re.search(r'_(\d{3})_', rel).group(1)) - 86
            else: guess = name_midi(rel)
            root = detect(x, RATE, guess + OCTAVE.get(iid, 0), (OCTAVE.get(iid, 0) * 0,) if iid in OCTAVE else (-24, -12, 0, 12))
        loop = (-1, -1)
        if kind == 'sus':
            # Attack + 0.5 s, then a 1.0 s loop taken from the steady part.
            x = x[:int(RATE * 2.6)]
            start = int(RATE * 0.55)
            x, ls, le = make_loop(x, root, start, int(RATE * 1.0))
            loop = (ls, le)
        elif kind == 'hit':
            keep = {'harp': 2.6, 'piano': 3.2, 'glock': 2.4, 'marimba': 1.6, 'xylophone': 1.0, 'contrabass': 1.8}[iid]
            x = x[:int(RATE * keep)]
        # Fade the tail of one-shots so they end in silence.
        if loop[0] < 0:
            tail = min(len(x) // 3, int(0.25 * RATE))
            x[-tail:] *= np.linspace(1, 0, tail) ** 2
        # Level: match the loudest 100 ms (attack area) across an instrument's notes.
        frame = int(0.1 * RATE)
        rms = max(np.sqrt(np.mean(x[i:i + frame] ** 2)) for i in range(0, max(1, len(x) - frame), frame // 2))
        x = x * (0.25 / max(rms, 1e-6))
        x = np.clip(x, -0.999, 0.999)
        q = np.round(x * 32767).astype('<i2')
        chunks.append(q)
        lines.append(f'{iid} {kind} {key if kind == "perc" else "%.3f" % root} {offset} {len(q)} '
                     f'{loop[0] + offset if loop[0] >= 0 else -1} {loop[1] + offset if loop[1] >= 0 else -1}')
        offset += len(q)
        print(f'{iid:11s} {key:14s} root={root:8.2f}Hz midi={12*math.log2(root/440)+69 if root else 0:6.2f} len={len(q)/RATE:.2f}s', file=sys.stderr)
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, iid + '.pcm'), 'wb').write(np.concatenate(chunks).tobytes())
open(os.path.join(OUT, 'bank.txt'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
print('bank written', len(lines), 'notes', round(total / 1e6, 1), 'MB', file=sys.stderr)
