CREATE TABLE worker_activity_tasks (
  id UUID PRIMARY KEY,
  worker_id UUID NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
  city_id UUID NOT NULL REFERENCES cities(id),
  activity_type VARCHAR(32) NOT NULL CHECK(activity_type IN ('WORK_SHIFT','WAREHOUSE_WAIT','GUILD_EXIT','REPAIR','FARM_VISIT','GUARD_POST')),
  priority SMALLINT NOT NULL CHECK(priority BETWEEN 0 AND 100),
  status VARCHAR(20) NOT NULL CHECK(status IN ('QUEUED','TRAVELLING','WORKING','COMPLETED','CANCELLED')),
  simulation_mode VARCHAR(16) NOT NULL CHECK(simulation_mode IN ('PENDING','PHYSICAL','SIMULATED')),
  target_world UUID NOT NULL,
  target_x INTEGER NOT NULL,
  target_y INTEGER NOT NULL,
  target_z INTEGER NOT NULL,
  path_state VARCHAR(20) NOT NULL CHECK(path_state IN ('PENDING','REQUESTED','READY','UNREACHABLE','SIMULATED')),
  source_type VARCHAR(24) NOT NULL,
  source_id UUID,
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
  last_error TEXT,
  available_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_worker_activity_active ON worker_activity_tasks(worker_id)
  WHERE status IN ('QUEUED','TRAVELLING','WORKING');
CREATE INDEX idx_worker_activity_queue ON worker_activity_tasks(status,available_at,priority DESC,created_at);
CREATE INDEX idx_worker_activity_lease ON worker_activity_tasks(lease_expires_at,status);

ALTER TABLE workers ADD COLUMN current_activity_id UUID REFERENCES worker_activity_tasks(id);
ALTER TABLE workers ADD COLUMN next_activity_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_worker_activity_due ON workers(next_activity_at,city_id)
  WHERE current_activity_id IS NULL AND state <> 'UNAVAILABLE';
