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

The 1.0.0 artifact remains immutable. Frontier 1.1 currently adds migrations V16–V45 for Harbor/wallets through repair integrity, founding/membership governance, specialized districts and building validation/registration. Back up first and allow the application to apply the entire sequence once. V37 migrates existing region, policy and manager data; V39 removes `city_buildings.district_key` only after every valid UUID value is copied to the constrained `district_id`; V40 renames the two legacy district tables without changing IDs or rows; V41 removes the now-migrated district JSON columns; V42 adds production and repair priorities with safe defaults of 50; V43 makes previous static bonuses conditional on valid buildings/infrastructure and installs default balance settings before startup applies configured values; V44 constrains every building/history state to the complete lifecycle and adds the specialization eligibility index; V45 adds constrained transfer proposals without changing existing buildings. Existing `buildings.yml` files receive the new registration/type defaults while preserving tuned values.
