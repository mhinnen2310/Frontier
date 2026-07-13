ALTER TABLE city_districts
  ADD COLUMN tier SMALLINT NOT NULL DEFAULT 1 CHECK(tier BETWEEN 1 AND 5),
  ADD COLUMN maintenance_minor BIGINT NOT NULL DEFAULT 0 CHECK(maintenance_minor >= 0);

ALTER TABLE city_districts
  ADD CONSTRAINT chk_district_status CHECK(status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  ADD CONSTRAINT chk_district_type CHECK(district_type IN (
    'RESIDENTIAL','AGRICULTURAL','INDUSTRIAL','COMMERCIAL','MILITARY','GOVERNMENT',
    'LOGISTICS','MINING','FORESTRY','CULTURE','RESEARCH','HARBOR'));

CREATE TABLE district_regions (
  district_id UUID PRIMARY KEY REFERENCES city_districts(id) ON DELETE CASCADE,
  world_id UUID NOT NULL,
  min_x INTEGER NOT NULL,
  min_y INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_y INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  center_x INTEGER NOT NULL,
  center_y INTEGER NOT NULL,
  center_z INTEGER NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CHECK(min_x <= max_x AND min_y <= max_y AND min_z <= max_z),
  CHECK(center_x BETWEEN min_x AND max_x),
  CHECK(center_y BETWEEN min_y AND max_y),
  CHECK(center_z BETWEEN min_z AND max_z)
);

INSERT INTO district_regions(
  district_id,world_id,min_x,min_y,min_z,max_x,max_y,max_z,center_x,center_y,center_z)
SELECT id,
  (bounds->>'world')::uuid,
  (bounds->>'minX')::int,(bounds->>'minY')::int,(bounds->>'minZ')::int,
  (bounds->>'maxX')::int,(bounds->>'maxY')::int,(bounds->>'maxZ')::int,
  ((bounds->>'minX')::int+(bounds->>'maxX')::int)/2,
  ((bounds->>'minY')::int+(bounds->>'maxY')::int)/2,
  ((bounds->>'minZ')::int+(bounds->>'maxZ')::int)/2
FROM city_districts;

CREATE TABLE district_roles (
  district_id UUID NOT NULL REFERENCES city_districts(id) ON DELETE CASCADE,
  role_key VARCHAR(24) NOT NULL,
  permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(district_id,role_key),
  CHECK(role_key IN ('MANAGER','OFFICER','RESIDENT','WORKER')),
  CHECK(jsonb_typeof(permissions)='array')
);

INSERT INTO district_roles(district_id,role_key,permissions,created_at)
SELECT d.id,v.role_key,v.permissions,d.created_at
FROM city_districts d
CROSS JOIN (VALUES
  ('MANAGER','["MANAGE","BUDGET","POLICY","ASSIGN"]'::jsonb),
  ('OFFICER','["ASSIGN","REPORT"]'::jsonb),
  ('RESIDENT','["REPORT"]'::jsonb),
  ('WORKER','["WORK","REPORT"]'::jsonb)
) AS v(role_key,permissions);

CREATE TABLE district_memberships (
  district_id UUID NOT NULL,
  player_id UUID NOT NULL,
  role_key VARCHAR(24) NOT NULL,
  assigned_by UUID,
  joined_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(district_id,player_id),
  FOREIGN KEY(district_id,role_key) REFERENCES district_roles(district_id,role_key)
);

INSERT INTO district_memberships(district_id,player_id,role_key,assigned_by,joined_at)
SELECT id,manager_id,'MANAGER',manager_id,updated_at
FROM city_districts
WHERE manager_id IS NOT NULL;

CREATE TABLE district_policies (
  district_id UUID NOT NULL REFERENCES city_districts(id) ON DELETE CASCADE,
  policy_key VARCHAR(24) NOT NULL,
  policy_value VARCHAR(64) NOT NULL,
  updated_by UUID,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(district_id,policy_key),
  CHECK(policy_key IN ('ACCESS','AUTOMATION','TAX','REPAIR','WORK'))
);

INSERT INTO district_policies(district_id,policy_key,policy_value,updated_at)
SELECT d.id,p.key,p.value,d.updated_at
FROM city_districts d
CROSS JOIN LATERAL jsonb_each_text(d.policies) p;

ALTER TABLE city_buildings
  ADD COLUMN district_id UUID REFERENCES city_districts(id) ON DELETE SET NULL;

UPDATE city_buildings b
SET district_id=d.id
FROM city_districts d
WHERE b.district_key=d.id::text;

UPDATE city_buildings SET district_key=NULL;

CREATE INDEX idx_district_region_world_bounds
  ON district_regions(world_id,min_x,max_x,min_z,max_z);
CREATE INDEX idx_district_membership_player
  ON district_memberships(player_id,district_id);
CREATE INDEX idx_building_district_id
  ON city_buildings(district_id) WHERE district_id IS NOT NULL;

DROP VIEW district_effects;
CREATE VIEW district_effects AS
SELECT id AS district_id,city_id,
  CASE district_type WHEN 'AGRICULTURAL' THEN 20 WHEN 'INDUSTRIAL' THEN 20 WHEN 'MINING' THEN 20 WHEN 'FORESTRY' THEN 15 WHEN 'LOGISTICS' THEN 5 WHEN 'HARBOR' THEN 5 WHEN 'RESEARCH' THEN 5 ELSE 0 END AS production_bonus,
  CASE district_type WHEN 'RESIDENTIAL' THEN 20 WHEN 'CULTURE' THEN 10 ELSE 0 END AS housing_bonus,
  CASE district_type WHEN 'GOVERNMENT' THEN 10 WHEN 'INDUSTRIAL' THEN 5 WHEN 'FORESTRY' THEN 5 WHEN 'LOGISTICS' THEN 5 ELSE 0 END AS maintenance_bonus,
  CASE district_type WHEN 'MILITARY' THEN 20 WHEN 'GOVERNMENT' THEN 5 ELSE 0 END AS defense_bonus,
  CASE district_type WHEN 'COMMERCIAL' THEN 20 WHEN 'HARBOR' THEN 20 WHEN 'LOGISTICS' THEN 10 WHEN 'GOVERNMENT' THEN 5 WHEN 'CULTURE' THEN 5 ELSE 0 END AS trade_bonus,
  CASE WHEN district_type='INDUSTRIAL' THEN 10 WHEN district_type='RESEARCH' THEN 20 WHEN district_type='CULTURE' THEN 10 WHEN district_type='LOGISTICS' THEN 10 WHEN district_type IN ('RESIDENTIAL','AGRICULTURAL','COMMERCIAL','HARBOR','MINING','FORESTRY') THEN 5 ELSE 0 END AS worker_efficiency_bonus,
  CASE district_type WHEN 'MILITARY' THEN 10 WHEN 'GOVERNMENT' THEN 10 WHEN 'LOGISTICS' THEN 5 ELSE 0 END AS repair_priority_bonus
FROM city_districts WHERE status='ACTIVE';
