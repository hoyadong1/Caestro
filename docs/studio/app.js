import * as C from './core.js';

const $ = id => document.getElementById(id);
const CLOUD = '../cloud/';
let ctx = null;
const audio = () => (ctx ??= new (window.AudioContext || window.webkitAudioContext)());

/** Packs made in this session, by id: { kind, manifest, files: {path: Uint8Array} }. */
const session = new Map();
/** Sampled instruments the preview can play (cloud and session), by id. */
const sampled = new Map();
let cloudCatalog = { format: 1, name: '카에스트로 클라우드', items: [] };

const SUSTAINED = new Set(['cello', 'bassoon', 'organ', 'strings', 'viola', 'horn', 'violin', 'flute', 'clarinet', 'oboe', 'trumpet', 'choir', 'synthstrings', 'glasspad']);
const SUSTAINED_HIGH = new Set(['flute', 'violin', 'strings', 'glasspad']);
const STAGE_LEVEL = [0.9, 0.5, 0.55, 0.6, 0.7, 0.5];

function save(name, bytes, type = 'application/octet-stream') {
  const url = URL.createObjectURL(new Blob([bytes], { type }));
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

// ------------------------------------------------------------------ tabs

for (const b of document.querySelectorAll('nav.tabs button')) b.addEventListener('click', () => showTab(b.dataset.tab));
function showTab(name) {
  for (const b of document.querySelectorAll('nav.tabs button')) b.setAttribute('aria-selected', String(b.dataset.tab === name));
  for (const s of document.querySelectorAll('main section')) s.hidden = s.id !== 'tab-' + name;
  if (name === 'pack') renderPack();
  try { history.replaceState(null, '', '#' + name); } catch { }
}

// ------------------------------------------------------------------ sampled instruments (preview)

function makeSampled(manifest, float32, source) {
  const a = audio();
  const notes = manifest.notes.map(n => {
    const buffer = a.createBuffer(1, n.length, C.RATE);
    buffer.getChannelData(0).set(float32.subarray(n.offset, n.offset + n.length));
    return { root: n.root, buffer, loopStart: n.loopStart >= 0 ? (n.loopStart - n.offset) / C.RATE : -1, loopEnd: n.loopEnd >= 0 ? (n.loopEnd - n.offset) / C.RATE : -1 };
  });
  return { manifest, notes, source };
}
function registerSampled(manifest, float32, source) { sampled.set(manifest.id, makeSampled(manifest, float32, source)); }
function pcmToFloat(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), out = new Float32Array(bytes.byteLength / 2);
  for (let i = 0; i < out.length; i++) out[i] = v.getInt16(i * 2, true) / 32768;
  return out;
}

function playSample(inst, midi, when, dur, vel, dest) {
  const m0 = inst.manifest;
  let m = midi;
  while (m < m0.minMidi) m += 12;
  while (m > m0.maxMidi) m -= 12;
  const f = C.hz(m);
  let best = inst.notes[0];
  for (const n of inst.notes) if (Math.abs(Math.log(f / n.root)) < Math.abs(Math.log(f / best.root))) best = n;
  const a = audio(), src = a.createBufferSource(), g = a.createGain();
  src.buffer = best.buffer;
  src.playbackRate.value = f / best.root;
  const looping = m0.sustained && best.loopStart >= 0;
  if (looping) { src.loop = true; src.loopStart = best.loopStart; src.loopEnd = best.loopEnd; }
  const level = vel * m0.gain * 0.16, attack = Math.max(0.002, m0.attack), release = Math.max(0.02, m0.release);
  g.gain.setValueAtTime(0, when);
  g.gain.linearRampToValueAtTime(level, when + attack);
  const off = when + Math.max(dur, attack);
  g.gain.setValueAtTime(level, off);
  g.gain.setTargetAtTime(0, off, release / 3);
  src.connect(g).connect(dest);
  src.start(when);
  src.stop(off + release * 2 + 0.05);
}

// ------------------------------------------------------------------ simple synth voices (the app's own instruments)

let noise = null;
function noiseBuffer() {
  if (noise) return noise;
  const a = audio(); noise = a.createBuffer(1, a.sampleRate, a.sampleRate);
  const d = noise.getChannelData(0); for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
  return noise;
}
function synth(stage, id, midi, when, dur, vel, dest) {
  const a = audio(), f = C.hz(midi), g = a.createGain(), out = a.createBiquadFilter();
  out.type = 'lowpass';
  const osc = (type, freq, level) => { const o = a.createOscillator(), og = a.createGain(); o.type = type; o.frequency.value = freq; og.gain.value = level; o.connect(og).connect(out); o.start(when); o.stop(when + dur + 2); return o; };
  const sustained = stage === 4 || stage === 0 ? SUSTAINED.has(id) : stage === 2 ? SUSTAINED.has(id) : stage === 5 ? SUSTAINED_HIGH.has(id) : false;
  let attack = 0.01, decay = null, level = vel;
  if (stage === 0) { osc('triangle', f, 0.9); osc('sine', f * 2, 0.25); out.frequency.value = 700; attack = sustained ? 0.05 : 0.005; if (!sustained) decay = 0.6; level *= 0.42; }
  else if (stage === 2) { osc('sawtooth', f, 0.5); osc('sawtooth', f * 1.004, 0.5); out.frequency.value = 1500; attack = sustained ? 0.18 : 0.01; if (!sustained) decay = 0.5; level *= 0.07; }
  else if (stage === 3) { osc('triangle', f, 1); osc('sine', f * 2, 0.3); out.frequency.value = 3500; attack = 0.003; decay = 0.32; level *= 0.2; }
  else if (stage === 4) { const o = osc('sawtooth', f, 0.7); osc('triangle', f, 0.6); out.frequency.value = 2400;
    const lfo = a.createOscillator(), lg = a.createGain(); lfo.frequency.value = 5.2; lg.gain.value = f * 0.004; lfo.connect(lg).connect(o.frequency); lfo.start(when + 0.2); lfo.stop(when + dur + 2);
    attack = sustained ? 0.05 : 0.005; if (!sustained) decay = 0.5; level *= 0.13; }
  else { osc('sine', f, 1); osc('sine', f * 2.76, 0.25); out.frequency.value = 7000; attack = sustained ? 0.12 : 0.002; if (!sustained) decay = 1.1; level *= 0.12; }
  g.gain.setValueAtTime(0, when);
  g.gain.linearRampToValueAtTime(level, when + attack);
  if (decay) g.gain.setTargetAtTime(0, when + attack, decay / 3);
  else { g.gain.setValueAtTime(level, when + dur); g.gain.setTargetAtTime(0, when + dur, 0.12); }
  out.connect(g).connect(dest);
}
function drum(position, spb, when, dest) {
  const a = audio(), accent = position === 0 ? 1 : position % 4 === 0 ? 0.7 : 0;
  if (!accent && position % 2) return;
  if (position === 0 || position === spb / 2) {
    const o = a.createOscillator(), g = a.createGain();
    o.frequency.setValueAtTime(110, when); o.frequency.exponentialRampToValueAtTime(48, when + 0.18);
    g.gain.setValueAtTime(0.5 * (position === 0 ? 1 : 0.7), when); g.gain.setTargetAtTime(0, when, 0.08);
    o.connect(g).connect(dest); o.start(when); o.stop(when + 0.5);
  }
  const src = a.createBufferSource(), bp = a.createBiquadFilter(), g = a.createGain();
  src.buffer = noiseBuffer(); bp.type = 'highpass'; bp.frequency.value = 6000;
  g.gain.setValueAtTime(accent ? 0.09 * accent : 0.035, when); g.gain.setTargetAtTime(0, when, 0.025);
  src.connect(bp).connect(g).connect(dest); src.start(when, Math.random() * 0.5); src.stop(when + 0.2);
}

// ------------------------------------------------------------------ score editor

const SCORE_FIELDS = ['id', 'title', 'character', 'composer', 'creator', 'license', 'color', 'beatsPerBar', 'tonic', 'bpm', 'tempoRange', 'chords', 'melody', 'arp', 'comp', 'high', 'canonBars', 'voicings'];
const TWINKLE = {
  id: 'twinkle', title: 'Twinkle', character: '프랑스 민요 · 반짝반짝 작은 별', composer: '프랑스 민요', creator: '', license: '퍼블릭 도메인',
  color: '#f2d16b', bpm: 84, tempoRange: 10, beatsPerBar: 4, tonic: 'C',
  chords: 'C:16 | F:8 C:8 | F:8 C:8 | G:8 C:8 | C:8 F:8 | C:8 G:8 | C:8 F:8 | C:8 G:8 | C:16 | F:8 C:8 | F:8 C:8 | G:8 C:8',
  melody: 'C5:4 C5 G5 G5 | A5 A5 G5:8 | F5:4 F5 E5 E5 | D5 D5 C5:8 | G5:4 G5 F5 F5 | E5 E5 D5:8 | G5:4 G5 F5 F5 | E5 E5 D5:8 | C5:4 C5 G5 G5 | A5 A5 G5:8 | F5:4 F5 E5 E5 | D5 D5 C5:8',
  bass: 'ROOT_FIFTH', arp: '0 . 2 . 1 . 2 . 0 . 2 . 1 . 2 .', comp: 'x . . . x . . . x . . . x . . .', high: 'BELLS', canonBars: 0,
  defaults: ['cello', 'orchperc', 'strings', 'harp', 'flute', 'glock'],
};

function stageChoices(stage) {
  const list = C.BUILT_IN[stage].map(([id, label]) => ({ id, label }));
  if (stage !== 1) for (const [id, s] of sampled) if (s.manifest.stages.includes(stage))
    list.splice(list.length - (list.at(-1).id === 'none' ? 1 : 0), 0, { id, label: `${s.manifest.label} · ${s.source}` });
  return list;
}

const stageBox = $('stages');
function renderStages(defaults = readDefaults()) {
  stageBox.innerHTML = C.STAGES.map((name, stage) => `<div class="stage" id="stage-${stage}"><b>${name}</b>${stage ? C.ENTER[stage] + ' km/h~' : '항상'}
    <select data-stage="${stage}" aria-label="${name} 추천 악기">${stageChoices(stage).map(o => `<option value="${esc(o.id)}">${esc(o.label)}</option>`).join('')}</select></div>`).join('');
  stageBox.querySelectorAll('select').forEach((sel, stage) => {
    const want = defaults?.[stage] ?? C.BAND_DEFAULT[stage];
    if (![...sel.options].some(o => o.value === want)) sel.add(new Option(`${want} (차에 없으면 기본)`, want));
    sel.value = want;
    sel.addEventListener('change', onScoreInput);
  });
  updateStageLights();
}
function readDefaults() { const s = stageBox.querySelectorAll('select'); return s.length ? [...s].map(x => x.value) : null; }

function readScore() {
  const o = {};
  for (const f of SCORE_FIELDS) o[f] = $('s-' + f).value;
  for (const f of ['bpm', 'tempoRange', 'beatsPerBar', 'canonBars']) o[f] = Number(o[f]);
  const style = $('s-bassStyle').value;
  o.bass = style === 'EXPLICIT' ? $('s-bass').value : style;
  o.defaults = readDefaults() ?? C.BAND_DEFAULT.slice();
  return o;
}
function writeScore(s) {
  for (const f of SCORE_FIELDS) {
    const el = $('s-' + f);
    let v = s[f] ?? '';
    if (f === 'license' && v && ![...el.options].some(o => o.value === v)) el.add(new Option(v, v));
    if (f === 'color') v = /^#[0-9a-f]{6}$/i.test(v) ? v.toLowerCase() : '#8fd0b8';
    el.value = v;
  }
  const styles = ['HALVES', 'ROOT_FIFTH', 'VOICING'];
  $('s-bassStyle').value = styles.includes(s.bass) ? s.bass : 'EXPLICIT';
  $('s-bass').value = styles.includes(s.bass) ? '' : s.bass;
  $('s-bass').hidden = styles.includes(s.bass);
  if (s.voicings) $('s-voicings').closest('details').open = true;
  renderStages(s.defaults);
  onScoreInput();
}

let piece = null, lastCheck = null;
const FIELD_INPUT = { bass: 's-bass', defaults: 'stages' };
function onScoreInput() {
  $('s-bass').hidden = $('s-bassStyle').value !== 'EXPLICIT';
  $('s-canonBars').disabled = $('s-high').value !== 'CANON';
  const s = readScore();
  const r = C.checkScore(s);
  lastCheck = { score: s, result: r };
  document.querySelectorAll('#tab-score .invalid').forEach(e => e.classList.remove('invalid'));
  for (const e of r.errors) $(FIELD_INPUT[e.field] ?? 's-' + e.field)?.classList.add('invalid');
  if (r.errors.some(e => e.field === 'bass') && $('s-bassStyle').value !== 'EXPLICIT') $('s-bassStyle').classList.add('invalid');
  const st = $('score-status');
  if (r.errors.length) st.innerHTML = `<span class="bad">차가 이 악보를 받지 않습니다. 고칠 곳 ${r.errors.length}개:</span><ul>${r.errors.map(e => `<li class="bad">${esc(e.message)}</li>`).join('')}</ul>`;
  else {
    const p = r.piece;
    st.innerHTML = `<span class="ok">✓ 차에서 연주할 수 있는 악보입니다.</span> ${p.lengthSteps / p.spb}마디 · 선율 ${p.melody.length}음 · 코드 ${p.chords.length}개 · 한 바퀴 ${Math.round(p.lengthSteps * 60 / p.bpm / 4)}초 (정차 시)` +
      (r.warnings.length ? `<ul>${r.warnings.map(w => `<li class="warn">${esc(w.message)}</li>`).join('')}</ul>` : '');
    piece = p;
  }
  $('score-add').disabled = $('score-download').disabled = !!r.errors.length;
  drawRoll();
}
const onScoreInputSoon = debounce(onScoreInput, 180);
for (const f of [...SCORE_FIELDS, 'bassStyle', 'bass']) $('s-' + f).addEventListener('input', onScoreInputSoon);

$('ex-twinkle').addEventListener('click', () => writeScore(TWINKLE));
$('ex-largo').addEventListener('click', async () => {
  try { writeScore(await (await fetch(CLOUD + 'scores/largo.json')).json()); }
  catch { $('score-status').innerHTML = '<span class="bad">클라우드의 「라르고」를 불러오지 못했습니다.</span>'; }
});
$('score-open').addEventListener('change', async e => {
  const file = e.target.files[0]; if (!file) return;
  try { const s = JSON.parse(await file.text()); if (s.type !== 'score') throw 0; writeScore(s); }
  catch { $('score-status').innerHTML = '<span class="bad">악보 JSON이 아닙니다.</span>'; }
  e.target.value = '';
});
function scoreFiles() {
  const manifest = C.scoreManifest(lastCheck.score);
  return { manifest, files: { [`scores/${manifest.id}.json`]: C.utf8(JSON.stringify(manifest, null, 1)) } };
}
$('score-download').addEventListener('click', () => { const { manifest, files } = scoreFiles(); save(manifest.id + '.json', Object.values(files)[0], 'application/json'); });
$('score-add').addEventListener('click', () => {
  const { manifest, files } = scoreFiles();
  session.set(manifest.id, { kind: 'score', manifest, files });
  $('score-status').insertAdjacentHTML('beforeend', `<div class="ok">「${esc(manifest.title)}」을 클라우드 팩에 담았습니다. ③ 탭에서 올립니다.</div>`);
  updatePackButton();
});

// ------------------------------------------------------------------ piano roll

const roll = $('roll'), g2 = roll.getContext('2d');
let playStep = -1;
function drawRoll() {
  const W = roll.width, H = roll.height;
  g2.clearRect(0, 0, W, H);
  if (!piece) { g2.fillStyle = '#5f7f90'; g2.font = '22px sans-serif'; g2.fillText('악보가 맞으면 여기에 보입니다', 30, H / 2); return; }
  const p = piece, L = p.lengthSteps, left = 44, top = 34, sw = (W - left - 10) / L;
  const all = [...p.melody, ...p.bass];
  let lo = Math.min(...all.map(n => n.midi)) - 2, hi = Math.max(...all.map(n => n.midi)) + 2;
  if (hi - lo < 24) { const mid = (hi + lo) / 2; lo = Math.floor(mid - 12); hi = Math.ceil(mid + 12); }
  const nh = (H - top - 8) / (hi - lo + 1), y = m => top + (hi - m) * nh;
  for (let m = lo; m <= hi; m++) if ([1, 3, 6, 8, 10].includes(((m % 12) + 12) % 12)) { g2.fillStyle = '#0a1b27'; g2.fillRect(left, y(m), W - left, nh); }
  g2.fillStyle = '#6f8fa0'; g2.font = '12px sans-serif';
  for (let m = lo; m <= hi; m++) if (m % 12 === 0) g2.fillText(C.noteName(m), 6, y(m) + nh - 2);
  for (const c of p.chords) {
    const x = left + c.start * sw;
    g2.fillStyle = '#123247'; g2.fillRect(x + 1, 6, c.length * sw - 2, 22);
    g2.fillStyle = '#bfe7f8'; g2.font = 'bold 13px sans-serif';
    if (c.length * sw > 24) g2.fillText(c.name, x + 5, 22);
    // Chord tones as faint bands.
    g2.fillStyle = '#7bd5ff12';
    for (const m of c.harmony) for (let k = m - 24; k <= hi; k += 12) if (k >= lo && ((k - m) % 12 === 0)) g2.fillRect(x, y(k), c.length * sw, nh);
  }
  for (let s = 0; s <= L; s += p.spb) { g2.fillStyle = s % (p.spb * 4) ? '#1d3a4c' : '#2f5a72'; g2.fillRect(left + s * sw, top, 1, H - top); }
  const note = (n, color) => { g2.fillStyle = color; g2.fillRect(left + n.start * sw + 1, y(n.midi) + 1, Math.max(2, n.length * sw - 2), Math.max(3, nh - 2)); };
  for (const n of p.bass) note(n, '#d08a52');
  for (const n of p.melody) note(n, '#70cfff');
  if (playStep >= 0) { g2.fillStyle = '#ffffffcc'; g2.fillRect(left + (playStep % L) * sw, 0, 2, H); }
}

// ------------------------------------------------------------------ playback (PieceEngine, simplified)

const speedInput = $('speed');
let playing = false, timer = 0, nextTime = 0, stepCount = 0, master = null, stageGains = [], arpSide = 1;
const queue = [];

function ensureGraph() {
  if (master) return;
  const a = audio();
  master = a.createDynamicsCompressor(); master.threshold.value = -12; master.ratio.value = 3;
  const out = a.createGain(); out.gain.value = 0.9; master.connect(out).connect(a.destination);
  stageGains = C.STAGES.map((_, s) => { const g = a.createGain(); g.gain.value = s === 0 ? 1 : 0; g.connect(master); return g; });
}
function speed() { return Number(speedInput.value); }
function updateStageLights() {
  const v = speed();
  C.STAGES.forEach((_, s) => {
    const on = s === 0 || v >= C.ENTER[s];
    $('stage-' + s)?.classList.toggle('on', on);
    if (stageGains[s] && ctx) stageGains[s].gain.setTargetAtTime(on ? STAGE_LEVEL[s] : 0, ctx.currentTime, 0.25);
  });
  $('kmh').textContent = v;
  $('bpmNow').textContent = piece ? Math.round(piece.bpm + piece.tempoRange * v / 130) : '–';
}
speedInput.addEventListener('input', updateStageLights);

function playStage(stage, midi, steps, vel, when, stepDur) {
  const id = readDefaults()?.[stage] ?? C.BAND_DEFAULT[stage];
  if (id === 'none') return;
  const dur = steps * stepDur * 0.95, dest = stageGains[stage];
  const inst = sampled.get(id);
  if (inst) playSample(inst, midi, when, dur, vel, dest); else synth(stage, id, midi, when, dur, vel, dest);
}
function sustainedAt(stage) { const id = readDefaults()?.[stage]; const s = sampled.get(id); return s ? s.manifest.sustained : (stage === 5 ? SUSTAINED_HIGH : SUSTAINED).has(id); }

function scheduleStep(p, s, when, stepDur) {
  const spb = p.spb, position = s % spb, span = p.chords[p.chordAt[s]];
  const accent = position === 0 ? 1 : position % 4 === 0 ? 0.86 : 0.74;
  const b = p.bassAt[s]; if (b) playStage(0, b.midi, b.length, 0.95, when, stepDur);
  if (readDefaults()?.[1] !== 'none') drum(position, spb, when, stageGains[1]);
  if (sustainedAt(2)) { if (span.start === s) for (const m of span.harmony) playStage(2, m, span.length, 0.8, when, stepDur); }
  else if (p.comp[position]) { let until = 1; while (position + until < spb && !p.comp[position + until]) until++; for (const m of span.harmony) playStage(2, m, until, accent * 0.85, when, stepDur); }
  const index = p.arp[position];
  if (index >= 0) { arpSide = -arpSide; playStage(3, span.arp[index % span.arp.length], 3, accent * 0.9, when, stepDur); }
  const mel = p.melodyAt[s]; if (mel) playStage(4, mel.midi, mel.length, 0.9, when, stepDur);
  if (p.high === 'BELLS') {
    if (sustainedAt(5)) { if (span.start === s) for (const m of span.harmony.slice(-2)) playStage(5, m + 12, span.length, 0.7, when, stepDur); }
    else if (s % (spb / 2) === 0 && (span.start === s || span.length > spb / 2)) playStage(5, span.harmony.at(-1) + 12, spb / 2, 0.8, when, stepDur);
  } else if (p.high === 'CANON') { const n = p.melodyAt[(((s - p.canonBars * spb) % p.lengthSteps) + p.lengthSteps) % p.lengthSteps]; if (n) playStage(5, n.midi, n.length, 0.75, when, stepDur); }
  else { if (mel) playStage(5, mel.midi + 12, mel.length, 0.75, when, stepDur); }
}

function tick() {
  if (!piece) return;
  const a = audio(), p = piece;
  while (nextTime < a.currentTime + 0.15) {
    const stepDur = 60 / (p.bpm + p.tempoRange * speed() / 130) / 4;
    const s = stepCount % p.lengthSteps;
    scheduleStep(p, s, nextTime, stepDur);
    queue.push([nextTime, s]);
    nextTime += stepDur; stepCount++;
  }
}
function frame() {
  if (!playing) return;
  while (queue.length > 1 && queue[1][0] <= ctx.currentTime) queue.shift();
  if (queue.length) playStep = queue[0][1];
  drawRoll();
  requestAnimationFrame(frame);
}
$('play').addEventListener('click', async () => {
  if (playing) { stop(); return; }
  if (!piece || lastCheck?.result.errors.length) return;
  ensureGraph(); await audio().resume();
  playing = true; stepCount = 0; queue.length = 0; nextTime = ctx.currentTime + 0.08;
  updateStageLights();
  timer = setInterval(tick, 30); tick(); requestAnimationFrame(frame);
  $('play').textContent = '■ 정지';
});
function stop() {
  playing = false; clearInterval(timer); playStep = -1; drawRoll();
  $('play').textContent = '▶ 재생';
  if (stageGains.length) { const t = ctx.currentTime; stageGains.forEach(g => { g.gain.cancelScheduledValues(t); g.gain.setTargetAtTime(0, t, 0.05); }); setTimeout(() => { if (!playing) updateStageLights(); }, 400); }
}

// ------------------------------------------------------------------ instrument maker

const inst = { mode: 'recorded', recorded: [], designed: [] };
const midiOptions = (sel, value) => { sel.innerHTML = ''; for (let m = 24; m <= 108; m++) sel.add(new Option(C.noteName(m), m)); sel.value = value; };
midiOptions($('i-min'), 40); midiOptions($('i-max'), 76);
$('i-look').innerHTML = C.LOOKS.map(l => `<option>${l}</option>`).join(''); $('i-look').value = 'BRASS';
$('i-stages').innerHTML = [0, 2, 3, 4, 5].map(s => `<label><input type="checkbox" value="${s}" ${[0, 2, 4].includes(s) ? 'checked' : ''}> ${C.STAGES[s]}</label>`).join('');
$('i-id').value = 'my-instrument'; $('i-label').value = '내 악기';

for (const b of document.querySelectorAll('.seg button')) b.addEventListener('click', () => setMode(b.dataset.mode));
function setMode(mode) {
  inst.mode = mode;
  for (const b of document.querySelectorAll('.seg button')) b.setAttribute('aria-pressed', String(b.dataset.mode === mode));
  $('mode-recorded').hidden = mode !== 'recorded'; $('mode-designed').hidden = mode !== 'designed';
  if (mode === 'designed' && !inst.designed.length) { defaultsFor('designed'); applyDesign('handpan'); makeDesigned(); }
  renderNotes(); onInstInput();
}
function defaultsFor(mode) {
  if (mode === 'designed') {
    if ($('i-id').value === 'my-instrument') { $('i-id').value = 'my-tone'; $('i-label').value = '내 음색'; }
    $('i-look').value = 'GLASS'; $('i-color').value = '#7dd8cb';
    $('i-license').value = '직접 디자인 · CC BY 4.0'; $('i-min').value = 50; $('i-max').value = 86;
    $('i-sustained').checked = false; $('i-attack').value = 0.002; $('i-release').value = 0.9; $('i-gain').value = 1.9; $('i-voices').value = 6;
    for (const c of $('i-stages').querySelectorAll('input')) c.checked = [3, 4, 5].includes(Number(c.value));
  }
}

// Recorded notes.
const drop = $('drop');
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); addFiles(e.dataTransfer.files); });
$('files').addEventListener('change', e => { addFiles(e.target.files); e.target.value = ''; });

async function addFiles(fileList) {
  const st = $('inst-status');
  const list = Array.from(fileList); // the input is cleared right after, which empties its live FileList
  for (const file of list) {
    st.textContent = `「${file.name}」 읽는 중…`;
    try {
      const decoded = await audio().decodeAudioData(await file.arrayBuffer());
      const mono = new Float32Array(decoded.length);
      for (let c = 0; c < decoded.numberOfChannels; c++) { const d = decoded.getChannelData(c); for (let i = 0; i < mono.length; i++) mono[i] += d[i] / decoded.numberOfChannels; }
      let x = C.trimStart(C.resample(mono, decoded.sampleRate));
      x = x.slice(0, Math.round(C.RATE * 2.6));
      const named = C.noteFromName(file.name), heard = C.detectPitch(x);
      let root = heard ?? (named != null ? C.hz(named) : null), warn = '';
      if (named != null && heard) {
        const diff = 12 * Math.log2(heard / C.hz(named));
        if (Math.abs(diff) > 0.6) warn = `파일 이름은 ${C.noteName(named)}, 들리는 음은 ${C.noteName(Math.round(69 + 12 * Math.log2(heard / 440)))}`;
      }
      if (!root) warn = '음높이를 못 찾았습니다: 음을 골라 주세요';
      inst.recorded.push({ name: file.name, root: root ?? C.hz(60), x, warn });
    } catch { inst.recorded.push({ name: file.name, root: 0, x: new Float32Array(0), warn: '읽을 수 없는 파일입니다' }); }
  }
  inst.recorded.sort((a, b) => a.root - b.root);
  renderNotes(); onInstInput();
}

// Designed notes.
$('d-preset').innerHTML = Object.entries(C.DESIGNS).map(([k, d]) => `<option value="${k}">${d.label}</option>`).join('');
let design = null;
function applyDesign(key) {
  design = structuredClone(C.DESIGNS[key]);
  $('d-preset').value = key; $('d-attack').value = design.attack; $('d-thump').value = design.thump; $('d-shimmer').value = design.shimmer;
  $('i-sustained').checked = design.sustained;
  $('partials').innerHTML = '<span></span><span>배음 (배)</span><span>크기</span><span>울림 (초)</span>' + design.partials.map((p, i) => `
    <span>${i + 1}</span><input type="number" step="0.01" min="0.5" max="12" value="${p.ratio}" data-p="${i}" data-k="ratio">
    <input type="range" min="0" max="1" step="0.01" value="${p.level}" data-p="${i}" data-k="level">
    <input type="range" min="0.05" max="5" step="0.05" value="${p.decay}" data-p="${i}" data-k="decay" ${design.sustained ? 'disabled' : ''}>`).join('');
}
$('d-preset').addEventListener('change', e => { applyDesign(e.target.value); makeDesigned(); });
$('partials').addEventListener('input', e => { const t = e.target; if (t.dataset.p) design.partials[t.dataset.p][t.dataset.k] = Number(t.value); });
for (const k of ['attack', 'thump', 'shimmer']) $('d-' + k).addEventListener('input', e => { design[k] = Number(e.target.value); });
$('d-make').addEventListener('click', makeDesigned);
function makeDesigned() {
  design.sustained = $('i-sustained').checked;
  const lo = Number($('i-min').value), hi = Number($('i-max').value);
  inst.designed = [];
  for (let m = lo; m <= hi; m += 4) inst.designed.push({ name: `${C.noteName(m)} · ${design.label}`, root: C.hz(m), x: C.designNote(C.hz(m), design), warn: '' });
  renderNotes(); onInstInput();
}

function currentNotes() { return inst.mode === 'recorded' ? inst.recorded : inst.designed; }
function finalNotes() {
  const sustained = $('i-sustained').checked;
  return currentNotes().filter(n => n.x.length).map(n => {
    if (!sustained) return { root: n.root, x: n.x, loop: null };
    const l = C.makeLoop(n.x, n.root);
    return l ? { root: n.root, x: l.x, loop: { loopStart: l.loopStart, loopEnd: l.loopEnd } } : { root: n.root, x: n.x, loop: null };
  });
}
function renderNotes() {
  const list = currentNotes();
  $('note-list').innerHTML = list.map((n, i) => {
    const m = Math.round(69 + 12 * Math.log2((n.root || 261.6) / 440));
    const opts = Array.from({ length: 85 }, (_, k) => k + 24).map(k => `<option value="${k}" ${k === m ? 'selected' : ''}>${C.noteName(k)}</option>`).join('');
    return `<div class="note"><span class="name" title="${esc(n.name)}">${esc(n.name)}${n.warn ? `<br><span style="color:var(--warn)">${esc(n.warn)}</span>` : ''}</span>
      <select data-i="${i}" aria-label="음 높이" ${inst.mode === 'designed' ? 'disabled' : ''}>${opts}</select>
      <span class="len">${(n.x.length / C.RATE).toFixed(1)}초</span>
      <button class="btn mini" data-play="${i}">▶</button>${inst.mode === 'recorded' ? `<button class="btn mini del" data-del="${i}">✕</button>` : ''}</div>`;
  }).join('');
}
$('note-list').addEventListener('change', e => { const i = e.target.dataset.i; if (i != null) { inst.recorded[i].root = C.hz(Number(e.target.value)); inst.recorded[i].warn = ''; onInstInput(); } });
$('note-list').addEventListener('click', async e => {
  const t = e.target;
  if (t.dataset.del != null) { inst.recorded.splice(Number(t.dataset.del), 1); renderNotes(); onInstInput(); }
  if (t.dataset.play != null) {
    const n = currentNotes()[Number(t.dataset.play)]; if (!n?.x.length) return;
    await audio().resume();
    const lv = C.level(n.x), b = ctx.createBuffer(1, lv.length, C.RATE); b.getChannelData(0).set(lv);
    const s = ctx.createBufferSource(), g = ctx.createGain(); g.gain.value = 0.35; s.buffer = b; s.connect(g).connect(ctx.destination); s.start();
  }
});

function readInstMeta() {
  return {
    id: $('i-id').value.trim(), label: $('i-label').value.trim(), sound: inst.mode, creator: $('i-creator').value.trim(), license: $('i-license').value,
    stages: [...$('i-stages').querySelectorAll('input:checked')].map(c => Number(c.value)),
    sustained: $('i-sustained').checked, voices: Math.max(1, Math.min(8, Number($('i-voices').value) || 4)), mono: $('i-mono').checked,
    minMidi: Number($('i-min').value), maxMidi: Number($('i-max').value),
    gain: Number($('i-gain').value), attack: Number($('i-attack').value), release: Number($('i-release').value), look: $('i-look').value, color: $('i-color').value.toUpperCase(),
  };
}
let built = null;
function onInstInput() {
  const meta = readInstMeta(), notes = finalNotes();
  const r = C.checkInstrument(meta, notes);
  for (const n of currentNotes()) if (n.warn) r.warnings.push({ field: 'notes', message: `${n.name}: ${n.warn}` });
  document.querySelectorAll('#tab-instrument .invalid').forEach(e => e.classList.remove('invalid'));
  for (const e of r.errors) ({ id: $('i-id'), label: $('i-label'), range: $('i-max') })[e.field]?.classList.add('invalid');
  built = null;
  const st = $('inst-status');
  if (r.errors.length) st.innerHTML = `<span class="bad">고칠 곳 ${r.errors.length}개:</span><ul>${r.errors.map(e => `<li class="bad">${esc(e.message)}</li>`).join('')}</ul>`;
  else {
    built = C.buildInstrument(meta, notes);
    const loops = notes.filter(n => n.loop).length;
    st.innerHTML = `<span class="ok">✓ 차의 악단에 넣을 수 있는 악기입니다.</span> ${notes.length}음 · ${(built.pcm.length / 1048576).toFixed(2)} MB${meta.sustained ? ` · 루프 ${loops}/${notes.length}` : ''} · ${meta.stages.map(s => C.STAGES[s]).join('·')}` +
      (r.warnings.length ? `<ul>${r.warnings.map(w => `<li class="warn">${esc(w.message)}</li>`).join('')}</ul>` : '');
    preview = makeSampled(built.manifest, pcmToFloat(built.pcm), '만드는 중');
  }
  $('inst-add').disabled = $('inst-download').disabled = !built;
  renderKeys();
}
let preview = null;
const onInstInputSoon = debounce(onInstInput, 250);
for (const id of ['i-id', 'i-label', 'i-creator', 'i-license', 'i-look', 'i-color', 'i-voices', 'i-gain', 'i-attack', 'i-release', 'i-mono', 'i-sustained', 'i-min', 'i-max']) $(id).addEventListener('input', onInstInputSoon);
$('i-stages').addEventListener('change', onInstInputSoon);
$('i-sustained').addEventListener('change', () => { if (inst.mode === 'designed' && design) { design.sustained = $('i-sustained').checked; applyDesignInputs(); } });
function applyDesignInputs() { $('partials').querySelectorAll('[data-k="decay"]').forEach(el => { el.disabled = design.sustained; }); }

function renderKeys() {
  const lo = Number($('i-min').value), hi = Number($('i-max').value);
  $('keys').innerHTML = Array.from({ length: 49 }, (_, k) => k + 36).map(m => {
    const black = [1, 3, 6, 8, 10].includes(m % 12);
    return `<button class="${black ? 'black' : ''} ${m < lo || m > hi ? 'out' : ''}" data-m="${m}" aria-label="${C.noteName(m)}">${m % 12 === 0 ? C.noteName(m) : ''}</button>`;
  }).join('');
}
$('keys').addEventListener('pointerdown', async e => {
  const m = Number(e.target.dataset.m); if (!m || !preview) return;
  await audio().resume();
  ensureGraph();
  playSample(preview, m, ctx.currentTime + 0.01, preview.manifest.sustained ? 0.9 : 0.4, 1, master);
});
$('inst-download').addEventListener('click', () => { if (!built) return; save(built.manifest.id + '.json', C.utf8(built.json), 'application/json'); setTimeout(() => save(built.manifest.pcm, built.pcm), 400); });
$('inst-add').addEventListener('click', () => {
  if (!built) return;
  const m = built.manifest;
  session.set(m.id, { kind: 'instrument', manifest: m, files: { [`instruments/${m.id}.json`]: C.utf8(built.json), [`instruments/${m.pcm}`]: built.pcm } });
  registerSampled(m, pcmToFloat(built.pcm), '새로 만듦');
  renderStages(readDefaults());
  $('inst-status').insertAdjacentHTML('beforeend', `<div class="ok">「${esc(m.label)}」을 담았습니다. ① 악보 탭의 단계 칸에서 골라 들어 볼 수 있습니다.</div>`);
  updatePackButton();
});

// ------------------------------------------------------------------ pack

function updatePackButton() { $('pack-zip').disabled = !session.size; }
function sizesOf(files) { return Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.length])); }
function renderPack() {
  const newItems = [...session.values()].map(s => ({ ...C.catalogItem(s.kind, s.manifest, sizesOf(s.files)), fresh: true }));
  const ids = new Set(newItems.map(i => i.id));
  const items = [...cloudCatalog.items.filter(i => !ids.has(i.id)), ...newItems];
  $('pack').innerHTML = items.map(i => `<div class="item" style="--c:${esc(i.color)}">${i.fresh ? '<div class="new">NEW · 이번에 담음</div>' : ''}
    <b>${esc(i.title)}</b> <span class="meta">${i.type === 'score' ? '악보' : '악기'}</span><br>${esc(i.subtitle)}<br>
    <span class="meta">${esc(i.creator || '')} · ${esc(i.license || '')} · ${(i.bytes / 1024).toFixed(i.bytes > 1e5 ? 0 : 1)} KB${i.stages?.length ? ' · ' + i.stages.map(s => C.STAGES[s]).join('·') : ''}</span>
    ${i.fresh ? `<div class="btns" style="margin-top:8px"><button class="btn mini" data-remove="${esc(i.id)}">빼기</button></div>` : ''}</div>`).join('') || '<p class="hint">클라우드 목록을 불러오는 중…</p>';
  updatePackButton();
}
$('pack').addEventListener('click', e => { const id = e.target.dataset.remove; if (id) { session.delete(id); renderPack(); } });
$('pack-zip').addEventListener('click', () => {
  const files = {};
  const newItems = [];
  for (const s of session.values()) { Object.assign(files, s.files); newItems.push(C.catalogItem(s.kind, s.manifest, sizesOf(s.files))); }
  const ids = new Set(newItems.map(i => i.id));
  const catalog = { format: 1, name: cloudCatalog.name, items: [...cloudCatalog.items.filter(i => !ids.has(i.id)), ...newItems] };
  files['catalog.json'] = C.utf8(JSON.stringify(catalog, null, 1));
  save('caestro-cloud-pack.zip', C.zip(files), 'application/zip');
  $('pack-status').innerHTML = `<span class="ok">✓ 팩을 받았습니다:</span> 새 항목 ${newItems.length}개와 갱신된 <code>catalog.json</code>. 저장소의 <code>docs/cloud</code>에 풀어 올리면 차의 클라우드 탭에 나타납니다.`;
});

// ------------------------------------------------------------------ start

async function loadCloud() {
  try {
    cloudCatalog = await (await fetch(CLOUD + 'catalog.json')).json();
    for (const item of cloudCatalog.items.filter(i => i.type === 'instrument')) {
      const [json, pcm] = item.files;
      const manifest = await (await fetch(CLOUD + json)).json();
      const bytes = new Uint8Array(await (await fetch(CLOUD + pcm)).arrayBuffer());
      registerSampled(manifest, pcmToFloat(bytes), '클라우드');
    }
    renderStages(readDefaults());
  } catch { /* the editor works without the example cloud (e.g. opened from disk) */ }
  renderPack();
}

renderStages(TWINKLE.defaults);
writeScore(TWINKLE);
renderKeys();
onInstInput();
loadCloud();
const start = location.hash.slice(1);
if (['score', 'instrument', 'pack'].includes(start)) showTab(start);
