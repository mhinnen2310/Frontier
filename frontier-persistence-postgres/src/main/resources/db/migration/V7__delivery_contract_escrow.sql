ALTER TABLE contracts ADD COLUMN issuer_city_id UUID REFERENCES cities(id);
ALTER TABLE contracts ADD COLUMN escrow_account_id UUID REFERENCES accounts(id);
ALTER TABLE contracts ADD COLUMN idempotency_key UUID UNIQUE;
ALTER TABLE contracts ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE delivery_contract_terms (
  contract_id UUID PRIMARY KEY REFERENCES contracts(id),
  destination_warehouse_id UUID NOT NULL REFERENCES warehouses(id),
  commodity_key VARCHAR(96) NOT NULL,
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  reward_minor BIGINT NOT NULL CHECK(reward_minor > 0)
);
CREATE TABLE contract_completions (
  contract_id UUID PRIMARY KEY REFERENCES contracts(id),
  idempotency_key UUID NOT NULL UNIQUE,
  completed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_contract_board ON contracts(status,deadline,created_at)
  WHERE status IN ('POSTED','ACCEPTED','IN_PROGRESS','DELIVERED');
