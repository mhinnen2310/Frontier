# Building validation

A registered selection becomes operational only after a bounded live-world inspection and a transactional persistence check both succeed. Commands and Paper adapters collect input; `BuildingValidationService` owns the workflow and the reusable domain validator owns gameplay rules.

## Validation model

Each `BuildingDefinition` is a list of named `ValidationRule` functions. All rules consume the same immutable `BuildingInspection`, so one registration never rescans the selected cuboid per rule. A result contains every violation rather than stopping at the first failure.

Shared rules require:

- the complete selection to be controlled by the settlement;
- no overlap with another registered building;
- a real compatible district when a district is selected;
- width, height, depth and total volume within configured hard limits;
- at least the configured structural-block minimum.

Enclosed buildings additionally use configured floor, boundary-wall and roof coverage. Per-type profiles decide whether enclosure, entrance and a recognized road on the one-block perimeter are required. Functional requirements use block families rather than exact block positions or a schematic.

| Type | Default functional minimum |
|---|---|
| Town Hall | bell, lectern, four stair seats, enclosure, entrance and road |
| Warehouse | two chests/barrels, enclosure, entrance and road |
| Housing | bed, six interior-air blocks, light, enclosure and entrance |
| Farm | 16 farmland, water and eight crops; intentionally outdoors |
| Builder Guild | two crafting/smithing stations and two storage blocks, enclosed |
| Market | three stall blocks, entrance and road; may be outdoors |
| Barracks | four beds and two storage blocks, enclosed |
| Workshop | two crafting stations, a furnace variant, storage, enclosure, entrance and road |
| Mine Entrance | four rails, two lights, entrance and road; may be open-air |
| Watchtower | minimum height eight, four ladders/scaffolding, light, enclosure and entrance |

All counts and type dimensions are server-configurable within the global scan limits. Every profile must keep at least one functional minimum. Failure text includes both the required and observed value, for example `requires 4 rail blocks (found 2)`.

## Registration protocol

1. Open `/frontier menu buildings` or run `/frontier city building start <type> [district]`. A tagged blaze rod is issued for the configured session lifetime.
2. Left-click the first corner and right-click the second. Frontier normalizes reverse corners, rejects cross-world selection and automatically previews after the second click.
3. Preview rereads the bounded cuboid, prints every missing requirement with observed values and marks its corners with green or red particles. It also reports claim, district and overlap failures.
4. Run `confirm` to resurvey and register or `cancel` to discard the session. A successful preview is never treated as authorization for a later confirmation.

The underlying registration protocol then proceeds:

1. The UI supplies the settlement, optional district, type and two selected corners.
2. The Paper survey rejects oversized dimensions or volume before iterating blocks, then produces counts and coverage percentages.
3. Pure rules evaluate the inspection and authorization context.
4. On success PostgreSQL opens one transaction, locks the settlement and repeats claim control, overlap and district compatibility checks.
5. The transaction records `PLANNED` → `UNDER_CONSTRUCTION` → `VALIDATING` → `ACTIVE` history and stores the validation report/version.

The repeated database checks are deliberate: Minecraft chunks and PostgreSQL cannot share one transaction. A stale world survey can therefore fail safely before an invalid assignment commits.

## Revalidation, unregister and transfer

Mayors and architects may revalidate or inspect history. Revalidation excludes the building itself from overlap but repeats all other live and transactional checks; integrity still decides whether the resulting state is `ACTIVE`, `DAMAGED`, `DISABLED` or `DESTROYED`.

Unregister records `ABANDONED`, clears the district and preserves history. It is rejected while an active warehouse, Builder Guild depot, production order or assigned/housed worker depends on the building. An abandoned footprint may be registered again.

Only a source mayor may propose ownership transfer and only the target mayor may accept it before expiry. Acceptance is one transaction: it verifies that the building's claim chunks contain no other active source building, transfers those claims and the building, and clears the district assignment. One-sided or overlapping transfers fail without partial mutation.

## Lifecycle

The constrained state vocabulary is `PLANNED`, `UNDER_CONSTRUCTION`, `VALIDATING`, `ACTIVE`, `DAMAGED`, `DISABLED`, `DESTROYED`, and `ABANDONED`. Only validated `ACTIVE` or sufficiently healthy `DAMAGED` buildings contribute to district specialization. Damage, ruin and later revalidation flows must use this same vocabulary; callers may not invent status strings.

## Tuning and troubleshooting

`modules/buildings.yml` controls maximum dimensions/volume, minimum structural blocks, enclosure coverage, per-type rules, selection timeout and transfer-proposal lifetime. Invalid or non-positive bounds fail startup. These are restart-required settings.

When registration fails, inspect the returned violation list and the stored validation report. Do not bypass a failure with SQL: adjust the selection or physical structure. If a formerly active building is physically edited, later type-specific validation and integrity sprints govern its reinspection and state transition.
