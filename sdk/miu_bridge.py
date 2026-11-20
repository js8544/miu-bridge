"""Miu Bridge SDK for Python: write a normal script in your own sandbox; every call acts on the phone.

    from miu_bridge import Phone
    phone = Phone()                       # reads MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN
    phone.launch("com.android.settings")
    screen = phone.tap("text=Network")    # a selector, located on the phone at that moment
    print(screen.text)

Standard library only. Each call is one HTTPS request to the phone (through the relay it takes about a
second), so for long runs of steps prefer `phone.script(js)`, which runs them on the phone in one request.

Elements are named by a ref from the latest screen ("e12") or by a selector that needs no screen read:
text=Send, text="Send" (exact), id=send_btn, role=button[name="Send"], optionally followed by " >> nth=N".
"""
import base64
import http.client
import json
import os
import re
import time
import urllib.parse
import urllib.request
import uuid

__all__ = ["Phone", "PhoneError", "Screen", "Screenshot"]

USER_AGENT = "miu-bridge-sdk/1.2 (python)"


class PhoneError(Exception):
    """The phone refused or could not do what was asked. The message says why, in words meant for the agent.

    `code` is stable and meant for branching: phone_locked, display_not_ready, not_found, ambiguous,
    not_actionable, wait_timeout, timeout, paced, blocked_app, phone_offline, unauthorized, ... (see the guide).
    """

    def __init__(self, message, code="failed"):
        super().__init__(message)
        self.code = code


class Screen:
    """What the phone shows after an action: the element tree as text, and anything the phone wants you to know."""

    def __init__(self, data, images=()):
        self.data = data
        self.text = data.get("snapshot", "")       # one line per element: [e5] button "Send" @540,1620 #send_btn
        self.package = data.get("package")         # the app in front, or None
        self.done = data.get("done")               # what the action did
        self.warning = data.get("warning")         # a rate-limit or verification screen: stop and hand off
        self.notice = data.get("notice")           # e.g. the background display was restarted
        self.loading = bool(data.get("loading"))     # still loading after the wait: wait() for the content you expect
        self.restarted = bool(data.get("restarted"))  # the display was restarted: apps on it were closed, launch again
        # The picture of this screen, when you asked for one (screenshot=True) or the app exposes no element tree.
        self.screenshot = Screenshot(data, images[0]) if images else None

    @property
    def lines(self):
        return self.text.split("\n")[1:]

    def find(self, pattern):
        """The ref of the first element whose line matches the regular expression, or None."""
        for line in self.lines:
            if re.search(pattern, line):
                m = re.search(r"\[(e\d+)\]", line)
                if m:
                    return m.group(1)
        return None

    def texts(self):
        """The quoted labels on the screen, in order."""
        return [m.group(1) for m in (re.search(r'"([^"]*)"', line) for line in self.lines) if m]

    def __str__(self):
        return self.text


class Screenshot:
    """A JPEG of the background display. Image pixels times `scale` are display pixels for tap(x=, y=)."""

    def __init__(self, data, jpeg):
        self.jpeg = jpeg
        self.scale = data.get("scale", 1)
        self.width = data.get("image_width")
        self.height = data.get("image_height")

    def save(self, path):
        with open(path, "wb") as f:
            f.write(self.jpeg)
        return path


class Phone:
    def __init__(self, url=None, token=None, timeout=150, retries=2):
        self.url = url or os.environ.get("MIU_BRIDGE_URL")
        self.token = token or os.environ.get("MIU_BRIDGE_TOKEN")
        if not self.url or not self.token:
            raise PhoneError("set MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN, or pass url and token")
        self.timeout = timeout
        self.retries = retries
        self._id = 0
        self._connection = None
        self.last_timing = {}
        self._path = "/"
        self._proxy_headers = {}

    # --- transport ---

    def close(self):
        """Lets go of the connection. The next call opens a new one, so calling this is optional."""
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None

    def call(self, tool, **arguments):
        """Runs one phone tool by name and returns (data, images). The methods below are thin wrappers over this.

        A request that gets no answer (a timeout, a dropped connection, the relay or phone briefly away) is sent
        again with the same request id; the phone answers a repeat with its first result, so nothing is done twice.
        """
        self._id += 1
        clean = {k: v for k, v in arguments.items() if v is not None and v is not False}
        clean["request_id"] = uuid.uuid4().hex
        body = json.dumps({"jsonrpc": "2.0", "id": self._id, "method": "tools/call", "params": {"name": tool, "arguments": clean}}).encode()
        for attempt in range(self.retries + 1):
            try:
                return self._send(body)
            except PhoneError as e:
                if e.code not in ("unreachable", "phone_offline", "relay_timeout") or attempt == self.retries:
                    raise
                time.sleep(1 + 2 * attempt)

    def _note_timing(self, header, seconds, new_connection):
        """Keeps where the last call's time went in `last_timing` (milliseconds): `total_ms` as seen from here, each
        part the relay and the phone reported (`edge`, `relay`, `link`, `phone`), `network_ms` for what is left over
        (the way from here to the first server and back), and the places Cloudflare handled it in `where`."""
        timing = {"total_ms": round(seconds * 1000), "new_connection": new_connection}
        where = {}
        for part in (header or "").split(","):
            name, _, rest = part.strip().partition(";")
            dur = re.search(r"dur=([0-9.]+)", rest)
            if name and dur:
                timing[name + "_ms"] = round(float(dur.group(1)))
            place = re.search(r'desc="([^"]*)"', rest)
            if name and place and place.group(1):
                where[name] = place.group(1)
        timing["network_ms"] = max(0, timing["total_ms"] - sum(v for k, v in timing.items() if k in ("edge_ms", "relay_ms", "link_ms", "phone_ms")))
        if where:
            timing["where"] = where
        self.last_timing = timing

    def _connect(self):
        """Opens the one connection this object keeps to the phone or its relay, through the environment's proxy if any."""
        target = urllib.parse.urlsplit(self.url)
        secure = target.scheme == "https"
        port = target.port or (443 if secure else 80)
        self._path = (target.path or "/") + ("?" + target.query if target.query else "")
        proxy = None if urllib.request.proxy_bypass(target.hostname) else urllib.request.getproxies().get(target.scheme)
        if not proxy:
            cls = http.client.HTTPSConnection if secure else http.client.HTTPConnection
            return cls(target.hostname, port, timeout=self.timeout)
        via = urllib.parse.urlsplit(proxy if "://" in proxy else "http://" + proxy)
        auth = {}
        if via.username:
            pair = urllib.parse.unquote(via.username) + ":" + urllib.parse.unquote(via.password or "")
            auth["Proxy-Authorization"] = "Basic " + base64.b64encode(pair.encode()).decode()
        cls = http.client.HTTPSConnection if (secure or via.scheme == "https") else http.client.HTTPConnection
        connection = cls(via.hostname, via.port or (443 if via.scheme == "https" else 8080), timeout=self.timeout)
        if secure:
            connection.set_tunnel(target.hostname, port, headers=auth)
        else:  # a plain-http target through a proxy is asked for by its full address
            self._path, self._proxy_headers = self.url, auth
        return connection

    def _send(self, body):
        headers = {
            "Content-Type": "application/json", "Accept": "application/json", "Authorization": "Bearer " + self.token,
            # Named on purpose: the relay's CDN refuses Python's default "Python-urllib" signature with a 403.
            "User-Agent": USER_AGENT,
        }
        # One connection for the life of this object: opening a new one costs several round trips each call.
        started = time.time()
        for fresh in (False, True):
            reused = self._connection is not None and not fresh
            try:
                if self._connection is None:
                    self._proxy_headers = {}
                    self._connection = self._connect()
                self._connection.request("POST", self._path, body=body, headers={**headers, **self._proxy_headers})
                response = self._connection.getresponse()
                status, raw = response.status, response.read()
                self._note_timing(response.getheader("Server-Timing"), time.time() - started, not reused)
                break
            except (http.client.HTTPException, OSError) as e:
                self.close()
                # A kept connection the other side has since closed: say it once more on a new one. The request id
                # makes the repeat safe. Anything else is a real failure to reach the phone.
                if reused and isinstance(e, (http.client.RemoteDisconnected, http.client.CannotSendRequest, ConnectionError)):
                    continue
                raise PhoneError(f"cannot reach the phone: {e}", "unreachable") from None
        if status != 200:
            detail = raw.decode("utf-8", "replace")[:300]
            hint = {401: "the token was rejected", 503: "the phone is offline", 504: "the phone did not answer in time"}.get(status, "")
            code = {401: "unauthorized", 502: "unreachable", 503: "phone_offline", 504: "relay_timeout"}.get(status, "http_error")
            if status == 403 and "1010" in detail:
                hint = "the relay's CDN refused this client's signature; send a User-Agent header that names your client"
            raise PhoneError(f"HTTP {status} {hint}: {detail}".strip(), code)
        try:
            reply = json.loads(raw)
        except ValueError:
            raise PhoneError("the answer was not JSON: " + raw.decode("utf-8", "replace")[:200], "unreachable") from None
        if "error" in reply:
            raise PhoneError(str(reply["error"].get("message", reply["error"])), "bad_request")
        result = reply.get("result", {})
        content = result.get("content", [])
        text = "".join(c.get("text", "") for c in content if c.get("type") == "text")
        if result.get("isError"):
            raise PhoneError(text, (result.get("structuredContent") or {}).get("code", "failed"))
        images = [base64.b64decode(c["data"]) for c in content if c.get("type") == "image"]
        phone_parts = (result.get("_meta") or {}).get("timing")
        if phone_parts:
            self.last_timing["phone_parts"] = phone_parts
        try:
            return json.loads(text), images
        except ValueError:
            return {"text": text}, images

    def _screen(self, tool, **arguments):
        return Screen(*self.call(tool, **arguments))

    # --- looking ---

    def apps(self, query=None):
        """Launchable apps: [{"label", "package", "blocked"?}]; with a query, only those whose label or package contains it."""
        return self.call("phone_apps", query=query)[0].get("apps", [])

    def status(self):
        """The phone's state without touching it: locked, ready, display {id, width, height, dpi}, package, reader, restarted, version."""
        return self.call("phone_status")[0]

    def snapshot(self, screenshot=False):
        """The screen as text. Every method that returns a Screen takes screenshot=True to get its picture too (screen.screenshot)."""
        return self._screen("phone_snapshot", screenshot=screenshot)

    def screenshot(self):
        data, images = self.call("phone_screenshot")
        if not images:
            raise PhoneError("the phone returned no image")
        return Screenshot(data, images[0])

    def wait(self, target, state="visible", timeout_ms=5000, screenshot=False):
        """Waits until the element is on the screen (or, state="gone", until it is not). Raises PhoneError (wait_timeout) on timeout."""
        return self._screen("phone_wait", target=target, state=state, timeout_ms=timeout_ms, screenshot=screenshot)

    # --- acting ---

    def launch(self, package, screenshot=False):
        return self._screen("phone_launch", package=package, screenshot=screenshot)

    def open_url(self, url, screenshot=False):
        return self._screen("phone_open_url", url=url, screenshot=screenshot)

    def tap(self, target=None, x=None, y=None, screenshot=False):
        """Taps an element (ref or selector) or a point in display pixels."""
        return self._screen("phone_tap", ref=target, x=x, y=y, screenshot=screenshot)

    def long_press(self, target=None, x=None, y=None, screenshot=False):
        return self._screen("phone_long_press", ref=target, x=x, y=y, screenshot=screenshot)

    def swipe(self, direction=None, x1=None, y1=None, x2=None, y2=None, duration_ms=None, screenshot=False):
        """direction is the finger's movement: "up" shows what is further down."""
        return self._screen("phone_swipe", direction=direction, x1=x1, y1=y1, x2=x2, y2=y2, duration_ms=duration_ms, screenshot=screenshot)

    def type(self, text, target=None, screenshot=False):
        """Replaces the text of an input (the focused one if no target). Does not press Enter."""
        return self._screen("phone_type", text=text, ref=target, screenshot=screenshot)

    def key(self, key, screenshot=False):
        """back, enter, or home (home closes the apps on the background display)."""
        return self._screen("phone_key", key=key, screenshot=screenshot)

    def handoff(self):
        """Moves the app to the owner's own screen: for payment, login, verification, anything irreversible."""
        return self.call("phone_handoff")[0]

    def script(self, code):
        """Runs JavaScript on the phone that chains the tools in one request; returns what it printed."""
        return self.call("phone_script", code=code)[0].get("output", "")

    # --- notes agents keep about apps on this phone ---

    def notes(self):
        return self.call("phone_skills")[0].get("skills", [])

    def read_notes(self, name):
        return self.call("phone_skill_read", name=name)[0].get("skill", "")

    def learn(self, name, fact, description=None, replaces=None):
        return self.call("phone_skill_learn", name=name, fact=fact, description=description, replaces=replaces)[0]

    def forget(self, name, fact):
        return self.call("phone_skill_forget", name=name, fact=fact)[0]
