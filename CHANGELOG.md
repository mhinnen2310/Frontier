# Changelog

## Unreleased — Post-1.0 remediation

### Sprint 1 — Settlement Bootstrap

- Added persistent player wallets with `/frontier balance` and idempotent player payments through `/frontier pay`.
- Added transactional treasury deposits, withdrawals, player payouts and expanded double-entry treasury audit output.
- Added Frontier Harbor as an automatically bootstrapped server settlement with visible starter professions, daily starter contracts, bounded daily funding and deliberately unfavorable buy/sell liquidity.
- Added first-join Harbor onboarding and a no-admin path from starter contract reward to settlement treasury funding.
- Added a 10,000-cent founding grant and finite wheat/bread reserve so a new settlement survives its initial maintenance and food cycles while its player establishes revenue.
- Added Flyway migration V16 and integration coverage for replay-safe transfers, Harbor budgets/jobs, initial settlement solvency and existing economic/war/repair flows.

## 1.0.0 - 2026-07-13

- First complete Paper 26.2 release of The Frontier.
- Adds persistent settlements, influence, economy, production, logistics, NPCs, campaigns, damage and reconstruction.
- Adds regional simulation, dynamic events, kingdoms, diplomacy, research, wonders, mega projects and global objectives.
- Adds PostgreSQL/Flyway persistence, transactional outbox/recovery, Paper Dialogs with single-use actions, metrics, admin diagnostics and exploit controls.
- Verifies the shaded JAR with unit, PostgreSQL integration, concurrency and real Paper startup/upgrade smoke tests.
