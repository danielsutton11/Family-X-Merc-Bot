-- Phase 5c: the audit view shows each entry's one-line summary, as posted in the audit channel.
ALTER TABLE audit_event ADD COLUMN summary TEXT;
CREATE INDEX audit_event_at_idx ON audit_event (at DESC);
CREATE INDEX audit_event_entity_idx ON audit_event (entity);
CREATE INDEX hit_started_idx ON hit (started) WHERE accepted;
