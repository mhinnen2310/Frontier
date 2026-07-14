# Settlement ambient life

Ambient life is a bounded visual projection of authoritative settlement state. It never owns money, inventory, market orders, repairs, events or population.

## Scenes and schedules

When an online player is within 128 blocks of an active settlement capital, the ambient cycle derives scenes from one PostgreSQL snapshot:

- citizens scale modestly with population;
- an active Market with open orders shows traders during the day and closes at night;
- an active Barracks shows a guard patrol by day and a larger night watch;
- an active Builder Guild plus queued/active repairs shows repair activity;
- food, housing and employment shortages appear visibly at Town Hall;
- announced/active settlement events appear at Town Hall.

At night, ordinary citizens reduce to one “heading home” scene, market scenes retire and guards increase. Authoritative workers continue to use their own durable travelling/working schedule.

## Lifecycle and authority

Flyway V53 stores one unique scene per settlement/type/slot. A Paper Mannequin is bound conditionally to that record, is invulnerable/silent/non-colliding, and may be removed or recreated without changing gameplay. Moving every observer away retires all active scenes; an invalid saved entity UUID is unbound before recreation. A second entity cannot bind to an occupied scene.

The configured total presentation budget includes both real worker projections and ambient scenes. Important shortage/event/repair/guard/market scenes are selected before decorative citizens. Packaged defaults permit 24 total presentations, of which no more than 20 can be workers and at most 6 citizens, 2 market scenes, 2 guards and 2 repair scenes.

## Announcements

Players within the same 128-block observation area receive local settlement messages for active Town Hall events, shortages and non-zero population trends. The same signature repeats no more than once every five minutes; a changed condition may be announced immediately. `settlement_ambient_state` retains current/peak scene counts and cycle totals for later diagnostics.
