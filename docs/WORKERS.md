# Workers

Workers are authoritative database records. A visible Minecraft entity is only a replaceable presentation and never owns inventory, wages or task completion.

## Scheduling and visibility

Flyway V50 gives each worker at most one active activity. The bounded scheduler derives priority work shifts, warehouse waits, Builder Guild exits, repair visits, farm visits and guard-post duty from the worker's existing assignment and state. Activities move through `QUEUED`, `TRAVELLING`, `WORKING` and `COMPLETED`; path and simulation state are recorded separately so a failed presentation can be retried safely.

When any online player is within 128 blocks of a settlement capital, eligible workers materialize as Mannequins and walk in bounded terrain-following steps. Builders assigned to a guild begin there and leave toward the capital; other assigned professions travel from the settlement toward their workplace. Arrival is committed transactionally before the short work phase completes. When no player is nearby, the same activity completes as an abstract database simulation and no entity is spawned. Moving away retires the entity, while an expired lease is reset by startup recovery and safely retried.

`population.yml` caps visible workers per settlement, activities per cycle, lease duration, path steps and step cadence. It also controls daily population growth/decline, settlement and food-shortage grace periods, and the collapse floor. These bounds prevent hundreds of permanent entities, unbounded path work or sudden demographic collapse.

## Population, housing and migration

Population is recalculated at most once per UTC day in one settlement transaction. Capacity comes from the core allowance, district effects and validated `ACTIVE` Housing buildings; damaged, disabled and merely planned structures do not count. Stored wheat/bread determines food security, non-retired worker records determine employment, military buildings and hostile campaigns determine safety, and the settlement supplies prosperity.

Healthy housing, food, employment, safety and prosperity permit bounded births and immigration. Food shortage, unemployment, low safety or low prosperity cause bounded deaths and emigration after protection expires. The packaged policy allows at most five growth and three decline per day, protects a new settlement for three days, gives a newly observed food shortage two days, and retains one resident as collapse protection.

`/frontier population` reports the signed trend and exact causes, for example `+ Housing available`, `+ Food surplus`, `+ Employment` or `- Border conflict`. Every cycle keeps the inputs, grace state, separate birth/death/migration values and reasons in `population_cycle_history`.

## Professions and attributes

The supported professions are Builder, Farmer, Miner, Courier, Guard, Clerk, Merchant and Engineer. Every worker profile reports profession, skill, morale, daily wage in cents, assigned building, assigned district, status, current task and experience. Employment, housing, age and efficiency remain simulation details visible in reports.

Mayors, architects and builder masters can assign or clear an operational building with `/frontier workers assign <worker> <building|none>`. Mayors and treasurers set the daily wage with `/frontier workers wage <worker> <daily-cents>`. The worker and building must belong to the same settlement; every successful change is recorded in `worker_history`.

## States

`IDLE`, `TRAVELLING`, `WORKING`, `WAITING_MATERIALS`, `WAITING_PAYMENT`, `RESTING`, `INJURED`, `FLEEING` and `UNAVAILABLE` are the complete state vocabulary. Repairs lease only an idle Builder and move it to travelling until the package completes or expires. Unpaid idle workers wait for payment; retired workers become unavailable. The activity scheduler uses the same vocabulary and exposes its activity UUID as the current task when no repair work package is active.

Flyway V49 maps older GUIDE/PORTER/LUMBERJACK-style professions, internal repair phases and PAUSED/RETIRED states deterministically before adding database checks. V50 adds the scheduler, activity lease and recovery fields. No manual SQL is required.

Ambient residents, guards, market traders and visible repair scenes are not workers and never receive jobs, wages or inventory. They share the configured per-settlement presentation ceiling with worker Mannequins and retire when the settlement has no nearby observer. See [AMBIENT_LIFE.md](AMBIENT_LIFE.md).
