-- Family X Merc Bot: initial schema (26 tables, scope doc section 10.6).
-- Money is stored as whole Torn dollars (BIGINT). Times are TIMESTAMPTZ.

-- ---------------------------------------------------------------- factions
CREATE TABLE family_faction (
    torn_faction_id  BIGINT PRIMARY KEY,
    name             TEXT        NOT NULL,
    leader_role_id   BIGINT,
    hosp_perk_ok     BOOLEAN     NOT NULL DEFAULT FALSE,
    perk_checked_at  TIMESTAMPTZ,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE protected_faction (
    torn_faction_id  BIGINT PRIMARY KEY,
    reason           TEXT,
    added_by         BIGINT,
    added_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- keys
CREATE TABLE api_key (
    id                BIGSERIAL PRIMARY KEY,
    owner_torn_id     BIGINT      NOT NULL,
    owner_discord_id  BIGINT      NOT NULL,
    faction_id        BIGINT,
    kind              TEXT        NOT NULL CHECK (kind IN ('LEADER', 'MERC')),
    key_enc           BYTEA       NOT NULL,
    key_hash          TEXT        NOT NULL UNIQUE,
    healthy           BOOLEAN     NOT NULL DEFAULT TRUE,
    unhealthy_reason  TEXT,
    bsp_active        BOOLEAN     NOT NULL DEFAULT FALSE,
    bsp_expires_at    TIMESTAMPTZ,
    bsp_checked_at    TIMESTAMPTZ,
    last_checked_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX api_key_faction_idx ON api_key (faction_id, kind, healthy);
CREATE INDEX api_key_owner_idx ON api_key (owner_discord_id);

-- ---------------------------------------------------------------- settings
CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value       JSONB       NOT NULL,
    version     INT         NOT NULL DEFAULT 1,
    changed_by  BIGINT,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- tiers
CREATE TABLE tier (
    id                 SERIAL PRIMARY KEY,
    name               TEXT    NOT NULL UNIQUE,
    rank               INT     NOT NULL,
    colour             INT     NOT NULL DEFAULT 0,
    requires_merits    BOOLEAN NOT NULL DEFAULT FALSE,
    requires_perk      BOOLEAN NOT NULL DEFAULT FALSE,
    requires_stricken  BOOLEAN NOT NULL DEFAULT FALSE,
    role_id            BIGINT,
    channel_id         BIGINT,
    board_message_id   BIGINT,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    active             BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE tier_price_version (
    id                   SERIAL PRIMARY KEY,
    tier_id              INT         NOT NULL REFERENCES tier (id),
    price_per_hit        BIGINT      NOT NULL,
    expected_hosp_min_s  INT,
    expected_hosp_max_s  INT,
    effective_from       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           BIGINT
);
CREATE INDEX tier_price_version_idx ON tier_price_version (tier_id, effective_from DESC);

-- ---------------------------------------------------------------- mercs
CREATE TABLE merc (
    torn_id                 BIGINT PRIMARY KEY,
    discord_id              BIGINT      NOT NULL UNIQUE,
    faction_id              BIGINT,
    merits_ok               BOOLEAN     NOT NULL DEFAULT FALSE,
    merits_checked_at       TIMESTAMPTZ,
    stricken_opt_in         BOOLEAN     NOT NULL DEFAULT FALSE,
    available               BOOLEAN     NOT NULL DEFAULT TRUE,
    available_until         TIMESTAMPTZ,
    ping_pref               TEXT        NOT NULL DEFAULT 'ALL' CHECK (ping_pref IN ('ALL', 'NEW_ONLY', 'OFF')),
    dm_pref                 TEXT        NOT NULL DEFAULT 'EVERY_HIT' CHECK (dm_pref IN ('EVERY_HIT', 'HOURLY')),
    claims_suspended_until  TIMESTAMPTZ,
    suspension_reason       TEXT,
    claim_cooldown_until    TIMESTAMPTZ,
    consecutive_noshows     INT         NOT NULL DEFAULT 0,
    availability_off_dm_at  TIMESTAMPTZ,
    status                  TEXT        NOT NULL DEFAULT 'ACTIVE',
    joined_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE merc_tier_optin (
    merc_id    BIGINT  NOT NULL REFERENCES merc (torn_id) ON DELETE CASCADE,
    tier_id    INT     NOT NULL REFERENCES tier (id),
    opted_in   BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (merc_id, tier_id)
);

-- ---------------------------------------------------------------- requesters
CREATE TABLE requester (
    torn_id           BIGINT PRIMARY KEY,
    discord_id        BIGINT      NOT NULL,
    faction_id        BIGINT,
    score             INT         NOT NULL DEFAULT 50,
    blacklisted       BOOLEAN     NOT NULL DEFAULT FALSE,
    blacklist_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE discount (
    id          BIGSERIAL PRIMARY KEY,
    scope       TEXT          NOT NULL CHECK (scope IN ('PERSON', 'FACTION')),
    torn_id     BIGINT,
    faction_id  BIGINT,
    pct         NUMERIC(5, 2) NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((scope = 'PERSON' AND torn_id IS NOT NULL) OR (scope = 'FACTION' AND faction_id IS NOT NULL))
);

-- ---------------------------------------------------------------- contracts
CREATE SEQUENCE contract_ref_seq START 1;

CREATE TABLE contract (
    id                     BIGSERIAL PRIMARY KEY,
    ref                    TEXT          NOT NULL UNIQUE,
    requester_id           BIGINT        NOT NULL REFERENCES requester (torn_id),
    requested_for          TEXT          NOT NULL CHECK (requested_for IN ('PERSONAL', 'MY_FACTION')),
    merc_me                BOOLEAN       NOT NULL DEFAULT FALSE,
    scenario               TEXT,
    posture                TEXT CHECK (posture IN ('DEFENSIVE', 'OFFENSIVE')),
    tier_id                INT           NOT NULL REFERENCES tier (id),
    price_version_id       INT           NOT NULL REFERENCES tier_price_version (id),
    unit_price             BIGINT        NOT NULL,
    discount_pct           NUMERIC(5, 2) NOT NULL DEFAULT 0,
    faction_cut_pct        NUMERIC(5, 2) NOT NULL,
    starter_pct            NUMERIC(5, 2) NOT NULL,
    claimer_noshow_pct     NUMERIC(5, 2) NOT NULL,
    attack_type            TEXT          NOT NULL CHECK (attack_type IN ('HOSPITALIZE', 'MUG')),
    mode                   TEXT          NOT NULL CHECK (mode IN ('HITS', 'DURATION')),
    starts_at              TIMESTAMPTZ,
    ends_at                TIMESTAMPTZ,
    hits_per_target        INT,
    target_kind            TEXT          NOT NULL CHECK (target_kind IN ('PLAYERS', 'FACTION')),
    target_faction_id      BIGINT,
    member_selection       TEXT CHECK (member_selection IN ('ALL', 'PICKED')),
    status_condition       TEXT          NOT NULL DEFAULT 'ANY' CHECK (status_condition IN ('ANY', 'ONLINE', 'AWAY')),
    away_minutes           INT,
    allow_revivable        BOOLEAN       NOT NULL DEFAULT TRUE,
    allow_early_discharge  BOOLEAN       NOT NULL DEFAULT TRUE,
    priority               INT           NOT NULL DEFAULT 0,
    status                 TEXT          NOT NULL DEFAULT 'QUOTED'
        CHECK (status IN ('QUOTED', 'EXPIRED', 'PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'PAUSED',
                          'COMPLETED', 'ENDED', 'CANCELLED')),
    notes                  TEXT,
    approved_by            BIGINT,
    quote_expires_at       TIMESTAMPTZ,
    dm_panel_message_id    BIGINT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX contract_status_idx ON contract (status);
CREATE INDEX contract_requester_idx ON contract (requester_id);

CREATE TABLE contract_target (
    id                  BIGSERIAL PRIMARY KEY,
    contract_id         BIGINT      NOT NULL REFERENCES contract (id) ON DELETE CASCADE,
    target_torn_id      BIGINT      NOT NULL,
    target_name         TEXT,
    level               INT,
    faction_id          BIGINT,
    hits_required       INT,
    hits_done           INT         NOT NULL DEFAULT 0,
    roster_status       TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (roster_status IN ('ACTIVE', 'LEFT')),
    last_state          TEXT,
    status_until        TIMESTAMPTZ,
    status_details      TEXT,
    revivable           BOOLEAN,
    early_discharge     BOOLEAN,
    last_action_at      TIMESTAMPTZ,
    last_action_status  TEXT,
    available_at        TIMESTAMPTZ,
    last_checked_at     TIMESTAMPTZ,
    UNIQUE (contract_id, target_torn_id)
);
CREATE INDEX contract_target_torn_idx ON contract_target (target_torn_id);

CREATE TABLE target_override (
    id                  BIGSERIAL PRIMARY KEY,
    contract_target_id  BIGINT      NOT NULL REFERENCES contract_target (id) ON DELETE CASCADE,
    kind                TEXT        NOT NULL CHECK (kind IN ('STATUS', 'REVIVABLE')),
    used_hit_id         BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- claims
CREATE TABLE claim (
    id                  BIGSERIAL PRIMARY KEY,
    contract_target_id  BIGINT      NOT NULL REFERENCES contract_target (id) ON DELETE CASCADE,
    merc_id             BIGINT      NOT NULL REFERENCES merc (torn_id),
    claimed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_starts_at      TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    outcome             TEXT CHECK (outcome IN ('HIT', 'EXPIRED', 'RELEASED', 'NOSHOW')),
    claimer_joined      BOOLEAN
);
CREATE INDEX claim_open_idx ON claim (contract_target_id) WHERE outcome IS NULL;

CREATE TABLE claim_noshow (
    id       BIGSERIAL PRIMARY KEY,
    merc_id  BIGINT      NOT NULL REFERENCES merc (torn_id),
    claim_id BIGINT      NOT NULL REFERENCES claim (id),
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- hits and money
CREATE TABLE hit (
    id                  BIGSERIAL PRIMARY KEY,
    contract_target_id  BIGINT      NOT NULL REFERENCES contract_target (id),
    starter_attack_id   BIGINT      NOT NULL UNIQUE,
    attack_code         TEXT,
    result              TEXT        NOT NULL,
    started             TIMESTAMPTZ NOT NULL,
    ended               TIMESTAMPTZ,
    stricken_value      INT,
    hosp_seconds        INT,
    billed_tier_id      INT REFERENCES tier (id),
    billed_amount       BIGINT,
    claim_id            BIGINT REFERENCES claim (id),
    accepted            BOOLEAN     NOT NULL,
    reject_reason       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlement (
    id            BIGSERIAL PRIMARY KEY,
    faction_id    BIGINT      NOT NULL,
    period_start  TIMESTAMPTZ NOT NULL,
    period_end    TIMESTAMPTZ NOT NULL,
    total         BIGINT      NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'OWED' CHECK (status IN ('OWED', 'SENT', 'RECEIVED')),
    sent_by       BIGINT,
    sent_at       TIMESTAMPTZ,
    received_at   TIMESTAMPTZ
);

CREATE TABLE hit_share (
    id                BIGSERIAL PRIMARY KEY,
    hit_id            BIGINT        NOT NULL REFERENCES hit (id) ON DELETE CASCADE,
    merc_id           BIGINT        NOT NULL REFERENCES merc (torn_id),
    faction_id        BIGINT,
    role              TEXT          NOT NULL CHECK (role IN ('STARTER', 'ASSIST', 'CLAIMER', 'HITTER')),
    attack_id         BIGINT,
    pct               NUMERIC(6, 3) NOT NULL,
    amount            BIGINT        NOT NULL,
    state             TEXT          NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'OWED', 'CREDITED')),
    settlement_id     BIGINT REFERENCES settlement (id),
    credited_news_id  TEXT,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX hit_share_state_idx ON hit_share (faction_id, state);

CREATE TABLE invoice (
    id                BIGSERIAL PRIMARY KEY,
    contract_id       BIGINT      NOT NULL REFERENCES contract (id),
    kind              TEXT        NOT NULL DEFAULT 'FINAL' CHECK (kind IN ('FINAL', 'INTERIM')),
    period_start      TIMESTAMPTZ,
    period_end        TIMESTAMPTZ,
    total             BIGINT      NOT NULL,
    discount_total    BIGINT      NOT NULL DEFAULT 0,
    amount_paid       BIGINT      NOT NULL DEFAULT 0,
    status            TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PARTIAL', 'PAID')),
    payee_torn_id     BIGINT,
    due_at            TIMESTAMPTZ,
    matched_news_ids  TEXT[]      NOT NULL DEFAULT '{}',
    paid_marked_by    BIGINT,
    paid_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE faction_news_seen (
    news_id         TEXT PRIMARY KEY,
    faction_id      BIGINT      NOT NULL,
    at              TIMESTAMPTZ NOT NULL,
    actor_id        BIGINT,
    direction       TEXT,
    account_id      BIGINT,
    amount          BIGINT,
    balance_before  BIGINT,
    balance_after   BIGINT
);
CREATE INDEX faction_news_seen_idx ON faction_news_seen (faction_id, at);

-- ---------------------------------------------------------------- messaging and monitoring
CREATE TABLE inbox_message (
    id                  BIGSERIAL PRIMARY KEY,
    contract_id         BIGINT      NOT NULL REFERENCES contract (id),
    direction           TEXT        NOT NULL CHECK (direction IN ('TO_LEADERS', 'TO_REQUESTER')),
    author_discord_id   BIGINT      NOT NULL,
    author_name         TEXT,
    text                TEXT        NOT NULL,
    discord_message_id  BIGINT,
    answered_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outside_attack_event (
    id                   BIGSERIAL PRIMARY KEY,
    contract_target_id   BIGINT      NOT NULL REFERENCES contract_target (id) ON DELETE CASCADE,
    cause                TEXT        NOT NULL CHECK (cause IN ('HOSPITALIZED', 'MUGGED', 'ATTACKED')),
    attacker_id          BIGINT,
    attacker_faction_id  BIGINT,
    at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    counted              BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE ranked_war (
    id           BIGSERIAL PRIMARY KEY,
    faction_id   BIGINT      NOT NULL,
    opponent_id  BIGINT,
    war_id       BIGINT,
    starts_at    TIMESTAMPTZ,
    ends_at      TIMESTAMPTZ,
    status       TEXT        NOT NULL,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (faction_id, war_id)
);

CREATE TABLE bsp_prediction (
    target_torn_id  BIGINT PRIMARY KEY,
    tbs             BIGINT,
    score           BIGINT,
    result          INT,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reputation_event (
    id             BIGSERIAL PRIMARY KEY,
    requester_id   BIGINT      NOT NULL REFERENCES requester (torn_id),
    change         INT         NOT NULL,
    reason         TEXT        NOT NULL,
    contract_id    BIGINT REFERENCES contract (id),
    by_discord_id  BIGINT,
    at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    id                BIGSERIAL PRIMARY KEY,
    actor_discord_id  BIGINT,
    action            TEXT        NOT NULL,
    entity            TEXT,
    before_json       JSONB,
    after_json        JSONB,
    at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
