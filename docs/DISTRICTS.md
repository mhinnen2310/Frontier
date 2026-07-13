# District domain

Districts are settlement-owned aggregates with a stable UUID. The authoritative `districts` row stores identity, type, manager, status, tier, budget allocation, maintenance requirement, general/production/repair priorities and optimistic version. Bounds and center are normalized in `district_regions`; policies, roles and player memberships have their own constrained tables. Append-only `district_history` remains available after a district is deleted.

## Types

Frontier supports Residential, Agricultural, Industrial, Commercial, Military, Government, Logistics, Mining, Forestry, Culture, Research and Harbor districts. Type is a planning category, not an exact schematic. Physical buildings and infrastructure must still pass their own validators before a specialization can affect gameplay.

## Spatial invariants

A district region must remain wholly inside the settlement's controlled chunks, use one world, have ordered bounds and not overlap another active district. Center coordinates are persisted and constrained to the region. Create and resize lock the settlement/district, validate claims and overlap, then update the region and its compatibility mirror in the same transaction.

Buildings reference `districts.id` through a UUID foreign key. The former free-text `district_key` column was migrated and removed in V39, so an invalid string can no longer assign a building to a district. Production, repairs, defense and building validation all consume this UUID relation.

Reassignment repeats authorization, settlement ownership, active-state, full-bounds containment and building-type compatibility checks while both the target district and building are locked. The shared compatibility policy is also used during initial building registration.

## Roles, members and policy

Every district receives Manager, Officer, Resident and Worker role definitions with constrained permission arrays. Assigning or transferring a manager also writes the authoritative manager membership. Policies are stored by constrained key in `district_policies`; V41 removes the migrated JSON columns so region and policy state each have exactly one source of truth.

## Management gameplay

District references accept a case-insensitive name or UUID. Listing and ordinary flows show names; UUID is only needed when two accessible settlements contain the same district name.

Mayors and architects create, rename and resize districts. Mayors appoint, replace or remove a manager and delete districts. Treasurers allocate budget. An appointed manager or settlement planner can allocate workers/buildings and choose production/repair priorities. Every mutation runs in the corresponding transactional service and appends district history.

The Overview, Manager, Buildings, Workers, Budget, Production, Maintenance, Reports, Policies and History Dialog views use the same command-to-service path as the fallback commands. Reports include concrete assignment rows instead of only totals.

## Effective specialization

A district type by itself grants nothing. Its specialization activates only when it contains a compatible registered building in `ACTIVE` or `DAMAGED` state above the configured integrity threshold and at least one healthy in-bounds road node connected to a healthy edge. Reports show the qualifying building/node counts and why a specialization is inactive.

The first building supplies the base effect. Further valid buildings add a diminishing configured contribution up to a hard count, compatible neighboring district types add a small bounded modifier, and excessive active districts of the same type subtract a penalty. The result is clamped to the configured maximum. Production, housing, maintenance, defense, trade ordering, worker efficiency and repair ordering read this effective value, not the type's theoretical base.

Industrial specialization increases settlement maintenance and Military specialization increases wages. Commercial specialization adds bounded market-order slots; Logistics adds bounded effective warehouse capacity. These penalties sum, while capacity benefits use the strongest active district so duplicating a district cannot stack them without limit.
