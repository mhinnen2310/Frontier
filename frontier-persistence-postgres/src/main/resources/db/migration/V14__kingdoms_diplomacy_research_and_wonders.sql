ALTER TABLE kingdoms ADD COLUMN leader_player_id UUID;
ALTER TABLE kingdoms ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE treaties ADD COLUMN proposed_by UUID;
ALTER TABLE treaties ADD COLUMN accepted_by UUID;
ALTER TABLE treaties ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE treaties ADD COLUMN accepted_at TIMESTAMPTZ;

CREATE TABLE kingdom_roles (
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  player_id UUID NOT NULL,
  role VARCHAR(32) NOT NULL,
  granted_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(kingdom_id,player_id)
);
CREATE TABLE kingdom_invitations (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE diplomatic_relations (
  first_kingdom UUID NOT NULL REFERENCES kingdoms(id),
  second_kingdom UUID NOT NULL REFERENCES kingdoms(id),
  relation VARCHAR(24) NOT NULL,
  reputation INTEGER NOT NULL DEFAULT 0 CHECK(reputation BETWEEN -1000 AND 1000),
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(first_kingdom,second_kingdom),
  CHECK(first_kingdom::text < second_kingdom::text)
);
CREATE TABLE kingdom_history (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  event_type VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE political_votes (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  vote_key VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  closes_at TIMESTAMPTZ NOT NULL,
  result JSONB,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE research_points (
  kingdom_id UUID PRIMARY KEY REFERENCES kingdoms(id),
  available_points BIGINT NOT NULL DEFAULT 0 CHECK(available_points >= 0),
  lifetime_points BIGINT NOT NULL DEFAULT 0 CHECK(lifetime_points >= 0),
  last_generated_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE research_projects (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  branch VARCHAR(32) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  progress_points BIGINT NOT NULL DEFAULT 0 CHECK(progress_points >= 0),
  required_points BIGINT NOT NULL CHECK(required_points > 0),
  status VARCHAR(24) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(kingdom_id,project_key)
);

ALTER TABLE world_wonders ADD COLUMN commodity_key VARCHAR(96);
ALTER TABLE world_wonders ADD COLUMN started_by UUID;
ALTER TABLE world_wonders ADD COLUMN started_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE world_wonders ADD COLUMN completed_at TIMESTAMPTZ;
CREATE TABLE wonder_contributions (
  id UUID PRIMARY KEY,
  wonder_id UUID NOT NULL REFERENCES world_wonders(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  actor_id UUID NOT NULL,
  commodity_key VARCHAR(96) NOT NULL,
  units BIGINT NOT NULL CHECK(units > 0),
  idempotency_key UUID NOT NULL UNIQUE,
  contributed_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE kingdom_prestige (
  id UUID PRIMARY KEY,
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  amount BIGINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE mega_projects (
  id UUID PRIMARY KEY,
  kingdom_id UUID REFERENCES kingdoms(id),
  project_key VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  progress BIGINT NOT NULL DEFAULT 0,
  target BIGINT NOT NULL CHECK(target > 0),
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE global_objectives (
  id UUID PRIMARY KEY,
  objective_key VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(24) NOT NULL,
  progress BIGINT NOT NULL DEFAULT 0,
  target BIGINT NOT NULL CHECK(target > 0),
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE server_history (
  id UUID PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  aggregate_id UUID,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_treaty_active ON treaties(status,expires_at);
CREATE INDEX idx_research_active ON research_projects(status,kingdom_id);
