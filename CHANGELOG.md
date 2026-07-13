# Changelog

## Unreleased — Post-1.0 remediation

### Sprint 1 — Settlement Bootstrap

- Added persistent player wallets with `/frontier balance` and idempotent player payments through `/frontier pay`.
- Added transactional treasury deposits, withdrawals, player payouts and expanded double-entry treasury audit output.
- Added Frontier Harbor as an automatically bootstrapped server settlement with visible starter professions, daily starter contracts, bounded daily funding and deliberately unfavorable buy/sell liquidity.
- Added first-join Harbor onboarding and a no-admin path from starter contract reward to settlement treasury funding.
- Added a 10,000-cent founding grant and finite wheat/bread reserve so a new settlement survives its initial maintenance and food cycles while its player establishes revenue.
- Added Flyway migration V16 and integration coverage for replay-safe transfers, Harbor budgets/jobs, initial settlement solvency and existing economic/war/repair flows.

### Sprint 2 — Complete Claim Protection

- Added a database-backed, atomically replaced claim/member/role/override cache and a central claim-protection application service; Paper listeners contain no authorization policy.
- Protected block placement/breaking, containers, hopper inventory movement, doors/buttons/levers, hanging entities, armor stands, buckets, fire/ignite, crop trampling, entity interaction, vehicle placement and cross-boundary redstone.
- Integrated active campaign breach authorization, settlement ownership, membership roles, explicit per-player overrides and the `frontier.protection.bypass` permission.
- Added a complete exploit-vector matrix covering every audited listener category plus role, override, campaign and bypass behavior.
- Verified all prior persistence/economy/war/repair tests and a real Paper startup after installing the complete listener set.

### Sprint 3 — Repair Engine Integrity

- Replaced the ambiguous damage/repair flags with the durable `REGISTERED → RESERVED → REPAIRING → COMPLETED → ARCHIVED` lifecycle and added Flyway migration V17.
- Added two-phase structural mutation (`AUTHORIZED → APPLIED`) so journal creation, world mutation and building-integrity damage can be reconciled without phantom repair work.
- Added startup/runtime reconciliation for mutations interrupted by crash: applied world state is confirmed, unchanged blocks are rejected and conflicting blocks are quarantined from repair.
- Made concurrent duplicate damage charge once, made repair purchase lock all eligible journal rows, and made completed/archived re-breaks create a newly charged journal generation.
- Made task commit/release/conflict retry-safe, reusable after released prepared consumption, bounded at five failed attempts and restart-safe when worker packages expire.
- Added automatic 24-hour archival and safe world-unloaded handling without stopping the repair supervisor.
- Added integration scenarios for concurrent damage, phantom rejection, restart during PREPARED consumption, worker re-lease, duplicate commit, order archival and re-break rollback.

### Sprint 4 — Building Validation Engine

- Replaced direct building registration with a Paper-world survey and transactional validation service for warehouses, housing, farms, builder guilds, markets and barracks.
- Added physical requirements for dimensions, storage, entrances, road access, beds, enclosed space, roofs, lighting, farmland, water, crops, crafting stations and market stalls.
- Added authoritative overlap, controlled-claim, settlement-role and district-compatibility checks, with a second in-transaction check before persistence.
- Added the `PLANNED → UNDER_CONSTRUCTION → VALIDATING → ACTIVE` registration path plus durable `DAMAGED`, `DISABLED` and `DESTROYED` states, validation reports, history and audit entries.
- Added validator coverage for every supported building type and PostgreSQL integration coverage for migration V18 and lifecycle history.

### Sprint 5 — District System

- Added persistent `city_districts`, `district_workers`, `district_storage`, `district_budget` and append-only `district_history` data with Flyway migration V19.
- Added transactional create, delete, resize, rename, manager assignment/transfer, budget allocation, priority, policy and worker assignment flows through `/frontier district`.
- Added all eleven district types with bounded production, housing, maintenance, defense, trade, worker-efficiency and repair-priority bonuses.
- Integrated bonuses into production work, population growth, daily maintenance, structural-defense cost, market-order priority and repair-task priority.
- Replaced temporary building district hints with authoritative district UUID, boundary and building-type compatibility validation.
- Added District Overview, Budget, Workers, Buildings, Reports, Policies and History Paper Dialog views, with commands retained as fallback.
- Added full PostgreSQL lifecycle coverage including overlap/claim/role enforcement, manager transfer, budgets, policies, workers, storage, building assignment, reporting, history and deletion.

### Sprint 6 — Settlement Lifecycle

- Added physical Settlement Core founding with world-border, terrain, height, ocean-biome and 128-block inter-settlement validation.
- Added a recoverable founding saga requiring 64 stone bricks, 16 oak logs, one bell and a 2,500-cent audited player fee; failed attempts refund both materials and money.
- Added charters, founder records, per-member activity, lifecycle history and a solo-compatible minimum-founder invariant.
- Added mayor transfer, seven-day member-triggered succession, abandonment, disbanding, 30-day ruins, claim archival/restoration and inactive-settlement recovery.
- Added two-mayor settlement merge proposals with expiry and transactional transfer of treasury, claims, buildings, workers, districts, roads, warehouse stock and membership.
- Added hourly recovery of expired founding reservations and settlements inactive for 30 days, choosing an active successor before abandonment.
- Added Flyway migration V20, legacy core/charter/founder backfill, lifecycle commands and regression coverage from a clean database.

## 1.0.0 - 2026-07-13

- First complete Paper 26.2 release of The Frontier.
- Adds persistent settlements, influence, economy, production, logistics, NPCs, campaigns, damage and reconstruction.
- Adds regional simulation, dynamic events, kingdoms, diplomacy, research, wonders, mega projects and global objectives.
- Adds PostgreSQL/Flyway persistence, transactional outbox/recovery, Paper Dialogs with single-use actions, metrics, admin diagnostics and exploit controls.
- Verifies the shaded JAR with unit, PostgreSQL integration, concurrency and real Paper startup/upgrade smoke tests.
