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
