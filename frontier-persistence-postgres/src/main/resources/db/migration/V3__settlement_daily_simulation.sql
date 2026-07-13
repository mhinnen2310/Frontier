CREATE TABLE city_simulation_state (
  city_id UUID PRIMARY KEY REFERENCES cities(id) ON DELETE CASCADE,
  last_cycle_at TIMESTAMPTZ,
  next_cycle_at TIMESTAMPTZ NOT NULL,
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO city_simulation_state(city_id,next_cycle_at)
SELECT id, now() FROM cities
ON CONFLICT(city_id) DO NOTHING;

CREATE TABLE population_history (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
  population_before INTEGER NOT NULL,
  population_after INTEGER NOT NULL,
  prosperity_before SMALLINT NOT NULL,
  prosperity_after SMALLINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_population_history_city ON population_history(city_id, occurred_at DESC);
