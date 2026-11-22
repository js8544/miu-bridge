# Security

Miu Bridge holds powerful access to a phone: it reads the screen of its background display and
injects input there. Please treat problems in it seriously, and report them privately.

## Reporting

Use GitHub's "Report a vulnerability" on this repository (Security tab). Please do not open a public
issue for a vulnerability. You will get an answer within a few days.

## What the design promises

- Agents act only on a background display. The owner's main screen is never read or touched.
- Every request needs the phone's token, and the phone checks it.
- Requests from the SDK and from its local MCP server are end-to-end encrypted between the agent and
  the phone (see below). The relay, and the Wi-Fi on a direct connection, see neither the token nor
  what is asked and answered, and cannot change a request, repeat one or put an answer of their own
  in its place. For these requests the relay cannot act on a phone by itself.
- The relay forwards requests and answers. It does not store or log their content: no screen text,
  no screenshots, no tool arguments. It stores account email, device name, hashed keys,
  last-seen time and a count of remote tool calls per account per month (`relay/migrations`).
- The experience improvement program is off unless the owner joins it in the app. While joined, the
  phone reports metadata about each tool call under a random install id that is not linked to the
  account: tool name, package name of the app in front, success or error code, duration, how the
  element was named, app version, Android level and phone maker. It never includes screen text,
  typed text, screenshots or URLs. While joined, agents are also offered `phone_feedback` to write a
  short note (at most 1000 characters per field) when a tool did not behave as described. A note is the
  agent's own words, sent with the metadata of the last five calls; agents are told never to include
  screen contents or private data, and the owner can read every note in the app. Leaving the program
  asks the relay to delete what was sent, notes included.
- On the same Wi-Fi no server is involved at all.
- Once a day the app asks GitHub whether a newer release exists, to show a notice. The request says
  nothing about the phone, and the owner installs updates themselves.
- Agents are told to hand payments, sign-ins and verifications to the owner. Which apps agents may open is the
  owner's choice: every app except a block list (the default, empty), or only the apps on an allow list.

A report that breaks one of these is exactly what we want to hear.

## End-to-end encryption

The token is the shared secret between the owner's agent and the phone, and an encrypted request
never sends it. The agent opens a session by sending a fresh P-256 key with an HMAC-SHA256 over it
keyed by the token; the phone answers with a fresh key of its own. Both derive, with HKDF-SHA256
over the ECDH result, salted by the token and bound to both keys and the session id, one AES-256-GCM
key for each direction. Every request carries a counter that is its nonce and is accepted once; the
answer is sealed under the same counter, which ties it to its request. Because both keys are fresh
for each session and kept only in memory, traffic recorded today stays closed to someone who learns
the token later. `app/src/main/java/dev/miubridge/server/E2e.kt` holds the exact format; the SDKs
(`sdk/`) are the other side.

What stays visible to the relay: the phone's address, when requests happen, how large they and
their answers are, and whether an encrypted request was a tool call (the phone says so, for the
monthly count).

## Known limits

- An MCP client that is given the relay address and the token as a header (Streamable HTTP) is not
  end-to-end encrypted: its traffic is TLS to the relay and TLS from it, and whoever runs the relay
  could read it, the token included, in transit. Use the SDK or its local MCP server
  (`node miu-bridge.mjs mcp`) instead, and turn on "Encrypted remote access only" in the app to
  refuse anything else through the relay; or run your own relay (`relay/`).
- On the same Wi-Fi the phone listens on plain HTTP. Requests from the SDK are encrypted there too;
  a plain MCP client's are not, so use one only on a Wi-Fi you trust.
- The relay can still refuse or delay requests, and an answer it makes up fails to open on the agent's side.
- Anyone holding the address and token controls the background display. Replace the token in the
  app if it leaks.
