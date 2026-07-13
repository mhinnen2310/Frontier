ALTER TABLE road_nodes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE road_edges ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shipments ADD COLUMN owner_city_id UUID REFERENCES cities(id);
ALTER TABLE shipments ADD COLUMN origin_node_id UUID REFERENCES road_nodes(id);
ALTER TABLE shipments ADD COLUMN destination_node_id UUID REFERENCES road_nodes(id);
ALTER TABLE shipments ADD COLUMN idempotency_key UUID UNIQUE;
ALTER TABLE shipments ADD COLUMN lease_owner UUID;
ALTER TABLE shipments ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE TABLE shipment_items (
  shipment_id UUID NOT NULL REFERENCES shipments(id),
  commodity_key VARCHAR(96) NOT NULL,
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  reservation_id UUID NOT NULL REFERENCES stock_reservations(id),
  PRIMARY KEY(shipment_id,commodity_key)
);
CREATE TABLE shipment_routes (
  id UUID PRIMARY KEY,
  shipment_id UUID NOT NULL UNIQUE REFERENCES shipments(id),
  nodes JSONB NOT NULL,
  weighted_distance DOUBLE PRECISION NOT NULL CHECK(weighted_distance >= 0),
  calculated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_road_edges_route ON road_edges(from_node,to_node) WHERE integrity >= 10;
CREATE INDEX idx_shipments_due ON shipments(expected_arrival_at,status)
  WHERE status IN ('TRAVELING','WAITING_ROUTE','REROUTING');
