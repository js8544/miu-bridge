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

Requests are end-to-end encrypted between here and the phone with keys made from the token, which is
itself never sent: a relay or network in between can read neither what you ask nor what comes back.
With the `cryptography` package installed its AES is used; without it the one in this file, which
takes about a tenth of a second for a screenshot and a millisecond for a screen as text.

`python miu_bridge.py mcp` runs this file as a local MCP server (stdio) that carries an MCP client's
requests to the phone the same way, for clients that would otherwise send them to the URL in the clear.
"""
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import sys
import time
import urllib.parse
import urllib.request
import uuid

__all__ = ["Phone", "PhoneError", "Screen", "Screenshot"]

USER_AGENT = "miu-bridge-sdk/1.3 (python)"


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


# --- end-to-end encryption: P-256 and AES-256-GCM, as the app's server/E2e.kt describes the protocol ---

_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
_G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
      0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)


def _point_add(p, q):
    """The sum of two points on P-256; None is the point at infinity."""
    if p is None or q is None:
        return p or q
    (x1, y1), (x2, y2) = p, q
    if x1 == x2:
        if (y1 + y2) % _P == 0:
            return None
        slope = (3 * x1 * x1 - 3) * pow(2 * y1, -1, _P) % _P
    else:
        slope = (y2 - y1) * pow(x2 - x1, -1, _P) % _P
    x = (slope * slope - x1 - x2) % _P
    return x, (slope * (x1 - x) - y1) % _P


def _point_mul(k, point):
    out = None
    while k:
        if k & 1:
            out = _point_add(out, point)
        point = _point_add(point, point)
        k >>= 1
    return out


def _point_bytes(point):
    return b"\x04" + point[0].to_bytes(32, "big") + point[1].to_bytes(32, "big")


def _point_from(raw):
    """An uncompressed point, refused unless it is on the curve."""
    x, y = int.from_bytes(raw[1:33], "big"), int.from_bytes(raw[33:], "big")
    if len(raw) != 65 or raw[0] != 4 or x >= _P or y >= _P or (y * y - (x * x * x - 3 * x + _B)) % _P:
        raise PhoneError("the phone's key is not a valid one", "e2e_rejected")
    return x, y


def _hkdf(salt, ikm, info, length=64):
    """HKDF-SHA256 (RFC 5869)."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out = block = b""
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([len(out) // 32 + 1]), hashlib.sha256).digest()
        out += block
    return out[:length]


def _sbox(u, ones):
    """The AES S-box on eight integers holding one bit of every byte each (most significant first), as the circuit
    of Boyar and Peralta: 113 operations however many bytes there are. `ones` has a 1 for every byte."""
    u0, u1, u2, u3, u4, u5, u6, u7 = u
    t1 = u0 ^ u3; t2 = u0 ^ u5; t3 = u0 ^ u6; t4 = u3 ^ u5; t5 = u4 ^ u6; t6 = t1 ^ t5; t7 = u1 ^ u2
    t8 = u7 ^ t6; t9 = u7 ^ t7; t10 = t6 ^ t7; t11 = u1 ^ u5; t12 = u2 ^ u5; t13 = t3 ^ t4; t14 = t6 ^ t11
    t15 = t5 ^ t11; t16 = t5 ^ t12; t17 = t9 ^ t16; t18 = u3 ^ u7; t19 = t7 ^ t18; t20 = t1 ^ t19; t21 = u6 ^ u7
    t22 = t7 ^ t21; t23 = t2 ^ t22; t24 = t2 ^ t10; t25 = t20 ^ t17; t26 = t3 ^ t16; t27 = t1 ^ t12
    m1 = t13 & t6; m2 = t23 & t8; m3 = t14 ^ m1; m4 = t19 & u7; m5 = m4 ^ m1; m6 = t3 & t16; m7 = t22 & t9
    m8 = t26 ^ m6; m9 = t20 & t17; m10 = m9 ^ m6; m11 = t1 & t15; m12 = t4 & t27; m13 = m12 ^ m11; m14 = t2 & t10
    m15 = m14 ^ m11; m16 = m3 ^ m2; m17 = m5 ^ t24; m18 = m8 ^ m7; m19 = m10 ^ m15; m20 = m16 ^ m13; m21 = m17 ^ m15
    m22 = m18 ^ m13; m23 = m19 ^ t25; m24 = m22 ^ m23; m25 = m22 & m20; m26 = m21 ^ m25; m27 = m20 ^ m21
    m28 = m23 ^ m25; m29 = m28 & m27; m30 = m26 & m24; m31 = m20 & m23; m32 = m27 & m31; m33 = m27 ^ m25
    m34 = m21 & m22; m35 = m24 & m34; m36 = m24 ^ m25; m37 = m21 ^ m29; m38 = m32 ^ m33; m39 = m23 ^ m30
    m40 = m35 ^ m36; m41 = m38 ^ m40; m42 = m37 ^ m39; m43 = m37 ^ m38; m44 = m39 ^ m40; m45 = m42 ^ m41
    m46 = m44 & t6; m47 = m40 & t8; m48 = m39 & u7; m49 = m43 & t16; m50 = m38 & t9; m51 = m37 & t17
    m52 = m42 & t15; m53 = m45 & t27; m54 = m41 & t10; m55 = m44 & t13; m56 = m40 & t23; m57 = m39 & t19
    m58 = m43 & t3; m59 = m38 & t22; m60 = m37 & t20; m61 = m42 & t1; m62 = m45 & t4; m63 = m41 & t2
    l0 = m61 ^ m62; l1 = m50 ^ m56; l2 = m46 ^ m48; l3 = m47 ^ m55; l4 = m54 ^ m58; l5 = m49 ^ m61; l6 = m62 ^ l5
    l7 = m46 ^ l3; l8 = m51 ^ m59; l9 = m52 ^ m53; l10 = m53 ^ l4; l11 = m60 ^ l2; l12 = m48 ^ m51; l13 = m50 ^ l0
    l14 = m52 ^ m61; l15 = m55 ^ l1; l16 = m56 ^ l0; l17 = m57 ^ l1; l18 = m58 ^ l8; l19 = m63 ^ l4; l20 = l0 ^ l1
    l21 = l1 ^ l7; l22 = l3 ^ l12; l23 = l18 ^ l2; l24 = l15 ^ l9; l25 = l6 ^ l10; l26 = l7 ^ l9; l27 = l8 ^ l10
    l28 = l11 ^ l14; l29 = l11 ^ l17
    return (l6 ^ l24, l16 ^ l26 ^ ones, l19 ^ l28 ^ ones, l6 ^ l21, l20 ^ l22, l25 ^ l29, l13 ^ l27 ^ ones, l6 ^ l23 ^ ones)


class _Aes:
    """AES-256 encryption of many blocks at once. Python has no fast way to do one block, but it is fast on very
    long integers, so the blocks are turned on their side: eight integers, each holding one bit of every byte, and
    each step of the cipher becomes a few operations on those whatever the number of blocks. In such an integer a
    block is 16 bits, its first byte the highest; byte 4c+r is row r of column c."""

    _table = None

    def __init__(self, key):
        if _Aes._table is None:  # the S-box as a table, for the key schedule: the circuit run over all 256 bytes
            out = _sbox([sum((v >> bit & 1) << v for v in range(256)) for bit in range(7, -1, -1)], (1 << 256) - 1)
            _Aes._table = [sum((out[k] >> v & 1) << (7 - k) for k in range(8)) for v in range(256)]
        table = _Aes._table
        sub = lambda w: table[w >> 24] << 24 | table[w >> 16 & 255] << 16 | table[w >> 8 & 255] << 8 | table[w & 255]
        words = [int.from_bytes(key[i:i + 4], "big") for i in range(0, 32, 4)]
        rcon = 1
        for i in range(8, 60):
            w = words[-1]
            if i % 8 == 0:
                w = sub((w << 8 | w >> 24) & 0xFFFFFFFF) ^ rcon << 24
                rcon <<= 1
            elif i % 8 == 4:
                w = sub(w)
            words.append(words[-8] ^ w)
        # For each round and each bit of a byte, the 16 bits that bit takes across the round key's bytes.
        self._keys = []
        for i in range(0, 60, 4):
            k = b"".join(w.to_bytes(4, "big") for w in words[i:i + 4])
            self._keys.append([sum((k[p] >> bit & 1) << (15 - p) for p in range(16)) for bit in range(8)])

    def encrypt(self, data):
        """Encrypts each 16-byte block of `data` on its own."""
        size, count = len(data), len(data) // 16
        every = ((1 << 16 * count) - 1) // 0xFFFF  # a 1 at the foot of every block
        ones = every * 0xFFFF
        bytewise = int.from_bytes(b"\x01" * size, "big")
        # On its side: bit by bit, the bytes as text of 0s and 1s, read back as one binary number.
        x = int.from_bytes(data, "big")
        s = [int(((x >> bit & bytewise) | bytewise * 0x30).to_bytes(size, "big"), 2) for bit in range(8)]
        row0, rest, upper, lower = every * 0x8888, every * 0x7777, every * 0x3333, every * 0xCCCC
        # ShiftRows, row by row: the columns that move left within the block, and those that wrap round.
        moves = [(every * a, 4 * r, every * b, 16 - 4 * r) for r, a, b in ((1, 0x0444, 0x4000), (2, 0x0022, 0x2200), (3, 0x0001, 0x1110))]
        for number, key in enumerate(self._keys):
            if number:
                s = list(_sbox(s[::-1], ones)[::-1])
                for bit in range(8):
                    v = s[bit]
                    w = v & row0
                    for stay, left, wrap, right in moves:
                        w |= (v & stay) << left | (v & wrap) >> right
                    s[bit] = w
                if number < 14:  # MixColumns: each byte with the one below it doubled, plus the other three
                    below = [(v & rest) << 1 | (v & row0) >> 3 for v in s]
                    t = [v ^ b for v, b in zip(s, below)]
                    two = [(v & upper) << 2 | (v & lower) >> 2 for v in t]
                    high = t[7]
                    double = [high, t[0] ^ high, t[1], t[2] ^ high, t[3] ^ high, t[4], t[5], t[6]]
                    s = [d ^ b ^ w for d, b, w in zip(double, below, two)]
            s = [v ^ every * k for v, k in zip(s, key)]
        # Upright again: each integer written out as 0s and 1s is already one byte for every bit.
        out = 0
        for bit in range(8):
            out |= (int.from_bytes(format(s[bit], "0%db" % size).encode(), "big") & bytewise) << bit
        return out.to_bytes(size, "big")


class _Gcm:
    """AES-256-GCM with a 12-byte nonce and a 16-byte tag after the ciphertext (NIST SP 800-38D)."""

    def __init__(self, key):
        self._aes = _Aes(key)
        # Multiplying by the hash key, a byte at a time: one table of 256 products for each byte of a block.
        power = int.from_bytes(self._aes.encrypt(bytes(16)), "big")
        bits = []
        for _ in range(128):
            bits.append(power)
            power = power >> 1 ^ (0xE1 << 120 if power & 1 else 0)
        self._products = []
        for at in range(0, 128, 8):
            table = [0] * 256
            for value in range(1, 256):
                low = value & -value
                table[value] = table[value ^ low] ^ bits[at + 7 - low.bit_length() + 1]
            self._products.append(table)

    def _stream(self, nonce, length):
        """The mask for the tag, and `length` bytes to add to the text: the cipher run over a counter."""
        blocks = self._aes.encrypt(b"".join(nonce + i.to_bytes(4, "big") for i in range(1, -(-length // 16) + 2)))
        return blocks[:16], blocks[16:16 + length]

    def _tag(self, mask, aad, ct):
        products, acc = self._products, 0
        pad = lambda data: data + bytes(-len(data) % 16)
        data = pad(aad) + pad(ct) + (len(aad) * 8).to_bytes(8, "big") + (len(ct) * 8).to_bytes(8, "big")
        for i in range(0, len(data), 16):
            acc ^= int.from_bytes(data[i:i + 16], "big")
            block, acc = acc, 0
            for table in products:
                acc ^= table[block >> 120]
                block = block << 8 & 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF
        return (acc ^ int.from_bytes(mask, "big")).to_bytes(16, "big")

    def encrypt(self, nonce, data, aad):
        mask, stream = self._stream(nonce, len(data))
        ct = (int.from_bytes(data, "big") ^ int.from_bytes(stream, "big")).to_bytes(len(data), "big")
        return ct + self._tag(mask, aad, ct)

    def decrypt(self, nonce, data, aad):
        if len(data) < 16:
            raise ValueError("not authentic")
        ct, tag = data[:-16], data[-16:]
        mask, stream = self._stream(nonce, len(ct))
        if not hmac.compare_digest(tag, self._tag(mask, aad, ct)):
            raise ValueError("not authentic")
        return (int.from_bytes(ct, "big") ^ int.from_bytes(stream, "big")).to_bytes(len(ct), "big")


def _gcm(key):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key)
    except Exception:  # not installed, or installed without a working backend
        return _Gcm(key)


class _Session:
    """One encrypted session with the phone: its id, a key for each direction, and the message counter."""

    def __init__(self, sid, keys):
        self.sid, self.n = sid, 0
        self._out, self._in = _gcm(keys[:32]), _gcm(keys[32:])

    def seal(self, body):
        self.n += 1
        ct = self._out.encrypt(self.n.to_bytes(12, "big"), body, b"miu-e2e req " + self.sid.encode())
        return json.dumps({"miu_e2e": 1, "sid": self.sid, "n": self.n, "ct": base64.b64encode(ct).decode()}).encode()

    def open(self, reply):
        """The phone's answer to the message sealed last. Only what opens with the session's key is the phone's."""
        try:
            return json.loads(self._in.decrypt(self.n.to_bytes(12, "big"), base64.b64decode(reply["ct"]), b"miu-e2e res " + self.sid.encode()))
        except Exception:
            raise PhoneError("the answer was not the phone's: it does not open with this session's key", "e2e_rejected") from None


_TOO_OLD = ("the phone's app is from before end-to-end encryption: ask the owner to update it, or pass encrypt=False "
            "(MIU_BRIDGE_PLAINTEXT=1) to send requests and the token in the clear")


class Phone:
    def __init__(self, url=None, token=None, timeout=150, retries=2, encrypt=None):
        """encrypt=False (or MIU_BRIDGE_PLAINTEXT=1) sends requests and the token in the clear, as an app from
        before end-to-end encryption expects. Whoever runs the relay can then read both."""
        self.url = url or os.environ.get("MIU_BRIDGE_URL")
        self.token = token or os.environ.get("MIU_BRIDGE_TOKEN")
        if not self.url or not self.token:
            raise PhoneError("set MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN, or pass url and token")
        self.encrypt = os.environ.get("MIU_BRIDGE_PLAINTEXT") not in ("1", "true") if encrypt is None else encrypt
        self._session = None
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

    def _http(self, payload, sealed=True):
        """One HTTP request to the phone's address; `payload` makes the body, anew for each attempt. Returns the
        answer as JSON (None where there is none). Anything but an answer from the phone raises PhoneError."""
        headers = {
            "Content-Type": "application/json", "Accept": "application/json",
            # Named on purpose: the relay's CDN refuses Python's default "Python-urllib" signature with a 403.
            "User-Agent": USER_AGENT,
        }
        if not sealed:
            headers["Authorization"] = "Bearer " + self.token
        # One connection for the life of this object: opening a new one costs several round trips each call.
        started = time.time()
        for fresh in (False, True):
            reused = self._connection is not None and not fresh
            try:
                if self._connection is None:
                    self._proxy_headers = {}
                    self._connection = self._connect()
                self._connection.request("POST", self._path, body=payload(), headers={**headers, **self._proxy_headers})
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
        if status in (202, 204):
            return None
        try:
            reply = json.loads(raw)
        except ValueError:
            reply = None
        if status != 200:
            detail = raw.decode("utf-8", "replace")[:300]
            named = reply if isinstance(reply, dict) else {}
            hint = {401: "the token was rejected", 503: "the phone is offline", 504: "the phone did not answer in time"}.get(status, "")
            code = {401: "unauthorized", 502: "unreachable", 503: "phone_offline", 504: "relay_timeout"}.get(status)
            if status == 403 and "1010" in detail:
                hint = "the relay's CDN refused this client's signature; send a User-Agent header that names your client"
            error = PhoneError(f"HTTP {status} {hint}: {detail}".strip(), code or (named.get("code") if isinstance(named.get("code"), str) else None) or "http_error")
            error.sealed = named.get("miu_e2e") == 1  # whether the phone itself knows about encryption
            raise error
        if not isinstance(reply, dict):
            raise PhoneError("the answer was not JSON: " + raw.decode("utf-8", "replace")[:200], "unreachable") from None
        return reply

    def _hello(self):
        """Opens an encrypted session: a fresh key from each side, bound to the token."""
        token = self.token.encode()
        private = secrets.randbelow(_ORDER - 1) + 1
        pub = _point_bytes(_point_mul(private, _G))
        mac = hmac.new(token, b"miu-bridge e2e v1 hello" + pub, hashlib.sha256).digest()
        hello = json.dumps({"miu_e2e": 1, "hello": {"pub": base64.b64encode(pub).decode(), "mac": base64.b64encode(mac).decode()}}).encode()
        try:
            reply = self._http(lambda: hello)
        except PhoneError as e:
            if e.code == "unauthorized" and not getattr(e, "sealed", False):
                raise PhoneError(_TOO_OLD, "e2e_unsupported") from None
            raise
        if not reply or reply.get("miu_e2e") != 1 or not isinstance(reply.get("sid"), str) or not isinstance(reply.get("pub"), str):
            raise PhoneError(_TOO_OLD, "e2e_unsupported")
        try:
            theirs = base64.b64decode(reply["pub"])
        except ValueError:
            theirs = b""
        shared = _point_mul(private, _point_from(theirs))[0].to_bytes(32, "big")
        return _Session(reply["sid"], _hkdf(token, shared, b"miu-bridge e2e v1" + pub + theirs + reply["sid"].encode()))

    def rpc(self, body):
        """Sends one JSON-RPC message (bytes) and returns the phone's JSON-RPC answer, or None where there is none
        (a notification). Encrypted unless this Phone was made with encrypt=False."""
        if not self.encrypt:
            return self._http(lambda: body, sealed=False)
        for again in (True, False):
            session = self._session = self._session or self._hello()
            try:
                inner = session.open(self._http(lambda: session.seal(body)))
            except PhoneError as e:
                # The phone restarted or let the session go: it did nothing with this request, so open another and say it again.
                if e.code != "e2e_session" or not again:
                    raise
                self._session = None
                continue
            if inner.get("status") != 200 and not inner.get("body"):
                if inner.get("status") in (202, 204):
                    return None
                raise PhoneError(f"HTTP {inner.get('status')}", "http_error")
            return inner["body"]

    def _send(self, body):
        reply = self.rpc(body)
        if not isinstance(reply, dict):
            raise PhoneError("no answer from the phone", "unreachable")
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
        """The phone's state without touching it: locked, ready, display {id, width, height, dpi}, package, reader, restarted, handed_off (the package the owner holds from handoff, or null), version."""
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

    def takeback(self):
        """Brings the handed-off app back as the owner left it, once they say they are done. Returns its Screen."""
        return self._screen("phone_takeback")

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


def _serve_stdio():
    """`python miu_bridge.py mcp`: an MCP server on stdin and stdout for a local MCP client, with the phone behind
    it. Each message is carried to the phone end-to-end encrypted, so the client needs no URL and no header, only
    MIU_BRIDGE_URL and MIU_BRIDGE_TOKEN in the environment of this process."""
    phone = Phone()
    for line in sys.stdin.buffer:
        if not line.strip():
            continue
        try:
            reply = phone.rpc(line.strip())
        except PhoneError as e:
            try:
                request = json.loads(line)
            except ValueError:
                continue
            if not isinstance(request, dict) or "id" not in request:
                continue
            reply = {"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32000, "message": f"{e} [code: {e.code}]"}}
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()


if __name__ == "__main__" and sys.argv[1:2] == ["mcp"]:
    _serve_stdio()
