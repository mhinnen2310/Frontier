# Settlement membership and governance

Settlement membership changes run through transactional application services. Commands and Paper listeners only collect input; PostgreSQL rechecks settlement state, membership and government role while holding the settlement lock.

## Membership flow

- Mayors and diplomats can invite players for 48 hours and revoke pending invitations.
- A player cannot accept while already in a settlement, in an accepted founding expedition, or actively banned by the target settlement.
- Members may leave at any time except the mayor. The mayor must first transfer ownership.
- The mayor may kick or ban another member. A ban also revokes pending invitations and can be applied before a player joins; unbanning is explicit.
- Join, leave, kick, ban, unban, invitation, revocation and role changes are written to audit and settlement history.

Invitation acceptance and ban/removal serialize on the settlement row. A concurrent accept/ban therefore ends with the player banned and outside the settlement; it cannot create a banned member.

## Mayor continuity

Ownership transfer requires an existing member and is immediate and audited. Emergency succession requires the configured mayor-inactivity period and an active officer role: treasurer, general, architect, builder master or diplomat. The recovery supervisor applies the same rule automatically. A recruit or ordinary citizen cannot take control.

Mayor inactivity and whole-settlement inactivity use separate clocks. By default, an eligible officer can succeed a mayor after 7 days, while a settlement becomes ruins only after 30 days without any member activity. This prevents an inactive mayor from soft-locking an otherwise active community.

## Disband, abandonment and ruins

Disbanding is a two-command operation. `/frontier city disband request` creates a persistent request, and `/frontier city disband confirm <request>` is accepted only from the same mayor after the cooldown and before expiry. Active campaigns block confirmation. Abandonment remains an immediate mayor action.

Both paths create 30-day ruins. Claims are archived, buildings and the core are disabled, simulation stops, and treasury, warehouse and open market orders are frozen. Ruin recovery restores only still-available claims and reactivates the same warehouse and prior order states. It never creates a second warehouse or spends frozen assets. A merge remains a two-mayor proposal and transfers treasury, free stock, capacity, members and territorial assets deterministically into the target settlement.
