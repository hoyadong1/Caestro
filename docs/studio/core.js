// Caestro Studio core: the pack format of tools/cloud/README.md, checked the way the car app checks it
// (CloudLibrary.parseScore / parseInstrument, Score, Piece). No DOM here, so Node can run it too.

export const RATE = 24000;
export const ID = /^[a-z0-9][a-z0-9_-]{0,39}$/;
export const STAGES = ['베이스', '리듬', '화음', '분산음', '선율', '고음'];
/** Speed (km/h) at which stages 1..5 join; stage 0 always plays. */
export const ENTER = [0, 18, 38, 58, 82, 105];
export const LOOKS = ['BOWED', 'KEYS', 'ORGAN', 'HARP', 'PLUCKED', 'WIND', 'BRASS', 'MALLET', 'VOICE', 'GLASS', 'SYNTH'];

/** The app's own instruments per stage (Instruments.baseOptions), id and label. */
export const BUILT_IN = [
  [['cello', '첼로'], ['pizz', '콘트라베이스'], ['bassoon', '바순'], ['piano', '피아노'], ['organ', '파이프 오르간'], ['horn', '호른'], ['synthbass', '신스 베이스']],
  [['orchperc', '오케스트라 타악기'], ['latin', '라틴 퍼커션'], ['timpani', '팀파니'], ['softkit', '부드러운 드럼'], ['shaker', '셰이커'], ['electro', '일렉트로 비트'], ['none', '없음']],
  [['strings', '바이올린 합주'], ['viola', '비올라 합주'], ['horn', '호른'], ['organ', '파이프 오르간'], ['piano', '피아노'], ['epiano', '전자 피아노'], ['choir', '합창'], ['synthstrings', '신스 스트링'], ['none', '없음']],
  [['harp', '하프'], ['piano', '피아노'], ['marimba', '마림바'], ['xylophone', '실로폰'], ['kalimba', '칼림바'], ['synthpluck', '신스 플럭'], ['none', '없음']],
  [['violin', '바이올린'], ['flute', '플루트'], ['clarinet', '클라리넷'], ['oboe', '오보에'], ['bassoon', '바순'], ['trumpet', '트럼펫'], ['horn', '호른'], ['piano', '피아노'], ['synthlead', '신스 리드'], ['none', '없음']],
  [['glock', '글로켄슈필'], ['xylophone', '실로폰'], ['flute', '플루트'], ['violin', '바이올린'], ['strings', '바이올린 합주'], ['musicbox', '뮤직박스'], ['glasspad', '유리 패드'], ['none', '없음']],
];
export const BAND_DEFAULT = ['cello', 'orchperc', 'strings', 'harp', 'violin', 'flute'];

// ------------------------------------------------------------------ score notation (Score in Pieces.kt)

const LETTERS = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

export function pitchClass(name) {
  if (!(name[0] in LETTERS)) throw new Error(`음 이름이 아닙니다: ${name}`);
  let pc = LETTERS[name[0]];
  for (const ch of name.slice(1)) { if (ch === '#') pc++; else if (ch === 'b') pc--; else throw new Error(`음 이름이 아닙니다: ${name}`); }
  return ((pc % 12) + 12) % 12;
}

export function midi(name) {
  const m = /^([A-G])([#b]*)(-?\d+)$/.exec(name);
  if (!m) throw new Error(`음 이름이 아닙니다: ${name} (예: C4, F#5, Bb3)`);
  let pc = LETTERS[m[1]];
  for (const ch of m[2]) pc += ch === '#' ? 1 : -1;
  return 12 * (Number(m[3]) + 1) + pc;
}

function tokens(text) { return text.trim().split(/\s+/).filter(Boolean); }

function stepLength(part, token) {
  if (!/^\d+$/.test(part)) throw new Error(`길이는 16분음표 개수(숫자)입니다: ${token}`);
  const n = Number(part);
  if (n < 1) throw new Error(`길이는 1 이상입니다: ${token}`);
  return n;
}

/** "F#5:4 E5 D5 | ..."; "r" rests, "-" ties, ":n" sets the length in steps (sixteenths). */
export function notes(text, spb, tileTo = null) {
  const out = [];
  let position = 0, length = 4;
  for (const token of tokens(text)) {
    if (token === '|') {
      if (position % spb) throw new Error(`마디선 위치가 맞지 않습니다: ${position / 4}박째에 있는 ‘|’ (마디는 ${spb / 4}박)`);
      continue;
    }
    const parts = token.split(':');
    if (parts.length > 2) throw new Error(`알 수 없는 표기: ${token}`);
    if (parts.length > 1) length = stepLength(parts[1], token);
    if (parts[0] === 'r') { /* rest */ }
    else if (parts[0] === '-') {
      if (!out.length) throw new Error('붙임줄(-) 앞에 이어 줄 음이 없습니다');
      out[out.length - 1].length += length;
    } else out.push({ start: position, length, midi: midi(parts[0]) });
    position += length;
  }
  if (!out.length && position === 0) throw new Error('비어 있습니다');
  if (position % spb) throw new Error(`마디 중간에서 끝납니다 (${position}칸, 마디는 ${spb}칸)`);
  if (tileTo == null || position >= tileTo) return out;
  if (tileTo % position) throw new Error(`${position}칸 길이로는 곡 길이 ${tileTo}칸을 반복해 채울 수 없습니다`);
  const tiled = [];
  for (let k = 0; k < tileTo / position; k++) for (const n of out) tiled.push({ ...n, start: n.start + k * position });
  return tiled;
}

const CHORD = /^([A-G][#b]?)(maj7|m7|m6|dim7|dim|aug|sus4|m|7|6)?(?:\/([A-G][#b]?))?$/;
export const QUALITIES = {
  '': [0, 4, 7], m: [0, 3, 7], 7: [0, 4, 7, 10], maj7: [0, 4, 7, 11], m7: [0, 3, 7, 10], m6: [0, 3, 7, 9],
  6: [0, 4, 7, 9], dim: [0, 3, 6], dim7: [0, 3, 6, 9], aug: [0, 4, 8], sus4: [0, 5, 7],
};

/** "C:16 | F/C:8 G7:8"; optional voicings give one explicit chord per symbol. */
export function chords(text, spb, voicings = null) {
  const explicit = voicings ? voicings.split('|').map(v => tokens(v).map(midi)) : null;
  const out = [];
  let position = 0, length = spb;
  for (const token of tokens(text)) {
    if (token === '|') {
      if (position % spb) throw new Error(`코드 마디선 위치가 맞지 않습니다 (${position}칸)`);
      continue;
    }
    const parts = token.split(':');
    if (parts.length > 2) throw new Error(`알 수 없는 코드 표기: ${token}`);
    if (parts.length > 1) length = stepLength(parts[1], token);
    const m = CHORD.exec(parts[0]);
    if (!m) throw new Error(`코드 이름이 아닙니다: ${parts[0]} (예: C, Am, G7, F/C, Bdim7)`);
    const root = pitchClass(m[1]);
    const bass = m[3] ? pitchClass(m[3]) : root;
    out.push({ start: position, length, root, intervals: QUALITIES[m[2] || ''], bassClass: bass, voicing: explicit ? explicit[out.length] ?? null : null, name: parts[0] });
    position += length;
  }
  if (!out.length) throw new Error('코드가 없습니다');
  if (position % spb) throw new Error(`코드가 마디 중간에서 끝납니다 (${position}칸)`);
  if (explicit && explicit.length !== out.length) throw new Error(`보이싱 ${explicit.length}개, 코드 ${out.length}개: 개수가 같아야 합니다`);
  if (explicit) explicit.forEach((v, i) => { if (v.length < 2) throw new Error(`보이싱 ${i + 1}번은 두 음 이상이어야 합니다`); });
  return out;
}

export function pattern(text, spb, what) {
  const t = tokens(text);
  if (t.length !== spb) throw new Error(`${what}은 한 마디(${spb}칸)여야 합니다. 지금 ${t.length}칸`);
  return t.map(x => { if (x === '.') return -1; if (!/^\d+$/.test(x)) throw new Error(`${what}에는 숫자나 ‘.’만 씁니다: ${x}`); return Number(x); });
}

export function hits(text, spb) {
  const t = tokens(text);
  if (t.length !== spb) throw new Error(`반주 리듬은 한 마디(${spb}칸)여야 합니다. 지금 ${t.length}칸`);
  return t.map(x => { if (x !== 'x' && x !== '.') throw new Error(`반주 리듬에는 x나 ‘.’만 씁니다: ${x}`); return x === 'x'; });
}

function lowest(pc, floor) { return floor + (((pc - floor) % 12) + 12) % 12; }

/**
 * Checks a score manifest the way the app does and returns the playable piece:
 * { errors: [{field, message}], warnings: [...], piece } (piece only when there are no errors).
 */
export function checkScore(s) {
  const errors = [], warnings = [];
  const fail = (field, e) => errors.push({ field, message: e.message ?? String(e) });
  if (!ID.test(s.id || '')) errors.push({ field: 'id', message: '아이디는 영문 소문자·숫자·-·_로 40자까지, 첫 글자는 영문이나 숫자' });
  if (!String(s.title || '').trim()) errors.push({ field: 'title', message: '곡 제목을 적어 주세요' });
  if (!String(s.character || '').trim()) errors.push({ field: 'character', message: '부제(앨범에 보이는 설명)를 적어 주세요' });
  const beats = Number(s.beatsPerBar);
  if (beats !== 3 && beats !== 4) { errors.push({ field: 'beatsPerBar', message: '3/4 또는 4/4 박자만 됩니다' }); return { errors, warnings }; }
  const spb = beats * 4;
  let tonic = 0;
  try { tonic = pitchClass(String(s.tonic || '')); } catch (e) { fail('tonic', e); }
  const bpm = Number(s.bpm);
  if (!(bpm >= 40 && bpm <= 160)) errors.push({ field: 'bpm', message: '템포는 40~160 BPM' });
  const range = Number(s.tempoRange ?? 6);
  if (!(range >= 0 && range <= 40)) errors.push({ field: 'tempoRange', message: '속도에 따른 템포 변화는 0~40 BPM' });

  let ch = null, length = 0;
  try { ch = chords(String(s.chords || ''), spb, String(s.voicings || '').trim() || null); length = ch.at(-1).start + ch.at(-1).length; }
  catch (e) { fail(String(s.voicings || '').trim() && /보이싱/.test(e.message) ? 'voicings' : 'chords', e); }

  let melody = null;
  try {
    melody = notes(String(s.melody || ''), spb);
    if (ch) for (const n of melody) if (n.start + n.length > length) throw new Error(`선율이 코드 진행(${length / spb}마디)보다 깁니다`);
  } catch (e) { fail('melody', e); melody = null; }

  const bassText = String(s.bass || '').trim();
  const styles = ['HALVES', 'ROOT_FIFTH', 'VOICING'];
  let bass = null;
  if (ch) {
    try {
      if (bassText === 'VOICING' && ch.some(c => !c.voicing)) warnings.push({ field: 'bass', message: 'VOICING 베이스는 보이싱이 없으면 근음을 씁니다' });
      bass = styles.includes(bassText) ? styleBass(bassText, ch, spb) : notes(bassText, spb, length);
      for (const n of bass) if (n.start + n.length > length) throw new Error('베이스가 곡보다 깁니다');
    } catch (e) { fail('bass', e); }
  }

  let arp = null, comp = null;
  try { arp = pattern(String(s.arp || ''), spb, '분산음 패턴'); } catch (e) { fail('arp', e); }
  try { comp = hits(String(s.comp || ''), spb); } catch (e) { fail('comp', e); }
  if (!['BELLS', 'OCTAVE', 'CANON'].includes(s.high)) errors.push({ field: 'high', message: '고음 단계는 BELLS, OCTAVE, CANON 중 하나' });
  const canonBars = Number(s.canonBars || 0);
  if (s.high === 'CANON' && !(canonBars >= 1 && Number.isInteger(canonBars))) errors.push({ field: 'canonBars', message: '캐논은 몇 마디 뒤에 따라올지(1 이상) 정해 주세요' });
  if (!Array.isArray(s.defaults) || s.defaults.length !== 6) errors.push({ field: 'defaults', message: '단계마다 추천 악기 6개' });
  else s.defaults.forEach((id, stage) => {
    if (!BUILT_IN[stage].some(([i]) => i === id)) warnings.push({ field: 'defaults', message: `${STAGES[stage]}: ‘${id}’가 차에 없으면 기본 악단(${BAND_DEFAULT[stage]})으로 연주합니다` });
  });
  if (s.license && !/퍼블릭|public|직접|own|CC/i.test(s.license)) warnings.push({ field: 'license', message: '직접 쓴 곡이거나 저작권이 끝난 곡만 올릴 수 있습니다' });
  if (length && length % spb) errors.push({ field: 'chords', message: '곡이 마디선에서 끝나야 합니다' });
  if (errors.length) return { errors, warnings };

  for (const c of ch) {
    c.relativeRoot = (((c.root - tonic) % 12) + 12) % 12;
    const iv = c.intervals;
    c.tones = iv.length >= 4 ? [0, iv[1], iv[2], iv[3], 12] : [0, iv[1], iv[2], 12, 12 + iv[1]];
    if (c.voicing) { c.arp = c.voicing; c.harmony = c.voicing.slice(1); }
    else { const low = lowest(c.root, 53); c.harmony = iv.map(i => low + i); const ar = lowest(c.root, 55); c.arp = c.tones.map(t => ar + t); }
  }
  const piece = { spb, beats, tonic, chords: ch, melody, bass, arp, comp, high: s.high, canonBars, lengthSteps: length,
    bpm, tempoRange: range, chordAt: new Array(length), melodyAt: new Array(length), bassAt: new Array(length) };
  ch.forEach((c, i) => { for (let k = c.start; k < c.start + c.length; k++) piece.chordAt[k] = i; });
  for (const n of melody) piece.melodyAt[n.start] = n;
  for (const n of bass) piece.bassAt[n.start] = n;
  return { errors, warnings, piece };
}

function styleBass(style, ch, spb) {
  if (style === 'VOICING') return ch.map(c => ({ start: c.start, length: c.length, midi: c.voicing ? c.voicing[0] : lowest(c.bassClass, 36) }));
  const out = [];
  for (const c of ch) {
    if (style === 'HALVES') {
      const half = spb / 2;
      for (let s = c.start; s < c.start + c.length; s += half) out.push({ start: s, length: Math.min(half, c.start + c.length - s), midi: lowest(c.bassClass, 36) });
    } else {
      for (let s = c.start; s < c.start + c.length; s += 4) {
        const fifth = Math.floor(s / 4) % 2 === 1;
        out.push({ start: s, length: 4, midi: lowest(fifth ? (c.root + c.intervals[2]) % 12 : c.bassClass, fifth ? 33 : 36) });
      }
    }
  }
  return out;
}

/** The score manifest in the key order of the example packs. */
export function scoreManifest(s) {
  const o = { format: 1, type: 'score', id: s.id, title: s.title.trim(), character: s.character.trim(), composer: (s.composer || '').trim(),
    creator: (s.creator || '').trim(), license: (s.license || '').trim(), color: s.color, bpm: Number(s.bpm), tempoRange: Number(s.tempoRange),
    beatsPerBar: Number(s.beatsPerBar), tonic: s.tonic.trim(), chords: oneLine(s.chords) };
  if (String(s.voicings || '').trim()) o.voicings = oneLine(s.voicings);
  Object.assign(o, { melody: oneLine(s.melody), bass: oneLine(s.bass), arp: oneLine(s.arp), comp: oneLine(s.comp), high: s.high,
    canonBars: s.high === 'CANON' ? Number(s.canonBars) : 0, defaults: s.defaults.slice() });
  return o;
}
function oneLine(t) { return String(t).trim().split(/\s+/).join(' '); }

// ------------------------------------------------------------------ instruments (build_cloud.py)

export function hz(m) { return 440 * 2 ** ((m - 69) / 12); }
export function noteName(m) { return ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'][((m % 12) + 12) % 12] + (Math.floor(m / 12) - 1); }

/** Linear resampling after a short windowed-sinc low-pass, as build_cloud.resample. */
export function resample(x, src, dst = RATE) {
  if (src === dst) return Float32Array.from(x);
  const cutoff = 0.45 * Math.min(1, dst / src);
  const taps = 63, h = new Float64Array(taps);
  let sum = 0;
  for (let i = 0; i < taps; i++) {
    const n = i - 31, sinc = n === 0 ? 1 : Math.sin(2 * Math.PI * cutoff * n) / (2 * Math.PI * cutoff * n);
    const w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (taps - 1));
    h[i] = 2 * cutoff * sinc * w; sum += h[i];
  }
  const y = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) {
    let acc = 0;
    for (let k = 0; k < taps; k++) { const j = i + k - 31; if (j >= 0 && j < x.length) acc += x[j] * h[k]; }
    y[i] = acc / sum;
  }
  const n = Math.floor((y.length - 1) * dst / src), out = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i * src / dst, j = Math.floor(t), f = t - j; out[i] = y[j] * (1 - f) + y[j + 1] * f; }
  return out;
}

export function peak(x) { let p = 0; for (const v of x) p = Math.max(p, Math.abs(v)); return p; }

export function trimStart(x) {
  const limit = peak(x) * 0.02;
  let i = 0; while (i < x.length && Math.abs(x[i]) <= limit) i++;
  return x.slice(Math.max(0, i - Math.round(0.004 * RATE)));
}

/** Fundamental by YIN on 0.3..0.8 s (or what there is): returns Hz or null. */
export function detectPitch(x) {
  const a = Math.min(Math.round(0.3 * RATE), Math.floor(x.length / 4));
  const seg = x.slice(a, a + Math.round(0.5 * RATE));
  const n = Math.floor(seg.length / 2), maxLag = Math.min(Math.floor(RATE / 40), n - 1);
  if (maxLag < 30) return null;
  const d = new Float64Array(maxLag + 1);
  for (let t = 1; t <= maxLag; t++) { let s = 0; for (let i = 0; i < n; i++) { const v = seg[i] - seg[i + t]; s += v * v; } d[t] = s; }
  let run = 0, best = -1;
  const cm = new Float64Array(maxLag + 1); cm[0] = 1;
  for (let t = 1; t <= maxLag; t++) { run += d[t]; cm[t] = run > 0 ? d[t] * t / run : 1; }
  for (let t = 20; t < maxLag; t++) if (cm[t] < 0.15 && cm[t] <= cm[t + 1]) { best = t; break; }
  if (best < 0) { let m = Infinity; for (let t = 20; t <= maxLag; t++) if (cm[t] < m) { m = cm[t]; best = t; } if (m > 0.5) return null; }
  // Parabolic refinement.
  const p = best > 1 && best < maxLag ? best + (cm[best - 1] - cm[best + 1]) / (2 * (cm[best - 1] - 2 * cm[best] + cm[best + 1]) || 1) : best;
  return RATE / p;
}

/** MIDI note from a file name like "tbn_A#3_v2.wav" or "C4.wav", or null. */
export function noteFromName(name) {
  const m = /(?:^|[^A-Za-z])([A-G])(#|b|s)?(-?\d)(?![\d])/.exec(name.replace(/\.[^.]+$/, ''));
  if (!m) return null;
  return midi(m[1] + (m[2] === 's' ? '#' : m[2] || '') + m[3]);
}

/** A crossfaded loop that is a whole number of periods (build_cloud.make_loop). */
export function makeLoop(x, f0, start = Math.round(0.55 * RATE), length = RATE) {
  const period = RATE / f0;
  length = Math.max(Math.round(Math.floor(length / period) * period), Math.ceil(period));
  const end = start + length;
  if (end > x.length) return null;
  const fade = Math.min(Math.round(0.12 * RATE), start);
  const y = x.slice(0, end);
  for (let i = 0; i < fade; i++) {
    const w = i / (fade - 1 || 1);
    y[end - fade + i] = x[end - fade + i] * Math.sqrt(1 - w) + x[start - fade + i] * Math.sqrt(w);
  }
  return { x: y, loopStart: start, loopEnd: end };
}

/** The app bank's loudness rule: loudest 100 ms at RMS 0.25, clipped below full scale. */
export function level(x) {
  const frame = Math.round(0.1 * RATE), hop = frame / 2;
  let rms = 0;
  for (let i = 0; i < Math.max(1, x.length - frame); i += hop) {
    let s = 0, n = 0;
    for (let k = i; k < Math.min(x.length, i + frame); k++) { s += x[k] * x[k]; n++; }
    rms = Math.max(rms, Math.sqrt(s / Math.max(1, n)));
  }
  const g = 0.25 / Math.max(rms, 1e-6), out = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) out[i] = Math.max(-0.999, Math.min(0.999, x[i] * g));
  return out;
}

/** Designed timbre: decaying partials, a shimmer of detuned modes and a soft strike (as the handpan). */
export function designNote(f0, d, seconds = d.sustained ? 2.6 : 3.4, seed = Math.round(f0)) {
  let state = seed * 9301 + 49297;
  const rand = () => { state = (state * 16807) % 2147483647; return state / 2147483647; };
  const n = Math.round(RATE * seconds), x = new Float32Array(n);
  const scale = (220 / f0) ** 0.3;
  for (const p of d.partials) {
    if (!(p.level > 0)) continue;
    const f = f0 * p.ratio;
    if (f > 9000) continue;
    const phase = rand() * 6.28, detune = f + d.shimmer * (0.6 + 0.4 * p.ratio);
    const tau = d.sustained ? Infinity : p.decay * scale;
    for (let i = 0; i < n; i++) {
      const t = i / RATE, env = tau === Infinity ? 1 : Math.exp(-t / tau);
      x[i] += p.level * env * (Math.sin(2 * Math.PI * f * t + phase) + (d.shimmer > 0 ? 0.35 * Math.sin(2 * Math.PI * detune * t) : 0));
    }
  }
  const rise = Math.max(1, Math.round(RATE * d.attack));
  for (let i = 0; i < Math.min(n, rise); i++) x[i] *= i / rise;
  if (d.thump > 0) {
    const noise = new Float32Array(n);
    for (let i = 0; i < Math.min(n, RATE * 0.2); i++) noise[i] = (rand() * 2 - 1) * Math.exp(-i / RATE / 0.025);
    let acc = 0;
    for (let i = 0; i < n; i++) { acc += noise[i] - (i >= 40 ? noise[i - 40] : 0); x[i] += d.thump * acc / 40; }
  }
  if (!d.sustained) { const tail = Math.round(0.25 * RATE); for (let i = 0; i < tail; i++) x[n - tail + i] *= (1 - i / tail) ** 2; }
  return x;
}

export const DESIGNS = {
  handpan: { label: '핸드팬', sustained: false, attack: 0.006, thump: 0.6, shimmer: 1,
    partials: [{ ratio: 1, level: 1, decay: 2.6 }, { ratio: 2, level: 0.55, decay: 1.5 }, { ratio: 3, level: 0.28, decay: 0.9 }, { ratio: 4.03, level: 0.08, decay: 0.35 }] },
  bell: { label: '종소리', sustained: false, attack: 0.002, thump: 0.1, shimmer: 1.4,
    partials: [{ ratio: 1, level: 1, decay: 3.2 }, { ratio: 2.76, level: 0.5, decay: 1.4 }, { ratio: 5.4, level: 0.25, decay: 0.6 }, { ratio: 8.93, level: 0.12, decay: 0.25 }] },
  softpluck: { label: '부드러운 퉁김', sustained: false, attack: 0.003, thump: 0.2, shimmer: 0,
    partials: [{ ratio: 1, level: 1, decay: 0.9 }, { ratio: 2, level: 0.4, decay: 0.45 }, { ratio: 3, level: 0.2, decay: 0.25 }, { ratio: 4, level: 0.1, decay: 0.15 }] },
  warmpad: { label: '따뜻한 패드', sustained: true, attack: 0.08, thump: 0, shimmer: 0.8,
    partials: [{ ratio: 1, level: 1, decay: 1 }, { ratio: 2, level: 0.45, decay: 1 }, { ratio: 3, level: 0.18, decay: 1 }, { ratio: 4, level: 0.06, decay: 1 }] },
};

/**
 * Builds <id>.json and <id>.pcm from notes [{root, x (Float32 at RATE), loop: {loopStart, loopEnd} | null}].
 * Returns { manifest, pcm (Uint8Array), json (string) }.
 */
export function buildInstrument(meta, noteList) {
  const sorted = noteList.slice().sort((a, b) => a.root - b.root);
  const chunks = [], rows = [];
  let offset = 0;
  for (const n of sorted) {
    const q = new Int16Array(n.x.length), lv = level(n.x);
    for (let i = 0; i < q.length; i++) q[i] = Math.round(lv[i] * 32767);
    rows.push({ root: Math.round(n.root * 1000) / 1000, offset, length: q.length,
      loopStart: n.loop ? n.loop.loopStart + offset : -1, loopEnd: n.loop ? n.loop.loopEnd + offset : -1 });
    chunks.push(q); offset += q.length;
  }
  const pcm = new Uint8Array(offset * 2), view = new DataView(pcm.buffer);
  let at = 0;
  for (const q of chunks) for (const v of q) { view.setInt16(at, v, true); at += 2; }
  const manifest = { format: 1, type: 'instrument', id: meta.id, label: meta.label, sound: meta.sound, creator: meta.creator, license: meta.license,
    stages: meta.stages, sustained: meta.sustained, voices: meta.voices, mono: meta.mono, minMidi: meta.minMidi, maxMidi: meta.maxMidi,
    gain: meta.gain, attack: meta.attack, release: meta.release, look: meta.look, color: meta.color, rate: RATE, pcm: meta.id + '.pcm', notes: rows };
  return { manifest, pcm, json: JSON.stringify(manifest, null, 1) };
}

/** Checks instrument settings the way the app does (parseInstrument plus the band's stage rules). */
export function checkInstrument(meta, noteList) {
  const errors = [], warnings = [];
  if (!ID.test(meta.id || '')) errors.push({ field: 'id', message: '아이디는 영문 소문자·숫자·-·_로 40자까지' });
  if (!String(meta.label || '').trim()) errors.push({ field: 'label', message: '악기 이름을 적어 주세요' });
  const stages = (meta.stages || []).filter(s => s !== 1);
  if (!stages.length) errors.push({ field: 'stages', message: '연주할 단계를 하나 이상 고르세요 (리듬 단계는 안 됩니다)' });
  if (!noteList.length) errors.push({ field: 'notes', message: '음이 하나도 없습니다' });
  for (const [i, n] of noteList.entries()) {
    if (!(n.root > 20)) errors.push({ field: 'notes', message: `${i + 1}번 음의 높이를 알 수 없습니다` });
    if (n.x.length < RATE * 0.15) errors.push({ field: 'notes', message: `${i + 1}번 음이 너무 짧습니다 (0.15초 이상)` });
  }
  if (meta.minMidi >= meta.maxMidi) errors.push({ field: 'range', message: '음역의 아래가 위보다 낮아야 합니다' });
  if (meta.sustained && noteList.some(n => !n.loop)) warnings.push({ field: 'notes', message: '지속음인데 루프가 없는 음은 길이만큼만 울립니다 (1.6초 이상 녹음하면 루프를 만듭니다)' });
  const roots = noteList.map(n => 12 * Math.log2(n.root / 440) + 69).sort((a, b) => a - b);
  for (let i = 1; i < roots.length; i++) if (roots[i] - roots[i - 1] > 7) warnings.push({ field: 'notes', message: `${noteName(Math.round(roots[i - 1]))}와 ${noteName(Math.round(roots[i]))} 사이가 넓습니다: 사이 음은 많이 늘여 연주됩니다` });
  const bytes = noteList.reduce((s, n) => s + n.x.length * 2, 0);
  if (bytes > 16 * 1024 * 1024) errors.push({ field: 'notes', message: '소리 파일이 16 MB를 넘습니다. 음 수나 길이를 줄여 주세요' });
  return { errors, warnings };
}

// ------------------------------------------------------------------ catalog and pack zip

export function catalogItem(kind, manifest, sizes) {
  const folder = kind === 'score' ? 'scores' : 'instruments';
  const files = [`${folder}/${manifest.id}.json`].concat(kind === 'instrument' ? [`${folder}/${manifest.pcm}`] : []);
  return { id: manifest.id, type: kind, title: kind === 'score' ? manifest.title : manifest.label,
    subtitle: kind === 'score' ? manifest.character : (manifest.sound === 'recorded' ? '녹음한 악기' : '디자인한 음색'),
    creator: manifest.creator, license: manifest.license, look: manifest.look || '', color: manifest.color,
    stages: manifest.stages || [], files, bytes: files.reduce((s, f) => s + sizes[f], 0) };
}

export function utf8(text) { return new TextEncoder().encode(text); }

const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(b) { let c = 0xFFFFFFFF; for (const v of b) c = CRC[(c ^ v) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; }

/** A stored (uncompressed) zip of {path: Uint8Array}. */
export function zip(files) {
  const parts = [], central = [];
  let offset = 0;
  for (const [path, data] of Object.entries(files)) {
    const name = utf8(path), crc = crc32(data);
    const head = new DataView(new ArrayBuffer(30));
    head.setUint32(0, 0x04034b50, true); head.setUint16(4, 20, true); head.setUint16(6, 0x0800, true);
    head.setUint32(14, crc, true); head.setUint32(18, data.length, true); head.setUint32(22, data.length, true); head.setUint16(26, name.length, true);
    parts.push(new Uint8Array(head.buffer), name, data);
    const c = new DataView(new ArrayBuffer(46));
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true);
    c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, offset, true);
    central.push(new Uint8Array(c.buffer), name);
    offset += 30 + name.length + data.length;
  }
  const size = central.reduce((s, p) => s + p.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, Object.keys(files).length, true); end.setUint16(10, Object.keys(files).length, true);
  end.setUint32(12, size, true); end.setUint32(16, offset, true);
  const all = [...parts, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(all.reduce((s, p) => s + p.length, 0));
  let at = 0; for (const p of all) { out.set(p, at); at += p.length; }
  return out;
}
