-- Final invoice waits until every attack started before the end is in the attack logs.
ALTER TABLE contract ADD COLUMN final_pending boolean NOT NULL DEFAULT false;

-- "Invoice not delivered" card in the leaders channel, and when it's tidied away.
ALTER TABLE invoice ADD COLUMN undelivered_card_id bigint;
ALTER TABLE invoice ADD COLUMN undelivered_card_delete_at timestamptz;
CREATE INDEX invoice_undelivered_idx ON invoice (undelivered_card_delete_at) WHERE undelivered_card_id IS NOT NULL;
CREATE INDEX contract_final_pending_idx ON contract (id) WHERE final_pending;
