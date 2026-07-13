ALTER TABLE political_votes ADD COLUMN created_by UUID;
ALTER TABLE political_votes ADD COLUMN subject JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE political_votes ADD COLUMN required_yes INTEGER NOT NULL DEFAULT 1 CHECK(required_yes > 0);
ALTER TABLE political_votes ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE kingdom_vote_ballots (
  vote_id UUID NOT NULL REFERENCES political_votes(id) ON DELETE CASCADE,
  city_id UUID NOT NULL REFERENCES cities(id),
  actor_id UUID NOT NULL,
  choice BOOLEAN NOT NULL,
  cast_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(vote_id,city_id)
);
CREATE TABLE kingdom_policies (
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  policy_key VARCHAR(48) NOT NULL,
  policy_value VARCHAR(64) NOT NULL,
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(kingdom_id,policy_key)
);
CREATE TABLE kingdom_tax_policy (
  kingdom_id UUID PRIMARY KEY REFERENCES kingdoms(id),
  rate_basis_points INTEGER NOT NULL DEFAULT 0 CHECK(rate_basis_points BETWEEN 0 AND 2500),
  updated_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE kingdom_tax_assessments (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  assessment_date DATE NOT NULL,
  amount_minor BIGINT NOT NULL CHECK(amount_minor >= 0),
  status VARCHAR(24) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  UNIQUE(kingdom_id,city_id,assessment_date)
);
CREATE TABLE kingdom_war_approvals (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  target_city_id UUID NOT NULL REFERENCES cities(id),
  approval_type VARCHAR(32) NOT NULL,
  approved_by UUID NOT NULL,
  approved_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_by UUID REFERENCES campaigns(id),
  UNIQUE(kingdom_id,target_city_id,approval_type,expires_at)
);
CREATE TABLE kingdom_secessions (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  status VARCHAR(24) NOT NULL,
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL,
  campaign_id UUID REFERENCES campaigns(id),
  resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_active_kingdom_secession ON kingdom_secessions(city_id)
  WHERE status IN ('REQUESTED','CONTESTED');
CREATE INDEX idx_kingdom_war_approval ON kingdom_war_approvals(kingdom_id,target_city_id,expires_at)
  WHERE consumed_by IS NULL;

INSERT INTO kingdom_tax_policy(kingdom_id) SELECT id FROM kingdoms ON CONFLICT DO NOTHING;
UPDATE kingdom_roles SET role='KING' WHERE role='SOVEREIGN';
UPDATE kingdom_roles SET role='COUNCIL' WHERE role='CHANCELLOR';
