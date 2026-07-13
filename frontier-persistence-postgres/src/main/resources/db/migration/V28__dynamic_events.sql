ALTER TABLE world_events ADD COLUMN city_id UUID REFERENCES cities(id);
ALTER TABLE world_events ADD COLUMN kingdom_id UUID REFERENCES kingdoms(id);
ALTER TABLE world_events ADD COLUMN shipment_id UUID REFERENCES shipments(id);
ALTER TABLE world_events ADD COLUMN road_edge_id UUID REFERENCES road_edges(id);
ALTER TABLE world_events ADD COLUMN expires_at TIMESTAMPTZ;

CREATE TABLE dynamic_event_participants (
  event_id UUID NOT NULL REFERENCES world_events(id) ON DELETE CASCADE,
  player_id UUID NOT NULL,
  role VARCHAR(32) NOT NULL,
  joined_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(event_id,player_id)
);
CREATE TABLE dynamic_event_responses (
  event_id UUID NOT NULL REFERENCES world_events(id) ON DELETE CASCADE,
  player_id UUID NOT NULL,
  contribution BIGINT NOT NULL CHECK(contribution > 0),
  responded_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(event_id,player_id)
);
CREATE INDEX idx_dynamic_event_available ON world_events(state,expires_at,event_key);
CREATE INDEX idx_dynamic_event_city ON world_events(city_id,state);
CREATE INDEX idx_dynamic_event_shipment ON world_events(shipment_id,state);
