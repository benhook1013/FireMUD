# ADR 0086: Owner-Validated Class A Caches and Presentation-Only Class B

## Status

Accepted

## Implementation Status

The owner-validated Class A and presentation-only Class B boundary is target state. The current `room:*` reader remains an unversioned TTL-only implementation without the required owner proof or focused validation.

## Canonical Design

- [Redis Cache & Rate Limiting](../system-architecture-redis-cache.md)
- [Redis Cache & Rate Limiting Reference](../system-architecture-redis-cache-reference.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CACHE-01`
- Primary capability: `SF-2.2` Redis and cache foundations
- Affected capabilities: `GR-2.3`, `GR-3.2`, `EA-1.2`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of correctness-sensitive caching, owner-local validated reads, authoritative-only reads, and TTL-only presentation caches
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CACHE-01`

## Context

Cache invalidation and TTLs do not by themselves prove that a cached aggregate is current. A cache can accelerate an owning service's read only when that service can prove the payload's complete scope and current authoritative version or fence. Other services must not bypass that ownership boundary by reading the owner's cache keys directly.

## Decision

Class A caches are owner-local, version- or fence-validated read accelerators. Only the service that owns the authoritative aggregate may use a Class A entry for a correctness-sensitive read, and it must obtain currentness proof during that operation from the authoritative store, an owner-controlled version index with equivalent consistency, or an exact expected version/fence already required by the operation. An exact expected version/fence is valid for this purpose only when the owning service derives it from, or authenticates it against, authoritative workflow/operation state; a caller-supplied or otherwise untrusted value is not currentness proof. Cache presence, TTL, an invalidation event, or a version embedded only in the cached payload is not sufficient proof.

If validation is unavailable, ambiguous, below the required fence, or mismatched, the owner falls back to an authoritative read and rebuilds the cache or fails closed when the operation cannot safely proceed. Mutations continue to enforce the owning datastore's authoritative precondition, lock, version, or idempotency guard regardless of how their preceding read was served.

Other services call the owning API; they do not read an owner's Class A Redis keys directly. Owner invalidation reduces stale hits and load but does not replace currentness validation.

Class B caches are disposable TTL-only presentation or performance data for which bounded staleness is accepted. They may support reconnect redraw, rendered `LOOK` views, analytics, or debugging, but never movement, combat, pathing, visibility, authorization, financial, or other correctness-sensitive decisions. A rendered room-view cache must bind the exact room, viewer/session, and policy context in its key or equivalent opaque binding; a generic room payload is not a safe substitute.

Every prefix declares its owner, authoritative source, scope, correctness class, validation or accepted-staleness rule, invalidator, TTL, reset behavior, and payload schema. A prefix whose semantics change must be migrated or versioned rather than silently reusing old TTL-only entries as validated data.

## Consequences

- Owner-validated caches can reduce reconstruction cost without becoming an authority.
- Non-owner services retain service boundaries and cannot bypass domain validation through Redis.
- Class A cache hits still require a version/fence proof, which can reduce their benefit when no cheap authoritative metadata path exists.
- Class B presentation caches retain simple TTL behavior and explicit bounded staleness but cannot be reused for gameplay decisions.
- Viewer/session/policy binding increases rendered-cache key cardinality and must be bounded by the owning presentation contract.

## Alternatives Considered

### Prohibit Redis from Every Correctness-Sensitive Read

Rejected as the universal rule because an owning service can safely avoid reconstructing expensive aggregates when it proves an exact authoritative version or fence. Features without a cheap trustworthy proof path should still use this alternative.

### Allow Event Invalidation or TTL Alone for Class A

Rejected because delayed, lost, or reordered invalidation and unexpired stale entries can make gameplay depend on old state. Events and TTLs are load and cleanup mechanisms, not sufficient currentness proof.

## Implementation and Proof Obligations

Each Class A prefix must prove complete scope, atomic payload/version publication, authoritative version or fence acquisition, current-version cache hits, mismatch and unavailable-validation fallback, stale invalidation races, concurrent mutation, Redis loss/reset, and authoritative mutation guards after cached reads. Tests must prove that stale or wrong-scope entries cannot change the logical outcome.

Each Class B prefix must prove that it is absent from correctness-sensitive call paths and that its declared staleness, TTL, size, eviction, reset, and reconstruction behavior are acceptable. Rendered room-view caches must prove exact room/viewer/session/policy-context binding.

The current `room:*` implementation remains an unversioned TTL-only payload without the target Class A validation or invalidation proof. Reusing that prefix for the target Class A contract requires an explicit payload/key migration. The current implementation and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Individual prefixes may move from Class B to Class A only after adding the owner, version/fence, migration, fallback, and proof contract. A Class A cache may be removed without semantic change because the authoritative read remains available. Revisit the model if an owner cannot obtain validation cheaply enough for measured performance needs or a future coherent read model provides equivalent proof outside Redis.
