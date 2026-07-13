CREATE TABLE building_transfer_proposals (
  id UUID PRIMARY KEY,
  building_id UUID NOT NULL REFERENCES city_buildings(id),
  source_city_id UUID NOT NULL REFERENCES cities(id),
  target_city_id UUID NOT NULL REFERENCES cities(id),
  requested_by UUID NOT NULL,
  status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED','EXPIRED')),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  accepted_by UUID,
  resolved_at TIMESTAMPTZ,
  CHECK(source_city_id<>target_city_id),
  CHECK(expires_at>created_at),
  CHECK((status='PENDING' AND accepted_by IS NULL AND resolved_at IS NULL)
    OR (status<>'PENDING' AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_building_transfer_pending
  ON building_transfer_proposals(building_id)
  WHERE status='PENDING';

CREATE INDEX idx_building_transfer_target
  ON building_transfer_proposals(target_city_id,status,expires_at);
