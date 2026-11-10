-- Phase 5b: merc blacklist, reputation events per invoice, the unpaid-after-7-days card.

CREATE TABLE merc_blacklist (
    torn_id     BIGINT PRIMARY KEY,
    discord_id  BIGINT,
    name        TEXT,
    reason      TEXT,
    added_by    BIGINT,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reputation changes tied to an invoice, so each one happens once per invoice (scope doc 6.5).
ALTER TABLE reputation_event ADD COLUMN invoice_id BIGINT REFERENCES invoice (id);
CREATE INDEX reputation_event_requester_idx ON reputation_event (requester_id, at);

ALTER TABLE invoice ADD COLUMN unpaid_week_at TIMESTAMPTZ;

ALTER TABLE requester ADD COLUMN blacklisted_by BIGINT;
ALTER TABLE requester ADD COLUMN blacklisted_at TIMESTAMPTZ;
