# Claim protection

Controlled settlement territory fails closed. Paper listeners translate an event into an action,
actor, target and optional source location; `TerritoryActionPolicy` is the only component that
decides claim authorization. The listeners never query PostgreSQL and never contain role or war
rules. Their atomically replaced cache contains claim ownership, settlement membership, government
role and explicit player overrides.

## Decision order

1. Wilderness permits normal action.
2. `frontier.protection.bypass` permits an operator action.
3. The settlement owner is permitted.
4. An explicit `PROTECTION_<ACTION>` override permits or denies.
5. Settlement role policy is evaluated.
6. An active hostile campaign permits only break/explosion entry into the structural damage
   gateway; that gateway must still match an objective, reserve breach capacity, journal and confirm
   the world mutation.
7. All other actions are denied.

Treaty and incident state are explicit policy inputs but do not grant implicit property access.
Recruits may use ordinary interactions. Citizens may build, break, use containers, entities,
buckets, crops and vehicles. Mayors, architects and builder masters additionally control hoppers,
hanging entities, fire, explosions and redstone. Explicit player overrides take precedence over
role defaults.

## Covered Paper surfaces

Protection includes block break/place, buckets, fire/ignite/spread, piston movement, liquids,
entity and block explosions, containers and entity inventories, hopper movement, doors, trapdoors,
buttons, levers, pressure plates, beds, crops and trampling, armor stands, item frames and hanging
entities, animal interaction/damage, lecterns, signs, furnaces, anvils, brewing stands, minecarts,
boats and cross-boundary redstone.

Actorless propagation may continue only when source and target have the same territory owner.
Consequently pistons, liquids, hoppers, fire, block explosions and redstone cannot cross between
wilderness and a claim or between two settlements. Entity explosions in claimed land require an
identified player with owner/override/trusted-role authority, or an active campaign routed through
the journaled structural-damage service.

## Verification

`ClaimProtectionServiceTest` exercises outsider, owner, member, recruit, trusted role, explicit
allow/deny override, campaign, treaty/incident, bypass and settlement/wilderness boundaries across
the complete action vocabulary. `ClaimProtectionListenerContractTest` asserts that every audited
Paper event still has an annotated adapter and verifies restrictive block/vehicle classification.
