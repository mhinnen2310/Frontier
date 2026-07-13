# Final QA — Sprint 23

## Gate

The release candidate is accepted only when all normal Gradle tests pass, `:frontier-persistence-postgres:finalQaTest` passes against a newly created empty database, and a new Paper 26.2 world starts and stops with Frontier enabled. The journey uses public application gateways and no Frontier admin command to create gameplay state.

Run the database journey with:

```powershell
$env:FRONTIER_QA_DATABASE_URL='jdbc:postgresql://localhost:55432/frontier_qa'
.\gradlew.bat :frontier-persistence-postgres:finalQaTest --rerun-tasks --no-configuration-cache
```

## Acceptance matrix

| Player journey | Automated evidence |
|---|---|
| New player / no-admin start | Harbor bootstraps buy/sell liquidity; onboarding and a starter job fund the first wallet once |
| Settlement creation and upgrade | Player settlement creates with starter solvency; physical founding saga, core validation, fee/refund, roles, transfer, succession, ruins, recovery and merge pass; CAMP upgrades to OUTPOST |
| Districts | Create, bounds/overlap enforcement, rename, resize, manager transfer, budget, priority, policy, worker/building assignment, reports, history and deletion pass |
| Economy and markets | Wallet, treasury/audit, order escrow/matching, warehouse stock, production, company, invoice, loan/interest, tax, procurement and emergency buying invariants pass |
| Population and workers | Housing/food/safety/prosperity, birth, immigration, emigration/migration history, salary, skill/morale/experience and retirement pass |
| Roads and infrastructure | Physical edge ownership, width, surface, health, bridge integrity, critical failure, capacity, rerouting, funded maintenance and route delivery pass |
| Buildings | Physical validation and full lifecycle history pass; invalid overlap/type/claim attempts fail atomically |
| Caravans/contracts | Authoritative cargo, visible binding, escort/combat, unload/despawn and contract escrow/delivery pass |
| Wars/conquest | Declaration cost, preparation/active/ceasefire/resolution, objectives, breach budget, conquest/territory assets, reparations and tribute pass |
| Repairs | Quote, purchase, reserve/lease, recovery, duplicate commit, complete/archive and paid re-break generation pass |
| Kingdoms | Membership, treaties, roles, voting, shared treasury, taxes, policies, war approval and secession pass |
| Events/world | Weather/season, decay, disasters, dynamic detection, participation, escort link, response/reward and cooldown pass |
| Wonders/late game | Research, eras, mega projects, unique wonders, unlock effects, history and deterministic rankings pass |
| Integrity/balancing | Non-negative money/stock, idempotent transfers, single active records, database constraints, outbox drain and security audit pass |

## Human test boundary

Automation proves rules, persistence, recovery and server startup. It cannot judge subjective music volume, text readability, combat feel, pacing or 500 real clients/network conditions. Those remain playtest feedback, not hidden implementation work; the synthetic scale scope is recorded in `SCALE_TEST.md`.

## Recorded result

On 2026-07-13 the empty `frontier_qa` database migrated through V31 and the complete integration journey passed. Paper `26.2-60` on Java 25 generated `frontier-final-qa` from scratch, enabled Frontier, reported healthy status and stopped cleanly. Evidence log: `.frontier-work/final-qa-server/sprint-23-fresh-paper.out.log` (local/ignored runtime evidence).
