# Implementation status

Release `1.0.0` implements the documented Paper 26.2 gameplay as a modular, persistent server plugin. All 28 supplied design documents were read and mapped into the dependency-ordered sprints in `SPRINTS.md`.

## Completed

- Settlement lifecycle, membership, invitations, government roles, claims, registered buildings, policies, levels, treasury, taxes, wages, food and maintenance.
- Bounded influence recalculation, reachable borders, contested hysteresis, dirty queues and a rebuilt hot ownership cache.
- Warehouses, audited stock, production/workers, local order books, escrow, physical market shipments, contracts, roads, routes, caravans and abstract/visible Mannequin NPC presentation.
- Campaign declaration and lifecycle, objectives, relations, friendly-fire policy, defender scaling, breach budgets and structural block/explosion/piston/liquid enforcement.
- Durable damage journaling, paid repair quotes/orders, material reservation, dependency planning, worker packages, crash-safe prepared consumption and RegionScheduler placement/conflict handling.
- Aggregated regional simulation, migration, seasons, infrastructure aging, nature pressure and dynamic event lifecycle/objectives/rewards.
- Kingdom membership, diplomacy/treaties, research, eras, prestige, unique world wonders, shared mega projects, global objectives and history.
- Paper Dialog presenters for settlement, treasury, market, campaign and repair flows, backed by player/action/aggregate-bound single-use expiring action tokens; commands remain the complete fallback interface.
- Transactional outbox dispatch, startup recovery, hot caches, Micrometer health metrics, admin diagnostics/audit/inspection and command rate limiting.
- Fifteen forward-only Flyway migrations, unit/integration/concurrency checks, a real Paper 26.2 startup/upgrade smoke test and a reproducible shaded release JAR.

## Deliberately configurable, not missing

The open balancing choices from the documents are resolved in `DECISIONS.md` and `config.yml`. Perishable goods, private-building insurance and vanilla-automation restrictions default off; Paper is the certified runtime and Folia is not claimed as certified.

## Remaining

No implementation item remains for `1.0.0`. Live multiplayer balance and UX feedback are the next input expected from server playtesting; those are tuning rather than unfinished code.

## Unclear requirements

No implementation-blocking ambiguity remains. Where the documents intentionally offered alternatives, the chosen release defaults are documented in `DECISIONS.md`.
