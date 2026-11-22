# Relay

A Cloudflare Worker that lets agents on other networks reach a phone. The phone keeps one outbound
WebSocket to it; agents send requests to the phone's address and the relay passes them on. It stores
devices and a monthly count of tool calls per phone (D1), and nothing that passes through. A phone
registers itself; no account is needed.

It counts answered `tools/call` requests per phone per calendar month (UTC) and refuses further
ones over the allowance with `quota_exceeded`; `MONTHLY_CALLS` in `src/index.ts` sets it. It also receives the app's opt-in experience improvement reports
at `/api/trace`: metadata about tool calls under a random install id, and agents' notes on tools
that did not behave as described at `/api/feedback`, both described in [SECURITY.md](../SECURITY.md).

## Run your own

Needs a Cloudflare account with a domain and Workers Paid (Durable Objects).

```sh
cp wrangler.example.toml wrangler.toml      # then set your domain and database id
npx wrangler d1 create miu-bridge
npx wrangler d1 migrations apply miu-bridge --remote
npx wrangler deploy
```

## Or on a server of your own

`selfhost/server.mjs` is the same relay as one Node process with a SQLite file, for a server near
your phones when the hosted relay is far from them (from mainland China, for one, where carriers take
Cloudflare traffic to Europe or the United States). It speaks the same protocol, so the app and the
SDKs need nothing changed.

Needs Node 22.13 or later, a domain pointing at the server, and ports 80 and 443 open.

```sh
cd selfhost && npm install --omit=dev
PORT=8787 DATA_DIR=/var/lib/miu-relay node server.mjs    # listens on 127.0.0.1
```

Put Caddy in front for the certificate (`selfhost/Caddyfile`) and run it as a service
(`selfhost/miu-relay.service`). `MONTHLY_CALLS` sets the allowance (0 for none). The app's opt-in
usage reports are passed on to `UPSTREAM` (the hosted relay unless you set another, or `""` to
drop them).

In the app: More → Relay server → enter your relay's address, then turn on remote access.
