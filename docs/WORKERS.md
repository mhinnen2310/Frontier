# Workers

Workers are authoritative database records. A visible Minecraft entity is only a replaceable presentation and never owns inventory, wages or task completion.

## Scheduling and visibility

Flyway V50 gives each worker at most one active activity. The bounded scheduler derives priority work shifts, warehouse waits, Builder Guild exits, repair visits, farm visits and guard-post duty from the worker's existing assignment and state. Activities move through `QUEUED`, `TRAVELLING`, `WORKING` and `COMPLETED`; path and simulation state are recorded separately so a failed presentation can be retried safely.

When any online player is within 128 blocks of a settlement capital, eligible workers materialize as Mannequins and walk in bounded terrain-following steps. Builders assigned to a guild begin there and leave toward the capital; other assigned professions travel from the settlement toward their workplace. Arrival is committed transactionally before the short work phase completes. When no player is nearby, the same activity completes as an abstract database simulation and no entity is spawned. Moving away retires the entity, while an expired lease is reset by startup recovery and safely retried.

`population.yml` caps visible workers per settlement, activities per cycle, lease duration, path steps and step cadence. These bounds prevent hundreds of permanent entities or unbounded path work.

## Professions and attributes

The supported professions are Builder, Farmer, Miner, Courier, Guard, Clerk, Merchant and Engineer. Every worker profile reports profession, skill, morale, daily wage in cents, assigned building, assigned district, status, current task and experience. Employment, housing, age and efficiency remain simulation details visible in reports.

Mayors, architects and builder masters can assign or clear an operational building with `/frontier workers assign <worker> <building|none>`. Mayors and treasurers set the daily wage with `/frontier workers wage <worker> <daily-cents>`. The worker and building must belong to the same settlement; every successful change is recorded in `worker_history`.

## States

`IDLE`, `TRAVELLING`, `WORKING`, `WAITING_MATERIALS`, `WAITING_PAYMENT`, `RESTING`, `INJURED`, `FLEEING` and `UNAVAILABLE` are the complete state vocabulary. Repairs lease only an idle Builder and move it to travelling until the package completes or expires. Unpaid idle workers wait for payment; retired workers become unavailable. The activity scheduler uses the same vocabulary and exposes its activity UUID as the current task when no repair work package is active.

Flyway V49 maps older GUIDE/PORTER/LUMBERJACK-style professions, internal repair phases and PAUSED/RETIRED states deterministically before adding database checks. V50 adds the scheduler, activity lease and recovery fields. No manual SQL is required.
