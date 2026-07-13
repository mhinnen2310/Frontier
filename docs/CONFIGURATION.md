# Configuration

Frontier writes `plugins/TheFrontier/config.yml`. Restart after changes. Durations are positive whole seconds/hours/days; limits are positive integers; money is integer cents. Invalid critical values stop safe initialization rather than silently changing gameplay.

| Section | Keys and meaning |
|---|---|
| `database` | JDBC URL, username/password and Hikari maximum pool size |
| `campaigns` | Preparation/duration, offline structural multiplier, breach window/budget, declaration cost, lifecycle/objective cadence and per-cycle bound |
| `repairs` | Minimum city level, blocks/tick, unsafe radius, combat delay, container/private insurance policy, cycle/lease/archive timing and work bound |
| `economy` | Perishables/Vault feature flags; market cadence/bound; production and logistics cadence/bounds |
| `compatibility` | Folia certification flag (false) and optional vanilla automation restriction |
| `performance` | Async thread count, visible NPC cap and legacy influence cadence |
| `npcs` | Materialization cadence |
| `world-simulation` | Cadence and maximum cities per cycle |
| `civilization` | Cadence and maximum kingdoms per cycle |
| `influence` | Cadence, settlements per cycle, contested threshold and lead-cycle hysteresis |
| `settlements` | Simulation cadence and maximum settlements per cycle |
| `outbox` | Dispatch cadence and events per cycle |
| `security` | Per-player command rate and rolling window |
| `harbor` | Starter market/job refresh cadence |
| `protection` | Claim cache refresh cadence |
| `damage-recovery` | Reconciliation cadence and maximum records per cycle |

## Tuning order

Increase per-cycle bounds only after checking `/frontier admin performance`, database pool saturation and Paper tick time. Prefer shorter queues over very frequent full scans. `async-threads` should remain below available CPU capacity and `database.maximum-pool-size` must cover the async workers plus administration headroom. Changing economic prices/budgets affects balance and should be staged with a database snapshot.

Secrets may remain in this file only when filesystem permissions are restricted to the server account. Never commit production credentials. There is no supported hot reload.
