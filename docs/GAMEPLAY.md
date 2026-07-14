# Gameplay

## First hour without an administrator

1. Join at Frontier Harbor and run `/frontier harbor tutorial`.
2. Inspect `/frontier harbor jobs`, perform `/frontier harbor work`, and receive wallet money from the Harbor's daily starter budget.
3. Start an expedition with `/frontier city create <name> [| <charter>]`. Invite founders with `/frontier city expedition invite`, and each invited player confirms with `accept`.
4. Stand at the intended core with the configured materials and use `/frontier city expedition found <expedition-uuid>`. The service revalidates world, terrain, claims, distance and the Harbor exclusion zone before reserving the fee and placing the bell.
5. Deposit money with `/frontier treasury deposit <cents>`, claim land, register valid buildings and upgrade.

Harbor guarantees limited bad-price buy orders and starter contracts. Typed server configuration controls only a hard low-tier commodity set, finite daily quantities, global currency creation and per-player rewards. Its enforced loss-making spread makes it an early-game source and sink, not a competitive late-game market.

## Settlement progression

Settlements have a physical bell core, charter, confirmed founders, members, roles, treasury, population, claims and lifecycle. Founding is a persistent [expedition saga](SETTLEMENT_FOUNDING.md); creating an expedition alone never creates a settlement. [Membership and governance](SETTLEMENT_MEMBERSHIP.md) cover invitations, leave/kick/ban, ownership transfer, officer succession and delayed disband confirmation. Buildings progress through `PLANNED`, `UNDER_CONSTRUCTION`, `VALIDATING`, `ACTIVE`, `DAMAGED`, `DISABLED`, `DESTROYED`, and `ABANDONED`. The [building validator and Architect flow](BUILDING_VALIDATION.md) use a corner-selection tool, particle preview and understandable missing-requirement report for Town Halls, warehouses, housing, farms, Builder Guilds, markets, barracks, workshops, mine entrances and watchtowers; an empty selection is never operational. Abandonment creates ruins and freezes settlement assets; recovery and merge restore or transfer them through audited operations.

Districts divide owned land into residential, agricultural, industrial, commercial, military, government, logistics, harbor, mining, forestry, research or culture areas. Managers control budgets, worker/building allocation, priorities and policies. A specialization requires a compatible validated building and connected healthy infrastructure. More buildings have diminishing returns; useful neighboring types help; repeatedly choosing one type causes an over-specialization penalty. Effects influence production, housing, maintenance, defense, trade, efficiency and repair priority, while Industrial/Military choices cost extra maintenance/wages and Commercial/Logistics choices expand market/warehouse capacity.

## Economy and population

Players have wallets and can pay each other or transact with settlement treasuries. Warehouses back real stock, production consumes recipes, workers earn salaries and markets escrow orders. Companies add invoices, loans, tax and procurement. Shipments move authoritative cargo over infrastructure; a caravan NPC only visualizes that database state.

Population depends on validated active housing, stored food, employment, safety and prosperity. Workers have profession, skill, morale, efficiency, experience, employment and salary. Migration, immigration, emigration, retirement, births and deaths are simulated in bounded daily transactions. New-settlement/food grace, daily growth/decline caps and a collapse floor keep shortages recoverable; `/frontier population` explains the signed trend and every contributing cause.

## War, damage and repair

Campaigns move through preparation, active, ceasefire and resolution. [Claim protection](CLAIM_PROTECTION.md) checks actor/action/source/target, campaign, treaty, incident, ownership, role, overrides and bypass for the complete Paper interaction surface. Automation, liquids, fire and redstone cannot cross a territory boundary. Authorized campaign structural damage is journaled once per generation. [Paid repair](REPAIR_INTEGRITY.md) progresses `REGISTERED` → `RESERVED` → `REPAIRING` → `COMPLETED` → `ARCHIVED`; leases and prepared consumption make restart, disconnect and chunk-unload recovery safe. The [Builder Guild](BUILDER_GUILD.md) adds foremen, teams, depot supply, queue control, emergency work and controlled player contribution without bypassing that lifecycle.

Results include occupation, liberation, conquest, annexation, territory concession, reparations, tribute, independence, civil war and kingdom intervention. Transfers include claims and their buildings, roads, infrastructure, workers and storage.

## World and endgame

Physical roads may curve inside a bounded survey corridor but must continuously reach both registered endpoints. Configured surfaces determine quality; measured width, slope, gaps, bridges, tunnel enclosure and gates decide validity. Roads, bridges, tunnels, gates, harbors and watchtowers have derived health/capacity plus traffic, importance and an accountable owner. Block changes trigger leased reinspection; blocked routes reroute shipments and generate settlement warnings/repair orders, while paid builders restore them through the normal repair engine. Weather, season, decay, bandits, disasters, migration waves, fairs, plague, flood and harvest failures affect simulation. Kingdom treaties, taxes, votes, roles, shared projects, research, era progression, wonders, mega projects, objectives, prestige and rankings form the late game.
