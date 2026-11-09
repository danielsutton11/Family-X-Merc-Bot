-- Phase 4a: hits. When a merc was told about their share (straight away, or in the hourly summary).
ALTER TABLE hit_share ADD COLUMN notified_at TIMESTAMPTZ;
-- The fight's other attackers, kept for the record.
ALTER TABLE hit ADD COLUMN attacker_id BIGINT;
ALTER TABLE hit ADD COLUMN joiners INT NOT NULL DEFAULT 0;
-- Why an accepted hit was billed below the contract's tier.
ALTER TABLE hit ADD COLUMN downgrade_reason TEXT;
CREATE INDEX hit_share_notify_idx ON hit_share (merc_id) WHERE notified_at IS NULL;
CREATE INDEX hit_target_idx ON hit (contract_target_id);
-- Claims judged after the attack log has been read past the end of their lock.
ALTER TABLE claim ADD COLUMN judged BOOLEAN NOT NULL DEFAULT FALSE;
