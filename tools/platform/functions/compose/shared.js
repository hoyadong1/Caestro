// What the composing model is told and how its answer becomes a score. Shared by the edge function (index.ts)
// and the local try-out script, so both send exactly the same instructions.

export const STAGE_KEYS = ['bass', 'rhythm', 'harmony', 'arpeggio', 'melody', 'high'];

/** JSON Schema of the answer; instrument ids come from the app's own list per stage (core.js BUILT_IN). */
export function schema(C) {
  const instruments = stage => C.BUILT_IN[stage].map(([id]) => id);
  return {
    type: 'object',
    additionalProperties: false,
    required: ['title', 'character', 'tonic', 'beatsPerBar', 'bpm', 'tempoRange', 'bars', 'bass', 'arp', 'comp',
      'high', 'canonBars', 'band', 'color', 'note'],
    properties: {
      title: { type: 'string', description: 'Short song title (Korean or English), at most 40 characters' },
      character: { type: 'string', description: 'One line shown under the title in the car, Korean, at most 60 characters; describes only the mood, never names a person, song, film or game' },
      tonic: { type: 'string', enum: ['C', 'C#', 'Db', 'D', 'Eb', 'E', 'F', 'F#', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'] },
      beatsPerBar: { type: 'integer', description: '3 or 4' },
      bpm: { type: 'integer', description: 'Tempo when parked, 40 to 160' },
      tempoRange: { type: 'integer', description: 'BPM added at 130 km/h, 0 to 40' },
      bars: {
        type: 'array',
        description: '8 to 16 bars in order; each bar has its chords and its melody, each adding up to exactly one bar of steps',
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['chords', 'melody'],
          properties: {
            chords: { type: 'string', description: 'This bar only, e.g. "C:16" or "F:8 G7:8" in 4/4, "Am:12" in 3/4' },
            melody: { type: 'string', description: 'This bar only, e.g. "E5:4 G5:4 C5:8" or "r:4 D5:12"' },
          },
        },
      },
      bass: { type: 'string' },
      arp: { type: 'string' },
      comp: { type: 'string' },
      high: { type: 'string', enum: ['BELLS', 'OCTAVE', 'CANON'] },
      canonBars: { type: 'integer', description: 'Bars the high stage follows behind with CANON, else 0' },
      band: {
        type: 'object',
        additionalProperties: false,
        required: STAGE_KEYS,
        properties: Object.fromEntries(STAGE_KEYS.map((k, i) => [k, { type: 'string', enum: instruments(i) }])),
      },
      color: { type: 'string', description: 'Card color, #RRGGBB' },
      note: { type: 'string', description: 'One or two Korean sentences to the user about what you made and why' },
    },
  };
}

export const SYSTEM = `You compose music for Caestro, a car app in which the car conducts a small band: the speed brings in
six stages one by one (bass always; rhythm from 18 km/h, chords 38, arpeggio 58, melody 82, high 105). You write
ONE score; every stage plays its share of it, and the listener picks the instruments. People listen while driving,
so keep it musical, singable and not busy; let the music grow as stages join.

Write the score in this text notation exactly (it is parsed by a program):
- A step is a sixteenth note and a beat (quarter note) is always 4 steps. A bar has beatsPerBar*4 steps: 16 in 4/4,
  12 in 3/4 (three beats of 4, e.g. "C5:4 E5:4 G5:4" or "A4:8 B4:4"; never count a 3/4 beat as 3 steps).
- bars: the song bar by bar (8 to 16 bars). Each bar has "chords" and "melody"; inside a bar the lengths must add up
  to exactly one bar (16 steps in 4/4, 12 in 3/4). Write every length explicitly as ":LEN" in steps.
  - chords: tokens "SYMBOL:LEN" separated by spaces. Symbols: root A-G with optional # or b, then one of
    "" (major), m, 7, maj7, m7, m6, 6, dim, dim7, aug, sus4, and an optional slash bass like F/C.
    Examples in 4/4: "C:16", "F:8 G7:8". In 3/4: "Am:12", "Dm:6 E7:6".
  - melody: tokens "NOTE:LEN" (NOTE like C5, F#4, Bb4) or "r:LEN" for a rest; "-:LEN" ties onto the previous note
    (also across a bar line). Examples in 4/4: "E5:4 G5:4 C5:8", "D5:6 E5:2 F5:8", "r:4 G4:4 A4:4 B4:4".
  Keep the melody between G4 and C6 and make it a real tune: motifs that come back, phrases of 2 or 4 bars that
  breathe, chord tones on the strong beats, steps more than leaps, end on the tonic in the last bar.
  Make the rhythm alive, not a row of equal notes: mix lengths (2, 4, 6, 8, 12 steps; dotted figures like 6+2 or 3+1;
  syncopation for jazz, bossa nova or pop; long held notes at phrase ends; a rest now and then). At most half of
  the notes may be quarter notes (length 4); every 2nd or 4th bar ends a phrase with a long note (8+) or a rest.
- tonic is the home note of the key (A for A minor). The song starts and ends at home: the first and last chords are
  the tonic chord, and the melody's last note is the tonic, held long.
- Choose what fits the request instead of defaults: the key (any of the 12, minor for sad, dark or tense moods,
  major for bright ones; do not fall back on C major or A minor unless asked), 3/4 for waltzes and lullabies, the tempo, the chord colours (7ths and 9th-like chords for
  jazz, simple triads for children's songs), 8 to 16 bars, and the band.
- bass: HALVES (chord root every half bar: calm, ballads, film), ROOT_FIFTH (root and fifth by beats: marches,
  waltzes, country, bossa nova), VOICING (follows the chord's lowest note: hymn-like, smooth), or best for jazz, funk,
  pop and fast pieces an explicit line in the melody notation (octave 2 or 3, whole bars) such as a walking bass
  "C3:4 E3:4 G3:4 A3:4 | F2:4 A2:4 C3:4 D3:4 |" or a syncopated "C2:6 C3:2 r:4 G2:4 |".
- arp: exactly one bar of space-separated tokens (16 in 4/4, 12 in 3/4): a digit 0-4 picks a chord note from low to
  high, "." rests. Shape it to the style, not always "0 1 2 3": e.g. "0 . 2 . 4 . 2 ." flowing, "0 2 4 2 3 2 4 2"
  piano-like, ". . 2 . . 3 . ." sparse plucks, "0 . . 3 . . 2 . . 4 . . 1 . 2 ." bossa/latin.
- comp: exactly one bar of space-separated tokens: "x" strikes the chord, "." rests. Match the style: "x . . . . . . .
  x . . . . . . ." calm; ". . . . x . . . . . . . x . . ." backbeat pop; ". . x . . . x . . x . . . x . ." bossa;
  "x . . . x . x . . . x . x . . ." funky; in 3/4 "x . . . x . . . x . . ." or "x . . . . . x . . . x .".
- high: BELLS (top chord note as bells), OCTAVE (the melody an octave up) or CANON (the melody again canonBars bars
  later; only sounds good when the harmony repeats every canonBars bars or stays on one chord).
- band: one instrument per stage from the allowed ids, chosen for this song. The rhythm stage plays a fixed groove
  per instrument, so it decides much of the feel: orchperc = classical, marches, film; timpani = epic, dramatic,
  slow and grand; latin = bossa nova, latin, beach, summer; shaker = acoustic, folk, light morning pop; softkit =
  ballads, slow jazz, soft pop; electro = EDM, synth-pop, night city, chases; none = lullabies, solo piano, very calm.
  Likewise the high stage: glock = bright, children; musicbox = lullaby, nostalgic; glasspad = dreamy, night, space;
  xylophone = playful; flute/violin/strings = lyrical, classical; none = when the melody should stand alone.
  Make two different requests sound different: avoid choosing the same band out of habit.

Originality and copyright: write original music. If the request names a song that is still under copyright (modern
pop, K-pop, film or game music, anything recent), do not reproduce or closely imitate its melody: write an original
piece in that mood and say so in the note. Melodies whose copyright has long ended (classical works by composers who
died long ago, traditional folk songs) are fine when you know them exactly.

Never put the name of a real artist, song, film or game in title or character (they are shown in the car); you may
mention what inspired you in the note. Write title (Korean or a short English title), character and note in Korean.`;

// ---- small repairs, so a nearly right answer still plays: every bar is made to add up to exactly one bar.
function fitBar(text, spb, fill) {
  const out = [];
  let used = 0;
  for (const token of String(text || '').trim().split(/\s+/).filter(t => t && t !== '|')) {
    const [what, len] = token.split(':');
    let n = Number(len) || 4;
    if (used >= spb) break;
    n = Math.min(n, spb - used);
    out.push(`${fill === 'hold' ? nearestChord(what) : what}:${n}`);
    used += n;
  }
  if (used < spb) {
    // Hold the last chord, or rest at the end of a melody bar.
    if (fill === 'hold' && out.length) { const [w, l] = out.pop().split(':'); out.push(`${w}:${Number(l) + spb - used}`); }
    else out.push(fill === 'hold' ? `C:${spb - used}` : `r:${spb - used}`);
  }
  return out.join(' ');
}
/** Chord colours the app does not have, as the nearest one it has (Cm9 -> Cm7, G13 -> G7, Csus2 -> C, Bm7b5 -> Bdim). */
function nearestChord(symbol) {
  const m = /^([A-G][#b]?)([^/]*)(\/[A-G][#b]?)?$/.exec(symbol);
  if (!m) return symbol;
  let q = m[2];
  const map = [[/^(maj9|maj11|maj13|maj7#11|M7|M9|Δ7?)$/, 'maj7'], [/^(m9|m11|m13|min7|mi7|-7)$/, 'm7'], [/^(m7b5|ø7?|half-?dim)$/, 'dim'],
    [/^(9|11|13|7sus4|7b9|7#9|7b5|7#5|9sus4|13sus4|7alt)$/, '7'], [/^(add9|add2|sus2|5|maj|M|\(add9\))$/, ''], [/^(min|mi|-)$/, 'm'],
    [/^(m\(add9\)|madd9|m\(maj7\)|mM7)$/, 'm'], [/^(6\/9|69)$/, '6'], [/^(o|°)$/, 'dim'], [/^(o7|°7)$/, 'dim7'], [/^(\+|aug5|#5)$/, 'aug']];
  for (const [re, to] of map) if (re.test(q)) { q = to; break; }
  return m[1] + q + (m[3] || '');
}
function fitPattern(text, spb, rest) {
  const t = String(text || '').trim().split(/\s+/).filter(Boolean);
  return Array.from({ length: spb }, (_, i) => t[i] ?? rest).join(' ');
}

/**
 * Names the request points at ("아이유 좋은날 같은 노래" -> 아이유, 좋은날): the words just before 같은/처럼/스타일/풍.
 * They must not reach the car's screen even if the model copies them into title or character.
 */
// Genres, moods and places are fine to keep; only proper names are removed.
const COMMON = /^(혼자|함께|같이|우리|아이|가족|친구|연인|노래|음악|곡|분위기|느낌|감성|정도|살짝|조금|약간|아주|완전|재즈|보사노바|클래식|발라드|팝|록|락|힙합|댄스|트로트|국악|동요|왈츠|행진곡|자장가|영화|영화음악|게임|게임음악|애니|애니메이션|드라마|광고|카페|봄|여름|가을|겨울|바다|바닷가|새벽|밤|아침|비|눈|파도|우주|꿈|동화|축제|파티|겨울밤|크리스마스)$/;
export function referencedNames(prompt) {
  const names = new Set();
  const re = /((?:[^\s,.]+\s+){0,2}[^\s,.]+?)(?:의|이|가)?\s*(?:같은|같이|처럼|스타일|풍)/g;
  for (const m of String(prompt || '').matchAll(re))
    for (const w of m[1].split(/\s+/)) {
      const word = w.replace(/(의|이|가)$/, '');
      // Verbs, adjectives and adverbs ("듣는", "따뜻한", "함께", "아이와") are not names.
      if (word.length >= 2 && !COMMON.test(word) && !/(는|은|던|한|운|게|고|와|과|히|께|로|에|를|을)$/.test(word)) names.add(word);
    }
  return [...names].filter(w => w.length >= 2);
}
function scrub(text, names) {
  let t = String(text || '');
  for (const n of names) t = t.split(n).join('');
  return t.replace(/^[\s의,·'"]+/, '').replace(/\s{2,}/g, ' ').trim();
}

/**
 * A musician's quick read of a score that already passes the car's checker: what would make it sound better.
 * Returns English remarks for the model (empty when the score is fine).
 */
export function critique(score, C) {
  const spb = score.beatsPerBar * 4;
  const ch = C.chords(score.chords, spb), mel = C.notes(score.melody, spb);
  const remarks = [];
  if (!mel.length) return ['The melody is empty.'];
  const lengths = new Set(mel.map(n => n.length));
  const quarters = mel.filter(n => n.length === 4).length / mel.length;
  if (lengths.size < 3 || quarters > 0.75)
    remarks.push(`The melody rhythm is too even (${lengths.size} different note lengths, ${Math.round(quarters * 100)}% quarter notes). ` +
      'Mix in eighths, dotted figures (6+2), syncopation and long notes so it sounds like a real tune.');
  // Lengths of 3, 6, 9 steps everywhere usually mean the beat was counted as 3 steps; in 3/4 a beat is still 4.
  const threes = mel.filter(n => n.length % 3 === 0 && n.length % 4).length / mel.length;
  const onBeat = mel.filter(n => n.start % 4 === 0).length / mel.length;
  if (threes > 0.3 && onBeat < 0.6)
    remarks.push(`Most notes are ${[...new Set(mel.filter(n => n.length % 3 === 0 && n.length % 4).map(n => n.length))].join('/')} steps long and fall between the beats. ` +
      'A beat is 4 steps (a quarter note) in every meter, so a 3/4 bar is three notes of 4, or 8+4, or 4+2+2+4.');
  const chordAt = t => ch.find(c => t >= c.start && t < c.start + c.length);
  const strong = mel.filter(n => n.start % spb === 0 || (spb === 16 && n.start % spb === 8));
  const fit = strong.filter(n => chordAt(n.start).intervals.some(i => (chordAt(n.start).root + i) % 12 === n.midi % 12)).length;
  if (strong.length && fit / strong.length < 0.75)
    remarks.push(`Only ${fit} of ${strong.length} melody notes on strong beats are notes of the chord under them; put chord tones on beats 1 and 3.`);
  // Held melody notes on a beat that rub a semitone against the chord (F over Dbm7): the ear hears these as wrong.
  const rubs = heldClashes(score, C);
  if (rubs.length > 3 && rubs.length > 0.12 * mel.filter(n => !(n.start % 4) && n.length >= 4).length)
    remarks.push('These held melody notes clash a semitone against the chord under them: ' +
      rubs.slice(0, 4).map(r => `bar ${r.bar} ${C.noteName(r.note.midi)} over ${r.chord.name}`).join(', ') +
      '. Make the melody and the chord agree (e.g. a major chord under a major third).');
  const bm = bassMisses(score, C);
  if (bm.misses.length > Math.max(1, 0.2 * bm.total))
    remarks.push(`The bass line doesn't fit the chords at: ${bm.misses.slice(0, 4).join(', ')}. Start each chord with one of its notes in the bass.`);
  const bars = Math.round(ch.at(-1).start + ch.at(-1).length) / spb;
  let breathing = 0;
  for (let b = 3; b < bars; b += 4) {
    const end = (b + 1) * spb, inBar = mel.filter(n => n.start >= b * spb && n.start < end);
    const last = inBar.at(-1);
    if (!last || inBar.some(n => n.length >= 8) || last.start + last.length < end) breathing++;
  }
  if (bars >= 8 && breathing < Math.floor(bars / 4) / 2)
    remarks.push('The phrases never breathe: end each 4-bar phrase with a long note or a short rest.');
  return remarks;
}

function heldClashes(score, C) {
  const spb = score.beatsPerBar * 4;
  const ch = C.chords(score.chords, spb), mel = C.notes(score.melody, spb);
  const out = [];
  for (const note of mel) {
    if (note.start % 4 || note.length < 4) continue;
    const chord = ch.find(c => note.start >= c.start && note.start < c.start + c.length);
    const set = chord.intervals.map(i => (chord.root + i) % 12), p = note.midi % 12;
    if (!set.includes(p) && set.some(q => (p - q + 12) % 12 === 1)) out.push({ bar: Math.floor(note.start / spb) + 1, note, chord });
  }
  return out;
}

/**
 * When the melody holds the major third over a minor chord (or the minor third over a major one), the melody
 * wins: the chord switches to the matching quality (Dbm7 -> Db7/Dbmaj7, C -> Cm).
 */
export function agreeChords(score, C) {
  const spb = score.beatsPerBar * 4;
  const ch = C.chords(score.chords, spb), mel = C.notes(score.melody, spb);
  const tonic = C.pitchClass(score.tonic);
  const toMajor = { m: '', m7: '7', m6: '6' }, toMinor = { '': 'm', 7: 'm7', maj7: 'm7', 6: 'm6' };
  let i = 0;
  const chords = score.chords.split(' ').map(token => {
    if (token === '|') return token;
    const c = ch[i++];
    const [name, len] = token.split(':');
    const m = /^([A-G][#b]?)(maj7|m7|m6|dim7|dim|aug|sus4|m|7|6)?(\/[A-G][#b]?)?$/.exec(name);
    if (!m) return token;
    const quality = m[2] || '';
    const held = third => mel.filter(n => !(n.start % 4) && n.length >= 4 && n.start >= c.start && n.start < c.start + c.length &&
      n.midi % 12 === (c.root + third) % 12).length;
    if (quality in toMajor && held(4) > 0 && held(3) === 0) {
      const q = quality === 'm7' ? (c.root === tonic ? 'maj7' : '7') : toMajor[quality];
      return `${m[1]}${q}${m[3] || ''}:${len}`;
    }
    if (quality in toMinor && held(3) > 0 && held(4) === 0) return `${m[1]}${toMinor[quality]}${m[3] || ''}:${len}`;
    return token;
  });
  return { ...score, chords: chords.join(' ') };
}

/** Makes sure the song ends at home: last chord = the first (tonic) chord, last melody note = its root. */
export function homeEnding(score, C) {
  const spb = score.beatsPerBar * 4;
  const first = C.chords(score.chords, spb)[0];
  const chordBars = score.chords.split(' | '), lastBar = chordBars.at(-1).split(' ');
  const [lastName, lastLen] = lastBar.at(-1).split(':');
  const homeName = first.name.split('/')[0];
  if (C.chords(lastName + ':' + spb, spb)[0].root !== first.root) lastBar[lastBar.length - 1] = `${homeName}:${lastLen}`;
  chordBars[chordBars.length - 1] = lastBar.join(' ');
  const melBars = score.melody.split(' | '), tokens = melBars.at(-1).split(' ');
  for (let i = tokens.length - 1; i >= 0; i--) {
    const [what, len] = tokens[i].split(':');
    if (what === 'r' || what === '-') continue;
    const m = C.midi(what);
    let best = m;
    for (let d = 0; d <= 6; d++) { if (((m + d) % 12) === first.root) { best = m + d; break; } if ((((m - d) % 12) + 12) % 12 === first.root) { best = m - d; break; } }
    tokens[i] = `${C.spell ? C.spell(best, /b/.test(homeName)) : C.noteName(best)}:${len}`;
    break;
  }
  melBars[melBars.length - 1] = tokens.join(' ');
  return { ...score, tonic: homeName.match(/^[A-G][#b]?/)[0], chords: chordBars.join(' | '), melody: melBars.join(' | ') };
}

/** The model's answer as the studio's score fields. */
export function toScore(out, prompt = '') {
  const names = referencedNames(prompt);
  out = { ...out, title: scrub(out.title, names), character: scrub(out.character, names) };
  const band = out.band || {};
  const beats = out.beatsPerBar === 3 ? 3 : 4, spb = beats * 4;
  const bars = (Array.isArray(out.bars) ? out.bars : []).slice(0, 32);
  // A tie can't open the song; elsewhere it carries a note across the bar line.
  const melodyBars = bars.map(b => fitBar(b.melody, spb, 'rest'));
  if (melodyBars.length) melodyBars[0] = melodyBars[0].replace(/^-:/, 'r:');
  out = { ...out, beatsPerBar: beats, chords: bars.map(b => fitBar(b.chords, spb, 'hold')).join(' | '), melody: melodyBars.join(' | '),
    arp: fitPattern(out.arp, spb, '.'), comp: fitPattern(out.comp, spb, '.') };
  return {
    id: 'ai-song', title: String(out.title || 'AI 작곡').slice(0, 40), character: String(out.character || 'AI 작곡').slice(0, 60),
    composer: '카에스트로 AI 작곡', creator: '', license: '직접 작곡 · CC BY 4.0',
    color: /^#[0-9a-fA-F]{6}$/.test(out.color) ? out.color : '#9AD7FF',
    bpm: out.bpm, tempoRange: out.tempoRange, beatsPerBar: out.beatsPerBar, tonic: out.tonic, chords: out.chords, voicings: '',
    melody: out.melody, bass: out.bass, arp: out.arp, comp: out.comp, high: out.high, canonBars: out.high === 'CANON' ? out.canonBars : 0,
    defaults: STAGE_KEYS.map(k => band[k]),
  };
}

/** The first message: the request plus a key picked at random, so songs don't all land in the model's favourite key. */
export function requestText(prompt, random = Math.random) {
  const keys = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'Ab', 'A', 'Bb', 'B'];
  const key = keys[Math.floor(random() * keys.length)];
  return `요청: ${prompt}\n(Unless the request names a key, write it in ${key} major or ${key} minor, whichever suits the mood.)`;
}

/**
 * A 3/4 song written with the beat counted as 3 steps (lengths 3, 6, 9: four "beats" per bar) is really in four.
 * When every length is a multiple of 3 and the notes keep missing the beat, play it as written: 4/4, lengths x4/3.
 */
export function fixThreeCount(score, C) {
  if (score.beatsPerBar !== 3) return score;
  const lengths = text => text.split(' ').filter(t => t !== '|').map(t => Number(t.split(':')[1]));
  const all = [...lengths(score.melody), ...lengths(score.chords)];
  if (all.some(n => !n || n % 3)) return score;
  const mel = C.notes(score.melody, 12);
  const offBeat = mel.filter(n => n.start % 4).length / mel.length;
  if (offBeat < 0.25) return score;
  const scale = text => text.split(' ').map(t => t === '|' ? t : `${t.split(':')[0]}:${Number(t.split(':')[1]) * 4 / 3}`).join(' ');
  const widen = text => { const t = text.split(' '); return [...t, ...t.slice(0, 4)].join(' '); };
  return { ...score, beatsPerBar: 4, melody: scale(score.melody), chords: scale(score.chords), arp: widen(score.arp), comp: widen(score.comp) };
}

/** Chords whose start the bass line doesn't support (the bass note is not in the chord). */
function bassMisses(score, C) {
  const p = C.checkScore({ ...score, title: 'x', character: 'x' }).piece;
  if (!p) return { misses: [], total: 0 };
  const at = t => p.bass.find(n => t >= n.start && t < n.start + n.length);
  const misses = p.chords.filter(c => {
    const b = at(c.start);
    return b && !c.intervals.some(i => (c.root + i) % 12 === b.midi % 12) && b.midi % 12 !== c.bassClass;
  }).map(c => `${c.name} over ${C.noteName(at(c.start).midi)}`);
  return { misses, total: p.chords.length };
}

/** A written bass line that mostly fights the chords is replaced by the plain root bass. */
export function safeBass(score, C) {
  if (['HALVES', 'ROOT_FIFTH', 'VOICING'].includes(String(score.bass).trim())) return score;
  const { misses, total } = bassMisses(score, C);
  return misses.length > Math.max(2, 0.3 * total) ? { ...score, bass: 'HALVES' } : score;
}
