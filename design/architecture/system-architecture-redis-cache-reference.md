# FireMUD System Architecture: Redis Cache & Rate Limiting Reference

This companion document holds the reference-heavy material for Redis cache and rate limiting. The parent policy doc covers cache ownership, invalidation rules, consistency expectations, and canonical cache policy.

## Implementation Status

The `room:*` Class A cache contract below is target-state only. It is not a current correctness path until World Management has an opaque room component version and a proven invalidation path. Current readers must not substitute `worldSnapshotId` or `roomDynamicVersion` for that component version; they must use authoritative reads when the target validation contract is unavailable. A cache-embedded version, TTL, or invalidation event alone is not currentness proof. The current Game Session `ratelimit:<sessionId>` key is likewise a legacy implementation and must be drained by its maximum TTL or isolated behind a versioned prefix before target rate-limit helpers consume the shared family. The future reader, invalidation, and version-advance rules below remain normative.

## Cache Adoption Checklist

When introducing or changing a cache/rate-limit prefix, designs must answer the following questions before implementation and CI should enforce that the answers are reflected in this reference doc and the owning service README as the target state:

- Update the cache prefix catalog and any worked examples first.
- Update the owning service README’s Redis section.
- Note where the cache adoption checklist and canonical examples live for that service.
- Record the prefix pattern, owner, correctness class, Redis role, authoritative version source, invalidation mechanism, TTL range, size budget, metrics, and reset behavior.
- For Class A caches, record the exact authoritative version, fence, or equivalent owner-controlled proof location that readers validate during the operation; a version stored only in the cache payload is insufficient.
- For Class B caches, record the correctness fallback path when authoritative reads are still required.
- Record per-environment TTL expectations (`dev_local`, `hobby_self_hosted`, `production_clustered`) and the reset policy matrix entry.
- Do not treat a prefix as accepted until the owning service docs and observability references are in sync with this reference document.
- Cache/rate-limit prefixes must bind to the Cache/Rate-Limit Redis role only; they must never rely on Coordination Redis.

### Worked Example – Inventory Cache (`inventory:*`, Class A)

This example shows a correctness-critical cache owned by Entity Management.

- Prefix: `inventory:<tenantId>:<containerId>`.
- Source of truth: PostgreSQL tables for entities, items, and containment.
- Version source: the container’s authoritative `version` or `lastModified` field exposed by Entity Management.
- Cache payload: `containerId`, `tenantId`, the version field, and the item records required for hot reads.
- Read path: the owning service obtains the current authoritative version/fence, compares the complete cache scope and payload version during the operation, and rebuilds from PostgreSQL if the entry is missing, stale, wrong-scope, or unverifiable.
- Invalidation: item/container change events delete or refresh the affected key.
- Write discipline: when populating or refreshing the cache, write the value and TTL atomically (single command or script) so partially refreshed entries are never visible.
- Reset behavior: caches are reset-tolerant and repopulate lazily after a drop.
- Metrics: hit/miss counters, key-count gauges, and oversize counters.

### Canonical World Management Cache Contracts (`world-dynamic:*` and `room:*`, Class A)

The first supported World Management Class A caches are intentionally narrow and room-scoped. The `room:*` contract is target-state only until its required opaque component version and invalidation path exist.

- `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>`
  - Owner: World Management Service.
  - Authoritative source: the room-instance dynamic-state row in World Management PostgreSQL.
  - Required version field: `roomDynamicVersion`.
  - Payload scope: room-local dynamic fields that affect correctness-critical world decisions.
  - Payload exclusions: must not include rendered LOOK text, inventory/container contents, occupant/entity lists, or any data whose authoritative owner is another service.
  - Invalidator of record: World Management write paths that mutate room dynamic state.
- `room:<tenantId>:<gameInstanceId>:<roomInstanceId>`
  - Owner: World Management Service.
  - Authoritative source: World Management’s room snapshot/read model.
  - Required scope, version, and payload fields: the owner-validated `regionId` and `regionEpoch`, plus the exact opaque World-owned room component version (see [Canonical Room Runtime Contract](./system-architecture-overview.md#canonical-room-runtime-contract)), are stored alongside the room snapshot payload.
  - Version-advance rule: the World-owned room component version must advance on both topology-visible changes and any included dynamic-state changes.
  - Payload scope: navigation and visibility metadata needed for correctness-critical reads.
  - Payload exclusions: must not include presentation-only rendered room views, chat/history windows, or inventories/occupant rosters unless an explicit cross-service contract makes them part of the authoritative room snapshot.
  - Invalidator of record: topology-visible publish/activation paths, snapshot-fed dynamic mutations, instance lifecycle transitions that rebuild or retire the room snapshot, and owner region-epoch changes.
  - Refresh discipline: write the room payload, exact owner scope (`regionId`, `regionEpoch`), component version, and TTL atomically; a missing or unverifiable scope/version is an authoritative-read miss and must be rebuilt before serving. A region-epoch change invalidates the prior entry and prevents cross-epoch reuse.
  - Reader contract:
  - Only `world-dynamic:*` and `room:*` may participate in correctness-critical World Management movement, pathfinding, and visibility decisions.
  - Only World Management may consume these Class A entries for correctness-sensitive decisions. It must validate `world-dynamic:*` against the current authoritative `roomDynamicVersion`. A `room:*` entry always requires the exact owner `regionId`/`regionEpoch` scope and opaque World-owned room component version and cannot be validated by `roomDynamicVersion` alone.
  - This `room:*` reader contract is target state only until the required opaque component version and invalidation path exist; current readers must not substitute `worldSnapshotId` or `roomDynamicVersion`.
  - Fall back to authoritative reads if the version cannot be verified.
  - TTL-only world or presentation caches must use distinct prefixes and must not be substituted for these Class A contracts.

### Cache/Rate-Limit Key Catalog

Cache/Rate-Limit Redis hosts prefixes that are not part of the coordination log and may be evicted under pressure. This table is the canonical CI/code-review-enforced registry for non-coordination Redis prefixes: new cache/rate-limit prefixes must be added here, labeled with their role, correctness class, and reset behavior, and reflected in the owning service docs before implementation is accepted.

| Prefix | Role | Correctness Class | Reset Tolerance | Owner / Semantics |
| --- | --- | --- | --- | --- |
| `inventory:<tenantId>:<containerId>` | Cache | Versioned (Class A) | Reset-tolerant | Entity Management cached inventory/container aggregates. |
| `character-cache:<tenantId>:<characterId>` | Cache | Versioned (Class A) | Reset-tolerant | Entity Management cached character graphs for hot reads. |
| `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` | Cache | Versioned (Class A) | Reset-tolerant | World Management room-scoped dynamic-state cache. |
| `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` | Cache | Versioned (Class A) | Reset-tolerant | World Management correctness-critical room snapshot cache; payload stores and validates owner `regionId`/`regionEpoch` with the opaque component version, atomically with refresh/TTL, and invalidates on epoch change. |
| `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:<sessionId>:<viewerContextHash>:<policyContextHash>` | Cache | TTL-only (Class B) | Reset-tolerant | Game Session-owned disposable presentation/redraw helper; see the [canonical Class-B contract](./system-architecture-redis-cache.md#canonical-viewroom-look-class-b-contract) for target TTL, payload, variant-budget, invalidation, fallback, and metrics. It is never semantic reconnect context, frame/output replay, a transcript archive, or a delivery ledger, and is not authoritative for fresh `LOOK`. |
| `chat:say:<tenantId>:<characterId>`, `chat:tell:<tenantId>:<conversationId>`, `chat:guild:<tenantId>:<guildId>`, `chat:city:<tenantId>:<cityId>`, `chat:account:<tenantId>:<accountId>` | Cache | TTL-only (Class B) | Reset-tolerant | Social & Groups short-lived chat history buffers. |
| `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:*`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*` | Cache / Rate-Limit | TTL-only (Class B) | Reset-tolerant | Automation & Scripting queued work items, per-script quota counters, per-tenant live execution budget counters, and tenant/cluster dry-run/test capacity leases. Durable triggers/effect tables in PostgreSQL, not Redis, guarantee eventual execution and quota correctness. |
| `ratelimit:<tenantId>:<subjectHash>:<timeWindow>` | Cache / Rate-Limit | TTL-only (Class B) | Reset-tolerant | Spring Cloud Gateway and other edge/credential rate-limit buckets. Each individual subject has one opaque stable hash; no modulo collision pool or request-derived shard is used. Reset-induced heuristic shifts are acceptable, while hard authority remains outside evictable Redis. |

CI and code review checks are expected to:

- Fail when new cache/rate-limit prefixes are introduced without being registered in this catalog.
- Ensure automation and cache tooling bind these prefixes to the Cache/Rate-Limit Redis role, not Coordination Redis.
- Preserve the contract that `automation:queue:*` and related automation caches remain best-effort buffers rather than authoritative logs.

### Cache Invalidation Policy Table

| Prefix / Aggregate | Example Key | Policy | Notes |
| --- | --- | --- | --- |
| Inventory/container views | `inventory:<tenantId>:<containerId>` | Versioned | Validated against a container or aggregate `version`/`lastModified` field in PostgreSQL. |
| Character graphs | `character-cache:<tenantId>:<characterId>` | Versioned | Backed by character graph rows with explicit versioning. |
| Dynamic world aggregates | `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` | Versioned | Backed by authoritative room-instance dynamic-state rows with `roomDynamicVersion`; invalidated on dynamic-state writes and relevant instance lifecycle changes. |
| Room topology snapshots | `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` | Versioned | Target-only cached room snapshots carry owner `regionId`/`regionEpoch` alongside the opaque World-owned component version; refresh/TTL is atomic, epoch changes invalidate, and current readers use authoritative reads when scope/version proof is unavailable. |
| Room LOOK views | `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:<sessionId>:<viewerContextHash>:<policyContextHash>` | TTL-only | See the [canonical Class-B contract](./system-architecture-redis-cache.md#canonical-viewroom-look-class-b-contract): target-only Game Session ownership, at most 5-second TTL, 64 KiB payload, four live variants per admitted session, exact-context invalidation, and uncached authoritative `ResolveLook` fallback. |
| Short-lived chat buffers | `chat:say:<tenantId>:<characterId>`, `chat:guild:<tenantId>:<guildId>`, `chat:city:<tenantId>:<cityId>`, etc. | TTL-only | Rolling windows of recent messages with fixed-size buffers. |

### Cache Size and Complexity Budgets

- Each cache prefix should document an expected key-count envelope per tenant, not just a generic “bounded” claim.
- Lists, sets, or sorted sets must declare a maximum length and enforce it via trimming or eviction logic.
- Serialized payloads should stay within a predictable size envelope, typically in the “tens of kilobytes” range rather than unbounded blob storage.
- Rate-limit prefixes should document a modest per-tenant active-subject-key envelope across all live time windows so profiling can catch runaway cardinality without using collision pools.

### Recommended Cache Metrics by Prefix Family

- `inventory:*` - hit/miss counters plus optional key-count and oversize counters.
- `character-cache:*` - hit/miss counters plus optional key-count gauges.
- `world-dynamic:*` / `room:*` - hit/miss counters plus key-count gauges where available.
- `view:room-look:*` - hit/miss, recompute, write-skip reason, oversize, active-key/variant-budget, and Redis-failure metrics as specified by the [canonical Class-B contract](./system-architecture-redis-cache.md#canonical-viewroom-look-class-b-contract).
- `chat:*` - hit/miss counters plus chat-type gauges where helpful.
- `automation:queue:*` / `automation:quota:*` / `automation:tenant-budget:*` / `automation:test:capacity:*` - queue and quota counters or equivalent service metrics that make best-effort loss and rebuild visible.
- Prefix tags should stay consistent across services so dashboards can reason about cache behavior by aggregate family rather than by one-off metric names.

### Future Work / TODO

- Keep `inventory:*` and `character-cache:*` definitions in sync with Entity Management.
- Keep `world-dynamic:*` and `room:*` definitions in sync with World Management.
- Update owning service docs with cache class, TTL strategy, reset tolerance, and example key shapes.

### Testing Caches

- Class A caches should cover miss-to-populate, version mismatch refresh, event-driven invalidation, and reset repopulation.
- Class B caches should cover TTL expiry, recomputation, and acceptable degradation on cache loss.
- Class A test coverage should explicitly assert the authoritative version field and fallback-read path named in the adoption checklist.
- Class B test coverage should explicitly assert the correctness fallback path for reads that cannot trust cached data.
- New cache prefixes must identify where those tests live and how regressions surface.

### Cache Metrics Catalog

- Emit hit/miss counters with consistent prefix and service labels.
- Add TTL and size histograms where useful.
- Use prefix tags that let dashboards reason about cache health per aggregate family.
- Highlight request paths that couple cache usage and tick identity too closely.

## Related Documentation

- [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
- [System Architecture: Redis](./system-architecture-redis.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)
