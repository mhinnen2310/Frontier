# Paper Dialogs

`/frontier` opens the root screen; `/frontier menu <screen>` opens one directly. Dialogs are the primary UX and commands are the complete fallback. Read actions execute immediately. Mutations use player/action/aggregate-bound, expiring, single-use tokens and server-side validation, so editing a client payload cannot bypass permissions or replay an action.

| Screen key | Purpose and principal actions |
|---|---|
| `frontier` | Navigation to every subsystem |
| `settlement` | Overview, found, claim, upgrade, population |
| `district` | List/create/select plus Overview, Manager, Buildings, Workers, Budget, Production, Maintenance, Reports, Policies and History views; contextual mutation buttons call the same transactional commands |
| `kingdom` | List, overview, create, approve war, tax and policy |
| `treasury` | Player/settlement balances, audit, deposit, withdraw and pay |
| `repair` | Orders, quotes and purchase |
| `war` | Campaign list, declaration, ceasefire and resolution |
| `market` | Orders, warehouse, buy and sell |
| `workers` | Workers, population, production and hiring |
| `contracts` | Contracts, Harbor jobs, accept and deliver |
| `infrastructure` | Shipments, caravans, nodes and escorts |
| `history` | Commercial, treasury and district history |
| `reports` | Health, world, events, rankings, settlement and population reports |
| `settings` | Refresh, command help and health |

Placeholders shown as `$(name)` become typed text, number or UUID inputs. Closing a dialog has no side effect. Failed validation returns an error without partially committing the requested action.
