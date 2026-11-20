-- Monthly allowance of remote tool calls. A plan names the allowance; usage is counted per account
-- and calendar month (UTC), one row per account and month, over all of the account's phones.
ALTER TABLE users ADD COLUMN plan TEXT NOT NULL DEFAULT 'free';

CREATE TABLE usage (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month TEXT NOT NULL,            -- 'YYYY-MM', UTC
  calls INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, month)
);
