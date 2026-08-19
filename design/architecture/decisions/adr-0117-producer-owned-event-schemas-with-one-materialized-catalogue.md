# ADR 0117: Producer-Owned Event Schemas with One Materialized Catalogue

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-11`
- Primary capability: `AS-1.1` trigger and event ingress contracts
- Affected capabilities: `AR-1.1`, `SF-1.1`, `PO-4.1`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of event semantic ownership, schema evolution, producer authorization, authoring consumption, catalogue skew, compatibility, deprecation, and retained patch support
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Accepted
- Review source: `SCRIPT-11`

## Context

Scripting events originate in domain services that understand their meaning, payload, causal context, and authorized producers. Moving those semantics into Automation makes the consumer responsible for contracts it cannot define. Producer-local enforcement alone leaves Automation admission and Game Design authoring dependent on scattered assumptions. Manually copied central definitions can drift from producer contracts.

## Decision

Producer services own the semantics and versioned schemas of the events they emit. Each producer publishes a versioned event-definition manifest from its primary contract surface. The manifest defines event meaning, payload schema reference, authorized producers, required Trigger Identity and consistency fields, replay and quota semantics, allowed binding scopes, dry-run support, and compatibility/deprecation metadata.

Automation & Scripting owns one canonical materialized event catalogue keyed by `(eventType, eventSchemaVersion)`. It is the enforcement and read authority for ingress admission and handler-resolution validation. Game Design consumes it read-only for editor choices and publish validation and does not maintain a competing registry.

Automation mechanically validates and materializes the complete producer-manifest set. It rejects malformed manifests, duplicate ownership, unresolved schema references, incompatible reuse of an existing key, and any source set that cannot produce one complete deterministic catalogue. Each accepted catalogue has an immutable revision identity and canonical digest over its validated inputs and ordered entries. Failed or partial materialization leaves the prior complete catalogue active.

Compatibility and lifecycle are fail closed:

- Breaking payload, identity, authorization, snapshot, consistency, replay, quota, or binding-scope changes require a new `eventSchemaVersion`.
- Compatible additive changes pass declared mechanical compatibility validation.
- Deprecation blocks new authoring while preserving runtime interpretation for supported patches.
- Removal waits until the authoritative patch-support lifecycle proves that no supported patch requires the schema version; required manifests and catalogue entries remain retained for validation, runtime admission, rollback, and operator explanation.

## Consequences

- Domain owners retain event meaning and schema-evolution responsibility.
- Automation provides one deterministic enforcement/read catalogue and makes source/catalogue skew observable.
- Game Design and operator tooling read the runtime catalogue rather than duplicating event tables.
- Materialization, revisions, digests, compatibility checks, and retention add release and storage work.

## Alternatives Considered

### Automation Owns Every Event Semantic Contract

Rejected because Automation cannot own the domain meaning or source causal guarantees of every event.

### Producer-Local Registries Only

Rejected because ingress authorization, handler binding, authoring, and operator explanation would lack one enforcement/read authority.

### Manually Copy Producer Definitions into a Central File

Rejected because copy omissions and ordering could leave producer and runtime contracts on different revisions.

### Rewrite Existing Schema Versions In Place

Rejected because retries, retained patches, rollback, and audit interpretation would change underneath immutable artifacts.

## Implementation and Proof Obligations

The current implementation loads one built-in Automation JSON catalogue and exposes definition reads, but does not discover producer manifests, materialize revisions/digests, validate compatibility, prove read-only Game Design consumption, or retain entries from an authoritative supported-patch set.

Proof must cover manifest ownership and schema-reference validation, deterministic materialization, duplicate/missing source rejection, incompatible same-version edits, additive and new-version changes, revision/digest stability, failed-materialization retention of the prior catalogue, Game Design read-only use, source/catalogue skew, deprecation, supported-patch retention, safe removal, mixed-node convergence, and revision-specific audit explanation.

## Reversibility and Revisit Triggers

Manifest encoding, materialization tooling, storage, digest algorithm, transport, and cache implementation may evolve while producer semantic ownership and one Automation-owned materialized catalogue remain. Revisit before admitting directly from unmaterialized producer assertions, allowing Game Design to author registry truth, rewriting a schema version incompatibly, or removing history still referenced by supported patches.

## Required Documentation Alignment

- [Scripting event registry](../system-architecture-scripting-event-registry.md)
- [Automation & Scripting API contracts](../microservices/automation-scripting-service/api-contracts.md)
