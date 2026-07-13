# Operations

## Startup

Frontier validates configuration, opens the HikariCP pool, runs forward-only Flyway migrations, rebuilds hot caches, reconciles leases and prepared material consumption, registers entry points, and starts bounded supervisors. A database failure prevents state-changing gameplay and is reported by `/frontier admin health`.

## Shutdown

New writes are rejected, supervisors stop, queues checkpoint, leases become recoverable, executors drain, and the pool closes. Authoritative state is always PostgreSQL; visible entities and caches may be recreated.

## Backup

Use daily PostgreSQL base backups plus WAL archiving. Retain at least seven daily and four weekly restore points. Backups must include the database and the Minecraft world from a coordinated checkpoint.

## Upgrade

Back up first, stop Paper, replace the plugin JAR, and start Paper. Migrations are forward-only. Keep the prior application JAR until staging has verified that it can read the migrated schema.

## Recovery priorities

Run `/frontier admin health`, then `/frontier admin recover`. The coordinator reconciles outbox events, expired task/work-package leases, transfers, and prepared material consumption before resuming simulations.
