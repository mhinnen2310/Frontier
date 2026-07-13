# Implementation sprints

## Master Remediation & Expansion Roadmap (active)

The attached 60-sprint roadmap supersedes the legacy implementation sequence below. Status changes only after end-to-end evidence, tests, documentation and a sprint commit.

| Train | Sprints | Scope | Status |
|---|---:|---|---|
| A | 1 | Repository Baseline and Truthful Status | complete |
| A | 2 | Central typed configuration architecture | complete |
| A | 3 | Fresh-world economy bootstrap | complete |
| A | 4 | Complete claim protection | complete |
| A | 5 | Repair engine integrity | complete |
| B | 6–10 | Founding, membership, districts and specialization | complete |
| C | 11 | Building validation framework | complete |
| C | 12 | Core building validators | complete |
| C | 13 | Building registration UX | complete |
| C | 14–15 | Physical roads and infrastructure failure | complete |
| D | 16 | Complete worker model | complete |
| D | 17 | Worker scheduling and visible activity | complete |
| D | 18–20 | Builder Guild, population and ambient life | pending |
| E | 21–25 | Cargo, physical caravans, escorts, raids and criminality | pending |
| F | 26–30 | Diplomacy, campaign outcomes, occupation/conquest and civil conflict | pending |
| G | 31–35 | Locator Bar waypoints, cartography, Atlas and intelligence | pending |
| H | 36–40 | Strategic map walls, local web API/map and dashboards | pending |
| I | 41–45 | Unified history, timeline, Chronicle, monuments and tourism | pending |
| J | 46–50 | Exploit/governance/concurrency audits, profiling and diagnostics | pending |
| K | 51–55 | Onboarding, Dialog UX, notifications, multiplayer QA and telemetry | pending |
| L | 56–60 | Configuration/player/admin docs, clean-room QA and stable 1.1.0 | pending |

Release trains are pushed/tagged only after the complete five-sprint quality gate. Because the repository already published `1.1.0-RC1`, intermediate versions must advance from that version and may not use the lower alpha/beta identifiers suggested for a greenfield branch.

## Legacy implementation sequence

This order follows the dependency graph in the Developer Blueprint. A sprint is complete only when its domain invariants, persistence, recovery behavior, command/UI entry points, and automated checks pass.

| Sprint | Scope | Exit gate |
| --- | --- | --- |
| 0 | Gradle, module boundaries, bootstrap, configuration, PostgreSQL, Flyway, logging, CI | Plugin lifecycle and migrations pass |
| 1 | Settlement core, members, roles, invitations, buildings, claims and upgrades | Restart-safe settlement gameplay |
| 2 | Treasury, immutable ledger, taxes and maintenance | Concurrent debit invariants pass |
| 3 | Influence budget, reachable flood-fill, contested hysteresis and dirty queues | No world scans; deterministic borders |
| 4 | Relations, campaigns, objectives, defender scaling and breach budget | Continuous-war and exploit policies pass |
| 5 | Damage journal, original-state capture and structure integrity | Damage survives restart without duplication |
| 6 | Repair estimates, payment, dependency plans and safe placement | Level 3 settlement repair vertical slice |
| 7 | Builder Guild, worker leases, Mannequin presentation and navigation adapter | Entity loss cannot lose tasks/materials |
| 8 | Warehouses, reservations, production, markets, escrow and contracts | Atomic matching and stock safety pass |
| 9 | Road graph, transfers, shipments and caravans | Route failure cannot duplicate cargo |
| 10 | Population, consumption, seasons, decay and dynamic events | Bounded dirty-object simulation |
| 11 | Kingdoms, treaties, research, prestige, wonders and history | Civilization progression vertical slice |
| 12 | Admin recovery, metrics, rate limits, performance/load and release hardening | Reproducible release candidate |
