# Frontier Security Audit

Audit baseline: remediation Sprint 18, Paper 26.2, PostgreSQL 18.

| Area | Status | Enforced boundary |
| --- | --- | --- |
| Permissions | Pass | Administrative commands require `frontier.admin`; claim bypass defaults to operators; gameplay mutations additionally require persisted settlement/kingdom roles. |
| SQL injection | Pass | Player values use prepared-statement parameters. The only dynamic production table/query fragments come from fixed source-code allowlists. Arbitrary SQL is never accepted. |
| Race conditions | Pass | Money, stock, campaigns, repairs and events mutate inside transactions. Coordinate damage and event sources use transaction advisory locks; active-state uniqueness is also enforced by indexes. |
| Deadlocks/serialization | Pass | SQL states `40P01` and `40001` receive at most three whole-transaction retries. Supervisors lock ordered, bounded batches with `SKIP LOCKED`. |
| Duplication | Pass | Ledger, market, contract, contribution, campaign result and event response paths use unique idempotency/replay keys. Authoritative caravan cargo exists only in PostgreSQL. |
| Chunk exploits | Pass | Claim decisions use the committed ownership projection; cross-owner automation, fire and redstone are denied; physical scans are bounded. |
| Disconnect exploits | Pass | Player inventories use compensating restoration, caravan entities are projections, and repair leases/reservations recover after expiry or restart. |
| Inventory exploits | Pass | Container, hopper, bucket, entity and vehicle paths are claim checked. Market deposits remove held items once and compensate database failure. |
| Replay attacks | Pass | Dangerous contextual dialogs are player/aggregate bound and single-use; general dialogs close after submission; database commands use unique idempotency keys. |
| Packet/command abuse | Pass | Every player `/frontier` invocation, including menu-only opens and operators, is rate limited. Paper remains authoritative for packet validation. |
| Economic exploits | Pass | Accounts and stock cannot be negative; transfers lock accounts and write balanced audited ledger entries; integer arithmetic is checked or database bounded. |
| Repair exploits | Pass | Damage coordinates serialize, lifecycle transitions are explicit, material consumption commits only after placement, and stale work is recoverable. |
| War exploits | Pass | Campaign pairs, declarations, kingdom approvals and results are replay safe; structural damage requires active scoped objectives and bounded breach capacity. |

Operators can run `/frontier admin security` at any time. It verifies live balance/stock invariants, campaign and event uniqueness, ledger replay keys, damage coordinates, stale damage/reservations, repair consumption and required indexes. Any `FAIL` line is an operational incident and should be investigated before continuing gameplay.
