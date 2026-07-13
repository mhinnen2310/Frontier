# Architecture

Frontier is a modular Java 25 Paper plugin. PostgreSQL is authoritative; Paper entities, dialogs and caches are projections that may be recreated. Every state change enters a domain service and one `TransactionalStore` transaction. Commands and listeners validate input and delegate; they do not contain gameplay transactions.

## Modules

| Module | Responsibility |
|---|---|
| `frontier-domain` | Immutable identifiers, value objects, policies and domain rules; no Paper or SQL |
| `frontier-api` | Stable runtime ports: transactions, scheduling, UI, health, recovery, outbox and diagnostics |
| `frontier-city` | Settlement, district, building and population services |
| `frontier-influence` | Claim ownership, influence calculation and hot ownership cache |
| `frontier-economy` | Wallets, treasury, market, production, contracts and logistics |
| `frontier-warfare` | Campaign lifecycle, authorization and result application |
| `frontier-repair` | Damage journal, repair leases, material reservation and reconstruction |
| `frontier-npc` | Database-backed NPC and caravan presentation |
| `frontier-world` | Roads, seasons, weather, decay, events and civilization/endgame simulation |
| `frontier-ui-paper` | Paper Dialog rendering and guarded actions |
| `frontier-persistence-postgres` | JDBC repositories, transactions, Flyway and PostgreSQL locking |
| `frontier-observability` | Health, metrics and diagnostic reports |
| `frontier-bootstrap` | Paper wiring, commands, listeners, schedulers and configuration |
| `frontier-testkit` | Deterministic clocks, fixtures and integration support |

Dependencies point inward: bootstrap/Paper and PostgreSQL adapters depend on service/domain contracts, never the reverse.

## Runtime flow

1. Bootstrap validates config, starts the pool and applies forward-only migrations.
2. Recovery reconciles expired leases, outbox work, transfers and prepared consumption.
3. Bounded schedulers claim work asynchronously; world mutations return through the Paper region/entity/global scheduler.
4. A service locks aggregates in a fixed order, validates authorization and idempotency, writes state/audit/outbox atomically, then commits.
5. Caches and visible NPCs update from committed state. A restart reconstructs both safely.

Settlement founding is a cross-resource saga rather than a database transaction pretending to own
a Paper inventory and block. PostgreSQL first records the expedition, accepted founders, selected
core and fee/material intent. The entity scheduler performs the inventory handoff, the region
scheduler places the bell, and a deterministic preallocated city UUID makes final database creation
idempotent. Login and minute recovery resume `MATERIALS_CLAIMED`, `MATERIALS_RESERVED`, and
`CORE_PLACED`; see [Settlement founding](SETTLEMENT_FOUNDING.md).

Claim events are a specialized read-only hot path: Paper adapters create a complete actor/action/
source/target context, `TerritoryActionPolicy` decides from the in-memory projection, and only an
authorized campaign break/explosion enters the transactional structural-damage journal. Actorless
propagation uses the same policy and cannot cross territory-owner boundaries.

Building corner selections are intentionally ephemeral Paper UI state. The listener only captures a tagged tool click; a bounded region task surveys the world and the city service performs preview or confirmation against transactional PostgreSQL authorization. Confirm never trusts the previous preview. Revalidation, unregister and two-mayor parcel transfer are database service operations with append-only validation/audit history.

Physical infrastructure uses a two-stage read/analyze pipeline. A corridor plan is bounded before scheduling; each touched chunk is captured on its own Paper region scheduler into immutable surface cells. Pure asynchronous path analysis then derives continuity, endpoint connection, width, slope, bridge/tunnel/gate evidence, quality, health, capacity, bounds and exact path segments. Accepted block-change listeners only enqueue coordinates into a bounded deduplicating tracker; an asynchronous transactional service intersects persisted segments and marks matching routes dirty.

## Integrity rules

- Monetary values and quantities are integer units; no floating-point accounting.
- Caller-supplied idempotency keys protect replayable writes.
- Work queues use leases and retry-safe lifecycle states.
- Damage/repair bridge world state with generation-linked authorization and two-phase prepared
  material consumption; see [Repair integrity](REPAIR_INTEGRITY.md).
- `SELECT ... FOR UPDATE` and advisory locks serialize contested aggregates.
- The transactional outbox separates durable decisions from Paper-side effects.
- Chunk scans and simulation cycles are bounded and measured by named scheduler metrics.
