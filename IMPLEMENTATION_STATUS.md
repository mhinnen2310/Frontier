# Frontier implementation status

This is the truthful baseline for the 60-sprint Master Remediation & Expansion Roadmap, initially audited on 2026-07-13 against commit `f04db67` and maintained through Sprint 14 with real Paper 26.2/PostgreSQL gates. A class, table or command name alone does not count as a complete feature.

## Inventory

| Surface | Audited result |
|---|---:|
| Gradle modules | 14 |
| Application/domain service classes | 19 |
| Command roots | 25 |
| Paper Dialog screens | 15 |
| Paper listener adapters | 8 |
| Flyway migrations | 47 (V1–V47) |
| PostgreSQL public gameplay tables | 158 |
| Bukkit permissions | 3 |

The runtime modules are domain, API, city, influence, economy, warfare, repair, NPC, world, Paper UI, PostgreSQL persistence, observability and bootstrap. Testkit is build-only. `/frontier admin build` reports the packaged version, Git source revision/time, Java runtime, Paper target, live schema version and these module states.

## Feature truth table

| Area | Status | Evidence and remaining gap |
|---|---|---|
| Wallets, treasury and Harbor bootstrap | complete | Transactional player/city transfers, idempotency and audit plus config-driven low-tier stock/jobs/orders, non-arbitrage pricing and global/player daily caps have integration coverage |
| Claim protection | complete | One cached `TerritoryActionPolicy` covers actor/action/source/target, ownership, roles, overrides, campaign, treaty, incident and bypass; all listed Paper actions plus cross-boundary propagation have exploit and handler-contract coverage |
| Repair integrity | complete | Generation-linked breach accounting, two-phase mutation/material consumption, idempotent purchase/commit, multi-task progress, lease/restart recovery, unload defer, conflict quarantine and completion-based archive/re-break tests pass |
| Settlement founding/lifecycle | complete for Sprints 6–7 | Physical expedition founding, charter/founders, configurable fee/material/location rules, invitations/revocation, leave/kick/ban, role-safe transfer/succession, delayed disband, abandonment, asset-frozen ruins/recovery, merge and complete audit history have transactional integration coverage |
| Districts | complete for Sprint 10 | Name-based management and complete Dialog reports feed a config-driven effective specialization projection requiring valid buildings plus connected infrastructure, with diminishing returns, adjacency, over-specialization and real cost/capacity trade-offs |
| Buildings | complete for Sprint 13 | All ten types have safe config-driven physical rules; the Architect Dialog and tagged selection tool provide timeout, particles, overlap preview, reports and confirm/cancel; transactional revalidation/unregister/history and two-mayor parcel ownership transfer have PostgreSQL coverage |
| Roads/infrastructure | complete for Sprint 14 | Configured material profiles, bounded curved path detection, endpoint/width/continuity/slope/gap/bridge/tunnel/gate checks, derived health/capacity, ownership and per-region immutable snapshots have pure/PostgreSQL coverage; block changes feed a bounded transactional dirty workset. Failure orders and critical-path gameplay remain Sprint 15. |
| Workers/population | partial | Profession, skill, morale, wage, experience, housing, employment, migration and retirement exist; full task states, visible schedules and Builder Guild player contribution are incomplete |
| Ambient settlement life | partial | Harbor/worker/caravan presentation and announcements exist; citizen/guard/market/day-night activity is incomplete |
| Cargo/caravans/contracts | partial | Database cargo, shipment reservation, abstract/physical caravan handoff, escort and delivery exist; capture, always-attackable cargo, raid locks and multi-escort contribution are missing |
| Crime/incidents/stolen cargo | missing | No criminality, evidence, bounty, laundering, restitution or incident/casus-belli domain |
| Campaigns and outcomes | partial | Campaign phases/objectives, breach rules, occupation, transfer, conquest, tribute and reparations exist; full treaty enforcement, cooldowns, momentum and internal kingdom conflict remain |
| Kingdoms/endgame | partial | Membership, roles, votes, treasury, tax, treaties, research, wonders, projects and rankings exist; full diplomacy effects, coalitions, split/reunification remain |
| Locator Bar waypoints | missing | No `frontier-waypoints` module or player anchor lifecycle |
| Cartography/Atlas/fog of war | missing | No `frontier-cartography` module, map sessions, tile cache or intelligence policy |
| Strategic map walls | missing | No registered frame-grid rendering or recovery |
| Local web/API/map/dashboard | missing | No `frontier-web` module or local HTTP server |
| Unified history/Chronicle/tourism | partial | Several domain-specific history tables exist; no one immutable visibility-aware ledger, timeline, Chronicle, monuments or tourism |
| Typed configuration | complete | Global plus 17 versioned module files load into immutable records, reject unknown/invalid/dependency-unsafe state, redact secrets, classify reloads and disable commands/listeners/supervisors safely |
| Admin/security/performance | partial | Health, recovery, build/config reports, inspectors, security/performance reports, metrics and scale harness exist; later web/map/waypoint coverage is missing |
| Onboarding/Dialog/notifications | partial | Harbor onboarding and 14 command-backed Dialog screens exist; waypoint guidance, confirmations, pagination, role-aware buttons and notification digests are incomplete |
| Documentation/clean-room release | partial | Current system is documented and RC-tested, but documentation for the missing expansion features cannot be complete yet |

## Static audit findings

- No code `TODO`, `FIXME`, `XXX`, `HACK`, placeholder or no-op markers were found. One `unsupported road node type` exception is intentional validation.
- No scattered `getConfig()` reads remain in gameplay/bootstrap wiring. Seven previously unconsumed legacy keys were removed during the version-1 migration rather than retained as misleading switches.
- Ten tables have no direct Java SQL reference: `builder_depot_stock`, `campaign_participants`, `city_roles`, `city_upgrades`, `economic_metrics`, `economy_cycle_state`, `idempotency_tokens`, `production_chain_steps`, `production_chains`, and `regional_modifiers`. Some are schema constraints/history foundations, but none may be called active gameplay without Sprint-level evidence.
- The supplied failed startup used two plugin JARs and a stopped PostgreSQL listener. The duplicate snapshot was removed, PostgreSQL was started on port 55432, the committed RC1 JAR replaced it, and Java 26/Paper 26.2 startup now passes.

## Blocked

No repository or implementation blocker is known. Human gameplay feel, public network behavior and visual usability require later multiplayer playtests, explicitly scheduled in Sprints 51–59.
