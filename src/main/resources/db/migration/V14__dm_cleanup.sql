-- DM cleanup: every DM the bot will delete later, and when (or on what event).
CREATE TABLE dm_message (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    message_id  BIGINT      NOT NULL,
    kind        TEXT        NOT NULL,
    ref         TEXT,
    delete_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dm_message_due_idx ON dm_message (delete_at);
CREATE INDEX dm_message_kind_idx ON dm_message (kind, ref);
ALTER TABLE contract ADD COLUMN dm_panel_deleted_at TIMESTAMPTZ;
