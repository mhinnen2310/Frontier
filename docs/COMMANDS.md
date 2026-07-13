# Commands

`/frontier` and alias `/tf` open the Dialog UI. `/frontier help` prints the fallback. Amounts are integer cents; quantities are whole units; identifiers are UUIDs unless a player name is requested. Tab completion exposes valid roots and common enum values.

| Area | Syntax |
|---|---|
| UI/health | `/frontier [menu <screen>]`, `/frontier help`, `/frontier status` |
| Settlement | `/frontier city create <name> [\| <charter>]` starts an expedition; `/frontier city expedition status`, `create <name> [\| <charter>]`, `invite <expedition> <player>`, `accept <expedition>`, `found <expedition>`, `cancel <expedition>`; then `info`, `invite <player>`, `invite-revoke <invitation>`, `accept <membership-invitation>`, `members`, `leave`, `kick <player>`, `ban <player> <reason>`, `unban <player>`, `role <player> <role>`, `claim`, `building <type> [radius] [district]`, `upgrade`, `policy tax <LOW\|STANDARD\|HIGH>`, `transfer <player>`, `succession`, `abandon`, `disband request`, `disband confirm <request>`, `recover`, `merge <city>`, `merge-accept <proposal>`, `history` |
| District | `/frontier district list`, `create <type> [radius] <name>`, `select <name-or-uuid>`, `info <name-or-uuid>`, `delete`, `resize`, `rename`, `manager`/`manager-assign <district> <player>`, `manager-transfer <district> <player>`, `manager-remove <district>`, `budget`, `priority`, `production-priority`, `repair-priority`, `policy`, `worker-assign`/`worker-remove`, `building-assign`/`building-remove`, `view <district> <overview\|manager\|buildings\|workers\|budget\|production\|maintenance\|reports\|policies\|history>`; every `<district>` accepts its name or UUID |
| Money | `/frontier balance`, `/frontier pay <player> <cents>`, `/frontier treasury status`, `deposit <cents>`, `withdraw <cents>`, `pay <player> <cents>`, `history [limit]` (`audit` remains a compatibility alias) |
| Harbor | `/frontier harbor tutorial`, `jobs`, `work [job]`, `status` |
| Market | `/frontier market list`, `warehouse`, `deposit <quantity>`, `buy <commodity> <quantity> <unit-price-cents>`, `sell ...`, `cancel <order>` |
| Production | `/frontier production list`, `queue <building> <recipe> <quantity> [priority]`, `hire <profession> <skill> <daily-salary-cents>` |
| Logistics | `/frontier logistics list`, `node <type>`, `connect <from> <to> <type> [importance]`, `ship <origin-warehouse> <destination-warehouse> <origin-node> <destination-node> <commodity> <quantity> <carrier> <declared-value>` |
| Caravans | `/frontier caravan list`, `escort <shipment>` |
| Population | `/frontier population`, `/frontier workers` |
| Economy | `/frontier economy companies`, `company-create <capital-cents> <name>`, `invoice <company> <player> <cents> <due-days>`, `invoice-pay <invoice>`, `loan <company> <cents> <annual-basis-points>`, `loan-repay <loan> <cents>`, `tax <business-basis-points>`, `procure <commodity> <quantity> <max-unit-cents>`, `fulfill <procurement> <company> <quantity>`, `emergency <commodity> <quantity>`, `history` |
| Contracts | `/frontier contracts list`, `post <destination-warehouse> <commodity> <quantity> <reward-cents> <deadline-minutes>`, `accept <contract>`, `deliver <contract>` |
| War | `/frontier war list`, `declare <defender-city> <type> <objective-type> [target]`, `ceasefire <campaign> [reason]`, `resume <campaign> [reason]`, `resolve <campaign> [reason]`, `outcome <campaign> <outcome> [amount-cents]`, `end <campaign> [reason]` |
| Repair | `/frontier repair list`, `quote <campaign> [priority]`, `buy <campaign> [priority]` |
| World/events | `/frontier world regions`, `events`, `season`; `/frontier events list`, `join <event> [role]`, `respond <event> <contribution>` |
| Endgame | `/frontier endgame catalog`, `rankings`, `history`, `unlocks <kingdom>` |
| Kingdom | `/frontier kingdom list`, `create <name>`, `invite <kingdom> <city>`, `accept <invitation>`, `overview <kingdom>`, `treaty <kingdom> <counterpart> <type> [days]`, `treaty-accept <treaty>`, `treaties <kingdom>`, `role <kingdom> <player-uuid> <king\|council\|marshal\|diplomat>`, `vote <kingdom> <kind> [hours]`, `vote-cast <vote> <yes\|no>`, `war-approve <kingdom> <target-city> [type]`, `deposit <kingdom> <city> <cents>`, `withdraw ...`, `tax <kingdom> <basis-points>`, `tax-collect <kingdom>`, `policy <kingdom> <key> <value>`, `secede <kingdom> <city>`, `research <kingdom> <branch> <project> <points>`, `wonder <kingdom> <key> <commodity> <units>`, `contribute <wonder> <units>`, `mega <kingdom> <key> <commodity> <units>`, `mega-contribute <project> <units>`, `objectives` |

Administrative commands are deliberately separated in [ADMIN.md](ADMIN.md).

`/frontier admin build` reports the exact packaged version, Git source revision/time, Java runtime, Paper target, live Flyway schema version and runtime/build-only module status. Configuration administration uses `/frontier admin config validate|reload|show <global|module>`; secret values are never printed.
