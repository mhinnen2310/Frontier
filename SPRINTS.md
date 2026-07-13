# Implementation sprints

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
