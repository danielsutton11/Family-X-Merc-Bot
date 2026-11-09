-- Phase 3c: claims. One open claim per board row; the database settles two mercs pressing Claim at once.
DROP INDEX IF EXISTS claim_open_idx;
CREATE UNIQUE INDEX claim_open_uq ON claim (contract_target_id) WHERE outcome IS NULL;
CREATE INDEX claim_open_merc_idx ON claim (merc_id) WHERE outcome IS NULL;
