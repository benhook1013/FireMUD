# ADR 0112: Bounded Pull Settings Distribution with Freshness Classes

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SET-02`
- Primary capability: `AR-2.3` operator and tenant configuration resolution
- Affected capabilities: `AR-2.1`, `SF-2.1`, `SF-2.2`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of cache freshness, outage behavior, safety controls, operational cost, and the boundary against a general distributed configuration platform

## Context

Runtime services need effective tenant and game settings without synchronously consulting the settings authority for every action. A generalized push-distribution system could reduce propagation latency, but would introduce another distributed control plane, convergence protocol, and failure surface before measured demand justifies it.

A short cache TTL alone is incomplete: it does not identify the snapshot being used, define how stale each setting may become, or distinguish presentation preferences from admission and safety controls during an authority outage.

## Decision

FireMUD uses bounded typed settings domains and local pull-based caching. It does not build a generalized arbitrary-key configuration service or distributed push fabric.

Each authoritative scope snapshot carries a monotonic revision. Every surfaced domain or key declares its source eligibility and a freshness class with a maximum stale interval appropriate to its consequences. Writes are not described as globally instantaneous; they become effective at each consumer within the declared bound.

During a settings-authority outage, a consumer may retain its last-known-good snapshot only within that bound. Once the bound expires:

- ordinary presentation preferences may use a documented safe fallback when that key permits it;
- admission, tenant-isolation, resource-safety, or other restrictive policy must fail closed or consult a dedicated authoritative fence;
- consumers must not silently continue an indefinitely stale permissive value.

Immediate revocation and emergency fencing are not modeled as ordinary cached settings. They require an owning authoritative control path with semantics suitable for their urgency.

Local force-refresh and per-scope eviction remain supported. A later notification-plus-pull optimization may prompt consumers to refresh sooner, but the authoritative snapshot, monotonic revision, and pull validation remain the correctness boundary. Adoption requires measured propagation need; notification delivery must not become a second settings authority.

## Consequences

- Runtime reads remain local and inexpensive in steady state.
- Services can explain which revision and provenance produced an effective value.
- Outage behavior is explicit and differs according to the setting's safety consequences.
- Ordinary settings changes have bounded, not instantaneous, propagation.
- Schema metadata, consumers, diagnostics, and tests must carry revision and freshness information.
- A separate urgent control path is required when a policy genuinely needs immediate revocation semantics.

## Alternatives Considered

### General Distributed Push Configuration Platform

Rejected until measured requirements justify its operational and correctness cost. Push notification may later improve refresh latency without replacing pull-based authority.

### Synchronous Authoritative Read for Every Use

Rejected because it puts settings authority latency and availability on routine gameplay paths.

### Indefinite Last-Known-Good Values

Rejected because a stale permissive admission or safety setting can outlive an operator's intended restriction. Bounded retention remains useful, but expiry behavior must be class-specific.

### One Failure Rule for Every Setting

Rejected because harmless presentation preferences and isolation or admission controls have materially different consequences.

## Implementation and Proof Obligations

The typed schema must declare scope eligibility, freshness class, maximum stale interval, fallback or fail-closed behavior, and whether a setting is prohibited from carrying urgent revocation semantics. Effective-setting diagnostics must expose the authoritative scope revision, observation age, provenance, disregarded invalid overrides, and degraded or expired state.

Proof must cover TTL refresh, forced refresh, per-scope eviction, monotonic revision changes, bounded last-known-good retention, safe presentation fallback, fail-closed restrictive settings, authority recovery, and concurrent cap tightening. Consumers must not each invent incompatible stale-data behavior.

The current implementation provides a bounded shared persisted-override reader with short local TTL, force-refresh, and per-scope eviction. It does not yet provide the complete snapshot revision, per-key freshness classification, class-specific expiry behavior, centralized operator-default/cap resolution, or urgent-fence separation required here.

## Reversibility and Revisit Triggers

TTL values and freshness classes are adjustable schema policy. Add notification-plus-pull when observed propagation latency or authority load warrants it. Introduce a broader configuration control plane only if multiple bounded domains demonstrate requirements that cannot be met by the shared resolver and local cache model.
