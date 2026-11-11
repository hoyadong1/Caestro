// Composing as an ensemble: a conductor writes only what every part must agree on (key, meter, tempo, form, the
// chords bar by bar, how strong each section is, the band); then the players write their parts separately, each
// seeing that skeleton and nothing of the others: melody, bass, drums, and the keys (arpeggio and accompaniment).
// The parts are put together into one score and checked; a part the check finds wrong is asked again on its own.
//
// Shared by the edge function and the local comparison script. generate(system, contents, schema) is passed in.
import { STAGE_KEYS, agreeChords, critique, cutBars, fitDrums, fitPattern, fixThreeCount, homeEnding, safeBass, stepsOf, toScore, tokensOf } from './shared.js';

const NAMES = ['I', 'A', 'B', 'C', 'E'];
const SECTION_WORD = { I: 'intro', A: 'verse', B: 'chorus', C: 'bridge', E: 'ending' };

const NOTATION = `Notation (parsed by a program): a step is a sixteenth note, a beat (quarter note) is always 4 steps, a bar has
16 steps in 4/4 and 12 in 3/4 (three beats of 4; never count a 3/4 beat as 3 steps). Notes are "NOTE:LEN" (C5, F#4, Bb4),
"r:LEN" rests, "-:LEN" ties onto the previous note, LEN in steps, written on every token; tokens separated by spaces.`;

// ------------------------------------------------------------------ the conductor

export function conductorSchema(C) {
  const instruments = stage => C.BUILT_IN[stage].map(([id]) => id);
  return {
    type: 'object', additionalProperties: false,
    required: ['title', 'character', 'tonic', 'mode', 'beatsPerBar', 'bpm', 'tempoRange', 'sections', 'form', 'high', 'canonBars', 'band', 'color', 'note'],
    properties: {
      title: { type: 'string', description: 'Short song title (Korean or English), at most 40 characters' },
      character: { type: 'string', description: 'One Korean line about the mood, at most 60 characters; never names a person, song, film or game' },
      tonic: { type: 'string', enum: ['C', 'C#', 'Db', 'D', 'Eb', 'E', 'F', 'F#', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'] },
      mode: { type: 'string', enum: ['major', 'minor'] },
      beatsPerBar: { type: 'integer', description: '3 or 4' },
      bpm: { type: 'integer', description: 'Tempo when parked, 40 to 160' },
      tempoRange: { type: 'integer', description: 'BPM added at 130 km/h, 0 to 40' },
      sections: {
        type: 'array',
        items: {
          type: 'object', additionalProperties: false, required: ['name', 'energy', 'chords'],
          properties: {
            name: { type: 'string', enum: NAMES },
            energy: { type: 'string', enum: ['calm', 'medium', 'peak'] },
            chords: { type: 'array', description: 'One item per bar: that bar\'s chords, e.g. "C:16" or "F:8 G7:8"', items: { type: 'string' } },
          },
        },
      },
      form: { type: 'array', items: { type: 'string', enum: NAMES } },
      high: { type: 'string', enum: ['BELLS', 'OCTAVE', 'CANON'] },
      canonBars: { type: 'integer' },
      band: { type: 'object', additionalProperties: false, required: STAGE_KEYS,
        properties: Object.fromEntries(STAGE_KEYS.map((k, i) => [k, { type: 'string', enum: instruments(i) }])) },
      color: { type: 'string', description: 'Card color, #RRGGBB' },
      note: { type: 'string', description: 'One or two Korean sentences to the listener about the piece' },
    },
  };
}

const CONDUCTOR = `You are the conductor and arranger of Caestro, a car app in which the car conducts a small band: the speed
brings in six stages one by one (bass always; rhythm from 18 km/h, chords 38, arpeggio 58, melody 82, high 105).
You do NOT write the parts. You write only what every player must agree on; four players will then write the melody,
the bass, the drums and the keys separately from your plan, without hearing each other.

${NOTATION}

Decide, to fit the request:
- key (tonic and mode; minor for sad, dark or tense, major for bright; avoid C major and A minor unless asked), meter
  (3/4 for waltzes and lullabies), tempo.
- the form like a real song: sections I (intro, 1-4 bars), A (verse, 4-8), B (chorus, 4-8, the high point), optional
  C (bridge, 2-8, a contrast) and E (ending, 1-4); form lists the order (4 to 9 entries, 24 to 48 bars in all), e.g.
  ["I","A","B","A","B","C","B","E"]. The song loops after the last entry.
- the chords of every bar of every section (one array item per bar, adding up to exactly one bar). Symbols: root A-G
  with optional # or b, then "" (major), m, 7, maj7, m7, m6, 6, dim, dim7, aug or sus4, optional slash bass (F/C).
  Use colours that fit the style (7ths for jazz, simple triads for children's songs). Start and end at home (tonic chord).
- energy of each section: calm, medium or peak (the chorus usually peak, intro and ending calm).
- band: one instrument per stage (rhythm: orchperc classical/film, timpani epic, latin bossa/beach, shaker acoustic/folk,
  softkit ballad/soft jazz/pop, electro EDM/night/chase, none lullaby/solo piano), and the high stage line: BELLS, OCTAVE
  or CANON (canonBars bars later; only when the harmony repeats every canonBars bars).
Write original music. Title, character and note in Korean; never the name of a real artist, song, film or game in
title or character.`;

// ------------------------------------------------------------------ the players

const linesSchema = (what, withStyle) => ({
  type: 'object', additionalProperties: false,
  required: withStyle ? ['style', 'sections'] : ['sections'],
  properties: {
    ...(withStyle ? { style: { type: 'string', enum: ['LINE', 'HALVES', 'ROOT_FIFTH', 'VOICING'] } } : {}),
    sections: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['name', 'bars'],
      properties: { name: { type: 'string', enum: NAMES }, bars: { type: 'array', description: `One item per bar: that bar's ${what}`, items: { type: 'string' } } } } },
  },
});
const drumsSchema = { type: 'object', additionalProperties: false, required: ['sections'], properties: { sections: { type: 'array', items: {
  type: 'object', additionalProperties: false, required: ['name', 'drums'], properties: { name: { type: 'string', enum: NAMES }, drums: { type: 'string', description: 'One bar of drum tokens' } } } } } };
const keysSchema = { type: 'object', additionalProperties: false, required: ['sections'], properties: { sections: { type: 'array', items: {
  type: 'object', additionalProperties: false, required: ['name', 'arp', 'comp'],
  properties: { name: { type: 'string', enum: NAMES }, arp: { type: 'string', description: 'One bar of arpeggio tokens' }, comp: { type: 'string', description: 'One bar of accompaniment tokens' } } } } } };

const PLAYER = `You play one part in Caestro's band, written from the conductor's plan below. The other players write the
other parts at the same time; you do not hear them, so follow the plan exactly: the same sections, the same number of
bars in each, every bar adding up to exactly one bar.

${NOTATION}`;

export const PLAYERS = {
  melody: {
    schema: () => linesSchema('melody', false),
    system: `${PLAYER}

You write the MELODY (the tune everyone remembers), section by section, one string per bar. Keep it between G4 and C6
and make it a real tune: motifs that come back, phrases of 2 or 4 bars that breathe (end with a long note or a rest),
chord tones of the conductor's chords on the strong beats, steps more than leaps. Make the rhythm alive: mix lengths
(2, 4, 6, 8, 12; dotted 6+2; syncopation for jazz, bossa nova, pop); at most half the notes quarter notes. The chorus
sits higher and is the most memorable; a calm intro may rest or hint at the tune; end on the tonic, held long.`,
  },
  bass: {
    schema: () => linesSchema('bass line', true),
    system: `${PLAYER}

You write the BASS. Choose style LINE and write a bass line for every bar (octave 2 or 3), or a plain style with all
bars "" (HALVES: root every half bar, calm or film; ROOT_FIFTH: root and fifth by beats, marches, waltzes, country;
VOICING: smooth and hymn-like). A line starts every chord on one of its notes (usually the root), moves with the style
(walking quarter notes for jazz, syncopated "C2:6 C3:2 r:4 G2:4" for bossa or pop, long notes for ballads), and does
more in peak sections than in calm ones.`,
  },
  drums: {
    schema: () => drumsSchema,
    system: `${PLAYER}

You are the DRUMMER: one bar of drum tokens per section, repeated through that section (exactly 16 tokens in 4/4, 12 in
3/4, one token per sixteenth step, no ":LEN" lengths).
Each token is "." or letters struck together: K = low drum (kick, bass drum, low conga, timpani tonic), S = backbeat
(rim, snare, conga, timpani fifth), H = small high percussion (shaker, hi-hat, tambourine), A = accent (open hat,
triangle, claves; heard only at higher speeds). The listener's chosen rhythm instrument plays them in its own sound.
Follow the energy: calm sections light or silent (all "."), medium a steady groove, peak the fullest. Fit the style:
ballad "K . . . . . H . S . . . . . H ."; pop "K . H . S . H . K . H . S . H ."; bossa "K . H S . H K . K . H S . H K .";
march "K . S . K . S . K . S S K . S A"; EDM "KH . H . KSH . H . KH . H . KSH . H A"; waltz "K . . . S . . . S . . .".`,
  },
  keys: {
    schema: () => keysSchema,
    system: `${PLAYER}

You play the KEYS: for each section one bar of arpeggio and one bar of accompaniment, repeated through that section
(exactly 16 tokens each in 4/4, 12 in 3/4, one token per sixteenth step, no ":LEN" lengths in these patterns). arp: a digit 0-4 picks a chord note from low to high, "." rests (flowing
"0 . 2 . 4 . 2 .", piano "0 2 4 2 3 2 4 2", sparse ". . 2 . . 3 . .", latin "0 . . 3 . . 2 . . 4 . . 1 . 2 ."). comp:
"x" strikes the chord, "." rests (calm "x . . . . . . . x . . . . . . .", backbeat ". . . . x . . . . . . . x . . .",
bossa ". . x . . . x . . x . . . x . .", funky "x . . . x . x . . . x . x . . ."). Calm sections sparse, peak sections
busier, and make the sections differ.`,
  },
};

// ------------------------------------------------------------------ the plan as the players see it

/** The conductor's chords cut into whole bars per section (a long string is spread over as many bars as it fills). */
function sectionBars(sec, spb) {
  const stream = (sec.chords || []).join(' ');
  const bars = Math.max(1, Math.ceil(stepsOf(stream) / spb));
  return cutBars(stream, spb, bars, false).map(b => b || `C:${spb}`).map(b => {
    const used = stepsOf(b);
    if (used >= spb) return b;
    const t = tokensOf(b); const [w, l] = t.at(-1).split(':'); t[t.length - 1] = `${w}:${Number(l) + spb - used}`; return t.join(' ');
  });
}

export function plan(conductor) {
  const spb = (conductor.beatsPerBar === 3 ? 3 : 4) * 4;
  const sections = (conductor.sections || []).filter(s => NAMES.includes(s.name) && s.chords?.length)
    .map(s => ({ name: s.name, energy: s.energy || 'medium', bars: sectionBars(s, spb) }));
  const known = new Set(sections.map(s => s.name));
  let form = (conductor.form || []).filter(n => known.has(n));
  if (!form.length) form = sections.map(s => s.name);
  return { ...conductor, beatsPerBar: spb / 4, spb, sections, form };
}

function planText(p, prompt, part) {
  const lines = p.sections.map(s => `${s.name} (${SECTION_WORD[s.name]}, ${s.energy}, ${s.bars.length} bars): | ${s.bars.join(' | ')} |`);
  const instrument = { melody: p.band?.melody, bass: p.band?.bass, drums: p.band?.rhythm, keys: `${p.band?.harmony} and ${p.band?.arpeggio}` }[part];
  return `Request: ${prompt}
Key: ${p.tonic} ${p.mode}, ${p.beatsPerBar}/4, ${p.bpm} BPM.
Sections (each written once) with their chords bar by bar:
${lines.join('\n')}
Form (order played): ${p.form.join(' ')}
Your instrument: ${instrument}.`;
}

// ------------------------------------------------------------------ putting the parts together

/** Pattern tokens written with lengths ("x:4", "0:2", "K:8") as one token per step: the token, then rests. */
function steps(text, drums = false) {
  // Written without spaces ("K.H.S.H.", "x...x...", "0.2.4.2."): one step per character, letters struck together.
  const split = t => /:/.test(t) || t.length < 2 ? [t] : drums ? (/^[KSHA.]+$/.test(t) ? t.match(/[KSHA]+|./g) : [t])
    : /^[0-9x.]+$/.test(t) ? [...t] : [t];
  return tokensOf(text).filter(t => t !== '|').flatMap(split).flatMap(t => {
    const [what, len] = t.split(':');
    const n = Number(len);
    return Number.isInteger(n) && n > 1 ? [what, ...Array(n - 1).fill('.')] : [what];
  }).join(' ');
}

const byName = (answer, name) => (answer?.sections || []).find(s => s.name === name);

/** A player's bars for a section, as exactly the plan's number of bars (the whole section re-cut, ties kept). */
function fitLines(barsText, n, spb) {
  const stream = (barsText || []).join(' ');
  if (!tokensOf(stream).length) return Array(n).fill('');
  const total = stepsOf(stream);
  const padded = total < n * spb ? `${stream} r:${n * spb - total}` : stream;
  return cutBars(padded, spb, n, true);
}

export function merge(p, parts) {
  const spb = p.spb;
  const bassStyle = parts.bass?.style && parts.bass.style !== 'LINE' ? parts.bass.style : 'LINE';
  const sections = p.sections.map(sec => {
    const n = sec.bars.length;
    const melody = fitLines(byName(parts.melody, sec.name)?.bars, n, spb);
    const bass = bassStyle === 'LINE' ? fitLines(byName(parts.bass, sec.name)?.bars, n, spb) : Array(n).fill('');
    const keys = byName(parts.keys, sec.name) || {};
    return { name: sec.name, drums: fitDrums(steps(byName(parts.drums, sec.name)?.drums, true), spb), arp: fitPattern(steps(keys.arp), spb, '.'),
      comp: fitPattern(steps(keys.comp), spb, '.'), bars: sec.bars.map((chords, i) => ({ chords, melody: melody[i] || `r:${spb}`, bass: bass[i] })) };
  });
  return { ...p, bass: bassStyle, sections, form: p.form };
}

/** Which player a remark of the check is for. */
function owner(remark) {
  if (/bass line/i.test(remark)) return 'bass';
  if (/whole song is only/i.test(remark)) return null;
  return 'melody';
}

/**
 * The whole ensemble: conductor, four players at once, merge, check, and one more round for the parts the check
 * finds wrong (each asked on its own). generate(system, contents, schema) -> { json, tokens } and may throw.
 */
export async function composeEnsemble({ generate, prompt, C, keyHint = '', log = () => {} }) {
  let calls = 0, tokens = 0;
  const ask = async (system, contents, schema) => { calls++; const r = await generate(system, contents, schema); tokens += r.tokens || 0; return r.json; };
  const conductor = await ask(CONDUCTOR, [{ role: 'user', parts: [{ text: `요청: ${prompt}${keyHint}` }] }], conductorSchema(C));
  const p = plan(conductor);
  log('plan', p.tonic, p.mode, `${p.beatsPerBar}/4`, p.bpm, p.form.join(' '), p.sections.map(s => `${s.name}${s.bars.length}`).join(' '));
  const threads = {};
  const play = async (part, extra) => {
    threads[part] ??= [{ role: 'user', parts: [{ text: planText(p, prompt, part) }] }];
    if (extra) threads[part].push({ role: 'user', parts: [{ text: extra }] });
    const json = await ask(PLAYERS[part].system, threads[part], PLAYERS[part].schema());
    threads[part].push({ role: 'model', parts: [{ text: JSON.stringify(json) }] });
    return json;
  };
  const parts = {};
  await Promise.all(Object.keys(PLAYERS).map(async part => { parts[part] = await play(part); }));

  const build = () => toScore(merge(p, parts), prompt);
  let score = build();
  let check = C.checkScore({ ...score, title: score.title || 'AI', character: score.character || 'AI' });
  let remarks = check.errors.length ? check.errors.map(e => `${e.field}: ${e.message}`) : critique(score, C);
  log('first', check.errors.length ? 'errors' : 'ok', remarks.map(r => r.slice(0, 50)));
  const firstRemarks = remarks.length;
  if (remarks.length) {
    // Each part hears only the remarks about it; the rest stay as they are.
    const mine = {};
    for (const r of remarks) {
      const who = check.errors.length ? (/bass/.test(r) ? 'bass' : /drums/.test(r) ? 'drums' : /arp|comp/.test(r) ? 'keys' : 'melody') : owner(r);
      if (who) (mine[who] ??= []).push(r);
    }
    await Promise.all(Object.entries(mine).map(async ([part, list]) => {
      parts[part] = await play(part, `A musician checking the whole band says about your part:\n- ${list.join('\n- ')}\nRewrite your part to fix this, following the same plan. Answer with the full JSON.`);
    }));
    const again = build();
    const check2 = C.checkScore({ ...again, title: again.title || 'AI', character: again.character || 'AI' });
    const remarks2 = check2.errors.length ? check2.errors.map(e => `${e.field}: ${e.message}`) : critique(again, C);
    if (!check2.errors.length && (check.errors.length || remarks2.length <= remarks.length)) { score = again; check = check2; remarks = remarks2; }
  }
  if (check.errors.length) return { score: null, problems: remarks, calls, tokens };
  const home = safeBass(homeEnding(agreeChords(fixThreeCount(score, C), C), C), C);
  const final = C.checkScore(home).errors.length ? score : home;
  return { score: final, note: String(conductor.note || ''), form: p.form.join(' '), remarks: remarks.length, firstRemarks, calls, tokens };
}
