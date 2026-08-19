# ADR 0113: Bounded Pull Settings Distribution with Freshness Classes

## Status

Accepted

## Implementation Status

The current short-TTL, force-refresh, and per-scope eviction reader is a bounded implementation seam. It does not yet prove monotonic revision handling, atomic revision/content compare-and-set, class-specific stale behavior, or the restrictive-setting fence; concrete freshness durations remain schema policy and implementation work. Missing authoritative scope revisions and consumer CAS application are implementation gaps, not shipped behavior.

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SET-02`
- Primary capability: `AR-2.3` operator and tenant configuration resolution
- Affected capabilities: `AR-2.1`, `SF-2.1`, `SF-2.2`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SET-02`

## Context

Runtime services need effective tenant and game settings without synchronously consulting the settings authority for every action. A generalized push-distribution system would add another distributed control plane and convergence surface before measured demand justifies it.

## Decision

FireMUD uses bounded typed settings domains and local pull-based caching. It does not build a generalized arbitrary-key configuration service or distributed push fabric.

Each authoritative scope snapshot carries a monotonic revision. A consumer applies an authority response through an atomic compare-and-set against its effective snapshot: a strictly newer scope revision replaces the effective value and its diagnostics together. An equal revision is accepted only when its canonical content/digest is identical; a lower revision or a contradictory equal revision is rejected before changing either the effective value or diagnostics, while the last-known-good value and evidence remain available. Every surfaced domain or key declares its source eligibility and a freshness class with a maximum stale interval appropriate to its consequences. Writes become effective at each consumer within the declared bound; they are not described as globally instantaneous.

During a settings-authority outage, a consumer may retain its last-known-good snapshot only within that bound. After expiry, ordinary presentation preferences may use a documented safe fallback when the key permits it, while admission, tenant-isolation, resource-safety, and other restrictive policy must fail closed or consult a dedicated authoritative fence. Consumers must not silently continue an indefinitely stale permissive value.

Immediate revocation and emergency fencing are not ordinary cached settings. They require an owning authoritative control path with semantics suitable for their urgency. A later notification-plus-pull optimization may prompt earlier refresh, but notifications do not become a second settings authority.

## Consequences

- Runtime reads remain local and inexpensive in steady state.
- Services can explain which revision, freshness class, and provenance produced an effective value.
- Outage behavior is explicit and differs according to a setting's safety consequences.
- Ordinary settings changes have bounded, not instantaneous, propagation.
- Schema metadata, consumers, diagnostics, and tests must carry revision and freshness information.

## Implementation and Proof Obligations

The typed schema must declare scope eligibility, freshness class, maximum stale interval, fallback or fail-closed behavior, and whether a setting is prohibited from carrying urgent revocation semantics. Effective-setting diagnostics must expose authoritative scope revision, observation age, provenance, disregarded invalid overrides, and degraded or expired state.

Proof must cover refresh, forced refresh, per-scope eviction, monotonic revision changes, atomic newer-revision application, equal-revision identical-content acceptance, lower-revision rejection, contradictory equal-revision rejection, preservation of last-known-good value/evidence and diagnostics on every rejected response, bounded last-known-good retention, safe presentation fallback, fail-closed restrictive settings, authority recovery, and concurrent cap tightening. Consumers must not invent incompatible stale-data behavior.

## Related Contracts

- [Settings Model](../system-architecture-settings-model.md)
- [Game Session configuration](../microservices/game-session-service/configuration.md)
- [Settings precedence and constraints](./adr-0012-settings-value-precedence-and-constraints.md)
