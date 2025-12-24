# FireMUD Redis Cheat Sheet

This cheat sheet is the **front door** for Redis-related design and operations. It summarizes key prefixes, roles, and docs so contributors can quickly find the right reference.

> 🔗 Canonical details live in:
> - [System Architecture: Redis](./system-architecture-redis.md)
> - [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
> - [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](./system-architecture-redis-operations.md)
> - [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md)

---

## Prefix & Role Overview

This table highlights representative prefixes and their intended Redis role. It is not an exhaustive key catalog but should be treated as the **first stop** when introducing new prefixes or reviewing existing ones.

| Prefix (pattern) | Redis Role | Primary Owner / Service | Reset / Lifecycle Notes |
| --- | --- | --- | --- |
| `tick:{tenantRegionTag}:*` | **Coordination Redis** | Game Session Service | Region-scoped tick queues, locks, and pending sets. Reset only via **coordination reset** tooling described in [Redis Operations & Migrations](./system-architecture-redis-operations.md) and [Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model); never via ad-hoc `FLUSHDB`/`DEL`. |
| `timer:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Tick-region timers and scheduled effects. Subject to the same reset and tail-loss envelopes as other tick coordination keys. |
| `retry:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Retry queues for tick effects. Uses idempotent Lua semantics; reset only through documented coordination reset flows. |
| `tick-executor-lease:{tenantRegionTag}` | **Coordination Redis** | Game Session Service | Region leadership and executor lease. Must obey shard-local Lua rules; coordination resets and failover must follow the patterns in [Tick System and Runtime Design](./system-architecture-ticks.md). |
| `session:game:<tenantId>:<sessionId>` | **Coordination Redis** | Game Session Service | Gameplay session binding and reconnect state. Treated as volatile coordination data with derived expiry; removed when sessions end or expire. Recreated as part of normal login/reconnect flows, not by manual key edits. |
| `remote:<tenantId>:<entityId>:*` | **Coordination Redis** | Game Session Service / Automation & Scripting | Best-effort hint markers for remote participation. Safe to drop within tail-loss envelopes; coordination resets may clear them as part of broader tenant/region resets. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | **Coordination Redis** | Automation & Scripting Service | Tracks tick heartbeat consumption. Treated as coordination metadata; adjustments should go through documented automation tooling, not manual writes. |
| `automation:queue:<tenantId>:<entityId>` | **Cache/Rate-Limit Redis** (single-key ops) | Automation & Scripting Service | Treated as non-authoritative, queue-like coordination that must not mix with coordination prefixes in a single Lua call. Reset via automation tooling or cache-level maintenance; see [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md) and [Redis Architecture](./system-architecture-redis.md#redis-as-a-volatile-state-layer). |
| `inventory:<tenantId>:<containerId>` | **Cache/Rate-Limit Redis** | Entity Management Service (target-state) | Cached inventory/container aggregates. Safe to evict; writers must respect TTL and invalidation rules. Never stored on Coordination Redis. |
| `world-dynamic:<tenantId>:<aggregateId>` | **Cache/Rate-Limit Redis** | World Management / Game Session (aggregates) | Cached world/room dynamic views (target-state). Treated as derived state; safe to drop or miss. |
| `view:room-look:<tenantId>:<roomId>` | **Cache/Rate-Limit Redis** | Game Session / Game Logic (LOOK aggregates) | HOT-path cached room views. Non-authoritative; TTL-driven and safe to recompute. |
| `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]` | **Cache/Rate-Limit Redis** | Spring Cloud Gateway / edge services | Gateway and API rate-limit buckets. Reside only on Cache/Rate-Limit Redis; eviction is acceptable within documented behavior. |

When introducing **new prefixes**, update the owning service design doc and ensure the prefix is consistent with the role and reset expectations above.

---

## Which Doc to Read for…?

Use this table to route design and review questions to the correct source doc.

| Task / Question | Primary Doc | Supporting Docs |
| --- | --- | --- |
| Understanding overall Redis roles, tail-loss envelope, and coordination invariants | [System Architecture: Redis](./system-architecture-redis.md) | [Tick System and Runtime Design](./system-architecture-ticks.md), [System Architecture: Reconnection](./system-architecture-reconnection.md) |
| Adding or changing a **coordination prefix** (ticks, timers, sessions, leases) | [System Architecture: Redis – Key Naming and Shard Discipline](./system-architecture-redis.md#key-naming-and-shard-discipline) | This cheat sheet, service-specific READMEs (e.g., Game Session Service) |
| Adding or changing a **cache or rate-limit prefix** | [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md) | [System Architecture: Redis – Redis as a Volatile State Layer](./system-architecture-redis.md#redis-as-a-volatile-state-layer) |
| Writing or modifying **Lua scripts** for coordination | [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md) | [System Architecture: Redis – Atomicity and Concurrency Control](./system-architecture-redis.md#atomicity-and-concurrency-control) |
| Planning or executing a **coordination reset**, AOF maintenance, or migration | [Redis Operations & Migrations](./system-architecture-redis-operations.md) | [System Architecture: Redis – Coordination Reset Model](./system-architecture-redis.md#coordination-reset-model), [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md) |
| Deciding **who may run which commands** in Redis and under which ACLs | [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md) | [Redis Operations & Migrations](./system-architecture-redis-operations.md) |
| Investigating **tail-loss SLOs**, replay behavior, or failover impact | [System Architecture: Redis – Redis Availability, Consistency, and Safety Guarantees](./system-architecture-redis.md#redis-availability-consistency-and-safety-guarantees) | [Redis Operations & Migrations](./system-architecture-redis-operations.md), [Tick System and Runtime Design](./system-architecture-ticks.md) |
| Understanding **Redis usage in tests** and standard testing profiles | [System Architecture: Testing Strategy](./system-architecture-testing.md#redis-in-tests) | [System Architecture: Redis](./system-architecture-redis.md), per-service testing docs |

Treat this cheat sheet as a **routing map**, not a replacement for the detailed docs. When in doubt, start here, then follow the linked sections for full context.

