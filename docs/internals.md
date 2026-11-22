# Miu Bridge internals

An Android app that lets AI agents (Claude Code, Codex, Muse, Instinct, …) operate apps on this
phone through MCP. Agents work on a **background virtual display**; the owner keeps using the main
screen. `phone_handoff` moves a task to the main screen for the
owner to finish.

## How it works

- `phone/`: the app connects to the phone's own adbd (`127.0.0.1:5555`) with its own RSA key, then
  starts `helper/HelperMain` as the shell user (`app_process`). The helper creates the background
  display, launches apps on it and injects input. `ScreenReaderService` (accessibility, enabled by the
  helper) reads only that display.
- `server/`: MCP over Streamable HTTP (`POST /mcp`, JSON responses), port 8765, bearer token. One tool
  call at a time. A request may instead come sealed (`server/E2e.kt`): end-to-end encrypted with keys
  made from the token, which is then not sent. The SDKs do this always, on the Wi-Fi and through the relay.
- `BridgeService`: foreground service with a stop button; restarts after reboot if it was on.

## Set up a phone (no computer, Android 11+)

1. Install the APK and open Miu Bridge. The setup checklist shows each step as ✓/✗ with a button
   to the right system page.
2. Developer options on (tap the build number seven times).
3. ② 无线调试配对: opens Wireless debugging. Turn it on, tap "Pair device with pairing code", pull
   down the notification shade and type the six digits into the Miu Bridge notification.
   The app pairs (`phone/AdbPairing.kt`: TLS 1.3 + SPAKE2, as `adb pair`), connects to adbd's TLS
   port found over mDNS, and starts the background screen.
4. Battery and auto-start permissions.

After pairing the app grants itself `WRITE_SECURE_SETTINGS` through its shell, so after a reboot it
switches wireless debugging back on and reconnects by itself (Android only allows this on a Wi-Fi
network the owner allowed). Wireless debugging needs Wi-Fi.

Phones without a Wireless debugging page (HarmonyOS 4) still need a computer once per boot:
`adb tcpip 5555`, then 启动后台服务 and allow the USB debugging prompt.

## Connect an agent

Any agent on any device works. On the phone tap 连接一个 agent (Connect an agent), choose the network
path (any network goes through the relay; same Wi-Fi does not) and copy the connection details: one text with
the address, the token and both ways to use them. The agent decides which.

Agents that write scripts in their own sandbox do not need MCP: `sdk/miu_bridge.py` and
`sdk/miu-bridge.mjs` (no dependencies) turn `phone.tap("text=Send")` into a request to the phone.
The relay serves them and their guide at `/sdk/`. Run with the argument `mcp`, either file is a local
stdio MCP server that carries a client's requests to the phone end-to-end encrypted. An MCP client
that posts to the address itself is not encrypted that way; More → "Encrypted remote access only"
refuses such requests when they come through the relay.

`phone_script` is code mode: one call runs a JavaScript script on the phone that calls the other phone
tools (`call(name, args)`, `print`, `sleep`), so many steps take one round trip and only the printed
output comes back, with up to four screenshots the script took. It runs in a hidden WebView with no
network (`core/ScriptRunner.kt`). `phone_launch` returns once the app's first screen has elements.

While an agent is using the background display, a live thumbnail floats in the top-right corner of the
app (toggle it with the phone icon in the app bar; drag it anywhere); tap it for the full view. In a
window 600 dp or wider (an unfolded foldable, a tablet) the display gets a pane of its own beside the
content instead.

Tools: `phone_apps`, `phone_launch`, `phone_snapshot`, `phone_screenshot`, `phone_tap`,
`phone_long_press`, `phone_swipe`, `phone_type`, `phone_key`, `phone_open_url`, `phone_handoff`.

## Limits

- On phones without wireless debugging, `adb tcpip` is lost on reboot.
- Android 12 / HarmonyOS behaviour is untested. Without a trusted display (Android < 13 may refuse
  one), the keyboard may appear on the main screen and the background display may stop when the
  screen turns off.
- `FLAG_SECURE` screens screenshot black. `uiautomator dump` turns the accessibility service off while
  it runs.

## Build and test

```sh
./gradlew assembleDebug testDebugUnitTest
```

A release build (`./gradlew assembleRelease`) is signed with your own key when `keystore.properties`
exists in the project root, and debug-signed otherwise. The file is git-ignored:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Relay

The relay (`relay/`, a Cloudflare Worker with its devices in D1) is what agents on other networks go
through. Turning on remote access needs no account: the phone registers itself under a hash of the id
Android gives this app on that phone, and keeps a WebSocket to the relay. Registering again on the same
phone keeps the same address and the month's count. The app uses the hosted relay unless the owner enters another under
More → Relay server (`server/Account.kt`); `relayUrl` in `gradle.properties` changes the built-in
default. See [relay/README.md](../relay/README.md) to run one.

## UI and strings

The UI is Jetpack Compose Material 3 (`ui/Theme.kt` holds the palette). All user-facing text lives in
`tools/strings.py`, one table with English and Chinese side by side; run it to regenerate
`res/values/strings.xml` and `res/values-zh/strings.xml`. The relay returns stable error `code`s that
`server/Account.kt` maps to those strings.

## On-phone agent models

`agent/LlmClient.kt` speaks OpenAI-style chat completions. Presets: DeepSeek, OpenAI, Anthropic and
Google Gemini (their OpenAI-compatible endpoints), OpenRouter, or any custom base URL. The model list
is read from the service with the owner's key, and a thinking effort can be set per provider. Only
DeepSeek has been run end to end. The on-phone agent also has `bash`: Android's sh in the app's own
sandbox, limited to the workspace directory.

## The background display

It is the same on every phone: 1080 x 2400 at 420 dpi, upright, whatever the device's own screen is
or how a foldable is folded. Agents see one layout and positions noted on one phone mean the same on
another. Font size, language, theme and app versions still differ between phones.

## Known adbd behaviour

If the "Allow USB debugging?" prompt goes away unanswered, adbd keeps waiting for that answer and
queues every later prompt, so no new prompt appears. Turning USB debugging off and on clears it
(from a computer: `adb shell service call adb 2` denies the stale one).

## App notes

No app manuals ship with the app. What agents learn about an app on a phone is recorded there as
dated facts (`phone_skill_learn`), corrected (`replaces`) or removed (`phone_skill_forget`), and the
oldest give way when a list is full. `core/Skills.kt`.

## Naming elements

A ref in a snapshot stands for an element, not a coordinate: the live node is asked where it is at
the moment of the action, and if the app replaced the node it is found again by role, name and id
(`PhoneController.locate`). Tools also take selectors that need no snapshot: `text=Send`,
`text="Send"`, `id=send_btn`, `role=button[name="Send"]`, optionally ` >> nth=N`; a selector must
match exactly one element and is waited for up to 3 s. `phone_wait` waits for an element to appear or
go. A position must hold still before it is tapped, so a page that is still loading does not shift
the tap. After the background display restarts, the next result carries a `notice`.

## Pacing

`phone/Pacing.kt` holds actions to a human tempo without naming any app. The phone's own apps and
apps without internet access are not paced; other apps wait a little between actions (longer after
arriving on a new screen, counting time the agent already spent thinking) and allow only so many new
screens in a row. An app that shows a rate-limit or verification screen becomes slow from then on,
the result carries a `warning`, and the fact is recorded in its notes. Swipes follow a bowed, eased
path (`phone/GesturePath.kt`). The owner can switch pacing off in the app.
