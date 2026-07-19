# FireMUD System Architecture: Redis Cache & Rate Limiting

Redis is already used for transient coordination (ticks, sessions, locks). This document describes how **Cache/Rate-Limit Redis** backs selected read-side caches and rate limiting as a performance optimization. It focuses on cache ownership, invalidation rules, consistency expectations, and canonical cache policy. The detailed prefix catalog, worked examples, reference tables, adoption checklist, and testing guidance live in [Redis Cache & Rate Limiting Reference](./system-architecture-redis-cache-reference.md).

The canonical cache policy in this file and the reference catalog in the companion document must stay aligned with the global reset policy matrix in `system-architecture-redis-reset-and-recovery.md`.

> ℹ️ **Implementation status**
>
> - Spring Cloud Gateway’s rate limiting is wired to Cache/Rate-Limit Redis today using the patterns in this document.
> - Other services reference these cache and aggregate patterns (for example, Entity Management character caches and World Management room caches) as target-state behavior; concrete cache adoption may evolve over time while continuing to follow these rules.
>
> 🔗 Tick locks, session coordination, and Lua-based execution are covered in
> [System Architecture: Redis](./system-architecture-redis.md). Usage patterns,
> environment profiles, and role wiring are described in
> [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md).
> This document focuses on cache/rate-limit policy and separation from Coordination Redis. For a concise overview of prefixes, roles, and owning services, see the [Redis Cheat Sheet](./system-architecture-redis-cheatsheet.md). For the detailed prefix catalog and reference material, see [Redis Cache & Rate Limiting Reference](./system-architecture-redis-cache-reference.md).

## Default Operator Knobs

- Which aggregates are cached at all, and whether they are **versioned** or **TTL-only** (per-prefix correctness class).
- Cache TTL ranges and high-level size/pressure budgets on Cache/Rate-Limit Redis (not per-tenant micro-tuning).

All other controls (for example, per-tenant heuristics, noisy-tenant detection strategies, or advanced bucketing schemes) are **advanced** and should normally stay at their documented defaults unless a concrete production need is demonstrated.

## Core Principles

- Database and domain services remain authoritative. PostgreSQL and the owning microservices define the source of truth for entities, rooms, inventories, and configuration; Redis is a helper, not a primary store and must never be treated as an independent source of truth for gameplay outcomes or financial transactions.
- Static/topology vs dynamic/runtime state:
  - Static or topology data (world geometry, room/zone graphs, published templates, configuration) changes infrequently and is a good fit for aggressive caching with long TTLs or manual invalidation.
- Dynamic runtime state (inventories, room occupants, transient effects, in-progress combat) changes frequently and must use careful invalidation rules and short-lived caches, if cached at all.
- Purpose-driven caching only. Objects should be cached because they are expensive to compute or fetch and appear on hot paths, not “just in case.” Profiling and production telemetry will drive what actually lands in Redis.
- Coordination workload isolation:
  - All environments, including local development and small self-hosted setups, run **at least two Redis roles**:
    - A **Coordination Redis** deployment dedicated to ticks, locks, timers, sessions, and other gameplay-critical coordination state.
    - A **Cache/Rate-Limit Redis** deployment dedicated to read-side caches and gateway rate limits.
  - Coordination Redis must not host large, eviction-driven caches under any profile. Even in development and hobby/self-hosted profiles, caches and rate limits are pointed at the separate Cache/Rate-Limit deployment so eviction and OOM behavior cannot silently affect coordination keys. The only supported exceptions are explicitly ephemeral test stacks that opt out of tail-loss and role-separation guarantees; see `system-architecture-redis-usage-and-profiles.md` for environment profiles and mappings.
- No soft coordination logs on Cache/Rate-Limit Redis:
  - Tick ordering, tick idempotency, and any correctness or fairness invariants for gameplay **must not** depend on cache or rate-limit keys. Cache/Rate-Limit Redis may only influence latency and load, never “what happened” or “in which order” from the tick engine’s perspective.
  - Automation and scripting structures on Cache/Rate-Limit Redis (for example `automation:queue:*`, `automation:quota:*`, `automation:tenant-budget:*`, `automation:test:capacity:*`) are explicitly documented as best-effort buffers and counters; they cannot act as authoritative logs or effect ledgers. Durable automation schedules and quotas live in PostgreSQL; cache entries merely accelerate lookups and quota checks.
  - If a new feature appears to need a durable or authoritative log for tick- or session-driven workflows, that log belongs in PostgreSQL (for example as a ledger or follow-up table) or, in rare cases, on Coordination Redis with explicit reset/tail-loss rules—not on Cache/Rate-Limit Redis.

### Forbidden Patterns (Cache/Rate-Limit Redis)

To keep Cache/Rate-Limit Redis clearly separate from coordination concerns:

- Cache and rate-limit keys **must not encode the coordination timeline**:
  - Key names and core fields must not include `tickId`, `region_epoch`, or other identifiers that participate directly in tick ordering or idempotency invariants.
  - Any design that requires tick-aligned history or logs belongs in PostgreSQL (ledger/follow-up tables) or, where appropriate, Coordination Redis under the tick prefix families.
- Tick and domain handlers **must not branch behavior on cache hits**:
  - Cache presence (for example, an `inventory:*` or `view:*` hit) may affect latency, but may not change “what happens” or “in which order” from the tick engine’s perspective.
  - Handlers must compute the same logical effects regardless of whether relevant cache entries are present; caches may only short-circuit lookups, not drive different code paths with different outcomes.
- Cache keys must not be used as soft coordination logs:
  - `automation:queue:*`, `automation:quota:*`, `automation:tenant-budget:*`, `automation:test:capacity:*`, `chat:*`, and similar prefixes remain best-effort buffers and counters; they cannot become the only record of “which effects were applied” or “which commands ran”.
  - If evolution of a feature starts to require durable sequencing, migrate that responsibility into a ledger table or explicit coordination structure and update this catalog accordingly.

## Candidate Cacheable Object Types

This section catalogs the primary categories of objects that services cache in (or plan to cache in) Cache/Rate-Limit Redis. Concrete adoption per service is driven by profiling data and cross-service load metrics, but key shapes and invalidation strategies follow this design.

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

### Built-In Gameplay View Cache Subsystem

Some derived caches are better understood as a small built-in gameplay view cache subsystem rather than as one-off command optimizations.

- Scope:
  - canonical platform-provided read/redraw commands only;
  - examples include `LOOK` today and may later include inventory, equipment, status/score, or map-style redraw views when those become stable built-in platform surfaces.
- Non-scope:
  - arbitrary game-scripted commands;
  - transient action acknowledgements such as speech, combat, item transfer confirmations, or admin mutation responses.
- Ownership:
  - Game Logic (or another authoritative gameplay orchestrator) owns the structured result for the underlying read;
  - Game Session owns any client-facing rendered transcript cache used for reconnect replay, UI restoration, or short-lived repeated reads.
- Safety rules:
  - cached view payloads are derived and disposable, never authoritative;
  - they may improve latency or reconnect redraw experience, but they must not change gameplay semantics;
  - each cached built-in view must opt in explicitly with a documented key shape, TTL, invalidation source, and replay/use rules rather than inheriting from a generic “all commands are cacheable” framework;
  - cached room-view payloads should preserve the same top-level structure as the authoritative view contract (for example room prose, exits, occupants, room-ground items, and optional overlays) so reconnect/UI redraw does not invent a second ad hoc shape.

## Version-Based Cache Validation

Some dynamic aggregates will be easier to cache if the authoritative store exposes a version or `lastModified` field per aggregate root. Others are naturally best-effort and can tolerate occasional stale reads under simple TTL-based eviction. To keep designs consistent and reviewable, caches are grouped into two classes:

[ADR 0086](decisions/adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md) defines the authority boundary: a Class A entry may serve a correctness-sensitive read only inside the owning service and only when that operation proves the complete cached scope and version/fence are current. Other services use the owner's API rather than reading its Redis keys. Cache TTL, cache-embedded version, or invalidation receipt alone is not currentness proof, and authoritative mutation preconditions still apply after a cached read.

- **Strongly validated caches (versioned)** – payloads that are validated against a version or `lastModified` value stored in the authoritative system:
  - The owning service (for example Entity Management or World Management) maintains a version counter or timestamp on the aggregate root (such as a container, character effective stats, or a room’s dynamic state row) and increments or updates it whenever the aggregate changes.
  - Redis entries for that aggregate store both version and payload together, typically inside a single serialized object.
  - When fetching, callers can:
    - Read the current version from the authoritative store or a lighter-weight index.
    - Compare it with the version in Redis.
    - Reuse the cached payload if versions match, or recompute and overwrite the cache if they differ.
  - If the authoritative version/fence cannot be proven, the owner falls back to its authoritative read or fails closed; it never uses the entry as stale truth.
- Versioning is applied per aggregate root (for example `inventory:<tenantId>:<containerId>`), not per field, and is treated as part of the aggregate’s API contract.

- **Best-effort caches (TTL-only)** – payloads that are inexpensive to recompute or where occasional staleness is acceptable:
  - Entries are written with a TTL that bounds staleness; readers accept that data may lag behind the source of truth within that window.
  - No explicit version field is required in the cache payload.
  - Cache invalidation may still be event-driven (for example, delete-on-change) but correctness does not depend on precise invalidation timing.

Designs and reviews must explicitly record which class each cacheable aggregate belongs to (strongly validated vs best-effort) and which invalidation pattern it uses, so reviewers understand why a cache entry carries version metadata versus relying on TTLs.

### Decision Criteria for Versioning

Not every cached aggregate needs its own version column. Apply these heuristics before adding schema baggage:

- Reuse an existing `lastModified`/`version` field from the authoritative store whenever possible; no schema change is required.
- Add a version column only when cache invalidation currently causes stale reads that TTLs alone cannot fix (for example, players seeing an old inventory after a trade).
- Prefer **TTL-only** caches for highly volatile but non-critical aggregates; shorter TTLs are easier to tune than inventing new schema.
- Document each decision so reviewers understand why a cache entry carries version metadata versus relying on TTLs.

### Invalidator-of-Record Matrix

To keep cache behavior predictable across services, each cached aggregate designates a clear **invalidator of record** and relies on one of a small set of patterns:

- **Static / rarely-changing aggregates** (for example, world topology slices, published templates, feature-flag metadata)
  - **Invalidator of record:** the owning service’s publish/update path.
  - Preferred strategy: **event-driven or manual invalidation** plus a relatively long TTL.
    - When topology or config changes, the owning service emits a domain event or calls a small invalidation helper to drop/refresh the corresponding cache keys.
    - TTLs act as a safety valve, not the primary correctness mechanism.
- **Dynamic but correctness-critical aggregates** (Class A caches such as inventories, room occupants, or per-entity effective stats that drive gameplay decisions)
  - **Invalidator of record:** the owning service that persists the authoritative aggregate (for example Entity Management or World Management).
  - Preferred strategy: **event-driven and/or version-based validation**; TTLs are only a backup.
    - On writes, the owning service emits change events or updates a version/`lastModified` field.
    - Callers either:
      - Listen for events and invalidate affected keys, or
      - Use version-based reads as described above to refresh or reuse cache entries.
    - TTLs may still be set on keys, but **TTL alone is never considered sufficient** for correctness; Class A caches must stay correct even if TTLs were very large.
  - Aggregates that cannot provide events or versions should either:
    - Treat Redis as a pure performance optimization with per-request in-memory caches, or
    - Avoid Redis caching for that aggregate.
- **Dynamic best-effort / analytics / debug aggregates** (Class B caches such as analytics-style views, non-player-facing summaries, debug dashboards)
  - **Invalidator of record:** TTL configuration (operations).
  - Preferred strategy: **TTL-only** invalidation.
    - Occasional staleness is acceptable by design.
    - TTLs and size limits are tuned so these caches cannot starve Class A caches or coordination workloads.

When introducing a new cache, designs and reviews must explicitly record:

- Which class it belongs to (static, dynamic-critical/Class A, or dynamic-best-effort/Class B).
- Which invalidator-of-record pattern it uses (events, version-based, TTL-only).
- Why that choice is appropriate for its correctness and performance needs.

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

- **Class A – owner-validated correctness-sensitive read accelerators** (for example, inventory or room aggregates whose owning service can prove an exact current version/fence):
  - Must use **event-based invalidation and/or version checks** as described above.
  - May add TTLs, but **TTL alone is never considered sufficient** for correctness; a Class A cache must remain correct even if TTLs are set very large.
  - Only the authoritative owner consumes these Redis entries for correctness-sensitive reads. Aggregates that cannot provide trustworthy current-version/fence proof use authoritative reads or avoid Class A caching.
- **Class B – best-effort/performance caches** (for example, analytics-style aggregates, debug views, or non-player-facing summaries):
  - May rely on **TTL-only** invalidation, as long as occasional staleness is acceptable for the use case.
  - Still must respect per-key TTL budgets and size limits so they cannot starve Class A caches or coordination workloads.

Class B payloads may support declared presentation surfaces such as reconnect redraw or rendered `LOOK`, but never movement, combat, pathing, visibility, authorization, financial, or other correctness-sensitive decisions. A prefix changing from old TTL-only semantics to Class A must use an explicit key/payload migration or schema discriminator so old entries cannot be mistaken for validated state.

To avoid noisy-neighbor effects on coordination workloads, cache writers must also enforce **per-value size limits** and avoid unbounded lists or blobs in Redis:

- Cap serialized values to a practical ceiling (for example, roughly 32 KB or two protobuf pages) before writing them to Redis. If an aggregate such as a “current room view” would exceed that size, split it into a set of chunked entries (for example `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:chunk:<n>`) instead of storing a multi-megabyte blob.
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
    - The canonical threshold source is `system-architecture-redis-operations.md` (Redis SLOs & Budgets); this doc intentionally mirrors that value.
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

For **all deployments**, including local development:

- Coordination and cache/rate-limit roles run on **separate Redis deployments** (for example, two containers/pods on the same host or separate processes), even when serving a single developer or tenant.
- Docker Compose and Helm charts provide two services by default (for example `redis-coord` and `redis-cache`) so developers and operators never need to share a single Redis instance for both roles.
- This two-role split is intentionally kept **lightweight**: it mirrors larger clustered deployments while only adding configuration complexity, not additional infrastructure primitives, so hobby and self-hosted operators can follow the same patterns as production with minimal overhead.
- Any ad-hoc experiments that deliberately collapse roles into a single Redis instance are considered **unsupported** and outside the guarantees in this document; they must not be used for QA, staging, production, or any player-facing game instances.

Operational dashboards track `used_memory`, `maxmemory`, and eviction counters for each deployment. In addition:

- Alert when a single cache key (or tenant bucket) begins consuming a disproportionate share of the namespace (for example, dominating the delta of `used_memory` or generating most of the eviction events).
- Alert when eviction counters climb steadily while `keyspace_hits` drop or `blocked_clients` rise, which usually signals a hot key or TTL that is too long.
- Alert when memory usage grows despite expected TTL decay so cache scans can be inspected before coordination workloads are affected.

These alerts keep you aware of misconfigurations early without hard-coding percentages that don’t make sense at small self-hosted scale.

### Cache Safety Envelopes

Cache Redis is allowed to be noisy and eviction-driven, but it should still respect simple, global **safety envelopes** so operators can tell when it is over-subscribed:

- **Global cache pressure**
  - Treat sustained high eviction rates and near-`maxmemory` usage on Cache/Rate-Limit Redis as a signal that the cache is overfull, not as normal behavior.
  - A single, environment-wide threshold (for example, “evictions remain elevated for several minutes while `used_memory` hovers near `maxmemory`”) is enough to mark the cache as **under pressure**; the exact numeric values are tuned per deployment, not per tenant.
- **Relative noisy-tenant detection (only under pressure)**
  - Under cache pressure, dashboards should help identify whether one tenant or prefix family is dominating usage:
    - For example, by comparing approximate per-tenant bytes/keys (using prefix-scoped scans or exporter metrics) and highlighting tenants that account for most of the delta in `used_memory` or evictions.
  - This is a **relative heuristic**, not a hard quota: it is only meaningful when the cache as a whole is struggling, and it does not try to reserve a fixed percentage of memory per tenant.
- **Default behavior: observability over automatic throttling**
  - In the default, minimally configurable setup:
    - “Cache under pressure” and “noisy tenant” signals result in **metrics and alerts**, not automatic per-tenant throttling.
    - Operators decide whether to:
      - Reduce what is cached (for example, demoting some Class B aggregates to in-memory only).
      - Shorten TTLs or shrink payloads for particularly heavy prefixes.
      - Increase Cache Redis resources if justified.
  - More advanced deployments may optionally wire these signals into policy (for example, automatically shortening TTLs or shedding Class B cache writes when pressure/noisy-tenant conditions persist), but such behavior is an explicit opt-in and not part of the default design.

This approach keeps configuration minimal—a single notion of “cache under pressure” plus relative noisy-tenant hints—while giving operators concrete signals to act on when Cache Redis becomes a bottleneck.

Rate limiting keys (for example those used by Spring Cloud Gateway’s `RequestRateLimiter`) should be designed to avoid **hot keys** under heavy load:

- Per-client or per-token prefixes are preferred over global counters so that no single key receives a disproportionate share of traffic.
- Define a canonical pattern such as `ratelimit:<tenantId>:<bucket>:<timeWindow>` (for example, `bucket` may be a hash of the client/token plus an optional slice) and publish helper builders so services reuse the same bucketing logic instead of inventing divergent, hotspot-prone schemes.
- Support more granular sub-bucketing where heads-on credentials are unavoidable (for example `ratelimit:<tenantId>:<bucket>:<timeWindow>:<shard>`) to spread aggregates across multiple keys within the rate-limit Redis cluster.

Helpers for rate limiting must distinguish between:

- **Single-bucket operations** (default): each Redis command or script operates on exactly one `ratelimit:*` key and never attempts cross-key atomicity. These are the only Redis rate-limit operations that may live on a latency-sensitive path.
- **Bounded multi-signal policy evaluation**: an owning edge or credential policy may inspect a small declared set of independently updated subject and coarse-pressure buckets, then combine them while accepting bounded inter-read skew. Each mutation remains single-key; the result is not an exact cross-key quota.
- **Bulk multi-bucket inspection** (advanced): best-effort tooling that scans many buckets and must tolerate:
  - `CROSSSLOT` errors when running against Redis Cluster.
  - Non-atomic views of rate-limit state (for example, two keys changing while they are being read).

Shared helper APIs distinguish single-bucket mutation, bounded policy evaluation, and bulk observability. Bulk inspection remains observability-only; bounded policy evaluation is allowed only for an explicitly owned abuse policy and never promises a cross-slot atomic result.

#### Rate-Limit Bucket Design

Under [ADR 0087](decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md), an individual enforcement bucket represents one actual client, connection, account candidate, credential, token, source, or other declared subject. Its `bucket` segment is a normalized opaque stable hash that preserves one-to-one subject isolation; it is not `H(subject) mod N`, and raw credential or address material does not appear in keys or metric labels.

Bound active key count through TTLs, a fixed number of live windows, active-subject/admission limits, per-tenant and deployment memory budgets, and explicit overload behavior. Do not obtain boundedness by making unrelated subjects consume one another's allowance.

A small fixed shared bucket is allowed only when it deliberately represents a coarse tenant, endpoint, source-class, or global pressure signal. It cannot be described as per-subject fairness or act alone as an individual security consequence. Request-based sharding must not multiply an individual's effective enforcement allowance.

Every limiter declares its subject and owner, privacy-preserving key shape, window/TTL, cardinality and memory envelope, deliberately shared collision behavior, reset/eviction effect, store-unavailable behavior, and whether it is a heuristic or hard gate. A hard authorization, financial, durable-quota, or similar invariant cannot rely solely on evictable Cache/Rate-Limit Redis. Ordinary gameplay command limiting remains the in-process session-front-end mechanism defined by ADR 0034 and performs no Redis call per command solely for rate limiting.

Cluster slotting implications:

- Rate-limit keys are treated as **single-key mutations** from the cluster’s perspective; scripts or commands do not attempt atomic multi-key updates across different buckets or time windows.
- Rate-limit keys are treated as single-key operations; the design intentionally does not rely on Redis Cluster hash tags for rate limiting.
- Bounded multi-signal policy reads and bulk inspection tolerate `CROSSSLOT` and fall back to per-key operations; the design does not rely on cross-slot multi-key transactions for rate limiting.

### Key Naming and Overwrite Expectations

Cached aggregates in Redis should follow structured, namespaced key patterns to keep responsibilities clear and enable targeted invalidation. Examples (subject to refinement):

- `inventory:<tenantId>:<containerId>` – cached view of a single inventory or container (including room-ground containers).
- `character-cache:<tenantId>:<characterId>` – cached character graphs for hot reads.
- `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` – cached room-level dynamic state used in correctness-critical world decisions.
- `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` – cached room snapshots/topology slices used for LOOK/navigation, scoped to a running instance.
- `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` – cached rendered or pre-assembled room “view” data serving LOOK or similar commands.
- `chat:city:<tenantId>:<cityId>` – cached short-lived windows of city chat history.

#### Usage Restrictions for `view:room-look:*`

`view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` is always treated as a **Class B, TTL-only cache** for rendered LOOK-style room views:

- It is never a correctness source for combat, pathfinding/movement, or visibility/line-of-sight decisions.
- Correctness-critical flows must call World Management and Entity Management APIs (and any Class A caches they own), or use separate, explicitly versioned Class A prefixes registered in this catalog.
- Helper APIs that expose `view:room-look:*` should be scoped to Game Session’s view pipeline and other presentation-only consumers; Game Logic and similar subsystems should continue to consume authoritative LOOK results via gRPC, not by reading this prefix directly.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [Redis Cache & Rate Limiting Reference](./system-architecture-redis-cache-reference.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)
