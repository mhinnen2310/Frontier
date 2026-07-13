ALTER TABLE campaigns ADD COLUMN scheduled_active_at TIMESTAMPTZ;
ALTER TABLE campaigns ADD COLUMN maximum_ends_at TIMESTAMPTZ;
ALTER TABLE campaigns ADD COLUMN declaration_cost_minor BIGINT NOT NULL DEFAULT 0 CHECK(declaration_cost_minor >= 0);
ALTER TABLE campaigns ADD COLUMN attacker_score BIGINT NOT NULL DEFAULT 0 CHECK(attacker_score >= 0);
ALTER TABLE campaigns ADD COLUMN defender_score BIGINT NOT NULL DEFAULT 0 CHECK(defender_score >= 0);
ALTER TABLE campaigns ADD COLUMN baseline_finalized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE campaigns ADD COLUMN resolution_reason TEXT;
ALTER TABLE campaigns ADD COLUMN idempotency_key UUID UNIQUE;
ALTER TABLE campaign_objectives ADD COLUMN minimum_participants INTEGER NOT NULL DEFAULT 1 CHECK(minimum_participants >= 0);
ALTER TABLE campaign_objectives ADD COLUMN expires_at TIMESTAMPTZ;

CREATE TABLE campaign_participants (
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  player_id UUID NOT NULL,
  city_id UUID NOT NULL REFERENCES cities(id),
  role VARCHAR(32) NOT NULL,
  joined_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(campaign_id,player_id)
);
CREATE TABLE combat_activity (
  player_id UUID PRIMARY KEY,
  last_move_at TIMESTAMPTZ,
  last_interaction_at TIMESTAMPTZ,
  last_combat_at TIMESTAMPTZ,
  afk BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE campaign_baselines (
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  buildings JSONB NOT NULL,
  finalized_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(campaign_id,city_id)
);
CREATE UNIQUE INDEX uq_active_campaign_pair ON campaigns(attacker_city_id,defender_city_id)
  WHERE phase IN ('DECLARED','PREPARATION','ACTIVE','CEASEFIRE','RESOLUTION');
CREATE INDEX idx_campaign_advance ON campaigns(phase,scheduled_active_at,maximum_ends_at);
