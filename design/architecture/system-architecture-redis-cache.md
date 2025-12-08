# FireMUD System Architecture: Redis Cache & Rate Limiting

Redis is already used for transient coordination (ticks, sessions, locks). This document describes how it will **eventually** back selected read-side caches and rate limiting as a performance optimization. It focuses on design principles, not an implemented feature set.

> 🔗 Tick locks, session coordination, and Lua-based execution are covered in
> [System Architecture: Redis](./system-architecture-redis.md). This document
> focuses on cache/rate-limit usage and separation from Coordination Redis.

## Core Principles

- Database and domain services remain authoritative. PostgreSQL and the owning microservices define the source of truth for entities, rooms, inventories, and configuration; Redis is a helper, not a primary store.
- Static/topology vs dynamic/runtime state:
  - Static or topology data (world geometry, room/zone graphs, published templates, configuration) changes infrequently and is a good fit for aggressive caching with long TTLs or manual invalidation.
- Dynamic runtime state (inventories, room occupants, transient effects, in-progress combat) changes frequently and must use careful invalidation rules and short-lived caches, if cached at all.
- Purpose-driven caching only. Objects should be cached because they are expensive to compute or fetch and appear on hot paths, not “just in case.” Profiling and production telemetry will drive what actually lands in Redis.
- Coordination workload isolation:
  - In **QA, staging, and production** (any environment used for load tests or player traffic), read-side caches and gateway rate limits are placed on one or more **separate Redis cache/rate-limit deployments**, distinct from the **Coordination Redis** used for ticks, locks, timers, and sessions. Coordination Redis does **not** host caches or rate-limit keys in these environments.
  - In **local developer** and other low-concurrency lab setups, a single Redis instance may be shared for convenience (see below), but this topology is not considered representative for performance or failure behavior and must not be reused for QA, staging, or production.

## Candidate Cacheable Object Types

This list is planning-only; it documents candidate categories rather than a final decision. Concrete cache choices will be revisited once profiling data and cross-service load metrics are available.

- Static or rarely changing data:
  - World topology slices (room adjacency, precomputed path segments, region metadata that rarely changes).
  - Published templates and configuration (ability definitions, item templates, world-generation parameters) copied from the Game Design Service into runtime schemas.
  - Feature-flag and version metadata that is read frequently but updated only at publish/activation time.
- Dynamic aggregates that are expensive to compute:
  - “Current view” of a room or instance: an aggregate combining world topology, visible entities, and room-ground inventory assembled for LOOK and similar commands.
  - Cross-entity aggregates such as “all entities in a shard/region with a given tag” used for scripted world events or analytics-style queries.
- Dynamic but localized aggregates:
  - Inventories and containers (player inventory, equipment, banks, room-ground containers) where reads are common and writes are scoped to a single aggregate root.
  - Per-entity views (for example, “effective stats” after applying all modifiers) that are expensive to recompute but naturally tied to a single character or NPC.

## Version-Based Cache Validation

Some dynamic aggregates will be easier to cache if the authoritative store exposes a version or `lastModified` field per aggregate root:

- The owning service (for example Entity Management or World Management) maintains a version counter or timestamp on the aggregate root (such as a container, character effective stats, or a room’s dynamic state row) and increments or updates it whenever the aggregate changes.
- Redis entries for that aggregate store both version and payload together, typically inside a single serialized object.
- When fetching, callers can:
  - Read the current version from the authoritative store or a lighter-weight index.
  - Compare it with the version in Redis.
  - Reuse the cached payload if versions match, or recompute and overwrite the cache if they differ.
- Versioning is applied per aggregate root (for example `inventory:{tenantId}:{containerId}` or `roomDynamic:{tenantId}:{roomId}`) rather than being added indiscriminately to every table or DTO.

This pattern keeps cache correctness bounded to clearly defined aggregates and avoids random, hard-to-reason-about version fields scattered across the schema.

### Decision Criteria for Versioning

Not every cached aggregate needs its own version column. Apply these heuristics before adding schema baggage:

- Reuse an existing `lastModified`/`version` field from the authoritative store whenever possible; no schema change is required.
- Add a version column only when cache invalidation currently causes stale reads that TTLs alone cannot fix (for example, players seeing an old inventory after a trade).
- Prefer **TTL-only** caches for highly volatile but non-critical aggregates; shorter TTLs are easier to tune than inventing new schema.
- Document each decision so reviewers understand why a cache entry carries version metadata versus relying on TTLs.

## Invalidation Strategies

Future cache layers are expected to combine several invalidation mechanisms, tuned per aggregate:

- TTL-based expiry:
  - Primary role is as a safety valve and memory-bloat control, not the main correctness mechanism.
  - Long TTLs may be acceptable for static/topology slices; short TTLs can bound staleness for dynamic aggregates in low-risk flows.
  - Implementations must enforce **per-key TTL budgets** in configuration so caches cannot silently accumulate effectively permanent entries; long-lived keys should be rare, documented exceptions.
- Event-based invalidation:
  - When authoritative state changes, the owning service emits domain events (for example: inventory changed, room-dynamic state changed, entity moved, template version activated).
  - A cache layer or a dedicated listener reacts to those events and either deletes affected keys or overwrites them with fresh values.
- Version check (where applicable):
  - For aggregates that expose versions, application code may choose to read version and payload from Redis and compare with the current authoritative version.
  - On mismatch, the cache entry is recomputed and updated atomically (value plus TTL) before being reused.

This pattern keeps cache correctness bounded to clearly defined aggregates and avoids random, hard-to-reason-about invalidation scattered across services.

### Cache Correctness Classes

From a correctness perspective, cache usage falls into two broad classes:

- **Class A – correctness-critical caches** (for example, inventories shown to players, room occupants that drive combat/visibility decisions):
  - Must use **event-based invalidation and/or version checks** as described above.
  - May add TTLs, but **TTL alone is never considered sufficient** for correctness; a Class A cache must remain correct even if TTLs are set very large.
  - Aggregates that cannot provide events or versions should treat Redis as a pure performance optimization (for example, per-request in-memory caching) or avoid caching that aggregate entirely.
- **Class B – best-effort/performance caches** (for example, analytics-style aggregates, debug views, or non-player-facing summaries):
  - May rely on **TTL-only** invalidation, as long as occasional staleness is acceptable for the use case.
  - Still must respect per-key TTL budgets and size limits so they cannot starve Class A caches or coordination workloads.

To avoid noisy-neighbor effects on coordination workloads, cache writers must also enforce **per-value size limits** and avoid unbounded lists or blobs in Redis:

- Cap serialized values to a practical ceiling (for example, roughly 32 KB or two protobuf pages) before writing them to Redis. If an aggregate such as a “current room view” would exceed that size, split it into a set of chunked entries (for example `view:roomLook:{tenantId}:{roomId}:chunk:{n}`) instead of storing a multi-megabyte blob.
- CI or static checks should exercise representative payloads to keep them within these limits, and reviewers must explicitly justify any intentional exception.
- Large or streaming-style responses stay in PostgreSQL/object storage or behind dedicated APIs rather than being replicated wholesale into Redis, even on the Cache/Rate-Limit cluster.

## Memory, Eviction, and Rate Limiting

Memory and eviction behavior for cache and rate-limit workloads must not compromise Coordination Redis:

- The **Coordination Redis** deployment:
  - Uses a `maxmemory-policy` of `noeviction` so that coordination keys are never removed to make room for caches.
  - Is sized with sufficient `maxmemory` (and headroom) to accommodate expected tick, lock, timer, and session state plus operational buffers. As a rule of thumb:
    - Estimate the worst-case coordination footprint as:
      - `coord_bytes = regions * (locks_per_region * avg_lock_bytes + timers_per_region * avg_timer_bytes + pending_bytes_per_region + session_bytes_per_region)`.
      - Apply a safety factor of at least **2–3×** (`coord_bytes * SAFETY_FACTOR`) to account for spikes, fragmentation, and unforeseen growth.
    - Keep coordination prefixes (`tick:*`, `session:*`, `timer:*`, `retry:*`) under a target fraction of `maxmemory` (for example, <30–40%) and treat sustained growth beyond that as a sizing or design issue.
  - Does **not** store large cache payloads or unbounded aggregates; those belong in the Cache/Rate-Limit cluster or in PostgreSQL/object storage. Any exception must:
    - Use a distinct, clearly documented prefix (for example, `coordCache:`).
    - Respect strict per-key size limits and a small aggregate memory budget.
    - Have an explicit incident plan if it contributes to memory pressure.
  - Treats any `OOM`/`OUT OF MEMORY` write error for coordination commands as a **critical failure condition**: the Game Session Service and other coordination clients:
    - Detect write failures from Redis clients (including Lua script results) instead of ignoring them.
    - Log structured errors and increment metrics (for example `redis.coordination_oom_errors`).
    - Mark affected regions as degraded or temporarily halt new ticks/lock acquisitions until operators resolve the underlying memory issue.
- The **Cache/Rate-Limit Redis** deployment:
  - May use an eviction policy such as `allkeys-lru` or `volatile-lru`, since entries are recomputable or best-effort.
  - Enforces strict limits on value size and TTL so cache growth does not starve rate limiting or degrade performance.

For **small or development deployments** that share all workloads on a single Redis cluster:

- This configuration is intended for **low-concurrency lab and developer environments only**, not for QA, staging, production, or any player-facing game instances.
- The configuration should still avoid mixing large, eviction-driven caches with critical coordination keys under `allkeys-*` policies, because it can silently evict locks, timers, or staging keys.
- Even with `maxmemory-policy noeviction`, conservative cache TTLs, and tight cache size limits, shared coordination+cache Redis remains **operationally fragile**: any mis-sized cache or unexpected hot key can push the node into `OOM` conditions where coordination writes begin to fail.
- If a single-node instance must serve both coordination and cache traffic in a local/dev setup, it is strongly recommended to:
  - Use `maxmemory-policy noeviction`, with very small, well-bounded caches used purely for development convenience; and
  - Move to separate logical Redis instances (for example, two containers or pods) as soon as a scenario moves beyond low-volume, single-user testing so coordination and cache eviction policies can diverge even on the same host.

Operational dashboards track `used_memory`, `maxmemory`, and eviction counters for each deployment. In addition:

- Alert when a single cache key (or tenant bucket) begins consuming a disproportionate share of the namespace (for example, dominating the delta of `used_memory` or generating most of the eviction events).
- Alert when eviction counters climb steadily while `keyspace_hits` drop or `blocked_clients` rise, which usually signals a hot key or TTL that is too long.
- Alert when memory usage grows despite expected TTL decay so cache scans can be inspected before coordination workloads are affected.

These alerts keep you aware of misconfigurations early without hard-coding percentages that don’t make sense at hobby scale.

Rate limiting keys (for example those used by Spring Cloud Gateway’s `RequestRateLimiter`) should be designed to avoid **hot keys** under heavy load:

- Per-client or per-token prefixes are preferred over global counters so that no single key receives a disproportionate share of traffic.
- Define a canonical pattern such as `ratelimit:{tenantId}:{bucket}:{timeWindow}` (for example, `bucket` may be a hash of the client/token plus an optional slice) and publish helper builders so services reuse the same bucketing logic instead of inventing divergent, hotspot-prone schemes.
- Support more granular sub-bucketing where heads-on credentials are unavoidable (for example `ratelimit:{tenantId}:{bucket}:{timeWindow}:{shard}`) to spread aggregates across multiple keys within the rate-limit Redis cluster.

### Key Naming and Overwrite Expectations

Cached aggregates in Redis should follow structured, namespaced key patterns to keep responsibilities clear and enable targeted invalidation. Examples (subject to refinement):

- `inventory:{tenantId}:{containerId}` – cached view of a single inventory or container (including room-ground containers).
- `worldDynamic:{tenantId}:{aggregateId}` – cached view of room-level dynamic state or other world-scoped aggregates.
- `view:roomLook:{tenantId}:{roomId}` – cached rendered or pre-assembled room “view” data serving LOOK or similar commands.

Expectations:

- Writing a new value for a key overwrites the previous entry. Cache writers do not attempt to merge old and new payloads inside Redis.
- Writers are encouraged to set TTLs as part of the same write operation (for example using `SET key value EX ttl` or a Lua script) so value and expiry are updated atomically.
- Future implementations should prefer single atomic commands or scripts (set value and TTL together, optionally with version) over multi-step delete plus set sequences unless there is a very specific, documented reason (such as maintaining backward-compatible behavior during a migration).

## Future Work / TODO

This section captures design intent only; concrete decisions are explicitly deferred. Before caching is implemented broadly, we need to:

- Decide which aggregates (if any) are actually cached for each service (Entity Management, World Management, Game Session, Game Logic, and others).
- Decide which aggregates receive a dedicated version or `lastModified` field for cache validation and how those fields are surfaced in their APIs.
- Define the domain events required to drive event-based invalidation (including payload shape, routing, and delivery guarantees).
- Add concrete examples, diagrams, and per-service subsections that show exactly how the chosen aggregates use Redis (key shapes, TTLs, version semantics, and listeners) once profiling and production telemetry justify their introduction.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)
