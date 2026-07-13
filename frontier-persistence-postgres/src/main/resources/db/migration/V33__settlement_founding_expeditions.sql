CREATE TABLE settlement_founding_expeditions (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL UNIQUE,
  leader_id UUID NOT NULL,
  settlement_name VARCHAR(32) NOT NULL,
  charter_text VARCHAR(512) NOT NULL,
  minimum_founders SMALLINT CHECK (minimum_founders > 0),
  minimum_core_distance INTEGER CHECK (minimum_core_distance > 0),
  harbor_exclusion_radius INTEGER CHECK (harbor_exclusion_radius > 0),
  status VARCHAR(32) NOT NULL,
  world_id UUID,
  x INTEGER,
  y INTEGER,
  z INTEGER,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK ((world_id IS NULL AND x IS NULL AND y IS NULL AND z IS NULL)
      OR (world_id IS NOT NULL AND x IS NOT NULL AND y IS NOT NULL AND z IS NOT NULL))
);

CREATE TABLE settlement_founding_expedition_members (
  expedition_id UUID NOT NULL REFERENCES settlement_founding_expeditions(id) ON DELETE CASCADE,
  player_id UUID NOT NULL,
  status VARCHAR(24) NOT NULL,
  invited_by UUID NOT NULL,
  invited_at TIMESTAMPTZ NOT NULL,
  accepted_at TIMESTAMPTZ,
  PRIMARY KEY(expedition_id, player_id)
);

CREATE TABLE settlement_founding_expedition_history (
  id UUID PRIMARY KEY,
  expedition_id UUID NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  actor_id UUID,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE settlement_founding_reservations
  ADD COLUMN expedition_id UUID REFERENCES settlement_founding_expeditions(id);

CREATE INDEX idx_founding_reservation_expedition
  ON settlement_founding_reservations(expedition_id)
  WHERE expedition_id IS NOT NULL;
CREATE UNIQUE INDEX uq_active_founding_core
  ON settlement_founding_expeditions(world_id, x, y, z)
  WHERE world_id IS NOT NULL
    AND status NOT IN ('CANCELLED', 'EXPIRED', 'COMPLETED', 'REVIEW_REQUIRED');
CREATE INDEX idx_founding_expedition_leader
  ON settlement_founding_expeditions(leader_id, status, expires_at);
CREATE INDEX idx_founding_expedition_member
  ON settlement_founding_expedition_members(player_id, status);
CREATE INDEX idx_founding_expedition_recovery
  ON settlement_founding_expeditions(status, updated_at)
  WHERE status IN ('MATERIALS_CLAIMED', 'MATERIALS_RESERVED', 'CORE_PLACED');
CREATE INDEX idx_founding_expedition_history
  ON settlement_founding_expedition_history(expedition_id, occurred_at DESC);
