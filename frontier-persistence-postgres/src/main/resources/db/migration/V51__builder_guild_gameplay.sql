ALTER TABLE builder_depots ADD COLUMN foreman_worker_id UUID REFERENCES workers(id);
ALTER TABLE builder_depots ADD COLUMN team_capacity INTEGER NOT NULL DEFAULT 1 CHECK(team_capacity BETWEEN 1 AND 5);
ALTER TABLE builder_depots ADD COLUMN emergency_order_id UUID REFERENCES repair_orders(id);

ALTER TABLE repair_orders ADD COLUMN emergency BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repair_orders ADD COLUMN contribution_boost INTEGER NOT NULL DEFAULT 0 CHECK(contribution_boost BETWEEN 0 AND 10000);

CREATE TABLE builder_teams (
  id UUID PRIMARY KEY,
  depot_id UUID NOT NULL REFERENCES builder_depots(id) ON DELETE CASCADE,
  name VARCHAR(48) NOT NULL,
  foreman_worker_id UUID NOT NULL REFERENCES workers(id),
  priority INTEGER NOT NULL DEFAULT 50 CHECK(priority BETWEEN 0 AND 100),
  worker_capacity INTEGER NOT NULL CHECK(worker_capacity BETWEEN 1 AND 8),
  status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','PAUSED','DISBANDED')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(depot_id,name)
);

CREATE TABLE builder_team_workers (
  team_id UUID NOT NULL REFERENCES builder_teams(id) ON DELETE CASCADE,
  worker_id UUID NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
  assigned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(team_id,worker_id),
  UNIQUE(worker_id)
);

CREATE TABLE builder_guild_contributions (
  id UUID PRIMARY KEY,
  depot_id UUID NOT NULL REFERENCES builder_depots(id),
  repair_order_id UUID NOT NULL REFERENCES repair_orders(id),
  player_id UUID NOT NULL,
  contribution_kind VARCHAR(16) NOT NULL CHECK(contribution_kind IN ('MATERIAL','LABOR','MANUAL_REPAIR','CONFLICT')),
  commodity_key VARCHAR(96),
  units BIGINT NOT NULL CHECK(units > 0),
  status VARCHAR(16) NOT NULL CHECK(status IN ('COMMITTED','CANCELLED')),
  idempotency_key UUID NOT NULL UNIQUE,
  contributed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_builder_contribution_daily ON builder_guild_contributions(player_id,repair_order_id,contributed_at);

CREATE TABLE repair_assist_sessions (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  repair_order_id UUID NOT NULL REFERENCES repair_orders(id),
  player_id UUID NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','COMPLETED','EXPIRED','CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_repair_assist_player ON repair_assist_sessions(player_id) WHERE status='ACTIVE';

CREATE TABLE manual_repair_claims (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES repair_assist_sessions(id),
  repair_task_id UUID NOT NULL REFERENCES repair_tasks(id),
  player_id UUID NOT NULL,
  placed_data TEXT NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('COMMITTED','ROLLED_BACK')),
  idempotency_key UUID NOT NULL UNIQUE,
  completed_at TIMESTAMPTZ NOT NULL,
  UNIQUE(repair_task_id)
);

CREATE TABLE builder_guild_history (
  id UUID PRIMARY KEY,
  depot_id UUID NOT NULL REFERENCES builder_depots(id),
  actor_id UUID NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_builder_team_depot ON builder_teams(depot_id,status);
CREATE INDEX idx_repair_assist_expiry ON repair_assist_sessions(expires_at) WHERE status='ACTIVE';
CREATE INDEX idx_builder_guild_history ON builder_guild_history(depot_id,occurred_at DESC);
