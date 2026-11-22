/**
 * Miu Bridge SDK for JavaScript (Node 18+, Deno, Bun): write a normal script in your own sandbox;
 * every call acts on the phone.
 *
 *   import { Phone } from "./miu-bridge.mjs";
 *   const phone = new Phone();                      // reads MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN
 *   await phone.launch("com.android.settings");
 *   const screen = await phone.tap("text=Network"); // a selector, located on the phone at that moment
 *   console.log(screen.text);
 *
 * No dependencies. Each call is one HTTPS request to the phone (through the relay about a second), so
 * for long runs of steps prefer `phone.script(js)`, which runs them on the phone in one request.
 *
 * Elements are named by a ref from the latest screen ("e12") or by a selector that needs no screen read:
 * text=Send, text="Send" (exact), id=send_btn, role=button[name="Send"], optionally followed by " >> nth=N".
 *
 * Requests are end-to-end encrypted between here and the phone with keys made from the token, which
 * is itself never sent: a relay or network in between can read neither what you ask nor what comes back.
 *
 * `node miu-bridge.mjs mcp` runs this file as a local MCP server (stdio) that carries an MCP client's
 * requests to the phone the same way, for clients that would otherwise send them to the URL in the clear.
 */

const E2E_HELLO = "miu-bridge e2e v1 hello";
const E2E_INFO = "miu-bridge e2e v1";
const utf8 = new TextEncoder();
const b64 = (bytes) => { let s = ""; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000)); return btoa(s); };
const unb64 = (text) => Uint8Array.from(atob(text), (ch) => ch.charCodeAt(0));
const join = (...parts) => { const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let at = 0; for (const p of parts) { out.set(p, at); at += p.length; } return out; };
const nonce = (n) => { const iv = new Uint8Array(12); new DataView(iv.buffer).setBigUint64(4, BigInt(n)); return iv; };

/** The phone refused or could not do what was asked. The message says why, in words meant for the agent. */
export class PhoneError extends Error {
  /** `code` is stable and meant for branching: phone_locked, not_found, ambiguous, wait_timeout, paced, phone_offline, ... */
  constructor(message, code = "failed") { super(message); this.code = code; }
}

/** What the phone shows after an action: the element tree as text, and anything the phone wants you to know. */
export class Screen {
  constructor(data, images = []) {
    this.data = data;
    this.text = data.snapshot ?? ""; // one line per element: [e5] button "Send" @540,1620 #send_btn
    this.package = data.package ?? null; // the app in front
    this.done = data.done ?? null; // what the action did
    this.warning = data.warning ?? null; // a rate-limit or verification screen: stop and hand off
    this.notice = data.notice ?? null; // e.g. the background display was restarted
    this.loading = Boolean(data.loading); // still loading after the wait: wait() for the content you expect
    this.restarted = Boolean(data.restarted); // the display was restarted: apps on it were closed, launch again
    // The picture of this screen, when you asked for one ({screenshot: true}) or the app exposes no element tree.
    this.screenshot = images.length ? new Screenshot(data, images[0]) : null;
  }
  get lines() { return this.text.split("\n").slice(1); }
  /** The ref of the first element whose line matches the regular expression, or null. */
  find(pattern) {
    const re = pattern instanceof RegExp ? pattern : new RegExp(pattern);
    for (const line of this.lines) if (re.test(line)) { const m = line.match(/\[(e\d+)\]/); if (m) return m[1]; }
    return null;
  }
  /** The quoted labels on the screen, in order. */
  texts() { return this.lines.map((l) => l.match(/"([^"]*)"/)?.[1]).filter((t) => t !== undefined); }
  toString() { return this.text; }
}

/** A JPEG of the background display. Image pixels times `scale` are display pixels for tap({x, y}). */
export class Screenshot {
  constructor(data, jpeg) { this.jpeg = jpeg; this.scale = data.scale ?? 1; this.width = data.image_width; this.height = data.image_height; }
  async save(path) { const { writeFile } = await import("node:fs/promises"); await writeFile(path, this.jpeg); return path; }
}

export class Phone {
  #session = null;

  /**
   * `encrypt: false` (or MIU_BRIDGE_PLAINTEXT=1) sends requests and the token in the clear, as an app
   * from before end-to-end encryption expects. Whoever runs the relay can then read both.
   */
  constructor({ url, token, timeoutMs = 150_000, retries = 2, encrypt } = {}) {
    const env = globalThis.process?.env ?? {};
    this.url = url ?? env.MIU_BRIDGE_URL;
    this.token = token ?? env.MIU_BRIDGE_TOKEN;
    if (!this.url || !this.token) throw new PhoneError("set MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN, or pass url and token");
    this.encrypt = encrypt ?? !["1", "true"].includes(env.MIU_BRIDGE_PLAINTEXT);
    this.timeoutMs = timeoutMs;
    this.retries = retries;
    this.id = 0;
  }

  /**
   * Runs one phone tool by name and returns { data, images }. The methods below are thin wrappers over this.
   * A request that gets no answer (a timeout, a dropped connection, the relay or phone briefly away) is sent
   * again with the same request id; the phone answers a repeat with its first result, so nothing is done twice.
   */
  async call(tool, args = {}) {
    const clean = Object.fromEntries(Object.entries(args).filter(([, v]) => v !== undefined && v !== null && v !== false));
    clean.request_id = globalThis.crypto.randomUUID();
    const body = JSON.stringify({ jsonrpc: "2.0", id: ++this.id, method: "tools/call", params: { name: tool, arguments: clean } });
    for (let attempt = 0; ; attempt++) {
      try { return await this.#send(body); } catch (e) {
        if (!["unreachable", "phone_offline", "relay_timeout"].includes(e.code) || attempt >= this.retries) throw e;
        await new Promise((r) => setTimeout(r, 1000 + 2000 * attempt));
      }
    }
  }

  /** One HTTP request to the phone's address. Anything but an answer from the phone is a PhoneError. */
  async #http(payload, sealed) {
    let response;
    try {
      response = await fetch(this.url, {
        method: "POST",
        // The User-Agent is named on purpose: the relay's CDN refuses some default client signatures with a 403.
        headers: { "Content-Type": "application/json", Accept: "application/json", "User-Agent": "miu-bridge-sdk/1.3 (js)", ...(sealed ? {} : { Authorization: `Bearer ${this.token}` }) },
        body: payload,
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (e) {
      throw new PhoneError(`cannot reach the phone: ${e.cause?.message ?? e.message}`, "unreachable");
    }
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 300);
      let named = null;
      try { named = JSON.parse(detail); } catch { /* not JSON: a CDN or proxy page */ }
      let hint = { 401: "the token was rejected", 503: "the phone is offline", 504: "the phone did not answer in time" }[response.status] ?? "";
      if (response.status === 403 && detail.includes("1010")) hint = "the relay's CDN refused this client's signature; send a User-Agent header that names your client";
      const code = { 401: "unauthorized", 502: "unreachable", 503: "phone_offline", 504: "relay_timeout" }[response.status] ?? (typeof named?.code === "string" ? named.code : "http_error");
      const error = new PhoneError(`HTTP ${response.status} ${hint}: ${detail}`.trim(), code);
      error.sealed = named?.miu_e2e === 1; // whether the phone itself knows about encryption
      throw error;
    }
    let reply = null;
    if (response.status !== 202 && response.status !== 204) {
      try { reply = await response.json(); } catch (e) { throw new PhoneError(`no answer from the phone: ${e.message}`, "unreachable"); }
    }
    return { reply, header: response.headers.get("Server-Timing") };
  }

  /** Opens an encrypted session: a fresh key from each side, bound to the token. Described in the app's server/E2e.kt. */
  async #hello() {
    const subtle = globalThis.crypto.subtle;
    const pair = await subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    const pub = new Uint8Array(await subtle.exportKey("raw", pair.publicKey));
    const secret = await subtle.importKey("raw", utf8.encode(this.token), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const mac = new Uint8Array(await subtle.sign("HMAC", secret, join(utf8.encode(E2E_HELLO), pub)));
    let reply;
    try {
      ({ reply } = await this.#http(JSON.stringify({ miu_e2e: 1, hello: { pub: b64(pub), mac: b64(mac) } }), true));
    } catch (e) {
      if (e.code === "unauthorized" && !e.sealed) {
        throw new PhoneError("the phone's app is from before end-to-end encryption: ask the owner to update it, or pass encrypt: false (MIU_BRIDGE_PLAINTEXT=1) to send requests and the token in the clear", "e2e_unsupported");
      }
      throw e;
    }
    if (reply?.miu_e2e !== 1 || typeof reply.sid !== "string" || typeof reply.pub !== "string") {
      throw new PhoneError("the phone's app is from before end-to-end encryption: ask the owner to update it, or pass encrypt: false (MIU_BRIDGE_PLAINTEXT=1) to send requests and the token in the clear", "e2e_unsupported");
    }
    const theirs = unb64(reply.pub);
    const shared = await subtle.deriveBits({ name: "ECDH", public: await subtle.importKey("raw", theirs, { name: "ECDH", namedCurve: "P-256" }, false, []) }, pair.privateKey, 256);
    const keys = new Uint8Array(await subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: utf8.encode(this.token), info: join(utf8.encode(E2E_INFO), pub, theirs, utf8.encode(reply.sid)) },
      await subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]), 512));
    return {
      sid: reply.sid, n: 0,
      c2s: await subtle.importKey("raw", keys.slice(0, 32), "AES-GCM", false, ["encrypt"]),
      s2c: await subtle.importKey("raw", keys.slice(32), "AES-GCM", false, ["decrypt"]),
    };
  }

  /**
   * Sends one JSON-RPC message (a string) and returns the phone's JSON-RPC answer, or null where there
   * is none (a notification). Encrypted unless this Phone was made with encrypt: false.
   */
  async rpc(body) { return (await this.#exchange(body)).reply; }

  async #exchange(body) {
    if (!this.encrypt) return this.#http(body, false);
    const subtle = globalThis.crypto.subtle;
    for (let again = true; ; again = false) {
      // One hello for calls started together; a failed one is forgotten so the next call tries again.
      const opening = (this.#session ??= this.#hello());
      let session;
      try { session = await opening; } catch (e) { if (this.#session === opening) this.#session = null; throw e; }
      const n = ++session.n;
      const ct = await subtle.encrypt({ name: "AES-GCM", iv: nonce(n), additionalData: utf8.encode(`miu-e2e req ${session.sid}`) }, session.c2s, utf8.encode(body));
      let answer;
      try {
        answer = await this.#http(JSON.stringify({ miu_e2e: 1, sid: session.sid, n, ct: b64(new Uint8Array(ct)) }), true);
      } catch (e) {
        // The phone restarted or let the session go: it did nothing with this request, so open another and say it again.
        if (e.code !== "e2e_session" || !again) throw e;
        if (this.#session === opening) this.#session = null;
        continue;
      }
      // Only what opens with the session's key is the phone's answer; anything else was put there on the way.
      let plain;
      try {
        plain = await subtle.decrypt({ name: "AES-GCM", iv: nonce(n), additionalData: utf8.encode(`miu-e2e res ${session.sid}`) }, session.s2c, unb64(answer.reply.ct));
      } catch {
        throw new PhoneError("the answer was not the phone's: it does not open with this session's key", "e2e_rejected");
      }
      const inner = JSON.parse(new TextDecoder().decode(plain));
      if (inner.status === 202 || inner.status === 204) return { reply: null, header: answer.header };
      if (inner.status !== 200 && !inner.body) throw new PhoneError(`HTTP ${inner.status}`, "http_error");
      return { reply: inner.body, header: answer.header };
    }
  }

  async #send(body) {
    const started = Date.now();
    const { reply, header } = await this.#exchange(body);
    if (!reply) throw new PhoneError("no answer from the phone", "unreachable");
    if (reply.error) throw new PhoneError(String(reply.error.message ?? JSON.stringify(reply.error)), "bad_request");
    // Where this call's time went, in milliseconds: total as seen from here, each part the relay and the phone
    // reported (edge, relay, link, phone), network for what is left over, and where Cloudflare handled it.
    const timing = { total_ms: Date.now() - started };
    const where = {};
    for (const part of (header ?? "").split(",")) {
      const [name, ...rest] = part.trim().split(";");
      const dur = /dur=([0-9.]+)/.exec(rest.join(";"));
      if (name && dur) timing[`${name}_ms`] = Math.round(Number(dur[1]));
      const place = /desc="([^"]*)"/.exec(rest.join(";"));
      if (name && place?.[1]) where[name] = place[1];
    }
    timing.network_ms = Math.max(0, timing.total_ms - ["edge_ms", "relay_ms", "link_ms", "phone_ms"].reduce((sum, k) => sum + (timing[k] ?? 0), 0));
    if (Object.keys(where).length) timing.where = where;
    if (reply.result?._meta?.timing) timing.phone_parts = reply.result._meta.timing;
    this.lastTiming = timing;
    const content = reply.result?.content ?? [];
    const text = content.filter((c) => c.type === "text").map((c) => c.text).join("");
    if (reply.result?.isError) throw new PhoneError(text, reply.result.structuredContent?.code ?? "failed");
    const images = content.filter((c) => c.type === "image").map((c) => Uint8Array.from(atob(c.data), (ch) => ch.charCodeAt(0)));
    let data;
    try { data = JSON.parse(text); } catch { data = { text }; }
    return { data, images };
  }

  async #screen(tool, args, { screenshot } = {}) {
    const { data, images } = await this.call(tool, { ...args, screenshot });
    return new Screen(data, images);
  }

  // --- looking ---
  /** Launchable apps: [{label, package, blocked?}]; with a query, only those whose label or package contains it. */
  async apps(query) { return (await this.call("phone_apps", { query })).data.apps ?? []; }
  /** The phone's state without touching it: locked, ready, display {id, width, height, dpi}, package, reader, restarted, handed_off (the package the owner holds from handoff, or null), version. */
  async status() { return (await this.call("phone_status")).data; }
  /** The screen as text. Every method that returns a Screen takes a last argument {screenshot: true} to get its picture too (screen.screenshot). */
  snapshot(opts) { return this.#screen("phone_snapshot", {}, opts); }
  async screenshot() {
    const { data, images } = await this.call("phone_screenshot");
    if (!images.length) throw new PhoneError("the phone returned no image");
    return new Screenshot(data, images[0]);
  }
  /** Waits until the element is on the screen (or, state "gone", until it is not). Throws PhoneError on timeout. */
  wait(target, { state = "visible", timeoutMs = 5000, screenshot } = {}) { return this.#screen("phone_wait", { target, state, timeout_ms: timeoutMs }, { screenshot }); }

  // --- acting ---
  launch(pkg, opts) { return this.#screen("phone_launch", { package: pkg }, opts); }
  openUrl(url, opts) { return this.#screen("phone_open_url", { url }, opts); }
  /** Taps an element (a ref or selector string) or a point ({x, y} in display pixels). */
  tap(target, opts) { return this.#screen("phone_tap", typeof target === "string" ? { ref: target } : target, opts); }
  longPress(target, opts) { return this.#screen("phone_long_press", typeof target === "string" ? { ref: target } : target, opts); }
  /** A direction ("up" shows what is further down) or {x1, y1, x2, y2, duration_ms}. */
  swipe(how, opts) { return this.#screen("phone_swipe", typeof how === "string" ? { direction: how } : how, opts); }
  /** Replaces the text of an input (the focused one if no target). Does not press Enter. */
  type(text, target, opts) { return this.#screen("phone_type", { text, ref: target }, opts); }
  /** back, enter, or home (home closes the apps on the background display). */
  key(key, opts) { return this.#screen("phone_key", { key }, opts); }
  /** Moves the app to the owner's own screen: for payment, login, verification, anything irreversible. */
  async handoff() { return (await this.call("phone_handoff")).data; }
  /** Brings the handed-off app back as the owner left it, once they say they are done. */
  async takeback() { return this.#screen("phone_takeback", {}); }
  /** Runs JavaScript on the phone that chains the tools in one request; returns what it printed. */
  async script(code) { return (await this.call("phone_script", { code })).data.output ?? ""; }

  // --- notes agents keep about apps on this phone ---
  async notes() { return (await this.call("phone_skills")).data.skills ?? []; }
  async readNotes(name) { return (await this.call("phone_skill_read", { name })).data.skill ?? ""; }
  async learn(name, fact, { description, replaces } = {}) { return (await this.call("phone_skill_learn", { name, fact, description, replaces })).data; }
  async forget(name, fact) { return (await this.call("phone_skill_forget", { name, fact })).data; }
}

/**
 * `node miu-bridge.mjs mcp`: an MCP server on stdin and stdout for a local MCP client, with the phone
 * behind it. Each message is carried to the phone end-to-end encrypted, so the client needs no URL and
 * no header, only MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN in the environment of this process.
 */
async function serveStdio() {
  const phone = new Phone();
  const { createInterface } = await import("node:readline");
  const write = (message) => globalThis.process.stdout.write(JSON.stringify(message) + "\n");
  for await (const line of createInterface({ input: globalThis.process.stdin })) {
    if (!line.trim()) continue;
    phone.rpc(line).then((reply) => { if (reply) write(reply); }, (e) => {
      let id;
      try { id = JSON.parse(line).id; } catch { /* not ours to answer */ }
      if (id !== undefined) write({ jsonrpc: "2.0", id, error: { code: -32000, message: `${e.message} [code: ${e.code ?? "failed"}]` } });
    });
  }
}

const argv = globalThis.process?.argv ?? [];
if (argv[2] === "mcp" && (import.meta.main ?? import.meta.url === (await import("node:url")).pathToFileURL(argv[1]).href)) await serveStdio();
