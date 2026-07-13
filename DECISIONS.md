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
