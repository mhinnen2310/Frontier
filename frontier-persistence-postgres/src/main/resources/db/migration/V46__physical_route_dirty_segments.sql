ALTER TABLE road_edges ADD COLUMN route_world UUID;
ALTER TABLE road_edges ADD COLUMN route_min_x INTEGER;
ALTER TABLE road_edges ADD COLUMN route_min_y INTEGER;
ALTER TABLE road_edges ADD COLUMN route_min_z INTEGER;
ALTER TABLE road_edges ADD COLUMN route_max_x INTEGER;
ALTER TABLE road_edges ADD COLUMN route_max_y INTEGER;
ALTER TABLE road_edges ADD COLUMN route_max_z INTEGER;
ALTER TABLE road_edges ADD COLUMN route_state VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
  CHECK(route_state IN ('LEGACY','VALID','DIRTY','BLOCKED','DESTROYED'));

CREATE TABLE dirty_road_edges (
  edge_id UUID PRIMARY KEY REFERENCES road_edges(id) ON DELETE CASCADE,
  reason VARCHAR(64) NOT NULL,
  first_marked_at TIMESTAMPTZ NOT NULL,
  last_marked_at TIMESTAMPTZ NOT NULL,
  change_count BIGINT NOT NULL DEFAULT 1 CHECK(change_count > 0)
);

CREATE INDEX idx_road_edges_physical_bounds
  ON road_edges(route_world,route_min_x,route_max_x,route_min_z,route_max_z)
  WHERE route_state <> 'LEGACY';
CREATE INDEX idx_dirty_road_edges_marked ON dirty_road_edges(last_marked_at,edge_id);
