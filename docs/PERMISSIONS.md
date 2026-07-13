# Permissions and authorization

## Bukkit nodes

| Node | Default | Meaning |
|---|---|---|
| `frontier.admin` | operators | Administrative diagnostics and recovery |
| `frontier.city.create` | everyone | Begin the settlement founding transaction |
| `frontier.protection.bypass` | operators | Bypass claim protection; all use is auditable and must be tightly assigned |

Wildcard permissions are not declared. Use a permissions plugin to assign these exact nodes. Do not grant bypass to normal staff or gameplay roles.

## Domain authorization

Bukkit nodes do not replace settlement authorization. Every protected action evaluates campaign status, claim owner, settlement membership/role, explicit city permission overrides, and bypass. Treasury, districts, buildings, war and lifecycle operations require the appropriate mayor/officer/manager role. Kingdom actions additionally require king, council, marshal or diplomat authority and, where configured, a recorded vote or war approval.

Listeners and commands call the same transactional authorization services. Online status, a Dialog token, physical access to a container, or client-supplied UUID never proves authority by itself.
