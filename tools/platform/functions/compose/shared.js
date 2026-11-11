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
- A step is a sixteenth note. A bar has beatsPerBar*4 steps: 16 in 4/4, 12 in 3/4.
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
  syncopation for jazz, bossa nova or pop; long held notes at phrase ends; a rest now and then).
- Choose what fits the request instead of defaults: the key (any of the 12, minor for sad, dark or tense moods,
  major for bright ones), 3/4 for waltzes and lullabies, the tempo, the chord colours (7ths and 9th-like chords for
  jazz, simple triads for children's songs), 8 to 16 bars, and the band.
- bass: HALVES (chord root every half bar), ROOT_FIFTH (root and fifth by beats) or VOICING, or an explicit line in
  the melody notation (octave 2 or 3) that fills whole bars.
- arp: exactly one bar of space-separated tokens (16 in 4/4, 12 in 3/4): a digit 0-4 picks a chord note from low to
  high, "." rests.
- comp: exactly one bar of space-separated tokens: "x" strikes the chord, "." rests.
- high: BELLS (top chord note as bells), OCTAVE (the melody an octave up) or CANON (the melody again canonBars bars
  later; only sounds good when the harmony repeats every canonBars bars or stays on one chord).
- band: one suggested instrument per stage from the allowed ids.

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
