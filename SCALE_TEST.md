# Frontier Multiplayer Scale Test

Sprint 20 validates four reproducible synthetic tiers against PostgreSQL 18 and then boots the real Paper 26.2 plugin against the retained 500-player dataset.

## Workload

Each player owns one settlement, account, capital claim, warehouse/stock, market order, shipment/caravan and population simulation row plus two workers. Every two settlements share an active campaign with one repair order and four ready repair tasks. Dynamic events are present throughout the dataset.

The concurrent phase uses 16 clients and the real gateway implementations for campaign policy, workers, economy orders, repairs, caravans, dynamic events, diagnostics, rankings and world-region reads. World simulation processes every settlement before the read workload. Acceptance requires p95 below 1,000 ms, each workload below 60 seconds and zero negative account/stock or duplicate shipment idempotency state.

| Players | Workers | Wars | Repair tasks | Caravans | Operations | Seed | World cycle | Workload | p95 | Maximum |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 | 100 | 25 | 100 | 50 | 400 | 66 ms | 188 ms | 218 ms | 24.68 ms | 48.05 ms |
| 100 | 200 | 50 | 200 | 100 | 800 | 77 ms | 291 ms | 232 ms | 15.79 ms | 38.61 ms |
| 250 | 500 | 125 | 500 | 250 | 2,000 | 124 ms | 777 ms | 660 ms | 20.11 ms | 48.68 ms |
| 500 | 1,000 | 250 | 1,000 | 500 | 4,000 | 232 ms | 1,981 ms | 2,066 ms | 40.66 ms | 81.02 ms |

## Paper gate at 500 players

The real plugin ran for 38 seconds under Java Flight Recorder against the retained 500-player database. Startup, all supervisors, `/frontier admin security`, `/frontier admin performance` and shutdown passed.

- PostgreSQL cache hit: 99.99%; database size: 17,981,967 bytes.
- Scheduler queue at sample: 0; maximum observed queue wait: 78.73 ms.
- Region/global callbacks: 0.04 ms average, 3.17 ms maximum.
- JVM used heap at sample: 370,132,648 bytes.
- Named database cycles: repairs 20.52 ms average, population 21.48 ms, campaigns 21.92 ms, events 38.09 ms, world 18.59 ms; all remained asynchronous.
- Live security audit: pass.

Run again with `FRONTIER_SCALE_DATABASE_URL=... ./gradlew :frontier-persistence-postgres:scaleTest`.

This is a deterministic server/database capacity test, not 500 authenticated network clients rendering chunks. It covers the plugin workload requested here—wars, repairs, economy, workers/NPC projections, caravans, events and database concurrency—while avoiding a false claim about network, vanilla chunk-generation or host bandwidth capacity.
