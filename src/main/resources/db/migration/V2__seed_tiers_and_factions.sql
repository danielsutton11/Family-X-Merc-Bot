-- Default tiers (scope doc 5.2). Prices are starting values; leaders edit them in Tiers & pricing.
INSERT INTO tier (name, rank, colour, requires_merits, requires_perk, requires_stricken)
VALUES ('Bronze', 1, 13467442, FALSE, FALSE, FALSE),   -- #CD7F32
       ('Silver', 2, 12632256, TRUE,  TRUE,  FALSE),   -- #C0C0C0
       ('Gold',   3, 16766720, TRUE,  TRUE,  TRUE);    -- #FFD700

INSERT INTO tier_price_version (tier_id, price_per_hit)
SELECT id, CASE name WHEN 'Bronze' THEN 1000000 WHEN 'Silver' THEN 2400000 ELSE 2900000 END
FROM tier;

-- Capacity X is the home faction. The other family factions are added from Settings > Factions.
INSERT INTO family_faction (torn_faction_id, name) VALUES (9357, 'Capacity X');
