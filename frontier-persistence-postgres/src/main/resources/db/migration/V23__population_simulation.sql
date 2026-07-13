ALTER TABLE workers ADD COLUMN morale SMALLINT NOT NULL DEFAULT 70 CHECK(morale BETWEEN 0 AND 100);
ALTER TABLE workers ADD COLUMN efficiency SMALLINT NOT NULL DEFAULT 50 CHECK(efficiency BETWEEN 0 AND 150);
ALTER TABLE workers ADD COLUMN employment_status VARCHAR(24) NOT NULL DEFAULT 'EMPLOYED';
ALTER TABLE workers ADD COLUMN housing_building UUID REFERENCES city_buildings(id);
ALTER TABLE workers ADD COLUMN age_days INTEGER NOT NULL DEFAULT 6570 CHECK(age_days >= 0);
ALTER TABLE workers ADD COLUMN retirement_age_days INTEGER NOT NULL DEFAULT 21900 CHECK(retirement_age_days > 0);
ALTER TABLE workers ADD COLUMN retired_at TIMESTAMPTZ;

CREATE TABLE city_population_state (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  housing_capacity INTEGER NOT NULL DEFAULT 0,
  food_security SMALLINT NOT NULL DEFAULT 50 CHECK(food_security BETWEEN 0 AND 100),
  safety SMALLINT NOT NULL DEFAULT 50 CHECK(safety BETWEEN 0 AND 100),
  births INTEGER NOT NULL DEFAULT 0,
  deaths INTEGER NOT NULL DEFAULT 0,
  immigration INTEGER NOT NULL DEFAULT 0,
  emigration INTEGER NOT NULL DEFAULT 0,
  simulated_at TIMESTAMPTZ,
  next_cycle_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE demographic_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  event_type VARCHAR(24) NOT NULL,
  quantity INTEGER NOT NULL,
  population_after INTEGER NOT NULL,
  factors JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE migration_applications (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  direction VARCHAR(16) NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  reason VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO city_population_state(city_id,next_cycle_at)
SELECT id,now() FROM cities ON CONFLICT DO NOTHING;
CREATE INDEX idx_population_due ON city_population_state(next_cycle_at);
CREATE INDEX idx_demographic_history ON demographic_history(city_id,occurred_at DESC);
CREATE INDEX idx_worker_employment ON workers(city_id,employment_status,efficiency DESC);
