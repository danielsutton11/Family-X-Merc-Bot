-- Phase 4c: payout strings, credits and settlement with other family factions.

-- A generated payout string: the exact amount per merc at that moment (scope doc 6.6).
-- One open string per faction; credits seen in faction news are matched against its lines.
CREATE TABLE payout (
    id          BIGSERIAL PRIMARY KEY,
    faction_id  BIGINT      NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    total       BIGINT      NOT NULL,
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX payout_one_open_idx ON payout (faction_id) WHERE status = 'OPEN';

CREATE TABLE payout_line (
    payout_id         BIGINT      NOT NULL REFERENCES payout (id) ON DELETE CASCADE,
    merc_id           BIGINT      NOT NULL,
    amount            BIGINT      NOT NULL,
    credited_at       TIMESTAMPTZ,
    credited_news_id  TEXT,
    credited_by       BIGINT,
    PRIMARY KEY (payout_id, merc_id)
);
CREATE UNIQUE INDEX payout_line_news_idx ON payout_line (credited_news_id) WHERE credited_news_id IS NOT NULL;

ALTER TABLE hit_share ADD COLUMN payout_id BIGINT REFERENCES payout (id);
ALTER TABLE hit_share ADD COLUMN owed_at TIMESTAMPTZ;
ALTER TABLE hit_share ADD COLUMN credited_at TIMESTAMPTZ;
UPDATE hit_share SET owed_at = updated_at WHERE state IN ('OWED', 'CREDITED');
CREATE INDEX hit_share_payout_idx ON hit_share (payout_id);
CREATE INDEX hit_share_owed_idx ON hit_share (state, faction_id);

-- Settlement rows: one per faction per period, or per contract (scope doc 6.6).
ALTER TABLE settlement ADD COLUMN contract_id BIGINT REFERENCES contract (id);
ALTER TABLE settlement ADD COLUMN received_by BIGINT;
ALTER TABLE settlement ADD COLUMN ping_message_id BIGINT;
ALTER TABLE settlement ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX settlement_status_idx ON settlement (faction_id, status);
