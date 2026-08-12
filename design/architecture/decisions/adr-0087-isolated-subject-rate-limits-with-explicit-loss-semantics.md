# ADR 0087: Isolated Subject Rate Limits with Explicit Loss Semantics

## Status

Accepted

## Implementation Status

The isolated-subject and explicit-loss rate-limit boundary is target state. Gateway raw-IP keying and Game Session's legacy `ratelimit:<sessionId>` Redis-backed per-session limiting remain implementation drift; target helper adoption must first drain that legacy shape by TTL or use a distinct versioned namespace. The current implementation and focused proof are not claimed.

## Canonical Design

- [Redis Cache & Rate Limiting](../system-architecture-redis-cache.md)
- [Redis Usage & Profiles](../system-architecture-redis-usage-and-profiles.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CACHE-02`
- Primary capability: `SF-2.2` Redis and rate-limit foundations
- Affected capabilities: `PO-2.4`, `AA-1.5`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of per-subject buckets, bounded modulo hashing, multi-signal abuse policy, cross-key atomicity, and cache-loss semantics
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CACHE-02`

## Context

Hashing unrelated clients into a fixed modulo bucket couples their allowance and permits collision-based denial of service. Conversely, request-based sharding can multiply effective allowance when shards are not atomically combined. Cache/Rate-Limit Redis is evictable and reset-tolerant, so it cannot be the sole authority for authorization, money, durable quota, or another hard invariant.

## Decision

An enforcement bucket represents one actual rate-limit subject or one deliberately shared policy scope. Per-client, connection, account candidate, credential, token, or source enforcement uses a normalized opaque stable hash that preserves one-to-one subject isolation; it never maps individual subjects modulo a small common bucket count. Raw credential or address material is not embedded in keys or metric labels.

Cardinality is bounded through TTLs, fixed live-window counts, active-subject and admission limits, per-tenant and deployment memory budgets, and explicit overload behavior. Small fixed shared buckets may represent intentionally coarse tenant, endpoint, source-class, or global-pressure signals, but they are heuristics and cannot alone impose an individual security consequence.

A domain policy may consult a small bounded set of independently updated buckets. Each Redis mutation remains atomic for one bucket; cross-key atomic correctness is not promised. The owning domain combines those signals under bounded inter-read skew and does not infer an exact global counter.

Ordinary gameplay command limiting remains an in-process session-front-end token bucket under ADR 0034 and performs no Redis operation per command solely for rate limiting. Cache/Rate-Limit Redis is reserved for edge, credential, reconnect, or coarse shared abuse windows outside that routine hot path.

Every limiter declares its subject, owner, key/privacy shape, window and TTL, cardinality and memory envelope, collision semantics if deliberately shared, eviction/reset effect, store-unavailable behavior, and whether it is a heuristic or hard gate. A rule whose loss would violate authorization, financial correctness, durable quota, or another hard invariant must use an authoritative mechanism rather than relying solely on evictable Redis.

## Consequences

- One abusive subject cannot consume an unrelated subject's individual allowance through modulo collision.
- TTLs, admission bounds, and memory budgets replace fixed collision pools as cardinality controls.
- Multi-signal edge and credential policies can remain layered without cross-slot atomic transactions.
- Redis reset or eviction may temporarily weaken declared heuristics, so hard guarantees require another authority and every outage behavior must be explicit.
- Per-subject keys can consume more memory than shared pools and require measured cardinality, TTL, and overload controls.

## Alternatives Considered

### Fixed Modulo Buckets for Individual Enforcement

Rejected because bounded key count is purchased through collision-based false throttling. Bucket-count changes also remap subjects and reset their effective histories.

### Exact Cross-Key Hierarchical Counters

Rejected as the default because atomic enforcement across source, account, tenant, endpoint, and global Redis Cluster keys introduces hash-slot coupling and makes an evictable heuristic look like correctness authority.

### One Redis Counter for Every Gameplay Command

Rejected by ADR 0034 because it adds a network call and shared-store dependency to the routine gameplay hot path. Session-local enforcement plus coarse shared backstops is sufficient unless measured abuse evidence justifies revisiting it.

## Implementation and Proof Obligations

Proof must cover stable one-to-one subject hashing, key privacy, TTL and live-window expiry, active-subject and per-tenant cardinality bounds, memory pressure and overload behavior, deliberately shared-bucket collisions, bounded multi-signal skew, Redis eviction/reset, store outage, fail-open or fail-closed behavior per entrypoint, and separation between heuristic and authoritative limits.

Gateway currently derives rate-limit keys directly from raw client IP without an evidenced tenant/cardinality profile. Game Session currently performs Redis-backed per-session limiting and does not evidence Cache/Rate-Limit role isolation, contrary to ADR 0034. The current implementation and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Algorithms, thresholds, TTLs, and memory budgets are tunable within the declared subject and loss semantics. Revisit if measured cardinality makes isolated subject keys impractical, cross-instance abuse defeats the layered policy, or a specialized rate-limit service can provide stronger isolation and outage behavior without entering the routine gameplay hot path.
