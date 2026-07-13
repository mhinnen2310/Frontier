# Settlement founding integrity

Creating a founding expedition does not create a settlement. The durable flow is:

```text
RECRUITING
  -> LOCATION_SELECTED
  -> FEE_RESERVED
  -> MATERIALS_CLAIMED
  -> MATERIALS_RESERVED
  -> CORE_PLACED
  -> COMPLETED
```

`CANCELLED`, `EXPIRED`, and `REVIEW_REQUIRED` are terminal or operator-visible outcomes. The leader
is accepted when the expedition is created. Invited founders must explicitly accept, and the
configured minimum is rechecked inside the fee-reservation transaction. The default remains one so
a new solo server is playable.

## Player flow

1. Earn the configured fee through Frontier Harbor and collect the configured blocks.
2. Run `/frontier city create <name> [| <charter>]`.
3. If additional founders are required, use `/frontier city expedition invite <id> <player>`; each
   invited player uses `/frontier city expedition accept <id>`.
4. Stand on solid, non-ocean terrain with an empty block at the intended core.
5. Run `/frontier city expedition found <id>`.

World-border height, allowed environment, existing claim, core spacing, active expedition spacing,
and the larger Frontier Harbor exclusion radius are checked before money moves and again where a
concurrent database change matters. A successful expedition places a bell, creates a Camp, writes
the charter and ordered founder records, makes the leader mayor, and adds other founders as citizens.

## Crash and replay behavior

The database, inventory, and Minecraft world cannot share one atomic transaction. Frontier uses a
saga with a preallocated city UUID:

- Only one caller can change `FEE_RESERVED` to `MATERIALS_CLAIMED`, preventing duplicate inventory
  consumption.
- Logout or restart in `MATERIALS_CLAIMED` resumes on the leader's next join.
- `MATERIALS_RESERVED` with no bell is retried on the region scheduler; an existing bell is treated
  as the idempotent result of an interrupted placement.
- `CORE_PLACED` reuses the expedition's deterministic city UUID. A crash after city insertion but
  before expedition completion therefore reloads the same city instead of creating another.
- A changed core block moves unattended recovery to `REVIEW_REQUIRED`; it is never overwritten.
- Cancellation before material claim refunds the exact fee through the audited player ledger.
  Expired pre-material expeditions are refunded by bounded lifecycle recovery.

Core coordinates are unique both among active expeditions and completed settlement cores. The
integration suite recreates the service between material reservation and placement, repeats final
completion, and proves that one expedition yields one city, one core, one fee, and the confirmed
founder set.
