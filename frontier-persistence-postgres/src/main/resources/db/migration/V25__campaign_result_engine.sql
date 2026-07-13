CREATE TABLE campaign_results (
  id UUID PRIMARY KEY,
  campaign_id UUID NOT NULL UNIQUE REFERENCES campaigns(id),
  outcome VARCHAR(32) NOT NULL,
  winner_city UUID NOT NULL REFERENCES cities(id),
  loser_city UUID NOT NULL REFERENCES cities(id),
  amount_minor BIGINT NOT NULL DEFAULT 0 CHECK(amount_minor >= 0),
  claims_transferred INTEGER NOT NULL DEFAULT 0,
  buildings_transferred INTEGER NOT NULL DEFAULT 0,
  roads_transferred INTEGER NOT NULL DEFAULT 0,
  workers_transferred INTEGER NOT NULL DEFAULT 0,
  storage_transferred BIGINT NOT NULL DEFAULT 0,
  terms JSONB NOT NULL,
  applied_by UUID NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE city_occupations (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  occupier_city UUID NOT NULL REFERENCES cities(id),
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  status VARCHAR(24) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK(city_id<>occupier_city)
);
CREATE TABLE campaign_tributes (
  id UUID PRIMARY KEY,
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  payer_city UUID NOT NULL REFERENCES cities(id),
  payee_city UUID NOT NULL REFERENCES cities(id),
  amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
  status VARCHAR(24) NOT NULL,
  next_due_at TIMESTAMPTZ NOT NULL,
  paid_cycles INTEGER NOT NULL DEFAULT 0,
  missed_cycles INTEGER NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE territory_transfer_history (
  id UUID PRIMARY KEY,
  campaign_id UUID NOT NULL REFERENCES campaigns(id),
  from_city UUID NOT NULL REFERENCES cities(id),
  to_city UUID NOT NULL REFERENCES cities(id),
  scope VARCHAR(24) NOT NULL,
  claims INTEGER NOT NULL,
  buildings INTEGER NOT NULL,
  roads INTEGER NOT NULL,
  workers INTEGER NOT NULL,
  storage_units BIGINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tribute_due ON campaign_tributes(status,next_due_at);
CREATE INDEX idx_occupation_occupier ON city_occupations(occupier_city,status);
