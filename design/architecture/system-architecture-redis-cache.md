# FireMUD System Architecture: Redis Cache & Rate Limiting

Redis is already used for transient coordination (ticks, sessions, locks). This document describes how **Cache/Rate-Limit Redis** backs selected read-side caches and rate limiting as a performance optimization. It focuses on design principles and cross-service patterns; per-service design docs describe target behavior as if fully implemented and kept in sync with this document.

The **Cache/Rate-Limit Key Catalog** in this file is the canonical catalog for cache and rate-limit prefixes: new prefixes must be registered there (and referenced from service docs) so their role, reset behavior, and correctness class stay in sync with the global reset policy matrix in `system-architecture-redis-reset-and-recovery.md`.

> ℹ️ **Implementation status**
>
> - Spring Cloud Gateway’s rate limiting is wired to Cache/Rate-Limit Redis today using the patterns in this document.
> - Other services reference these cache and aggregate patterns (for example, Entity Management character caches and World Management room caches) as target-state behavior; concrete cache adoption may evolve over time while continuing to follow these rules.
>
> 🔗 Tick locks, session coordination, and Lua-based execution are covered in
> [System Architecture: Redis](./system-architecture-redis.md). Usage patterns,
> environment profiles, and role wiring are described in
> [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md).
> This document focuses narrowly on cache/rate-limit key design and separation
> from Coordination Redis. For a concise overview of prefixes, roles, and owning
> services, see the [Redis Cheat Sheet](./system-architecture-redis-cheatsheet.md).

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
  - Automation and scripting structures on Cache/Rate-Limit Redis (for example `automation:queue:*`, `automation:quota:*`) are explicitly documented as best-effort buffers and counters; they cannot act as authoritative logs or effect ledgers. Durable automation schedules and quotas live in PostgreSQL; cache entries merely accelerate lookups and quota checks.
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
  - `automation:queue:*`, `automation:quota:*`, `chat:*`, and similar prefixes remain best-effort buffers and counters; they cannot become the only record of “which effects were applied” or “which commands ran”.
  - If evolution of a feature starts to require durable sequencing, migrate that responsibility into a ledger table or explicit coordination structure and update this catalog accordingly.

## Cache Adoption Checklist

When introducing or changing a cache/rate-limit prefix, designs must answer the following questions before implementation and CI should enforce that the answers are reflected in this doc and the owning service README as the **target state**. When you update or add a prefix:

- Update the **Cache/Rate-Limit Key Catalog** in this document first.
- Update the owning service README’s **Redis Role and Prefixes** section.
- Add a brief note in that README indicating where the cache adoption checklist for its prefixes is documented (either “this section” or this central catalog).

- **Prefix and ownership**
  - Prefix pattern (for example `inventory:<tenantId>:<containerId>`).
  - Owning service(s) and their design docs/sections.
- **Role and correctness class**
  - Redis role: **Cache/Rate-Limit Redis only** (never Coordination).
  - Correctness class:
    - **Class A – versioned/cache-for-correctness**, or
    - **Class B – TTL-only/best-effort**.
- **Authoritative version and invalidation**
  - For Class A caches:
    - Name and location of the authoritative version or `lastModified` field (DB column or API field). These version sources are part of the canonical contract and must match the owning service design docs (for example, the character and inventory version fields documented in the Entity Management design, and the room/world version fields documented in the World Management design).
    - Invalidation mechanism: domain events, version checks on read, or both.
  - For Class B caches:
    - Why occasional staleness is acceptable for this aggregate.
    - Which views fall back to authoritative reads when correctness matters.
- **TTL and budget**
  - Expected TTL range per environment profile (`dev_local`, `hobby_self_hosted`, `production_clustered`).
  - Size and key-count budgets, including:
    - Expected keys per tenant.
    - Any per-key cardinality limits (for example list lengths).
- **Metrics and observability**
  - Planned cache metrics (hits, misses, evictions, oversize/over-budget counters).
  - Any per-prefix gauges or key-count metrics that feed the “cache under pressure” dashboards.
- **Reset behavior**
  - Confirmation that the prefix is reset-tolerant and appears in the reset policy matrix with consistent semantics.
  - Any additional service-specific behavior after a reset (for example, lazy repopulation, optional warm-up).

New prefixes should not be considered “accepted” until this checklist is reflected in:

- The **Cache/Rate-Limit Key Catalog** in this file.
- The owning service README (under its Redis section).
- Any relevant testing/observability sections for that service.

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

## Version-Based Cache Validation

Some dynamic aggregates will be easier to cache if the authoritative store exposes a version or `lastModified` field per aggregate root. Others are naturally best-effort and can tolerate occasional stale reads under simple TTL-based eviction. To keep designs consistent and reviewable, caches are grouped into two classes:

- **Strongly validated caches (versioned)** – payloads that are validated against a version or `lastModified` value stored in the authoritative system:
  - The owning service (for example Entity Management or World Management) maintains a version counter or timestamp on the aggregate root (such as a container, character effective stats, or a room’s dynamic state row) and increments or updates it whenever the aggregate changes.
  - Redis entries for that aggregate store both version and payload together, typically inside a single serialized object.
  - When fetching, callers can:
    - Read the current version from the authoritative store or a lighter-weight index.
    - Compare it with the version in Redis.
    - Reuse the cached payload if versions match, or recompute and overwrite the cache if they differ.
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

- **Class A – correctness-critical caches** (for example, inventories shown to players, room occupants that drive combat/visibility decisions):
  - Must use **event-based invalidation and/or version checks** as described above.
  - May add TTLs, but **TTL alone is never considered sufficient** for correctness; a Class A cache must remain correct even if TTLs are set very large.
  - Aggregates that cannot provide events or versions should treat Redis as a pure performance optimization (for example, per-request in-memory caching) or avoid caching that aggregate entirely.
- **Class B – best-effort/performance caches** (for example, analytics-style aggregates, debug views, or non-player-facing summaries):
  - May rely on **TTL-only** invalidation, as long as occasional staleness is acceptable for the use case.
  - Still must respect per-key TTL budgets and size limits so they cannot starve Class A caches or coordination workloads.

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

- **Single-bucket operations** (default): commands or scripts that operate on exactly one `ratelimit:*` key at a time and never attempt cross-key atomicity. These are the only operations that may live on the hot path.
- **Multi-bucket inspection** (advanced): best-effort tooling that scans or inspects multiple buckets and must tolerate:
  - `CROSSSLOT` errors when running against Redis Cluster.
  - Non-atomic views of rate-limit state (for example, two keys changing while they are being read).

Shared helper APIs should make this explicit by providing separate entrypoints (for example, `RateLimitBucketHelper.singleBucket(...)` vs `RateLimitBucketHelper.inspectBuckets(...)`) and by documenting that the latter is **observability-only**, not a control-plane primitive.

#### Rate-Limit Bucket Design

To keep rate limiting robust under high cardinality and load, `bucket` values should follow a simple, predictable scheme:

- **Small/self-hosted deployments**
  - Per-client or per-token buckets (for example `bucket = clientId` or a normalized token ID) are acceptable when the number of active clients is small and overall throughput is modest.
  - Even in this mode, services should reuse shared helper builders so bucket shapes stay consistent across codebases.
- **Higher-cardinality or multi-tenant deployments**
  - Use a **stable hash** of the client/token into a bounded number of buckets per tenant:
    - Example: `bucket = H(clientId) mod N`, where `N` is a small fixed number (for example 64 or 256) chosen per deployment, not per tenant.
    - This caps the number of keys per the `(tenantId, timeWindow)` pair while avoiding single-key hotspots.
  - Introduce `<shard>` only when profiling shows that individual buckets are still too hot:
    - For example, split each logical bucket into a small number of shards (`0..S-1`) using `ratelimit:<tenantId>:<bucket>:<timeWindow>:<shard>` where `shard = H(requestId) mod S`.
    - Keep `S` small and fixed so the total key count remains predictable.
- **Key count and rotation**
  - Choose `N` (and optional `S`) so the total number of active `ratelimit:*` keys per tenant across all live `timeWindow` values stays within a comfortable range for the deployment.
  - Allow old `timeWindow` keys to expire naturally via TTL; do not attempt to retain historical rate-limit keys in Redis for analytics or debugging.

This approach gives small games straightforward per-client buckets by default while providing a clear, hash-based pattern for larger or noisier workloads without introducing many configuration knobs.

Cluster slotting implications:

- Rate-limit keys are treated as **single-key operations** from the cluster’s perspective; scripts or commands should not attempt atomic multi-key updates across different buckets or time windows.
- Rate-limit keys are treated as single-key operations; the design intentionally does not rely on Redis Cluster hash tags for rate limiting.
- Multi-key operations over rate-limit data (for example, bulk inspection of multiple buckets) must tolerate `CROSSSLOT` errors and fall back to per-key operations; the design does not rely on cross-slot multi-key transactions for rate limiting.

### Key Naming and Overwrite Expectations

Cached aggregates in Redis should follow structured, namespaced key patterns to keep responsibilities clear and enable targeted invalidation. Examples (subject to refinement):

- `inventory:<tenantId>:<containerId>` – cached view of a single inventory or container (including room-ground containers).
- `character-cache:<tenantId>:<characterId>` – cached character graphs for hot reads.
- `world-dynamic:<tenantId>:<aggregateId>` – cached view of room-level dynamic state or other world-scoped aggregates.
- `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` – cached room snapshots/topology slices used for LOOK/navigation, scoped to a running instance.
- `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` – cached rendered or pre-assembled room “view” data serving LOOK or similar commands.
- `chat:city:<tenantId>:<cityId>` – cached short-lived windows of city chat history.

#### Usage Restrictions for `view:room-look:*`

`view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` is always treated as a **Class B, TTL-only cache** for rendered LOOK-style room views:

- It is never a correctness source for combat, pathfinding/movement, or visibility/line-of-sight decisions.
- Correctness-critical flows must call World Management and Entity Management APIs (and any Class A caches they own), or use separate, explicitly versioned Class A prefixes registered in this catalog.
- Helper APIs that expose `view:room-look:*` should be scoped to Game Session’s view pipeline and other presentation-only consumers; Game Logic and similar subsystems should continue to consume authoritative LOOK results via gRPC, not by reading this prefix directly.

### Worked Example – Inventory Cache (`inventory:*`, Class A)

This section shows how the general patterns apply to a specific correctness-critical cache: per-container inventories owned by the Entity Management Service.

- **Prefix and ownership**
  - Prefix: `inventory:<tenantId>:<containerId>`.
  - Owner: Entity Management Service (see its Redis section for details).
- **Authoritative state**
  - Source of truth: PostgreSQL tables for entities/items/containment.
  - Each logical container (including room-ground containers) exposes a stable version or `lastModified` value via Entity Management APIs. The canonical container version field is defined in the Entity Management design and must match the version carried in these cache payloads.
- **Redis value shape (conceptual)**
  - Key: `inventory:<tenantId>:<containerId>`.
  - Value: serialized structure containing:
    - `containerId`, `tenantId`.
    - `version` (or `lastModified`).
    - A list of item records (item IDs and relevant display fields).
  - TTL: short/medium (for example 30–120 seconds) to bound memory use; precise values are environment-specific and documented in the Entity Management README.
- **Read path**
  1. Given `<tenantId, containerId>`, the caller:
     - Attempts to read `inventory:<tenantId>:<containerId>` from Cache/Rate-Limit Redis.
     - If present, inspects the cached `version`.
  2. Fetches the current authoritative `version`/`lastModified` from Entity Management (either as a cheap header call or as part of a full fetch).
  3. Behavior:
     - If the key is missing or versions do not match:
       - Fetch the inventory from PostgreSQL via Entity Management.
       - Rebuild the serialized payload with the current `version`.
       - Write it back to Redis with TTL in a **single atomic operation** (value + TTL).
       - Return the fresh inventory to the caller.
     - If the cached version matches:
       - Return the cached payload directly.
- **Invalidation**
  - Entity Management emits domain events for operations that change containers:
    - Item added/removed.
    - Item moved between containers.
    - Container destroyed/emptied.
  - A small listener (owned by Entity Management or a shared cache module) reacts to those events by:
    - Deleting the corresponding `inventory:<tenantId>:<containerId>` key, or
    - Refreshing it immediately with an updated payload and version.
  - TTL remains a backup: if a key is missed by event-based invalidation, it will eventually expire and be recomputed on the next access.
- **Reset and tail-loss behavior**
  - The prefix is reset-tolerant: coordination or cache resets may drop all `inventory:*` keys without affecting authoritative inventories in PostgreSQL.
  - After a reset, caches repopulate lazily on demand using the read path above.
- **Metrics**
  - Cache clients and/or Entity Management expose:
    - `cache.inventory_hits_total` / `cache.inventory_misses_total`.
    - Optional gauges for `cache.inventory_keys` and payload sizes.
    - Oversize counters for cases where inventory payloads exceed configured thresholds.
  - These metrics tie into the general cache SLOs and “cache under pressure” dashboards described in the Redis operations doc.

Expectations:

- These prefixes live on **Cache/Rate-Limit Redis**, not Coordination Redis. Coordination Redis remains reserved for strict coordination prefixes (`tick:`, `retry:`, `timer:`, `session:`, `tick-executor-lease:`, and related coordination keys); cache-like aggregates must never be written to Coordination Redis.

- Writing a new value for a key overwrites the previous entry. Cache writers do not attempt to merge old and new payloads inside Redis.
- Writers are encouraged to set TTLs as part of the same write operation (for example using `SET key value EX ttl` or a Lua script) so value and expiry are updated atomically.
- Future implementations should prefer single atomic commands or scripts (set value and TTL together, optionally with version) over multi-step delete plus set sequences unless there is a very specific, documented reason (such as maintaining backward-compatible behavior during a migration).

### Canonical World Management Cache Contracts (`world-dynamic:*` and `room:*`, Class A)

The first supported World Management Class A caches are intentionally narrow and room-scoped. They are defined here so `world-dynamic:*` and `room:*` are implementable contracts rather than placeholders.

- **`world-dynamic:*` authoritative aggregate**
  - Canonical first aggregate:
    - `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>`
  - Owner:
    - World Management Service.
  - Authoritative source:
    - The room-instance dynamic-state row (or equivalent authoritative projection) in World Management PostgreSQL keyed by `(tenantId, gameInstanceId, roomInstanceId)`.
  - Required authoritative version field:
    - `roomDynamicVersion`, a monotonic version/`lastModified` value on that authoritative row.
  - Cache payload may contain only room-scoped dynamic fields that affect correctness-critical world decisions, such as:
    - exit open/closed/locked state
    - traversal blockers and room accessibility flags
    - ambient hazard activation state that World Management serves authoritatively
    - other room-local, gameplay-relevant dynamic flags explicitly documented by World Management
  - Cache payload must **not** include:
    - rendered LOOK text
    - inventory/container contents
    - occupant/entity lists
    - any data whose authoritative owner is another service
  - Invalidator of record:
    - World Management write paths that mutate room dynamic state.
    - Required events include room dynamic-state changes such as door/exit state changes, hazard activation/inactivation changes, traversal-flag changes, and instance reset/teardown.

- **`room:*` authoritative aggregate**
  - Canonical first aggregate:
    - `room:<tenantId>:<gameInstanceId>:<roomInstanceId>`
  - Owner:
    - World Management Service.
  - Authoritative source:
    - World Management’s room snapshot/read model for the running instance, built from published topology plus room-instance dynamic overlays.
  - Required authoritative version field:
    - `roomSnapshotVersion`, a monotonic version/`lastModified` value exposed by World Management for the full snapshot served to correctness-critical readers.
    - `roomSnapshotVersion` must advance whenever either:
      - the underlying published room topology/version visible to the instance changes, or
      - any included room dynamic field changes.
  - Cache payload may contain only the data required for correctness-critical navigation/visibility reads, such as:
    - room identity and instance scope
    - exits and connectivity metadata
    - traversal/LOS-relevant topology flags
    - the current `roomDynamicVersion` or equivalent embedded dynamic overlay version
  - Cache payload must **not** include:
    - presentation-only rendered room views
    - chat/history windows
    - inventories or occupant rosters owned by other services unless a separate cross-service contract explicitly makes them part of the World Management snapshot
  - Invalidator of record:
    - World Management publish/activation paths for topology-visible changes.
    - World Management dynamic-state mutations that feed the snapshot.
    - Instance lifecycle transitions that rebuild or retire the room snapshot.

- **Reader and correctness contract**
  - `world-dynamic:*` and `room:*` are the only World Management Redis prefixes allowed to participate in correctness-critical pathfinding, movement admission, and visibility decisions.
  - Readers must validate these caches against `roomDynamicVersion` or `roomSnapshotVersion`; TTL is a backup only.
  - If the authoritative version cannot be fetched or compared, readers must fall back to the authoritative World Management read path rather than trust a possibly stale cache entry.
  - TTL-only world or room presentation caches must use distinct prefixes and must not be substituted for these Class A contracts.

### Cache/Rate-Limit Key Catalog

Cache/Rate-Limit Redis hosts prefixes that are **not** part of the coordination log and may be evicted under pressure. Designs and CI treat this table as the canonical catalog for non-coordination Redis keys; new cache/rate-limit prefixes must be added here, labelled with their role, correctness class, and reset behavior, and then referenced from per-service design docs and the reset policy matrix.

| Prefix | Role | Correctness Class | Reset Tolerance | Owner / Semantics |
| --- | --- | --- | --- | --- |
| `inventory:<tenantId>:<containerId>` | Cache | **Versioned (Class A)** | **Reset-tolerant** | Entity Management – cached inventory/container aggregates following version/TTL rules in the Redis cache design. Dropping entries flushes cached views; values are recomputed from PostgreSQL. Inventories rely on versioned correctness because stale caches can otherwise produce duplicate or missing items across containers. |
| `character-cache:<tenantId>:<characterId>` | Cache | **Versioned (Class A)** | **Reset-tolerant** | Entity Management – cached character graphs for hot reads. Loss clears caches only; character state persists in PostgreSQL. These caches are Class A because stale graphs can leak incorrect stats, abilities, or equipment into combat and progression flows. |
| `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` | Cache | **Versioned (Class A)** | **Reset-tolerant** | World Management – room-scoped dynamic-state cache backed by authoritative `roomDynamicVersion` in World Management. Includes only room-local correctness-critical dynamic fields such as exit state, traversal blockers, and hazard activation. Occupant lists, inventories, and presentation views remain out of scope. |
| `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` | Cache | **Versioned (Class A)** | **Reset-tolerant** | World Management – correctness-critical room snapshot cache backed by authoritative `roomSnapshotVersion`. Includes topology and dynamic overlay fields needed for movement/pathfinding/visibility decisions. Presentation-only LOOK payloads remain on `view:room-look:*` as Class B caches. |
| `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` | Cache | **TTL-only (Class B)** | **Reset-tolerant** | Game Session – cached rendered or pre-assembled room views for LOOK and similar commands. Dropping keys invalidates views; they are recomputed on demand. This prefix is strictly Class B and is never used as a correctness source for combat, movement, or visibility; Game Logic consumes authoritative LOOK results only via gRPC, not directly from this prefix. Game Session is the sole writer and direct Redis reader for this prefix. |
| `chat:say:<tenantId>:<playerId>`, `chat:tell:<tenantId>:<conversationId>`, `chat:guild:<tenantId>:<guildId>`, `chat:city:<tenantId>:<cityId>`, `chat:account:<tenantId>:<accountId>` | Cache | **TTL-only (Class B)** | **Reset-tolerant** | Social & Groups – short-lived chat history buffers. Resets drop recent chat windows only; authoritative history (where needed) lives in PostgreSQL. Clients must tolerate gaps and non-contiguous windows after resets or TTL truncation and rely on persisted history when a complete transcript is required. |
| `automation:queue:<tenantId>:*`, `automation:quota:<tenantId>:*` | Cache / Rate-Limit | **TTL-only (Class B)** | **Reset-tolerant** | Automation & Scripting – queued automation work items and per-script quota counters. Dropping queued work or quota counters is acceptable; automation re-enqueues from durable triggers where required and quotas are re-established from configuration. Eventual execution is guaranteed by authoritative effect/trigger tables in PostgreSQL, not by these queue keys. |
| `ratelimit:<tenantId>:<bucket>:<timeWindow>` (and optional `:<shard>`) | Cache / Rate-Limit | **TTL-only (Class B)** | **Reset-tolerant** | Spring Cloud Gateway – rate-limit buckets and optional sharded buckets as described in the Redis cache & rate-limiting design. Resets clear buckets; future requests rebuild counters. Temporary fairness shifts are acceptable (for example brief post-reset bursts) as long as global abuse and security policies remain enforced by gateway logic rather than Redis persistence. |

CI and code review checks are expected to:

- Fail when new Redis prefixes are introduced in cache/rate-limit contexts without being added to this catalog.
- Ensure that cache/rate-limit tooling and scripts explicitly bind to the Cache/Rate-Limit Redis role, not Coordination Redis, when using these prefixes.
- Treat `automation:queue:*` and related automation caches as **best-effort only**:
  - Automation workflows must be designed so that losing queued items or quota counters does not violate correctness; durable triggers and domain state remain the source of truth.
  - Non-idempotent or “exactly-once” automation contracts must store their authoritative state outside Redis and use these prefixes only as advisory buffers.
  - `automation:queue:*` must never be the sole source of truth for whether work has been enqueued or processed; any exactly-once or at-least-once guarantees come from durable trigger tables and idempotent domain logic, not from Redis queue contents.

### Cache Invalidation Policy Table

To keep cache usage reviewable and consistent across services, common aggregate types follow standard invalidation policies. This table mirrors the correctness classes captured in the cache key catalog above:

| Prefix / Aggregate | Example Key | Policy | Notes |
| --- | --- | --- | --- |
| Inventory/container views | `inventory:<tenantId>:<containerId>` | **Versioned** | Validated against a container or aggregate `version`/`lastModified` field in PostgreSQL; writes bump the version, and cache entries are discarded when versions mismatch. |
| Character graphs | `character-cache:<tenantId>:<characterId>` | **Versioned** | Backed by character graph rows with explicit versioning; invalidated on writes or relevant domain events. |
| Dynamic world aggregates | `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` | **Versioned** | Backed by authoritative room-instance dynamic-state rows with `roomDynamicVersion`; invalidated on room dynamic-state writes and instance lifecycle changes. |
| Room topology snapshots | `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` | **Versioned** | Cached room snapshots scoped to a running instance; validated against `roomSnapshotVersion`, which advances on topology-visible or included dynamic changes. |
| Room LOOK views | `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` | **TTL-only** | Recomputed on demand and cached for a short TTL; occasional staleness is acceptable between writes and cache expiry. |
| Short-lived chat buffers | `chat:say:<tenantId>:<playerId>`, `chat:guild:<tenantId>:<guildId>`, `chat:city:<tenantId>:<cityId>`, etc. | **TTL-only** | Treated as rolling windows of recent messages with small, fixed-size buffers; authoritative chat history (where required) lives in PostgreSQL. |

Service design docs may introduce additional cache aggregates, but each new prefix must declare whether it is **versioned** or **TTL-only**, specify its reset tolerance in terms of the reset policy matrix, and explain why that choice is appropriate.

### Cache Size and Complexity Budgets

To keep Cache/Rate-Limit Redis predictable and avoid unbounded growth, cache prefixes adhere to **size and complexity budgets** similar in spirit to the coordination budgets in `system-architecture-redis.md`:

- **Per-tenant key count envelopes**
  - Each cache prefix documents an expected range of keys per tenant (for example, “chat history per tenant typically stays under a small, bounded number of keys across all chat prefixes”) so operators can detect runaway key creation.
  - CI and observability should flag sustained deviations from these envelopes as potential design issues (for example, mis-keyed caches or forgotten TTLs).

- **Per-key cardinality and payload size**
  - Lists, sets, or sorted sets used for caches must declare a target maximum length (for example, “≤50 messages per chat buffer”, or “≤N entries per room view cache”) and enforce it via trimming or eviction logic.
  - Serialized payloads should stay within a small, predictable size envelope (for example, tens of kilobytes), with warnings and metrics emitted when caches exceed those thresholds.

### Recommended Cache Metrics by Prefix Family

To keep observability consistent, cache clients are expected to expose at least basic hit/miss and keycount metrics per prefix family:

- `inventory:*` – `cache.inventory_hits_total`, `cache.inventory_misses_total`, optional `cache.inventory_keys` and oversize counters.
- `character-cache:*` – `cache.character_hits_total`, `cache.character_misses_total`, optional `cache.character_keys`.
- `world-dynamic:*` / `room:*` – `cache.world_dynamic_hits_total`, `cache.world_dynamic_misses_total`, `cache.room_hits_total`, `cache.room_misses_total`, plus gauges for key counts where available.
- `view:room-look:*` – `cache.view_room_look_hits_total`, `cache.view_room_look_misses_total`, primarily to watch for pathological miss rates or misuse in correctness-critical paths.
- `chat:*` – `cache.chat_hits_total`, `cache.chat_misses_total`, and prefix-specific gauges keyed by chat type where helpful (say/tell/guild/account/city).
- `automation:queue:*` / `automation:quota:*` – either:
  - Generic cache-family metrics such as `cache.automation_queue_enqueued_total`, `cache.automation_queue_dropped_total`, and simple gauges for active queue/quota keys, or
  - Service-specific metrics that clearly cover the same concerns (for example, the Automation & Scripting metrics `automation_script_queue_delay_seconds`, `automation_tick_events_enqueued_total`, and `script_quota_*` counters).
  In either case, Automation & Scripting must document in its design which metrics satisfy these visibility requirements.

Service design docs should reference the relevant metrics for the prefixes they own so that dashboards and alerts can be wired consistently.

- **TTL and retention expectations**
  - TTLs are chosen so that caches do not accumulate long-lived data; prefixes that require longer retention for correctness should be reconsidered as candidates for Coordination Redis or PostgreSQL instead.

- **Rate-limit key and bucket budgets**
  - Cache/Rate-Limit Redis tracks rate limiting keys under the `ratelimit:<tenantId>:<bucket>:<timeWindow>` (and optional `:<shard>`) prefixes described above. Deployments should choose `N` (buckets per tenant) and any `S` (shards per bucket) so that:
    - The total number of active `ratelimit:*` keys per tenant across all live `timeWindow` values remains within a modest, documented envelope (for example, on the order of **a few thousand** keys for small/self-hosted deployments).
    - No single `(tenantId, bucket)` tuple becomes a pathological hot key under expected load; when profiling reveals such hotspots, operators either increase `N` or introduce a small `S` to fan out writes.
  - Spring Cloud Gateway’s `RequestRateLimiter` maps its per-client IP and route buckets into this scheme by:
    - Using a synthetic tenant identifier for edge traffic (for example `gateway-edge`) so all rate-limit keys still carry a `tenantId` prefix.
    - Deriving `bucket` from a stable hash of the client IP and route identifier, and using a small, fixed `N` to bound the total bucket count per `timeWindow`.
  - Observability for rate limiting should include simple counters and gauges (for example, total active `ratelimit:*` keys per tenant, distribution of hits per bucket) so operators can detect when the configured `N`/`S` values are no longer appropriate for the workload.
  - Runtime self-checks and ACLs reinforce role separation: Cache/Rate-Limit Redis deployments use cache-specific users (for example `cache_app`) that are denied access to coordination prefixes, while Coordination Redis ACLs deny access to cache prefixes such as `ratelimit:*` and `view:room-look:*`. Misrouted cache traffic therefore surfaces as explicit authorization errors instead of silently mutating coordination keys.

New cache prefixes must document their budgets in the owning service’s design doc and, where applicable, in the central Cache/Rate-Limit Key Catalog so reviews can validate that Redis is being used as a cache rather than a secondary datastore.

## Future Work / TODO

This section captures remaining design work; it is intentionally **short-lived** and should be cleared as part of early cache adoption, not deferred indefinitely. Near-term priorities:

- **Entity Management**
  - Keep the canonical definitions for `inventory:*` and `character-cache:*` in sync with the Entity Management service design:
    - Ensure the authoritative `version`/`lastModified` fields referenced in this catalog match the concrete columns and API fields named in the Entity Management README.
    - Confirm event-based invalidation flows (for example, “inventory changed”, “character graph changed”) remain documented and tested as those APIs evolve.
- **World Management**
  - Keep the canonical `world-dynamic:*` and `room:*` contracts in sync with the World Management service design:
    - Ensure `roomDynamicVersion` and `roomSnapshotVersion` map to concrete columns/API fields in the World Management README and APIs.
    - Confirm the documented invalidation events stay aligned with the actual room dynamic-state and topology mutation paths.
- **Game Session / Game Logic**
  - Confirm how `view:room-look:*` caches map onto room views and ensure they are used only for Class B flows (UI/analytics-style views). Correctness-critical flows (combat, visibility, movement) must rely on authoritative world/entity APIs or separate Class A caches with their own prefixes.
- **Cross-service documentation**
  - For each of the prefixes listed above, add or update the corresponding sections in the owning service docs (Entity Management, World Management, Game Session, Game Logic) to:
    - Declare the cache’s correctness class (A vs B), versioning or TTL strategy, and reset tolerance.
    - Show example key shapes, TTL ranges, and event listeners/invalidation paths.

These tasks should be completed **before** enabling broad, non-gateway use of Cache/Rate-Limit Redis in production-like environments so that caches do not accumulate organically without clear ownership and correctness stories.

## Testing Caches

Cache behavior must be covered by tests appropriate to its correctness class. This section describes expectations for new prefixes:

- **Class A (versioned, correctness-critical) caches**
  - Unit and/or integration tests should cover:
    - Cache miss → authoritative fetch → populate → subsequent hit with matching version.
    - Version change in the authoritative store:
      - Cached entry with old version is detected as stale.
      - Reader triggers a refresh that updates both value and version.
    - Event-driven invalidation:
      - Simulate domain events (for example, inventory changed, room updated) and assert that affected keys are deleted or refreshed.
    - Behavior after reset:
      - With an empty Cache/Rate-Limit Redis, reads correctly repopulate from PostgreSQL without relying on any prior cache state.
  - Tests must assert that stale cache entries **do not** cause incorrect game-visible state (for example, duplicate or missing items); authoritative reads remain decisive.
  - In practice:
    - Entity Management should host most tests for `inventory:*` and `character-cache:*` (for example in `InventoryCache*Test` and `CharacterCache*Test` suites).
    - World Management should host tests for `world-dynamic:*` and `room:*`.
    - Game Session should host tests for `view:room-look:*` recomputation behavior.
- **Class B (TTL-only, best-effort) caches**
  - Tests should demonstrate:
    - Simple hit/miss behavior and that TTL expiry leads to recomputation from authoritative services.
    - That losing cache entries (for example via reset or eviction) degrades to extra DB/service calls rather than incorrect gameplay behavior.
  - For presentation-oriented caches such as `view:room-look:*`, tests may focus on performance and freshness characteristics rather than strict correctness, as long as underlying world/entity correctness is covered elsewhere.
  - For best-effort operational structures such as `ratelimit:*`, `chat:*`, and `automation:queue:*`, tests should capture:
    - The acceptable impact of resets on fairness or history windows (for example, extra burst capacity after a reset, shorter chat windows).
    - That invariants enforced by authoritative stores or policy (for example, abuse limits, moderation logs) remain intact regardless of cache loss.

Per-service testing docs can add more detail, but new cache prefixes must describe where these scenarios are expected to be tested (unit vs integration vs cross-service tests) and how failures surface in observability (metrics and logs) when cache behavior regresses. See also `system-architecture-testing.md` for guidance on layering these tests.

## Cache Metrics Catalog

To keep observability consistent across services, cache instrumentation should follow a common naming and tagging scheme. At minimum, each prefix family should emit:

- Hit/miss counters (for example `redis_cache_hits_total{prefix=...,service=...}`, `redis_cache_misses_total{prefix=...,service=...}`).
- Optional TTL and size histograms where appropriate (for example `redis_cache_value_bytes{prefix=...}`).

Recommended prefix tags:

- `inventory:*` – `prefix="inventory"`, `aggregate_type="container"`.
- `character-cache:*` – `prefix="character-cache"`, `aggregate_type="character"`.
- `world-dynamic:*` – `prefix="world-dynamic"`, `aggregate_type="world-dynamic"`.
- `room:*` – `prefix="room"`, `aggregate_type="room-topology"`.
- `view:room-look:*` – `prefix="view:room-look"`, `aggregate_type="room-view"`.
- `chat:*` – `prefix="chat"`, `chat_kind="say|tell|guild|city|account"`.
- `ratelimit:*` – `prefix="ratelimit"`, `bucket_kind="ip|token|tenant"` plus time-window labels.
- `automation:queue:*` / `automation:quota:*` – `prefix="automation-queue"` / `prefix="automation-quota"`, `script_id=...`.

Services may add more specific metrics (for example per-tenant tags) where cardinality is controlled, but should keep this baseline consistent so global dashboards and alerts can reason about cache health per prefix family.

As an additional guardrail against accidental “soft coordination logs” on Cache/Rate-Limit Redis, observability should make it easy to spot flows that couple cache usage and tick identity too closely. Where practical:

- Dashboards or ad-hoc analyses should highlight request paths where cache prefixes (for example `inventory:*`, `view:*`, `ratelimit:*`) are frequently accessed alongside tick identifiers (`tickId`, `region_epoch`) in the same handler.
- Such flows should be reviewed to ensure:
  - Cache presence does not change tick behavior or ordering, and
  - Any need for durable sequencing is handled by the tick effect ledger or other PostgreSQL-backed structures instead of by cache keys.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)
