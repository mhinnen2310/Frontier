CREATE TABLE caravans (
  shipment_id UUID PRIMARY KEY REFERENCES shipments(id),
  state VARCHAR(24) NOT NULL,
  health SMALLINT NOT NULL DEFAULT 100 CHECK(health BETWEEN 0 AND 100),
  progress DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 1),
  route_index INTEGER NOT NULL DEFAULT 0,
  escort_player UUID,
  presentation_entity UUID UNIQUE,
  simulation_mode VARCHAR(16) NOT NULL DEFAULT 'SIMULATED',
  combat_until TIMESTAMPTZ,
  state_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE caravan_history (
  id UUID PRIMARY KEY,
  shipment_id UUID NOT NULL REFERENCES shipments(id),
  event_type VARCHAR(32) NOT NULL,
  actor_id UUID,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_caravan_active ON caravans(state,updated_at) WHERE state<>'DESPAWNED';
CREATE INDEX idx_caravan_history ON caravan_history(shipment_id,occurred_at DESC);
