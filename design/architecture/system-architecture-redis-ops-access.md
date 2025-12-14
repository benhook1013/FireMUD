# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes**.

## Coordination Redis Access Rules

- Coordination Redis is treated as an **application-only write surface**:
  - All writes to coordination prefixes (`tick:*`, `retry:*`, `timer:*`, `remote:*`, `session:*`, `tick-executor-lease:*`, and related keys) go through registered Lua scripts and key-builder helpers in `firemud-common`.
  - Application services (Game Session, Automation & Scripting, and any future tick participants) never bypass those helpers with raw Redis commands.
- Human operators and ad-hoc tools:
  - May use `redis-cli`, RedisInsight, or similar tools with **read-only ops users** to inspect coordination state.
  - Must not issue raw `EVAL`/`EVALSHA`, `SET`, `DEL`, or TTL-changing commands against coordination prefixes in normal operation.

These rules keep the script registry’s invariants and hash-tag discipline meaningful by ensuring there is only one code path for mutating coordination keys.

## Ops User vs Application User

Redis ACLs enforce a clear split between application and operations clients:

- Application user (for example `coord_app`):
  - Used only by application services and shared maintenance tools that import `firemud-common` helpers.
  - Permitted to execute `EVALSHA`/`SCRIPT LOAD` and write commands on coordination databases.
  - Not used from interactive shells or general-purpose admin tooling in production.
- Read-only ops user (for example `coord_ops_ro`):
  - Used by human operators and generic tools.
  - Restricted to `@read` commands; explicitly denied `EVAL`, `EVALSHA`, `SCRIPT LOAD`, and write commands for coordination deployments.
  - Allowed to run non-destructive inspection commands (`GET`, `HGETALL`, `ZRANGE`, `SCAN`, etc.) for debugging and incident analysis.

Other Redis roles (for example cache/rate-limit clients) connect to separate deployments or logical databases that do not contain coordination prefixes.

## Supported Maintenance Tooling

Operators interact with coordination state through **supported tools**, not raw Redis commands:

- A small “coordination maintenance client” or CLI (implemented in the codebase, not ad-hoc scripts) provides:
  - High-level operations such as “inspect region”, “dump pending”, “list locks for entity”, “trigger scoped reset”.
  - Guarantees that all keys and scripts are invoked via the shared descriptors and registry.
- Runbooks and Helm hooks:
  - Call into this maintenance tooling for any write operation on coordination prefixes.
  - Use raw Redis commands only for:
    - Node-level operations such as `FLUSHALL`/AOF reset during a coordinated reset (already covered by the Redis Operations doc).
    - Read-only inspection via the ops user.

Direct `redis-cli` writes to coordination prefixes are reserved for **break-glass scenarios** and must follow the incident guidelines in `system-architecture-redis.md` (auditing, post-incident reset, and verification).

## Static Checks for Ops Scripts

To keep operational scripts aligned with application code:

- Repository-wide checks are extended to cover:
  - `dev-tools/` and other maintenance directories.
  - Helm hooks and Kubernetes jobs that interact with Redis.
- CI fails when:
  - Ops scripts contain raw `EVAL`/`EVALSHA` against coordination deployments.
  - Scripts construct `tick:*`, `timer:*`, `retry:*`, `remote:*`, or `session:*` keys by hand instead of calling shared helpers.

Maintenance scripts that genuinely need to work with coordination keys must:

- Import the same key-builder APIs and Lua registry helpers used by services.
- Document their scope (which prefixes/tenants/regions they touch) and the runbook they implement.

This keeps human-driven maintenance and automation under the same discipline as regular application code, reducing the chance that debugging or emergency fixes introduce silent hash-tag or lock/lease violations.
