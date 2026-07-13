CREATE TABLE economic_daily_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  cycle_key UUID NOT NULL UNIQUE,
  tax_income_minor BIGINT NOT NULL,
  maintenance_minor BIGINT NOT NULL,
  wages_minor BIGINT NOT NULL,
  food_required BIGINT NOT NULL,
  food_satisfied BOOLEAN NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE price_history (
  id UUID PRIMARY KEY,
  settlement_id UUID NOT NULL REFERENCES cities(id),
  commodity_key VARCHAR(96) NOT NULL,
  unit_price_minor BIGINT NOT NULL CHECK(unit_price_minor > 0),
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_price_history_lane ON price_history(settlement_id,commodity_key,occurred_at DESC);
CREATE TABLE reference_prices (
  settlement_id UUID NOT NULL REFERENCES cities(id),
  commodity_key VARCHAR(96) NOT NULL,
  unit_price_minor BIGINT NOT NULL CHECK(unit_price_minor > 0),
  sample_volume BIGINT NOT NULL CHECK(sample_volume >= 0),
  calculated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(settlement_id,commodity_key)
);
CREATE TABLE economic_metrics (
  id UUID PRIMARY KEY,
  metric_key VARCHAR(64) NOT NULL,
  settlement_id UUID REFERENCES cities(id),
  metric_value DOUBLE PRECISION NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_economic_metrics_time ON economic_metrics(metric_key,occurred_at DESC);
