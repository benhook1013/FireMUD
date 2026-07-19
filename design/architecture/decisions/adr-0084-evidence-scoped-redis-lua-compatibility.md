# ADR 0084: Evidence-Scoped Redis Lua Compatibility

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `REDIS-04`
- Primary capability: `SF-2.2` Redis topology, contracts, and safe evolution
- Affected capabilities: `SF-1.5`, `GR-1.3`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of deterministic execution, mutation outcomes, stored-schema compatibility, missing-version evidence, immutable version-specific scripts, rolling coexistence, and reset-scoped alternatives

## Context

Redis Lua scripts can observe stored values written by older deployments while being invoked by callers from a rolling set of application versions. Treating compatibility as an arbitrary permanent current-and-previous-version rule can retain obsolete behavior without proving that those versions actually coexist. Treating every script change as compatible can instead reinterpret stored data or partially mutate state before discovering an unsupported shape.

Compatibility must be defined by the callers and payloads that can actually coexist, and by preservation of the script's mutation semantics, not only by whether a script parses several shapes.

## Decision

Every Redis Lua script is deterministic from its declared `KEYS`, `ARGV`, and current Redis state. It does not derive correctness behavior from Redis `TIME`, randomness, process-local state, completion order, or undeclared external input.

Mutation scripts return explicit, low-cardinality outcomes whose meanings are declared for that mutation class. Callers branch on those stable outcomes rather than free-form messages, truthiness, or incidental Redis return shapes. Every supported outcome defines whether mutation occurred and the state the caller may rely on.

Scripts validate the schema versions and required shapes they will read before performing a mutation. An unknown version fails with an explicit unsupported-version outcome and leaves all targeted state unchanged.

Compatibility support covers every caller version and stored payload version that evidence shows may actually coexist during a supported rollout, rollback, recovery, or retained-data window. It is not a permanent arbitrary `N`/`N-1` promise. A rollout may require one, two, or more versions according to that evidence, and obsolete support may be removed once coexistence is no longer possible under the supported deployment and data-retention contract.

A missing version field maps to a legacy version only when the script-family compatibility record identifies one unambiguous legacy shape and focused proof shows that every versionless value in scope has that meaning. Without that proof, missing version is unsupported and fails without mutation.

Immutable version-specific scripts are allowed. A rollout may retain an old script identity for old callers and introduce a new immutable script identity for new callers when that is the clearest way to cover the evidenced coexistence set. A shared multi-version script is also allowed when it safely implements every supported caller and stored-data combination.

`compatible` means semantically safe across all supported callers and stored data: the script preserves the mutation class's ownership, fencing, idempotency, validation, outcome, and no-partial-mutation guarantees. Parsing both versions or retaining the same key names is not sufficient evidence of compatibility.

When the required caller and stored-data versions cannot safely coexist, the rollout uses the smallest reset scope that removes the incompatible coordination state under the canonical fenced reset and reconciliation workflow. Compatibility impossibility does not justify a broader reset when a narrower region, tenant, prefix family, or other supported scope is sufficient.

## Consequences

- Rolling changes support the versions that can truly overlap without creating indefinite arbitrary backward-compatibility obligations.
- Unknown or ambiguous stored shapes fail closed before mutation instead of being silently reinterpreted.
- Stable low-cardinality outcomes keep caller behavior, metrics, and alerting bounded and reviewable.
- Immutable version-specific scripts can simplify risky transitions, but supported callers may temporarily require several registered script identities.
- Compatibility records and rollout evidence must track caller versions, stored payload versions, outcome semantics, and reset sensitivity for every mutation script.
- Proven-incompatible changes may require a fenced scoped reset and the associated temporary availability or player-session impact.

## Alternatives Considered

### Immutable Version-Specific Scripts Only

This is the strongest alternative because an existing script never changes behavior, callers can pin an exact identity, and rollback can retain the prior implementation. It is rejected as the exclusive rule because immutable identities alone do not resolve mixed stored payloads, missing-version ambiguity, shared key evolution, or the operational burden of retaining and selecting every historical script. Immutable version-specific scripts remain an allowed rollout technique when they safely cover the evidenced coexistence set.

### Permanent Current-and-Previous-Version Support

Rejected because `N`/`N-1` can be too narrow when more versions or retained payloads actually coexist and unnecessarily broad when they do not. Compatibility scope follows deployment and stored-data evidence rather than a fixed ordinal formula.

### Treat Every Parseable Change as Compatible

Rejected because syntactic acceptance does not prove equivalent fencing, idempotency, mutation, and outcome behavior and can allow an unsupported caller or payload to change state incorrectly.

## Implementation and Proof Obligations

Maintain a registry for every coordination Lua script that records its immutable identity or upgrade relationship, mutation class, declared keys and arguments, explicit outcome set, supported caller versions, supported stored payload versions, versionless legacy rule when any, compatibility mode, and smallest required reset scope when coexistence is impossible.

Proof must cover determinism from declared inputs and state; absence of time and randomness dependencies; every mutation-class outcome and its mutation/no-mutation meaning; validation before mutation; unknown-version non-mutating failure; proven and rejected versionless legacy payloads; every evidenced caller/payload coexistence combination; rolling upgrade and rollback; immutable old/new script selection where used; semantic fencing and idempotency preservation; incompatible coexistence detection; and the fenced smallest-scope reset and reconciliation path.

The current registry coverage, mutation-outcome standardization, caller and stored-version coexistence evidence, versionless legacy proof, reset-sensitivity declarations, and focused compatibility proof are incomplete and are not claimed by this decision.

## Reversibility and Revisit Triggers

Supported version sets and the choice between a shared multi-version script and immutable version-specific scripts may change as rollout and retained-data evidence changes without weakening semantic compatibility. Revisit this decision only if the deployment model can provide a stronger atomic script-and-data cutover that eliminates coexistence, or if a new Redis execution model requires compatibility evidence that cannot be represented by caller versions, stored payload versions, and scoped reset sensitivity.
