-- Accounts, login sessions and the phones each account owns. Secrets are stored only as hashes.
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,   -- pbkdf2-sha256 hex
  password_salt TEXT NOT NULL,   -- hex
  created_at INTEGER NOT NULL
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,   -- sha256 of the bearer token the app holds
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,           -- random, part of the public MCP URL
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL,        -- sha256 of the key the phone uses to attach
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER
);
CREATE INDEX devices_user ON devices(user_id);
