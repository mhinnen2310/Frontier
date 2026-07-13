ALTER TABLE road_edges ADD COLUMN infrastructure_type VARCHAR(24) NOT NULL DEFAULT 'ROAD';
ALTER TABLE road_edges ADD COLUMN traffic BIGINT NOT NULL DEFAULT 0 CHECK(traffic >= 0);
ALTER TABLE road_edges ADD COLUMN importance SMALLINT NOT NULL DEFAULT 50 CHECK(importance BETWEEN 0 AND 100);
ALTER TABLE road_edges ADD COLUMN owner_city UUID REFERENCES cities(id);
ALTER TABLE road_edges ADD COLUMN minimum_width SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE road_edges ADD COLUMN surface_quality SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN maximum_slope DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN broken_segments INTEGER NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN bridge_segments INTEGER NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN tunnel_segments INTEGER NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN validation_report JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE road_edges ADD COLUMN validated_at TIMESTAMPTZ;

UPDATE road_edges e SET owner_city=n.city_id FROM road_nodes n WHERE n.id=e.from_node;
CREATE INDEX idx_road_edge_operational ON road_edges(owner_city,integrity,importance DESC);
