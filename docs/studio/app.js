import * as C from './core.js';
import { SUPABASE_URL, SUPABASE_KEY } from './config.js';

const $ = id => document.getElementById(id);
const CLOUD = '../cloud/';
let ctx = null;
const audio = () => (ctx ??= new (window.AudioContext || window.webkitAudioContext)());

/** What this visitor put on the list to send, by id: { kind, manifest, files: {path: Uint8Array} }. */
const session = new Map();
/** Sampled instruments the preview can play (cloud and made here), by id. */
const sampled = new Map();
let cloudCatalog = { format: 1, name: '카에스트로 클라우드', items: [] };

const SUSTAINED = new Set(['cello', 'bassoon', 'organ', 'strings', 'viola', 'horn', 'violin', 'flute', 'clarinet', 'oboe', 'trumpet', 'choir', 'synthstrings', 'glasspad']);
const SUSTAINED_HIGH = new Set(['flute', 'violin', 'strings', 'glasspad']);
const STAGE_LEVEL = [1, 1, 1, 1, 1, 1];
/** The app's own level per stage (PieceEngine.STAGE_GAIN) and the studio's output trim. */
const STAGE_GAIN = [0.085, 1.0, 0.036, 0.054, 0.066, 0.036];
const OUTPUT = 2.4;

// ------------------------------------------------------------------ the app's instruments, recorded from the app

/** bank.json: every built-in instrument rendered with the app's own voices (StudioBankExport). */
let bank = null;
const bankBuffers = new Map();
/** The decoded recording of [id], or null while it loads (loading starts on the first ask). */
function bankBuffer(id) {
  const info = bank?.instruments[id];
  if (!info) return null;
  const have = bankBuffers.get(id);
  if (have instanceof AudioBuffer) return have;
  if (!have) bankBuffers.set(id, fetch('bank/' + info.file).then(r => r.arrayBuffer()).then(x => audio().decodeAudioData(x))
    .then(buffer => { bankBuffers.set(id, buffer); return buffer; }).catch(() => bankBuffers.delete(id)));
  return null;
}
function preloadBand() { for (const id of readDefaults() ?? []) bankBuffer(id); }
async function loadBank() {
  try { bank = await (await fetch('bank/bank.json')).json(); preloadBand(); } catch { /* the simple voices stand in */ }
}
/** One note of a built-in instrument from the bank; false while its recording is not loaded yet. */
function playBank(id, stage, midi, when, dur, vel, dest) {
  const info = bank?.instruments[id], buffer = bankBuffer(id);
  if (!info || !buffer || info.kind !== 'melodic') return false;
  let m = midi;
  while (m < info.min) m += 12;
  while (m > info.max) m -= 12;
  let best = info.notes[0];
  for (const n of info.notes) if (Math.abs(n.midi - m) < Math.abs(best.midi - m)) best = n;
  const a = audio(), src = a.createBufferSource(), g = a.createGain();
  src.buffer = buffer;
  src.playbackRate.value = 2 ** ((m - best.midi) / 12);
  const available = info.slot / src.playbackRate.value;
  const end = info.sustained ? Math.max(0.05, Math.min(dur, available - 0.3)) : Math.min(available, dur + 1.2);
  const level = vel * STAGE_GAIN[stage] * OUTPUT / bank.scale / 0.9;
  g.gain.setValueAtTime(level, when);
  g.gain.setValueAtTime(level, when + end);
  g.gain.setTargetAtTime(0, when + end, info.sustained ? 0.08 : 0.15);
  src.connect(g).connect(dest);
  src.start(when, best.at, info.slot);
  src.stop(when + end + 0.8);
  return true;
}
/** One bar of a built-in rhythm section, from the bank, stretched to the tempo. */
function playKit(id, beats, when, bpm, dest) {
  const info = bank?.instruments[id], buffer = bankBuffer(id);
  if (!info || !buffer || info.kind !== 'rhythm') return false;
  const [start, length] = info.loops[beats] ?? info.loops['4'];
  const a = audio(), src = a.createBufferSource(), g = a.createGain();
  src.buffer = buffer; src.playbackRate.value = bpm / info.bpm;
  g.gain.value = STAGE_GAIN[1] * OUTPUT / bank.scale;
  src.connect(g).connect(dest);
  src.start(when, start, length);
  return true;
}

function save(name, bytes, type = 'application/octet-stream') {
  const url = URL.createObjectURL(new Blob([bytes], { type }));
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
let toastTimer = 0;
function toast(text) { const t = $('toast'); t.textContent = text; t.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), 2600); }
function press(group, button) { for (const b of group.querySelectorAll('[aria-pressed]')) b.setAttribute('aria-pressed', String(b === button)); }

// ------------------------------------------------------------------ tabs

for (const b of document.querySelectorAll('nav.tabs button')) b.addEventListener('click', () => showTab(b.dataset.tab));
function showTab(name) {
  for (const b of document.querySelectorAll('nav.tabs button')) b.setAttribute('aria-selected', String(b.dataset.tab === name));
  for (const s of document.querySelectorAll('main section')) s.hidden = s.id !== 'tab-' + name;
  // The making tabs open with the introduction; browsing and review get straight to their lists.
  $('intro').hidden = !['score', 'instrument'].includes(name);
  if (name === 'gallery') renderGallery();
  if (name === 'mine') refreshMine();
  if (name === 'review') refreshReview();
  if (name !== 'score' && playing) stop();
  try { history.replaceState(null, '', '#' + name); } catch { }
}

// ------------------------------------------------------------------ sampled instruments

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

function playSample(inst, midi, when, dur, vel, dest, stage = 4) {
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
  if (m0.sustained && best.loopStart >= 0) { src.loop = true; src.loopStart = best.loopStart; src.loopEnd = best.loopEnd; }
  const level = vel * m0.gain * STAGE_GAIN[stage] * OUTPUT, attack = Math.max(0.002, m0.attack), release = Math.max(0.02, m0.release);
  g.gain.setValueAtTime(0, when);
  g.gain.linearRampToValueAtTime(level, when + attack);
  const off = when + Math.max(dur, attack);
  g.gain.setValueAtTime(level, off);
  g.gain.setTargetAtTime(0, off, release / 3);
  src.connect(g).connect(dest);
  src.start(when);
  src.stop(off + release * 2 + 0.05);
}

// ------------------------------------------------------------------ simple voices for the app's own instruments

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
  const sustained = (stage === 5 ? SUSTAINED_HIGH : SUSTAINED).has(id) && stage !== 3;
  let attack = 0.01, decay = null, level = vel;
  if (stage === 0) { osc('triangle', f, 0.9); osc('sine', f * 2, 0.25); out.frequency.value = 700; attack = sustained ? 0.05 : 0.005; if (!sustained) decay = 0.6; level *= 0.42; }
  else if (stage === 2) { osc('sawtooth', f, 0.5); osc('sawtooth', f * 1.004, 0.5); out.frequency.value = 1500; attack = sustained ? 0.18 : 0.01; if (!sustained) decay = 0.5; level *= 0.07; }
  else if (stage === 3) { osc('triangle', f, 1); osc('sine', f * 2, 0.3); out.frequency.value = 3500; attack = 0.003; decay = 0.32; level *= 0.2; }
  else if (stage === 4) {
    const o = osc('sawtooth', f, 0.7); osc('triangle', f, 0.6); out.frequency.value = 2400;
    const lfo = a.createOscillator(), lg = a.createGain(); lfo.frequency.value = 5.2; lg.gain.value = f * 0.004; lfo.connect(lg).connect(o.frequency); lfo.start(when + 0.2); lfo.stop(when + dur + 2);
    attack = sustained ? 0.05 : 0.005; if (!sustained) decay = 0.5; level *= 0.13;
  } else { osc('sine', f, 1); osc('sine', f * 2.76, 0.25); out.frequency.value = 7000; attack = sustained ? 0.12 : 0.002; if (!sustained) decay = 1.1; level *= 0.12; }
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
  const src = a.createBufferSource(), hp = a.createBiquadFilter(), g = a.createGain();
  src.buffer = noiseBuffer(); hp.type = 'highpass'; hp.frequency.value = 6000;
  g.gain.setValueAtTime(accent ? 0.09 * accent : 0.035, when); g.gain.setTargetAtTime(0, when, 0.025);
  src.connect(hp).connect(g).connect(dest); src.start(when, Math.random() * 0.5); src.stop(when + 0.2);
}

// ------------------------------------------------------------------ score: easy steps

let mood = 'calm', bars = 8, seed = 0, idTouched = false;
$('moods').innerHTML = Object.entries(C.MOODS).map(([k, m]) => `<button class="tile" style="--c:${m.color}" aria-pressed="${k === mood}" data-mood="${k}">
  <span class="em">${m.emoji}</span><b>${m.label}</b><small>${m.beats}/4 · ${m.mode === 'major' ? '밝은 장조' : '차분한 단조'} · ${m.bpm} BPM</small></button>`).join('');
$('moods').addEventListener('click', e => {
  const b = e.target.closest('[data-mood]'); if (!b) return;
  mood = b.dataset.mood; press($('moods'), b); compose();
});
$('lengths').addEventListener('click', e => { const b = e.target.closest('[data-bars]'); if (!b) return; bars = Number(b.dataset.bars); press($('lengths'), b); compose(); });
$('compose').addEventListener('click', () => compose());

function compose() {
  // A new song: not an arrangement of someone else's, nothing to go back to.
  forkedFrom = null; stash = null;
  if (idTouched) { idTouched = false; $('s-id').value = ''; }
  seed = (Date.now() ^ (Math.random() * 1e9)) >>> 0;
  const { score, info } = C.compose(mood, { bars, seed });
  writeScore(withNames(score));
  $('compose-info').textContent = `${C.MOODS[mood].label} · ${info.key} · ${bars}마디`;
  $('compose').textContent = '🎲 다른 곡으로 다시 만들기';
  if (playing) restartPlayback();
}

/** The easy title and creator fields fill the score's names; the id follows the title unless edited. */
function withNames(score) {
  const title = $('e-title').value.trim(), creator = $('e-creator').value.trim();
  return { ...score, title: title || score.title || '', creator: creator || score.creator || '', id: idTouched ? $('s-id').value : (title ? C.idFrom(title, 'song') : $('s-id').value || C.idFrom('', 'song')) };
}
function syncNames() {
  const title = $('e-title').value.trim(), creator = $('e-creator').value.trim();
  $('s-title').value = title; $('s-creator').value = creator;
  if (!idTouched) $('s-id').value = title ? C.idFrom(title, 'song') : ($('s-id').value || C.idFrom('', 'song'));
  onScoreInput();
}
$('e-title').addEventListener('input', debounce(syncNames, 200));
$('e-creator').addEventListener('input', debounce(syncNames, 200));
$('s-id').addEventListener('input', () => { idTouched = true; });
$('s-title').addEventListener('input', () => { $('e-title').value = $('s-title').value; });
$('s-creator').addEventListener('input', () => { $('e-creator').value = $('s-creator').value; });

// ------------------------------------------------------------------ score: my own melody

const KEYS = [['C', 'major'], ['G', 'major'], ['D', 'major'], ['F', 'major'], ['A', 'minor'], ['E', 'minor'], ['D', 'minor']];
$('m-key').innerHTML = KEYS.map(([t, m]) => `<option value="${t}|${m}">${t} ${m === 'major' ? '장조 (밝게)' : '단조 (차분하게)'}</option>`).join('');
const mel = { notes: [], history: [], pos: 0, len: 4 };
function melKey() { const [tonic, mode] = $('m-key').value.split('|'); return { tonic, mode, flats: C.usesFlats(tonic, mode) }; }
function renderMelodyKeys() {
  const { tonic, mode, flats } = melKey(), scale = C.scaleOf(tonic, mode);
  $('m-keys').innerHTML = Array.from({ length: 25 }, (_, k) => k + 60).map(m => {
    const black = [1, 3, 6, 8, 10].includes(m % 12), inScale = scale.includes(m % 12);
    return `<button class="${black ? 'black' : ''} ${inScale ? 'scale' : ''}" data-m="${m}" aria-label="${C.spell(m, flats)}">${!black ? C.spell(m, flats).replace(/\d/, '') : ''}</button>`;
  }).join('');
}
function renderTyped() {
  const { flats } = melKey();
  const beats = { 2: '♪', 4: '♩', 8: '𝅗𝅥', 16: '𝅝' };
  $('m-typed').innerHTML = mel.history.length
    ? mel.history.map(h => h.rest ? `<span class="rest">쉼 ${beats[h.len] ?? h.len}</span>` : `<span>${C.spell(h.midi, flats)} ${beats[h.len] ?? h.len}</span>`).join('')
    : '<span class="empty">건반을 누르면 여기에 음이 쌓입니다</span>';
  $('m-harmonize').disabled = !mel.notes.length;
}
$('m-key').addEventListener('change', () => { renderMelodyKeys(); renderTyped(); });
$('m-lengths').addEventListener('click', e => { const b = e.target.closest('[data-len]'); if (!b) return; mel.len = Number(b.dataset.len); press($('m-lengths'), b); });
$('m-keys').addEventListener('pointerdown', async e => {
  const m = Number(e.target.dataset.m); if (!m) return;
  e.preventDefault();
  await audio().resume(); ensureGraph();
  synth(4, 'flute', m, ctx.currentTime + 0.01, Math.min(1.2, mel.len * 0.12), 1, master);
  mel.notes.push({ start: mel.pos, length: mel.len, midi: m });
  mel.history.push({ midi: m, len: mel.len });
  mel.pos += mel.len;
  renderTyped();
});
$('m-rest').addEventListener('click', () => { mel.history.push({ rest: true, len: mel.len }); mel.pos += mel.len; renderTyped(); });
$('m-undo').addEventListener('click', () => {
  const h = mel.history.pop(); if (!h) return;
  mel.pos -= h.len; if (!h.rest) mel.notes.pop();
  renderTyped();
});
$('m-clear').addEventListener('click', () => { mel.notes = []; mel.history = []; mel.pos = 0; renderTyped(); });
$('m-listen').addEventListener('click', async () => {
  if (!mel.notes.length) return;
  await audio().resume(); ensureGraph();
  const step = 60 / 90 / 4, t0 = ctx.currentTime + 0.05;
  for (const n of mel.notes) synth(4, 'flute', n.midi, t0 + n.start * step, n.length * step * 0.95, 1, master);
});
$('m-harmonize').addEventListener('click', () => {
  const { tonic, mode } = melKey();
  const base = Object.entries(C.MOODS).find(([, m]) => m.mode === mode && m.beats === 4)?.[1] ?? C.MOODS.calm;
  const own = C.MOODS[mood].mode === mode && C.MOODS[mood].beats === 4 ? C.MOODS[mood] : base;
  const h = C.harmonize(mel.notes, { tonic, mode, beats: 4 });
  const creator = $('e-creator').value.trim();
  writeScore(withNames({
    id: '', title: '', character: `${own.label} · 내 선율`, composer: creator || '직접 작곡', creator, license: '직접 작곡 · CC BY 4.0',
    color: own.color, bpm: own.bpm, tempoRange: own.tempoRange, beatsPerBar: 4, tonic, chords: h.chords, voicings: '', melody: h.melody,
    bass: own.bass, arp: own.arp, comp: own.comp, high: own.high, canonBars: 0, defaults: own.band.slice(),
  }));
  $('compose-info').textContent = `내 선율 · ${tonic} ${mode === 'major' ? '장조' : '단조'} · ${h.bars}마디`;
  toast('화음과 반주를 붙였어요. 오른쪽에서 들어 보세요');
  if (!playing) $('play').click(); else restartPlayback();
});

// ------------------------------------------------------------------ score: fields, check

const SCORE_FIELDS = ['id', 'title', 'character', 'composer', 'creator', 'license', 'color', 'beatsPerBar', 'tonic', 'bpm', 'tempoRange', 'chords', 'melody', 'arp', 'comp', 'high', 'canonBars', 'voicings'];

function stageChoices(stage) {
  const list = C.BUILT_IN[stage].map(([id, label]) => ({ id, label }));
  if (stage !== 1) for (const [id, s] of sampled) if (s.manifest.stages.includes(stage))
    list.splice(list.length - (list.at(-1).id === 'none' ? 1 : 0), 0, { id, label: `${s.manifest.label} · ${s.source}` });
  return list;
}
const stageBox = $('stages');
function renderStages(defaults = readDefaults()) {
  stageBox.innerHTML = C.STAGES.map((name, stage) => `<div class="stage" id="stage-${stage}"><b>${name}</b>${stage ? C.ENTER[stage] + ' km/h~' : '항상'}
    <select data-stage="${stage}" aria-label="${name} 악기">${stageChoices(stage).map(o => `<option value="${esc(o.id)}">${esc(o.label)}</option>`).join('')}</select></div>`).join('');
  stageBox.querySelectorAll('select').forEach((sel, stage) => {
    const want = defaults?.[stage] ?? C.BAND_DEFAULT[stage];
    if (![...sel.options].some(o => o.value === want)) sel.add(new Option(`${want} (차에 없으면 기본)`, want));
    sel.value = want;
    sel.addEventListener('change', () => { bankBuffer(sel.value); onScoreInput(); });
  });
  updateStageLights();
  preloadBand();
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
  $('e-title').value = s.title || ''; $('e-creator').value = s.creator || '';
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
  const naming = r.errors.filter(e => ['title', 'id'].includes(e.field));
  const music = r.errors.filter(e => !['title', 'id', 'character'].includes(e.field));
  $('e-title').classList.toggle('invalid', naming.some(e => e.field === 'title') && !!piece);
  const st = $('score-status');
  if (music.length) {
    st.innerHTML = `<span class="bad">악보에 고칠 곳이 ${music.length}개 있어요 (고급 편집):</span><ul>${music.map(e => `<li class="bad">${esc(e.message)}</li>`).join('')}</ul>`;
  } else if (r.errors.length) {
    const p = C.checkScore({ ...s, id: s.id || 'x', title: s.title || 'x', character: s.character || 'x' }).piece;
    piece = p ?? piece;
    st.innerHTML = `<span class="ok">✓ 차에서 연주할 수 있는 곡이에요.</span> <span class="warn">곡 제목을 적으면 올릴 수 있어요.</span>`;
  } else {
    const p = r.piece;
    piece = p;
    const seconds = Math.round(p.lengthSteps * 60 / p.bpm / 4);
    st.innerHTML = `<span class="ok">✓ 차에서 연주할 수 있는 곡이에요.</span> ${p.lengthSteps / p.spb}마디 · 정차 중 한 바퀴 ${seconds}초, 달리면 조금 빨라져요.` +
      (r.warnings.length ? `<ul>${r.warnings.map(w => `<li class="warn">${esc(w.message)}</li>`).join('')}</ul>` : '');
  }
  if (music.length) piece = null;
  $('score-add').disabled = $('score-download').disabled = !!r.errors.length;
  updateStageLights();
  drawRoll();
}
const onScoreInputSoon = debounce(onScoreInput, 180);
for (const f of [...SCORE_FIELDS, 'bassStyle', 'bass']) $('s-' + f).addEventListener('input', onScoreInputSoon);

$('ex-largo').addEventListener('click', async () => {
  try { idTouched = true; writeScore(await (await fetch(CLOUD + 'scores/largo.json')).json()); $('compose-info').textContent = '예시 · 드보르자크 「라르고」'; }
  catch { toast('클라우드의 「라르고」를 불러오지 못했어요'); }
});
$('score-open').addEventListener('change', async e => {
  const file = e.target.files[0]; if (!file) return;
  try { const s = JSON.parse(await file.text()); if (s.type !== 'score') throw 0; idTouched = true; writeScore(s); }
  catch { toast('악보 JSON 파일이 아니에요'); }
  e.target.value = '';
});
function scoreFiles() {
  const manifest = C.scoreManifest(lastCheck.score);
  return { manifest, files: { [`scores/${manifest.id}.json`]: C.utf8(JSON.stringify(manifest, null, 1)) } };
}
$('score-download').addEventListener('click', () => { const { manifest, files } = scoreFiles(); save(manifest.id + '.json', Object.values(files)[0], 'application/json'); });
$('score-add').addEventListener('click', async () => {
  if (!PLATFORM) {
    const { manifest, files } = scoreFiles();
    session.set(manifest.id, { kind: 'score', manifest, files });
    toast(`「${manifest.title}」을 담았어요 · 📁 내 작품 탭의 오프라인 묶음`);
    updatePackButton();
    return;
  }
  await platform();
  if (!user) { openLogin('올리려면 로그인해 주세요. 이메일로 받은 링크를 누르면 돼요.'); return; }
  const button = $('score-add'); button.disabled = true;
  try {
    if (!$('e-creator').value.trim()) { $('e-creator').value = nickname(); syncNames(); }
    for (let attempt = 0; attempt < 3; attempt++) {
      const { manifest, files } = scoreFiles();
      const result = await upload('score', manifest, { [`${manifest.id}.json`]: Object.values(files)[0] }, {
        title: manifest.title, subtitle: manifest.character, creator: manifest.creator || nickname(), license: manifest.license,
        color: manifest.color, look: '', stages: [], forkedFrom });
      if (result === 'taken') { idTouched = true; $('s-id').value = freshId(manifest.id); onScoreInput(); continue; }
      if (result) { toast(`「${manifest.title}」을 올렸어요 · 운영자 확인 뒤 차에 공개돼요`); refreshMineCount(); }
      return;
    }
  } catch (e) { toast('올리지 못했어요: ' + (e.message || e)); }
  finally { button.disabled = !!lastCheck?.result.errors.length; }
});

// ------------------------------------------------------------------ piano roll

const roll = $('roll'), g2 = roll.getContext('2d');
let playStep = -1;
function drawRoll() {
  const W = roll.width, H = roll.height;
  g2.clearRect(0, 0, W, H);
  if (!piece) { g2.fillStyle = '#5f7f90'; g2.font = '24px sans-serif'; g2.fillText('곡을 만들면 여기에 악보가 그려집니다', 30, H / 2); return; }
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
    g2.fillStyle = '#7bd5ff12';
    for (const m of c.harmony) for (let k = m - 24; k <= hi; k += 12) if (k >= lo) g2.fillRect(x, y(k), c.length * sw, nh);
  }
  for (let s = 0; s <= L; s += p.spb) { g2.fillStyle = s % (p.spb * 4) ? '#1d3a4c' : '#2f5a72'; g2.fillRect(left + s * sw, top, 1, H - top); }
  const note = (n, color) => { g2.fillStyle = color; g2.fillRect(left + n.start * sw + 1, y(n.midi) + 1, Math.max(2, n.length * sw - 2), Math.max(3, nh - 2)); };
  for (const n of p.bass) note(n, '#d08a52');
  for (const n of p.melody) note(n, '#70cfff');
  if (playStep >= 0) { g2.fillStyle = '#ffffffcc'; g2.fillRect(left + (playStep % L) * sw, 0, 2, H); }
}

// ------------------------------------------------------------------ playback (PieceEngine, simplified)

const speedInput = $('speed');
let playing = false, timer = 0, nextTime = 0, stepCount = 0, master = null, stageGains = [];
const queue = [];

function ensureGraph() {
  if (master) return;
  const a = audio();
  master = a.createDynamicsCompressor(); master.threshold.value = -12; master.ratio.value = 3;
  const out = a.createGain(); out.gain.value = 0.9; master.connect(out).connect(a.destination);
  if (DEBUG) { DEBUG.analyser = a.createAnalyser(); DEBUG.analyser.fftSize = 4096; out.connect(DEBUG.analyser); }
  stageGains = C.STAGES.map((_, s) => { const g = a.createGain(); g.gain.value = s === 0 ? 1 : 0; g.connect(master); return g; });
}
function speed() { return Number(speedInput.value); }
function updateStageLights() {
  const v = speed();
  C.STAGES.forEach((_, s) => {
    const on = s === 0 || v >= C.ENTER[s];
    $('stage-' + s)?.classList.toggle('on', on);
    if (stageGains[s] && ctx && playing) stageGains[s].gain.setTargetAtTime(on ? STAGE_LEVEL[s] : 0, ctx.currentTime, 0.25);
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
  if (inst) playSample(inst, midi, when, dur, vel, dest, stage);
  else if (!playBank(id, stage, midi, when, dur, vel, dest)) synth(stage, id, midi, when, dur, vel, dest);
}
function sustainedAt(stage) {
  const id = readDefaults()?.[stage], s = sampled.get(id);
  if (s) return s.manifest.sustained;
  if (bank?.instruments[id]) return bank.instruments[id].sustained;
  return (stage === 5 ? SUSTAINED_HIGH : SUSTAINED).has(id);
}

function scheduleStep(p, s, when, stepDur) {
  const spb = p.spb, position = s % spb, span = p.chords[p.chordAt[s]];
  const accent = position === 0 ? 1 : position % 4 === 0 ? 0.86 : 0.74;
  const b = p.bassAt[s]; if (b) playStage(0, b.midi, b.length, 0.95, when, stepDur);
  const kit = readDefaults()?.[1];
  if (kit !== 'none' && !(bank?.instruments[kit] && bankBuffer(kit))) drum(position, spb, when, stageGains[1]);
  else if (kit !== 'none' && position === 0) playKit(kit, p.beats, when, 60 / stepDur / 4, stageGains[1]);
  if (sustainedAt(2)) { if (span.start === s) for (const m of span.harmony) playStage(2, m, span.length, 0.8, when, stepDur); }
  else if (p.comp[position]) { let until = 1; while (position + until < spb && !p.comp[position + until]) until++; for (const m of span.harmony) playStage(2, m, until, accent * 0.85, when, stepDur); }
  const index = p.arp[position];
  if (index >= 0) playStage(3, span.arp[index % span.arp.length], 3, accent * 0.9, when, stepDur);
  const mel = p.melodyAt[s]; if (mel) playStage(4, mel.midi, mel.length, 0.9, when, stepDur);
  if (p.high === 'BELLS') {
    if (sustainedAt(5)) { if (span.start === s) for (const m of span.harmony.slice(-2)) playStage(5, m + 12, span.length, 0.7, when, stepDur); }
    else if (s % (spb / 2) === 0 && (span.start === s || span.length > spb / 2)) playStage(5, span.harmony.at(-1) + 12, spb / 2, 0.8, when, stepDur);
  } else if (p.high === 'CANON') { const n = p.melodyAt[(((s - p.canonBars * spb) % p.lengthSteps) + p.lengthSteps) % p.lengthSteps]; if (n) playStage(5, n.midi, n.length, 0.75, when, stepDur); }
  else if (mel) playStage(5, mel.midi + 12, mel.length, 0.75, when, stepDur);
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
function restartPlayback() { stepCount = 0; queue.length = 0; nextTime = ctx.currentTime + 0.1; }
$('play').addEventListener('click', async () => {
  if (playing) { stop(); return; }
  if (!piece) { toast('먼저 곡을 만들어 주세요'); return; }
  ensureGraph(); await audio().resume();
  playing = true; restartPlayback();
  updateStageLights();
  timer = setInterval(tick, 30); tick(); requestAnimationFrame(frame);
  $('play').textContent = '■ 정지';
});
function stop() {
  playing = false; clearInterval(timer); playStep = -1; drawRoll();
  $('play').textContent = '▶ 재생';
  if (stageGains.length) { const t = ctx.currentTime; stageGains.forEach((g, s) => { g.gain.cancelScheduledValues(t); g.gain.setTargetAtTime(s === 0 ? 1 : 0, t, 0.05); }); }
}

// ------------------------------------------------------------------ instrument maker

const KINDS = {
  long: { emoji: '🎻', label: '길게 끄는 소리', hint: '바이올린 · 관악기 · 목소리', sustained: true, attack: 0.06, release: 0.3, voices: 3, gain: 2.3,
    stages: [0, 2, 4], look: 'BOWED', color: '#E3A857', min: 43, max: 81, design: 'warmpad' },
  pluck: { emoji: '🎹', label: '치면 울리는 소리', hint: '피아노 · 기타 · 종 · 칼림바', sustained: false, attack: 0.002, release: 0.8, voices: 6, gain: 2.0,
    stages: [3, 4, 5], look: 'PLUCKED', color: '#7DD8CB', min: 50, max: 88, design: 'handpan' },
  bass: { emoji: '🎸', label: '낮은 소리', hint: '베이스 · 첼로 · 낮은 목소리', sustained: true, attack: 0.04, release: 0.25, voices: 2, gain: 2.6,
    stages: [0], look: 'BOWED', color: '#D08A52', min: 28, max: 60, design: 'softpluck' },
};
const inst = { mode: 'recorded', kind: 'long', recorded: [], designed: [] };
let instIdTouched = false;
const midiOptions = (sel, value) => { sel.innerHTML = ''; for (let m = 24; m <= 108; m++) sel.add(new Option(C.noteName(m), m)); sel.value = value; };
midiOptions($('i-min'), 43); midiOptions($('i-max'), 81);
$('i-look').innerHTML = C.LOOKS.map(l => `<option>${l}</option>`).join('');
$('i-stages').innerHTML = [0, 2, 3, 4, 5].map(s => `<label><input type="checkbox" value="${s}"> ${C.STAGES[s]}</label>`).join('');
$('kinds').innerHTML = Object.entries(KINDS).map(([k, d]) => `<button class="tile" style="--c:${d.color}" aria-pressed="${k === inst.kind}" data-kind="${k}"><span class="em">${d.emoji}</span><b>${d.label}</b><small>${d.hint}</small></button>`).join('');
$('kinds').addEventListener('click', e => { const b = e.target.closest('[data-kind]'); if (!b) return; press($('kinds'), b); applyKind(b.dataset.kind); });

function applyKind(kind) {
  inst.kind = kind;
  const d = KINDS[kind];
  $('i-sustained').checked = inst.mode === 'designed' && kind === 'bass' ? false : d.sustained;
  $('i-attack').value = d.attack; $('i-release').value = d.release; $('i-voices').value = d.voices; $('i-gain').value = d.gain;
  $('i-look').value = d.look; $('i-color').value = d.color.toLowerCase(); $('i-min').value = d.min; $('i-max').value = d.max;
  for (const c of $('i-stages').querySelectorAll('input')) c.checked = d.stages.includes(Number(c.value));
  if (inst.mode === 'designed') { applyDesign(d.design); makeDesigned(); } else onInstInput();
}

for (const b of document.querySelectorAll('.seg button')) b.addEventListener('click', () => setMode(b.dataset.mode));
function setMode(mode) {
  inst.mode = mode;
  for (const b of document.querySelectorAll('.seg button')) b.setAttribute('aria-pressed', String(b.dataset.mode === mode));
  $('mode-recorded').hidden = mode !== 'recorded'; $('mode-designed').hidden = mode !== 'designed';
  $('i-license').value = mode === 'designed' ? '직접 디자인 · CC BY 4.0' : '직접 녹음 · CC BY 4.0';
  if (mode === 'designed' && !inst.designed.length) { applyDesign(KINDS[inst.kind].design); makeDesigned(); return; }
  renderNotes(); onInstInput();
}

// Recorded notes: files or the microphone.
const drop = $('drop');
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); addFiles(e.dataTransfer.files); });
$('files').addEventListener('change', e => { addFiles(e.target.files); e.target.value = ''; });

let recording = false, micCount = 0;
$('mic').addEventListener('click', async () => {
  if (recording) return;
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) { toast('이 브라우저에서는 바로 녹음을 쓸 수 없어요. 파일로 올려 주세요'); return; }
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } }); }
  catch { toast('마이크를 쓸 수 없어요. 브라우저의 마이크 권한을 확인해 주세요'); return; }
  recording = true;
  const label = $('mic-label'), button = $('mic');
  for (const n of [3, 2, 1]) { label.textContent = `${n}…`; await new Promise(r => setTimeout(r, 600)); }
  const chunks = [], rec = new MediaRecorder(stream);
  rec.ondataavailable = e => chunks.push(e.data);
  const done = new Promise(r => { rec.onstop = r; });
  button.classList.add('on'); label.textContent = '녹음 중… 한 음을 길게';
  rec.start(); await new Promise(r => setTimeout(r, 3000)); rec.stop(); await done;
  stream.getTracks().forEach(t => t.stop());
  button.classList.remove('on'); label.textContent = '바로 녹음 (한 음, 3초)'; recording = false;
  micCount++;
  await addFiles([new File(chunks, `마이크 녹음 ${micCount}`, { type: rec.mimeType })]);
});

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
      if (C.peak(x) < 0.01) throw new Error('quiet');
      const named = C.noteFromName(file.name), heard = C.detectPitch(x);
      let root = heard ?? (named != null ? C.hz(named) : null), warn = '';
      if (named != null && heard) {
        const diff = 12 * Math.log2(heard / C.hz(named));
        if (Math.abs(diff) > 0.6) warn = `파일 이름은 ${C.noteName(named)}, 들리는 음은 ${C.noteName(Math.round(69 + 12 * Math.log2(heard / 440)))}`;
      }
      if (!root) warn = '음 높이를 못 찾았어요: 오른쪽에서 골라 주세요';
      inst.recorded.push({ name: file.name, root: root ?? C.hz(60), x, warn });
    } catch (e) {
      toast(e.message === 'quiet' ? `「${file.name}」이 너무 조용해요. 조금 더 크게 녹음해 주세요` : `「${file.name}」을 읽을 수 없어요`);
    }
  }
  inst.recorded.sort((a, b) => a.root - b.root);
  fitRange();
  renderNotes(); onInstInput();
}
/** The playable range follows the recorded notes (a fifth beyond the outer ones). */
function fitRange() {
  if (!inst.recorded.length) return;
  const ms = inst.recorded.map(n => 69 + 12 * Math.log2(n.root / 440));
  $('i-min').value = Math.max(24, Math.round(Math.min(...ms)) - 7);
  $('i-max').value = Math.min(108, Math.round(Math.max(...ms)) + 7);
}

// Designed notes.
$('d-preset').innerHTML = Object.entries(C.DESIGNS).map(([k, d]) => `<option value="${k}">${d.label}</option>`).join('');
let design = null;
function applyDesign(key) {
  design = structuredClone(C.DESIGNS[key]);
  design.sustained = $('i-sustained').checked;
  $('d-preset').value = key; $('d-attack').value = design.attack; $('d-thump').value = design.thump; $('d-shimmer').value = design.shimmer;
  $('partials').innerHTML = '<span></span><span>배음 (배)</span><span>크기</span><span>울림 (초)</span>' + design.partials.map((p, i) => `
    <span>${i + 1}</span><input type="number" step="0.01" min="0.5" max="12" value="${p.ratio}" data-p="${i}" data-k="ratio">
    <input type="range" min="0" max="1" step="0.01" value="${p.level}" data-p="${i}" data-k="level">
    <input type="range" min="0.05" max="5" step="0.05" value="${p.decay}" data-p="${i}" data-k="decay" ${design.sustained ? 'disabled' : ''}>`).join('');
}
$('d-preset').addEventListener('change', e => { const s = C.DESIGNS[e.target.value].sustained; $('i-sustained').checked = s; applyDesign(e.target.value); makeDesigned(); });
$('partials').addEventListener('input', e => { const t = e.target; if (t.dataset.p) design.partials[t.dataset.p][t.dataset.k] = Number(t.value); makeDesignedSoon(); });
for (const k of ['attack', 'thump', 'shimmer']) $('d-' + k).addEventListener('input', e => { design[k] = Number(e.target.value); makeDesignedSoon(); });
$('d-make').addEventListener('click', makeDesigned);
function makeDesigned() {
  design.sustained = $('i-sustained').checked;
  const lo = Number($('i-min').value), hi = Number($('i-max').value);
  inst.designed = [];
  for (let m = lo; m <= hi; m += 4) inst.designed.push({ name: `${C.noteName(m)} · ${design.label}`, root: C.hz(m), x: C.designNote(C.hz(m), design), warn: '' });
  renderNotes(); onInstInput();
}
const makeDesignedSoon = debounce(makeDesigned, 350);

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
    return `<div class="note"><span class="name" title="${esc(n.name)}">${esc(n.name)}${n.warn ? `<br><span class="warn">${esc(n.warn)}</span>` : ''}</span>
      <select data-i="${i}" aria-label="음 높이" ${inst.mode === 'designed' ? 'disabled' : ''}>${opts}</select>
      <span class="len info">${(n.x.length / C.RATE).toFixed(1)}초</span>
      <button class="btn mini" data-play="${i}" aria-label="들어 보기">▶</button>${inst.mode === 'recorded' ? `<button class="btn mini del" data-del="${i}" aria-label="빼기">✕</button>` : ''}</div>`;
  }).join('');
}
$('note-list').addEventListener('change', e => { const i = e.target.dataset.i; if (i != null) { inst.recorded[i].root = C.hz(Number(e.target.value)); inst.recorded[i].warn = ''; fitRange(); onInstInput(); } });
$('note-list').addEventListener('click', async e => {
  const t = e.target.closest('button'); if (!t) return;
  if (t.dataset.del != null) { inst.recorded.splice(Number(t.dataset.del), 1); renderNotes(); onInstInput(); }
  if (t.dataset.play != null) {
    const n = currentNotes()[Number(t.dataset.play)]; if (!n?.x.length) return;
    await audio().resume();
    const lv = C.level(n.x), b = ctx.createBuffer(1, lv.length, C.RATE); b.getChannelData(0).set(lv);
    const s = ctx.createBufferSource(), g = ctx.createGain(); g.gain.value = 0.35; s.buffer = b; s.connect(g).connect(ctx.destination); s.start();
  }
});

function readInstMeta() {
  const label = $('i-label').value.trim();
  if (!instIdTouched) $('i-id').value = label ? C.idFrom(label, 'inst') : ($('i-id').value || C.idFrom('', 'inst'));
  return {
    id: $('i-id').value.trim(), label, sound: inst.mode, creator: $('i-creator').value.trim(), license: $('i-license').value,
    stages: [...$('i-stages').querySelectorAll('input:checked')].map(c => Number(c.value)),
    sustained: $('i-sustained').checked, voices: Math.max(1, Math.min(8, Number($('i-voices').value) || 4)), mono: $('i-mono').checked,
    minMidi: Number($('i-min').value), maxMidi: Number($('i-max').value),
    gain: Number($('i-gain').value), attack: Number($('i-attack').value), release: Number($('i-release').value), look: $('i-look').value, color: $('i-color').value.toUpperCase(),
  };
}
let built = null, preview = null;
function onInstInput() {
  const meta = readInstMeta(), notes = finalNotes();
  const r = C.checkInstrument(meta, notes);
  for (const n of currentNotes()) if (n.warn) r.warnings.push({ field: 'notes', message: `${n.name}: ${n.warn}` });
  document.querySelectorAll('#tab-instrument .invalid').forEach(e => e.classList.remove('invalid'));
  built = null; preview = null;
  const st = $('inst-status');
  const sound = r.errors.filter(e => e.field !== 'label' && e.field !== 'id');
  if (!notes.length) st.innerHTML = '소리를 넣으면 차의 악단에 넣을 수 있는지 확인합니다.';
  else if (sound.length) st.innerHTML = `<span class="bad">고칠 곳이 있어요:</span><ul>${sound.map(e => `<li class="bad">${esc(e.message)}</li>`).join('')}</ul>`;
  if (!sound.length && notes.length) {
    const probe = C.buildInstrument({ ...meta, id: meta.id || 'preview', label: meta.label || '미리 듣기' }, notes);
    preview = makeSampled(probe.manifest, pcmToFloat(probe.pcm), '만드는 중');
    if (!r.errors.length) built = probe;
    const loops = notes.filter(n => n.loop).length;
    st.innerHTML = `<span class="ok">✓ 차의 악단에 넣을 수 있는 악기예요.</span> ${notes.length}음${meta.sustained ? ` · 이어지는 소리 ${loops}/${notes.length}` : ''} · ${meta.stages.map(s => C.STAGES[s]).join('·')} 단계` +
      (r.errors.length ? ` <span class="warn">악기 이름을 적으면 올릴 수 있어요.</span>` : '') +
      (r.warnings.length ? `<ul>${r.warnings.map(w => `<li class="warn">${esc(w.message)}</li>`).join('')}</ul>` : '');
  }
  $('i-label').classList.toggle('invalid', notes.length > 0 && !meta.label);
  $('inst-add').disabled = $('inst-download').disabled = !built;
  renderKeys();
}
const onInstInputSoon = debounce(onInstInput, 250);
for (const id of ['i-label', 'i-creator', 'i-license', 'i-look', 'i-color', 'i-voices', 'i-gain', 'i-attack', 'i-release', 'i-mono', 'i-sustained', 'i-min', 'i-max']) $(id).addEventListener('input', onInstInputSoon);
$('i-id').addEventListener('input', () => { instIdTouched = true; onInstInputSoon(); });
$('i-stages').addEventListener('change', onInstInputSoon);
$('i-sustained').addEventListener('change', () => { if (inst.mode === 'designed' && design) { applyDesign($('d-preset').value); makeDesigned(); } });

function renderKeys() {
  const lo = Number($('i-min').value), hi = Number($('i-max').value);
  $('keys').innerHTML = Array.from({ length: 37 }, (_, k) => k + 48).map(m => {
    const black = [1, 3, 6, 8, 10].includes(m % 12);
    return `<button class="${black ? 'black' : ''} ${m < lo || m > hi ? 'out' : ''}" data-m="${m}" aria-label="${C.noteName(m)}">${m % 12 === 0 ? C.noteName(m) : ''}</button>`;
  }).join('');
}
$('keys').addEventListener('pointerdown', async e => {
  const m = Number(e.target.dataset.m); if (!m) return;
  e.preventDefault();
  if (!preview) { toast('먼저 소리를 넣어 주세요'); return; }
  await audio().resume(); ensureGraph();
  playSample(preview, m, ctx.currentTime + 0.01, preview.manifest.sustained ? 0.9 : 0.4, 1, master, 4);
});
$('inst-download').addEventListener('click', () => { if (!built) return; save(built.manifest.id + '.json', C.utf8(built.json), 'application/json'); setTimeout(() => save(built.manifest.pcm, built.pcm), 400); });
$('inst-add').addEventListener('click', async () => {
  if (!built) return;
  if (PLATFORM) { await uploadInstrument(); return; }
  const m = built.manifest;
  session.set(m.id, { kind: 'instrument', manifest: m, files: { [`instruments/${m.id}.json`]: C.utf8(built.json), [`instruments/${m.pcm}`]: built.pcm } });
  registerSampled(m, pcmToFloat(built.pcm), '내가 만듦');
  renderStages(readDefaults());
  toast(`「${m.label}」을 담았어요 · 📁 내 작품 탭의 오프라인 묶음`);
  updatePackButton();
});
async function uploadInstrument() {
  await platform();
  if (!user) { openLogin('올리려면 로그인해 주세요. 이메일로 받은 링크를 누르면 돼요.'); return; }
  const button = $('inst-add'); button.disabled = true;
  try {
    if (!$('i-creator').value.trim()) { $('i-creator').value = nickname(); onInstInput(); }
    for (let attempt = 0; attempt < 3 && built; attempt++) {
      const m = built.manifest;
      const result = await upload('instrument', m, { [`${m.id}.json`]: C.utf8(built.json), [m.pcm]: built.pcm }, {
        title: m.label, subtitle: m.sound === 'recorded' ? '녹음한 악기' : '디자인한 음색', creator: m.creator || nickname(),
        license: m.license, color: m.color, look: m.look, stages: m.stages, forkedFrom: null });
      if (result === 'taken') { instIdTouched = true; $('i-id').value = freshId(m.id); onInstInput(); continue; }
      if (result) {
        registerSampled(m, pcmToFloat(built.pcm), '내가 만듦');
        renderStages(readDefaults());
        toast(`「${m.label}」을 올렸어요 · 운영자 확인 뒤 차에 공개돼요`);
        refreshMineCount();
      }
      return;
    }
  } catch (e) { toast('올리지 못했어요: ' + (e.message || e)); }
  finally { button.disabled = !built; }
}

// ------------------------------------------------------------------ send to the car

function updatePackButton() {
  $('pack-zip').disabled = !session.size;
  $('pack-count').hidden = !session.size; $('pack-count').textContent = session.size;
}
function sizesOf(files) { return Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.length])); }
function renderPack() {
  const newItems = [...session.values()].map(s => ({ ...C.catalogItem(s.kind, s.manifest, sizesOf(s.files)), fresh: true }));
  const ids = new Set(newItems.map(i => i.id));
  const items = [...newItems, ...cloudCatalog.items.filter(i => !ids.has(i.id))];
  $('pack').innerHTML = items.map(i => `<div class="item" style="--c:${esc(i.color)}">${i.fresh ? '<div class="new">NEW · 이번에 담음</div>' : ''}
    <b>${esc(i.title)}</b> <span class="meta">${i.type === 'score' ? '곡' : '악기'}</span><br>${esc(i.subtitle)}<br>
    <span class="meta">${esc(i.creator || '')} · ${esc(i.license || '')} · ${(i.bytes / 1024).toFixed(i.bytes > 1e5 ? 0 : 1)} KB${i.stages?.length ? ' · ' + i.stages.map(s => C.STAGES[s]).join('·') : ''}</span>
    ${i.fresh ? `<div class="btns" style="margin-top:8px"><button class="btn mini" data-remove="${esc(i.id)}">빼기</button></div>` : ''}</div>`).join('') || '<p class="hint">클라우드 목록을 불러오는 중…</p>';
  updatePackButton();
}
$('pack').addEventListener('click', e => { const id = e.target.dataset.remove; if (id) { session.delete(id); renderPack(); } });
$('pack-zip').addEventListener('click', () => {
  const files = {}, newItems = [];
  for (const s of session.values()) { Object.assign(files, s.files); newItems.push(C.catalogItem(s.kind, s.manifest, sizesOf(s.files))); }
  const ids = new Set(newItems.map(i => i.id));
  const catalog = { format: 1, name: cloudCatalog.name, items: [...cloudCatalog.items.filter(i => !ids.has(i.id)), ...newItems] };
  files['catalog.json'] = C.utf8(JSON.stringify(catalog, null, 1));
  save('caestro-cloud-pack.zip', C.zip(files), 'application/zip');
  $('pack-status').innerHTML = `<span class="ok">✓ 묶음을 받았어요:</span> 새 항목 ${newItems.length}개와 갱신된 목록. 클라우드에 올리면 차의 클라우드 탭에 나타납니다.`;
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
  } catch { /* the studio works without the example cloud (e.g. opened from disk) */ }
  renderPack();
}

// ------------------------------------------------------------------ platform (Supabase): accounts, uploads, gallery, review

const PLATFORM = !!(SUPABASE_URL && SUPABASE_KEY);
const FILES = PLATFORM ? SUPABASE_URL.replace(/\/$/, '') + '/storage/v1/object/public/packs/' : '';
const STATUS = { pending: '검토 대기', approved: '공개 중', rejected: '반려' };
let sb = null, user = null, profile = null, forkedFrom = null, stash = null;
let gKind = '', gSort = 'likes', myLikes = new Set();
const shown = new Map();

async function platform() {
  if (!PLATFORM) return null;
  if (sb) return sb;
  const { createClient } = await import('https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.4/+esm');
  sb = createClient(SUPABASE_URL, SUPABASE_KEY);
  const { data } = await sb.auth.getSession();
  user = data.session?.user ?? null;
  sb.auth.onAuthStateChange((_event, session) => {
    const before = user?.id;
    user = session?.user ?? null;
    if (before !== user?.id) loadProfile();
  });
  await loadProfile();
  return sb;
}
async function loadProfile() {
  profile = null;
  if (user) {
    const { data } = await sb.from('profiles').select('nickname,is_admin').eq('id', user.id).maybeSingle();
    profile = data ?? { nickname: '', is_admin: false };
    if (!profile.nickname) setTimeout(() => openLogin(), 300); // first sign-in: pick a nickname
  }
  renderAccount();
  refreshMineCount();
}
function nickname() { return profile?.nickname?.trim() || '익명'; }
function freshId(id) { return id.replace(/-[a-z0-9]{4}$/, '').slice(0, 34) + '-' + Math.random().toString(36).slice(2, 6); }
function renderAccount() {
  $('account').hidden = !PLATFORM;
  $('account').textContent = user ? `👤 ${nickname()}` : '로그인';
  $('review-tab').hidden = !profile?.is_admin;
}
function openLogin(why) {
  $('login-out').hidden = !!user; $('login-in').hidden = !user;
  $('login-title').textContent = user ? '내 계정' : '로그인';
  if (why) $('login-why').textContent = why;
  $('login-nick').value = profile?.nickname ?? '';
  $('login-status').hidden = true;
  if (!$('login').open) $('login').showModal();
}
$('account').addEventListener('click', async () => { await platform(); openLogin(); });
$('login-form').addEventListener('submit', async e => {
  const action = e.submitter?.value;
  if (action === 'cancel') return;
  e.preventDefault();
  const st = $('login-status'); st.hidden = false; st.textContent = '…';
  const c = await platform();
  if (action === 'send') {
    const email = $('login-email').value.trim();
    if (!/^\S+@\S+\.\S+$/.test(email)) { st.innerHTML = '<span class="bad">이메일 주소를 확인해 주세요</span>'; return; }
    const { error } = await c.auth.signInWithOtp({ email, options: { emailRedirectTo: location.origin + location.pathname } });
    st.innerHTML = error ? `<span class="bad">보내지 못했어요: ${esc(error.message)}</span>`
      : '<span class="ok">✓ 메일을 보냈어요. 메일의 링크를 누르면 이 페이지로 돌아와 로그인돼요.</span>';
  } else if (action === 'save') {
    const nick = $('login-nick').value.trim().slice(0, 30);
    if (!nick) { st.innerHTML = '<span class="bad">닉네임을 적어 주세요</span>'; return; }
    const { error } = await c.from('profiles').update({ nickname: nick }).eq('id', user.id);
    if (error) { st.innerHTML = `<span class="bad">${esc(error.message)}</span>`; return; }
    profile = { ...profile, nickname: nick };
    renderAccount(); $('login').close(); toast('닉네임을 저장했어요');
  } else if (action === 'logout') {
    await c.auth.signOut(); user = null; profile = null;
    renderAccount(); $('login').close(); toast('로그아웃했어요'); refreshMineCount();
  }
});

/** Uploads a work (its files to the bucket, its row to works). 'taken' when another account owns the id. */
async function upload(kind, manifest, files, info) {
  const c = await platform();
  const { data: same } = await c.from('works').select('id,owner').eq('id', manifest.id).maybeSingle();
  if (same && same.owner !== user.id) return 'taken';
  const paths = [];
  for (const [name, bytes] of Object.entries(files)) {
    const path = `${user.id}/${manifest.id}/${name}`;
    const type = name.endsWith('.json') ? 'application/json' : 'application/octet-stream';
    const { error } = await c.storage.from('packs').upload(path, new Blob([bytes], { type }), { upsert: true, cacheControl: '60', contentType: type });
    if (error) throw error;
    paths.push(path);
  }
  const row = { id: manifest.id, kind, title: info.title.slice(0, 60), subtitle: (info.subtitle || '').slice(0, 120), creator: (info.creator || '').slice(0, 30),
    license: (info.license || '').slice(0, 60), color: /^#[0-9a-f]{6}$/i.test(info.color) ? info.color : '#9FB4C2', look: info.look || '', stages: info.stages || [],
    manifest, files: paths, bytes: Object.values(files).reduce((sum, b) => sum + b.length, 0), forked_from: info.forkedFrom || null };
  const { error } = same ? await c.from('works').update(row).eq('id', manifest.id) : await c.from('works').insert(row);
  if (error) { if (error.code === '23505') return 'taken'; throw error; }
  return true;
}

function card(w, where) {
  shown.set(w.id, w);
  const acts = [`<button class="btn mini" data-act="listen" data-id="${esc(w.id)}">▶ 들어 보기</button>`];
  if (where === 'gallery') {
    acts.push(`<button class="btn mini like" aria-pressed="${myLikes.has(w.id)}" data-act="like" data-id="${esc(w.id)}">♥ ${w.likes}</button>`);
    if (w.kind === 'score') acts.push(`<button class="btn mini" data-act="fork" data-id="${esc(w.id)}">가져와 고치기</button>`);
  }
  if (where === 'mine') {
    if (w.kind === 'score') acts.push(`<button class="btn mini" data-act="edit" data-id="${esc(w.id)}">고치기</button>`);
    acts.push(`<button class="btn mini" data-act="delete" data-id="${esc(w.id)}">삭제</button>`);
  }
  if (where === 'review') {
    if (w.status !== 'approved') acts.push(`<button class="btn mini primary" data-act="approve" data-id="${esc(w.id)}">승인</button>`);
    acts.push(`<button class="btn mini" data-act="reject" data-id="${esc(w.id)}">${w.status === 'approved' ? '내리기' : '반려'}</button>`);
  }
  const tag = where === 'gallery' ? '' : `<span class="tag ${w.status}">${STATUS[w.status]}</span> `;
  const stages = w.stages?.length ? ' · ' + w.stages.map(s => C.STAGES[s]).join('·') : '';
  return `<div class="item" style="--c:${esc(w.color)}">${tag}<b>${esc(w.title)}</b> <span class="meta">${w.kind === 'score' ? '곡' : '악기'}</span><br>${esc(w.subtitle)}<br>
    <span class="meta">${esc(w.creator || '익명')}${w.forked_from ? ` · 원작 ${esc(w.forked_from)}` : ''} · ${esc(w.license)}${stages}${where === 'gallery' ? '' : ` · ♥ ${w.likes}`}</span>
    ${w.review_note ? `<br><span class="warn">${esc(w.review_note)}</span>` : ''}<div class="acts">${acts.join('')}</div></div>`;
}
const COLUMNS = 'id,owner,kind,title,subtitle,creator,license,color,look,stages,manifest,files,bytes,forked_from,status,review_note,likes,created_at,updated_at';

async function renderGallery() {
  const box = $('gallery'), st = $('gallery-status');
  if (!PLATFORM) {
    st.hidden = false; st.textContent = '플랫폼 서버를 준비하고 있어요. 지금은 예시 클라우드만 보여요.';
    box.innerHTML = cloudCatalog.items.map(i => `<div class="item" style="--c:${esc(i.color)}"><b>${esc(i.title)}</b> <span class="meta">${i.type === 'score' ? '곡' : '악기'}</span><br>${esc(i.subtitle)}<br><span class="meta">${esc(i.creator)} · ${esc(i.license)}</span></div>`).join('');
    return;
  }
  st.hidden = true;
  box.innerHTML = '<p class="hint">불러오는 중…</p>';
  try {
    const c = await platform();
    let q = c.from('works').select(COLUMNS).eq('status', 'approved');
    if (gKind) q = q.eq('kind', gKind);
    q = gSort === 'likes' ? q.order('likes', { ascending: false }).order('updated_at', { ascending: false }) : q.order('created_at', { ascending: false });
    const { data, error } = await q.limit(60);
    if (error) throw error;
    myLikes = new Set();
    if (user) {
      const { data: liked } = await c.from('likes').select('work_id').eq('user_id', user.id);
      myLikes = new Set((liked ?? []).map(l => l.work_id));
    }
    box.innerHTML = data.map(w => card(w, 'gallery')).join('') || '<p class="hint">아직 공개된 작품이 없어요. 첫 작품을 올려 보세요!</p>';
  } catch (e) { box.innerHTML = ''; st.hidden = false; st.innerHTML = `<span class="bad">불러오지 못했어요: ${esc(e.message || e)}</span>`; }
}
$('g-filter').addEventListener('click', e => {
  const b = e.target.closest('button'); if (!b) return;
  if (b.dataset.kind != null) { gKind = b.dataset.kind; for (const x of $('g-filter').querySelectorAll('[data-kind]')) x.setAttribute('aria-pressed', String(x === b)); }
  if (b.dataset.sort) { gSort = b.dataset.sort; for (const x of $('g-filter').querySelectorAll('[data-sort]')) x.setAttribute('aria-pressed', String(x === b)); }
  renderGallery();
});

async function refreshMine() {
  renderPack();
  const box = $('mine'), st = $('mine-status');
  if (!PLATFORM) { box.innerHTML = '<p class="hint">플랫폼 서버를 준비하고 있어요. 지금은 아래 오프라인 묶음으로 담을 수 있어요.</p>'; return; }
  await platform();
  if (!user) { box.innerHTML = '<p class="hint">로그인하면 내가 올린 작품이 여기 모여요.</p>'; return; }
  try {
    const { data, error } = await sb.from('works').select(COLUMNS).eq('owner', user.id).order('updated_at', { ascending: false });
    if (error) throw error;
    st.hidden = true;
    box.innerHTML = data.map(w => card(w, 'mine')).join('') || '<p class="hint">아직 올린 작품이 없어요. 곡이나 악기를 만들어 “올리기”를 눌러 보세요.</p>';
    showCount(data.length);
  } catch (e) { st.hidden = false; st.innerHTML = `<span class="bad">불러오지 못했어요: ${esc(e.message || e)}</span>`; }
}
async function refreshMineCount() {
  if (!PLATFORM || !sb) return;
  if (!user) { showCount(session.size); return; }
  const { count } = await sb.from('works').select('id', { count: 'exact', head: true }).eq('owner', user.id);
  showCount(count ?? 0);
}
function showCount(n) { $('pack-count').hidden = !n; $('pack-count').textContent = n; }

async function refreshReview() {
  if (!profile?.is_admin) return;
  const st = $('review-status');
  try {
    const { data, error } = await sb.from('works').select(COLUMNS).order('created_at', { ascending: true });
    if (error) throw error;
    $('review-pending').innerHTML = data.filter(w => w.status !== 'approved').map(w => card(w, 'review')).join('') || '<p class="hint">검토할 작품이 없어요.</p>';
    $('review-approved').innerHTML = data.filter(w => w.status === 'approved').map(w => card(w, 'review')).join('') || '<p class="hint">공개 중인 작품이 없어요.</p>';
    st.hidden = true;
  } catch (e) { st.hidden = false; st.innerHTML = `<span class="bad">${esc(e.message || e)}</span>`; }
}

/** Opens a work from the gallery, my works or review: a score in the editor, an instrument in the band. */
async function listen(w, mode) {
  if (w.kind === 'instrument') {
    try {
      const pcmPath = w.files.find(f => f.endsWith('.pcm'));
      const bytes = new Uint8Array(await (await fetch(/^https?:/.test(pcmPath) ? pcmPath : FILES + pcmPath)).arrayBuffer());
      const manifest = { ...w.manifest, id: w.id };
      registerSampled(manifest, pcmToFloat(bytes), w.creator || '둘러보기');
      renderStages(readDefaults());
      // A few notes so it is heard at once.
      await audio().resume(); ensureGraph();
      const s = sampled.get(w.id), t = ctx.currentTime + 0.05, low = Math.max(manifest.minMidi, 55);
      [0, 4, 7, 12].forEach((d, k) => playSample(s, low + d, t + k * 0.35, manifest.sustained ? 0.5 : 0.3, 1, master, 4));
      toast(`「${w.title}」을 악단에 넣었어요 · 곡 만들기의 악단 칸에서 골라 들어 보세요`);
    } catch (e) { toast('악기를 불러오지 못했어요: ' + (e.message || e)); }
    return;
  }
  if (!stash && mode !== 'edit') stash = { score: readScore(), info: $('compose-info').textContent, forkedFrom };
  let score = { ...w.manifest };
  if (mode === 'fork') {
    const title = (w.title + ' (편곡)').slice(0, 40);
    score = { ...score, id: freshId(C.idFrom(w.title, 'song')), title, creator: nickname(),
      character: `원작 ${w.creator || '익명'} · ${w.subtitle}`.slice(0, 120) };
    forkedFrom = w.id;
  } else forkedFrom = mode === 'edit' ? w.forked_from : null;
  idTouched = true;
  writeScore(score);
  showTab('score');
  const label = { fork: '가져와 고치는 중', edit: '내 작품 고치는 중', listen: '둘러보기' }[mode];
  $('compose-info').innerHTML = `${label} · 「${esc(w.title)}」 · ${esc(w.creator || '익명')}` + (stash ? ' <button class="btn mini" id="restore">↩ 내 곡으로</button>' : '');
  if (mode === 'fork') toast('가져왔어요 · 고친 뒤 올리면 원작자가 함께 표시돼요');
  // On a phone the player sits below the steps: bring it into view.
  if (innerWidth < 900) $('roll').closest('.card').scrollIntoView({ behavior: 'smooth', block: 'start' });
  if (!playing) $('play').click(); else restartPlayback();
}
document.addEventListener('click', e => {
  if (e.target.id === 'restore' && stash) {
    writeScore(stash.score); forkedFrom = stash.forkedFrom; $('compose-info').textContent = stash.info; stash = null;
  }
});

for (const box of ['gallery', 'mine', 'review-pending', 'review-approved']) $(box).addEventListener('click', async e => {
  const b = e.target.closest('button[data-act]'); if (!b) return;
  const w = shown.get(b.dataset.id); if (!w) return;
  const act = b.dataset.act;
  if (act === 'listen') return listen(w, 'listen');
  if (act === 'fork') return listen(w, 'fork');
  if (act === 'edit') return listen(w, 'edit');
  const c = await platform();
  try {
    if (act === 'like') {
      if (!user) { openLogin('좋아요를 누르려면 로그인해 주세요.'); return; }
      const liked = myLikes.has(w.id);
      const { error } = liked ? await c.from('likes').delete().eq('user_id', user.id).eq('work_id', w.id)
        : await c.from('likes').insert({ work_id: w.id });
      if (error) throw error;
      liked ? myLikes.delete(w.id) : myLikes.add(w.id);
      w.likes += liked ? -1 : 1;
      b.setAttribute('aria-pressed', String(!liked)); b.textContent = `♥ ${w.likes}`;
    } else if (act === 'delete') {
      if (!confirm(`「${w.title}」을 지울까요? 차의 목록에서도 사라져요.`)) return;
      await c.storage.from('packs').remove(w.files.filter(f => !/^https?:/.test(f)));
      const { error } = await c.from('works').delete().eq('id', w.id);
      if (error) throw error;
      toast('지웠어요'); refreshMine();
    } else if (act === 'approve') {
      const problems = w.kind === 'score' ? C.checkScore({ ...w.manifest }).errors : [];
      if (problems.length) { toast('차가 받지 않는 악보예요: ' + problems[0].message); return; }
      const { error } = await c.from('works').update({ status: 'approved', review_note: '' }).eq('id', w.id);
      if (error) throw error;
      toast(`「${w.title}」을 공개했어요`); refreshReview();
    } else if (act === 'reject') {
      const note = prompt(w.status === 'approved' ? '내리는 이유 (만든 사람에게 보여요)' : '반려 이유 (만든 사람에게 보여요)', '');
      if (note == null) return;
      const { error } = await c.from('works').update({ status: 'rejected', review_note: note.slice(0, 200) }).eq('id', w.id);
      if (error) throw error;
      toast('반려했어요'); refreshReview();
    }
  } catch (err) { toast('하지 못했어요: ' + (err.message || err)); }
});

/** Admin: the example cloud (Largo, trombone, handpan) goes up as approved works. */
$('seed').addEventListener('click', async () => {
  const st = $('review-status'); st.hidden = false; st.textContent = '올리는 중…';
  try {
    for (const item of cloudCatalog.items) {
      const files = {}, manifest = await (await fetch(CLOUD + item.files[0])).json();
      for (const f of item.files) files[f.split('/').pop()] = new Uint8Array(await (await fetch(CLOUD + f)).arrayBuffer());
      const result = await upload(item.type, manifest, files, { title: item.title, subtitle: item.subtitle, creator: item.creator,
        license: item.license, color: item.color, look: item.look, stages: item.stages, forkedFrom: null });
      if (result === 'taken') continue;
      await sb.from('works').update({ status: 'approved' }).eq('id', item.id);
    }
    st.innerHTML = '<span class="ok">✓ 예시를 올렸어요.</span>';
    refreshReview();
  } catch (e) { st.innerHTML = `<span class="bad">${esc(e.message || e)}</span>`; }
});

renderMelodyKeys(); renderTyped();
compose();
applyKind('long');
renderKeys();
// ?debug exposes the bank and the output analyser to automated checks.
const DEBUG = new URLSearchParams(location.search).has('debug') ? (window.__studio = { bankBuffer, playBank, get bank() { return bank; }, get stageGains() { return stageGains; },
  signIn: async (email, password) => (await platform()).auth.signInWithPassword({ email, password }) }) : null;
loadBank();
loadCloud();
const start = location.hash.slice(1);
if (['score', 'instrument', 'gallery', 'mine', 'review'].includes(start)) showTab(start);
renderAccount();
if (PLATFORM) platform().catch(() => {}); // restores the session (also right after a sign-in link)
