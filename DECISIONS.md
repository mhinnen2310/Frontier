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
| 5 | Districts are non-overlapping, remain wholly inside controlled settlement claims and are referenced by UUID from buildings. | A stable identity prevents rename/type strings from silently reassigning buildings; spatial ownership is rechecked in every mutation transaction. |
| 5 | District budgets are audited allocations capped by the live settlement treasury, not separate money accounts. | This prevents double-counted currency while still allowing priorities and reports to reserve political spending intent. |
| 5 | District history deliberately survives district deletion; operational worker/storage/budget rows cascade and building references are cleared. | Administrative history must remain auditable, while deleted districts must not leave active gameplay ownership behind. |
| 5 | District bonuses are capped at 20% and applied through the database-backed district projection to production, housing growth, maintenance, defense cost, trade queue priority, worker efficiency and repair priority. | Small bounded modifiers make specialization meaningful without allowing stacked multiplicative runaway; one building belongs to at most one district. |
| 6 | Founding uses a database fee reservation plus compensating material refund because a Minecraft inventory and PostgreSQL cannot share one atomic transaction. | The explicit `RESERVED`, `COMPLETED`, and `REFUNDED` saga closes fee/material loss on validation or database failure and is recoverable after timeout. |
| 6 | The default minimum is one accepted founder, while the schema and validation retain a configurable minimum-founder invariant. | A fresh or low-population server must remain playable without admin intervention; multiplayer servers can raise the invariant without a schema change. |
| 6 | A core requires solid non-ocean terrain inside the border and 128 blocks of separation; founding consumes a bell which becomes the physical core. | The checks prevent overlapping capitals and invalid/off-world settlements while giving the lifecycle a visible in-world anchor. |
| 6 | Abandoned/disbanded settlements become ruins for 30 days; claims are archived and only still-wilderness claims can be restored. | Recovery is possible without stealing territory acquired by another settlement during abandonment. |
| 6 | Merge requires both mayors, no active campaign, and no kingdom membership; the target keeps its identity and active warehouse while source free stock and capacity transfer. | This avoids ambiguous diplomacy/war ownership and respects the one-active-warehouse invariant while preserving reserved stock references. |
| 7 | Physical edge scans are capped at 256 blocks and require 85% connected recognized surface, two-block minimum width, quality 40 and maximum per-sample slope 1.5. | The cap bounds region-thread work; the thresholds tolerate decorative intersections without accepting teleport links or unusable paths. |
| 7 | Edge health and capacity are derived from the physical survey, while importance is a mayor/architect planning value from 0-100 and traffic is incremented by actual routed shipments. | Players cannot claim arbitrary throughput; operational and political values remain separate and auditable. |
| 7 | Paper is the certified physical-survey target; the scheduler boundary is retained but cross-region Folia route scanning is not claimed. | A multi-chunk synchronous read is valid on certified Paper but would need per-region aggregation before claiming Folia support. |
