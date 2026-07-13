# Frontier documentation

This directory is the maintained operator, player and developer manual for Frontier 1.1. The SQL migrations and source code remain authoritative when developing a newer version.

## Players

- [Gameplay](GAMEPLAY.md): the Harbor-to-endgame loop and major systems.
- [Commands](COMMANDS.md): complete command fallback reference.
- [Dialogs](DIALOGS.md): all Paper Dialog screens and their command equivalents.

## Server owners

- [Administration](ADMIN.md): installation, diagnostics, recovery, security and performance.
- [Permissions](PERMISSIONS.md): Bukkit nodes and settlement/kingdom authorization.
- [Settlement founding](SETTLEMENT_FOUNDING.md): expedition, founder, fee/material and physical-core recovery protocol.
- [Settlement membership](SETTLEMENT_MEMBERSHIP.md): invitations, bans, succession, disband confirmation and ruin asset policy.
- [Districts](DISTRICTS.md): normalized domain, regions, UUID building assignment, roles and persistence invariants.
- [Building validation](BUILDING_VALIDATION.md): physical inspection rules, lifecycle and safe registration protocol.
- [Claim protection](CLAIM_PROTECTION.md): central action policy, role rules and covered Paper events.
- [Repair integrity](REPAIR_INTEGRITY.md): damage generations, crash recovery, leases and material safety.
- [Configuration](CONFIGURATION.md): every configuration section and safe tuning.
- [Upgrade guide](UPGRADE.md): backup, migration, rollback and verification procedure.
- [Operations](OPERATIONS.md) and [verification evidence](VERIFICATION.md).

## Developers

- [Architecture](ARCHITECTURE.md): modules, transaction boundaries and runtime flow.
- [Developer API](DEVELOPER_API.md): supported interfaces and extension rules.
- [Database schema](DATABASE.md): ownership, table families and all migrations.
- [Implementation status](../IMPLEMENTATION_STATUS.md), [decisions](../DECISIONS.md), [changelog](../CHANGELOG.md) and [security audit](../SECURITY_AUDIT.md).
