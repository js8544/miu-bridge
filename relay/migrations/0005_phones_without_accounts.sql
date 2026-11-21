-- A phone can use the relay without an account: it registers under a hash of an id only this app on
-- that phone knows, and its monthly allowance is counted per phone. Accounts stay for phones that
-- have one (user_id), and for a paid plan later.
CREATE TABLE devices_new (
  id TEXT PRIMARY KEY,           -- random, part of the public MCP URL
  user_id TEXT REFERENCES users(id) ON DELETE CASCADE,  -- null for a phone without an account
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL,        -- sha256 of the key the phone uses to attach
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  hardware_hash TEXT UNIQUE,     -- sha256 of the phone's own id: registering again keeps the row and its count
  origin TEXT                    -- sha256 of the registering address and day, to cap new phones per address
);
INSERT INTO devices_new (id, user_id, name, key_hash, created_at, last_seen_at)
  SELECT id, user_id, name, key_hash, created_at, last_seen_at FROM devices;
DROP TABLE devices;
ALTER TABLE devices_new RENAME TO devices;
CREATE INDEX devices_user ON devices(user_id);
CREATE INDEX devices_origin ON devices(origin);

CREATE TABLE device_usage (
  device_id TEXT NOT NULL,
  month TEXT NOT NULL,            -- 'YYYY-MM', UTC
  calls INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, month)
);
