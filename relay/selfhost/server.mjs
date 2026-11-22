/**
 * Miu Bridge relay for a server of your own: one Node process and a SQLite file, speaking the same
 * protocol as the Cloudflare relay in ../src/index.ts, so the app and the SDKs cannot tell them apart.
 * Put it where the hosted relay is far from your phones.
 *
 *   POST   /api/phones            {name, hardware}             -> {deviceId, deviceKey, mcpUrl}
 *   wss    /d/<deviceId>/connect  phone, header `X-Device-Key`; `?k=<seconds>` asks for that keepalive interval
 *   GET    /d/<deviceId>/usage    phone, header `X-Device-Key`  -> {month, used, limit}
 *   https  /d/<deviceId>/mcp      MCP clients, header `Authorization: Bearer <token>` (checked by the phone)
 *   GET    /sdk/                  the client SDK and its guide
 *   /api/trace, /api/feedback     passed on to UPSTREAM, which keeps the opt-in usage reports
 *
 * It listens on 127.0.0.1; a reverse proxy in front (Caddy) holds the certificate and passes the
 * caller's address in X-Forwarded-For. A phone has one connection, to this one process, so there is
 * nothing to coordinate: who is connected lives in memory, the rest in SQLite.
 */
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT ?? 8787);
const DATA_DIR = process.env.DATA_DIR ?? path.dirname(fileURLToPath(import.meta.url));
/** Where usage reports go, and the address the SDK guide names; "" keeps the reports nowhere. */
const UPSTREAM = process.env.UPSTREAM ?? "https://phone.jinshang.ai";
/** Remote tool calls a phone may make per calendar month (UTC); 0 for no limit. */
const MONTHLY_CALLS = Number(process.env.MONTHLY_CALLS ?? 1000);
const SDK_DIR = process.env.SDK_DIR ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "sdk");

const DEVICE_ID = /^[A-Za-z0-9_-]{22,64}$/;
const HARDWARE_ID = /^[A-Za-z0-9_-]{32,64}$/;
const MAX_PENDING = 32; // requests waiting on one phone; it runs one at a time
const REQUEST_TIMEOUT_MS = 10 * 60_000; // tool calls can wait for the owner (handoff)
const MAX_BODY = 1 << 20;
const MAX_NEW_PHONES_PER_DAY = 30; // from one address; carrier networks put many owners behind one
const REGISTERS_PER_MINUTE = 10; // from one address
const KEEPALIVE_MS = 120_000;
const KEEPALIVE_RANGE_S = [30, 900];
const SDK = { "/sdk": "README.md", "/sdk/": "README.md", "/sdk/README.md": "README.md", "/sdk/miu_bridge.py": "miu_bridge.py", "/sdk/miu-bridge.mjs": "miu-bridge.mjs" };
const SDK_TYPES = { ".md": "text/markdown; charset=utf-8", ".py": "text/x-python; charset=utf-8", ".mjs": "text/javascript; charset=utf-8" };

const db = new DatabaseSync(path.join(DATA_DIR, "relay.db"));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,           -- random, part of the public MCP URL
    name TEXT NOT NULL,
    key_hash TEXT NOT NULL,        -- sha256 of the key the phone uses to attach
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    hardware_hash TEXT UNIQUE,     -- sha256 of the phone's own id: registering again keeps the row and its count
    origin TEXT                    -- sha256 of the registering address and day, to cap new phones per address
  );
  CREATE INDEX IF NOT EXISTS devices_origin ON devices(origin);
  CREATE TABLE IF NOT EXISTS device_usage (
    device_id TEXT NOT NULL,
    month TEXT NOT NULL,           -- 'YYYY-MM', UTC
    calls INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, month)
  );
`);

/** Connected phones: device id -> its socket and the requests waiting on it. */
const phones = new Map();
/** Registrations in the last minute, by address. */
const registers = new Map();

class HttpError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");
const randomB64 = (bytes) => crypto.randomBytes(bytes).toString("base64url");
const month = () => new Date().toISOString().slice(0, 7);
const same = (a, b) => a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
/** The caller's address: the last one the reverse proxy added. */
const addressOf = (req) => String(req.headers["x-forwarded-for"] ?? "").split(",").pop().trim() || req.socket.remoteAddress || "unknown";
const originOf = (req) => `${req.headers["x-forwarded-proto"] ?? "http"}://${req.headers.host}`;

function send(res, status, body, headers = {}) {
  const data = body === null ? "" : typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(status, { ...(body === null ? {} : { "Content-Type": typeof body === "string" ? "text/plain; charset=utf-8" : "application/json; charset=utf-8" }), ...headers });
  res.end(data);
}

function read(req, limit = MAX_BODY) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) { reject(new HttpError(413, "too_large", "request too large")); req.destroy(); } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function usageOf(deviceId) {
  const row = db.prepare("SELECT calls FROM device_usage WHERE device_id = ? AND month = ?").get(deviceId, month());
  return { month: month(), used: row?.calls ?? 0, limit: MONTHLY_CALLS || null };
}

/** The JSON-RPC id of a request that counts as a tool call, or null for anything else. */
function toolCall(body) {
  // End-to-end encrypted: nothing in it can be read here, so every such request counts as a call.
  if (body.startsWith("e1.")) return { id: null, sealed: true };
  if (!body.includes("tools/call")) return null;
  try {
    const v = JSON.parse(body);
    return v && typeof v === "object" && v.method === "tools/call" ? { id: v.id ?? null } : null;
  } catch { return null; }
}

function quotaExceeded(rpc, usage) {
  const message = `This phone has used its ${usage.limit} remote tool calls for ${usage.month}. ` +
    "Agents on the phone's own Wi-Fi are not limited; remote use continues next month.";
  // An encrypted request cannot be answered in kind from here: the status says it instead.
  if (rpc.sealed) return [402, { error: "this phone has used its remote tool calls for this month", code: "quota_exceeded" }];
  return [200, { jsonrpc: "2.0", id: rpc.id, result: { isError: true, content: [{ type: "text", text: `${message} [code: quota_exceeded]` }],
    structuredContent: { code: "quota_exceeded", message, used: usage.used, limit: usage.limit, month: usage.month } } }];
}

async function register(req, res) {
  let body;
  try { body = JSON.parse(await read(req, 4096)); } catch (e) { if (e instanceof HttpError) throw e; throw new HttpError(400, "bad_request", "Bad request"); }
  const hardware = String(body?.hardware ?? "");
  if (!HARDWARE_ID.test(hardware)) throw new HttpError(400, "bad_request", "Bad request");
  const ip = addressOf(req);
  const now = Date.now();
  const recent = (registers.get(ip) ?? []).filter((t) => now - t < 60_000);
  if (recent.length >= REGISTERS_PER_MINUTE) throw new HttpError(429, "rate_limited", "Too many requests");
  registers.set(ip, [...recent, now]);
  const name = String(body.name ?? "").trim().slice(0, 60) || "Phone";
  const key = randomB64(32);
  const hardwareHash = sha256(`hw:${hardware}`);
  // Registering again (a reinstall, cleared data) gives the same phone a new key and keeps its URL and
  // its count for the month, so starting over does not start the allowance over.
  const known = db.prepare("SELECT id FROM devices WHERE hardware_hash = ?").get(hardwareHash)?.id;
  if (known) {
    db.prepare("UPDATE devices SET key_hash = ?, name = ? WHERE id = ?").run(sha256(key), name, known);
    phones.get(known)?.ws.close(4001, "device key changed");
    return send(res, 200, { deviceId: known, deviceKey: key, mcpUrl: `${originOf(req)}/d/${known}/mcp` });
  }
  const origin = sha256(`ip:${ip}:${new Date().toISOString().slice(0, 10)}`);
  if (db.prepare("SELECT COUNT(*) AS n FROM devices WHERE origin = ?").get(origin).n >= MAX_NEW_PHONES_PER_DAY) throw new HttpError(429, "rate_limited", "Too many requests");
  const id = randomB64(18);
  db.prepare("INSERT INTO devices (id, name, key_hash, created_at, hardware_hash, origin) VALUES (?, ?, ?, ?, ?, ?)").run(id, name, sha256(key), now, hardwareHash, origin);
  send(res, 200, { deviceId: id, deviceKey: key, mcpUrl: `${originOf(req)}/d/${id}/mcp` });
}

/** The device row when the request carries that device's key; otherwise an error. */
function checkKey(req, id) {
  const row = db.prepare("SELECT key_hash FROM devices WHERE id = ?").get(id);
  if (!row) throw new HttpError(404, "unknown_device", "unknown device");
  const key = String(req.headers["x-device-key"] ?? "");
  if (!key || !same(sha256(key), row.key_hash)) throw new HttpError(403, "key_mismatch", "device key mismatch");
}

/** An MCP request: send it to the phone and wait for its answer. */
async function forward(req, res, id) {
  const received = Date.now();
  const phone = phones.get(id);
  if (!phone) return send(res, 503, { error: "the phone is offline", code: "phone_offline" });
  const body = req.method === "POST" ? await read(req) : "";
  // Only tool calls are metered; the handshake, tools/list and pings are free.
  const rpc = toolCall(body);
  if (rpc && MONTHLY_CALLS) {
    const usage = usageOf(id);
    if (usage.used >= usage.limit) return send(res, ...quotaExceeded(rpc, usage));
  }
  if (phone.pending.size >= MAX_PENDING) return send(res, 429, { error: "too many requests are waiting on this phone", code: "phone_busy" });
  const requestId = crypto.randomUUID();
  const ready = Date.now();
  const reply = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      phone.pending.delete(requestId);
      resolve({ status: 504, body: JSON.stringify({ error: "the phone did not answer in time; the action may or may not have happened", code: "relay_timeout" }) });
    }, REQUEST_TIMEOUT_MS);
    phone.pending.set(requestId, { resolve, timer });
    phone.ws.send(JSON.stringify({ id: requestId, method: req.method, authorization: req.headers.authorization ?? "", body }));
  });
  const status = reply.status ?? 502;
  const phoneMs = Number(reply.ms) || 0;
  const done = Date.now();
  // Count what the phone accepted and answered; a rejected token (401) or a relay failure does not count.
  if (rpc && status === 200) {
    db.prepare("INSERT INTO device_usage (device_id, month, calls) VALUES (?, ?, 1) ON CONFLICT(device_id, month) DO UPDATE SET calls = calls + 1").run(id, month());
  }
  const empty = status === 202 || status === 204 || !reply.body;
  res.writeHead(status, {
    ...(empty ? {} : { "Content-Type": "application/json" }),
    // Where the time went, for whoever is tracing a slow call. Durations only.
    "Server-Timing": `relay;dur=${ready - received}, link;dur=${Math.max(0, done - ready - phoneMs)}, phone;dur=${phoneMs}`,
  });
  res.end(empty ? "" : reply.body);
}

/** Passes an opt-in usage report on to the relay that keeps them. */
async function passOn(req, res, url) {
  if (!UPSTREAM) return send(res, 200, {});
  const body = req.method === "POST" ? await read(req) : undefined;
  const answer = await fetch(UPSTREAM + url.pathname, { method: req.method, headers: { "Content-Type": "application/json" }, body, signal: AbortSignal.timeout(20_000) });
  send(res, answer.status, await answer.text(), { "Content-Type": "application/json; charset=utf-8" });
}

async function handle(req, res) {
  const url = new URL(req.url, "http://relay");
  const route = `${req.method} ${url.pathname}`;
  if (route === "POST /api/phones") return register(req, res);
  if (route === "POST /api/trace" || route === "POST /api/feedback" || (req.method === "DELETE" && /^\/api\/trace\/[A-Za-z0-9-]{16,64}$/.test(url.pathname))) return passOn(req, res, url);
  const m = url.pathname.match(/^\/d\/([^/]+)\/(mcp|usage)$/);
  if (m && DEVICE_ID.test(m[1])) {
    if (m[2] === "mcp") return forward(req, res, m[1]);
    checkKey(req, m[1]);
    return send(res, 200, usageOf(m[1]));
  }
  const file = SDK[url.pathname];
  if (file && req.method === "GET") {
    // The SDK guide names the hosted relay; this relay serves it with its own address.
    const text = fs.readFileSync(path.join(SDK_DIR, file), "utf8").replaceAll("https://phone.jinshang.ai", originOf(req));
    return send(res, 200, text, { "Content-Type": SDK_TYPES[path.extname(file)], "Cache-Control": "public, max-age=300" });
  }
  if (url.pathname === "/") return send(res, 200, "Miu Bridge relay\n");
  send(res, 404, { error: "not found" });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    if (res.headersSent) return res.end();
    if (e instanceof HttpError) return send(res, e.status, { error: e.message, code: e.code });
    console.error(e);
    send(res, 500, { error: "Internal error", code: "internal" });
  });
});

// ---- the phone's connection ----

const sockets = new WebSocketServer({ noServer: true, maxPayload: 8 * MAX_BODY });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://relay");
  const m = url.pathname.match(/^\/d\/([^/]+)\/connect$/);
  const refuse = (status, text) => { socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\n\r\n`); socket.destroy(); };
  if (!m || !DEVICE_ID.test(m[1])) return refuse(404, "Not Found");
  try { checkKey(req, m[1]); } catch (e) { return refuse(e.status ?? 500, e.status === 404 ? "Not Found" : "Forbidden"); }
  db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(Date.now(), m[1]);
  sockets.handleUpgrade(req, socket, head, (ws) => attach(ws, m[1], Number(url.searchParams.get("k") || 0)));
});

function attach(ws, id, asked) {
  phones.get(id)?.ws.close(4000, "replaced by a newer connection");
  const phone = { ws, pending: new Map() };
  phones.set(id, phone);
  // The keepalive: a byte to the phone often enough that the network in between does not close an
  // idle link. It comes from this side because a sleeping phone's own timers do not run, while a
  // packet arriving wakes it.
  const every = asked ? Math.min(Math.max(asked, KEEPALIVE_RANGE_S[0]), KEEPALIVE_RANGE_S[1]) * 1000 : KEEPALIVE_MS;
  const keepalive = setInterval(() => { try { ws.send("k"); } catch { /* closing */ } }, every);
  ws.on("message", (data, binary) => {
    if (binary) return;
    const text = data.toString("utf8");
    if (text === "ping") return ws.send("pong");
    let reply;
    try { reply = JSON.parse(text); } catch { return; }
    const waiting = reply.id ? phone.pending.get(reply.id) : undefined;
    if (!waiting) return;
    phone.pending.delete(reply.id);
    clearTimeout(waiting.timer);
    waiting.resolve(reply);
  });
  const gone = (reason) => {
    clearInterval(keepalive);
    if (phones.get(id) === phone) phones.delete(id);
    for (const [, waiting] of phone.pending) {
      clearTimeout(waiting.timer);
      waiting.resolve({ status: 502, body: JSON.stringify({ error: `${reason}; the action may or may not have happened` }) });
    }
    phone.pending.clear();
  };
  ws.on("close", () => gone("the phone disconnected"));
  ws.on("error", () => gone("the phone connection failed"));
}

server.listen(PORT, "127.0.0.1", () => console.log(`Miu Bridge relay on 127.0.0.1:${PORT}, data in ${DATA_DIR}`));
