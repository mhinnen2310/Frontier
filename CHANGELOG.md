# Changelog

## Master Roadmap Sprint 1 — Repository Baseline and Truthful Status

- Replaced the broad RC-complete claim with an evidence-backed inventory and feature truth table covering all 60 expansion sprints.
- Added `/frontier admin build` with packaged version, Git revision/time, Java runtime, Paper target, live Flyway schema and module statuses.
- Audited source markers, dispersed/unused configuration, unreferenced schema foundations, commands, Dialogs, listeners, modules and migrations.
- Documented and repaired the observed Paper launch blockers: duplicate plugin JARs and an unavailable PostgreSQL listener; the RC1 plugin now starts cleanly on Java 26/Paper 26.2.
- Added startup troubleshooting and corrected README installation/status claims.

## Sprint 24 — Release Candidate

- Set the audited artifact version to `1.1.0-RC1`; the published 1.0.0 remains immutable.
- Added fail-closed startup validation for every critical positive configuration bound, database identity, campaign multiplier/budget consistency and pool/thread caps.
- Closed the source-marker audit, repeated the full regression, clean-world, scale, security and performance gates, and recorded the final JAR checksum.
- Added release-candidate deployment scope and pushed the source plus shaded JAR as the final five-sprint checkpoint.

## Sprint 23 — Final QA

- Added a dedicated clean-database `finalQaTest` gate for the full normal-player journey, separate from development and scale databases.
- Added a traceable acceptance matrix covering settlement founding/upgrading, districts, economy, population, roads, buildings, markets, workers, campaigns/conquest, repairs, kingdoms, events, wonders, migration, new-player and late-game integrity.
- Required a newly generated Paper world startup with no Frontier admin command used to seed gameplay.
- Documented the honest human-playtest boundary for subjective feel and real network clients.

## Sprint 22 — Documentation

- Added one maintained documentation index and complete manuals for architecture, gameplay, commands, all Paper Dialog screens, administration, permissions, developer API, database schema, configuration and upgrades.
- Documented the no-admin Harbor progression, transaction/service boundaries, all command families, runtime recovery/security/performance operations and forward-only rollback rules.
- Added a documentation coverage test that fails when a required manual, Dialog screen, permission, configuration section or migration range disappears.
- Updated the root quick start and implementation status to point at the post-audit documentation and 31-migration runtime.

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

### Sprint 7 — Roads & Infrastructure

- Replaced player-facing abstract edge creation with a bounded live-world survey between registered infrastructure nodes.
- Detects physical road materials, minimum width, continuous coverage, bridge spans, tunnel cover, surface quality, slope, broken segments and destroyed bridge gaps.
- Added Road, Bridge, Tunnel, Gate, Harbor and Watchtower graph types and expanded node registration for tunnel/watchtower infrastructure.
- Persists edge health, derived capacity, traffic, importance, owner, validation evidence and physical measurements through Flyway migration V21.
- Route use now increments authoritative edge traffic; routing continues to exclude unhealthy or zero-capacity edges.
- Added validator exploit coverage plus PostgreSQL assertions for physical edge ownership, capacity, quality, health, traffic and route compatibility.

### Sprint 8 — Caravan Simulation

- Added PostgreSQL-authoritative caravan state and history for shipment loading, walking, route pauses, combat, retreat, unloading and despawn.
- Added hybrid simulation: routes advance without loaded chunks, while nearby players materialize a chest llama projection that walks the persisted route.
- Added plugin chunk tickets only around observed physical caravans and automatic dematerialization back to simulated mode when no player is nearby.
- Cargo remains exclusively in shipment reservations/items; the NPC carries no authoritative inventory and cannot duplicate goods.
- Added `/frontier caravan list|escort`, escort damage mitigation, entity combat projection, combat-paused delivery and bounded retreat/recovery before rerouting.
- Added Flyway migration V22 and integration coverage for synchronization, routing, presentation binding, escort, combat pause, reroute, unloading, despawn and edge traffic.

### Sprint 9 — Population Simulation

- Expanded workers with persisted morale, derived efficiency, employment status, housing assignment, age, retirement age and retirement state while retaining profession, skill, salary and experience.
- Added a daily transactional demographic cycle for births, deaths, immigration and emigration driven by physical housing capacity, food stock, military safety, campaign risk and prosperity.
- Added demographic history, applied migration records and city population-state reports through Flyway migration V23.
- District worker-efficiency and housing bonuses now feed worker output and capacity; employed workers gain experience and housed workers reference a real residential building.
- Added `/frontier population` and `/frontier workers` reports plus a bounded population supervisor.
- Added clean-database regression coverage for housing capacity, food/safety inputs, births, immigration, worker retirement and report output.

### Sprint 10 — Complete Economy

- Added player-capitalized companies with audited company accounts, ownership/shares and `/frontier economy` command flows.
- Added company invoices and replay-safe player payments, settlement-backed company loans, daily interest, repayments and business-tax assessments.
- Added government procurement fulfilled from a company settlement warehouse and punitive Harbor-backed emergency purchasing.
- Added unified commercial history alongside the existing authoritative trade and price histories.
- Added explicit multi-step production-chain definitions and a tool-kit chain extending the existing recipe/reservation engine.
- Retained PostgreSQL-authoritative warehouses, shipment logistics, production reservations and player wallets; all new money and stock mutations are transactional.
- Added Flyway migration V24 and clean-database coverage for company capital, invoices, overdue processing, loans/interest, taxes, procurement, emergency pricing and history.

### Sprint 11 — Campaign Result Engine

- Added transactional, replay-safe resolution for occupation, liberation, conquest, annexation, territory concession, reparations, tribute, independence, civil war and kingdom intervention.
- Added authoritative transfer of scoped claims plus their buildings, roads, infrastructure ownership, workers and warehouse stock, with append-only transfer history.
- Added durable occupations, recurring tribute schedules, audited reparations/tribute ledger entries and automatic campaign completion.
- Added `/frontier war outcome` as the administrative fallback while keeping all outcome logic in `CampaignOutcomeService` and its PostgreSQL gateway.
- Added Flyway migration V25 and clean-database integration coverage for conquest transfers, recurring tribute and outcome replay safety.

### Sprint 12 — Kingdom Integration

- Added transactional KING, COUNCIL, MARSHAL and DIPLOMAT roles with explicit authority boundaries and transferable leadership.
- Added city-based majority votes, durable ballots, policy management and automatic vote/treaty expiry.
- Activated the existing kingdom account as a shared treasury with audited member deposits, authorized withdrawals and idempotent daily member taxes.
- Added mandatory, consumable kingdom war approvals with non-aggression/alliance enforcement before campaign declaration.
- Added peaceful and contested secession; contested secession authorizes a civil-war campaign and an independence outcome removes kingdom membership atomically.
- Kept wonders, research and mega projects as shared kingdom projects and exposed them with treasury, roles and policies in the integrated report.
- Added Flyway migration V26, fallback commands, an hourly governance/tax supervisor and clean-database coverage for roles, voting, treasury, tax, policy, secession and war approval.

### Sprint 13 — Complete Dialog UX

- Replaced the six disconnected early dialogs with one navigable Paper Dialog catalog covering Frontier, Settlement, District, Kingdom, Treasury, Repair, War, Market, Workers, Contracts, Infrastructure, History, Reports and Settings.
- `/frontier` now opens the root Dialog for players; `/frontier menu <screen>` provides direct navigation and all legacy commands remain fallback paths.
- Added form inputs and command-template actions for settlement founding, district lookup/rename, kingdom policy/war/tax, treasury transfers, repairs, campaigns, market orders, hiring, contracts and infrastructure.
- Corrected obsolete dialog actions that referenced missing war, repair and market commands.
- Retained player-bound single-use callbacks for contextual claim and upgrade actions and made general dialogs close after every submitted action to prevent repeated mutation clicks.
- Added catalog tests proving all fourteen screens are reachable, have back navigation, use Frontier actions and expose required parameterized flows.

### Sprint 14 — Admin & Debug

- Added read-only settlement, influence, road, repair, campaign, worker and economy viewers with joined authoritative state instead of isolated table rows.
- Added bounded in-world claim heatmaps showing capital, controlled, contested, influenced and wilderness chunks plus owning settlement IDs.
- Added exact chunk ownership inspection with influence, lead-cycle, owner and active-campaign context.
- Added combined live metrics for campaigns, repair backlog, caravans, market orders, workers, claims, outbox, database sessions and in-process counters.
- Exposed `/frontier admin settlement|influence|road|repair|campaign|worker|economy`, `heatmap`, `chunk` and `live` with permission checks and asynchronous database execution.
- Added PostgreSQL integration coverage executing every viewer, heatmap, chunk lookup and live-metric query against the complete schema.

### Sprint 15 — World Simulation

- Added deterministic daily regional weather with clear, rain, storm, heatwave and frost states, severity and season-aware selection.
- Applied weather and season to population migration and changed infrastructure aging to a safe daily cadence.
- Added audited road and bridge decay driven by maintenance, winter, storms, infrastructure type and accumulated traffic.
- Added deterministic policy and lifecycle impacts for bandit raids, disasters, migration waves, trade fairs, plagues, floods and harvest failures.
- World-event impacts now transactionally affect prosperity, population, safety, food stock, roads, bridges or buildings and are replay-safe per event/city/impact.
- Region reports now expose season, weather and severity; Flyway migration V27 adds weather, decay history and event-impact history.
- Hardened concurrent structural-damage registration with a coordinate-scoped transaction advisory lock after the expanded regression run exposed a rare duplicate-insert race.
- Added policy unit coverage for every required event and PostgreSQL coverage for weather, event activation/impact and winter infrastructure decay.

### Sprint 16 — Dynamic Events

- Added a transactional dynamic-event service and detector for escort requests, convoys, civil unrest, kingdom requests, settlement requests, bridge collapse, mine collapse, refugees and trade festivals.
- Kept bandit raids in the regional world-event policy and unified all detected events with the existing scheduled-to-archived lifecycle, objectives and rewards.
- Added player participation and cumulative response contributions; event goals resolve exactly once and expired/resolved events reject further joins.
- Joining an escort event atomically assigns the authoritative caravan escort rather than creating a cosmetic quest-only link.
- Added 24-hour per-source cooldowns so supervisors and restarts cannot spam repeated events for one caravan, settlement, project, road, mine or campaign.
- Added `/frontier events list|join|respond`, Dialog report access and a bounded minute detection supervisor.
- Added Flyway migration V28 and clean-database coverage detecting seven simultaneous source types, joining/completing an escort and checking cooldown replay protection.

### Sprint 17 — Wonders & Endgame

- Added data-driven research, unique-wonder and mega-project catalogs with era, prerequisite, material, requirement, prestige and effect definitions.
- Replaced free-text endgame starts with transactional catalog validation; unique wonders remain globally exclusive through the existing database invariant.
- Added durable kingdom unlocks on research, wonder and mega-project completion plus world-history records for completed research.
- Applied the Roads I research unlock to newly validated road capacity and the Continental Highway unlock to daily infrastructure decay.
- Added deterministic kingdom rankings combining era, prestige, population, completed research, wonders and mega projects.
- Added `/frontier endgame catalog|rankings|history|unlocks` and Dialog access to rankings.
- Added Flyway migration V29 and clean-database coverage for catalog seeds, unlocks, world history and rankings.

### Sprint 18 — Security Audit

- Completed and documented the permission, SQL injection, race, deadlock, dupe, chunk, disconnect, inventory, replay, packet/command, economy, repair and war exploit audit in `SECURITY_AUDIT.md`.
- Closed menu-only and operator command-spam bypasses by rate limiting every player Frontier invocation.
- Added whole-transaction retries, capped at three, for PostgreSQL serialization failures and deadlocks.
- Serialized dynamic-event detection per source and added a partial unique index for every open source event.
- Added unique war-approval consumption, bounded event contributions and stale security indexes in Flyway migration V30.
- Added `/frontier admin security` to verify live invariants and required indexes without mutating gameplay.
- Added permission/Dialog boundary tests, concurrent event detection coverage and full runtime security-audit assertions.

### Sprint 19 — Performance

- Added live async pool, queue-wait, execution, region/global task and named-subsystem timing instrumentation.
- Added JVM heap and PostgreSQL database size, cache hit, transaction, tuple, session, index-use, table-size and dead-tuple diagnostics through `/frontier admin performance`.
- Instrumented influence/cache, workers/production, repairs, economy, logistics, settlements, population, campaigns, events, civilization, claim cache and outbox cycles.
- Added Flyway migration V31 with due-queue/hot-path indexes for tribute, loans, repairs, production, shipments, population, caravans, workers, warehouses, events, campaigns and roads.
- Captured a real 32-second Paper/JFR baseline in `PERFORMANCE.md`; region/global tasks stayed at 0.03 ms average and 0.64 ms maximum with no queued async work at sample time.
- Made the world-event integration test weather-policy aware after profiling reruns exposed its previous fixed-event assumption.

### Sprint 20 — Multiplayer Scale Test

- Added a reproducible `scaleTest` matrix for 50, 100, 250 and 500 synthetic players using real PostgreSQL gateways and 16 concurrent clients.
- The 500 tier includes 500 settlements/claims/accounts/warehouses/orders/shipments/caravans, 1,000 workers, 250 wars/repair orders, 1,000 repair tasks and dynamic events.
- Stress coverage includes world simulation, wars, repairs, economy, worker/NPC projections, caravans, events, diagnostics, rankings and database concurrency with invariant checks.
- All tiers passed the 1,000 ms p95 and 60-second workload budgets; the 500 tier completed 4,000 operations in 2.066 seconds at 40.66 ms p95 and 81.02 ms maximum.
- Booted and JFR-profiled real Paper against the 500-player dataset; region/global callbacks remained 0.04 ms average/3.17 ms maximum, scheduler queue was empty at sample and live security passed.
- Documented methodology, exact tier results and scope limitations in `SCALE_TEST.md`.

### Sprint 21 — Polish

- Added repair placement animation with builder arm swings, happy-villager/electric particles and quiet anvil feedback on the correct region thread.
- Added server-wide campaign activation/resolution announcements and raid-horn audio cues.
- Added settlement founding broadcasts, founder title animation and advancement sound.
- Added first-visit Frontier Harbor title, ambient overworld music and bell welcome cue.
- Improved Dialog titles, explanatory bodies, action colors, mutation/read tooltips, button sizing and single-use action warnings.
- Reformatted settlement, population and worker reports into readable multi-line reports.
- Kept all polish in the Paper presentation adapter/service; no particle, sound or notification changes authoritative gameplay state.

## 1.0.0 - 2026-07-13

- First complete Paper 26.2 release of The Frontier.
- Adds persistent settlements, influence, economy, production, logistics, NPCs, campaigns, damage and reconstruction.
- Adds regional simulation, dynamic events, kingdoms, diplomacy, research, wonders, mega projects and global objectives.
- Adds PostgreSQL/Flyway persistence, transactional outbox/recovery, Paper Dialogs with single-use actions, metrics, admin diagnostics and exploit controls.
- Verifies the shaded JAR with unit, PostgreSQL integration, concurrency and real Paper startup/upgrade smoke tests.
