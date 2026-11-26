# Security

Miu Bridge holds powerful access to a phone: it reads the screen of its background display and
injects input there. Please treat problems in it seriously, and report them privately.

## Reporting

Use GitHub's "Report a vulnerability" on this repository (Security tab). Please do not open a public
issue for a vulnerability. You will get an answer within a few days.

## What the design promises

- Agents act only on a background display. The owner's main screen is never read or touched.
- Every request needs the phone's token, and the phone checks it.
- The relay forwards requests and answers. It does not store or log their content: no screen text,
  no screenshots, no tool arguments. It stores the device name, hashed keys, last-seen time and a count
  of remote tool calls per phone per month (`relay/migrations`). It has no accounts and stores no email.
- A plain request passes through the relay readable, token included, protected by HTTPS on each side
  of it. With the SDK's end-to-end encryption the relay carries only ciphertext and never sees the token.
- The experience improvement program is off unless the owner joins it in the app. While joined, the
  phone reports metadata about each tool call under a random install id that is not linked to the
  phone's address on the relay: tool name, package name of the app in front, success or error code, duration, how the
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
  owner's choice: every app except a block list (the default), or only the apps on an allow list. Banking,
  payment, broker, tax and password apps start on the block list; only the owner can take them off.

A report that breaks one of these is exactly what we want to hear.

## Known limits

- Traffic through the relay is TLS to the relay and TLS from it, not end-to-end encrypted: whoever
  runs the relay could read it in transit. Run your own relay (`relay/`) if that matters to you.
- Anyone holding the address and token controls the background display. Replace the token in the
  app if it leaks.
