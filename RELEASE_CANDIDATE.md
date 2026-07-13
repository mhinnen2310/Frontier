# Frontier 1.1.0-RC2

This artifact is the Release Train A checkpoint for the 60-sprint Master Remediation & Expansion Roadmap. It adds Master Sprints 1-5 to the RC1 baseline and contains database migrations V1-V32. Source is compiled for Java 25 and the runtime gate uses Java 26 on Paper 26.2.

## Release gates

- No source `TODO`, `FIXME`, `XXX` or `HACK` markers remain.
- Default configuration is validated at startup; unsafe non-positive bounds, empty database identity, invalid multipliers, impossible breach budgets and excessive pools fail closed.
- Full unit, PostgreSQL integration, concurrency, exploit, documentation, security and build verification passes.
- A separate empty PostgreSQL database migrates through V32 and passes the complete normal-player journey.
- The retained V31 database upgrades to V32 without manual SQL and passes the complete regression suite.
- The final shaded JAR starts on Paper 26.2 under Java 26, enables Frontier without plugin errors and has a committed SHA-256 checksum.

## Deployment

Follow `docs/UPGRADE.md`. Back up the database and world together before installing. This is an RC: stage it first, then report subjective balance, wording, sound and real-player network observations with the exact version and reproduction steps.

## Recorded evidence

On 2026-07-13 the full build, PostgreSQL integration suite, V32 clean-database journey, V31-to-V32 upgrade and Java 26/Paper 26.2 startup gate passed. The exact committed checksum in `dist/SHA256SUMS.txt` identifies the deployable JAR. RC1 performance and 50/100/250/500-player scale measurements remain in `PERFORMANCE.md` and `SCALE_TEST.md`; they are historical records and are not relabeled as RC2 measurements.
