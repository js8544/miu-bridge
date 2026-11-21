-- Notes agents write when a tool did not behave as described, sent only while the owner is in the
-- experience improvement program and shown to the owner in the app. Keyed by the same random
-- `install_id` as trace_events and deleted with them when the owner leaves.
CREATE TABLE agent_feedback (
  install_id TEXT NOT NULL,
  at INTEGER NOT NULL,            -- minute the note was written (epoch minutes)
  expected TEXT NOT NULL,         -- what the agent expected the tool to do
  happened TEXT NOT NULL,         -- what it did instead
  recent TEXT NOT NULL,           -- JSON array of the last few trace events before the note (metadata only)
  app TEXT NOT NULL,
  sdk INTEGER NOT NULL,
  brand TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
CREATE INDEX feedback_install ON agent_feedback(install_id);
CREATE INDEX feedback_received ON agent_feedback(received_at);
