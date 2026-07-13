# Database schema

PostgreSQL is authoritative. Flyway applies the ordered files in `frontier-persistence-postgres/src/main/resources/db/migration/index.txt`; never modify a migration already used by a server. UUID primary keys identify aggregates, integer columns hold cents/units, timestamps are UTC, constraints protect lifecycle values, V31 indexes cover active work/lookups, V32 hardens repair occurrence/completion integrity and V33–V34 add recoverable settlement-founding expeditions plus active-founder uniqueness.

## Table families

| Family | Principal tables |
|---|---|
| Identity/audit | `accounts`, `idempotency_tokens`, `audit_log`, `ledger_entries`, `financial_transfers`, `outbox_events`, `server_history` |
| Settlements/claims | `cities`, `city_members`, `city_roles`, `city_permission_overrides`, `city_claims`, `city_policies`, `city_upgrades`, `settlement_founding_expeditions`, `settlement_founding_expedition_members`, `settlement_founding_expedition_history`, other `settlement_*`, `influence_*`, `dirty_settlements` |
| Districts/buildings | `city_districts`, `district_workers`, `district_storage`, `district_budget`, `district_history`, `district_effects`, `city_buildings`, `building_validation_history` |
| Economy | `warehouses`, `warehouse_stock`, `market_orders`, `trades`, `trade_history`, `price_history`, `companies`, `commercial_*`, `company_loans`, `business_tax_*`, `government_procurements`, `emergency_purchases` |
| Production/population | `recipes`, `recipe_*`, `production_*`, `workers`, `work_packages`, `city_population_state`, `population_history`, `demographic_history`, `migration_*` |
| Logistics/caravans | `road_nodes`, `road_edges`, `shipments`, `shipment_*`, `contracts`, `delivery_contract_terms`, `caravans`, `caravan_history` |
| War/repair | `campaigns`, `campaign_*`, `breach_spends`, `combat_activity`, `damage_journal`, `repair_*`, `builder_depots`, `material_*`, `territory_transfer_history`, `city_occupations` |
| Kingdom/endgame | `kingdoms`, `kingdom_*`, `treaties`, `diplomatic_relations`, `research_*`, `world_wonders`, `wonder_contributions`, `mega_projects`, `global_objectives`, `kingdom_prestige`, `kingdom_unlocks` |
| World/events | `world_regions`, `world_weather`, `season_state`, `nature_state`, `regional_modifiers`, `world_events`, `world_event_impacts`, `dynamic_event_*`, `event_*`, `infrastructure_decay_history` |
| Harbor | `harbor_state`, `harbor_jobs`, `player_tutorials` |

## Migration history

| Versions | Scope |
|---|---|
| V1–V3 | Core schema, settlement government/influence and daily simulation |
| V4–V9 | Escrow/jobs, production, roads/shipments, contracts, NPC projection and price history |
| V10–V15 | Campaigns, durable damage, repair, regions/events, kingdoms, research, wonders and global objectives |
| V16–V20 | Player wallets/Harbor, repair integrity, building lifecycle, districts and settlement lifecycle |
| V21–V24 | Physical infrastructure, caravans, population and complete economy |
| V25–V29 | Campaign outcomes, kingdom integration, world simulation, dynamic events and endgame |
| V30–V34 | Security constraints, performance indexes, repair occurrence/completion integrity and settlement founding expeditions |

For exact columns, foreign keys, checks and indexes, read the corresponding migration rather than relying on an independently generated schema dump. Backups must contain the complete database, including `flyway_schema_history`.
