# The Frontier

The Frontier is a persistent civilization simulation for Minecraft Paper 26.2. Settlements grow through population, infrastructure and trade; borders respond to influence; campaigns run continuously with bounded structural damage; and registered war damage is reconstructed visibly by paid workers.

Frontier is currently undergoing the 60-sprint Master Remediation & Expansion Roadmap. Release Trains A–C cover the audited foundation through physical infrastructure failure/repair. Train D is underway: the complete worker model, visible/abstract scheduling and Builder Guild gameplay are finished, while expanded population and broader living-world behavior follow next. The Atlas, always-attackable cargo/crime, map walls, local web map and unified history expansion are not yet complete. See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for the evidence-backed status; no presence-only feature is labeled complete.

## Runtime requirements

- Paper `26.2` (compiled against `26.2.build.60-beta`)
- Java 25 or newer
- PostgreSQL 16+

## Build

```powershell
.\gradlew.bat clean releaseJar
```

The deployable plugin is written to `frontier-bootstrap/build/libs/TheFrontier-<version>.jar`.

## Local database

```powershell
docker compose up -d postgres
```

Copy exactly one Frontier JAR to the server `plugins` folder, start Paper once, and edit `plugins/TheFrontier/config.yml` if the database is not on localhost. PostgreSQL must be running before Paper starts. The plugin runs migrations automatically and fails closed when the database is unreachable.

## Player/admin entry point

Use `/frontier` for the command fallback interface. Paper Dialog screens are opened for supported management flows; commands remain available for automation and recovery.

The original design documents are retained in the repository root. The maintained documentation starts at [docs/README.md](docs/README.md), including gameplay, every command and Dialog screen, administration, permissions, architecture, API, database, configuration, upgrades, verification and operations.
