# Administration

Install Java 25, Paper 26.2 and PostgreSQL 16+, create an empty database/user, copy the shaded JAR to `plugins`, and configure the database before public access. Frontier applies Flyway migrations at startup and refuses unsafe state-changing operation when persistence is unhealthy.

## Commands

All commands below require `frontier.admin`.

| Command | Result |
|---|---|
| `/frontier admin build` | Packaged version, Git revision/time, Java, Paper target, live schema and module states |
| `/frontier admin health` | Database, outbox, scheduler and simulation health |
| `/frontier admin recover` | Reconcile expired leases, transfers, prepared consumption and outbox work |
| `/frontier admin inspect <settlement\|influence\|road\|repair\|campaign\|worker\|economy> <uuid>` | Read-only aggregate inspector |
| `/frontier admin influence <uuid>` | Influence detail/viewer |
| `/frontier admin road <uuid>` | Road graph/health viewer |
| `/frontier admin repair <uuid>` | Repair lifecycle/conflict viewer |
| `/frontier admin campaign <uuid>` | Campaign/objective/result viewer |
| `/frontier admin worker <uuid>` | Worker/employment viewer |
| `/frontier admin economy <uuid>` | Wallet/treasury/company viewer |
| `/frontier admin heatmap` | Influence and activity heatmaps |
| `/frontier admin chunk [world-uuid chunk-x chunk-z]` | Chunk ownership and protection explanation |
| `/frontier admin metrics` | Live metrics snapshot |
| `/frontier admin performance` | Named scheduler latency, queue and database profile |
| `/frontier admin security` | Runtime security constraints and configuration audit; must report `PASS` |

Inspectors are read-only. Recovery is idempotent and should be preferred to database edits. Never repair gameplay by changing SQL manually; preserve the audit trail and domain invariants.

## Startup troubleshooting

- `Ambiguous plugin name` means multiple Frontier JARs are present. Stop Paper and retain exactly one current JAR.
- `Connection ... refused` means PostgreSQL is stopped, listening elsewhere or blocked. Start PostgreSQL and verify the configured host/port/database before restarting Paper.
- `plugin is disabled` after either failure is a consequence, not a separate command bug. Correct startup and restart Paper; do not use `/reload`.
- `Failed to read console input` on Windows comes from launching Paper without a valid stdin stream. Use a normal terminal or a process manager that keeps stdin attached; it is independent from Frontier persistence.

## Routine gates

- Monitor Paper tick time, Frontier named scheduler maximums, Hikari saturation, outbox backlog and database cache hit rate.
- Back up PostgreSQL and the Minecraft world from a coordinated checkpoint.
- Run `./gradlew test release` and a real Paper startup before deploying a locally built JAR.
- Follow [UPGRADE.md](UPGRADE.md); retain the backup and previous JAR until staging passes.

See [SECURITY_AUDIT.md](../SECURITY_AUDIT.md), [PERFORMANCE.md](../PERFORMANCE.md), [SCALE_TEST.md](../SCALE_TEST.md), and [OPERATIONS.md](OPERATIONS.md) for audit evidence and thresholds.
