# ADR 0082: Semantic Boundary for Cross-Service Identifier Migration

## Status

Accepted

## Implementation Status

The semantic identity boundary and immutable-attestation target are established, but the repository provides only partial remap records and launch gates. Complete durable cross-service migration workflow and focused proof remain incomplete.

## Canonical Design

- [Database Migrations](../system-architecture-database-migrations.md)
- [Identifier Glossary](../system-architecture-identifier-glossary.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `DB-04`
- Primary capability: `SF-2.3` durable cross-service coordination and idempotency
- Affected capabilities: `SF-2.1`, `AR-1.5`, `AR-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of identity preservation, new-ID remapping, alias indirection, coordinated rewrites, and release re-attestation
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `DB-04`

## Context

Cross-service references must not silently change their referent or meaning, but requiring a new logical identifier for every database-column, wire-format, or storage-format migration would create needless remapping and rollout work. Conversely, preserving an identifier when an object is replaced, split, merged, re-scoped, or given materially different semantics would alias distinct identities and erase migration provenance.

The migration owner also depends on the identifier family. Game Design owns published template and release graphs; it is not the universal authority for account, operational, runtime, or another domain's identifiers.

## Decision

Preserve the existing logical identifier when the referent, ownership scope, cardinality, and domain meaning remain unchanged and only its database representation, column name, wire representation, or storage encoding changes. Such changes follow the applicable reader/writer and database compatibility process without pretending that the logical object was replaced.

Allocate a new identifier and create an explicit durable old-to-new mapping when the logical identity changes. This includes replacement with materially different semantics, ownership or scope changes, and identity splits or merges. Mappings record their type and lineage, are idempotently created, and remain available for as long as a retained version, durable record, retry, reconciliation path, audit, or rollback can still reference the old identity. A split or merge cannot be represented as an ambiguous single alias.

The authoritative owner of the affected relationship or version graph coordinates the migration. Game Design owns orchestration for design-time template and published-release graphs and validates their World, Entity, configuration, asset, and automation references. Other identifier families use their own domain authority rather than routing every migration through Game Design. Services do not independently rewrite another owner's records or infer mappings from identifier shape.

Published and Active release attestations remain immutable. A replacement release or an explicitly authorized re-attestation workflow creates a new attestation record and lineage; it does not rewrite the historical attestation in place. Readers and writers remain compatible for the applicable deployment window. A consumer that cannot interpret a launch-critical manifest `schemaVersion` fails closed rather than guessing, while representation-compatible evolution inside a supported schema version may use its declared additive rules.

## Consequences

- Format-only changes do not force new business identities or cross-service remapping.
- Semantic replacements, splits, merges, and scope changes retain explicit migration lineage and cannot masquerade as the old object.
- Mapping retention and coordinated migration add storage, workflow, proof, and cleanup obligations.
- Domain ownership stays accurate: Game Design coordinates published template graphs without becoming the authority for every cross-service identifier.
- Immutable release history remains trustworthy, and unsupported launch-critical manifests cannot be interpreted heuristically.

## Alternatives Considered

### Allocate a New Identifier for Every Representation Change

Rejected because column renames, UUID representation changes, and storage-format migrations do not create new logical objects. Mandatory replacement would cause unnecessary graph rewrites, duplicated retained objects, mapping growth, and rollout risk.

### Preserve or Reuse Every Identifier In Place

Rejected because replacements, scope changes, splits, and merges would alias different logical identities and make audit, rollback, and reconciliation ambiguous.

### Owner-Side Alias or Tombstone Indirection Only

Useful as an implementation mechanism for bounded migration and rollback, but insufficient as the whole contract. It must still distinguish semantic replacement from representation change, express splits and merges without ambiguity, preserve immutable release history, and eventually prove when legacy references may be retired.

## Implementation and Proof Obligations

Proof must classify each migration as representation-preserving or identity-changing; preserve complete scope and authorization checks; cover idempotent mapping creation, split/merge representation, partial workflow failure, retry, rollback, retained-version and live-instance exhaustion, mapping retention and cleanup, and compatible reader/writer rollout. Published-release proof must cover immutable prior attestations, explicit new lineage, supported manifest versions, and fail-closed launch behavior for unknown critical versions.

Current repository anchors provide partial remap records and launch gates but do not prove a complete durable cross-service migration workflow. At least one current manifest writer also omits the documented `schemaVersion`. This decision records the target contract and does not claim those implementation obligations are complete.

## Reversibility and Revisit Triggers

Mapping storage and orchestration technology are replaceable if identity semantics, ownership, lineage, retention, and release immutability remain intact. Revisit if a canonical global identity-resolution layer is introduced, published releases become mutable by an explicit product decision, or a new identifier family cannot express its migration with these preservation and replacement rules.
