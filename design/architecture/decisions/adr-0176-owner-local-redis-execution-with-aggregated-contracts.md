# ADR 0176: Owner-Local Redis Execution with Aggregated Contracts

## Status

Accepted

## Implementation Status

This decision is not implemented. The shared Redis-contract foundation, owner-local descriptor contributions, repository aggregation, ownership enforcement, and descriptor-driven proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Revised
- Review source: `LIB-01`
- Decision date: 2026-07-21
- Decision key: `LIB-01`
- Primary capability: `SF-1.5` shared platform libraries
- Affected capabilities: `SF-2.2`, `PO-4.4`, `AS-1.5`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of Redis ownership, key and Lua drift, shared-module coupling, operator tooling, cluster-slot safety, and current implementation reality

## Context

Existing design places all Redis key builders, Lua sources, descriptors, invocation helpers, and tests in one `firemud-common` library. The repository instead uses several narrow common modules, while Redis ownership is service-scoped. Most executable coordination scripts have only one legitimate deployed caller. Centralizing owner-exclusive behavior would blur authority and release-couple unrelated services, but leaving every contract service-local by convention would prevent repository-wide drift, slot, reset, and compatibility validation.

No complete Lua registry, descriptor-driven CI harness, slot gate, or ownership enforcement currently exists. Key construction and embedded Lua are fragmented, and some generic helpers accept arbitrary multi-key inputs without proving a common Redis Cluster slot.

## Decision

Redis contracts use a two-tier ownership model.

A narrow shared Redis-contract foundation owns:

- the descriptor and registry schema;
- common outcome categories and typed result vocabulary;
- Redis role, prefix-owner, hash-tag, and cluster-slot validators;
- compatibility, reset-sensitivity, and tail-loss metadata rules;
- the repository-level registry aggregation and generic CI harness;
- executable builders, invocation machinery, or Lua primitives only when multiple independently deployed callers genuinely execute the same mutation.

Each owning service owns, for its exclusive Redis families:

- its descriptor entries and key-shape declarations expressed through the shared schema;
- generated or typed owner-local key builders;
- caller-specific invocation adapters;
- executable Lua source and focused semantic tests;
- rollout and compatibility evidence for the caller versions that can coexist.

Repository CI aggregates every owner contribution into one machine-readable catalog. It rejects unregistered Lua, duplicate or unowned prefixes, wrong Redis roles, invalid hash tags, cross-slot multi-key declarations, missing outcome/reset/tail-loss metadata, and callers whose declared `KEYS`/`ARGV` contract diverges from their descriptor. The catalog provides global visibility without moving owner-exclusive execution into a global library.

An implementation becomes shared only because there are multiple legitimate independently deployed executors, not merely because operators need visibility. Supported maintenance tooling normally calls the owning service's typed maintenance API. If a tool must execute the exact mutation directly, that makes it another caller and the mutation contract moves to the shared foundation with the corresponding authority and compatibility review.

Non-owner services do not read or write another service's private Redis keys through a shared builder. Read visibility for operations is represented by catalog metadata and owner APIs rather than by distributing executable access.

The target shared foundation should remain a narrow module such as `common-redis-contracts`; it must not turn a broadly inherited runtime module into an implicit dependency on every owner implementation.

## Consequences

- Redis authority and deployment ownership remain aligned.
- CI and operations gain one global catalog for prefixes, roles, slots, outcomes, reset behavior, and compatibility.
- Owner-only script changes do not force unrelated services to consume a new global executable library.
- Truly shared mutations pay the justified cost of a shared release contract.
- Descriptor aggregation, generation, linting, and CODEOWNERS enforcement require new build work.
- Existing embedded scripts, direct string keys, duplicate owner-local scripts, and unconstrained generic multi-key helpers require reconciliation.

## Alternatives Considered

### Centralize Every Script and Builder

This maximizes single-source reuse but blurs service authority, increases global release coupling, and gives unrelated consumers executable access to owner-private keyspaces. It remains correct for a genuinely shared mutation primitive.

### Keep Everything Service-Local by Convention

This aligns executable ownership but cannot reliably detect prefix, role, outcome, reset, or slot drift and gives maintenance tooling no complete catalog.

### Centralize Every Key Builder but Keep Lua Local

This still distributes executable knowledge of owner-private keyspaces and makes routine owner-only key evolution a global library release. Shared schema plus aggregated descriptors provides the needed visibility with less coupling.

## Implementation and Proof Obligations

Implementation must introduce the narrow contract schema, per-owner descriptor contributions, repository aggregation, descriptor-driven key builders or validation, and CI lint/test hooks. Proof must inventory every Lua source and Redis prefix, demonstrate ownership and role assignment, validate multi-key cluster slots, exercise declared outcome and no-mutation behavior, and cover the full caller/payload coexistence set required by ADR 0084.

Shared generic operations must reject arbitrary multi-key use unless the descriptor and runtime validator prove slot compatibility. CODEOWNERS or an equivalent repository review boundary must route shared-schema and owner-contribution changes to the relevant owners.

## Reversibility and Revisit Triggers

An owner-local mutation can move into the shared foundation when a second legitimate deployed executor appears. A shared primitive can return to one owner after all other callers are removed and compatibility windows close. Revisit the split only if measured change patterns show that registry aggregation costs more than the ownership separation prevents.
