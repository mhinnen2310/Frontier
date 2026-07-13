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

## 1.0.0 - 2026-07-13

- First complete Paper 26.2 release of The Frontier.
- Adds persistent settlements, influence, economy, production, logistics, NPCs, campaigns, damage and reconstruction.
- Adds regional simulation, dynamic events, kingdoms, diplomacy, research, wonders, mega projects and global objectives.
- Adds PostgreSQL/Flyway persistence, transactional outbox/recovery, Paper Dialogs with single-use actions, metrics, admin diagnostics and exploit controls.
- Verifies the shaded JAR with unit, PostgreSQL integration, concurrency and real Paper startup/upgrade smoke tests.
