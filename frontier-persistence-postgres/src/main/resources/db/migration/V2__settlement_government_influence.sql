CREATE TABLE city_policies (
  city_id UUID NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
  policy_key VARCHAR(64) NOT NULL,
  policy_value JSONB NOT NULL,
  changed_by UUID NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL,
  cooldown_until TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(city_id, policy_key)
);

CREATE TABLE city_permission_overrides (
  city_id UUID NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
  player_id UUID NOT NULL,
  permission_key VARCHAR(64) NOT NULL,
  allowed BOOLEAN NOT NULL,
  changed_by UUID NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(city_id, player_id, permission_key)
);

CREATE TABLE influence_scores (
  world_id UUID NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  city_id UUID NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
  score INTEGER NOT NULL CHECK(score >= 0),
  consecutive_lead_cycles INTEGER NOT NULL DEFAULT 0 CHECK(consecutive_lead_cycles >= 0),
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(world_id, chunk_x, chunk_z, city_id)
);
CREATE INDEX idx_influence_scores_city ON influence_scores(city_id, world_id);

CREATE TABLE influence_history (
  id UUID PRIMARY KEY,
  world_id UUID NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  previous_city_id UUID,
  new_city_id UUID,
  previous_state VARCHAR(16) NOT NULL,
  new_state VARCHAR(16) NOT NULL,
  reason VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_influence_history_chunk ON influence_history(world_id, chunk_x, chunk_z, occurred_at DESC);

CREATE TABLE dirty_settlements (
  city_id UUID PRIMARY KEY REFERENCES cities(id) ON DELETE CASCADE,
  reason VARCHAR(64) NOT NULL,
  enqueued_at TIMESTAMPTZ NOT NULL,
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ
);

CREATE TABLE maintenance_invoices (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
  amount_minor BIGINT NOT NULL CHECK(amount_minor >= 0),
  status VARCHAR(24) NOT NULL,
  due_at TIMESTAMPTZ NOT NULL,
  paid_at TIMESTAMPTZ,
  idempotency_key UUID NOT NULL UNIQUE,
  version BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE city_invitations ADD COLUMN created_by UUID;
ALTER TABLE city_invitations ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE UNIQUE INDEX idx_city_invitation_pending
  ON city_invitations(city_id, player_id)
  WHERE status = 'PENDING';
