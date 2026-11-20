-- Sign-in by emailed one-time code. One pending code per email; only its hash is stored.
CREATE TABLE email_codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,       -- sha256 of email + code
  expires_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  sent_at INTEGER NOT NULL,      -- last send, for the resend cooldown
  window_start INTEGER NOT NULL, -- start of the hourly send window
  window_sends INTEGER NOT NULL  -- sends in that window
);
