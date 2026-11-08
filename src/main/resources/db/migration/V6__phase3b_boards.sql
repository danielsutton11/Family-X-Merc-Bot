-- Phase 3b: boards, target watcher, contract clock and the requester's DM control panel.
ALTER TABLE contract ADD COLUMN started_announced BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contract ADD COLUMN ended_at TIMESTAMPTZ;
-- The DM channel the control panel lives in (DM channels aren't always cached by the bot).
ALTER TABLE contract ADD COLUMN dm_channel_id BIGINT;

-- Whether the target was on its board at the last refresh, for "target back" pings.
ALTER TABLE contract_target ADD COLUMN on_board BOOLEAN NOT NULL DEFAULT FALSE;
-- Faction of the target's last roster check, and whether its faction is in a ranked war.
ALTER TABLE contract_target ADD COLUMN in_ranked_war BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX contract_target_contract_idx ON contract_target (contract_id);
