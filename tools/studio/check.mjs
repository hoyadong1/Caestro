// Checks docs/studio/core.js against the example cloud and writes a studio-made pack for the app's test:
//   node tools/studio/check.mjs            -> app/build/studio-pack/ (read by StudioPackTest)
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as core from '../../docs/studio/core.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const assert = (ok, what) => { if (!ok) { console.error('FAIL', what); process.exit(1); } };

// 1. The example score passes, and a manifest written back from it is the same file.
const largo = JSON.parse(readFileSync(join(root, 'docs/cloud/scores/largo.json'), 'utf8'));
const r = core.checkScore(largo);
assert(!r.errors.length, 'largo: ' + JSON.stringify(r.errors));
assert(r.piece.lengthSteps === 128 && r.piece.melody.length === 21, 'largo shape ' + r.piece.lengthSteps + ' ' + r.piece.melody.length);
assert(JSON.stringify(core.scoreManifest(largo)) === JSON.stringify(largo), 'largo round trip');

// 2. The app's own errors are caught.
const bad = (patch, field) => { const e = core.checkScore({ ...largo, ...patch }).errors; assert(e.some(x => x.field === field), `${field} not caught: ${JSON.stringify(patch)}`); };
bad({ chords: 'C:12 | G:4' }, 'chords');
bad({ melody: 'E5:6 G5:2 G5:4' }, 'melody');
bad({ melody: largo.melody + ' | C5:16' }, 'melody');
bad({ arp: '0 . 2 .' }, 'arp');
bad({ comp: 'x . . . . . . . x . . . . . . o' }, 'comp');
bad({ beatsPerBar: 2 }, 'beatsPerBar');
bad({ id: 'Largo!' }, 'id');
bad({ chords: 'H:16' }, 'chords');
bad({ high: 'CANON', canonBars: 0 }, 'canonBars');
bad({ bass: 'C2:8 G2:4' }, 'bass');
assert(!core.checkScore({ ...largo, beatsPerBar: 3, chords: 'C:12 | G:12', melody: 'E5:4 D5 C5 | D5:12', arp: '0 . 1 . 2 . 1 . 2 . 1 .', comp: 'x . . . x . . . x . . .', bass: 'C3:12 | G2:12' }).errors.length, 'waltz');

// 2b. The automatic composer always writes a score the car accepts, in range, ending on the tonic.
let composed = 0;
for (const mood of Object.keys(core.MOODS)) for (const bars of [8, 16]) for (let seed = 1; seed <= 40; seed++) {
  const { score } = core.compose(mood, { bars, seed });
  const c = core.checkScore({ ...score, id: 'auto', title: 'Auto' });
  assert(!c.errors.length, `${mood} ${bars} ${seed}: ${JSON.stringify(c.errors)}\n${score.melody}`);
  const p = c.piece;
  assert(p.lengthSteps === bars * p.spb, `${mood} length`);
  assert(p.melody.every(n => n.midi >= 55 && n.midi <= 96), `${mood} range`);
  const last = p.melody.at(-1);
  assert(((last.midi - p.tonic) % 12 + 12) % 12 === 0 && last.start === (bars - 1) * p.spb, `${mood} ${seed} ends on the tonic`);
  // Strong beats sit on chord tones.
  const strong = p.melody.filter(n => n.start % p.spb === 0);
  const fit = strong.filter(n => { const ch = p.chords[p.chordAt[n.start]]; return ch.intervals.some(i => (ch.root + i) % 12 === ((n.midi % 12) + 12) % 12); });
  assert(fit.length === strong.length, `${mood} ${seed} downbeats off the chord`);
  composed++;
}
assert(core.compose('calm', { seed: 7 }).score.melody === core.compose('calm', { seed: 7 }).score.melody, 'same seed, same song');
// The harmonizer: "Twinkle" played on the keyboard gets its usual chords.
const twinkle = [];
[[72, 4], [72, 4], [79, 4], [79, 4], [81, 4], [81, 4], [79, 8], [77, 4], [77, 4], [76, 4], [76, 4], [74, 4], [74, 4], [72, 8]].reduce((at, [m, l]) => { twinkle.push({ start: at, length: l, midi: m }); return at + l; }, 0);
const h = core.harmonize(twinkle, { tonic: 'C', mode: 'major', beats: 4 });
assert(h.bars === 4 && h.chords.startsWith('C:') && h.chords.endsWith('G7:8 C:8') && /F:8/.test(h.chords), 'twinkle chords ' + h.chords);
assert(!core.checkScore({ ...largo, chords: h.chords, melody: h.melody }).errors.length, 'harmonized score');
assert(core.melodyText([{ start: 12, length: 8, midi: 72 }], 32, 16, false) === 'r:12 C5:4 | -:4 r:12', 'tie over the bar line: ' + core.melodyText([{ start: 12, length: 8, midi: 72 }], 32, 16, false));
console.log('composer ok:', composed, 'songs;', 'twinkle →', h.chords);

// 3. Recorded notes: a synthetic tone is found by pitch and file name, looped and leveled like the app's bank.
const tone = (f, sec, rate) => Float32Array.from({ length: Math.round(sec * rate) }, (_, i) => 0.3 * Math.sin(2 * Math.PI * f * i / rate) + 0.1 * Math.sin(4 * Math.PI * f * i / rate));
const up = core.resample(tone(220, 2.4, 44100), 44100);
const f = core.detectPitch(up);
assert(Math.abs(1200 * Math.log2(f / 220)) < 15, 'pitch ' + f);
assert(core.noteFromName('tenortbn_sus_A#3_v2_1.wav') === 58 && core.noteFromName('C4.wav') === 60 && core.noteFromName('piano-Fs2.mp3') === 42, 'names');
const loop = core.makeLoop(up, 220);
assert(loop && loop.loopEnd <= loop.x.length && loop.loopStart < loop.loopEnd, 'loop');

// 4. A studio-made pack: the score above plus a designed instrument and a recorded one.
const out = join(root, 'app/build/studio-pack');
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'scores'), { recursive: true }); mkdirSync(join(out, 'instruments'), { recursive: true });
const files = {}, items = [];
const score = core.scoreManifest({ ...largo, id: 'studio-largo', title: 'Studio Largo', defaults: ['cello', 'orchperc', 'strings', 'studio-bell', 'studio-tone', 'glock'] });
files['scores/studio-largo.json'] = core.utf8(JSON.stringify(score, null, 1));
const auto = core.scoreManifest({ ...core.compose('drive', { bars: 16, seed: 5 }).score, id: 'studio-auto', title: 'Studio Auto', creator: 'check' });
files['scores/studio-auto.json'] = core.utf8(JSON.stringify(auto, null, 1));
const bellNotes = [62, 70, 78].map(m => ({ root: core.hz(m), x: core.designNote(core.hz(m), core.DESIGNS.bell), loop: null }));
const bell = core.buildInstrument({ id: 'studio-bell', label: '스튜디오 종', sound: 'designed', creator: 'check', license: 'CC0', stages: [3, 5],
  sustained: false, voices: 6, mono: false, minMidi: 60, maxMidi: 90, gain: 1.9, attack: 0.002, release: 0.9, look: 'GLASS', color: '#9AD0FF' }, bellNotes);
assert(!core.checkInstrument({ ...bell.manifest }, bellNotes).errors.length, 'bell check');
const toneNotes = [{ root: f, x: loop.x, loop }];
const rec = core.buildInstrument({ id: 'studio-tone', label: '스튜디오 녹음', sound: 'recorded', creator: 'check', license: 'CC0', stages: [0, 2, 4],
  sustained: true, voices: 3, mono: false, minMidi: 40, maxMidi: 80, gain: 2.3, attack: 0.05, release: 0.3, look: 'BRASS', color: '#E3A857' }, toneNotes);
for (const b of [bell, rec]) { files[`instruments/${b.manifest.id}.json`] = core.utf8(b.json); files[`instruments/${b.manifest.pcm}`] = b.pcm; }
const sizes = Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.length]));
items.push(core.catalogItem('score', score, sizes), core.catalogItem('score', auto, sizes), core.catalogItem('instrument', bell.manifest, sizes), core.catalogItem('instrument', rec.manifest, sizes));
files['catalog.json'] = core.utf8(JSON.stringify({ format: 1, name: 'studio check', items }, null, 1));
for (const [p, d] of Object.entries(files)) writeFileSync(join(out, p), d);
const z = core.zip(files);
assert(z.length > 1000 && new DataView(z.buffer).getUint32(0, true) === 0x04034b50, 'zip');
writeFileSync(join(out, 'pack.zip'), z);
console.log('studio core ok;', Object.keys(files).length, 'files in', out);
