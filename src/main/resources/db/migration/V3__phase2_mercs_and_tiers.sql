-- Phase 2: tiers hidden automatically when nobody holds them (scope doc 5.2, "Auto-disable empty tiers").
-- Kept separate from "enabled", which is the Merc Managers' own on/off switch.
ALTER TABLE tier ADD COLUMN auto_hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- When the faction perk was last confirmed for a merc's faction is on family_faction already;
-- mercs who leave keep their history rows, marked LEFT.
CREATE INDEX merc_status_idx ON merc (status);

-- The merc's Torn name, refreshed by the daily check, for profiles and leader views.
ALTER TABLE merc ADD COLUMN name TEXT;
