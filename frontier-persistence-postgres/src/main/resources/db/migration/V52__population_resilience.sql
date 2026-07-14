ALTER TABLE city_population_state
  ADD COLUMN employment_score SMALLINT NOT NULL DEFAULT 50 CHECK(employment_score BETWEEN 0 AND 100),
  ADD COLUMN population_trend INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN trend_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN food_shortage_since TIMESTAMPTZ,
  ADD COLUMN decline_grace_until TIMESTAMPTZ;

UPDATE city_population_state p
SET decline_grace_until = c.created_at + interval '3 days'
FROM cities c
WHERE c.id=p.city_id;

CREATE TABLE population_cycle_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  population_before INTEGER NOT NULL CHECK(population_before >= 0),
  population_after INTEGER NOT NULL CHECK(population_after >= 0),
  births INTEGER NOT NULL CHECK(births >= 0),
  deaths INTEGER NOT NULL CHECK(deaths >= 0),
  immigration INTEGER NOT NULL CHECK(immigration >= 0),
  emigration INTEGER NOT NULL CHECK(emigration >= 0),
  housing_capacity INTEGER NOT NULL CHECK(housing_capacity >= 0),
  food_security SMALLINT NOT NULL CHECK(food_security BETWEEN 0 AND 100),
  employment_score SMALLINT NOT NULL CHECK(employment_score BETWEEN 0 AND 100),
  safety SMALLINT NOT NULL CHECK(safety BETWEEN 0 AND 100),
  prosperity SMALLINT NOT NULL CHECK(prosperity BETWEEN 0 AND 100),
  settlement_grace_active BOOLEAN NOT NULL,
  food_grace_active BOOLEAN NOT NULL,
  reasons JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_population_cycle_history_city
  ON population_cycle_history(city_id,occurred_at DESC);
