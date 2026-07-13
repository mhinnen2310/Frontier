# Workers

Workers are authoritative database records. A visible Minecraft entity is only a replaceable presentation and never owns inventory, wages or task completion.

## Professions and attributes

The supported professions are Builder, Farmer, Miner, Courier, Guard, Clerk, Merchant and Engineer. Every worker profile reports profession, skill, morale, daily wage in cents, assigned building, assigned district, status, current task and experience. Employment, housing, age and efficiency remain simulation details visible in reports.

Mayors, architects and builder masters can assign or clear an operational building with `/frontier workers assign <worker> <building|none>`. Mayors and treasurers set the daily wage with `/frontier workers wage <worker> <daily-cents>`. The worker and building must belong to the same settlement; every successful change is recorded in `worker_history`.

## States

`IDLE`, `TRAVELLING`, `WORKING`, `WAITING_MATERIALS`, `WAITING_PAYMENT`, `RESTING`, `INJURED`, `FLEEING` and `UNAVAILABLE` are the complete state vocabulary. Repairs lease only an idle Builder and move it to travelling until the package completes or expires. Unpaid idle workers wait for payment; retired workers become unavailable. Sprint 17 adds the bounded scheduler and visible activity transitions without changing this persisted vocabulary.

Flyway V49 maps older GUIDE/PORTER/LUMBERJACK-style professions, internal repair phases and PAUSED/RETIRED states deterministically before adding database checks. No manual SQL is required.
