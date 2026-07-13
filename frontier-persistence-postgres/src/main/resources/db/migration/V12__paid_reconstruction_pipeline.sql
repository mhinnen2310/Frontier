ALTER TABLE repair_orders ADD COLUMN created_by UUID;
ALTER TABLE repair_orders ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE repair_orders ADD COLUMN paid_minor BIGINT NOT NULL DEFAULT 0 CHECK(paid_minor >= 0);
ALTER TABLE repair_orders ADD COLUMN idempotency_key UUID UNIQUE;
ALTER TABLE repair_tasks ADD COLUMN journal_id UUID REFERENCES damage_journal(id);
ALTER TABLE repair_tasks ADD COLUMN priority_score INTEGER NOT NULL DEFAULT 0;
ALTER TABLE repair_tasks ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0);
ALTER TABLE repair_tasks ADD COLUMN prepared_consumption_id UUID;
ALTER TABLE repair_tasks ADD COLUMN last_error TEXT;
ALTER TABLE repair_tasks ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE work_packages ADD COLUMN repair_task_id UUID REFERENCES repair_tasks(id);
CREATE UNIQUE INDEX uq_work_package_task ON work_packages(repair_task_id) WHERE status IN ('ISSUED','ACTIVE');

CREATE TABLE builder_depot_stock (
  depot_id UUID NOT NULL REFERENCES builder_depots(id),
  commodity_key VARCHAR(96) NOT NULL,
  available_quantity BIGINT NOT NULL DEFAULT 0 CHECK(available_quantity >= 0),
  reserved_quantity BIGINT NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(depot_id,commodity_key)
);
CREATE TABLE repair_conflicts (
  id UUID PRIMARY KEY,
  repair_task_id UUID NOT NULL REFERENCES repair_tasks(id),
  expected_data TEXT NOT NULL,
  actual_data TEXT NOT NULL,
  target_data TEXT NOT NULL,
  detected_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ
);
CREATE TABLE repair_history (
  id UUID PRIMARY KEY,
  repair_order_id UUID NOT NULL REFERENCES repair_orders(id),
  repair_task_id UUID REFERENCES repair_tasks(id),
  event_type VARCHAR(48) NOT NULL,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_repair_task_claim ON repair_tasks(status,layer,priority_score,world_id,x,z)
  WHERE status IN ('READY','PREPARED','LEASED');
CREATE INDEX idx_repair_reservation_active ON material_reservations(repair_order_id,status);
