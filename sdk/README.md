# Miu Bridge SDK

Operate apps on the owner's Android phone from a script in your own sandbox. You write ordinary
Python or JavaScript; each call is carried out on the phone, on a background display the owner does
not see, and returns what the screen shows afterwards.

The same address and token also work as an MCP server (Streamable HTTP, header `Authorization: Bearer <token>`)
with the same tools. Use whichever suits you: MCP tool calls, scripts in your sandbox with this SDK, or both.

## Set up

```sh
curl -fsSO https://phone.jinshang.ai/sdk/miu_bridge.py      # Python, standard library only
curl -fsSO https://phone.jinshang.ai/sdk/miu-bridge.mjs     # JavaScript (Node 18+, Deno, Bun), no dependencies
export MIU_BRIDGE_URL=...     # from the owner: the phone's address
export MIU_BRIDGE_TOKEN=...   # from the owner: the key to the phone; never print or share it
```

## Use

```python
from miu_bridge import Phone, PhoneError

phone = Phone()
phone.launch("com.android.settings")
screen = phone.tap("text=Network")          # returns the screen after the tap
for label in screen.texts():
    print(label)
phone.wait("text=Wi-Fi")                    # wait for a page to load
phone.screenshot().save("screen.jpg")       # when the element tree is not enough
```

```js
import { Phone } from "./miu-bridge.mjs";
const phone = new Phone();
await phone.launch("com.android.settings");
const screen = await phone.tap("text=Network");
console.log(screen.texts());
```

| Python | JavaScript | What it does |
| --- | --- | --- |
| `apps(query=None)` | `apps(query)` | Launchable apps (label, package), all or those matching a word. |
| `status()` | `status()` | The phone's state without touching it: locked, ready, display size, app in front, restarted, version. |
| `launch(package)` | `launch(pkg)` | Open an app on the background display. |
| `open_url(url)` | `openUrl(url)` | Open a link with the app that handles it. |
| `snapshot(screenshot=True)` | `snapshot({screenshot: true})` | The current screen as text, and its picture in the same call. |
| `screenshot()` | `screenshot()` | A JPEG; `.save(path)`, `.scale`. |
| `tap(target)` / `tap(x=, y=)` | `tap(target)` / `tap({x, y})` | Tap an element or a point. |
| `long_press(...)` | `longPress(...)` | Long-press. |
| `swipe("up")` | `swipe("up")` | Swipe; "up" shows what is further down. |
| `type(text, target=None)` | `type(text, target)` | Replace an input's text. |
| `key("back")` | `key("back")` | back, enter, home. |
| `wait(target, state, timeout_ms)` | `wait(target, {state, timeoutMs})` | Wait for an element to appear or go. |
| `handoff()` | `handoff()` | Give the app to the owner (payment, login, verification). |
| `takeback()` | `takeback()` | Take that app back as the owner left it, once they say they are done. |
| `script(js)` | `script(js)` | Run steps on the phone in one request. |
| `notes()`, `read_notes(name)`, `learn(...)`, `forget(...)` | `notes()`, `readNotes(name)`, `learn(...)`, `forget(...)` | What agents have learned about apps on this phone. |

Every action returns a `Screen`: `.text` (one line per element, `[e5] button "Send" @540,1620 #send_btn`),
`.lines`, `.texts()`, `.find(regex)` (a ref), `.package`, `.warning`, `.notice`, `.restarted`, `.loading`, `.screenshot`.

Every method that returns a `Screen` takes `screenshot=True` (JavaScript: a last argument `{screenshot: true}`),
and the picture of the new screen comes back in the same call as `.screenshot`. For an app that exposes
no element tree the picture comes without asking.

Failures raise `PhoneError`: the message says what to do, and `.code` is stable for branching:

| Code | Meaning |
| --- | --- |
| `phone_locked` | The owner must unlock the phone. |
| `display_not_ready`, `helper_failed`, `reader_unavailable` | The phone side is not up; `status()` says more. |
| `not_found`, `ambiguous`, `not_actionable` | No such element; several match (add `>> nth=N`); disabled or off-screen. |
| `wait_timeout` | `wait()` ran out of time. |
| `timeout`, `relay_timeout` | No answer in time; the action may or may not have happened. |
| `paced` | Too many screens of one app in a row; stop in that app for now. |
| `quota_exceeded` | The phone's monthly allowance of remote calls is used up. Only through the relay; on the phone's own Wi-Fi there is no limit. |
| `blocked_app`, `not_installed`, `on_main_display` | The app cannot be opened here. |
| `phone_offline`, `unreachable`, `unauthorized` | The phone is not connected; the network failed; the token is wrong. |
| `bad_request`, `unknown_tool`, `script_error`, `failed` | The call itself was wrong, or anything else. A script stopped by a failed tool call carries that call's code. |

## How to work

Write a script per round, not a call per step: do every step that what you know now lets you plan, stop
and look only when what you see would change what you do next, and end by printing what the next round
needs (the facts you were after, or the part of the final screen you will act on).

## Naming elements

- A ref from the latest screen: `"e12"`. It keeps working after the screen changes as long as the element is there.
- A selector, which needs no screen read: `text=Send` (contains), `text="Send"` (exact), `id=send_btn`,
  `role=button[name="Send"]`, optionally followed by ` >> nth=N`. It must match exactly one element; if it
  matches several the error lists them. It is waited for up to 3 seconds.
- A point, when the app exposes no element tree: read a screenshot and multiply image pixels by `.scale`.

## Good to know

- If you call the address yourself instead of through this SDK, send a `User-Agent` header that names
  your client. The relay's CDN answers some default signatures (Python's `Python-urllib`, for one) with
  `403` and `error code: 1010`; that is the CDN, not the phone, and not your token.

- A call that gets no answer (a timeout, a dropped connection, the phone briefly offline) is sent again by
  the SDK, up to twice, with the same request id. The phone answers a repeat with its first result, so a
  retried tap is not a second tap. Calling the address yourself, pass your own `request_id` argument
  (any string, new for each action) and repeat the call with the same one.
- Through the relay, each tool call counts once against the owner's monthly allowance (1,000 on the free
  plan), whatever it does on the phone: a `script(js)` with twenty steps is one call. Calls on the phone's own
  Wi-Fi do not count. When it runs out, calls fail with `quota_exceeded` until next month.
- Each call takes about a second through the relay (milliseconds on the same Wi-Fi). For a long run of
  steps you already know, `script(js)` runs them on the phone in one request: inside it, `call(name, args)`
  runs a tool, `print(x)` adds output, `sleep(ms)` waits. If it fails midway the steps already done are not
  undone; the error says how many succeeded.
- `launch()` returns once the app's first screen is there, and an action waits out a loading indicator it
  started (about 6 s at most). If the screen has `.loading` set it is still loading: `wait()` for what you expect.
- In apps with an account behind them the phone holds actions to a human tempo and may refuse to go
  further after many screens in a row. This protects the owner's account; do not work around it.
- If a result has a `warning` (a rate-limit or verification screen), stop acting in that app and `handoff()`.
- If a screen has `.restarted` set, the background display was restarted and apps that were open are closed;
  that is not a crash. Launch again.
- Never pay, transfer money, place orders, delete data or change security settings. Payment and banking
  apps are refused. On Android 12 and earlier the phone must be unlocked.
