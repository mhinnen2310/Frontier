ALTER TABLE city_buildings ADD COLUMN building_type VARCHAR(32);
ALTER TABLE city_buildings ADD COLUMN validation_report JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE city_buildings ADD COLUMN last_validated_at TIMESTAMPTZ;
ALTER TABLE city_buildings ADD COLUMN validation_version INTEGER NOT NULL DEFAULT 0;

UPDATE city_buildings SET status=CASE
  WHEN integrity=0 THEN 'DESTROYED'
  WHEN integrity<40 THEN 'DISABLED'
  WHEN integrity<90 THEN 'DAMAGED'
  ELSE 'ACTIVE'
END;

CREATE TABLE building_validation_history (
  id UUID PRIMARY KEY,
  building_id UUID NOT NULL REFERENCES city_buildings(id),
  from_state VARCHAR(32),
  to_state VARCHAR(32) NOT NULL,
  report JSONB NOT NULL,
  actor_id UUID,
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_building_validation_state ON city_buildings(city_id,status,building_type);
