# Production decisions

The design explicitly leaves these choices open. The initial release uses conservative, reversible defaults:

| Decision | Release default | Reason |
| --- | --- | --- |
| Currency | Internal audited accounts; no Vault dependency | Keeps treasury transactions authoritative |
| Worker navigation | Waypoint navigator behind `Navigator` | Avoids coupling task ownership to a hidden mob |
| Perishable goods | Disabled | Prevents early micromanagement; batch model remains available |
| Campaign timing | 24-hour preparation, 14-day maximum | Matches Chapter 4 configuration baseline |
| Offline damage | 10% structural multiplier | Sabotage remains possible without offline wiping |
| Private insurance | Disabled | Automatic repair remains limited to registered public structures |
| Vanilla automation | Unrestricted | Restrictions need playtest evidence, not guesses |
| Folia | Architecture-compatible schedulers; Paper is the certified target | Avoids an unsupported compatibility claim |
| Paper dependency | `26.2.build.60-beta` | Latest official 26.2 beta at project bootstrap (2026-07-12) |

## Post-1.0 remediation decisions

| Sprint | Decision | Reason |
| --- | --- | --- |
| 1 | Money commands use integer cents, matching every persisted `*_minor` field. | Avoids floating-point ambiguity and keeps commands identical to audited ledger values. |
| 1 | Player wallets start at zero; repeatable daily Harbor jobs are the non-admin currency entry point. | Currency enters through visible gameplay and a bounded daily source instead of unexplained login grants. |
| 1 | Frontier Harbor has a 250,000-cent daily starter-job budget, low guaranteed buy prices and high limited sell prices. | It guarantees liquidity without outcompeting player settlements; its daily reset is an explicit controlled source/sink. |
| 1 | Player settlements receive 10,000 cents, 64 wheat and 16 bread once at founding. | This is a finite bootstrap buffer: it prevents immediate collapse but still requires Harbor work, taxes, production and trade. |
| Release | Published 1.0.0 remains immutable; remediation checkpoints are development builds and the final audited artifact receives a new release-candidate version. | Replacing a public artifact under the same version would break reproducibility and checksum trust. |
| 2 | Claim authorization is served from one atomically replaced PostgreSQL projection and never queries the database on a Paper event thread. | Interaction events must be decided synchronously without blocking a region/main thread. |
| 2 | Citizens may build, break, use containers/interactions/entities/buckets/crops/vehicles; recruits may only interact; architects, builder masters and mayors also control automation, hanging entities, fire and redstone. | This gives residents usable claims while reserving high-impact automation controls for trusted roles. Explicit `PROTECTION_*` overrides take precedence. |
| 2 | Active campaign attackers may only pass the generic protection policy for block breaking; the existing breach service still journals and authorizes the actual mutation. | Campaign status must not become a general container, placement or redstone bypass. |
| 2 | Automatic inventory, fire and redstone propagation may not cross claim-owner boundaries. | These are the actorless exploit paths; same-settlement automation remains available. |
| 3 | Structural damage uses a two-phase database/world protocol: breach capacity and an `AUTHORIZED` journal are committed first, then the region-thread mutation is confirmed as `APPLIED`. | PostgreSQL and Minecraft chunks cannot share one transaction; an explicit recoverable intermediate state closes the phantom-journal crash gap. |
| 3 | A repaired coordinate reuses its durable journal identity but increments `generation` and creates a new breach spend. | The positional uniqueness invariant still prevents duplicate rows, while a legitimate re-break is no longer free or invisible. |
| 3 | Prepared material consumption is retained across worker/entity loss and can be rebound; release reuses the same per-task consumption row. | Material is only consumed on confirmed placement, so restart/disconnect cannot duplicate or lose it. |
| 3 | Five failed task attempts move work to `REVIEW_REQUIRED`; completed orders archive after 24 hours. | Infinite hot-loop retries are unsafe, while a delayed archive preserves an operational inspection window. |
| 4 | Building registration is only successful after a bounded live-world survey passes the type validator and the database repeats authorization, claim, overlap and district checks in the write transaction. | World state cannot be locked together with PostgreSQL; bounded scanning plus transactional revalidation prevents stale registration decisions and oversized scans. |
| 4 | Registration records `PLANNED`, `UNDER_CONSTRUCTION`, `VALIDATING` and `ACTIVE` as durable history in one transaction; damage later moves active buildings through `DAMAGED`, `DISABLED` and `DESTROYED`. | The full lifecycle remains auditable without exposing partially committed buildings to gameplay. |
| 4 | District compatibility accepts an optional district type until Sprint 5 supplies persistent district identities and boundaries. | This preserves strict type rules now while allowing Sprint 5 to replace the temporary type hint with authoritative district records. |
