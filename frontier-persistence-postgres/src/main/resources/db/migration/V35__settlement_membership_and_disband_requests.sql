CREATE TABLE settlement_bans (
  city_id UUID NOT NULL REFERENCES cities(id),
  player_id UUID NOT NULL,
  status VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  banned_by UUID NOT NULL,
  banned_at TIMESTAMPTZ NOT NULL,
  revoked_by UUID,
  revoked_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(city_id, player_id)
);

CREATE TABLE settlement_disband_requests (
  id UUID PRIMARY KEY,
  city_id UUID NOT NULL REFERENCES cities(id),
  requested_by UUID NOT NULL,
  status VARCHAR(24) NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL,
  confirms_after TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  confirmed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK (confirms_after >= requested_at),
  CHECK (expires_at > confirms_after)
);

CREATE UNIQUE INDEX uq_pending_disband_request
  ON settlement_disband_requests(city_id)
  WHERE status='REQUESTED';
CREATE INDEX idx_settlement_bans_player
  ON settlement_bans(player_id, status);
CREATE INDEX idx_disband_request_expiry
  ON settlement_disband_requests(status, expires_at);
