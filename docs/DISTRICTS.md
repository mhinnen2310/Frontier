# District domain

Districts are settlement-owned aggregates with a stable UUID. The authoritative `districts` row stores identity, type, manager, status, tier, budget allocation, maintenance requirement, priority and optimistic version. Bounds and center are normalized in `district_regions`; policies, roles and player memberships have their own constrained tables. Append-only `district_history` remains available after a district is deleted.

## Types

Frontier supports Residential, Agricultural, Industrial, Commercial, Military, Government, Logistics, Mining, Forestry, Culture, Research and Harbor districts. Type is a planning category, not an exact schematic. Physical buildings and infrastructure must still pass their own validators before a specialization can affect gameplay.

## Spatial invariants

A district region must remain wholly inside the settlement's controlled chunks, use one world, have ordered bounds and not overlap another active district. Center coordinates are persisted and constrained to the region. Create and resize lock the settlement/district, validate claims and overlap, then update the region and its compatibility mirror in the same transaction.

Buildings reference `districts.id` through a UUID foreign key. The former free-text `district_key` column was migrated and removed in V39, so an invalid string can no longer assign a building to a district. Production, repairs, defense and building validation all consume this UUID relation.

## Roles, members and policy

Every district receives Manager, Officer, Resident and Worker role definitions with constrained permission arrays. Assigning or transferring a manager also writes the authoritative manager membership. Policies are stored by constrained key in `district_policies`; V41 removes the migrated JSON columns so region and policy state each have exactly one source of truth.

The current management commands expose creation, listing, rename/resize, manager assignment/transfer, budget, priority, policies, workers, reports and deletion. Sprint 9 expands player allocation and Dialog flows; Sprint 10 applies specialization balance rules beyond the bounded base bonuses.
