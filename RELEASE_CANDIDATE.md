# Frontier 1.1.0-RC1

This artifact is the post-1.0 remediation release candidate for Paper 26.2 and Java 25. It contains all 24 audited sprints and database migrations V1–V31.

## Release gates

- No source `TODO`, `FIXME`, `XXX` or `HACK` markers remain.
- Default configuration is validated at startup; unsafe non-positive bounds, empty database identity, invalid multipliers, impossible breach budgets and excessive pools fail closed.
- Full unit, PostgreSQL integration, concurrency, exploit, documentation, security and build verification passes.
- The clean-database final player journey and fresh-world Paper startup pass.
- The 50/100/250/500 synthetic scale matrix passes with financial, stock and idempotency invariants.
- The final shaded JAR starts on Paper 26.2, passes live security/performance diagnostics, stops cleanly and has a committed SHA-256 checksum.

## Deployment

Follow `docs/UPGRADE.md`. Back up the database and world together before installing. This is an RC: stage it first, then report subjective balance, wording, sound and real-player network observations with the exact version and reproduction steps.

## Recorded evidence

On 2026-07-13 the final full build, V31 clean-database journey, 500-player scale matrix and Paper security/performance/JFR gates passed. The exact committed checksum in `dist/SHA256SUMS.txt` identifies the deployable JAR.
