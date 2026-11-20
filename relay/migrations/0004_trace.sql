-- Opt-in experience improvement program. One row per tool call, metadata only: no screen text,
-- screenshots, typed text, URLs, accounts or emails. `install_id` is a random id the app makes when
-- the owner joins and drops when they leave; it is not linked to an account.
CREATE TABLE trace_events (
  install_id TEXT NOT NULL,
  at INTEGER NOT NULL,            -- minute the call ended (epoch minutes)
  tool TEXT NOT NULL,             -- phone_tap, phone_script, ...
  pkg TEXT,                       -- app in front, e.g. com.android.settings
  ok INTEGER NOT NULL,            -- 1 success, 0 failure
  code TEXT,                      -- error code on failure
  ms INTEGER NOT NULL,            -- duration
  sel TEXT,                       -- how the element was named: ref | text | id | role | xy
  lines INTEGER,                  -- element lines in the returned snapshot
  app TEXT NOT NULL,              -- app version
  sdk INTEGER NOT NULL,           -- Android API level
  brand TEXT NOT NULL,            -- phone maker
  received_at INTEGER NOT NULL
);
CREATE INDEX trace_install ON trace_events(install_id);
CREATE INDEX trace_tool ON trace_events(tool, at);
