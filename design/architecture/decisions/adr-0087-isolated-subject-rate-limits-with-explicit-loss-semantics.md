# ADR 0087: Isolated Subject Rate Limits with Explicit Loss Semantics

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CACHE-02`
- Primary capability: `SF-2.2` Redis and rate-limit foundations
- Affected capabilities: `PO-2.4`, `AA-1.5`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of per-subject buckets, bounded modulo hashing, multi-signal abuse policy, cross-key atomicity, and cache-loss semantics

## Context

Hashing many unrelated clients into a small fixed number of enforcement buckets bounds Redis key count, but it also couples their allowance. One abusive client can exhaust a bucket shared with innocent players, creating collision-based denial of service and unpredictable tenant fairness. Request-based sharding can create the opposite failure by multiplying effective allowance when the shards are not atomically combined.

Rate-limit state in Cache/Rate-Limit Redis is evictable and reset-tolerant. It therefore cannot be the sole mechanism for authorization, money, durable quota, or another hard invariant whose guarantee must survive bucket loss.

## Decision

An enforcement bucket represents one actual rate-limit subject or one deliberately shared policy scope. Per-client, connection, account candidate, credential, token, or source enforcement uses a normalized opaque stable hash that preserves one-to-one subject isolation; it does not reduce subjects modulo a small common bucket count. Raw credential or address material is not embedded in keys or metric labels.

Key cardinality is bounded through TTLs, fixed live-window counts, active-subject and admission limits, per-tenant and deployment memory budgets, and explicit overload behavior. The system does not obtain boundedness by making unrelated subjects consume one another's individual allowance.

Small fixed shared buckets may represent an intentionally coarse tenant, endpoint, source class, or global pressure signal. They are not described as per-subject fairness and cannot alone impose an individual security consequence. Their collision and reset behavior is part of the declared heuristic.

A domain policy may consult a small bounded set of independently updated buckets, such as canonical source, normalized account candidate, and coarse global pressure. Each Redis mutation remains atomic for one bucket and cross-key atomic correctness is not promised. The owning domain combines those signals and accepts bounded inter-read skew; it does not use a cross-slot Lua transaction or infer that the set is an exact global counter.

Ordinary gameplay command limiting remains an in-process session-front-end token bucket under ADR 0034 and performs no Redis operation per command solely for rate limiting. Cache/Rate-Limit Redis is reserved for edge, credential, reconnect, or coarse shared abuse windows outside that routine hot path.

Every limiter declares its subject, owner, key and privacy shape, window and TTL, cardinality and memory envelope, collision semantics if deliberately shared, eviction/reset effect, store-unavailable behavior, and whether it is a heuristic or a hard gate. Cache eviction may weaken or reset a heuristic within its declared window. A rule whose loss would violate authorization, financial correctness, durable quota, or another hard invariant must use an authoritative mechanism rather than relying solely on Cache/Rate-Limit Redis. Security-sensitive entrypoints may fail closed when shared abuse enforcement is unavailable without claiming that evictable counters are durable authority.

## Consequences

- One abusive subject cannot consume an unrelated subject's individual allowance merely because of modulo-bucket collision.
- TTLs, admission bounds, and memory budgets replace fixed collision pools as the cardinality controls.
- Multi-signal credential and edge policies can remain layered without requiring cross-slot atomic transactions.
- Redis resets or eviction may temporarily weaken declared heuristics, so hard guarantees require another authority and every outage behavior must be explicit.
- Per-subject keys can consume more memory than small shared bucket pools and therefore require measured cardinality, TTL, and overload controls.

## Alternatives Considered

### Fixed Modulo Buckets for Individual Enforcement

Rejected because bounded key count is purchased through collision-based false throttling. It also makes bucket-count changes remap subjects and reset their effective histories.

### Exact Cross-Key Hierarchical Counters

Rejected as the default because atomic enforcement across source, account, tenant, endpoint, and global Redis Cluster keys introduces hash-slot coupling and makes an evictable heuristic look like correctness authority. Bounded independently evaluated signals provide the required abuse defense without that contract.

### One Redis Counter for Every Gameplay Command

Rejected by ADR 0034 because it adds a network call and shared-store dependency to the routine gameplay hot path. Session-local enforcement plus coarse shared backstops is sufficient unless measured abuse evidence justifies revisiting that decision.

## Implementation and Proof Obligations

Proof must cover stable one-to-one subject hashing, key privacy, TTL and live-window expiry, active-subject and per-tenant cardinality bounds, memory pressure and overload behavior, deliberately shared-bucket collisions, bounded multi-signal skew, Redis eviction/reset, store outage, fail-open or fail-closed behavior per entrypoint, and the separation between heuristic and authoritative limits.

Gateway currently derives rate-limit keys directly from raw client IP without an evidenced tenant/cardinality profile. Game Session currently performs Redis-backed per-session limiting and does not evidence Cache/Rate-Limit role isolation, contrary to ADR 0034. The current implementation and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Algorithms, thresholds, TTLs, and memory budgets are tunable within the declared subject and loss semantics. Revisit if measured cardinality makes isolated subject keys operationally impractical, cross-instance abuse defeats the layered policy, or a specialized rate-limit service can provide stronger isolation and outage behavior without entering the routine gameplay hot path.
