# Physical infrastructure

Register two road nodes first, then connect them through `/frontier logistics connect <from> <to> <type> [importance]`. The origin node must belong to the acting settlement; the destination may belong to another settlement so trade routes can cross borders. The resulting edge is owned by the origin settlement.

## Validation

Frontier captures a configurable corridor around the endpoints and searches for a continuous four-direction path over recognized road surfaces. The path must touch both node coordinates when those exact surfaces exist. It then measures minimum width, average material quality, maximum slope, missing or destroyed spans, bridge support, tunnel enclosure and physical gate blocks. `BRIDGE`, `TUNNEL` and `GATE` registrations require their matching evidence. Health and capacity are derived from those observations; players only choose planning importance.

The packaged policy limits an edge to 256 blocks, a six-block corridor radius and 65,536 captured columns. Each chunk is read on its owning Paper region scheduler. Only immutable cells are passed to bounded asynchronous analysis; no live world object is accessed by the analyzer or database service.

## Dirty routes

Accepted placement, breaking, burning, fluid, explosion and piston events enqueue the changed coordinate. The queue is bounded and deduplicated. A scheduled transactional service intersects coordinates with exact `road_edge_segments` and marks matching edges `DIRTY` in `road_edges` and `dirty_road_edges`. Listeners never execute SQL or decide route gameplay.

The health supervisor leases dirty routes, captures a fresh per-chunk snapshot and runs the pure analyzer asynchronously. One transaction records the new physical health, bridge integrity, report and history, then resolves the route as `VALID`, `BLOCKED` or `DESTROYED`. Dirty, blocked and destroyed routes have zero operational capacity and are excluded from route planning. Traveling shipments using one are moved to `REROUTING`; their database cargo is retained.

## Maintenance and failure

Routes below 70% health receive a durable maintenance order and settlement warning. A route below 10% is blocked; zero health or a destroyed bridge is destroyed. Daily winter, storms, overdue maintenance, bridge exposure, traffic and dynamic events can reduce integrity. Graph bridges, planning importance and active shipments determine criticality and therefore repair priority.

Use `/frontier logistics warnings` and `/frontier logistics maintenance` to inspect work. A level-three settlement officer can fund an open order with `/frontier logistics maintain <maintenance-order>`. Payment, material reservation, builder assignment and commit use the normal repair engine. Completion does not blindly reopen the route: it queues a physical reinspection, and capacity returns only after that survey passes.

Operators can tune surfaces and thresholds in `modules/infrastructure.yml`. Existing files receive missing defaults at startup without overwriting present values. Invalid Bukkit material names, out-of-range qualities or unsafe bounds fail startup before gameplay begins.
