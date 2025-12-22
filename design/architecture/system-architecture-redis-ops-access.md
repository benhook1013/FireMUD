# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes** and how those tools participate in the [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model).

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

In addition, coordination deployments must ensure that **configuration-changing commands** (such as `CONFIG *`, `SLAVEOF`/`REPLICAOF`, `CLUSTER *`, and `SHUTDOWN`) are reserved for infrastructure automation or dedicated admin roles, not everyday ops users:

- Standard read-only ops users (`coord_ops_ro`) must **not** have access to configuration commands in production; they focus solely on inspection.
- Any tooling that legitimately needs configuration access (for example, Kubernetes operators or controlled maintenance jobs) must:
  - Use a distinct, tightly scoped Redis user.
  - Be treated as part of the infrastructure control plane, not general incident response.

Other Redis roles (for example cache/rate-limit clients) connect to separate deployments or logical databases that do not contain coordination prefixes.

## Configuration and Redis Role Selection

All tools and services refer to Redis deployments via **role-specific configuration**, not hard-coded URLs:

- Coordination clients and ops tools read connection settings from `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT` (or an equivalent `FIREMUD_REDIS_COORD_URL`), which identify the **Coordination Redis** deployment.
- Cache/rate-limit clients and tools read from `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` (or `FIREMUD_REDIS_CACHE_URL`), which identify the **Cache/Rate-Limit Redis** deployment.

A small shared configuration module (in `firemud-common` or a dedicated tooling library) exposes **typed configs and helpers** such as:

- `RedisCoordConfig` + `createCoordinationRedisClient(...)`
- `RedisCacheConfig` + `createCacheRedisClient(...)`

All ops scripts and maintenance tools must:

- Accept an explicit `RedisCoordConfig` when they touch coordination prefixes.
- Accept an explicit `RedisCacheConfig` when they operate only on cache/rate-limit prefixes.
- Never construct Redis host/port or URLs by hand.

This makes the target Redis role part of the tool’s type signature and configuration, reducing the chance that coordination tooling accidentally points at Cache Redis (or vice versa).

Some health checks and observability tools legitimately need to talk to **both** roles in a single process (for example, a composite “Redis health” check or a diagnostic CLI). These multi-role tools must:

- Accept both `RedisCoordConfig` and `RedisCacheConfig` explicitly and label logs/metrics with a `redis_role` tag (for example `coordination` vs `cache`) so misconfigurations are easy to spot.
- Avoid sharing Redis client instances between roles; each role gets its own client configuration and connection pool.
- Keep any write operations role-specific and minimal; cross-role flows (for example, verifying that a prefix truly lives only on Cache Redis) should be implemented as read-only checks, not cross-writing scripts.

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

Direct `redis-cli` writes to coordination prefixes are reserved for **break-glass scenarios** and must follow the incident guidelines in `system-architecture-redis.md` (auditing, post-incident reset, and verification). As an additional guardrail:

- Any break-glass write that mutates `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, or `tick-executor-lease:*` for a given `{tenantId, regionId}` (or tenant) must be followed by a **scoped coordination reset** for the affected scope before normal tick processing resumes.
- Operators must treat such writes as equivalent to “coordination state may be inconsistent” and use the Coordination Reset Model to bring the region/tenant/cluster back to a known-good state, rather than leaving ad-hoc edits in place as a permanent fix.
- Break-glass flows should go through a small wrapper (CLI or Logging & Admin action) that:
  - Executes the minimal required Redis mutation.
  - Immediately triggers the appropriate scoped coordination reset.
  - Emits a structured audit event (for example `coordination_break_glass`) recording:
    - A unique event identifier and timestamp.
    - The affected tenants/regions and reset scope (region/tenant/cluster).
    - The operator or automation identity that initiated the change.
    - The Redis role and deployment (for example `coordination`, cluster name, node ID).
    - A free-form reason string describing why break-glass was used.
- After the scoped reset completes, operators run the standard post-reset health checks (for example, verifying that core Lua scripts load successfully and that sample ticks can schedule and commit for the affected regions) before unpausing ticks. Larger, more formal deployments may additionally link these audit events to external incident tracking systems, but hobby and self-hosted setups can rely on the built-in audit log alone.

### Tooling Maintenance and Versioning

The coordination maintenance client/CLI is treated as a first-class part of the system, not an ad-hoc script:

- It lives in the same repository and modules as:
  - The shared key builders and Lua Script Registry descriptors (`firemud-common`).
  - The integration tests that exercise script behavior and key shapes.
- It is **versioned alongside the main services**; there is no separate, free-floating versioning scheme for tooling.
- Any change to coordination key formats or Lua script contracts must:
  - Update the shared descriptors and key-builder helpers.
  - Update the maintenance CLI code that uses those helpers.
  - Extend or adjust the shared integration tests so both services and tooling are validated against the same expectations.

This ensures that operators use the same abstractions as application code and reduces the risk that maintenance tools silently drift away from the main coordination design.

## Static Checks for Ops Scripts

To keep operational scripts aligned with application code:

- Repository-wide checks are extended to cover:
  - `dev-tools/` and other maintenance directories.
  - Helm hooks and Kubernetes jobs that interact with Redis.
- CI fails when:
  - Ops scripts contain raw `EVAL`/`EVALSHA` against coordination deployments.
  - Scripts construct `tick:*`, `timer:*`, `retry:*`, `remote:*`, or `session:*` keys by hand instead of calling shared helpers.
  - Scripts hard-code Redis host/port or URLs instead of using the shared `RedisCoordConfig` / `RedisCacheConfig` helpers and role-specific environment variables.
  - Cache/rate-limit scripts introduce Redis prefixes that are not listed in the **Cache/Rate-Limit Redis Key Catalog** in `system-architecture-redis.md`, or misuse those prefixes against the wrong Redis role.
  - Automation-related Lua or tooling scripts reference both `automation:*` and `tick:*` prefixes in a single operation, violating the automation cluster slotting rules in `system-architecture-redis-lua-patterns.md` and the Automation & Scripting service design.

Maintenance scripts that genuinely need to work with coordination keys must:

- Import the same key-builder APIs and Lua registry helpers used by services.
- Document their scope (which prefixes/tenants/regions they touch) and the runbook they implement.

This keeps human-driven maintenance and automation under the same discipline as regular application code, reducing the chance that debugging or emergency fixes introduce silent hash-tag or lock/lease violations.

In addition, scripts and runbooks are labelled by **target Redis role**:

- “Coordination ops” scripts may only use the coordination config and helpers; CI fails if they import cache-only helpers or reference cache URLs.
- Cache/rate-limit tooling may only use the cache config; CI fails if it imports coordination helpers.

This role-aware labelling keeps coordination and cache tooling clearly separated in both code and configuration.
