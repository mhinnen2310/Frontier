CREATE TABLE district_balance_settings (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
  minimum_building_integrity SMALLINT NOT NULL CHECK(minimum_building_integrity BETWEEN 1 AND 100),
  minimum_infrastructure_integrity SMALLINT NOT NULL CHECK(minimum_infrastructure_integrity BETWEEN 1 AND 100),
  diminishing_return_percent SMALLINT NOT NULL CHECK(diminishing_return_percent BETWEEN 1 AND 100),
  maximum_building_contributions SMALLINT NOT NULL CHECK(maximum_building_contributions BETWEEN 1 AND 20),
  adjacency_bonus_percent SMALLINT NOT NULL CHECK(adjacency_bonus_percent BETWEEN 1 AND 100),
  maximum_adjacency_bonuses SMALLINT NOT NULL CHECK(maximum_adjacency_bonuses BETWEEN 1 AND 20),
  adjacency_distance_blocks SMALLINT NOT NULL CHECK(adjacency_distance_blocks BETWEEN 1 AND 256),
  over_specialization_threshold SMALLINT NOT NULL CHECK(over_specialization_threshold BETWEEN 1 AND 20),
  over_specialization_penalty_percent SMALLINT NOT NULL CHECK(over_specialization_penalty_percent BETWEEN 1 AND 100),
  maximum_effective_bonus_percent SMALLINT NOT NULL CHECK(maximum_effective_bonus_percent BETWEEN 1 AND 100),
  industrial_maintenance_penalty_percent SMALLINT NOT NULL CHECK(industrial_maintenance_penalty_percent BETWEEN 1 AND 100),
  military_wage_penalty_percent SMALLINT NOT NULL CHECK(military_wage_penalty_percent BETWEEN 1 AND 100),
  commercial_market_orders_per_building SMALLINT NOT NULL CHECK(commercial_market_orders_per_building BETWEEN 1 AND 100),
  logistics_warehouse_capacity_percent SMALLINT NOT NULL CHECK(logistics_warehouse_capacity_percent BETWEEN 1 AND 100),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO district_balance_settings(
  singleton,minimum_building_integrity,minimum_infrastructure_integrity,
  diminishing_return_percent,maximum_building_contributions,
  adjacency_bonus_percent,maximum_adjacency_bonuses,adjacency_distance_blocks,
  over_specialization_threshold,over_specialization_penalty_percent,
  maximum_effective_bonus_percent,industrial_maintenance_penalty_percent,
  military_wage_penalty_percent,commercial_market_orders_per_building,
  logistics_warehouse_capacity_percent)
VALUES(TRUE,40,40,50,3,10,2,16,2,20,30,10,10,4,25);

CREATE INDEX idx_road_nodes_city_world_position
  ON road_nodes(city_id,world_id,x,z) WHERE integrity >= 1;

DROP VIEW district_effects;
CREATE VIEW district_effects AS
WITH metrics AS (
  SELECT d.id AS district_id,d.city_id,d.district_type,s.*,
    (SELECT count(*)::int
       FROM city_buildings b
      WHERE b.district_id=d.id
        AND b.status IN ('ACTIVE','DAMAGED')
        AND b.integrity>=s.minimum_building_integrity
        AND CASE d.district_type
          WHEN 'RESIDENTIAL' THEN b.building_type='HOUSING'
          WHEN 'AGRICULTURAL' THEN b.building_type='FARM'
          WHEN 'INDUSTRIAL' THEN b.building_type IN ('WAREHOUSE','BUILDER_GUILD')
          WHEN 'COMMERCIAL' THEN b.building_type='MARKET'
          WHEN 'MILITARY' THEN b.building_type='BARRACKS'
          WHEN 'GOVERNMENT' THEN b.building_type='BUILDER_GUILD'
          WHEN 'LOGISTICS' THEN b.building_type='WAREHOUSE'
          WHEN 'HARBOR' THEN b.building_type IN ('WAREHOUSE','MARKET')
          WHEN 'MINING' THEN b.building_type='WAREHOUSE'
          WHEN 'FORESTRY' THEN b.building_type='WAREHOUSE'
          WHEN 'RESEARCH' THEN b.building_type='BUILDER_GUILD'
          WHEN 'CULTURE' THEN b.building_type='MARKET'
          ELSE FALSE END) AS valid_buildings,
    (SELECT count(*)::int
       FROM road_nodes n
      WHERE n.city_id=d.city_id AND n.world_id=r.world_id
        AND n.x BETWEEN r.min_x AND r.max_x AND n.y BETWEEN r.min_y AND r.max_y
        AND n.z BETWEEN r.min_z AND r.max_z
        AND n.integrity>=s.minimum_infrastructure_integrity
        AND EXISTS(SELECT 1 FROM road_edges e
                    WHERE (e.from_node=n.id OR e.to_node=n.id)
                      AND e.integrity>=s.minimum_infrastructure_integrity)) AS infrastructure_nodes,
    (SELECT count(*)::int FROM districts same
      WHERE same.city_id=d.city_id AND same.district_type=d.district_type
        AND same.status='ACTIVE') AS same_type_districts,
    (SELECT count(*)::int
       FROM districts other JOIN district_regions adjacent ON adjacent.district_id=other.id
      WHERE other.city_id=d.city_id AND other.id<>d.id AND other.status='ACTIVE'
        AND adjacent.world_id=r.world_id
        AND adjacent.max_x>=r.min_x-s.adjacency_distance_blocks
        AND adjacent.min_x<=r.max_x+s.adjacency_distance_blocks
        AND adjacent.max_z>=r.min_z-s.adjacency_distance_blocks
        AND adjacent.min_z<=r.max_z+s.adjacency_distance_blocks
        AND CASE d.district_type
          WHEN 'RESIDENTIAL' THEN other.district_type IN ('COMMERCIAL','CULTURE')
          WHEN 'AGRICULTURAL' THEN other.district_type IN ('COMMERCIAL','LOGISTICS')
          WHEN 'INDUSTRIAL' THEN other.district_type IN ('COMMERCIAL','LOGISTICS','MINING','FORESTRY','RESEARCH')
          WHEN 'COMMERCIAL' THEN other.district_type IN ('RESIDENTIAL','AGRICULTURAL','INDUSTRIAL','LOGISTICS','HARBOR','CULTURE')
          WHEN 'MILITARY' THEN other.district_type IN ('RESIDENTIAL','GOVERNMENT')
          WHEN 'GOVERNMENT' THEN other.district_type IN ('MILITARY','RESEARCH','CULTURE')
          WHEN 'LOGISTICS' THEN other.district_type IN ('AGRICULTURAL','INDUSTRIAL','COMMERCIAL','HARBOR','MINING','FORESTRY')
          WHEN 'HARBOR' THEN other.district_type IN ('COMMERCIAL','LOGISTICS')
          WHEN 'MINING' THEN other.district_type IN ('INDUSTRIAL','LOGISTICS')
          WHEN 'FORESTRY' THEN other.district_type IN ('INDUSTRIAL','LOGISTICS')
          WHEN 'RESEARCH' THEN other.district_type IN ('INDUSTRIAL','GOVERNMENT')
          WHEN 'CULTURE' THEN other.district_type IN ('RESIDENTIAL','COMMERCIAL','GOVERNMENT')
          ELSE FALSE END) AS compatible_adjacencies
  FROM districts d
  JOIN district_regions r ON r.district_id=d.id
  CROSS JOIN district_balance_settings s
  WHERE d.status='ACTIVE'
), base AS (
  SELECT m.*,
    CASE district_type WHEN 'AGRICULTURAL' THEN 20 WHEN 'INDUSTRIAL' THEN 20 WHEN 'MINING' THEN 20 WHEN 'FORESTRY' THEN 15 WHEN 'LOGISTICS' THEN 5 WHEN 'HARBOR' THEN 5 WHEN 'RESEARCH' THEN 5 ELSE 0 END AS base_production,
    CASE district_type WHEN 'RESIDENTIAL' THEN 20 WHEN 'CULTURE' THEN 10 ELSE 0 END AS base_housing,
    CASE district_type WHEN 'GOVERNMENT' THEN 10 WHEN 'INDUSTRIAL' THEN 5 WHEN 'FORESTRY' THEN 5 WHEN 'LOGISTICS' THEN 5 ELSE 0 END AS base_maintenance,
    CASE district_type WHEN 'MILITARY' THEN 20 WHEN 'GOVERNMENT' THEN 5 ELSE 0 END AS base_defense,
    CASE district_type WHEN 'COMMERCIAL' THEN 20 WHEN 'HARBOR' THEN 20 WHEN 'LOGISTICS' THEN 10 WHEN 'GOVERNMENT' THEN 5 WHEN 'CULTURE' THEN 5 ELSE 0 END AS base_trade,
    CASE WHEN district_type='INDUSTRIAL' THEN 10 WHEN district_type='RESEARCH' THEN 20 WHEN district_type='CULTURE' THEN 10 WHEN district_type='LOGISTICS' THEN 10 WHEN district_type IN ('RESIDENTIAL','AGRICULTURAL','COMMERCIAL','HARBOR','MINING','FORESTRY') THEN 5 ELSE 0 END AS base_worker,
    CASE district_type WHEN 'MILITARY' THEN 10 WHEN 'GOVERNMENT' THEN 10 WHEN 'LOGISTICS' THEN 5 ELSE 0 END AS base_repair,
    CASE WHEN valid_buildings>0 AND infrastructure_nodes>0 THEN greatest(0,
      100
      + least(maximum_building_contributions-1,greatest(valid_buildings-1,0))*diminishing_return_percent
      + least(maximum_adjacency_bonuses,compatible_adjacencies)*adjacency_bonus_percent
      - greatest(same_type_districts-over_specialization_threshold,0)*over_specialization_penalty_percent)
      ELSE 0 END AS effective_factor_percent
  FROM metrics m
)
SELECT district_id,city_id,
  least(maximum_effective_bonus_percent,base_production*effective_factor_percent/100)::int AS production_bonus,
  least(maximum_effective_bonus_percent,base_housing*effective_factor_percent/100)::int AS housing_bonus,
  least(maximum_effective_bonus_percent,base_maintenance*effective_factor_percent/100)::int AS maintenance_bonus,
  least(maximum_effective_bonus_percent,base_defense*effective_factor_percent/100)::int AS defense_bonus,
  least(maximum_effective_bonus_percent,base_trade*effective_factor_percent/100)::int AS trade_bonus,
  least(maximum_effective_bonus_percent,base_worker*effective_factor_percent/100)::int AS worker_efficiency_bonus,
  least(maximum_effective_bonus_percent,base_repair*effective_factor_percent/100)::int AS repair_priority_bonus,
  effective_factor_percent>0 AS specialization_active,
  valid_buildings,infrastructure_nodes,compatible_adjacencies,same_type_districts,effective_factor_percent,
  CASE WHEN district_type='INDUSTRIAL' AND effective_factor_percent>0 THEN industrial_maintenance_penalty_percent ELSE 0 END AS maintenance_penalty_percent,
  CASE WHEN district_type='MILITARY' AND effective_factor_percent>0 THEN military_wage_penalty_percent ELSE 0 END AS wage_penalty_percent,
  CASE WHEN district_type='COMMERCIAL' AND effective_factor_percent>0 THEN valid_buildings*commercial_market_orders_per_building ELSE 0 END AS market_order_capacity_bonus,
  CASE WHEN district_type='LOGISTICS' AND effective_factor_percent>0 THEN logistics_warehouse_capacity_percent ELSE 0 END AS warehouse_capacity_bonus_percent
FROM base;
