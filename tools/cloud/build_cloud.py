"""Builds the example cloud in docs/cloud (served by GitHub Pages) in the pack format of README.md.

    python build_cloud.py <cache dir>

- instruments/trombone: VSCO 2 Community Edition recordings (CC0), an example of an instrument made
  by uploading recorded notes on the creator platform.
- instruments/handpan: a designed timbre rendered to notes, an example of an instrument made by
  shaping a sound on the creator platform.
- scores/*.json are written by hand (the creator platform's score editor would write the same).
- catalog.json lists every pack with its files and size.

The car app only downloads these; it never creates them. Needs numpy.
"""
import json, math, os, re, sys, urllib.parse, urllib.request, wave
import numpy as np

RATE = 24000
HERE = os.path.dirname(os.path.abspath(__file__))
CLOUD = os.path.normpath(os.path.join(HERE, '..', '..', 'docs', 'cloud'))
NOTE = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


# ---------------------------------------------------------------- audio helpers (as tools/samples/build.py)

def read_wav(path):
    with wave.open(path) as w:
        ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if width == 2:
        x = np.frombuffer(raw, '<i2').astype(np.float64) / 32768
    elif width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16)
        x = np.where(v >= 1 << 23, v - (1 << 24), v).astype(np.float64) / (1 << 23)
    elif width == 4:
        x = np.frombuffer(raw, '<i4').astype(np.float64) / 2 ** 31
    else:
        raise ValueError(width)
    return x.reshape(-1, ch).mean(axis=1), rate


def resample(x, src, dst):
    if src == dst:
        return x
    cutoff = 0.45 * dst / src
    n = np.arange(63) - 31
    h = 2 * cutoff * np.sinc(2 * cutoff * n) * np.blackman(63)
    y = np.convolve(x, h / h.sum(), mode='same')
    t = np.arange(0, len(y) - 1, src / dst)
    i = t.astype(int)
    f = t - i
    return y[i] * (1 - f) + y[i + 1] * f


def trim_start(x):
    i = int(np.argmax(np.abs(x) > np.abs(x).max() * 0.02))
    return x[max(0, i - int(0.004 * RATE)):]


def yin_midi(x):
    """Period by the cumulative-mean difference (YIN): robust about the octave, coarse in cents."""
    seg = x[int(0.3 * RATE):int(0.8 * RATE)]
    n = len(seg) // 2
    d = np.array([np.sum((seg[:n] - seg[t:t + n]) ** 2) for t in range(1, RATE // 40)])
    cm = d * np.arange(1, len(d) + 1) / np.cumsum(d)
    t = next((i for i in range(20, len(cm) - 1) if cm[i] < 0.15 and cm[i] <= cm[i + 1]), int(np.argmin(cm[20:])) + 20) + 1
    return 12 * math.log2(RATE / t / 440) + 69


def detect(x, guess, octaves=(-24, -12, 0, 12)):
    """Fundamental near MIDI note [guess] in the given octaves, refined to a few cents."""
    onset = int(np.argmax(np.abs(x) > 0.05 * np.abs(x).max()))
    a = onset + int(0.12 * RATE)
    seg = x[a:a + int(0.6 * RATE)] * np.hanning(int(0.6 * RATE))
    spec = np.abs(np.fft.rfft(seg, 1 << 17))
    df = RATE / (1 << 17)
    best = None
    for octave in octaves:
        for cents in range(-60, 61, 2):
            f = 440 * 2 ** ((guess + octave - 69 + cents / 100) / 12)
            if f < 25 or f > 5000:
                continue
            s = 0.0
            for h in range(1, 7):
                k = int(round(f * h / df))
                if k + 2 >= len(spec):
                    break
                s += spec[k - 2:k + 3].max() / h
            k = int(round(f / df))
            fund = spec[max(0, k - 2):k + 3].max() / (spec.max() + 1e-12)
            score = s * (1.0 if fund > 0.08 else 0.2)
            if best is None or score > best[0]:
                best = (score, f)
    return best[1]


def make_loop(x, f0, start, length):
    period = RATE / f0
    length = max(int(round(int(length / period) * period)), int(period))
    end = start + length
    fade = min(int(0.12 * RATE), start)
    a = x[end - fade:end].copy()
    b = x[start - fade:start]
    w = np.linspace(0, 1, fade)
    y = x.copy()
    y[end - fade:end] = a * np.sqrt(1 - w) + b * np.sqrt(w)
    return y[:end], start, end


def level(x):
    """Same loudness rule as the app's bank: the loudest 100 ms at RMS 0.25."""
    frame = int(0.1 * RATE)
    rms = max(np.sqrt(np.mean(x[i:i + frame] ** 2)) for i in range(0, max(1, len(x) - frame), frame // 2))
    return np.clip(x * (0.25 / max(rms, 1e-6)), -0.999, 0.999)


def write_instrument(meta, notes):
    """notes: list of (rootHz, samples, loop or None). Writes <id>.json and <id>.pcm."""
    out = os.path.join(CLOUD, 'instruments')
    os.makedirs(out, exist_ok=True)
    chunks, rows, offset = [], [], 0
    for root, x, loop in sorted(notes, key=lambda n: n[0]):
        q = np.round(level(x) * 32767).astype('<i2')
        rows.append({'root': round(root, 3), 'offset': offset, 'length': len(q),
                     'loopStart': loop[0] + offset if loop else -1, 'loopEnd': loop[1] + offset if loop else -1})
        chunks.append(q)
        offset += len(q)
    iid = meta['id']
    open(os.path.join(out, iid + '.pcm'), 'wb').write(np.concatenate(chunks).tobytes())
    manifest = dict(format=1, type='instrument', **meta, rate=RATE, pcm=iid + '.pcm', notes=rows)
    with open(os.path.join(out, iid + '.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(iid, len(rows), 'notes', round(offset * 2 / 1e6, 2), 'MB', [r['root'] for r in rows], file=sys.stderr)


# ---------------------------------------------------------------- trombone (uploaded recordings)

def trombone(cache):
    tree = os.path.join(cache, 'tree.tsv')
    if not os.path.exists(tree):
        raise SystemExit('put the VSCO-2-CE file list in ' + tree + ' (see tools/samples/README.md)')
    files = [l.rstrip('\n').split('\t')[1] for l in open(tree, encoding='utf-8')]
    by_note = {}
    for p in files:
        m = re.fullmatch(r'Brass/Tenor Trombone/sus/tenortbn_sus_([A-G])(#?)(\d)_v(\d)_1\.wav', p)
        if not m:
            continue
        midi = NOTE[m.group(1)] + (1 if m.group(2) else 0) + 12 * (int(m.group(3)) + 1)
        # The middle dynamic (v2) where it exists: calm, not brassy.
        rank = {'2': 0, '1': 1, '3': 2}[m.group(4)]
        if midi not in by_note or rank < by_note[midi][0]:
            by_note[midi] = (rank, p)
    notes = []
    for midi, (_, p) in sorted(by_note.items()):
        dst = os.path.join(cache, p.replace('/', '__'))
        if not os.path.exists(dst):
            url = 'https://raw.githubusercontent.com/sgossner/VSCO-2-CE/master/' + urllib.parse.quote(p)
            urllib.request.urlretrieve(url, dst + '.part')
            os.replace(dst + '.part', dst)
        x, rate = read_wav(dst)
        x = trim_start(resample(x, rate, RATE))
        # The file names are an octave low (A#0 is the B-flat at 58 Hz); trust the measured period.
        heard = int(round(yin_midi(x)))
        if heard < 36:
            continue  # pedal tone, below the range the stages use
        root = detect(x, heard, (0,))
        x = x[:int(RATE * 2.6)]
        x, ls, le = make_loop(x, root, int(RATE * 0.55), int(RATE * 1.0))
        notes.append((root, x, (ls, le)))
    write_instrument({
        'id': 'trombone', 'label': '트롬본', 'sound': 'recorded',
        'creator': 'VSCO 2 CE 녹음을 올린 예시', 'license': 'CC0 (퍼블릭 도메인)',
        'stages': [0, 2, 4], 'sustained': True, 'voices': 3, 'mono': False, 'minMidi': 40, 'maxMidi': 72,
        'gain': 2.3, 'attack': 0.07, 'release': 0.32, 'look': 'BRASS', 'color': '#E3A857',
    }, notes)


# ---------------------------------------------------------------- handpan (designed timbre)

def handpan_note(f0, seconds=3.4):
    t = np.arange(int(RATE * seconds)) / RATE
    rng = np.random.default_rng(int(f0))
    # A steel tone field: fundamental, octave and compound fifth, each ring shorter than the last.
    ratios = [1.0, 2.0, 3.0, 4.03]
    levels = [1.0, 0.55, 0.28, 0.08]
    decays = [2.6, 1.5, 0.9, 0.35]
    scale = (220.0 / f0) ** 0.3
    x = np.zeros_like(t)
    for r, a, d in zip(ratios, levels, decays):
        f = f0 * r
        if f > 9000:
            continue
        # Two slightly detuned modes give the slow shimmer of a hand-hammered note.
        x += a * np.exp(-t / (d * scale)) * (np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) +
                                             0.35 * np.sin(2 * np.pi * (f + 0.6 + 0.4 * r) * t))
    # Soft hand strike: 6 ms rise and a short low thump.
    x *= np.minimum(1.0, t / 0.006)
    thump = rng.standard_normal(len(t)) * np.exp(-t / 0.025)
    thump = np.convolve(thump, np.ones(40) / 40, mode='same') * 0.6
    x = x + thump
    tail = int(0.25 * RATE)
    x[-tail:] *= np.linspace(1, 0, tail) ** 2
    return x


def handpan():
    notes = []
    for midi in range(50, 87, 4):  # D3 .. D6, every major third
        f0 = 440 * 2 ** ((midi - 69) / 12)
        notes.append((f0, handpan_note(f0), None))
    write_instrument({
        'id': 'handpan', 'label': '핸드팬', 'sound': 'designed',
        'creator': '음색을 디자인한 예시', 'license': 'CC0 (퍼블릭 도메인)',
        'stages': [3, 4, 5], 'sustained': False, 'voices': 6, 'mono': False, 'minMidi': 50, 'maxMidi': 88,
        'gain': 1.9, 'attack': 0.002, 'release': 0.9, 'look': 'GLASS', 'color': '#7DD8CB',
    }, notes)


# ---------------------------------------------------------------- catalog

def catalog():
    items = []
    for kind, folder in (('score', 'scores'), ('instrument', 'instruments')):
        d = os.path.join(CLOUD, folder)
        for name in sorted(os.listdir(d)):
            if not name.endswith('.json'):
                continue
            m = json.load(open(os.path.join(d, name), encoding='utf-8'))
            assert m['type'] == kind and m['id'] + '.json' == name, name
            files = [folder + '/' + name] + ([folder + '/' + m['pcm']] if kind == 'instrument' else [])
            item = {
                'id': m['id'], 'type': kind,
                'title': m['title'] if kind == 'score' else m['label'],
                'subtitle': m['character'] if kind == 'score' else ('녹음한 악기' if m['sound'] == 'recorded' else '디자인한 음색'),
                'creator': m['creator'], 'license': m['license'],
                'look': m.get('look', ''), 'color': m['color'], 'stages': m.get('stages', []),
                'files': files, 'bytes': sum(os.path.getsize(os.path.join(CLOUD, f)) for f in files),
            }
            items.append(item)
    with open(os.path.join(CLOUD, 'catalog.json'), 'w', encoding='utf-8') as f:
        json.dump({'format': 1, 'name': 'Drive Soundtrack 클라우드 (예시)', 'items': items}, f, ensure_ascii=False, indent=1)
    print('catalog', [i['id'] for i in items], file=sys.stderr)


if __name__ == '__main__':
    cache = sys.argv[1]
    os.makedirs(cache, exist_ok=True)
    trombone(cache)
    handpan()
    catalog()
