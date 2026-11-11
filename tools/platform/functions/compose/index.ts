// Caestro Studio: compose a score from a sentence with Claude.
// A Supabase Edge Function (Deno). The Anthropic API key stays here as a secret (ANTHROPIC_API_KEY); the studio
// calls this with the signed-in user's token. Each result is checked with the same rules as the car
// (docs/studio/core.js, CloudLibrary) and sent back to Claude with the problems until it passes.
import Anthropic from "npm:@anthropic-ai/sdk";
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import * as C from "https://hoyadong1.github.io/Caestro/studio/core.js";

const DAILY_LIMIT = 10;
const MODEL = "claude-opus-5-5";
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const STAGE_KEYS = ["bass", "rhythm", "harmony", "arpeggio", "melody", "high"] as const;
const instrumentEnum = (stage: number) => (C.BUILT_IN[stage] as [string, string][]).map(([id]) => id);

const SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["title", "character", "tonic", "beatsPerBar", "bpm", "tempoRange", "chords", "melody", "bass", "arp", "comp",
    "high", "canonBars", "band", "color", "note"],
  properties: {
    title: { type: "string", description: "Short song title (Korean or English), at most 40 characters" },
    character: { type: "string", description: "One line shown under the title in the car, Korean, at most 60 characters" },
    tonic: { type: "string", enum: ["C", "C#", "Db", "D", "Eb", "E", "F", "F#", "Gb", "G", "Ab", "A", "Bb", "B"] },
    beatsPerBar: { type: "integer", enum: [3, 4] },
    bpm: { type: "integer", description: "Tempo when parked, 40 to 160" },
    tempoRange: { type: "integer", description: "BPM added at 130 km/h, 0 to 40" },
    chords: { type: "string" },
    melody: { type: "string" },
    bass: { type: "string" },
    arp: { type: "string" },
    comp: { type: "string" },
    high: { type: "string", enum: ["BELLS", "OCTAVE", "CANON"] },
    canonBars: { type: "integer", description: "Bars the high stage follows behind with CANON, else 0" },
    band: {
      type: "object",
      additionalProperties: false,
      required: [...STAGE_KEYS],
      properties: Object.fromEntries(STAGE_KEYS.map((k, i) => [k, { type: "string", enum: instrumentEnum(i) }])),
    },
    color: { type: "string", description: "Card color, #RRGGBB" },
    note: { type: "string", description: "One or two Korean sentences to the user about what you made and why" },
  },
};

const SYSTEM = `You compose music for Caestro, a car app in which the car conducts a small band: the speed brings in
six stages one by one (bass always; rhythm from 18 km/h, chords 38, arpeggio 58, melody 82, high 105). You write
ONE score; every stage plays its share of it, and the listener picks the instruments. People listen while driving,
so keep it musical, singable and not busy; let the music grow as stages join.

Write the score in this text notation exactly (it is parsed by a program):
- A step is a sixteenth note. A bar has beatsPerBar*4 steps: 16 in 4/4, 12 in 3/4.
- chords: tokens "SYMBOL:LEN" separated by spaces, LEN in steps; a LEN carries over to the next tokens until changed.
  "|" marks a bar line and must fall exactly on a bar boundary. Symbols: root A-G with optional # or b, then one of
  "" (major), m, 7, maj7, m7, m6, 6, dim, dim7, aug, sus4, and an optional slash bass like F/C. Example 4/4:
  "C:16 | Am:16 | F:8 G7:8 | C:16". 8 to 16 bars in total.
- melody: tokens "NOTE:LEN" (NOTE like C5, F#4, Bb4), "r:LEN" for a rest, "-:LEN" ties onto the previous note.
  LEN carries over like in chords. Bar lines "|" must fall on bar boundaries and the line must end on one; it must not
  be longer than the chords. Keep the melody between G4 and C6 and make it a real tune: motifs that come back,
  phrases of 2 or 4 bars that breathe, chord tones on the strong beats, steps more than leaps, end on the tonic.
- bass: HALVES (chord root every half bar), ROOT_FIFTH (root and fifth by beats) or VOICING, or an explicit line in
  the melody notation (octave 2 or 3) that fills whole bars.
- arp: exactly one bar of tokens (16 in 4/4, 12 in 3/4): a digit 0-4 picks a chord note from low to high, "." rests.
- comp: exactly one bar of tokens: "x" strikes the chord, "." rests.
- high: BELLS (top chord note as bells), OCTAVE (the melody an octave up) or CANON (the melody again canonBars bars
  later; only sounds good when the harmony repeats every canonBars bars or stays on one chord).
- band: one suggested instrument per stage from the allowed ids.

Originality and copyright: write original music. If the request names a song that is still under copyright (modern
pop, K-pop, film or game music, anything recent), do not reproduce or closely imitate its melody: write an original
piece in that mood and say so in the note. Melodies whose copyright has long ended (classical works by composers who
died long ago, traditional folk songs) are fine when you know them exactly.

Answer in Korean in title (or a short English title), character and note.`;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

function toScore(out: Record<string, any>) {
  return {
    id: "ai-song", title: String(out.title).slice(0, 40), character: String(out.character).slice(0, 60), composer: "카에스트로 AI 작곡",
    creator: "", license: "직접 작곡 · CC BY 4.0", color: /^#[0-9a-fA-F]{6}$/.test(out.color) ? out.color : "#9AD7FF",
    bpm: out.bpm, tempoRange: out.tempoRange, beatsPerBar: out.beatsPerBar, tonic: out.tonic, chords: out.chords, voicings: "",
    melody: out.melody, bass: out.bass, arp: out.arp, comp: out.comp, high: out.high, canonBars: out.high === "CANON" ? out.canonBars : 0,
    defaults: STAGE_KEYS.map(k => out.band[k]),
  };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  // Signed-in people only, and a few songs a day each (admins are not limited).
  const url = Deno.env.get("SUPABASE_URL")!;
  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await admin.auth.getUser(token);
  const user = who?.user;
  if (!user) return json({ error: "로그인해 주세요" }, 401);
  const { data: profile } = await admin.from("profiles").select("is_admin").eq("id", user.id).maybeSingle();
  const since = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const { count } = await admin.from("ai_requests").select("id", { count: "exact", head: true }).eq("user_id", user.id).gte("created_at", since);
  const used = count ?? 0;
  if (!profile?.is_admin && used >= DAILY_LIMIT) return json({ error: `오늘은 AI 작곡을 ${DAILY_LIMIT}번 모두 썼어요. 내일 다시 해 주세요.`, remaining: 0 }, 429);

  let prompt = "";
  try { prompt = String((await req.json()).prompt ?? "").trim().slice(0, 500); } catch { /* empty */ }
  if (prompt.length < 2) return json({ error: "어떤 곡을 원하는지 적어 주세요" }, 400);

  const client = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY")! });
  const messages: Anthropic.Beta.Messages.BetaMessageParam[] = [{ role: "user", content: `요청: ${prompt}` }];
  let tokens = 0;
  let result: { score: ReturnType<typeof toScore>; note: string } | null = null;
  let lastProblems = "";
  try {
    for (let attempt = 0; attempt < 3 && !result; attempt++) {
      const response = await client.beta.messages.create({
        model: MODEL,
        max_tokens: 16000,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        system: SYSTEM,
        output_config: { effort: "medium", format: { type: "json_schema", schema: SCHEMA } },
        messages,
      } as any);
      tokens += (response.usage?.input_tokens ?? 0) + (response.usage?.output_tokens ?? 0);
      if (response.stop_reason === "refusal") return json({ error: "이 요청으로는 곡을 만들 수 없어요. 다르게 적어 주세요." }, 422);
      const text = response.content.filter((b: any) => b.type === "text").map((b: any) => b.text).join("");
      let out: Record<string, any>;
      try { out = JSON.parse(text); } catch { lastProblems = "the answer was not valid JSON"; messages.push({ role: "assistant", content: response.content as any }, { role: "user", content: "Your answer was not valid JSON. Answer again with the full JSON." }); continue; }
      const score = toScore(out);
      const check = C.checkScore({ ...score, title: score.title || "AI", character: score.character || "AI" });
      if (!check.errors.length) { result = { score, note: String(out.note ?? "") }; break; }
      lastProblems = check.errors.map((e: any) => `${e.field}: ${e.message}`).join("\n");
      messages.push({ role: "assistant", content: response.content as any },
        { role: "user", content: `The car's checker rejected the score:\n${lastProblems}\nFix these and answer again with the full JSON.` });
    }
  } catch (err) {
    if (err instanceof Anthropic.RateLimitError) return json({ error: "AI가 지금 바빠요. 잠시 뒤에 다시 해 주세요." }, 503);
    if (err instanceof Anthropic.APIError) return json({ error: `AI 연결 오류 (${err.status})` }, 502);
    return json({ error: "AI 연결 오류" }, 502);
  }
  await admin.from("ai_requests").insert({ user_id: user.id, prompt, ok: !!result, tokens });
  if (!result) return json({ error: "악보를 차 규칙에 맞추지 못했어요. 다시 해 주세요.", detail: lastProblems }, 422);
  return json({ ...result, remaining: profile?.is_admin ? null : DAILY_LIMIT - used - 1 });
});
