CREATE TABLE endgame_research_definitions (
  project_key VARCHAR(64) PRIMARY KEY,
  branch VARCHAR(32) NOT NULL,
  required_era VARCHAR(24) NOT NULL,
  required_points BIGINT NOT NULL CHECK(required_points > 0),
  prerequisite_key VARCHAR(64) REFERENCES endgame_research_definitions(project_key),
  effect JSONB NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE endgame_wonder_definitions (
  wonder_key VARCHAR(64) PRIMARY KEY,
  required_era VARCHAR(24) NOT NULL,
  commodity_key VARCHAR(96) NOT NULL,
  required_units BIGINT NOT NULL CHECK(required_units > 0),
  prestige_reward BIGINT NOT NULL CHECK(prestige_reward > 0),
  effect JSONB NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE endgame_mega_definitions (
  project_key VARCHAR(64) PRIMARY KEY,
  required_era VARCHAR(24) NOT NULL,
  commodity_key VARCHAR(96) NOT NULL,
  required_units BIGINT NOT NULL CHECK(required_units > 0),
  prestige_reward BIGINT NOT NULL CHECK(prestige_reward > 0),
  effect JSONB NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE kingdom_unlocks (
  kingdom_id UUID NOT NULL REFERENCES kingdoms(id),
  content_type VARCHAR(24) NOT NULL,
  content_key VARCHAR(64) NOT NULL,
  effect JSONB NOT NULL,
  unlocked_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(kingdom_id,content_type,content_key)
);

INSERT INTO endgame_research_definitions(project_key,branch,required_era,required_points,prerequisite_key,effect) VALUES
 ('roads_1','ENGINEERING','FRONTIER',1,NULL,'{"roadCapacityPercent":5}'),
 ('logistics_2','ENGINEERING','EXPANSION',100,'roads_1','{"caravanCapacityPercent":10}'),
 ('public_health','CIVICS','KINGDOM',250,NULL,'{"plagueResistancePercent":15}');
INSERT INTO endgame_wonder_definitions(wonder_key,required_era,commodity_key,required_units,prestige_reward,effect) VALUES
 ('GRAND_LIGHTHOUSE','GOLDEN_AGE','minecraft:stone_bricks',2,500,'{"tradeRangePercent":25}'),
 ('WORLD_ARCHIVE','GOLDEN_AGE','minecraft:bookshelf',5000,750,'{"researchPercent":20}');
INSERT INTO endgame_mega_definitions(project_key,required_era,commodity_key,required_units,prestige_reward,effect) VALUES
 ('CONTINENTAL_HIGHWAY','KINGDOM','minecraft:stone',3,150,'{"roadDecayPercent":-15}'),
 ('ROYAL_AQUEDUCT','INDUSTRIAL','minecraft:brick',10000,250,'{"housingPercent":10}');
