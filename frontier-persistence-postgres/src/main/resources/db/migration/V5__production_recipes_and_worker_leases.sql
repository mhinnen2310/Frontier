CREATE TABLE commodity_definitions (
  commodity_key VARCHAR(96) PRIMARY KEY,
  category VARCHAR(32) NOT NULL,
  reference_price_minor BIGINT NOT NULL CHECK(reference_price_minor > 0),
  strategic BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE recipes (
  recipe_key VARCHAR(96) PRIMARY KEY,
  building_category VARCHAR(32) NOT NULL,
  work_units_per_unit BIGINT NOT NULL CHECK(work_units_per_unit > 0),
  worker_profession VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE recipe_inputs (
  recipe_key VARCHAR(96) NOT NULL REFERENCES recipes(recipe_key),
  commodity_key VARCHAR(96) NOT NULL REFERENCES commodity_definitions(commodity_key),
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  PRIMARY KEY(recipe_key,commodity_key)
);
CREATE TABLE recipe_outputs (
  recipe_key VARCHAR(96) NOT NULL REFERENCES recipes(recipe_key),
  commodity_key VARCHAR(96) NOT NULL REFERENCES commodity_definitions(commodity_key),
  quantity BIGINT NOT NULL CHECK(quantity > 0),
  PRIMARY KEY(recipe_key,commodity_key)
);

ALTER TABLE production_orders ADD COLUMN idempotency_key UUID UNIQUE;
ALTER TABLE production_orders ADD COLUMN target_units BIGINT NOT NULL DEFAULT 0 CHECK(target_units >= 0);
ALTER TABLE production_orders ADD COLUMN lease_owner UUID;
ALTER TABLE production_orders ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE TABLE production_reservations (
  production_order_id UUID NOT NULL REFERENCES production_orders(id),
  reservation_id UUID NOT NULL REFERENCES stock_reservations(id),
  PRIMARY KEY(production_order_id,reservation_id)
);

ALTER TABLE workers ADD COLUMN mood SMALLINT NOT NULL DEFAULT 70 CHECK(mood BETWEEN 0 AND 100);
ALTER TABLE workers ADD COLUMN experience BIGINT NOT NULL DEFAULT 0 CHECK(experience >= 0);
ALTER TABLE workers ADD COLUMN assigned_building UUID REFERENCES city_buildings(id);
ALTER TABLE workers ADD COLUMN last_simulation_at TIMESTAMPTZ;
CREATE INDEX idx_workers_available ON workers(city_id,profession,state) WHERE state='IDLE';

INSERT INTO commodity_definitions(commodity_key,category,reference_price_minor,strategic) VALUES
('minecraft:oak_log','RAW',25,FALSE),('minecraft:oak_planks','PROCESSED',12,FALSE),
('minecraft:cobblestone','RAW',8,FALSE),('minecraft:stone_bricks','INFRASTRUCTURE',18,FALSE),
('minecraft:raw_iron','RAW',55,TRUE),('minecraft:iron_ingot','PROCESSED',90,TRUE),
('minecraft:coal','FUEL',35,TRUE),('frontier:tool_kit','COMPONENT',450,TRUE),
('minecraft:wheat','FOOD',10,TRUE),('minecraft:bread','FOOD',35,TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO recipes(recipe_key,building_category,work_units_per_unit,worker_profession) VALUES
('frontier:saw_planks','INDUSTRY',100,'LUMBERJACK'),
('frontier:cut_stone','INDUSTRY',120,'BUILDER'),
('frontier:smelt_iron','INDUSTRY',180,'BLACKSMITH'),
('frontier:bake_bread','AGRICULTURE',80,'FARMER')
ON CONFLICT DO NOTHING;
INSERT INTO recipe_inputs VALUES
('frontier:saw_planks','minecraft:oak_log',1),
('frontier:cut_stone','minecraft:cobblestone',2),
('frontier:smelt_iron','minecraft:raw_iron',1),('frontier:smelt_iron','minecraft:coal',1),
('frontier:bake_bread','minecraft:wheat',3)
ON CONFLICT DO NOTHING;
INSERT INTO recipe_outputs VALUES
('frontier:saw_planks','minecraft:oak_planks',4),
('frontier:cut_stone','minecraft:stone_bricks',2),
('frontier:smelt_iron','minecraft:iron_ingot',1),
('frontier:bake_bread','minecraft:bread',1)
ON CONFLICT DO NOTHING;
