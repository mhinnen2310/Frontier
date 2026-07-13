# Builder Guild

An operational Builder Guild turns paid repair orders into a settlement-managed queue. PostgreSQL owns the depot, teams, project settings, contributions, assist sessions and completion; a Paper block or Mannequin is never authoritative.

## Management

`/frontier guild overview` (or `queue`) reports tier, depot use, team slots, foreman, available Builders, worker shortage and every project's progress and blocked reasons. Mayors, architects, builder masters and treasurers may appoint a Builder with `foreman <worker>`, create `team <name> <foreman> <builder...>`, set `priority <repair> <priority>`, activate `emergency <repair>`, and resolve an inspected conflict with `resolve <conflict>`.

Tier derives from settlement level. Packaged defaults provide one team and 10,000 depot units per tier, with two plus tier Builders per team and a maximum tier of three. All limits are typed settings under `repairs.yml`; a Builder can belong to only one team.

Blocked reasons are explicit: waiting payment, unsafe zone, missing material, unresolved conflict or worker shortage. Emergency mode moves the project and its remaining tasks to the front without skipping material, conflict, hostile-player or transactional checks.

## Player contribution

- `deliver <repair> <amount>` removes the held block item and commits an idempotent contribution to the depot. A failed transaction returns the item. Depot stock is reserved before warehouse stock by current and future repairs.
- `boost <repair> <points>` adds bounded queue priority. The packaged maximum is 25 per action and 100 per player/project/UTC day; it never fabricates completed blocks.
- `inspect` reports the looked-at repair task, target data, state, conflict and blocked reason.
- `assist <repair>` starts a ten-minute controlled repair mode containing at most 64 eligible coordinates.

During controlled mode, an ordinary `BlockPlaceEvent` is accepted only at a listed coordinate when the replaced block still equals the expected damage, the new block exactly equals the repair target, claim protection already allowed placement and no hostile campaign participant is inside the repair safety radius. PostgreSQL then locks and revalidates the member, session, task and coordinate before idempotently recording completion. If that commit fails, the placed block is rolled back and the consumed item is returned. Manual completion releases one no-longer-needed database reservation, updates order progress/history and uses the normal completion lifecycle.

Expired assist sessions are closed during normal use and startup recovery. Team assignment, contributions, emergency activation and manual work are retained in Builder Guild and repair history.
