ALTER TABLE market_orders ADD COLUMN IF NOT EXISTS reservation_id UUID REFERENCES stock_reservations(id);
ALTER TABLE market_orders ADD COLUMN IF NOT EXISTS escrow_account_id UUID REFERENCES accounts(id);
ALTER TABLE market_orders ADD COLUMN IF NOT EXISTS idempotency_key UUID UNIQUE;
CREATE UNIQUE INDEX IF NOT EXISTS uq_warehouse_city_active ON warehouses(city_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS economy_cycle_state (
  cycle_key VARCHAR(32) PRIMARY KEY,
  last_run_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_production_queue
  ON production_orders(status, priority DESC, created_at)
  WHERE status IN ('QUEUED', 'ACTIVE', 'PAUSED_NO_INPUT', 'PAUSED_NO_WORKERS');
CREATE INDEX IF NOT EXISTS idx_shipments_active
  ON shipments(status, expected_arrival_at)
  WHERE status IN ('ASSEMBLING', 'DEPARTING', 'TRAVELING', 'WAITING_ROUTE', 'REROUTING');
