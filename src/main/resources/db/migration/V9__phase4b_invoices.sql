-- Phase 4b: invoices and payments.
-- Which invoice a hit was billed on (interim invoices cover the hits not yet invoiced).
ALTER TABLE hit ADD COLUMN invoice_id BIGINT REFERENCES invoice (id);
CREATE INDEX hit_invoice_idx ON hit (invoice_id);
ALTER TABLE invoice ADD COLUMN payee_name TEXT;
ALTER TABLE invoice ADD COLUMN reminded_at TIMESTAMPTZ;
-- The overdue card in the leaders channel, removed once the invoice is paid.
ALTER TABLE invoice ADD COLUMN overdue_card_id BIGINT;
CREATE INDEX invoice_status_idx ON invoice (status);
-- What became of each matching deposit: a card for leaders, then confirmed or set aside.
ALTER TABLE faction_news_seen ADD COLUMN invoice_id BIGINT REFERENCES invoice (id);
ALTER TABLE faction_news_seen ADD COLUMN card_message_id BIGINT;
ALTER TABLE faction_news_seen ADD COLUMN outcome TEXT;
