# Upgrade guide

Frontier upgrades are forward-only at the database level.

## Procedure

1. Read `CHANGELOG.md` and `DECISIONS.md`; confirm Java, Paper and PostgreSQL requirements.
2. Stop player access and let Paper shut down normally.
3. Take coordinated PostgreSQL and world backups. Verify that both can be restored.
4. Copy the existing JAR and config aside. Compare the new default `config.yml` and merge new keys without replacing credentials or tuned values.
5. Replace the plugin JAR and start Paper. Do not run two Frontier instances against one database.
6. Confirm Flyway reached the bundled latest migration, Paper logged `Done`, and `/frontier admin health` plus `/frontier admin security` pass.
7. Run read-only inspectors, then test Harbor, a wallet transfer, claim protection, one market action and repair listing on staging before reopening.

## Rollback

If startup fails before migrations, restore the prior JAR/config. If any new migration committed, application rollback alone is unsupported: stop Paper, restore the coordinated database and world backup, then install the previous JAR. Never manually delete Flyway rows or reverse schema changes in production.

## From 1.0.0

The 1.0.0 artifact remains immutable. Frontier 1.1 adds migrations V16–V32 for Harbor/wallets through repair occurrence/completion hardening. Back up first and allow the application to apply the entire sequence once. Existing cities remain authoritative; new systems initialize from migrations and bounded recovery.
