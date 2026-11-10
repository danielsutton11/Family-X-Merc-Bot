-- Phase 5a: requester messaging, claim no-show alerts and suspensions, outside merc services.

-- Messages: who answered, and which inbox card a message sits on.
ALTER TABLE inbox_message ADD COLUMN answered_by BIGINT;
CREATE INDEX inbox_message_contract_idx ON inbox_message (contract_id, created_at);

-- Suspensions set by a leader (scope doc 6.4). Indefinite = "until turned back on".
ALTER TABLE merc ADD COLUMN suspended_by BIGINT;
ALTER TABLE merc ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE merc ADD COLUMN suspension_indefinite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE merc ADD COLUMN suspension_reminded_at TIMESTAMPTZ;
-- The no-show alert card for a merc, and when a leader last pressed Ignore (counting restarts then).
ALTER TABLE merc ADD COLUMN noshow_card_id BIGINT;
ALTER TABLE merc ADD COLUMN noshow_ignored_at TIMESTAMPTZ;
-- "Your availability is off" DM, sent at most every N hours (scope doc 5.1).
ALTER TABLE merc ADD COLUMN avail_reminded_at TIMESTAMPTZ;

-- Outside merc services (scope doc 7.8): one row per contract and kind.
CREATE TABLE outside_alert (
    contract_id      BIGINT      NOT NULL REFERENCES contract (id) ON DELETE CASCADE,
    kind             TEXT        NOT NULL CHECK (kind IN ('OFFENSIVE', 'DEFENSIVE')),
    pings_sent       INT         NOT NULL DEFAULT 0,
    last_ping_at     TIMESTAMPTZ,
    card_message_id  BIGINT,
    muted_until      TIMESTAMPTZ,
    PRIMARY KEY (contract_id, kind)
);
ALTER TABLE outside_attack_event ADD COLUMN target_torn_id BIGINT;
ALTER TABLE outside_attack_event ADD COLUMN attacker_name TEXT;
CREATE INDEX outside_attack_event_at_idx ON outside_attack_event (contract_target_id, at);
