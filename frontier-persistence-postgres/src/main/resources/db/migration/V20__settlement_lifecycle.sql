ALTER TABLE cities ADD COLUMN lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE cities ADD COLUMN last_active_at TIMESTAMPTZ;
ALTER TABLE cities ADD COLUMN abandoned_at TIMESTAMPTZ;
ALTER TABLE cities ADD COLUMN ruins_until TIMESTAMPTZ;
UPDATE cities SET last_active_at=created_at WHERE last_active_at IS NULL;

CREATE TABLE settlement_cores (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  world_id UUID NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL,
  placed_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(world_id,x,y,z)
);

CREATE TABLE settlement_charters (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  charter_text VARCHAR(512) NOT NULL,
  founding_fee_minor BIGINT NOT NULL CHECK(founding_fee_minor >= 0),
  minimum_founders SMALLINT NOT NULL CHECK(minimum_founders > 0),
  ratified_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE settlement_founders (
  city_id UUID NOT NULL REFERENCES cities(id),
  player_id UUID NOT NULL,
  founder_order SMALLINT NOT NULL,
  accepted_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(city_id,player_id),
  UNIQUE(city_id,founder_order)
);

CREATE TABLE settlement_member_activity (
  city_id UUID NOT NULL REFERENCES cities(id),
  player_id UUID NOT NULL,
  last_active_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(city_id,player_id)
);

CREATE TABLE settlement_founding_reservations (
  id UUID PRIMARY KEY,
  player_id UUID NOT NULL,
  fee_minor BIGINT NOT NULL CHECK(fee_minor >= 0),
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  city_id UUID REFERENCES cities(id),
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE settlement_merge_proposals (
  id UUID PRIMARY KEY,
  source_city UUID NOT NULL REFERENCES cities(id),
  target_city UUID NOT NULL REFERENCES cities(id),
  proposed_by UUID NOT NULL,
  accepted_by UUID,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK(source_city<>target_city)
);

CREATE TABLE settlement_lifecycle_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  actor_id UUID,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE settlement_ruin_claims (
  city_id UUID NOT NULL,
  world_id UUID NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  previous_state VARCHAR(16) NOT NULL,
  abandoned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(city_id,world_id,chunk_x,chunk_z)
);

INSERT INTO settlement_cores(city_id,world_id,x,y,z,status,placed_at)
SELECT c.id,cl.world_id,cl.chunk_x*16+8,64,cl.chunk_z*16+8,'LEGACY',c.created_at
FROM cities c JOIN city_claims cl ON cl.city_id=c.id AND cl.state='CAPITAL'
ON CONFLICT(city_id) DO NOTHING;
INSERT INTO settlement_charters(city_id,charter_text,founding_fee_minor,minimum_founders,ratified_at)
SELECT id,'Legacy settlement charter',0,1,created_at FROM cities ON CONFLICT DO NOTHING;
INSERT INTO settlement_founders(city_id,player_id,founder_order,accepted_at)
SELECT id,owner_id,1,created_at FROM cities ON CONFLICT DO NOTHING;
INSERT INTO settlement_member_activity(city_id,player_id,last_active_at)
SELECT city_id,player_id,joined_at FROM city_members ON CONFLICT DO NOTHING;

CREATE INDEX idx_settlement_inactive ON cities(lifecycle_status,last_active_at);
CREATE INDEX idx_founding_reservation_expiry ON settlement_founding_reservations(status,expires_at);
CREATE INDEX idx_settlement_lifecycle_history ON settlement_lifecycle_history(city_id,occurred_at DESC);
