# FireMUD Redis Cheat Sheet

This cheat sheet is the **front door** for Redis-related design and operations. It summarizes key prefixes, roles, and docs so contributors can quickly find the right reference.

Treat it as a **routing map and curated subset**:

- Canonical coordination/reset details (including reset-tolerance classes) live in the reset policy matrix and any extended catalogs in `system-architecture-redis-reset-and-recovery.md`.
- Canonical cache/rate-limit prefix details (including correctness class and reset behavior) live in the Cache/Rate-Limit Key Catalog in `system-architecture-redis-cache.md`.
- When adding new prefixes, always update the canonical catalog first, then reflect representative entries here as needed for discoverability.

> 🔗 Canonical details live in:
>
> - [System Architecture: Redis](./system-architecture-redis.md)
> - [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md)
> - [Redis Reset & Recovery](./system-architecture-redis-reset-and-recovery.md)
> - [Redis Design Checklist](./system-architecture-redis-design-checklist.md)
> - [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
> - [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](./system-architecture-redis-operations.md)
> - [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md)

---

## Prefix & Role Overview

This table highlights representative prefixes and their intended Redis role. It is not an exhaustive key catalog but should be treated as the **first stop** when introducing new prefixes or reviewing existing ones.

| Prefix (pattern) | Redis Role | Primary Owner / Service | Reset / Lifecycle Notes |
| --- | --- | --- | --- |
| `tick:{tenantRegionTag}:*` | **Coordination Redis** | Game Session Service | Region-scoped tick queues, locks, and pending sets. Reset only via **coordination reset** tooling described in [Redis Operations & Migrations](./system-architecture-redis-operations.md) and [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model); never via ad-hoc `FLUSHDB`/`DEL`. |
| `timer:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Tick-region timers and scheduled effects. Subject to the same reset and tail-loss envelopes as other tick coordination keys. |
| `retry:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Retry queues for tick effects. Uses idempotent Lua semantics; reset only through documented coordination reset flows. |
| `tick-executor-lease:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Region leadership and executor lease. Must obey shard-local Lua rules; coordination resets and failover must follow the patterns in [Tick System and Runtime Design](./system-architecture-ticks.md). |
| `session:game:<tenantId>:<gameInstanceId>:<sessionId>` | **Coordination Redis** | Game Session Service | Gameplay session binding and reconnect state. Treated as volatile coordination data with derived expiry; removed when sessions end or expire. Recreated as part of normal login/reconnect flows, not by manual key edits. Region-scoped coordination resets preserve these keys by default; tenant/cluster resets may invalidate them per reset policy. |
| `sessionctx:*` | **Coordination Redis** | Game Session Service | Current implementation-local bootstrap/session-context keys for pre-auth transport context and authenticated session lookup indexes. Not region-local gameplay authority; gameplay commands still require authenticated `LOGIN` / `PLAY` state and region binding. |
| `session:auth:<scope>:<tokenHash>` (for example `session:auth:account:<accountId>:<tokenHash>`, `session:auth:tenant:<tenantId>:<tokenHash>`, `session:auth:global:<accountId>:<tokenHash>`) | **Coordination Redis** | Account Service | Server-side JWT/session allowlist entries as described in [Authentication](./system-architecture-authentication.md). Ownership is intentionally split from `session:game:*`: auth allowlists remain Account-owned while gameplay bindings remain Game Session-owned. Reset-sensitive: tenant/cluster coordination resets may force re-authentication and token re-issuance. |
| `remote:<tenantId>:<entityId>` | **Coordination Redis** | Game Session Service / Automation & Scripting | Best-effort, TTL-bounded hint marker for remote participation (typically “this entity has remote follow-ups due”). Default `remote_hint_ttl_ms = 60_000`. Safe to drop within tail-loss envelopes and under tenant- or cluster-scoped coordination resets; region-scoped resets leave these tenant-scoped hints intact. Ops enumeration may use `remote:<tenantId>:*` patterns, but the canonical key shape is one key per entity. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | **Coordination Redis** | Automation & Scripting Service | Tracks tick heartbeat consumption. Treated as coordination metadata; adjustments should go through documented automation tooling, not manual writes. |
| `tick-events:{tenantRegionTag}` and `tick-events-offset:{tenantRegionTag}` | **Coordination Redis** | Game Session Service / Automation & Scripting | Best-effort tick event stream and offsets used only as observer/wakeup hints (for example, reconnection hints and faster work discovery). Stream retention is capped (default `tick_events_maxlen = 2048` per region). Correctness derives from committed heartbeats/RegionStatus plus durable schedules; missing/duplicated events must not change which schedules eventually fire. |
| `automation:queue:<tenantId>:*` | **Cache/Rate-Limit Redis** (single-key ops) | Automation & Scripting Service | Treated as a non-authoritative, best-effort queue on Cache/Rate-Limit Redis that must not mix with coordination prefixes in a single Lua call. Reset via automation tooling or cache-level maintenance; see [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md) and [Redis Architecture](./system-architecture-redis.md#redis-as-a-volatile-state-layer). |
| `automation:quota:<tenantId>:<scriptId>` | **Cache/Rate-Limit Redis** | Automation & Scripting Service | Best-effort per-script quota counters. Safe to drop; budgets are re-established from configuration and durable state. |
| `inventory:<tenantId>:<containerId>` | **Cache/Rate-Limit Redis** | Entity Management Service (target-state) | Cached inventory/container aggregates. Safe to evict; writers must respect TTL and invalidation rules. Never stored on Coordination Redis. |
| `character-cache:<tenantId>:<characterId>` | **Cache/Rate-Limit Redis** | Entity Management Service (target-state) | Cached character graphs for hot reads. Versioned Class A cache; eviction/reset only affects latency. |
| `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` | **Cache/Rate-Limit Redis** | World Management Service | Cached room-scoped dynamic world state. Versioned Class A cache validated against `roomDynamicVersion`; safe to drop but must be rebuilt from authoritative World Management state. |
| `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` | **Cache/Rate-Limit Redis** | World Management Service | Cached room topology snapshots/navigation slices scoped to a running instance. Versioned Class A cache; safe to drop and recompute from PostgreSQL. |
| `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` | **Cache/Rate-Limit Redis** | Game Session (LOOK aggregates) | HOT-path cached room views. Class B, TTL-only caches owned and accessed directly only by Game Session; non-authoritative and safe to recompute. Game Logic consumes LOOK results via gRPC and does not read this prefix directly. |
| `chat:say:<tenantId>:<characterId>`, `chat:tell:<tenantId>:<conversationId>`, `chat:guild:<tenantId>:<guildId>`, `chat:account:<tenantId>:<accountId>` | **Cache/Rate-Limit Redis** | Social & Groups Service | Short-lived chat history buffers. TTL-only, reset-tolerant caches; authoritative history (where required) lives in PostgreSQL and clients must tolerate gaps after resets/TTL truncation. |
| `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]` | **Cache/Rate-Limit Redis** | Spring Cloud Gateway / edge services | Gateway and API rate-limit buckets. Reside only on Cache/Rate-Limit Redis; eviction is acceptable within documented behavior. |

When introducing **new prefixes**, update the owning service design doc and ensure the prefix is consistent with the role and reset expectations above.

---

## Which Doc to Read for…?

Use this table to route design and review questions to the correct source doc.

| Task / Question | Primary Doc | Supporting Docs |
| --- | --- | --- |
| Understanding overall Redis roles, tail-loss envelope, and coordination invariants | [System Architecture: Redis](./system-architecture-redis.md) | [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md), [Tick System and Runtime Design](./system-architecture-ticks.md), [System Architecture: Reconnection](./system-architecture-reconnection.md) |
| Adding or changing a **coordination prefix** (ticks, timers, sessions, leases) | [Redis Design Checklist – Coordination Prefix](./system-architecture-redis-design-checklist.md#coordination-prefix-checklist) | [System Architecture: Redis – Key Naming and Shard Discipline](./system-architecture-redis.md#key-naming-and-shard-discipline), service-specific READMEs |
| Adding or changing a **cache or rate-limit prefix** | [Redis Design Checklist – Cache / Rate-Limit Prefix](./system-architecture-redis-design-checklist.md#cache--rate-limit-prefix-checklist) | [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md), [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md) |
| Writing or modifying **Lua scripts** for coordination | [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md) | [Redis Design Checklist – Lua Script](./system-architecture-redis-design-checklist.md#lua-script-checklist) |
| Planning or executing a **coordination reset**, AOF maintenance, or migration | [Redis Reset & Recovery](./system-architecture-redis-reset-and-recovery.md) | [Redis Operations & Migrations](./system-architecture-redis-operations.md), [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md) |
| Deciding **who may run which commands** in Redis and under which ACLs | [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md) | [Redis Operations & Migrations](./system-architecture-redis-operations.md) |
| Investigating **tail-loss SLOs**, replay behavior, or failover impact | [Redis Operations & Migrations – Tail-Loss SLO Observability](./system-architecture-redis-operations.md#tail-loss-slo-observability) | [System Architecture: Redis](./system-architecture-redis.md), [Tick System and Runtime Design](./system-architecture-ticks.md) |
| Understanding **Redis usage in tests** and standard testing profiles | [System Architecture: Testing Strategy](./system-architecture-testing.md#redis-in-tests) | [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md), per-service testing docs |

Treat this cheat sheet as a **routing map**, not a replacement for the detailed docs. When in doubt, start here, then follow the linked sections for full context.
