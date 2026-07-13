# Repair integrity

PostgreSQL is authoritative for damage, repair orders, task leases, material reservations and
consumption. Paper blocks and worker entities are external effects and cannot share a transaction
with PostgreSQL, so both damage and repair use explicit recoverable protocols.

## Damage protocol

1. An active campaign/objective and breach capacity are checked in one transaction.
2. A position advisory lock prevents concurrent duplicate authorization.
3. The current journal generation becomes `AUTHORIZED` and reserves one generation-linked breach
   spend.
4. Paper mutates the block on its owning region thread.
5. A matching mutation becomes `APPLIED` and building integrity changes once. A missing or
   conflicting mutation becomes `REJECTED`; only that generation's reserved breach spend is
   removed.

Repeated authorization of a live generation never charges or mutates twice. A repaired/archived
position may be broken again by incrementing its generation. The unique
`(damage_id, damage_generation)` spend index makes every successful occurrence independently
auditable and prevents rejection of a later occurrence from refunding earlier damage.

The recovery supervisor inspects stale `AUTHORIZED` rows. Matching damaged world state is
confirmed, unchanged original state is rejected as phantom damage, conflicting state is rejected
for review, and unloaded worlds wait without fabricating a result.

## Repair protocol

Paid orders and journal entries use:

```text
REGISTERED -> RESERVED -> REPAIRING -> COMPLETED -> ARCHIVED
```

Every task leases one builder and prepares one already-reserved material consumption. Paper checks
the exact expected block state before placement. A successful placement commits consumption, task,
journal progress, worker experience and order progress atomically. Multi-block orders stay
`REPAIRING` after a partial commit. Archival age begins at `completed_at`, not order creation.

If a process stops after placement but before commit, the retained `PREPARED` task is leased again;
seeing the target block commits it without placing or consuming twice. A retryable failure releases
the prepared consumption and can prepare it again. Expired workers/coordinators can be rebound after
restart. World/chunk unload and temporary hostility use `TASK_DEFERRED`: the prepared material is
retained, no failure attempt is added, and other ready tasks may continue. A real unexpected block
edit releases material and moves the task/order to `REVIEW_REQUIRED` with a durable conflict row.

Purchase idempotency is serialized with an advisory transaction lock, while eligible journal rows
are locked before order creation. Concurrent replay returns the same order; distinct commands
cannot purchase the same damage twice.

## Verification matrix

The PostgreSQL integration journey covers concurrent damage, duplicate mutation confirmation,
phantom/rejected generation rollback, older-spend preservation, concurrent purchase replay,
two-block partial progress, retry/re-prepare, expired coordinator recovery, unload defer/resume,
duplicate commit, manual-edit quarantine and completion-based archival. The pure lifecycle and
dependency-cycle rules remain covered in `RepairTest`.
