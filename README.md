# The Frontier

The Frontier is a persistent civilization simulation for Minecraft Paper 26.2. Settlements grow through population, infrastructure and trade; borders respond to influence; campaigns run continuously with bounded structural damage; and registered war damage is reconstructed visibly by paid workers.

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

Copy the JAR to the server `plugins` folder, start Paper once, and edit `plugins/TheFrontier/config.yml` if the database is not on localhost. The plugin runs migrations automatically.

## Player/admin entry point

Use `/frontier` for the command fallback interface. Paper Dialog screens are opened for supported management flows; commands remain available for automation and recovery.

The original design documents are retained in the repository root. See [SPRINTS.md](SPRINTS.md), [DECISIONS.md](DECISIONS.md), [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md), [docs/VERIFICATION.md](docs/VERIFICATION.md), and [docs/OPERATIONS.md](docs/OPERATIONS.md) for scope, decisions, verification and operations.
