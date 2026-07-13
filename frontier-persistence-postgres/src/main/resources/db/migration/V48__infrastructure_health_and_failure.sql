ALTER TABLE road_edges ADD COLUMN physical_health SMALLINT NOT NULL DEFAULT 100 CHECK(physical_health BETWEEN 0 AND 100);
ALTER TABLE road_edges ADD COLUMN bridge_integrity SMALLINT NOT NULL DEFAULT 100 CHECK(bridge_integrity BETWEEN 0 AND 100);
ALTER TABLE road_edges ADD COLUMN designed_capacity BIGINT NOT NULL DEFAULT 0 CHECK(designed_capacity >= 0);
ALTER TABLE road_edges ADD COLUMN criticality SMALLINT NOT NULL DEFAULT 0 CHECK(criticality BETWEEN 0 AND 100);
ALTER TABLE road_edges ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE road_edges ADD COLUMN last_health_check_at TIMESTAMPTZ;
UPDATE road_edges SET physical_health=integrity,bridge_integrity=CASE WHEN infrastructure_type='BRIDGE' THEN integrity ELSE 100 END,designed_capacity=capacity;

ALTER TABLE dirty_road_edges ADD COLUMN lease_owner UUID;
ALTER TABLE dirty_road_edges ADD COLUMN lease_expires_at TIMESTAMPTZ;
ALTER TABLE dirty_road_edges ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0);
ALTER TABLE road_edge_segments ADD COLUMN target_data TEXT NOT NULL DEFAULT 'minecraft:stone_bricks';

CREATE TABLE infrastructure_dirty_changes (
  edge_id UUID NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
  world_id UUID NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  reason VARCHAR(64) NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(edge_id,world_id,x,y,z)
);

CREATE TABLE infrastructure_maintenance_orders (
  id UUID PRIMARY KEY,
  edge_id UUID NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
  city_id UUID NOT NULL REFERENCES cities(id),
  status VARCHAR(24) NOT NULL CHECK(status IN ('OPEN','FUNDED','REPAIRING','COMPLETED','CANCELLED')),
  priority VARCHAR(16) NOT NULL CHECK(priority IN ('CRITICAL','HIGH','NORMAL','LOW')),
  reason VARCHAR(64) NOT NULL,
  estimate_minor BIGINT NOT NULL CHECK(estimate_minor >= 0),
  repair_order_id UUID REFERENCES repair_orders(id),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_infrastructure_maintenance_active ON infrastructure_maintenance_orders(edge_id) WHERE status IN ('OPEN','FUNDED','REPAIRING');

CREATE TABLE infrastructure_maintenance_targets (
  maintenance_order_id UUID NOT NULL REFERENCES infrastructure_maintenance_orders(id) ON DELETE CASCADE,
  world_id UUID NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  expected_data TEXT NOT NULL DEFAULT 'minecraft:air',
  target_data TEXT NOT NULL,
  PRIMARY KEY(maintenance_order_id,world_id,x,y,z)
);

CREATE TABLE infrastructure_warnings (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  edge_id UUID NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
  warning_key VARCHAR(48) NOT NULL,
  severity VARCHAR(16) NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  message TEXT NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','RESOLVED')),
  created_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_infrastructure_warning_active ON infrastructure_warnings(edge_id,warning_key) WHERE status='ACTIVE';

CREATE TABLE infrastructure_health_history (
  id UUID PRIMARY KEY,
  edge_id UUID NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
  route_state VARCHAR(16) NOT NULL,
  health_before SMALLINT NOT NULL,
  health_after SMALLINT NOT NULL,
  bridge_integrity SMALLINT NOT NULL,
  violations JSONB NOT NULL,
  checked_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE shipment_route_edges (
  shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  route_id UUID NOT NULL REFERENCES shipment_routes(id) ON DELETE CASCADE,
  edge_id UUID NOT NULL REFERENCES road_edges(id),
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  PRIMARY KEY(shipment_id,sequence),
  UNIQUE(route_id,edge_id,sequence)
);

INSERT INTO shipment_route_edges(shipment_id,route_id,edge_id,sequence)
SELECT r.shipment_id,r.id,e.id,(p.ord-1)::int
FROM shipment_routes r
CROSS JOIN LATERAL jsonb_array_elements_text(r.nodes) WITH ORDINALITY p(node,ord)
JOIN road_edges e ON ((e.from_node=p.node::uuid AND e.to_node=(r.nodes->>(p.ord::int))::uuid)
                   OR (e.to_node=p.node::uuid AND e.from_node=(r.nodes->>(p.ord::int))::uuid))
WHERE p.ord < jsonb_array_length(r.nodes)
ON CONFLICT DO NOTHING;

ALTER TABLE repair_orders ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE repair_orders ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'CAMPAIGN' CHECK(source_type IN ('CAMPAIGN','INFRASTRUCTURE'));
ALTER TABLE repair_orders ADD COLUMN source_id UUID;
ALTER TABLE repair_tasks ADD COLUMN infrastructure_edge_id UUID REFERENCES road_edges(id);
ALTER TABLE repair_tasks ADD COLUMN maintenance_order_id UUID REFERENCES infrastructure_maintenance_orders(id);

CREATE INDEX idx_dirty_road_edges_lease ON dirty_road_edges(lease_expires_at,last_marked_at);
CREATE INDEX idx_infrastructure_dirty_changes_edge ON infrastructure_dirty_changes(edge_id,observed_at);
CREATE INDEX idx_infrastructure_maintenance_city ON infrastructure_maintenance_orders(city_id,status,priority,updated_at DESC);
CREATE INDEX idx_infrastructure_maintenance_targets_order ON infrastructure_maintenance_targets(maintenance_order_id);
CREATE INDEX idx_infrastructure_warnings_city ON infrastructure_warnings(city_id,status,created_at DESC);
CREATE INDEX idx_infrastructure_health_history_edge ON infrastructure_health_history(edge_id,checked_at DESC);
CREATE INDEX idx_shipment_route_edges_edge ON shipment_route_edges(edge_id,shipment_id);
