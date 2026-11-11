// Caestro Studio: compose a score from a sentence with Gemini (free tier).
// A Supabase Edge Function (Deno). The Gemini API key stays here as a secret (GEMINI_API_KEY); the studio calls
// this with the signed-in user's token. Each result is checked with the same rules as the car (docs/studio/core.js,
// CloudLibrary) and sent back to the model with the problems until it passes.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import * as C from "./core.js"; // copied from docs/studio/core.js at deploy time
import { SYSTEM, agreeChords, critique, fixThreeCount, formText, homeEnding, requestText, safeBass, schema, toScore } from "./shared.js";

const DAILY_LIMIT = 10;
// Tried in order: the next one is used when a model is busy or not available to this key.
const MODELS = ["gemini-3.1-flash-lite", "gemini-3.5-flash"];
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const SCHEMA = schema(C);

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

type Part = { text: string };
type Turn = { role: "user" | "model"; parts: Part[] };

/** One Gemini answer as text, trying the next model when one is busy (429/503) or missing (404). */
async function generate(key: string, contents: Turn[]) {
  let last = "";
  for (const model of MODELS) {
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: "POST",
      signal: AbortSignal.timeout(60000),
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYSTEM }] },
        contents,
        generationConfig: { responseMimeType: "application/json", responseJsonSchema: SCHEMA },
      }),
    }).catch((e) => ({ ok: false, status: 504, json: async () => ({ error: { message: String(e) } }) }) as unknown as Response);
    const d = await r.json().catch(() => ({}));
    if (r.ok) {
      const candidate = d.candidates?.[0];
      if (candidate?.finishReason === "SAFETY" || d.promptFeedback?.blockReason) return { blocked: true, text: "", tokens: 0 };
      const text = (candidate?.content?.parts ?? []).map((p: Part) => p.text ?? "").join("");
      return { blocked: false, text, tokens: d.usageMetadata?.totalTokenCount ?? 0 };
    }
    last = `${model} ${r.status}`;
    if (![404, 429, 500, 503, 504].includes(r.status)) break;
  }
  throw new Error(last);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  // Signed-in people only, and a few songs a day each (admins are not limited).
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await admin.auth.getUser(token);
  const user = who?.user;
  if (!user) return json({ error: "로그인해 주세요" }, 401);
  const { data: profile } = await admin.from("profiles").select("is_admin").eq("id", user.id).maybeSingle();
  const since = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const { count } = await admin.from("ai_requests").select("id", { count: "exact", head: true })
    .eq("user_id", user.id).gte("created_at", since);
  const used = count ?? 0;
  if (!profile?.is_admin && used >= DAILY_LIMIT) {
    return json({ error: `오늘은 AI 작곡을 ${DAILY_LIMIT}번 모두 썼어요. 내일 다시 해 주세요.`, remaining: 0 }, 429);
  }

  let prompt = "";
  try { prompt = String((await req.json()).prompt ?? "").trim().slice(0, 500); } catch { /* empty */ }
  if (prompt.length < 2) return json({ error: "어떤 곡을 원하는지 적어 주세요" }, 400);

  const key = Deno.env.get("GEMINI_API_KEY")!;
  const contents: Turn[] = [{ role: "user", parts: [{ text: requestText(prompt) }] }];
  let tokens = 0;
  let result: { score: ReturnType<typeof toScore>; note: string; form: string } | null = null;
  let lastProblems = "";
  // The best score that passed the car's checker so far, with how many musical remarks it still has. A passing score
  // with remarks (too even a rhythm, few chord tones on strong beats, no breathing) gets one more round.
  let best: { score: ReturnType<typeof toScore>; note: string; form: string; remarks: number } | null = null;
  let critiqued = 0;
  const passes = (s: ReturnType<typeof toScore>) => !C.checkScore({ ...s, title: s.title || "AI", character: s.character || "AI" }).errors.length;
  try {
    for (let attempt = 0; attempt < 4; attempt++) {
      const answer = await generate(key, contents);
      tokens += answer.tokens;
      if (answer.blocked) return json({ error: "이 요청으로는 곡을 만들 수 없어요. 다르게 적어 주세요." }, 422);
      contents.push({ role: "model", parts: [{ text: answer.text }] });
      let out: Record<string, unknown>;
      try { out = JSON.parse(answer.text); } catch {
        lastProblems = "the answer was not valid JSON";
        contents.push({ role: "user", parts: [{ text: "Your answer was not valid JSON. Answer again with the full JSON." }] });
        continue;
      }
      const score = toScore(out, prompt);
      const check = C.checkScore({ ...score, title: score.title || "AI", character: score.character || "AI" });
      if (check.errors.length) {
        lastProblems = check.errors.map((e: { field: string; message: string }) => `${e.field}: ${e.message}`).join("\n");
        contents.push({ role: "user", parts: [{ text: `The car's checker rejected the score:\n${lastProblems}\nFix these and answer again with the full JSON.` }] });
        continue;
      }
      const remarks = critique(score, C);
      if (!best || remarks.length < best.remarks) best = { score, note: String(out.note ?? ""), form: formText(out), remarks: remarks.length };
      if (!remarks.length || critiqued >= 2 || (critiqued === 1 && remarks.length < 2)) break;
      critiqued++;
      contents.push({ role: "user", parts: [{ text: `It plays, but a musician listening says:\n- ${remarks.join("\n- ")}\nRewrite the melody (and chords if needed) to fix this, keeping the mood. Answer again with the full JSON.` }] });
    }
  } catch (err) {
    // A busy model after a usable score: keep what we have.
    if (!best) {
      const busy = /\b(429|503|504)\b/.test(String((err as Error).message));
      return json({ error: busy ? "AI가 지금 바빠요. 잠시 뒤에 다시 해 주세요." : "AI 연결 오류" }, busy ? 503 : 502);
    }
  }
  if (best) {
    const home = safeBass(homeEnding(agreeChords(fixThreeCount(best.score, C), C), C), C);
    result = { score: passes(home) ? home : best.score, note: best.note, form: best.form };
  }
  await admin.from("ai_requests").insert({ user_id: user.id, prompt, ok: !!result, tokens });
  if (!result) return json({ error: "악보를 차 규칙에 맞추지 못했어요. 다시 해 주세요.", detail: lastProblems }, 422);
  return json({ ...result, remaining: profile?.is_admin ? null : DAILY_LIMIT - used - 1 });
});
