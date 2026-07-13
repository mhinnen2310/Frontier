# Frontier Performance Baseline

Baseline captured during remediation Sprint 19 on Paper 26.2 build 60, Java 25.0.2 and PostgreSQL 18. The run used a 32-second Java Flight Recorder `profile` recording, a real plugin startup/shutdown and the persistent smoke database upgraded through V31.

## Measured baseline

| Signal | Result |
| --- | ---: |
| PostgreSQL database size | 14,180,879 bytes |
| PostgreSQL cache hit ratio | 99.38% |
| Required due-queue indexes | 7/7 |
| JVM used heap at sample | 683,118,824 bytes |
| Async pool active / queued | 1 / 0 |
| Async average / maximum execution | 5.29 ms / 415.78 ms |
| Async average / maximum queue wait | 1.15 ms / 10.49 ms |
| Region/global profiled tasks | 121 |
| Region/global average / maximum task | 0.03 ms / 0.64 ms |

The 415.78 ms maximum async task occurred in the startup/recovery window and did not block a Paper region/global thread. Periodic named subsystem averages during the run were: database outbox 0.66 ms, logistics 0.90 ms, claim cache 1.14 ms, campaigns 1.25 ms, settlement simulation 1.41 ms, economy 1.59 ms, worker production 1.82 ms, repairs 1.93 ms, population 3.13 ms, world simulation 5.60 ms, events 7.04 ms and civilization 9.20 ms.

## Controls

- Chunk/building/infrastructure scans have fixed spatial caps and run only at explicit player actions.
- Influence uses an O(1) committed ownership cache and bounded dirty-settlement batches.
- Workers, repairs, production, economy, logistics, population, events and outbox use configured batch limits and `SKIP LOCKED` where work can be shared.
- V31 provides covering due-queue indexes for tribute, loans, repairs, production, shipments, population and caravans, plus hot lookup indexes for workers, warehouses, events, campaigns and roads.
- `/frontier admin performance` reports current heap, scheduler queue/load/timings, named subsystem timings, database cache/tuple/session statistics and largest/dead-tuple tables.

This baseline is an idle-server engineering gate, not the multiplayer capacity claim. Synthetic 50/100/250/500-player workloads and acceptance thresholds are handled by Sprint 20.

## Release-candidate recheck

`1.1.0-RC1` was rechecked for 45 seconds on Paper 26.2/Java 25 against the retained 500-player scale database under Java Flight Recorder. The live security audit passed; the database was 18,699,967 bytes with a 99.99% cache-hit ratio. The four-thread scheduler had zero queued work at sampling, 14.11 ms average/300.70 ms maximum async execution and 3.11 ms average/59.58 ms maximum queue wait. Paper started, reported performance, wrote the JFR recording and stopped cleanly.
