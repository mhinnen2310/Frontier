# Developer API

Frontier currently exposes Java service-provider interfaces through `frontier-api`; it does not promise a Bukkit service registry or binary compatibility for internal repositories. Compile extensions against the matching Frontier version and keep domain changes inside a transaction.

| Type | Contract |
|---|---|
| `TransactionalStore` | Runs `SqlWork<T>` atomically on one JDBC connection |
| `SchedulerFacade` | Region/entity/global/later scheduling plus async and named async work |
| `FrontierUi` | Opens supported Paper screens for a player |
| `DialogScreenCatalog` | Canonical 14-screen titles and guarded command actions |
| `RecoveryCoordinator` | Idempotent startup/admin reconciliation |
| `OutboxDispatcher` | Starts/stops committed event delivery |
| `HealthStatus` | Immutable health result and component details |
| `AdminDiagnostics` | Read-only diagnostics and audit reports |

## Extension rules

1. Put pure values and policies in `frontier-domain`; they must not import Bukkit or JDBC.
2. Put orchestration in a service module. Accept actor, aggregate and idempotency identifiers explicitly.
3. Use `TransactionalStore`; lock aggregates in the established order and write audit/outbox records in the same transaction.
4. Keep commands/listeners as validation/adaptation only. Never mutate gameplay state directly from Paper callbacks.
5. Perform database/block scans asynchronously and bounded; return entity/world mutations through `SchedulerFacade`.
6. Add forward-only SQL to the next numbered migration and append it to `index.txt`; never edit an applied migration.
7. Add unit, PostgreSQL integration, concurrency/retry and Paper startup coverage proportional to the change.

Source packages under `nl.frontier.*` are readable implementation examples, but only the types in `frontier-api` are intended adapter boundaries.
