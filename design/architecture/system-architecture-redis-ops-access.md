# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes** and how those tools participate in the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model).

## Default Operator Surface

- Using the **read-only ops user** for inspection and the **application user** (via supported tooling) for any coordination writes.
- Running coordination maintenance exclusively through the **versioned maintenance CLI** and its documented commands.

Defining additional Redis users, ACL variations, or ad-hoc tools is considered **advanced** and should be avoided unless existing roles and tooling are clearly insufficient for a documented operational requirement.

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
  - Restricted to read-only capabilities; explicitly denied `EVAL`, `EVALSHA`, `SCRIPT LOAD`, and write commands for coordination deployments.
  - Must not rely on `@read` alone: many essential diagnostics commands are not in `@read` and need explicit allowlisting. The recommended baseline is `@read` plus an incident-response allowlist that remains strictly non-mutating, for example:
    - Keyspace-safe inspection: `+scan +sscan +hscan +zscan +type +ttl +pttl +exists`
    - Latency/health diagnostics: `+info +slowlog|get +slowlog|len +latency|latest +latency|doctor`
    - Client/memory diagnostics (read-only subcommands only): `+client|list +client|id +memory|usage +memory|stats`
    - Cluster topology visibility (read-only subcommands only): `+cluster|info +cluster|nodes`

In addition, coordination deployments must ensure that **configuration-changing commands** (such as `CONFIG *`, `SLAVEOF`/`REPLICAOF`, `CLUSTER MEET`/`ADDSLOTS`/`DELSLOTS`/resharding operations, and `SHUTDOWN`) are reserved for infrastructure automation or dedicated admin roles, not everyday ops users:

- Standard read-only ops users (`coord_ops_ro`) must **not** have access to configuration commands in production; they focus solely on inspection.
- Any tooling that legitimately needs configuration access (for example, Kubernetes operators or controlled maintenance jobs) must:
  - Use a distinct, tightly scoped Redis user.
  - Be treated as part of the infrastructure control plane, not general incident response.

Other Redis roles (for example cache/rate-limit clients) connect to separate deployments or logical databases that do not contain coordination prefixes.

### Redis ACL Roles Overview

To keep ACL usage consistent across services and documentation, Redis deployments should expose a small, shared set of ACL users and map them to service roles:

| ACL User (example) | Intended Role | Typical Consumers |
| --- | --- | --- |
| `coord_app` | Coordination application client – may write and run Lua scripts against **Coordination Redis** only | Game Session Service, Automation & Scripting Service, shared coordination maintenance CLI |
| `coord_ops_ro` | Read-only coordination ops user – may inspect coordination keys but never write or run Lua on them | Human operators using `redis-cli`/RedisInsight, monitoring/exporter agents for Coordination Redis |
| `cache_app` | Cache/Rate-Limit application client – may read/write **only** cache/rate-limit prefixes on Cache/Rate-Limit Redis | Spring Cloud Gateway, Entity Management, World Management, Social & Groups, Game Session (for `view:room-look:*`), Automation & Scripting (for `automation:queue:*` / `automation:quota:*`) |
| `cache_ops_ro` | Read-only cache ops user – may inspect cache/rate-limit keys but never write to them | Human operators inspecting Cache/Rate-Limit Redis, cache-focused monitoring/exporters |

Per-service READMEs are expected to state which ACL user(s) each service uses and which Redis role(s) it connects to so configuration drift is easy to detect during reviews. CI and configuration checks should ensure that:

- Services that only participate in coordination (for example Game Session) never use cache ACL users.
- Services that only use Cache/Rate-Limit Redis (for example Spring Cloud Gateway) never use coordination ACL users.
- No service is configured with an ACL user that can read/write both coordination and cache prefixes on the same deployment.

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

- Any break-glass write that mutates `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, or `tick-executor-lease:*` must be followed by a reset/cleanup scope that actually covers the mutated prefix before normal tick processing resumes:
  - For region-scoped families (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`), run a region- or tenant-scoped coordination reset as appropriate.
  - For tenant-scoped `remote:*`, run a tenant-scoped reset or an explicit tenant-scoped `remote:<tenantId>:*` cleanup workflow (with audit trail), not a region-only reset.
  - For session prefixes, follow session reset policy (region resets preserve `session:game:*` by default; tenant/cluster resets may invalidate sessions).
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

## Discovery and Version Discipline

- The coordination maintenance CLI is shipped as part of the normal build/release pipeline (for example as a small JVM application or script under `dev-tools/`); runbooks and Helm hooks should reference its **concrete entrypoint** (for example, `dev-tools/coord-maintenance.sh` or the corresponding Gradle task) so operators do not need to guess how to invoke it.
- Operators must only use a CLI version that matches the deployed services and Lua registry:
  - If the CLI build version does **not** match the image tag or Git commit used for the running deployment, do not attempt coordination repairs; instead, run the CLI from the same artifact version that produced the deployment or perform a coordinated upgrade.
  - Break-glass or manual `redis-cli` operations are not an acceptable substitute for a mismatched maintenance CLI; they still require a scoped coordination reset afterwards and should be treated as incident-only paths.

Runbooks that reference coordination repairs or resets should always call the maintenance CLI entrypoint explicitly and avoid embedding raw Redis commands.

## Static Checks for Ops Scripts

To keep operational scripts aligned with application code:

- Repository-wide checks are extended to cover:
  - `dev-tools/` and other maintenance directories.
  - Helm hooks and Kubernetes jobs that interact with Redis.
- CI fails when:
  - Ops scripts contain raw `EVAL`/`EVALSHA` against coordination deployments.
  - Scripts construct `tick:*`, `timer:*`, `retry:*`, `remote:*`, or `session:*` keys by hand instead of calling shared helpers.
  - Scripts hard-code Redis host/port or URLs instead of using the shared `RedisCoordConfig` / `RedisCacheConfig` helpers and role-specific environment variables.
  - Cache/rate-limit scripts introduce Redis prefixes that are not listed in the Cache/Rate-Limit Redis key catalog maintained in the Redis cache design docs (Redis cheat sheet plus `system-architecture-redis-cache.md`), or misuse those prefixes against the wrong Redis role.
  - Automation-related Lua or tooling scripts reference both `automation:*` and `tick:*` prefixes in a single operation, violating the automation cluster slotting rules in `system-architecture-redis-lua-patterns.md` and the Automation & Scripting service design.

Maintenance scripts that genuinely need to work with coordination keys must:

- Import the same key-builder APIs and Lua registry helpers used by services.
- Document their scope (which prefixes/tenants/regions they touch) and the runbook they implement.

This keeps human-driven maintenance and automation under the same discipline as regular application code, reducing the chance that debugging or emergency fixes introduce silent hash-tag or lock/lease violations.

In addition, scripts and runbooks are labelled by **target Redis role**:

- “Coordination ops” scripts may only use the coordination config and helpers; CI fails if they import cache-only helpers or reference cache URLs.
- Cache/rate-limit tooling may only use the cache config; CI fails if it imports coordination helpers.

This role-aware labelling keeps coordination and cache tooling clearly separated in both code and configuration.

For reset and migration procedures tied to these roles, see [Redis Operations & Migrations](./system-architecture-redis-operations.md), which defines the versioned coordination reset flows and AOF maintenance commands that ops users are expected to run.
