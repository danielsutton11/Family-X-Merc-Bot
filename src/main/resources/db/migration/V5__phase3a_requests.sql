-- Phase 3a: requesting a contract.
ALTER TABLE requester ADD COLUMN name TEXT;
-- When a test DM last got through (scope doc 4.1); re-tested after a week.
ALTER TABLE requester ADD COLUMN dm_ok_at TIMESTAMPTZ;

ALTER TABLE contract ADD COLUMN target_faction_name TEXT;
-- The #merc-inbox approval card, so it can be removed once handled.
ALTER TABLE contract ADD COLUMN approval_message_id BIGINT;
ALTER TABLE contract ADD COLUMN rejected_reason TEXT;
