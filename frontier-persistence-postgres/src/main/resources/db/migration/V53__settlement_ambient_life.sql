CREATE TABLE ambient_scenes (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  scene_type VARCHAR(32) NOT NULL CHECK(scene_type IN ('CITIZEN','GUARD','MARKET','REPAIR','TOWN_HALL_EVENT','SHORTAGE')),
  scene_slot SMALLINT NOT NULL CHECK(scene_slot BETWEEN 0 AND 99),
  label VARCHAR(96) NOT NULL,
  world_id UUID NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')),
  presentation_entity_id UUID,
  presentation_bound_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(city_id,scene_type,scene_slot)
);

CREATE TABLE settlement_ambient_state (
  city_id UUID PRIMARY KEY REFERENCES cities(id),
  last_announcement_signature VARCHAR(512),
  last_announced_at TIMESTAMPTZ,
  current_scene_count INTEGER NOT NULL DEFAULT 0 CHECK(current_scene_count >= 0),
  peak_scene_count INTEGER NOT NULL DEFAULT 0 CHECK(peak_scene_count >= 0),
  cycles BIGINT NOT NULL DEFAULT 0 CHECK(cycles >= 0),
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_ambient_scene_active ON ambient_scenes(status,city_id);
CREATE INDEX idx_ambient_scene_binding ON ambient_scenes(presentation_entity_id)
  WHERE presentation_entity_id IS NOT NULL;
