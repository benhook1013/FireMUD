# FireMUD System Architecture: Redis Cache & Rate Limiting

Redis is already used for transient coordination (ticks, sessions, locks). This document describes how **Cache/Rate-Limit Redis** backs selected read-side caches and rate limiting as a performance optimization. It focuses on cache ownership, invalidation rules, consistency expectations, and canonical cache policy. The detailed prefix catalog, worked examples, reference tables, adoption checklist, and testing guidance live in [Redis Cache & Rate Limiting Reference](./system-architecture-redis-cache-reference.md).

The canonical cache policy in this file and the reference catalog in the companion document must stay aligned with the global reset policy matrix in `system-architecture-redis-reset-and-recovery.md`.

> ℹ️ **Implementation status**
>
> - Spring Cloud Gateway’s rate limiting is wired to Cache/Rate-Limit Redis today using the patterns in this document.
> - Other services reference these cache and aggregate patterns (for example, Entity Management character caches and World Management room caches) as target-state behavior; concrete cache adoption may evolve over time while continuing to follow these rules.
> - The current `room:*` implementation is still an unversioned TTL-only payload and does not prove the target owner-local Class A scope/version validation or invalidation path. Current readers must use authoritative reads when that proof is unavailable.
> - Spring Cloud Gateway currently derives rate-limit keys from raw client IP without an evidenced tenant/cardinality profile. Game Session currently has Redis-backed per-session limiting; the target ordinary gameplay limiter remains in-process under ADR 0034.
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
  - All non-ephemeral environments, including local development and small self-hosted setups, run **at least two Redis roles**:
    - A **Coordination Redis** deployment dedicated to ticks, locks, timers, sessions, and other gameplay-critical coordination state.
    - A **Cache/Rate-Limit Redis** deployment dedicated to read-side caches and gateway rate limits.
  - Coordination Redis must not host large, eviction-driven caches under any profile. Even in development and hobby/self-hosted profiles, caches and rate limits are pointed at the separate Cache/Rate-Limit deployment so eviction and OOM behavior cannot silently affect coordination keys. The only supported exception is an explicitly marked ephemeral CI/test stack whose tests are reset-tolerant, do not exercise coordination tail-loss or replay guarantees, and surface the shared endpoint; such a stack is outside coordination SLO validation. See `system-architecture-redis-usage-and-profiles.md` for environment profiles and mappings.
- No soft coordination logs on Cache/Rate-Limit Redis:
  - Tick ordering, tick idempotency, and any correctness or fairness invariants for gameplay **must not** depend on cache or rate-limit keys. Cache/Rate-Limit Redis may only influence latency and load, never “what happened” or “in which order” from the tick engine’s perspective.
  - Automation and scripting structures on Cache/Rate-Limit Redis (for example `automation:queue:*`, `automation:quota:*`, `automation:tenant-budget:*`, `automation:test:quota:*`, `automation:test:capacity:*`, and `automation:readiness:capacity:*`) are explicitly documented as best-effort buffers, counters, and bounded owner-token leases; they cannot act as authoritative logs or effect ledgers. Durable automation schedules, work, and readiness state live in PostgreSQL; cache entries merely accelerate lookups, quota checks, and disposable capacity admission.
  - If a new feature appears to need a durable or authoritative log for tick- or session-driven workflows, that log belongs in PostgreSQL (for example as a ledger or follow-up table) or, in rare cases, on Coordination Redis with explicit reset/tail-loss rules—not on Cache/Rate-Limit Redis.

Gameplay session state on Coordination Redis means liveness, binding, and rebind coordination. Durable semantic reconnect context remains Game Session-owned persistence under [ADR 0134](./decisions/adr-0134-bounded-durable-semantic-reconnect-context.md) and [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model); Cache/Rate-Limit Redis may only accelerate or cache that owner-controlled context.

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
  - Game Session owns durable semantic recent-context persistence and its rendering policy; see [ADR 0134](./decisions/adr-0134-bounded-durable-semantic-reconnect-context.md) and [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model). Cache/Rate-Limit Redis may only provide a disposable acceleration/cache for that owner-controlled context.
- Safety rules:
  - cached view payloads are derived and disposable, never authoritative;
  - `view:room-look:*` is a disposable presentation/redraw helper only; it is never semantic reconnect context, frame/output replay, a transcript archive, or a delivery ledger;
  - `screenbuffer:*` is a separate disposable reconnect-context acceleration/cache family; it is distinct from `view:room-look:*` and never semantic authority;
  - they may improve latency or presentation/redraw experience, but they must not change gameplay semantics;
  - each cached built-in view must opt in explicitly with a documented key shape, TTL, invalidation source, and presentation/use rules rather than inheriting from a generic “all commands are cacheable” framework;
  - cached room-view payloads should preserve the same top-level structure as the authoritative view contract (for example room prose, exits, occupants, room-ground items, and optional overlays) so presentation/UI redraw does not invent a second ad hoc shape.

## Version-Based Cache Validation

Some dynamic aggregates will be easier to cache if the authoritative store exposes a version or `lastModified` field per aggregate root. Others are naturally best-effort and can tolerate occasional stale reads under simple TTL-based eviction. To keep designs consistent and reviewable, caches are grouped into two classes:

- **Strongly validated caches (versioned)** – payloads that are validated against a version or `lastModified` value stored in the authoritative system:
  - Only the service that owns the authoritative aggregate may consume the entry for a correctness-sensitive read. It must prove during that operation that the complete cached scope and payload version or fence match the current authoritative requirement.
  - The owner may obtain that proof from the authoritative store, an owner-controlled version index with equivalent consistency, or an exact expected version/fence already required by the operation. An exact expected version/fence is valid for this purpose only when the owning service derives it from, or authenticates it against, authoritative workflow/operation state; a caller-supplied or otherwise untrusted value is not currentness proof. A version embedded only in the cache payload is not proof of currentness.
  - The owning service (for example Entity Management or World Management) maintains a version counter or timestamp on the aggregate root (such as a container, character effective stats, or a room’s dynamic state row) and increments or updates it whenever the aggregate changes.
  - Redis entries for that aggregate store both version and payload together, typically inside a single serialized object.
  - When fetching, the owning service must:
    - Read the current version or fence from the authoritative store or an equivalently consistent owner-controlled index.
    - Compare it with the version/scope in Redis during the same operation.
    - Reuse the cached payload only when the complete scope and proof match; otherwise recompute from authoritative state or fail closed.
  - Every other service calls the owning service's API and must not read, validate, or reuse that owner's Class A Redis entry directly.
- Versioning is applied per aggregate root, not per field, and is treated as part of the aggregate’s API contract. For namespace-backed Entity aggregates, the cache scope and version identity are `(tenantId, playableStateNamespaceId, domain object id)` (for example, `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>`); `playableStateScope` is separately validated routing/authorization evidence and is not a key component. Explicitly instance-scoped S3 holders use their complete `(tenantId, gameInstanceId, roomInstanceId/containerId)` scope instead.

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
  - Preferred strategy: **owner-local authoritative version/fence validation, with event-driven invalidation as a load optimization; TTLs are only a backup.**
    - On writes, the owning service emits change events or updates a version/`lastModified` field.
    - Callers either:
      - Listen for events and invalidate affected keys, and
      - Use owner-local version/fence proof as described above before reusing cache entries.
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
  - Must be consumed only by the owning service with current authoritative version/fence proof as described above. Event invalidation and TTL are supporting mechanisms, not proof.
  - May add TTLs, but **TTL alone is never considered sufficient** for correctness; a Class A cache must remain correct even if TTLs are set very large.
  - Aggregates that cannot provide events or versions should treat Redis as a pure performance optimization (for example, per-request in-memory caching) or avoid caching that aggregate entirely.
- **Class B – best-effort/performance caches** (for example, analytics-style aggregates, debug views, or non-player-facing summaries):
  - May rely on **TTL-only** invalidation, as long as occasional staleness is acceptable for the use case.
  - Still must respect per-key TTL budgets and size limits so they cannot starve Class A caches or coordination workloads.

To avoid noisy-neighbor effects on coordination workloads, cache writers must also enforce **per-value size limits** and avoid unbounded lists or blobs in Redis:

- Cap serialized values to a practical ceiling (for example, roughly 32 KB or two protobuf pages) before writing them to Redis. A cache family may split an oversized aggregate into chunked entries only when its owning contract explicitly defines chunking and the complete scope binding for those chunks. `view:room-look:*` is not chunked: when its rendered LOOK payload exceeds the canonical 32 KiB limit, skip the cache write and serve the uncached authoritative `ResolveLook` result as specified below.
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

For **all non-ephemeral deployments**, including local development:

- Coordination and cache/rate-limit roles run on **separate Redis deployments** (for example, two containers/pods on the same host or separate processes), even when serving a single developer or tenant.
- Deployment mechanisms vary by environment: local Docker Compose provides both `redis-coord` and `redis-cache`; the umbrella Helm chart's `k8s/helm/firemud/values.yaml` defaults `previewStack.enabled=false` and therefore does not render its in-chart Redis services by default; the hosted overlay enables both in-chart Redis services; and production Terraform provisions separate Redis releases. These are local deployment consequences of the two-role requirement, not a second role policy.
- Explicitly marked ephemeral CI/test stacks may collapse roles into a single Redis instance only when tests are reset-tolerant, do not exercise coordination tail-loss or replay guarantees, the environment is labelled `ephemeral / single-Redis`, and configuration/metrics surface the shared endpoint. These stacks are outside coordination tail-loss, replay, and SLO validation and must not be used for QA, staging, production, or any player-facing game instance.

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
      - Use the explicitly authorized cache-owner/deployment control to shorten TTLs or shrink payloads only for non-lease, disposable Class B prefixes. `automation:queue:*` is excluded unless its owner contract provides reconciled recovery that proves no stale or in-flight work is lost; pressure handling must not shorten its TTL or shed its writes before that recovery is available. Any shortening or automatic policy change must bind the exact Cache/Rate-Limit deployment and scope, use a bounded duration, and produce an audit record with rollback and readback evidence; it must not be interpreted as a direct `redis-cli` TTL mutation. Owner-token leases, including `automation:test:capacity:{automation-test-capacity}:cluster:lease:<tenantId>:<workItemId>` and `automation:readiness:capacity:{automation-readiness-capacity}:cluster:lease:<tenantId>:<workItemId>`, are excluded unless their owner contract explicitly defines a semantic minimum lifetime plus renewal or reacquisition behavior.
      - Increase Cache Redis resources if justified.
  - More advanced deployments may optionally wire these signals into policy (for example, automatically shortening TTLs or shedding non-lease disposable Class B cache writes when pressure/noisy-tenant conditions persist), but such behavior is an explicit opt-in through the same authorized cache-owner/deployment control, with exact scope, bounded duration, audit, rollback, and readback. `automation:queue:*` remains excluded unless its owner contract provides reconciled recovery proving that shortening or write shedding cannot lose stale or in-flight work; owner-token leases remain excluded unless their owner contract explicitly defines a semantic minimum lifetime plus renewal or reacquisition behavior. It is not part of the default design and does not authorize direct `redis-cli` TTL mutation.

This approach keeps configuration minimal—a single notion of “cache under pressure” plus relative noisy-tenant hints—while giving operators concrete signals to act on when Cache Redis becomes a bottleneck.

Rate limiting keys (for example those used by Spring Cloud Gateway’s `RequestRateLimiter`) should preserve subject isolation while avoiding **hot keys** under heavy load:

- Per-subject prefixes are preferred over global counters. An individual client, credential, token, connection, or account candidate maps to one normalized opaque stable subject hash for the active window; it must not be reduced modulo a common bucket count or split by request-derived shards.
- Use the canonical pattern `ratelimit:<tenantId>:<subjectHash>:<timeWindow>` and publish helper builders so services reuse the same privacy-preserving one-to-one subject mapping.
- The canonical helper renders `subjectHash` as `rsh-v1-<keyId>-<lowercase-hex-HMAC>`. The HMAC is the complete 32-byte HMAC-SHA-256 output and is never truncated or reduced modulo a bucket count. `keyId` is a non-secret identifier for one environment-scoped key delivered through the canonical secret mechanism; the HMAC key itself never appears in Redis, logs, metrics, or request context.
- The exact HMAC preimage uses `segment(bytes) = ascii(canonical_decimal_count(byte_length(bytes))) + ":" + bytes`, then concatenates `segment(utf8("FireMUD/rateLimitSubject/v1"))`, `segment(utf8(keyId))`, `segment(utf8(subjectKind))`, `segment(utf8(tenantId))`, `segment(utf8(policyScope))`, and `segment(canonicalSubjectBytes)` in that order. Each registered subject kind defines one canonicalization before hashing; client addresses use validated network bytes, with IPv4-mapped IPv6 normalized to the equivalent IPv4 bytes. The domain label, tenant, kind, and policy scope prevent cross-domain or cross-policy reuse.
- All replicas enforcing the same policy scope use the same active key ID and material. Activating a new unique key ID intentionally isolates new buckets from the old key; old buckets expire by their bounded TTL and are never reinterpreted or rehashed. Each limiter treats that cutover as its declared Cache/Rate-Limit reset behavior, including any temporary weakening or tightening, and must not rely on rotation continuity for a hard invariant.
- A digest collision must never silently merge subjects: implementations use the full 256-bit output, publish cross-language golden vectors, and treat any detected same-digest/different-canonical-subject condition as a fatal integrity failure under the limiter's declared fail-closed or unavailable-store behavior. There is no collision fallback bucket.
- Deliberately shared coarse signals use a separately named policy scope (for example `ratelimit:global-pressure:<timeWindow>` or a declared source-class bucket). Such buckets are heuristics and cannot alone impose an individual security consequence.
- The current Game Session `ratelimit:<sessionId>` shape is a legacy implementation, not a member of the target family. Before target shared helpers consume unversioned `ratelimit:*` keys, legacy writers must stop and the maximum legacy TTL must elapse with an empty-key readback; an adopter that cannot prove that drain uses a distinct versioned prefix. Target parsers and scripts reject the legacy arity rather than inferring tenant, subject, or window fields.

Helpers for rate limiting must distinguish between:

- **Single-bucket operations** (default): commands or scripts that operate on exactly one `ratelimit:*` key at a time and never attempt cross-key atomicity. These are the only operations that may live on the hot path.
- **Multi-bucket inspection** (advanced): best-effort tooling that scans or inspects multiple buckets and must tolerate:
  - `CROSSSLOT` errors when running against Redis Cluster.
  - Non-atomic views of rate-limit state (for example, two keys changing while they are being read).

Shared helper APIs should make this explicit by providing separate entrypoints (for example, `RateLimitBucketHelper.singleBucket(...)` vs `RateLimitBucketHelper.inspectBuckets(...)`) and by documenting that the latter is **observability-only**, not a control-plane primitive.

#### Rate-Limit Bucket Design

To keep rate limiting robust under high cardinality and load, `bucket` values should follow a simple, predictable scheme:

- **Small/self-hosted deployments**
  - Per-client or per-token buckets use one normalized opaque subject hash per live window. Shared helper builders keep privacy and key shape consistent.
- **Higher-cardinality or multi-tenant deployments**
  - Bound isolated subject cardinality with TTLs, active-subject admission limits, per-tenant/deployment memory budgets, and explicit overload behavior. Do not obtain boundedness by mapping unrelated subjects into collision pools.
  - If a subject key is too hot, scale the Cache/Rate-Limit deployment or use an explicitly declared coarse heuristic alongside the subject bucket; request-derived shards must not multiply allowance or change the subject's identity.
- **Key count and rotation**
  - Choose active-subject and admission limits so the total number of isolated `ratelimit:*` keys per tenant across all live `timeWindow` values stays within a measured range for the deployment.
  - Allow old `timeWindow` keys to expire naturally via TTL; do not attempt to retain historical rate-limit keys in Redis for analytics or debugging.

Before claiming the subject helper implemented, focused proof must establish stable output for identical key/input, different output when the key ID/material, subject kind, tenant, policy scope, or canonical subject changes, canonical equivalence for alternate spellings of the same address, full-length output, rotation isolation and TTL expiry, cross-language golden-vector agreement, and absence of raw subject material from Redis keys and metric labels.

This approach gives small games straightforward per-client buckets by default while providing a clear, hash-based pattern for larger or noisier workloads without introducing many configuration knobs.

Cluster slotting implications:

- Rate-limit keys are treated as **single-key operations** from the cluster’s perspective; scripts or commands should not attempt atomic multi-key updates across different buckets or time windows.
- Rate-limit keys are treated as single-key operations; the design intentionally does not rely on Redis Cluster hash tags for rate limiting.
- Multi-key operations over rate-limit data (for example, bulk inspection of multiple buckets) must tolerate `CROSSSLOT` errors and fall back to per-key operations; the design does not rely on cross-slot multi-key transactions for rate limiting.

### Key Naming and Overwrite Expectations

Cached aggregates in Redis should follow structured, namespaced key patterns to keep responsibilities clear and enable targeted invalidation. Namespace-backed Entity examples use `(tenantId, playableStateNamespaceId, domain object id)`; explicitly instance-scoped S3 holders use their complete instance scope and never fabricate a namespace key:

- `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>` – cached view of a namespace-backed inventory or container.
- `inventory:room-ground:<tenantId>:<gameInstanceId>:<roomInstanceId>:<containerId>` – cached view of an explicitly instance-scoped S3 synthetic room-ground holder, with complete `(tenantId, gameInstanceId, roomInstanceId/containerId)` scope.
- `character-cache:<tenantId>:<playableStateNamespaceId>:<characterId>` – cached namespace-backed character graphs for hot reads.
- `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` – cached room-level dynamic state used in correctness-critical world decisions.
- `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` – cached room snapshots/topology slices used for LOOK/navigation, scoped to a running instance.
- `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:<sessionId>:<viewerContextHash>:<policyContextHash>:<readFenceHash>` – cached rendered or pre-assembled room view data bound to the exact room, viewer/session, policy context, and applicable causal read fence.
- `chat:city:<tenantId>:<cityId>` – cached short-lived windows of city chat history.

#### Usage Restrictions for `view:room-look:*`

`view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:<sessionId>:<viewerContextHash>:<policyContextHash>:<readFenceHash>` is always treated as a **Class B, TTL-only cache** for rendered LOOK-style room views:

- It is a disposable presentation/redraw helper only, never semantic reconnect context, frame/output replay, a transcript archive, or a delivery ledger.
- It is never a correctness source for combat, pathfinding/movement, or visibility/line-of-sight decisions.
- The key must be bound to the exact room, viewer/session, policy context, and applicable read-fence context. `policyContextHash` remains the presentation/authorization policy context. `readFenceHash` is a stable collision-resistant digest over the canonical normalized complete applicable `CausalReadFence` identity. A cache read or write is forbidden when the complete applicable fence is unavailable; callers must serve the uncached authoritative `ResolveLook` result instead. Any change to the complete fence changes `readFenceHash` and prevents reuse. This hash is only a Class-B presentation-cache key component: it is not authority/currentness proof and does not replace served-through validation.
- Correctness-critical flows must call World Management and Entity Management APIs (and any Class A caches they own), or use separate, explicitly versioned Class A prefixes registered in this catalog.
- Helper APIs that expose `view:room-look:*` should be scoped to Game Session’s view pipeline and other presentation-only consumers; Game Logic and similar subsystems should continue to consume authoritative LOOK results via gRPC, not by reading this prefix directly.

### Canonical `view:room-look:*` Class-B Contract

`view:room-look:*` is a target-only Game Session presentation cache. The current implementation does not prove these bounds or ownership rules; until it does, callers must use the authoritative `ResolveLook` result without treating Redis as a required path.

- Game Session is the sole writer and invalidation owner. The cache uses the complete key shape above; a room, viewer, session, policy, or read-fence change prevents reuse. The `readFenceHash` derivation and unavailable-fence fallback are defined by the preceding usage restriction.
- Each entry has a TTL of at most 5 seconds, a payload of at most 32 KiB, and at most four simultaneously live variants per admitted session. Admission or write refusal falls back to the uncached authoritative `ResolveLook` result.
- Cache loss, a miss, an oversize result, variant-budget exhaustion, or Redis failure recomputes or serves the uncached authoritative `LOOK`; none may block gameplay. TTL is the correctness-independent expiry; deletion is optional cleanup, not correctness proof.
- Metrics must expose hits/misses, recomputes, write-skip reason, oversize results, active keys/variant-budget use, and Redis failures. Reset and recovery behavior follows [Redis reset and recovery](./system-architecture-redis-reset-and-recovery.md), not this presentation-cache contract.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [Redis Cache & Rate Limiting Reference](./system-architecture-redis-cache-reference.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)
