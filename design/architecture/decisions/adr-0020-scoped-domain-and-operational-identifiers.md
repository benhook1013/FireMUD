# ADR 0020: Opaque Durable and Scoped Runtime Identifiers

## Status

Accepted

## Implementation Status

The accepted UUID logical-identifier decision is target state. Current public and cross-service contracts still have numeric identifier gaps, and runtime numeric identifiers still need explicit scoped-stability and non-reuse proof. Existing numeric database keys remain implementation details where they are already private; this status does not claim identifier migration or runtime proof is complete.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `SF-1.2` Shared identifiers and canonical reference contracts
- Affected capabilities: `GR-2.1`, `GR-3.1`, `AR-1.5`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `ID-01`

## Context

FireMUD must prevent accounts, tenants, authored templates, published versions, running game instances, and live entities from aliasing each other. Durable identities also need to survive storage changes, import/export, and references created by independent authoring tools.

The implementation predominantly uses database-allocated numeric identifiers for tenants, versions, game instances, characters, templates, and entities. Some values are serialized as decimal strings, while runtime rooms use an `R-<rowId>` token. Existing design concurrency does not depend on resource UUIDs: revisions, commit identities, request identities, dedupe records, and optimistic epochs carry that responsibility. Numeric-only authored template identity would nevertheless force offline, imported, plugin-provided, or independently parallel graph creation to coordinate with the owning database before creating durable cross-references.

## Decision

Identifier safety comes from declared kind, complete scope, ownership, stability, and authorization. Identifier allocation must additionally preserve independent creation where the product requires it.

### Durable Public and Cross-Service Identifiers

- `accountId`, `tenantId`, `versionId`, `gameInstanceId`, and `characterId` are opaque UUID logical identifiers. Authored template IDs are client-allocatable opaque UUID logical identifiers.
- Human-readable version numbers, slugs, names, paths, and menu indices are selectors or presentation values, not substitutes for those durable identities.
- A canonical cross-service reference carries an explicit object kind and complete scope. Template references use `(tenantId, versionId, templateId)`; runtime references use `(objectKind, tenantId, gameInstanceId, instanceId)`.
- APIs use typed reference messages so an object kind is part of the wire type and cannot collide with another identifier family. A bare generic `id`, or a shared string/id pair whose kind is inferred by the consumer, is not a sufficient consequential cross-service contract.
- Services may keep numeric primary and join keys for local persistence and query efficiency. Those keys remain private implementation details and may not replace or be reversibly exposed as the canonical UUID identity.
- Template UUIDs may be allocated before persistence so independently created graph objects can refer to one another without a database round trip. Import/export preserves them when retaining object identity and explicitly remaps them when cloning or resolving a collision.
- UUID opacity reduces casual enumeration but never substitutes for tenant scope or authorization. Implementations must assume every identifier can become known to an attacker.

### Design and Runtime Namespaces

- Template identifiers never identify live objects. Runtime entities, rooms, items, and generated objects receive runtime identities scoped to the running game instance.
- A logical authored object may retain its template identifier across versions when it remains the same object; `versionId` pins the exact published representation. A fork or semantically new replacement receives a new template identifier, with explicit mappings where migration is intended.
- A replacement game instance receives a new UUID `gameInstanceId`; runtime state cannot silently alias the prior instance.
- Live room, entity, and item instance IDs may be concurrency-safely allocated stable numbers within `(tenantId, gameInstanceId)`. Future independently allocating runtime shards must introduce an explicit collision-free allocator before using numeric IDs across those shards.
- Numeric runtime instance IDs may be shown to authorized players and accepted in commands when useful for distinguishing otherwise similar entities. Player-visible presentation does not remove tenant, game-instance, room, visibility, or authorization checks.

### High-Entropy Operational Identities

Security, idempotency, command, event, effect, workflow-request, and correlation identities retain their owning contracts for high entropy or collision resistance. Session/token material must be unpredictable; command/event/effect identities must remain globally or scope-uniquely safe across retries. Dropping universal UUID syntax for domain resources does not weaken these requirements.

## Consequences

- FireMUD preserves strict design/runtime separation and independent authored-ID allocation while avoiding UUID requirements for game-instance-local objects that benefit from compact player-visible numbers.
- Durable resources may carry both a private numeric database key and a public UUID, adding mapping and migration work at persistence boundaries.
- Current numeric public/cross-service identifiers are implementation gaps to converge; existing numeric database keys may remain private.
- Every identifier family needs a clear contract; developers cannot infer scope or semantics from value shape.
- Runtime IDs derived mechanically from row locations remain implementation gaps until the runtime owner guarantees their scoped stability and non-reuse.
- Typed scoped references make APIs more verbose but prevent wrong-namespace and wrong-instance operations.

## Alternatives Considered

### Require UUIDs for Every Runtime Object

This is uniform and permits uncoordinated runtime allocation, but makes player-visible disambiguators cumbersome and is unnecessary while one authority concurrency-safely allocates instance-local IDs. UUID syntax still cannot prevent a room UUID from being supplied where a template UUID is expected.

### Use Numeric Database Keys Everywhere

This closely matches current implementation and is efficient, but exposes allocation order, couples consumers to storage, and forces independently created authored graphs to obtain IDs centrally or perform temporary-ID remapping.

### Reuse Template IDs at Runtime

This is simple for materialized content but fails for generated objects, aliases multiple running instances, and risks runtime mutation of design identity.

### Use Names or Slugs as Durable References

Human-readable selectors improve authoring but are mutable and unsuitable for replay, audit, publication, and deterministic runtime references.

## Implementation and Proof Obligations

- Converge public contracts for accounts, tenants, versions, templates, game instances, and characters onto their UUID logical identifiers without requiring local numeric database keys to be removed.
- Inventory each cross-service identifier family and declare its wire type, scope, owner, allocation, stability, reuse, and storage-key relationship.
- Converge generic or ambiguous proto fields onto typed scoped references where a namespace mistake is consequential.
- Prove authored UUIDs can be allocated before persistence and are preserved or explicitly remapped through supported import/export and cloning operations.
- Either independently allocate public runtime room/entity IDs or explicitly guarantee their numeric rows as stable and never reused within the game instance; undocumented reversible row encodings are not canonical.
- Prove wrong-kind, wrong-version, wrong-game-instance, and cross-tenant references fail closed.
- Prove two instances of one version do not alias live objects, generated objects need no template identity, and instance replacement does not reuse runtime state.
- Prove high-entropy session, token, command, event, effect, and idempotency identities retain their separate security and retry guarantees.

## Reversibility and Revisit Triggers

An identifier family can migrate representation without changing namespace semantics when all persisted and cross-service references converge together. Revisit numeric runtime IDs if runtime shards must mint them independently or their allocation throughput becomes a demonstrated constraint. Do not expose private database keys or weaken durable UUID identity merely for implementation convenience.

## Required Documentation Alignment

- [Identifier glossary](../system-architecture-identifier-glossary.md)
- [Authentication architecture](../system-architecture-authentication.md)
- [Runtime versioning architecture](../system-architecture-versioning-runtime.md)
- owning microservice API and data contracts for each identifier family
