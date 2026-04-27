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
    - Treated as a long-running **coordination buffer with bounded tail-loss** in persistent environments; durable history for tick effects and gameplay outcomes lives in PostgreSQL tick effect ledgers and domain stores.
    - Owned by the **Game Session Service** for gameplay coordination and gameplay session prefixes such as `tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`, and `session:game:*`; Account Service owns `session:auth:*`; Automation & Scripting Service owns automation-specific coordination prefixes as documented below.
    - AOF enabled in `dev_local`, `hobby_self_hosted`, and `production_clustered`–like profiles.
    - Subject to tail‑loss SLOs and replay guarantees described in the Redis hub doc.
  - Example prefixes:
    - `tick:{tenantRegionTag}:*`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`
    - `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
    - `sessionctx:*` bootstrap/session-context keys used by the current Game Session implementation.
    - Automation coordination prefixes that follow shard‑local rules.

- **Cache/Rate‑Limit Redis**
  - Responsibilities:
    - Read‑side caches for expensive aggregates (room views, inventories, topology slices).
    - Rate‑limit buckets (`ratelimit:*`) and small operational counters.
    - Best‑effort automation queues and quotas that can be rebuilt from domain state.
  - Characteristics:
    - Treated as **non‑authoritative** and fully reset‑tolerant.
    - Eviction and TTL are part of normal behavior; designs must tolerate cold caches.
    - Schema and TTL policies for cache and rate‑limit prefixes are defined centrally in shared infrastructure libraries rather than per service.
  - Example prefixes:
    - `inventory:<tenantId>:<containerId>`
    - `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>`
    - `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>`
    - `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]`
    - `automation:queue:{tenantInstanceTag}:<entityId>` and automation quota counters.

Coordination Redis and Cache/Rate‑Limit Redis are treated as **separate deployments** in all persistent, player-facing environments so cache eviction/pressure cannot silently impact coordination SLOs. The only supported exception is explicitly ephemeral test/CI stacks that opt out of tail-loss and role-separation guarantees; those stacks may collapse roles temporarily, but must be clearly labelled as ephemeral and must not be used to validate coordination behavior or SLOs. See [Environment Profiles and Mappings](#environment-profiles-and-mappings) for details.

New prefixes must declare:

- Which role they live on (Coordination vs Cache/Rate‑Limit),
- Whether they are reset‑tolerant, reset‑sensitive, or reset‑forbidden, and
- How they behave under tail‑loss and eviction.

The **Redis Cheat Sheet** keeps a representative mapping from prefixes to roles and owning services.

Keep the cheat sheet and the owning service Redis sections aligned with the canonical names in this document, especially:

- `tick:{tenantRegionTag}:session-binding:<entityId>`
- `binding_generation`
- `automationDispatchId`

### Automation & Scheduler Coordination Prefixes

A small set of automation/scheduler-specific prefixes live on Coordination Redis but remain **reset-tolerant**:

- `script-scheduler:{tenantRegionTag}:lastTickId`
  - Role: Coordination Redis.
  - Owner: Automation & Scripting Service.
  - Purpose: per-region checkpoint for “every N ticks” schedulers tied to the canonical `(regionEpoch, tickId)` timeline.
  - Reset behavior: classified as reset-tolerant in the reset policy matrix; region/tenant/cluster resets may drop this key. After resets or data loss, Automation & Scripting recomputes due work from PostgreSQL schedules and the tick heartbeat as described in the tick and scripting docs. Duplicate trigger prevention comes from a durable PostgreSQL trigger-instance or outbox key such as `(scheduleId, gameInstanceId, regionEpoch, dueTickId, triggerKind)` (or another explicitly instance-aware uniqueness projection), not from this Redis checkpoint.
- `automation:timer:{tenantRegionTag}`
  - Role: Coordination Redis.
  - Owner: Automation & Scripting Service.
  - Purpose: per-region timer/index structure for script intervals and timer-driven triggers; stored entries remain instance-aware via payload identity such as `gameInstanceId`.
  - Reset behavior: classified as reset-tolerant in the reset policy matrix; keys may be dropped by scoped coordination resets and are rebuilt from PostgreSQL-backed schedules, trigger-instance rows, and heartbeat progress.

Durable automation schedules, quotas, script configuration, and trigger-instance de-duplication live in PostgreSQL; these coordination prefixes are latency and progress hints only and must not be treated as the primary record of “which scripts should run”. Designs that introduce new automation-related coordination prefixes must register them in the reset policy matrix and document how they recover from resets.

The cheat sheet and the Automation & Scripting / Game Session service docs should also expose the operator-facing outcome vocabulary for fairness-critical automation admission so duplicate-dispatch no-ops, stale-timeline rejections, and successful admissions are recognizable without reading implementation code.

### Automation Routing: Coordination vs Cache/Rate-Limit

Automation workloads split into two broad classes, with different expectations and Redis roles:

- **Gameplay-equivalent, fairness-critical automation**
  - Examples: scripted commands that enter the same per-entity tick queues as player actions, “every N ticks” buffs or debuffs that affect combat state, automated movement or AI that must respect one-action-per-entity-per-tick fairness.
  - Redis role: **Coordination Redis**.
  - Prefixes: use the same coordination families as player commands (for example `tick:{tenantRegionTag}:queue:<entityId>`, `tick:{tenantRegionTag}:pending`, `timer:{tenantRegionTag}`, and scheduler checkpoints such as `script-scheduler:{tenantRegionTag}:lastTickId`), but only through Game Session-owned enqueue contracts.
  - Guarantees:
    - Subject to the same `(regionEpoch, tickId)` timeline, leases, and lock semantics as player commands.
    - Must respect the “one action per entity per tick” invariant and other tick fairness rules from the tick architecture docs.
  - Design rule: automation in this category must be reviewed like core gameplay logic and is **not** allowed to depend on TTL-only caches or best-effort queues for correctness.
  - Canonical handoff contract:
    - Automation & Scripting creates or reuses a durable PostgreSQL trigger-instance / outbox row keyed by a stable dispatch identity such as `(scheduleId, gameInstanceId, regionEpoch, dueTickId, entityId, commandKind)` or an equivalent derived `automationDispatchId`.
    - Automation & Scripting then calls a Game Session gRPC/API contract such as `EnqueueAutomationCommandIfAbsent`, carrying:
      - `automationDispatchId`
      - `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, `dueTickId`
      - `entityId`
      - the deterministic gameplay command payload
    - Game Session is the only service allowed to translate that contract into `tick:{tenantRegionTag}:queue:<entityId>` mutations.
    - Game Session first records the admission attempt in its durable command/admission ledger with a uniqueness constraint on `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` and the requested `dueTickId`, `entityId`, and command payload hash.
    - Game Session deduplicates on that durable admission record, not on Redis queue contents. Retries, duplicate gRPC delivery, or leader changes observe the existing admission record and return replay/no-op outcomes rather than second enqueues.
    - Only after the durable admission record is created or confirmed does Game Session invoke the region-lease Redis enqueue script for `tick:{tenantRegionTag}:queue:<entityId>`.
    - If the supplied `regionEpoch` is stale or the due tick is no longer valid for the active region lease, Game Session returns a non-applied outcome and Automation & Scripting re-derives the next action from durable trigger state instead of guessing from Redis.
  - Ordering point:
    - The durable trigger-instance / outbox row is the source of truth that the automation action became due.
    - The Game Session durable admission record is the source of truth that the due automation was accepted for gameplay admission.
    - The Game Session enqueue acknowledgement is the source of truth that the accepted automation was materialized into current Redis coordination for that region. If Redis enqueue fails after durable admission, Game Session retries materialization from the admission record or converges it to a terminal non-applied outcome under the same command-status rules used for player commands.
    - Redis keys are hot-path coordination state only; they are never the sole record that a fairness-critical automation action existed.
  - Worked example:
    - A schedule for NPC `entityId=E1` becomes due at `(tenantId=T1, gameInstanceId=G1, regionId=R1, regionEpoch=7, dueTickId=420)`.
    - Automation & Scripting upserts a durable trigger-instance row keyed by `(scheduleId=S1, gameInstanceId=G1, regionEpoch=7, dueTickId=420, entityId=E1, commandKind=MOVE)` and derives `automationDispatchId` from that identity.
    - It calls `EnqueueAutomationCommandIfAbsent` with `automationDispatchId`, the target region timeline fields, and the deterministic command payload.
    - Game Session inserts or reads the durable admission row for `(T1, G1, R1, 7, automationDispatchId)`, validates the active region lease and epoch for `R1`, maps the request to `tick:{tenantRegionTag}:queue:E1`, and returns `"ENQUEUED"` on the first successful materialization.
    - If the same trigger retries due to gRPC timeout, Game Session sees the same durable admission row and returns a replay/no-op outcome instead of enqueuing a second command.
    - If the region has already moved to `regionEpoch=8`, Game Session returns a stale-timeline outcome and Automation & Scripting re-derives what should happen next from the durable trigger-instance row instead of inferring state from Redis.

- **Best-effort, non-critical automation**
  - Examples: analytics-style background work, non-critical notifications, opportunistic refreshes that can be dropped or reordered without visible gameplay impact.
  - Redis role: **Cache/Rate-Limit Redis**.
  - Prefixes: `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:*`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*`, and similar TTL-only queues and counters documented in the Redis Cache & Rate Limiting design.
  - Guarantees:
    - Treated as **TTL-only, reset-tolerant** hints: items may be dropped, duplicated, or processed late.
    - Correctness (for example, “was this workflow triggered at least once?”) must come from durable trigger tables and idempotent domain logic, not from the queue contents.
  - Design rule: features that require strong ordering, fairness relative to player actions, or at-least-once guarantees **must not** rely solely on `automation:queue:*`; they either belong in the coordination category above or should use tick-adjacent outbox/saga patterns.

When designing new automation, authors must explicitly state which category it belongs to and which Redis role/prefixes it uses, and link to the relevant tick or cache sections. Reviews should push gameplay-equivalent automation toward coordination prefixes and reserve cache-based queues for truly best-effort workloads.

---

### Redis Usage by Service

The following table summarizes how core services interact with Coordination Redis and Cache/Rate‑Limit Redis. Per‑service design docs expand on these responsibilities and describe any participation in coordination via shared helpers (for example, tick locks or auth/session keys) even when a service does not own coordination prefixes itself.

| Service | Redis Usage |
| --- | --- |
| **Game Session Service** | Owns **Coordination Redis**: tick queues, locks, timers, retry metadata, region leases, and Redis‑backed session state used for reconnection. All tick/coordination key prefixes and their Lua scripts are registered and owned here. |
| **Automation & Scripting Service** | Owns automation-specific prefixes such as `automation:queue:{tenantInstanceTag}:*`, `automation:timer:{tenantRegionTag}`, and `script-scheduler:{tenantRegionTag}:lastTickId`, but does **not** own gameplay `tick:*` queues or locks. It reads tick heartbeats via gRPC, uses PostgreSQL as the durable work source of truth, and uses **Cache/Rate‑Limit Redis** for script quotas and best-effort queue projection where documented. |
| **Spring Cloud Gateway** | Uses **Cache/Rate‑Limit Redis** for token‑bucket rate limiting and best‑effort caches only; it never touches tick/coordination prefixes directly and always connects via the cache profile configured in `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`. |
| **Other microservices (Game Logic, Entity Management, World Management, Social & Groups, etc.)** | Do not define or own coordination prefixes; they participate in Coordination Redis **only** through shared helpers and Lua descriptors owned by Game Session (for example, `tick:{tenantRegionTag}:lock:<entityId>` for tick locks). Where they cache read‑heavy aggregates, they use **Cache/Rate‑Limit Redis** and the key patterns from the Redis Cache & Rate Limiting design. |

These boundaries are part of the **Redis Coordination Invariants** described in `system-architecture-redis.md` and are enforced via shared key helpers, the Lua script registry, and CI tooling.

---

## Environment Profiles and Mappings

Redis deployments in FireMUD approximate one of three main profiles. Each environment (local dev, CI, staging, prod) documents which profile it uses and whether it behaves as an **ephemeral** stack for tail-loss and role-separation guarantees.

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
  - Always runs **two separate Redis deployments** (for example `redis-coord` and `redis-cache` in Docker Compose) so role separation is exercised even on laptops.
  - `docker-compose` and `./gradlew devUp` run:
    - `redis-coord` with AOF and a dedicated volume (Coordination Redis).
    - `redis-cache` without shared volumes (Cache/Rate‑Limit Redis).
  - This environment is **non‑ephemeral** for role separation: pointing `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` to the same endpoint is treated as a misconfiguration.

- **CI and preview stacks**
  - Typically approximate `dev_local` or use an explicit **ephemeral coordination** profile:
    - Coordination Redis may run with reduced or disabled AOF where tests are fully reset‑tolerant.
    - These stacks are **not** used to validate tail‑loss SLOs or replay guarantees.
  - In truly ephemeral CI stacks it is acceptable to collapse roles into a single Redis instance **only** when:
    - Tests are explicitly designed to be reset‑tolerant and do not exercise coordination tail‑loss behavior.
    - The environment is clearly labelled as “ephemeral / single-Redis” in its documentation and configuration.
    - Misconfiguration checks and dashboards still surface the fact that roles are sharing an endpoint so it cannot be mistaken for a production-like topology.

- **Staging and production**
  - Approximate `production_clustered`:
    - Coordination Redis with AOF and carefully sized shards.
    - Cache/Rate‑Limit Redis sized and monitored for cache and rate‑limit workloads.
  - These environments are **non‑ephemeral**:
    - Coordination and Cache/Rate‑Limit Redis must always be distinct deployments.
    - Any attempt to point both roles at the same endpoint is treated as a hard failure in configuration checks and health indicators.
  - Environment docs must record:
    - The chosen profile.
    - The concrete AOF, `maxmemory`, and clustering settings for each role.

When adding or modifying an environment, update its documentation to state:

- Which Redis profile it approximates.
- Whether it is allowed to behave as an ephemeral/single-Redis stack for tests.
- How its concrete settings align with the targets above.

---

## Maxmemory, Eviction, and Sizing

Coordination and Cache/Rate‑Limit Redis are sized and configured differently.

### Coordination Redis

- **Goal:** predictable restart behavior and bounded memory usage for coordination keys.
- **Recommendations:**
  - Keep peak memory for coordination prefixes (`tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:*`, `tick-executor-lease:*`, etc.) within the canonical Coordination Redis budget from `system-architecture-redis-operations.md` (normally **≤ 30–40% of `maxmemory`**).
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
    - Detect when `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` resolve to the same endpoint.
    - Emit a clear log warning and a failing health indicator in all **non‑ephemeral** environments (`dev_local`, staging/prod, long‑lived hobby/self‑hosted); services must not treat a single shared Redis instance as a valid topology for those roles.
    - In explicitly marked ephemeral CI stacks, still log and surface this sharing in metrics so it is visible, but do not fail health checks solely for that reason.
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
