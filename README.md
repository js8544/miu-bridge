# Miu Bridge

Let any AI agent use the apps on your Android phone, while you keep using it.

Agents work on a **background display** you never see; your screen stays yours. It works with any
agent on any device (Claude Code, Codex, or your own) over MCP or from scripts.

## Use it

1. Build the APK (`./gradlew assembleDebug`), install it and follow the setup guide in the app
   (Android 11+).
2. Tap **Connect an agent**, copy the instructions, and paste them to your agent.

That is all. The agent can now open apps, read the screen, tap, type and swipe:

```js
import { Phone } from "./miu-bridge.mjs";
const phone = new Phone();
await phone.launch("com.android.settings");
await phone.tap("text=Wi-Fi");
```

## What you should know

- **Same Wi-Fi needs no account and no server.** From other networks, sign in and traffic goes
  through a relay. The relay forwards and does not store what passes through; you can
  [run your own](relay/) and point the app at it. The hosted relay is free for 1,000 remote tool
  calls per account per month; same Wi-Fi is never counted, and your own relay sets its own allowance.
- **The token is the key to your phone.** Give it only to agents you trust.
- Payment and banking apps are refused. Payments, sign-ins and verifications are handed back to you.
- Automating an app may break that app's terms. That is your call and your responsibility.

## More

- [How it works, limits, building](docs/internals.md)
- [Script SDK](sdk/README.md)
- [Security](SECURITY.md)

Apache-2.0.
