// Starter works for the studio gallery: public-domain melodies entered by hand, two songs made by the studio's
// own composer and one designed instrument. Writes docs/studio/seed/ (catalog.json + files, the cloud pack format)
// and a copy in app/build/studio-seed for the app's check. The admin's "예시 올리기" button uploads them.
//   node tools/studio/seed.mjs
import { writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as core from '../../docs/studio/core.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CURATOR = '카에스트로 큐레이션';
const LIGHT_ARP = '0 . 2 . 1 . 2 . 0 . 2 . 1 . 2 .';
const ON_BEATS = 'x . . . x . . . x . . . x . . .';

const scores = [
  {
    id: 'frere-jacques', title: 'Frère Jacques', character: '프랑스 민요 · 자크 형제 (돌림노래)', composer: '프랑스 민요',
    license: '퍼블릭 도메인 (전래 민요)', color: '#9AD7FF', bpm: 92, tempoRange: 14, beatsPerBar: 4, tonic: 'C',
    chords: 'C:16 | C | C | C | C | C | C | C',
    melody: 'C5:4 D5 E5 C5 | C5 D5 E5 C5 | E5:4 F5 G5:8 | E5:4 F5 G5:8 | G5:2 A5 G5 F5 E5:4 C5 | G5:2 A5 G5 F5 E5:4 C5 | C5:4 G4 C5:8 | C5:4 G4 C5:8',
    // The high stage follows two bars behind: the song as a round.
    bass: 'ROOT_FIFTH', arp: LIGHT_ARP, comp: ON_BEATS, high: 'CANON', canonBars: 2,
    defaults: ['cello', 'orchperc', 'strings', 'harp', 'flute', 'glock'],
  },
  {
    id: 'jingle-bells', title: 'Jingle Bells', character: 'J. 피어폰트 · 징글벨 (1857)', composer: 'J. Pierpont',
    license: '퍼블릭 도메인 (1857)', color: '#FF8F8F', bpm: 108, tempoRange: 16, beatsPerBar: 4, tonic: 'C',
    chords: 'C:16 | C | C | C | F | C | D7 | G7 | C | C | C | C | F | C | G7 | C',
    melody: 'E5:4 E5 E5:8 | E5:4 E5 E5:8 | E5:4 G5 C5:6 D5:2 | E5:16 | F5:4 F5 F5:6 F5:2 | F5:4 E5 E5 E5:2 E5 | E5:4 D5 D5 E5 | D5:8 G5:8 | ' +
      'E5:4 E5 E5:8 | E5:4 E5 E5:8 | E5:4 G5 C5:6 D5:2 | E5:16 | F5:4 F5 F5 F5 | F5:4 E5 E5 E5:2 E5 | G5:4 G5 F5 D5 | C5:16',
    bass: 'ROOT_FIFTH', arp: '0 1 2 1 0 1 2 1 0 1 2 1 0 1 2 1', comp: ON_BEATS, high: 'OCTAVE', canonBars: 0,
    defaults: ['pizz', 'latin', 'piano', 'marimba', 'trumpet', 'glock'],
  },
  {
    id: 'greensleeves', title: 'Greensleeves', character: '잉글랜드 민요 · 그린슬리브스', composer: '잉글랜드 민요',
    license: '퍼블릭 도메인 (전래 민요)', color: '#8FD0B8', bpm: 80, tempoRange: 10, beatsPerBar: 3, tonic: 'A',
    chords: 'Am:12 | Am | C | G | Em | Am | E | E | Am | Am | C | G | Em | Am | E | Am | Am',
    melody: 'r:8 A4:4 | C5:8 D5:4 | E5:6 F5:2 E5:4 | D5:8 B4:4 | G4:6 A4:2 B4:4 | C5:8 A4:4 | A4:6 G#4:2 A4:4 | B4:8 G#4:4 | E4:8 A4:4 | ' +
      'C5:8 D5:4 | E5:6 F5:2 E5:4 | D5:8 B4:4 | G4:6 A4:2 B4:4 | C5:6 B4:2 A4:4 | G#4:6 F#4:2 G#4:4 | A4:12 | -:12',
    bass: 'HALVES', arp: '0 . 1 . 2 . 1 . 2 . 1 .', comp: '. . . . x . . . x . . .', high: 'BELLS', canonBars: 0,
    defaults: ['cello', 'orchperc', 'viola', 'harp', 'flute', 'glasspad'],
  },
  {
    // 3/8 written two bars to one 3/4 bar: a step is a sixteenth, as in the original.
    id: 'fur-elise', title: 'Für Elise', character: '베토벤 · 엘리제를 위하여 (1810)', composer: 'L. van Beethoven',
    license: '퍼블릭 도메인 (1810)', color: '#C9B8F2', bpm: 66, tempoRange: 8, beatsPerBar: 3, tonic: 'A',
    chords: 'Am:12 | E7:6 Am:6 | E:6 Am:6 | E7:6 Am:6 | E:6 Am:6',
    melody: 'r:8 E5:2 D#5 | E5:1 D#5 E5 B4 D5 C5 A4:2 r:1 C4 E4 A4 | B4:2 r:1 E4 G#4 B4 C5:2 r:1 E4 E5 D#5 | ' +
      'E5:1 D#5 E5 B4 D5 C5 A4:2 r:1 C4 E4 A4 | B4:2 r:1 E4 C5 B4 A4:6',
    bass: 'HALVES', arp: '0 . . . 2 . 0 . . . 2 .', comp: 'x . . . . . x . . . . .', high: 'BELLS', canonBars: 0,
    defaults: ['cello', 'orchperc', 'strings', 'harp', 'piano', 'glasspad'],
  },
];

// Two songs from the studio's own composer (rule based, no source material).
for (const [id, mood, seed, title] of [['dawn-road', 'dreamy', 20261111, '새벽 국도'], ['highway-run', 'drive', 1111, '고속도로 질주']]) {
  const { score } = core.compose(mood, { bars: 16, seed });
  scores.push({ ...score, id, title, character: `${core.MOODS[mood].label} · 스튜디오 자동 작곡`, composer: '카에스트로 스튜디오',
    license: 'CC0 (스튜디오 자동 작곡)' });
}

const files = {}, items = [];
for (const s of scores) {
  const check = core.checkScore({ ...s, creator: CURATOR });
  if (check.errors.length) { console.error(s.id, check.errors); process.exit(1); }
  const p = check.piece;
  const strong = p.melody.filter(n => n.start % 4 === 0);
  const fit = strong.filter(n => { const c = p.chords[p.chordAt[n.start]]; return c.intervals.some(i => (c.root + i) % 12 === ((n.midi % 12) + 12) % 12); });
  console.log(`${s.id}: ${p.lengthSteps / p.spb} bars, ${p.melody.length} notes, ${fit.length}/${strong.length} beat notes on the chord`);
  const manifest = core.scoreManifest({ ...s, creator: CURATOR, voicings: '' });
  files[`scores/${s.id}.json`] = core.utf8(JSON.stringify(manifest, null, 1));
  items.push(['score', manifest]);
}

// A designed instrument: glass bells for the arpeggio and high stages.
const bell = { ...core.DESIGNS.bell, label: '글라스 벨' };
const notes = [];
for (let m = 60; m <= 96; m += 4) notes.push({ root: core.hz(m), x: core.designNote(core.hz(m), bell), loop: null });
const inst = core.buildInstrument({ id: 'glass-bell', label: '글라스 벨', sound: 'designed', creator: CURATOR, license: 'CC0 (퍼블릭 도메인)',
  stages: [3, 5], sustained: false, voices: 6, mono: false, minMidi: 60, maxMidi: 96, gain: 1.8, attack: 0.002, release: 1.0, look: 'GLASS', color: '#B4E4FF' }, notes);
files['instruments/glass-bell.json'] = core.utf8(inst.json);
files['instruments/glass-bell.pcm'] = inst.pcm;
items.push(['instrument', inst.manifest]);

const sizes = Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.length]));
const catalog = { format: 1, name: '카에스트로 스튜디오 시작 작품', items: items.map(([k, m]) => core.catalogItem(k, m, sizes)) };
for (const out of [join(root, 'docs/studio/seed'), join(root, 'app/build/studio-seed')]) {
  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, 'scores'), { recursive: true }); mkdirSync(join(out, 'instruments'), { recursive: true });
  for (const [p, d] of Object.entries(files)) writeFileSync(join(out, p), d);
  writeFileSync(join(out, 'catalog.json'), JSON.stringify(catalog, null, 1));
}
console.log('seed:', catalog.items.map(i => i.id).join(', '));
