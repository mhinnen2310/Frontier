ALTER TABLE mega_projects ADD COLUMN commodity_key VARCHAR(96);
ALTER TABLE mega_projects ADD COLUMN started_by UUID;
ALTER TABLE mega_projects ADD COLUMN started_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE mega_projects ADD COLUMN completed_at TIMESTAMPTZ;

CREATE TABLE mega_project_contributions (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES mega_projects(id),
  city_id UUID NOT NULL REFERENCES cities(id),
  actor_id UUID NOT NULL,
  commodity_key VARCHAR(96) NOT NULL,
  units BIGINT NOT NULL CHECK(units > 0),
  idempotency_key UUID NOT NULL UNIQUE,
  contributed_at TIMESTAMPTZ NOT NULL
);

INSERT INTO global_objectives(id,objective_key,status,progress,target,version) VALUES
  (gen_random_uuid(),'CONNECT_CAPITALS','ACTIVE',0,1,0),
  (gen_random_uuid(),'BUILD_WORLD_WONDERS','ACTIVE',0,1,0),
  (gen_random_uuid(),'SURVIVE_WORLD_CRISIS','ACTIVE',0,1,0),
  (gen_random_uuid(),'RESTORE_WAR_RUINS','ACTIVE',0,1,0)
ON CONFLICT(objective_key) DO NOTHING;

CREATE INDEX idx_mega_projects_status ON mega_projects(status,kingdom_id);
