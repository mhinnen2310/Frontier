ALTER TABLE cities ADD COLUMN settlement_kind VARCHAR(24) NOT NULL DEFAULT 'PLAYER';
ALTER TABLE ledger_entries ADD COLUMN counterparty_account_id UUID REFERENCES accounts(id);
ALTER TABLE ledger_entries ADD COLUMN description TEXT;

CREATE TABLE financial_transfers (
  id UUID PRIMARY KEY,
  transfer_type VARCHAR(40) NOT NULL,
  source_account_id UUID NOT NULL REFERENCES accounts(id),
  destination_account_id UUID NOT NULL REFERENCES accounts(id),
  actor_id UUID,
  amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
  idempotency_key UUID NOT NULL UNIQUE,
  description TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  CHECK(source_account_id <> destination_account_id)
);

CREATE TABLE harbor_state (
  singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
  city_id UUID NOT NULL UNIQUE REFERENCES cities(id),
  system_actor_id UUID NOT NULL,
  daily_budget_minor BIGINT NOT NULL CHECK(daily_budget_minor > 0),
  spent_today_minor BIGINT NOT NULL DEFAULT 0 CHECK(spent_today_minor >= 0),
  budget_resets_at TIMESTAMPTZ NOT NULL,
  last_market_refresh_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE player_tutorials (
  player_id UUID PRIMARY KEY,
  stage VARCHAR(32) NOT NULL,
  first_seen_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE harbor_jobs (
  id UUID PRIMARY KEY,
  player_id UUID NOT NULL,
  job_day DATE NOT NULL,
  job_type VARCHAR(32) NOT NULL,
  description TEXT NOT NULL,
  reward_minor BIGINT NOT NULL CHECK(reward_minor > 0),
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(player_id,job_day,job_type)
);

CREATE INDEX idx_harbor_jobs_player ON harbor_jobs(player_id,status,expires_at);
CREATE INDEX idx_financial_transfers_time ON financial_transfers(occurred_at DESC);
