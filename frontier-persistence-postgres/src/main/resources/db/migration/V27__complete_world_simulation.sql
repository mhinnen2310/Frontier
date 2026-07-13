CREATE TABLE world_weather (
  region_id UUID PRIMARY KEY REFERENCES world_regions(id),
  weather_key VARCHAR(24) NOT NULL,
  severity SMALLINT NOT NULL CHECK(severity BETWEEN 0 AND 100),
  observed_on DATE NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL,
  next_change_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE infrastructure_decay_history (
  id UUID PRIMARY KEY,
  edge_id UUID NOT NULL REFERENCES road_edges(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  infrastructure_type VARCHAR(24) NOT NULL,
  integrity_before SMALLINT NOT NULL,
  integrity_after SMALLINT NOT NULL,
  cause VARCHAR(48) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE world_event_impacts (
  id UUID PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES world_events(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  impact_key VARCHAR(48) NOT NULL,
  amount BIGINT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL,
  UNIQUE(event_id,city_id,impact_key)
);
ALTER TABLE world_events ADD COLUMN severity SMALLINT NOT NULL DEFAULT 50 CHECK(severity BETWEEN 0 AND 100);
CREATE INDEX idx_world_weather_due ON world_weather(next_change_at);
CREATE INDEX idx_infrastructure_decay_city ON infrastructure_decay_history(city_id,occurred_at DESC);
CREATE INDEX idx_world_event_impacts_event ON world_event_impacts(event_id);
