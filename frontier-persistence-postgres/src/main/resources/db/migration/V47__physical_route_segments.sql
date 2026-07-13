CREATE TABLE road_edge_segments (
  edge_id UUID NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  world_id UUID NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  PRIMARY KEY(edge_id,sequence),
  UNIQUE(edge_id,world_id,x,y,z)
);

CREATE INDEX idx_road_edge_segments_position
  ON road_edge_segments(world_id,x,z,y,edge_id);
