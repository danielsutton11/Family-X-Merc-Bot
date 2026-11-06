-- Mercs can turn hit DMs off entirely. Rejected hits, suspensions, lapsed merits and key
-- problems are still always sent (scope doc 5.1).
ALTER TABLE merc DROP CONSTRAINT IF EXISTS merc_dm_pref_check;
ALTER TABLE merc ADD CONSTRAINT merc_dm_pref_check CHECK (dm_pref IN ('EVERY_HIT', 'HOURLY', 'OFF'));
