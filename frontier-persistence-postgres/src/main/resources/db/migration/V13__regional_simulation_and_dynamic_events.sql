ALTER TABLE world_regions ADD COLUMN world_id UUID;
ALTER TABLE world_regions ADD COLUMN population INTEGER NOT NULL DEFAULT 0 CHECK(population >= 0);
ALTER TABLE world_regions ADD COLUMN prosperity DOUBLE PRECISION NOT NULL DEFAULT 50 CHECK(prosperity BETWEEN 0 AND 100);
ALTER TABLE world_regions ADD COLUMN stability DOUBLE PRECISION NOT NULL DEFAULT 50 CHECK(stability BETWEEN 0 AND 100);
ALTER TABLE world_regions ADD COLUMN trade_activity DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK(trade_activity >= 0);
ALTER TABLE world_regions ADD COLUMN road_integrity DOUBLE PRECISION NOT NULL DEFAULT 100 CHECK(road_integrity BETWEEN 0 AND 100);
ALTER TABLE world_regions ADD COLUMN season VARCHAR(16) NOT NULL DEFAULT 'SPRING';
ALTER TABLE world_regions ADD COLUMN simulated_at TIMESTAMPTZ;

CREATE TABLE season_state (
  id SMALLINT PRIMARY KEY DEFAULT 1 CHECK(id=1),
  season VARCHAR(16) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE city_world_simulation_state (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  region_key VARCHAR(96) NOT NULL,
  observed_city_version BIGINT NOT NULL DEFAULT -1,
  next_cycle_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  last_infrastructure_at TIMESTAMPTZ,
  last_nature_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);
INSERT INTO city_world_simulation_state(city_id,region_key)
SELECT c.id,cl.world_id::text || ':' || floor(cl.chunk_x/32.0)::int || ':' || floor(cl.chunk_z/32.0)::int
FROM cities c JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL'
ON CONFLICT DO NOTHING;

CREATE TABLE migration_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  region_id UUID REFERENCES world_regions(id),
  population_before INTEGER NOT NULL,
  population_after INTEGER NOT NULL,
  reason VARCHAR(48) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE nature_state (
  region_id UUID PRIMARY KEY REFERENCES world_regions(id),
  recovery DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK(recovery BETWEEN 0 AND 100),
  ruin_pressure DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK(ruin_pressure BETWEEN 0 AND 100),
  simulated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE event_objectives (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES world_events(id),
  objective_key VARCHAR(64) NOT NULL,
  progress BIGINT NOT NULL DEFAULT 0 CHECK(progress >= 0),
  target BIGINT NOT NULL CHECK(target > 0),
  state VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(event_id,objective_key)
);
CREATE TABLE event_rewards (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES world_events(id),
  reward_key VARCHAR(64) NOT NULL,
  amount BIGINT NOT NULL CHECK(amount > 0),
  status VARCHAR(24) NOT NULL,
  applied_at TIMESTAMPTZ,
  UNIQUE(event_id,reward_key)
);
CREATE TABLE event_history (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES world_events(id),
  previous_state VARCHAR(24),
  new_state VARCHAR(24) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE regional_modifiers (
  id UUID PRIMARY KEY,
  region_id UUID NOT NULL REFERENCES world_regions(id),
  modifier_key VARCHAR(64) NOT NULL,
  modifier_value DOUBLE PRECISION NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  source_event_id UUID REFERENCES world_events(id)
);
CREATE INDEX idx_world_sim_due ON city_world_simulation_state(next_cycle_at,lease_expires_at);
CREATE INDEX idx_event_lifecycle ON world_events(state,state_at);
CREATE INDEX idx_regional_modifiers_active ON regional_modifiers(region_id,expires_at);
