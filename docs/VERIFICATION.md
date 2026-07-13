# Release verification

## Automated gates

- Java 25 compilation with all warnings treated as errors and Java 26 runtime startup.
- Spotless formatting and the complete Gradle unit-test suite.
- Fresh PostgreSQL migration through V32 plus repository integration coverage for every gameplay vertical.
- Concurrent matching of the final reserved warehouse unit, proving that only one physical shipment can be created.
- Escrow/idempotency checks, partial matching/cancellation, route delivery, damage authorization, repair reservation/commit, research, treaty, wonder and mega-project completion.
- Action-token replay/expiry checks and command-rate-limit refill checks.
- Shaded-JAR construction through the `releaseJar` gate.

## Recovery and mandatory scenario coverage

The Blueprint 27.1 failure cases are covered by the same durable state transitions: damage is journaled before block mutation; repair material uses PREPARED/COMMITTED consumption; expired worker packages are recovered without losing the prepared task; shipments retain database cargo when a presentation entity or route disappears; campaign damage revalidates the active cached policy; Dialog actions are single-use; influence and disband writes are transactional; order matching locks orders/reservations; and supervisors fail a cycle without mutating state when PostgreSQL is unavailable.

The release-train server gate starts the built JAR on the pinned Paper `26.2.build.60-beta`, validates an in-place V31-to-V32 upgrade, reaches Paper's `Done` state without Frontier errors, then terminates the isolated test process. A separate empty database must also migrate through V32 and pass `finalQaTest`.
