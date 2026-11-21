# Relay

A Cloudflare Worker that lets agents on other networks reach a phone. The phone keeps one outbound
WebSocket to it; agents send requests to the phone's address and the relay passes them on. It stores
devices and a monthly count of tool calls per phone (D1), and nothing that passes through. A phone
registers itself; no account is needed.

It counts answered `tools/call` requests per phone per calendar month (UTC) and refuses further
ones over the plan's allowance with `quota_exceeded`; `PLAN_LIMITS` in `src/index.ts` sets the
allowances (`null` is unlimited). It also receives the app's opt-in experience improvement reports
at `/api/trace`: metadata about tool calls under a random install id, described in
[SECURITY.md](../SECURITY.md).

## Run your own

Needs a Cloudflare account with a domain, Workers Paid (Durable Objects) and Email Sending.

```sh
cp wrangler.example.toml wrangler.toml      # then set your domain, sender address and database id
npx wrangler d1 create miu-bridge
npx wrangler d1 migrations apply miu-bridge --remote
npx wrangler deploy
```

In the app: More → Relay server → enter your relay's address, then turn on remote access.
