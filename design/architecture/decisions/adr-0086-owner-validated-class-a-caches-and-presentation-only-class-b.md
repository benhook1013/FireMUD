# ADR 0086: Owner-Validated Class A Caches and Presentation-Only Class B

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CACHE-01`
- Primary capability: `SF-2.2` Redis and cache foundations
- Affected capabilities: `GR-2.3`, `GR-3.2`, `EA-1.2`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of correctness-sensitive caching, owner-local validated reads, authoritative-only reads, and TTL-only presentation caches

## Context

The existing cache policy simultaneously said that no cache may decide movement, pathing, or visibility and that World Management Class A caches may participate in those decisions. A blanket prohibition would remove most value from validated caches, while treating a cached value as authority without a current version or fence would let stale state change gameplay.

The relevant distinction is not whether Redis supplied bytes. It is whether the owning domain proves that the payload represents the authoritative aggregate version required by the operation and still enforces authoritative mutation preconditions.

## Decision

Class A caches are owner-local, version- or fence-validated read accelerators. Only the service that owns the authoritative aggregate may use a Class A entry for a correctness-sensitive read, and only when it proves during that operation that the cached payload's complete scope and version or fence match the current authoritative requirement.

The owner may obtain that proof from its authoritative store, an owner-controlled version index with equivalent consistency, or an exact expected version/fence already required by the operation. Cache presence, TTL, an invalidation event, or a version embedded only in the cached payload is not proof of currentness.

If validation is unavailable, ambiguous, below the required fence, or mismatched, the owner falls back to an authoritative read and rebuilds the cache or fails closed when the operation cannot safely proceed. It never uses the stale value. Mutations continue to enforce the owning datastore's authoritative precondition, lock, version, or idempotency guard regardless of how their preceding read was served.

Other services do not read an owner's Class A Redis keys directly. They call the owning API, which may internally satisfy the read from a validated cache. Owner invalidation reduces stale hits and load but does not replace validation where correctness depends on currentness.

Class B caches are disposable TTL-only presentation or performance data for which bounded staleness is an accepted product property. They may support reconnect redraw, rendered `LOOK` views, analytics, debugging, or other declared non-authoritative surfaces. They never feed movement, combat, pathing, visibility, authorization, financial, or other correctness-sensitive decisions.

Every prefix declares its owner, authoritative source, scope, correctness class, validation or accepted-staleness rule, invalidator, TTL, reset behavior, and payload schema. A prefix whose current payload semantics differ from a new Class A contract must be migrated or versioned rather than silently aliasing old TTL-only entries as validated data.

## Consequences

- Correctly validated owner-local caches can reduce reconstruction and read cost on gameplay paths without making Redis an authority.
- Non-owner services retain clean service boundaries and cannot bypass domain validation through direct cache reads.
- Class A cache hits still incur a version/fence proof, which can reduce their benefit when no cheap authoritative metadata path exists.
- Cache validation, fallback, invalidation, payload migration, and race testing add implementation complexity.
- Class B presentation caches retain simple TTL behavior and explicit bounded staleness but cannot be reused for gameplay decisions.

## Alternatives Considered

### Prohibit Redis from Every Correctness-Sensitive Read

This is the strongest alternative because it removes cache invalidation and version races and makes cache loss unable to affect correctness. It is rejected as the universal rule because an owning service can safely avoid reconstructing expensive aggregates when it already proves an exact authoritative version or fence. Features without a cheap and trustworthy proof path should use this alternative and avoid Class A caching.

### Allow Event Invalidation or TTL Alone for Class A

Rejected because delayed, lost, or reordered invalidation and unexpired stale entries can make gameplay depend on old state. Events and TTLs are load and cleanup mechanisms, not sufficient currentness proof.

## Implementation and Proof Obligations

Each Class A prefix must prove complete scope, atomic payload/version publication, authoritative version or fence acquisition, current-version cache hits, mismatch and unavailable-validation fallback, stale invalidation races, concurrent mutation, Redis loss/reset, and authoritative mutation guards after cached reads. Tests must prove that a stale or wrong-scope entry cannot change the logical outcome.

Each Class B prefix must prove it is absent from correctness-sensitive call paths and that its declared staleness, TTL, size, eviction, reset, and reconstruction behavior are acceptable.

The current `room:*` implementation returns an unversioned TTL-only payload without the documented Class A validation or invalidation behavior. Reusing that prefix for the target Class A contract requires an explicit payload/key migration. The current implementation and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Individual prefixes may move from Class B to Class A only after adding the owner, version/fence, migration, fallback, and proof contract. A Class A cache may be removed without semantic change because the authoritative read remains available. Revisit the model if an owner cannot obtain validation cheaply enough for measured performance needs or a future coherent read model provides equivalent proof outside Redis.
