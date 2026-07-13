CREATE TABLE companies (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  name VARCHAR(48) NOT NULL,
  founder_id UUID NOT NULL,
  account_id UUID NOT NULL REFERENCES accounts(id),
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  idempotency_key UUID NOT NULL UNIQUE,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(city_id,name)
);
CREATE TABLE company_members (
  company_id UUID NOT NULL REFERENCES companies(id),
  player_id UUID NOT NULL,
  role VARCHAR(24) NOT NULL,
  shares INTEGER NOT NULL DEFAULT 0 CHECK(shares BETWEEN 0 AND 10000),
  joined_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(company_id,player_id)
);
CREATE TABLE commercial_invoices (
  id UUID PRIMARY KEY,
  company_id UUID NOT NULL REFERENCES companies(id),
  target_player UUID NOT NULL,
  amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
  status VARCHAR(24) NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL,
  due_at TIMESTAMPTZ NOT NULL,
  paid_at TIMESTAMPTZ,
  payment_key UUID UNIQUE,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE company_loans (
  id UUID PRIMARY KEY,
  company_id UUID NOT NULL REFERENCES companies(id),
  lender_city UUID NOT NULL REFERENCES cities(id),
  principal_minor BIGINT NOT NULL CHECK(principal_minor > 0),
  outstanding_minor BIGINT NOT NULL CHECK(outstanding_minor >= 0),
  annual_interest_bps INTEGER NOT NULL CHECK(annual_interest_bps BETWEEN 0 AND 5000),
  accrued_interest_minor BIGINT NOT NULL DEFAULT 0 CHECK(accrued_interest_minor >= 0),
  status VARCHAR(24) NOT NULL,
  next_interest_at TIMESTAMPTZ NOT NULL,
  idempotency_key UUID NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE business_tax_rules (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  basis_points INTEGER NOT NULL CHECK(basis_points BETWEEN 0 AND 2500),
  changed_by UUID NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE business_tax_assessments (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  company_id UUID NOT NULL REFERENCES companies(id),
  amount_minor BIGINT NOT NULL CHECK(amount_minor >= 0),
  status VARCHAR(24) NOT NULL,
  assessed_at TIMESTAMPTZ NOT NULL,
  paid_at TIMESTAMPTZ,
  cycle_date DATE NOT NULL,
  UNIQUE(company_id,cycle_date)
);
CREATE TABLE government_procurements (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  commodity_key VARCHAR(96) NOT NULL,
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  fulfilled_quantity BIGINT NOT NULL DEFAULT 0 CHECK(fulfilled_quantity >= 0),
  maximum_unit_price_minor BIGINT NOT NULL CHECK(maximum_unit_price_minor > 0),
  status VARCHAR(24) NOT NULL,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE emergency_purchases (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  commodity_key VARCHAR(96) NOT NULL,
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  unit_price_minor BIGINT NOT NULL CHECK(unit_price_minor > 0),
  total_minor BIGINT NOT NULL CHECK(total_minor > 0),
  idempotency_key UUID NOT NULL UNIQUE,
  purchased_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE commercial_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  event_type VARCHAR(32) NOT NULL,
  aggregate_id UUID NOT NULL,
  details JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE production_chains (
  chain_key VARCHAR(96) PRIMARY KEY,
  name VARCHAR(96) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE production_chain_steps (
  chain_key VARCHAR(96) NOT NULL REFERENCES production_chains(chain_key),
  step_order SMALLINT NOT NULL CHECK(step_order > 0),
  recipe_key VARCHAR(96) NOT NULL REFERENCES recipes(recipe_key),
  PRIMARY KEY(chain_key,step_order),
  UNIQUE(chain_key,recipe_key)
);
INSERT INTO recipes(recipe_key,building_category,work_units_per_unit,worker_profession)
VALUES('frontier:forge_tool_kit','INDUSTRY',260,'BLACKSMITH') ON CONFLICT DO NOTHING;
INSERT INTO recipe_inputs(recipe_key,commodity_key,quantity) VALUES
('frontier:forge_tool_kit','minecraft:iron_ingot',2),
('frontier:forge_tool_kit','minecraft:oak_planks',2),
('frontier:forge_tool_kit','minecraft:coal',1) ON CONFLICT DO NOTHING;
INSERT INTO recipe_outputs(recipe_key,commodity_key,quantity)
VALUES('frontier:forge_tool_kit','frontier:tool_kit',1) ON CONFLICT DO NOTHING;
INSERT INTO production_chains(chain_key,name) VALUES
('frontier:tool_chain','Raw materials to tool kits'),
('frontier:food_chain','Wheat to bread') ON CONFLICT DO NOTHING;
INSERT INTO production_chain_steps(chain_key,step_order,recipe_key) VALUES
('frontier:tool_chain',1,'frontier:saw_planks'),
('frontier:tool_chain',2,'frontier:smelt_iron'),
('frontier:tool_chain',3,'frontier:forge_tool_kit'),
('frontier:food_chain',1,'frontier:bake_bread') ON CONFLICT DO NOTHING;
CREATE VIEW trade_history AS
SELECT t.id,t.occurred_at,b.settlement_id AS buyer_city,s.settlement_id AS seller_city,
       b.commodity_key,t.quantity,t.unit_price_minor
FROM trades t JOIN market_orders b ON b.id=t.buy_order_id JOIN market_orders s ON s.id=t.sell_order_id;
CREATE INDEX idx_company_city ON companies(city_id,status);
CREATE INDEX idx_invoice_target ON commercial_invoices(target_player,status,due_at);
CREATE INDEX idx_loan_interest ON company_loans(status,next_interest_at);
CREATE INDEX idx_procurement_open ON government_procurements(city_id,status,commodity_key);
