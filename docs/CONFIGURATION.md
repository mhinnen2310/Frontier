# Configuration

Frontier uses one global file and versioned module files under `plugins/TheFrontier`:

```text
config.yml
modules/settlements.yml
modules/districts.yml
modules/buildings.yml
modules/influence.yml
modules/economy.yml
modules/infrastructure.yml
modules/caravans.yml
modules/warfare.yml
modules/repairs.yml
modules/population.yml
modules/kingdoms.yml
modules/waypoints.yml
modules/cartography.yml
modules/map-walls.yml
modules/web.yml
modules/history.yml
modules/world-simulation.yml
messages/
```

Every file has `config-version: 1`; every module has `enabled`. Values are loaded once into immutable typed records, validated before database startup and passed to services/supervisors. Gameplay code does not perform scattered YAML lookups. Unknown keys produce a key-only warning—values and secrets are never logged.

## Global

`config.yml` contains the PostgreSQL JDBC URL, username/password, pool size (1–64), async thread count (1–64), and per-player command rate/window. Database, pool and thread changes require a server restart. Restrict the file to the server account and never commit production credentials.

## Modules

| File | Functional settings | Default |
|---|---|---|
| `settlements.yml` | Simulation/protection cadence; founding fee, founder minimum, attempt/lease lifetime, core/Harbor distances, materials and allowed world environments; mayor/settlement inactivity and disband confirmation timing | enabled |
| `districts.yml` | Integrity activation thresholds; diminishing contribution count/rate; adjacency range/count/rate; over-specialization threshold/penalty; effective cap; Industrial/Military cost and Commercial/Logistics capacity modifiers | enabled; 40 integrity, 50% diminishing to 3 buildings, 10% adjacency up to 2 within 16 blocks, 20% penalty beyond 2 same-type districts, 30% cap |
| `buildings.yml` | Physical validation/registration control; maximum width/height/depth/volume, minimum structural mass and floor/wall/roof coverage | enabled; 64 blocks per axis, 32,768 blocks total, 8 structural blocks, 60% floor, 50% walls, 60% roof |
| `influence.yml` | Cadence/bound, contested threshold and lead hysteresis | enabled |
| `economy.yml` | Market/production/logistics cadence; Harbor budgets, source/player caps, low-tier stock, jobs and limited daily orders | enabled |
| `infrastructure.yml` | Infrastructure subsystem control | enabled |
| `caravans.yml` | Caravan subsystem control | enabled |
| `warfare.yml` | Campaign timing/cost, breach budget and objective lifecycle | enabled |
| `repairs.yml` | Task cycle/lease/archive/bound, unsafe radius and damage recovery | enabled |
| `population.yml` | NPC materialization cadence and visible-per-settlement cap | enabled |
| `kingdoms.yml` | Civilization cadence and kingdom bound | enabled |
| `waypoints.yml` | Reserved typed control for Train G | disabled until implemented |
| `cartography.yml` | Reserved typed control for Train G | disabled until implemented |
| `map-walls.yml` | Reserved typed control for Train H | disabled until implemented |
| `web.yml` | Local bind address, port and public URL | disabled until implemented |
| `history.yml` | Transactional outbox cadence/bound | enabled |
| `world-simulation.yml` | World cycle cadence and city bound | enabled |

All durations and batch limits must be positive. Founding requires positive material counts and distance bounds, at most 100 founders, and a Harbor exclusion radius no smaller than the ordinary core distance; allowed environments are `NORMAL`, `NETHER`, `THE_END`, or `CUSTOM`. Membership defaults are 7 days of mayor inactivity, 30 days of settlement inactivity, a 30-second disband cooldown and a 10-minute request lifetime. Settlement inactivity cannot be shorter than mayor inactivity, and the request lifetime must exceed the cooldown. Web ports are 1–65535, visible NPCs are capped at 500, repair unsafe radius at 1024 blocks, breach base cannot exceed its maximum, and database/async pools cannot exceed 64. Enabled dependency chains are validated; for example repairs require warfare, warfare requires influence, and influence requires settlements.

Harbor's commodity allowlist is additionally bounded in code to bread, wheat, oak logs, cobblestone and iron ingots. Config may choose a subset but cannot introduce high-tier goods. Starter-job totals cannot exceed the per-player daily cap, daily currency creation cannot exceed the Harbor budget, and overlapping buy/sell prices may not permit arbitrage.

District balance percentages are constrained to 1–100, contribution/adjacency/same-type counts to 1–20, integrity to 1–100 and adjacency distance to 1–256 blocks. Changing balance settings requires a server restart; startup synchronizes the validated immutable policy to the authoritative database projection.

Building dimensions and volume must be positive and are enforced before any live-world iteration. Structural minimums must be positive; floor, wall and roof coverage are percentages from 1–100. Under `types`, every one of the ten building types has minimum dimensions, enclosure/entrance/road switches and functional block-group counts. Type dimensions cannot exceed the global scan bounds or volume; counts are non-negative, bounded by maximum volume and at least one functional group per type must remain positive. `registration.selection-timeout-seconds` defaults to 300 and is capped at 3,600; `registration.transfer-proposal-hours` defaults to 24 and is capped at 168. These settings require a server restart because the UI, Paper survey adapter and pure validator capture one immutable policy at startup.

## Administration

- `/frontier admin config validate` rereads every file and reports clear validation errors.
- `/frontier admin config show <global|module>` displays effective disk keys with passwords/tokens/secrets redacted.
- `/frontier admin config reload` validates and classifies differences as `LIVE`, `MODULE_RESTART` or `SERVER_RESTART`. Running values are deliberately unchanged until the reported restart, preventing half-reloaded supervisors.

Setting an implemented module to `enabled: false` prevents its commands, listeners and periodic supervisors from operating. Disable dependent modules too; invalid dependency combinations fail startup with a precise explanation. Legacy RC1 `config.yml` files migrate once to version 1 module files while retaining every formerly consumed value and removing seven nonfunctional legacy keys.
