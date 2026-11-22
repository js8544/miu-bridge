/**
 * Miu Bridge relay.
 *
 * A phone registers itself and gets a device id and key, with no account; it then keeps one outbound
 * WebSocket to that device's Durable Object. Accounts (D1) remain for phones that signed in. MCP clients (Muse, Claude
 * Code, …) POST to the device's /mcp URL and the object forwards each request over the socket and
 * returns the phone's answer.
 *
 * The relay is a pipe for MCP traffic: it keeps no history and does not check the MCP bearer token —
 * the phone does. It checks that the socket for a device id is opened with that device's key.
 *
 * A request may come sealed: a JSON body with "miu_e2e": 1, encrypted between the agent and the phone
 * with keys made from the token (the app's server/E2e.kt describes it). The relay passes it on as it
 * is and can read neither it nor the answer. It carries no Authorization header: the token never
 * comes here.
 *
 * Remote use is metered: each tools/call the phone answers counts once against the phone's monthly
 * allowance (the account's, for a phone that has one; PLAN_LIMITS), whatever the call does on the phone; a phone_script with many steps is one
 * call. Agents on the phone's own network talk to it directly and are not metered. A call that the
 * phone rejects (a wrong token) does not count, so knowing a device id is not enough to use up an
 * account's allowance. The relay cannot tell what a sealed request is, so the phone says in its
 * answer whether it was a tool call it answered.
 *
 *   POST   /api/auth/send         {email}                      emails a 6-digit code
 *   POST   /api/auth/verify       {email, code}                -> {token, email} (creates the account on first sign-in)
 *   POST   /api/logout            (session)
 *   GET    /api/me                (session)                    -> {email, devices: [...], usage: {month, used, limit}}
 *   POST   /api/phones            {name, hardware}             -> {deviceId, deviceKey, mcpUrl} (no account)
 *   POST   /api/devices           (session) {name, deviceId?}  -> {deviceId, deviceKey, mcpUrl}
 *   DELETE /api/devices/<id>      (session)
 *   POST   /api/trace             {install, app, sdk, brand, events: [...]}  opt-in usage metadata (no account)
 *   POST   /api/feedback          {install, app, sdk, brand, notes: [...]}   opt-in notes agents wrote (no account)
 *   DELETE /api/trace/<install>   forget everything sent under that install id (events and notes)
 *   GET    /sdk/                  the client SDK and its guide (miu_bridge.py, miu-bridge.mjs)
 *   wss    /d/<deviceId>/connect  phone, header `X-Device-Key`; `?k=<seconds>` asks for that keepalive interval
 *   GET    /d/<deviceId>/usage    phone, header `X-Device-Key`  -> {month, used, limit}
 *   https  /d/<deviceId>/mcp      MCP clients, header `Authorization: Bearer <token>` (checked by the phone)
 */

// @ts-expect-error imported as text (see wrangler.toml rules)
import sdkPython from "../../sdk/miu_bridge.py";
// @ts-expect-error imported as text
import sdkJs from "../../sdk/miu-bridge.mjs";
// @ts-expect-error imported as text
import sdkReadme from "../../sdk/README.md";

/** The client SDK, for an agent to fetch into its own sandbox. Public: it holds no secrets. */
const SDK: Record<string, [string, string]> = {
  "/sdk": [sdkReadme, "text/markdown; charset=utf-8"],
  "/sdk/": [sdkReadme, "text/markdown; charset=utf-8"],
  "/sdk/README.md": [sdkReadme, "text/markdown; charset=utf-8"],
  "/sdk/miu_bridge.py": [sdkPython, "text/x-python; charset=utf-8"],
  "/sdk/miu-bridge.mjs": [sdkJs, "text/javascript; charset=utf-8"],
};

export interface Env {
  PHONE: DurableObjectNamespace;
  DB: D1Database;
  EMAIL: SendEmail;
  SEND_LIMIT: RateLimit;
  REGISTER_LIMIT: RateLimit;
  TRACE_LIMIT: RateLimit;
  MAIL_FROM: string;
}

const HOSTED_ORIGIN = "https://phone.jinshang.ai";
const MAX_PENDING = 32; // requests waiting on one phone; it runs one at a time
const DEVICE_ID = /^[A-Za-z0-9_-]{22,64}$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const REQUEST_TIMEOUT_MS = 10 * 60_000; // tool calls can wait for the owner (handoff)
const MAX_BODY = 1 << 20;
const SESSION_TTL_MS = 90 * 24 * 3600_000;
const MAX_DEVICES = 10;
const MAX_NEW_PHONES_PER_DAY = 30; // from one address; carrier networks put many owners behind one
const HARDWARE_ID = /^[A-Za-z0-9_-]{32,64}$/;
const CODE_TTL_MS = 10 * 60_000;
const RESEND_COOLDOWN_MS = 60_000;
const MAX_SENDS_PER_HOUR = 5;
const MAX_CODE_ATTEMPTS = 5;
/** Remote tool calls per calendar month (UTC), by plan; null is unlimited. */
const MAX_TRACE_EVENTS = 200; // per request
const MAX_FEEDBACK_NOTES = 20; // per request
const MAX_FEEDBACK_CHARS = 1000; // per field
const MAX_FEEDBACK_RECENT = 5; // trace events attached to a note
const INSTALL_ID = /^[A-Za-z0-9-]{16,64}$/;
const SELECTORS = new Set(["ref", "text", "id", "role", "xy"]);
const PLAN_LIMITS: Record<string, number | null> = { free: 1000, paid: null };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname.startsWith("/api/")) return await api(request, env, url);
      const m = url.pathname.match(/^\/d\/([^/]+)\/(connect|mcp|usage)$/);
      if (m && DEVICE_ID.test(m[1])) return await device(request, env, m[1], m[2] as "connect" | "mcp" | "usage");
      const file = SDK[url.pathname];
      // The SDK guide names the hosted relay; a self-hosted relay serves it with its own address.
      if (file && request.method === "GET") return new Response(file[0].replaceAll(HOSTED_ORIGIN, url.origin), { headers: { "Content-Type": file[1], "Cache-Control": "public, max-age=300" } });
      if (url.pathname === "/") return new Response("Miu Bridge relay\n");
      return json(404, { error: "not found" });
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.message, code: e.code });
      console.error(e);
      return json(500, { error: "Internal error", code: "internal" });
    }
  },
};

// ---- accounts ----

async function api(request: Request, env: Env, url: URL): Promise<Response> {
  const route = `${request.method} ${url.pathname}`;
  switch (route) {
    case "POST /api/auth/send": {
      const email = emailOf(await readJson(request));
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      if (!(await env.SEND_LIMIT.limit({ key: ip })).success) throw new HttpError(429, "rate_limited", "Too many requests");
      await sendCode(env, email);
      return json(200, {});
    }
    case "POST /api/auth/verify": {
      const body = await readJson(request);
      const email = emailOf(body);
      const code = String(body.code ?? "").replace(/\D/g, "");
      const row = await env.DB.prepare("SELECT code_hash, expires_at, attempts FROM email_codes WHERE email = ?")
        .bind(email).first<{ code_hash: string; expires_at: number; attempts: number }>();
      if (!row || row.expires_at < Date.now() || row.attempts >= MAX_CODE_ATTEMPTS) {
        throw new HttpError(401, "code_expired", "The code has expired");
      }
      if (code.length !== 6 || !timingSafeEqual(await sha256(`${email}:${code}`), row.code_hash)) {
        await env.DB.prepare("UPDATE email_codes SET attempts = attempts + 1 WHERE email = ?").bind(email).run();
        throw new HttpError(401, "code_wrong", "Wrong code");
      }
      // The code is not deleted on success: it stays good until it expires. A phone on a slow network can
      // lose this answer after the work is done; it must be able to send the same code again and get in.
      // First sign-in creates the account. password_* are unused since sign-in moved to email codes.
      await env.DB.prepare(
        "INSERT INTO users (id, email, password_hash, password_salt, created_at) VALUES (?, ?, '', '', ?) ON CONFLICT(email) DO NOTHING",
      ).bind(crypto.randomUUID(), email, Date.now()).run();
      const user = await env.DB.prepare("SELECT id FROM users WHERE email = ?").bind(email).first<{ id: string }>();
      return json(200, { token: await newSession(env, user!.id), email });
    }
    case "POST /api/logout": {
      const token = bearer(request);
      if (token) await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256(token)).run();
      return json(200, {});
    }
    case "GET /api/me": {
      const user = await authed(request, env);
      const { results } = await env.DB.prepare(
        "SELECT id, name, created_at, last_seen_at FROM devices WHERE user_id = ? ORDER BY created_at",
      ).bind(user.id).all();
      return json(200, { email: user.email, devices: results, usage: await usageOf(env, user.id) });
    }
    case "POST /api/phones": {
      // No account: the phone names itself by an id only this app on that phone knows. Registering
      // again (a reinstall, cleared data) gives the same device a new key and keeps its URL and its
      // count for the month, so starting over does not start the allowance over.
      const body = await readJson(request);
      const hardware = String(body.hardware ?? "");
      if (!HARDWARE_ID.test(hardware)) throw new HttpError(400, "bad_request", "Bad request");
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      if (!(await env.REGISTER_LIMIT.limit({ key: ip })).success) throw new HttpError(429, "rate_limited", "Too many requests");
      const name = String(body.name ?? "").trim().slice(0, 60) || "Phone";
      const key = randomB64(32);
      const keyHash = await sha256(key);
      const hardwareHash = await sha256(`hw:${hardware}`);
      const known = await env.DB.prepare("SELECT id FROM devices WHERE hardware_hash = ?").bind(hardwareHash).first<string>("id");
      if (known) {
        await env.DB.prepare("UPDATE devices SET key_hash = ?, name = ? WHERE id = ?").bind(keyHash, name, known).run();
        await kick(env, known);
        return json(200, { deviceId: known, deviceKey: key, mcpUrl: mcpUrl(request, known) });
      }
      const origin = await sha256(`ip:${ip}:${new Date().toISOString().slice(0, 10)}`);
      const today = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE origin = ?").bind(origin).first<number>("n");
      if ((today ?? 0) >= MAX_NEW_PHONES_PER_DAY) throw new HttpError(429, "rate_limited", "Too many requests");
      const id = randomB64(18);
      await env.DB.prepare("INSERT INTO devices (id, name, key_hash, created_at, hardware_hash, origin) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(id, name, keyHash, Date.now(), hardwareHash, origin).run();
      return json(200, { deviceId: id, deviceKey: key, mcpUrl: mcpUrl(request, id) });
    }
    case "POST /api/devices": {
      const user = await authed(request, env);
      const body = await readJson(request);
      const name = String(body.name ?? "").trim().slice(0, 60) || "Phone";
      const key = randomB64(32);
      const keyHash = await sha256(key);
      // Signing in again on the same phone keeps its id, so the MCP URL given to agents stays the same.
      const wanted = typeof body.deviceId === "string" && DEVICE_ID.test(body.deviceId) ? body.deviceId : null;
      if (wanted) {
        const res = await env.DB.prepare("UPDATE devices SET key_hash = ?, name = ? WHERE id = ? AND user_id = ?")
          .bind(keyHash, name, wanted, user.id).run();
        if (res.meta.changes) {
          await kick(env, wanted);
          return json(200, { deviceId: wanted, deviceKey: key, mcpUrl: mcpUrl(request, wanted) });
        }
      }
      const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(user.id).first<number>("n");
      if ((count ?? 0) >= MAX_DEVICES) throw new HttpError(409, "device_limit", `An account can have at most ${MAX_DEVICES} phones`);
      const id = randomB64(18);
      await env.DB.prepare("INSERT INTO devices (id, user_id, name, key_hash, created_at) VALUES (?, ?, ?, ?, ?)")
        .bind(id, user.id, name, keyHash, Date.now()).run();
      return json(200, { deviceId: id, deviceKey: key, mcpUrl: mcpUrl(request, id) });
    }
  }
  if (route === "POST /api/trace") return await trace(request, env);
  if (route === "POST /api/feedback") return await feedback(request, env);
  const forget = url.pathname.match(/^\/api\/trace\/([^/]+)$/);
  if (forget && request.method === "DELETE") {
    if (!INSTALL_ID.test(forget[1])) throw new HttpError(400, "bad_request", "Bad request");
    await env.DB.batch([
      env.DB.prepare("DELETE FROM trace_events WHERE install_id = ?").bind(forget[1]),
      env.DB.prepare("DELETE FROM agent_feedback WHERE install_id = ?").bind(forget[1]),
    ]);
    return json(200, {});
  }
  const del = url.pathname.match(/^\/api\/devices\/([^/]+)$/);
  if (request.method === "DELETE" && del) {
    const user = await authed(request, env);
    const res = await env.DB.prepare("DELETE FROM devices WHERE id = ? AND user_id = ?").bind(del[1], user.id).run();
    if (!res.meta.changes) throw new HttpError(404, "not_found", "No such phone");
    await kick(env, del[1]);
    return json(200, {});
  }
  throw new HttpError(404, "not_found", "Not found");
}

function emailOf(body: Record<string, unknown>): string {
  const email = String(body.email ?? "").trim().toLowerCase();
  if (!EMAIL.test(email) || email.length > 200) throw new HttpError(400, "bad_email", "That email is not valid");
  return email;
}

/** Emails a fresh 6-digit code, replacing any earlier one. Limited per email: a cooldown and an hourly cap. */
async function sendCode(env: Env, email: string): Promise<void> {
  const now = Date.now();
  const prev = await env.DB.prepare("SELECT sent_at, window_start, window_sends FROM email_codes WHERE email = ?")
    .bind(email).first<{ sent_at: number; window_start: number; window_sends: number }>();
  if (prev && now - prev.sent_at < RESEND_COOLDOWN_MS) throw new HttpError(429, "code_recent", "A code was just sent");
  const fresh = !prev || now - prev.window_start > 3600_000;
  if (!fresh && prev!.window_sends >= MAX_SENDS_PER_HOUR) throw new HttpError(429, "too_many_codes", "Too many codes for this email");

  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
  await env.DB.prepare(
    `INSERT INTO email_codes (email, code_hash, expires_at, attempts, sent_at, window_start, window_sends)
     VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6)
     ON CONFLICT(email) DO UPDATE SET code_hash = ?2, expires_at = ?3, attempts = 0, sent_at = ?4, window_start = ?5, window_sends = ?6`,
  ).bind(email, await sha256(`${email}:${code}`), now + CODE_TTL_MS, now, fresh ? now : prev!.window_start, fresh ? 1 : prev!.window_sends + 1).run();

  try {
    await env.EMAIL.send({
      from: { email: env.MAIL_FROM, name: "Miu Bridge" },
      to: email,
      subject: `${code} is your Miu Bridge code · 验证码`,
      text: `Your Miu Bridge sign-in code is ${code}. It is valid for 10 minutes.\nIf you did not ask for it, ignore this email.\n\n你的 Miu Bridge 登录验证码是 ${code}，10 分钟内有效。\n如果不是你本人在登录，请忽略这封邮件。`,
    });
  } catch (e) {
    console.error("send code failed", (e as { code?: string }).code);
    await env.DB.prepare("DELETE FROM email_codes WHERE email = ?").bind(email).run();
    throw new HttpError(502, "send_failed", "The email could not be sent");
  }
}

async function newSession(env: Env, userId: string): Promise<string> {
  const token = "pbs_" + randomB64(32);
  const now = Date.now();
  await env.DB.prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
    .bind(await sha256(token), userId, now, now + SESSION_TTL_MS).run();
  return token;
}

async function authed(request: Request, env: Env): Promise<{ id: string; email: string }> {
  const token = bearer(request);
  if (!token) throw new HttpError(401, "login_required", "Sign in first");
  const user = await env.DB.prepare(
    "SELECT u.id, u.email FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?",
  ).bind(await sha256(token), Date.now()).first<{ id: string; email: string }>();
  if (!user) throw new HttpError(401, "session_expired", "The sign-in has expired");
  return user;
}

function mcpUrl(request: Request, id: string): string {
  return `${new URL(request.url).origin}/d/${id}/mcp`;
}

/** Drop the device's current socket, e.g. after its key changed or it was removed. */
async function kick(env: Env, id: string): Promise<void> {
  await env.PHONE.get(env.PHONE.idFromName(id)).fetch("https://relay/kick", { method: "POST" });
}

// ---- experience improvement program ----

/**
 * Stores a batch of opt-in usage events. Anyone can post here (it carries no account), so every
 * field is checked against a narrow shape and anything else is dropped: the table can only ever hold
 * tool names, app package names, codes and numbers.
 */
async function trace(request: Request, env: Env): Promise<Response> {
  const { body, install, app, sdk, brand } = await programPost(request, env, "events");
  const now = Date.now();
  const rows = (body.events as unknown[]).slice(0, MAX_TRACE_EVENTS).flatMap((v) => {
    const e = traceEvent(v);
    if (!e) return [];
    return [env.DB.prepare(
      "INSERT INTO trace_events (install_id, at, tool, pkg, ok, code, ms, sel, lines, app, sdk, brand, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(install, e.t, e.tool, e.pkg, e.ok ? 1 : 0, e.code, e.ms, e.sel, e.lines, app, sdk, brand, now)];
  });
  if (rows.length) await env.DB.batch(rows);
  return json(200, { stored: rows.length });
}

/**
 * Stores notes agents wrote when a tool did not behave as described. Like trace events they carry no
 * account; unlike them they hold the agent's own words, bounded in length, which the owner can read
 * in the app before and after they are sent. The trace events attached to a note are checked as above.
 */
async function feedback(request: Request, env: Env): Promise<Response> {
  const { body, install, app, sdk, brand } = await programPost(request, env, "notes");
  const now = Date.now();
  const text = (v: unknown) => typeof v === "string" && v.trim() ? v.trim().slice(0, MAX_FEEDBACK_CHARS) : null;
  const rows = (body.notes as unknown[]).slice(0, MAX_FEEDBACK_NOTES).flatMap((v) => {
    const n = (v ?? {}) as Record<string, unknown>;
    const at = Number(n.t), expected = text(n.expected), happened = text(n.happened);
    if (!Number.isInteger(at) || !expected || !happened) return [];
    const recent = (Array.isArray(n.recent) ? n.recent : []).slice(0, MAX_FEEDBACK_RECENT).map(traceEvent).filter(Boolean);
    return [env.DB.prepare(
      "INSERT INTO agent_feedback (install_id, at, expected, happened, recent, app, sdk, brand, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(install, at, expected, happened, JSON.stringify(recent), app, sdk, brand, now)];
  });
  if (rows.length) await env.DB.batch(rows);
  return json(200, { stored: rows.length });
}

/** The rate limit and the fields every experience-program post shares; `list` names its array. */
async function programPost(request: Request, env: Env, list: string) {
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.TRACE_LIMIT.limit({ key: ip })).success) throw new HttpError(429, "rate_limited", "Too many requests");
  const body = await readJson(request);
  const install = String(body.install ?? "");
  const app = String(body.app ?? "");
  const sdk = Number(body.sdk);
  const brand = String(body.brand ?? "");
  if (!INSTALL_ID.test(install) || !/^[0-9A-Za-z.+-]{1,32}$/.test(app) || !Number.isInteger(sdk) || sdk < 1 || sdk > 200 ||
      !/^[0-9A-Za-z ._-]{1,40}$/.test(brand) || !Array.isArray(body[list])) {
    throw new HttpError(400, "bad_request", "Bad request");
  }
  return { body, install, app, sdk, brand };
}

/** One trace event reduced to its allowed fields, or null when its required ones are malformed. */
function traceEvent(v: unknown) {
  const e = (v ?? {}) as Record<string, unknown>;
  const tool = String(e.tool ?? "");
  const t = Number(e.t), ms = Number(e.ms);
  if (!/^phone_[a-z_]{1,30}$/.test(tool) || !Number.isInteger(t) || !Number.isInteger(ms) || ms < 0 || ms > 3_600_000) return null;
  return {
    t, tool, ms, ok: e.ok === true,
    pkg: typeof e.pkg === "string" && /^[A-Za-z0-9._]{1,150}$/.test(e.pkg) ? e.pkg : null,
    code: typeof e.code === "string" && /^[a-z_]{1,40}$/.test(e.code) ? e.code : null,
    sel: typeof e.sel === "string" && SELECTORS.has(e.sel) ? e.sel : null,
    lines: Number.isInteger(e.lines) && (e.lines as number) >= 0 && (e.lines as number) < 100_000 ? e.lines as number : null,
  };
}

// ---- device traffic ----

async function device(request: Request, env: Env, id: string, kind: "connect" | "mcp" | "usage"): Promise<Response> {
  const phone = env.PHONE.get(env.PHONE.idFromName(id));
  if (kind !== "mcp") {
    const row = await env.DB.prepare("SELECT key_hash, user_id FROM devices WHERE id = ?").bind(id).first<{ key_hash: string; user_id: string | null }>();
    if (!row) return json(404, { error: "unknown device" });
    const key = request.headers.get("X-Device-Key") ?? "";
    if (!key || !timingSafeEqual(await sha256(key), row.key_hash)) return json(403, { error: "device key mismatch" });
    // Whose allowance this phone uses: its account's, or its own.
    const owner = row.user_id ?? `d:${id}`;
    if (kind === "usage") return json(200, await usageOf(env, owner));
    await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(Date.now(), id).run();
    // The phone's object remembers whose phone this is, so a tool call never waits on the database.
    const attach = new Request("https://relay/connect", request);
    attach.headers.set("X-Relay-User", owner);
    attach.headers.set("X-Relay-Keepalive", new URL(request.url).searchParams.get("k") ?? "");
    attach.headers.set("X-Relay-Colo", String((request as { cf?: { colo?: string } }).cf?.colo ?? ""));
    return phone.fetch(attach);
  }
  // A tool call goes straight to the phone's object: it knows the owner and the allowance from the
  // phone's own connection. An id that is not a connected phone is simply offline.
  const start = Date.now();
  const answer = await phone.fetch(new Request("https://relay/mcp", { method: request.method, headers: request.headers, body: request.method === "POST" ? await request.text() : undefined }));
  // Where the time went, for whoever is tracing a slow call: this entry point, the hop to the phone's
  // object, that object's own work, the link to the phone, and the phone. Durations and places only.
  const inside = answer.headers.get("Server-Timing");
  const traced = new Response(answer.body, answer);
  const colo = String((request as { cf?: { colo?: string } }).cf?.colo ?? "");
  const hop = Math.max(0, Date.now() - start - Number(answer.headers.get("X-Relay-Ms") ?? 0));
  traced.headers.delete("X-Relay-Ms");
  traced.headers.set("Server-Timing", [`edge;desc="${colo}";dur=${hop}`, inside].filter(Boolean).join(", "));
  return traced;
}

/** Whether a body is a sealed request (not the hello that opens a session, which is never counted). */
function sealedRequest(body: string): boolean {
  if (!body.includes('"miu_e2e"')) return false;
  try {
    const v = JSON.parse(body);
    return Boolean(v) && typeof v === "object" && v.miu_e2e === 1 && !v.hello;
  } catch {
    return false;
  }
}

/** Over the allowance, for a sealed request: the relay cannot write the answer the agent expects, so it says so beside it. */
function quotaExceededSealed(usage: Usage): Response {
  return json(429, {
    error: `This phone has used its ${usage.limit} remote tool calls for ${usage.month}. Agents on the phone's own Wi-Fi are not limited.`,
    code: "quota_exceeded", used: usage.used, limit: usage.limit, month: usage.month,
  });
}

/** The JSON-RPC id of a tools/call request, or null for anything else. */
function toolCall(body: string): { id: unknown } | null {
  if (!body.includes("tools/call")) return null;
  try {
    const v = JSON.parse(body);
    return v && typeof v === "object" && v.method === "tools/call" ? { id: v.id ?? null } : null;
  } catch {
    return null;
  }
}

type Usage = { month: string; used: number; limit: number | null };

/** What [owner] has used this month: an account id, or `d:<deviceId>` for a phone without one. */
async function usageOf(env: Env, owner: string): Promise<Usage> {
  const m = month();
  if (owner.startsWith("d:")) {
    const used = await env.DB.prepare("SELECT calls FROM device_usage WHERE device_id = ? AND month = ?").bind(owner.slice(2), m).first<number>("calls");
    return { month: m, used: used ?? 0, limit: PLAN_LIMITS.free };
  }
  const userId = owner;
  const row = await env.DB.prepare(
    "SELECT u.plan AS plan, COALESCE(g.calls, 0) AS used FROM users u LEFT JOIN usage g ON g.user_id = u.id AND g.month = ?2 WHERE u.id = ?1",
  ).bind(userId, m).first<{ plan: string; used: number }>();
  const plan = row?.plan ?? "free";
  return { month: m, used: row?.used ?? 0, limit: plan in PLAN_LIMITS ? PLAN_LIMITS[plan] : PLAN_LIMITS.free };
}

function month(now = new Date()): string {
  return now.toISOString().slice(0, 7);
}

/**
 * Over the allowance: answered as a failed tool call, in the same shape the phone uses for its own
 * errors, so a model reads the reason and an SDK gets the code, instead of a transport error.
 */
function quotaExceeded(id: unknown, usage: Usage): Response {
  const message = `This phone has used its ${usage.limit} remote tool calls for ${usage.month}. ` +
    "Agents on the phone's own Wi-Fi are not limited; remote use continues next month or after the owner upgrades.";
  return json(200, {
    jsonrpc: "2.0",
    id,
    result: {
      isError: true,
      content: [{ type: "text", text: `${message} [code: quota_exceeded]` }],
      structuredContent: { code: "quota_exceeded", message, used: usage.used, limit: usage.limit, month: usage.month },
    },
  });
}

type Pending = { resolve: (r: Response) => void; timer: ReturnType<typeof setTimeout> };
type CachedUsage = Usage & { at: number };
const USAGE_REFRESH_MS = 5 * 60_000;
// A quiet link was measured to die, silently, between 240 and 260 s on a home line; this leaves half of that.
const KEEPALIVE_MS = 120_000;
/** A phone may ask for another keepalive interval, to find the longest its network lets a quiet link live. */
const KEEPALIVE_RANGE_S = [30, 900];

/** One per device. Only the Worker reaches it, after checking the device exists (and its key on connect). */
export class Phone implements DurableObject {
  private pending = new Map<string, Pending>();

  constructor(private state: DurableObjectState, private env: Env) {
    // Answer the phone's keepalive pings without waking the object.
    state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    switch (new URL(request.url).pathname) {
      case "/connect": return this.attach(request);
      case "/mcp": return this.forward(request);
      case "/kick":
        for (const ws of this.state.getWebSockets()) ws.close(4001, "device key changed");
        return new Response(null, { status: 204 });
    }
    return new Response("not found", { status: 404 });
  }

  private attach(request: Request): Response {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    for (const old of this.state.getWebSockets()) old.close(4000, "replaced by a newer connection");
    const pair = new WebSocketPair();
    this.state.acceptWebSocket(pair[1]);
    // Set by the Worker after it checked the device key. A new connection also drops the cached
    // allowance, so a plan change or another phone's use shows at the latest on the next reconnect.
    const owner = request.headers.get("X-Relay-User");
    if (owner) this.state.waitUntil(Promise.all([this.state.storage.put("owner", owner), this.state.storage.delete("usage")]));
    // From here on the relay keeps the link open, so the phone can sleep between requests.
    const asked = Number(request.headers.get("X-Relay-Keepalive") || 0);
    const every = asked ? Math.min(Math.max(asked, KEEPALIVE_RANGE_S[0]), KEEPALIVE_RANGE_S[1]) * 1000 : KEEPALIVE_MS;
    // Only an interval the phone asked for is remembered; the rest follow the relay's own.
    this.state.waitUntil(Promise.all([
      asked ? this.state.storage.put("keepalive_ms", every) : this.state.storage.delete("keepalive_ms"),
      this.state.storage.setAlarm(Date.now() + every),
    ]));
    // Where the phone came in: part of the trace of a slow call.
    this.state.waitUntil(this.state.storage.put("phone_colo", request.headers.get("X-Relay-Colo") ?? ""));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  /**
   * The keepalive: a byte to the phone often enough that the network in between does not close an
   * idle link (the edge and mobile carriers drop quiet ones). It comes from this side because a sleeping phone's
   * own timers do not run, while a packet arriving wakes it. Stops when the phone is gone.
   */
  async alarm(): Promise<void> {
    const sockets = this.state.getWebSockets();
    if (sockets.length === 0) return;
    for (const ws of sockets) { try { ws.send("k"); } catch { /* closing */ } }
    await this.state.storage.setAlarm(Date.now() + ((await this.state.storage.get<number>("keepalive_ms")) ?? KEEPALIVE_MS));
  }

  /**
   * This account's use this month, kept beside the phone and refreshed from the database now and
   * then. Several phones on one account each count their own calls between refreshes, so the
   * allowance can overshoot by a few minutes of use: accepted, in exchange for not putting a
   * database round trip in front of every tool call.
   */
  private async usage(owner: string): Promise<CachedUsage> {
    const m = month();
    const cached = await this.state.storage.get<CachedUsage>("usage");
    if (cached && cached.month === m && Date.now() - cached.at < USAGE_REFRESH_MS) return cached;
    const fresh = await usageOf(this.env, owner);
    const next = { month: fresh.month, used: fresh.used, limit: fresh.limit, at: Date.now() };
    await this.state.storage.put("usage", next);
    return next;
  }

  /** An MCP request: send it to the phone and wait for its answer. */
  private async forward(request: Request): Promise<Response> {
    const received = Date.now();
    const socket = this.state.getWebSockets()[0];
    if (!socket) return json(503, { error: "the phone is offline", code: "phone_offline" });
    const body = request.method === "POST" ? await request.text() : "";
    if (body.length > MAX_BODY) return json(413, { error: "request too large" });
    // Only tool calls are metered; the handshake, tools/list and pings are free.
    // A sealed request may be one: it is held back when the allowance is used up, and counted if the
    // phone says it was.
    const sealed = sealedRequest(body);
    const rpc = sealed ? null : toolCall(body);
    const owner = rpc || sealed ? await this.state.storage.get<string>("owner") : undefined;
    if ((rpc || sealed) && owner) {
      const usage = await this.usage(owner);
      if (usage.limit !== null && usage.used >= usage.limit) return rpc ? quotaExceeded(rpc.id, usage) : quotaExceededSealed(usage);
    }
    const ready = Date.now();
    const sent = await this.send(socket, request, body);
    const phoneMs = Number(sent.headers.get("X-Phone-Ms") ?? 0);
    const metered = sent.headers.get("X-Phone-Metered") === "1";
    const answer = new Response(sent.body, sent);
    answer.headers.delete("X-Phone-Ms");
    answer.headers.delete("X-Phone-Metered");
    const done = Date.now();
    answer.headers.set("X-Relay-Ms", String(done - received));
    answer.headers.set("Server-Timing", [
      `relay;dur=${ready - received}`,
      `link;desc="${(await this.state.storage.get<string>("phone_colo")) ?? ""}";dur=${Math.max(0, done - ready - phoneMs)}`,
      `phone;dur=${phoneMs}`,
    ].join(", "));
    // Count what the phone accepted and answered; a rejected token (401) or a relay failure does not count.
    if ((rpc || (sealed && metered)) && owner && answer.status === 200) {
      this.state.waitUntil((async () => {
        const usage = await this.state.storage.get<CachedUsage>("usage");
        if (usage) await this.state.storage.put("usage", { ...usage, used: usage.used + 1 });
        await (owner.startsWith("d:")
          ? this.env.DB.prepare("INSERT INTO device_usage (device_id, month, calls) VALUES (?1, ?2, 1) ON CONFLICT(device_id, month) DO UPDATE SET calls = calls + 1").bind(owner.slice(2), month())
          : this.env.DB.prepare("INSERT INTO usage (user_id, month, calls) VALUES (?1, ?2, 1) ON CONFLICT(user_id, month) DO UPDATE SET calls = calls + 1").bind(owner, month())
        ).run();
      })());
    }
    return answer;
  }

  private async send(socket: WebSocket, request: Request, body: string): Promise<Response> {
    if (this.pending.size >= MAX_PENDING) return json(429, { error: "too many requests are waiting on this phone", code: "phone_busy" });

    const id = crypto.randomUUID();
    const answer = new Promise<Response>((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(json(504, { error: "the phone did not answer in time; the action may or may not have happened", code: "relay_timeout" }));
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer });
    });
    try {
      socket.send(JSON.stringify({
        id,
        method: request.method,
        authorization: request.headers.get("Authorization") ?? "",
        body,
      }));
    } catch {
      // The phone hung up and this is what is left of its socket: it is offline, not a relay fault.
      const p = this.pending.get(id);
      if (p) { clearTimeout(p.timer); this.pending.delete(id); }
      try { socket.close(1011, "gone"); } catch { /* already closed */ }
      return json(503, { error: "the phone is offline", code: "phone_offline" });
    }
    return answer;
  }

  /** A reply from the phone: `{id, status, body, ms, metered}`. */
  async webSocketMessage(_ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    let reply: { id?: string; status?: number; body?: string; ms?: number; metered?: boolean };
    try { reply = JSON.parse(message); } catch { return; }
    const p = reply.id ? this.pending.get(reply.id) : undefined;
    if (!p) return;
    this.pending.delete(reply.id!);
    clearTimeout(p.timer);
    const status = reply.status ?? 502;
    p.resolve(new Response(status === 202 || status === 204 || !reply.body ? null : reply.body, {
      status,
      headers: { ...(reply.body ? { "Content-Type": "application/json" } : {}), "X-Phone-Ms": String(Number(reply.ms) || 0), "X-Phone-Metered": reply.metered === true ? "1" : "0" },
    }));
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    // Finish the closing handshake, or the dead socket stays listed as this phone's connection.
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch { /* already closed */ }
    this.failAll("the phone disconnected");
  }

  async webSocketError(): Promise<void> {
    this.failAll("the phone connection failed");
  }

  private failAll(reason: string) {
    if (this.state.getWebSockets().length > 0) return; // a newer socket is still attached
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      p.resolve(json(502, { error: `${reason}; the action may or may not have happened` }));
      this.pending.delete(id);
    }
  }
}

// ---- helpers ----

/** [code] is stable and what the app translates; [message] is for people reading raw responses. */
class HttpError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  try {
    const v = await request.json();
    if (v && typeof v === "object") return v as Record<string, unknown>;
  } catch { /* fall through */ }
  throw new HttpError(400, "bad_request", "Bad request");
}

function bearer(request: Request): string | null {
  const h = request.headers.get("Authorization") ?? "";
  return h.startsWith("Bearer ") ? h.slice(7).trim() || null : null;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

function hex(buf: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomB64(bytes: number): string {
  const s = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(bytes))));
  return s.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256(s: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
