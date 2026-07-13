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

1. The command supplies the settlement, optional district, type and two selected corners.
2. The Paper survey rejects oversized dimensions or volume before iterating blocks, then produces counts and coverage percentages.
3. Pure rules evaluate the inspection and authorization context.
4. On success PostgreSQL opens one transaction, locks the settlement and repeats claim control, overlap and district compatibility checks.
5. The transaction records `PLANNED` → `UNDER_CONSTRUCTION` → `VALIDATING` → `ACTIVE` history and stores the validation report/version.

The repeated database checks are deliberate: Minecraft chunks and PostgreSQL cannot share one transaction. A stale world survey can therefore fail safely before an invalid assignment commits.

## Lifecycle

The constrained state vocabulary is `PLANNED`, `UNDER_CONSTRUCTION`, `VALIDATING`, `ACTIVE`, `DAMAGED`, `DISABLED`, `DESTROYED`, and `ABANDONED`. Only validated `ACTIVE` or sufficiently healthy `DAMAGED` buildings contribute to district specialization. Damage, ruin and later revalidation flows must use this same vocabulary; callers may not invent status strings.

## Tuning and troubleshooting

`modules/buildings.yml` controls maximum dimensions/volume, minimum structural blocks and enclosure coverage thresholds. Invalid or non-positive bounds fail startup. These are restart-required settings.

When registration fails, inspect the returned violation list and the stored validation report. Do not bypass a failure with SQL: adjust the selection or physical structure. If a formerly active building is physically edited, later type-specific validation and integrity sprints govern its reinspection and state transition.
