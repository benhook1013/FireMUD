# FireMUD Redis Usage & Profiles

This document describes **how** FireMUD uses Redis in different roles and environments. It complements the conceptual hub (`system-architecture-redis.md`) by defining concrete usage patterns, profiles, and configuration wiring.

---

## Table of Contents

- [Redis Roles and Usage Patterns](#redis-roles-and-usage-patterns)
- [Environment Profiles and Mappings](#environment-profiles-and-mappings)
- [Maxmemory, Eviction, and Sizing](#maxmemory-eviction-and-sizing)
- [Configuration Wiring and Misconfiguration Guards](#configuration-wiring-and-misconfiguration-guards)
- [Related Documentation](#related-documentation)

---

## Redis Roles and Usage Patterns

FireMUD runs two logical Redis roles in all non‑trivial environments:

- **Coordination Redis**
  - Responsibilities:
    - Tick queues, locks, timers, and executor leases.
    - Gameplay session state and liveness bindings.
    - Retry metadata and conflict tracking.
    - Automation coordination structures that participate in tick timelines.
  - Characteristics:
    - Treated as a **long‑lived coordination log** in persistent environments.
    - AOF enabled in `dev_local`, `hobby_self_hosted`, and `production_clustered`–like profiles.
    - Subject to tail‑loss SLOs and replay guarantees described in the Redis hub doc.
  - Example prefixes:
    - `tick:{tenantRegionTag}:*`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`
    - `session:game:<tenantId>:<sessionId>`
    - Automation coordination prefixes that follow shard‑local rules.

- **Cache/Rate‑Limit Redis**
  - Responsibilities:
    - Read‑side caches for expensive aggregates (room views, inventories, topology slices).
    - Rate‑limit buckets (`ratelimit:*`) and small operational counters.
    - Best‑effort automation queues and quotas that can be rebuilt from domain state.
  - Characteristics:
    - Treated as **non‑authoritative** and fully reset‑tolerant.
    - Eviction and TTL are part of normal behavior; designs must tolerate cold caches.
  - Example prefixes:
    - `inventory:<tenantId>:<containerId>`
    - `view:room-look:<tenantId>:<roomId>`
    - `world-dynamic:<tenantId>:<aggregateId>`
    - `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]`
    - `automation:queue:<tenantId>:<entityId>` and automation quota counters.

New prefixes must declare:

- Which role they live on (Coordination vs Cache/Rate‑Limit),
- Whether they are reset‑tolerant, reset‑sensitive, or reset‑forbidden, and
- How they behave under tail‑loss and eviction.

The **Redis Cheat Sheet** keeps a representative mapping from prefixes to roles and owning services.

---

### Redis Usage by Service

The following table summarizes how core services interact with Coordination Redis and Cache/Rate‑Limit Redis. Per‑service design docs expand on these responsibilities.

| Service | Redis Usage |
| --- | --- |
| **Game Session Service** | Owns **Coordination Redis**: tick queues, locks, timers, retry metadata, region leases, and Redis‑backed session state used for reconnection. All tick/coordination key prefixes and their Lua scripts are registered and owned here. |
| **Automation & Scripting Service** | Participates in coordination via **registered Lua helpers** and automation‑specific staging keys (`automation:tick:{tenantScriptTag}:...`) but does **not** own tick queues or locks directly. It reads tick heartbeats via gRPC and uses **Cache/Rate‑Limit Redis** for script quotas and best‑effort internal queues where documented. |
| **Spring Cloud Gateway** | Uses **Cache/Rate‑Limit Redis** for token‑bucket rate limiting and best‑effort caches only; it never touches tick/coordination prefixes directly and always connects via the cache profile configured in `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`. |
| **Other microservices (Game Logic, Entity Management, World Management, Social & Groups, etc.)** | Do not define or own coordination prefixes; they participate in Coordination Redis **only** through shared helpers and Lua descriptors owned by Game Session (for example, `tick:{tenantRegionTag}:lock:<entityId>` for tick locks). Where they cache read‑heavy aggregates, they use **Cache/Rate‑Limit Redis** and the key patterns from the Redis Cache & Rate Limiting design. |

These boundaries are part of the **Redis Coordination Invariants** described in `system-architecture-redis.md` and are enforced via shared key helpers, the Lua script registry, and CI tooling.

---

## Environment Profiles and Mappings

Redis deployments in FireMUD approximate one of three main profiles. Each environment (local dev, CI, staging, prod) documents which profile it uses.

### Profiles

- **`dev_local`**
  - Use case: single‑developer, non‑player‑facing environments.
  - Coordination Redis:
    - `appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`.
    - Modest `maxmemory` sized for laptops and local Docker.
    - Tail‑loss SLOs relaxed but invariants and key rules still enforced.
  - Cache/Rate‑Limit Redis:
    - May run without AOF and with more aggressive eviction.
    - Configuration emphasizes low friction over durability.

- **`hobby_self_hosted`**
  - Use case: small/self‑hosted FireMUD games with real players.
  - Coordination Redis:
    - `appendonly yes`, `appendfsync everysec` (or carefully documented alternative).
    - `aof-use-rdb-preamble yes`.
    - `maxmemory` sized so restarts normally complete within **30–60 seconds**.
  - Cache/Rate‑Limit Redis:
    - Sized for cache and rate‑limit workloads with eviction policies tuned for predictable behavior.
  - Tail‑loss SLOs and replay behavior are expected to match production‑like expectations, but at smaller scale.

- **`production_clustered`**
  - Use case: multi‑tenant or high‑scale deployments.
  - Coordination Redis:
    - Clustered or sharded deployments with AOF enabled.
    - Shard sizing aligned with tick workloads and tenant distributions.
    - Tail‑loss windows and restart budgets defined in SLOs and runbooks.
  - Cache/Rate‑Limit Redis:
    - Clustered or scaled deployments sized to keep cache and rate‑limit keys well within memory budgets.
    - Eviction policies tuned to preserve high‑value caches and token buckets.

### Environment Mappings

Each environment picks one of these profiles and documents the mapping:

- **Local development**
  - Approximates `dev_local`.
  - `docker-compose` and `./gradlew devUp` run:
    - `redis-coord` with AOF and a dedicated volume (Coordination Redis).
    - `redis-cache` without shared volumes (Cache/Rate‑Limit Redis).

- **CI and preview stacks**
  - Typically approximate `dev_local` or use an explicit **ephemeral coordination** profile:
    - Coordination Redis may run with reduced or disabled AOF where tests are fully reset‑tolerant.
    - These stacks are **not** used to validate tail‑loss SLOs or replay guarantees.

- **Staging and production**
  - Approximate `production_clustered`:
    - Coordination Redis with AOF and carefully sized shards.
    - Cache/Rate‑Limit Redis sized and monitored for cache and rate‑limit workloads.
  - Environment docs must record:
    - The chosen profile.
    - The concrete AOF, `maxmemory`, and clustering settings for each role.

When adding or modifying an environment, update its documentation to state:

- Which Redis profile it approximates, and
- How its concrete settings align with the targets above.

---

## Maxmemory, Eviction, and Sizing

Coordination and Cache/Rate‑Limit Redis are sized and configured differently.

### Coordination Redis

- **Goal:** predictable restart behavior and bounded memory usage for coordination keys.
- **Recommendations:**
  - Keep peak memory for coordination prefixes (`tick:*`, `timer:*`, `retry:*`, `session:*`, `tick-executor-lease:*`, etc.) well below available RAM (for example **≤ 50–60%** of memory).
  - Use AOF preamble and rewrite settings that keep AOF size within the budgets described in **Redis Operations & Migrations**.
  - Avoid eviction for coordination keys whenever possible; if `maxmemory` is configured with eviction, treat eviction events as incidents rather than normal operation.

### Cache/Rate‑Limit Redis

- **Goal:** predictable cache and rate‑limit behavior under eviction.
- **Recommendations:**
  - Choose `maxmemory` and `maxmemory-policy` values that:
    - Keep eviction focused on low‑value caches first.
    - Preserve high‑value caches and token buckets as long as possible.
  - Treat eviction as normal:
    - Cache writers must tolerate keys disappearing early.
    - Rate‑limit bucketing must tolerate dropped or reset bucket keys.
  - Use metrics to track:
    - Cache hit/miss rates by prefix.
    - Eviction counts and memory usage over time.

Concrete eviction policies and sizing guidelines are detailed in **Redis Cache & Rate Limiting**.

---

## Configuration Wiring and Misconfiguration Guards

All services and tools select Redis roles via configuration, not hard‑coded URLs:

- Coordination Redis:
  - `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT`  
    or `FIREMUD_REDIS_COORD_URL`.
- Cache/Rate‑Limit Redis:
  - `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`  
    or `FIREMUD_REDIS_CACHE_URL`.

Shared configuration helpers (for example in `firemud-common`) expose:

- `RedisCoordConfig` + `createCoordinationRedisClient(...)`
- `RedisCacheConfig` + `createCacheRedisClient(...)`

Requirements:

- **Role explicitness**
  - Every service and ops script must accept a specific role config (`RedisCoordConfig` or `RedisCacheConfig`), not arbitrary host/port strings.
  - Multi‑role tools that speak to both deployments must tag logs and metrics with `redis_role` (for example `coordination` vs `cache`).

- **Misconfiguration detection**
  - Configuration helpers should:
    - Detect when `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` resolve to the same endpoint in non‑ephemeral environments.
    - Emit a clear log warning and health indicator in that case.
  - Dashboards should include a simple “Redis role endpoints” view showing both roles per environment.

- **Test wiring**
  - Integration and cross‑service tests should:
    - Obtain endpoints from the same style of configuration (`FIREMUD_REDIS_COORD_*` / `FIREMUD_REDIS_CACHE_*`) but point them at Testcontainers.
    - Avoid hard‑coding secrets or production endpoints.

These wiring rules are enforced for ops scripts and maintenance tooling via **Coordination Redis Ops Access & Tooling** and CI checks.

---

## Related Documentation

- `system-architecture-redis.md` – conceptual hub for Redis roles, invariants, and key naming.
- `system-architecture-redis-cache.md` – cache and rate‑limit design, prefixes, and eviction guidance.
- `system-architecture-redis-operations.md` – AOF management, reset flows, and migration runbooks.
- `system-architecture-redis-ops-access.md` – ACLs, allowed commands, and CI checks for ops tooling.
- `system-architecture-redis-design-checklist.md` – concrete design checklist for Redis changes.
- `system-architecture-testing.md` – test strategy, including Redis usage in integration and cross‑service tests.
