CREATE TABLE city_districts (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  name VARCHAR(32) NOT NULL,
  district_type VARCHAR(24) NOT NULL,
  bounds JSONB NOT NULL,
  manager_id UUID,
  budget_minor BIGINT NOT NULL DEFAULT 0 CHECK(budget_minor >= 0),
  priority SMALLINT NOT NULL DEFAULT 50 CHECK(priority BETWEEN 0 AND 100),
  policies JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(city_id,name)
);

CREATE TABLE district_workers (
  district_id UUID NOT NULL REFERENCES city_districts(id) ON DELETE CASCADE,
  worker_id UUID NOT NULL REFERENCES workers(id),
  priority SMALLINT NOT NULL DEFAULT 50 CHECK(priority BETWEEN 0 AND 100),
  assigned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(district_id,worker_id),
  UNIQUE(worker_id)
);

CREATE TABLE district_storage (
  district_id UUID NOT NULL REFERENCES city_districts(id) ON DELETE CASCADE,
  commodity_key VARCHAR(96) NOT NULL,
  quantity BIGINT NOT NULL DEFAULT 0 CHECK(quantity >= 0),
  capacity BIGINT NOT NULL DEFAULT 0 CHECK(capacity >= 0),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(district_id,commodity_key)
);

CREATE TABLE district_budget (
  id UUID PRIMARY KEY,
  district_id UUID NOT NULL REFERENCES city_districts(id) ON DELETE CASCADE,
  amount_minor BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  reason VARCHAR(160) NOT NULL,
  actor_id UUID,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE district_history (
  id UUID PRIMARY KEY,
  district_id UUID NOT NULL,
  action VARCHAR(32) NOT NULL,
  old_value JSONB,
  new_value JSONB,
  actor_id UUID,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_district_city ON city_districts(city_id,status,priority DESC);
CREATE INDEX idx_district_history ON district_history(district_id,occurred_at DESC);
CREATE INDEX idx_district_budget ON district_budget(district_id,occurred_at DESC);

CREATE VIEW district_effects AS
SELECT id AS district_id,city_id,
  CASE district_type WHEN 'AGRICULTURAL' THEN 20 WHEN 'INDUSTRIAL' THEN 20 WHEN 'MINING' THEN 20 WHEN 'FORESTRY' THEN 15 WHEN 'HARBOR' THEN 5 WHEN 'RESEARCH' THEN 5 ELSE 0 END AS production_bonus,
  CASE district_type WHEN 'RESIDENTIAL' THEN 20 WHEN 'CULTURE' THEN 10 ELSE 0 END AS housing_bonus,
  CASE district_type WHEN 'GOVERNMENT' THEN 10 WHEN 'INDUSTRIAL' THEN 5 WHEN 'FORESTRY' THEN 5 ELSE 0 END AS maintenance_bonus,
  CASE district_type WHEN 'MILITARY' THEN 20 WHEN 'GOVERNMENT' THEN 5 ELSE 0 END AS defense_bonus,
  CASE district_type WHEN 'COMMERCIAL' THEN 20 WHEN 'HARBOR' THEN 20 WHEN 'GOVERNMENT' THEN 5 WHEN 'CULTURE' THEN 5 ELSE 0 END AS trade_bonus,
  CASE WHEN district_type='INDUSTRIAL' THEN 10 WHEN district_type='RESEARCH' THEN 20 WHEN district_type='CULTURE' THEN 10 WHEN district_type IN ('RESIDENTIAL','AGRICULTURAL','COMMERCIAL','HARBOR','MINING','FORESTRY') THEN 5 ELSE 0 END AS worker_efficiency_bonus,
  CASE district_type WHEN 'MILITARY' THEN 10 WHEN 'GOVERNMENT' THEN 10 ELSE 0 END AS repair_priority_bonus
FROM city_districts WHERE status='ACTIVE';

UPDATE city_buildings SET district_key=NULL WHERE district_key IS NOT NULL;
