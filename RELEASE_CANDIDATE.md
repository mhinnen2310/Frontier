# Frontier 1.1.0-RC4

This artifact is the Release Train C checkpoint for the 60-sprint Master Remediation & Expansion Roadmap. It adds Master Sprints 11-15 to the immutable RC3 baseline and contains database migrations V1-V48. Source is compiled for Java 25 and the runtime gate uses Java 26 on Paper 26.2.

## Release gates

- No source `TODO`, `FIXME`, `XXX` or `HACK` markers remain.
- Default configuration is validated at startup; unsafe non-positive bounds, empty database identity, invalid multipliers, impossible breach budgets and excessive pools fail closed.
- Full unit, PostgreSQL integration, concurrency, exploit, documentation, security and build verification passes.
- A separate empty PostgreSQL database migrates through V48 and passes the complete normal-player journey.
- A database created by the published RC3 JAR at V43 upgrades through V48 without manual SQL and passes the complete regression suite.
- The final shaded JAR starts on Paper 26.2 under Java 26, enables Frontier without plugin errors and has a committed SHA-256 checksum.

## Deployment

Follow `docs/UPGRADE.md`. Back up the database and world together before installing. This is an RC: stage it first, then report subjective balance, wording, sound and real-player network observations with the exact version and reproduction steps.

## Recorded evidence

On 2026-07-14 the full build, PostgreSQL integration suite, V48 clean-database journey, published RC3 V43-to-V48 upgrade and Java 26/Paper 26.2 startup gate passed. The exact committed checksum in `dist/SHA256SUMS.txt` identifies the deployable JAR. RC1 performance and 50/100/250/500-player scale measurements remain in `PERFORMANCE.md` and `SCALE_TEST.md`; they are historical records and are not relabeled as RC4 measurements.
