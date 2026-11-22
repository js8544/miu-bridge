-- The relay no longer has accounts: every phone is counted on its own. A month's count that was kept
-- per account moves to that account's most recently seen phone, so nobody's allowance starts over.
INSERT INTO device_usage (device_id, month, calls)
  SELECT (SELECT d.id FROM devices d WHERE d.user_id = g.user_id ORDER BY d.last_seen_at DESC LIMIT 1), g.month, g.calls
  FROM usage g
  WHERE EXISTS (SELECT 1 FROM devices d WHERE d.user_id = g.user_id)
  ON CONFLICT(device_id, month) DO UPDATE SET calls = calls + excluded.calls;

CREATE TABLE devices_new (
  id TEXT PRIMARY KEY,           -- random, part of the public MCP URL
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL,        -- sha256 of the key the phone uses to attach
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  hardware_hash TEXT UNIQUE,     -- sha256 of the phone's own id: registering again keeps the row and its count
  origin TEXT                    -- sha256 of the registering address and day, to cap new phones per address
);
INSERT INTO devices_new (id, name, key_hash, created_at, last_seen_at, hardware_hash, origin)
  SELECT id, name, key_hash, created_at, last_seen_at, hardware_hash, origin FROM devices;
DROP TABLE devices;
ALTER TABLE devices_new RENAME TO devices;
CREATE INDEX devices_origin ON devices(origin);

DROP TABLE sessions;
DROP TABLE email_codes;
DROP TABLE usage;
DROP TABLE users;
