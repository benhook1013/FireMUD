# ADR 0122: Producer-Owned Event Schemas with One Materialized Catalogue

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-11`
- Primary capability: `AS-1.1` trigger and event ingress contracts
- Affected capabilities: `AR-1.1`, `SF-1.1`, `PO-4.1`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of event semantic ownership, schema evolution, producer authorization, authoring consumption, catalogue skew, compatibility, deprecation, and retained patch support

## Context

Scripting events originate in domain services that understand the event's meaning, payload, causal context, and authorized producers. Moving those semantics into Automation would make a consumer responsible for contracts it cannot define correctly. Allowing each producer to enforce its own private registry would instead make Automation admission and Game Design authoring depend on scattered, potentially inconsistent assumptions.

A manually duplicated central registry also creates a dangerous middle state: producer code and schema may change while Automation and Game Design continue enforcing an older copied definition under the same event key. Event schema versioning alone is insufficient unless source manifests are validated, materialized, identified, and retained coherently.

## Decision

Producer services own the semantics and versioned schemas of the events they emit. Each producer publishes a versioned event-definition manifest from its primary contract surface. The manifest defines the event meaning, payload schema reference, authorized producers, required Trigger Identity and consistency fields, replay and quota semantics, allowed binding scopes, dry-run support, and compatibility and deprecation metadata.

Automation & Scripting owns one canonical materialized event catalogue keyed by `(eventType, eventSchemaVersion)`. That catalogue is the enforcement and read authority for ingress admission and handler-resolution validation. Game Design consumes it read-only for editor choices and publish validation; it does not maintain an independently authored event registry.

Producer manifests are mechanically validated and materialized into the Automation catalogue. Operators or service teams do not copy the same event definition manually into a second authoritative file. Materialization must reject malformed manifests, duplicate ownership, unresolved schema references, incompatible reuse of an existing key, and any source set that cannot produce one complete deterministic catalogue.

Every materialized catalogue state has an immutable revision identity and canonical digest that bind the exact validated producer-manifest inputs and resulting ordered entries. Automation admission and read surfaces expose sufficient revision and digest identity for consumers and operators to detect producer/catalogue skew. A failed or partial materialization does not replace the last complete accepted catalogue.

Compatibility and lifecycle rules are fail closed:

- A breaking payload, Trigger Identity, producer-authorization, snapshot-authority, consistency, replay, quota, or binding-scope change requires a new `eventSchemaVersion`; an existing `(eventType, eventSchemaVersion)` is not rewritten incompatibly.
- Compatible additive changes must be mechanically validated against the declared compatibility rules before materialization.
- Deprecation first prevents new authoring while preserving runtime interpretation for supported patches. Removal may reject new ingress only after the patch-support lifecycle proves that no supported patch still requires that event schema version.
- Catalogue entries and source manifests needed by supported patches remain available for validation, runtime admission, rollback, and operator explanation. Catalogue compaction must not silently remove them.

The patch-support lifecycle owns which patches remain supported; the event catalogue consumes that authoritative reference set rather than inventing a separate support policy.

## Consequences

- Domain owners retain responsibility for event meaning and schema evolution.
- Automation enforces one deterministic catalogue, so runtime admission does not depend on producer-local interpretation at request time.
- Game Design and operator tooling read the same catalogue used by runtime instead of duplicating event tables.
- Mechanical materialization, revisions, and digests add build/release validation but make source/catalogue skew observable and fail closed.
- Breaking evolution creates parallel schema versions and associated retention cost rather than mutating old contracts in place.
- Deprecation and removal must account for supported patches, which may retain older schema versions longer than new authoring needs them.

## Alternatives Considered

### Automation Owns Every Event Semantic Contract

Define and manually maintain all producer payloads in Automation. Rejected because Automation cannot own the domain meaning or source causal guarantees of every event, and duplicated producer definitions can drift.

### Producer-Local Registries Only

Let each producer validate its event and have Automation accept producer assertions. Rejected because ingress authorization, handler binding, Game Design authoring, and operator explanation would have no single enforcement/read authority.

### Manually Copy Producer Definitions into a Central File

Keep one apparent registry but update it by hand when producer contracts change. Rejected because copy omissions and ordering can leave producer code, schema, and runtime enforcement on different revisions without a deterministic materialization failure.

### Rewrite Existing Schema Versions In Place

Update the payload or policy under the same `(eventType, eventSchemaVersion)` and require all patches to follow the latest meaning. Rejected because retries, retained patches, rollback, and audit interpretation would change underneath immutable artifacts.

## Implementation and Proof Obligations

The current implementation is partial. Automation loads a single built-in JSON resource into an in-memory catalogue and exposes `GetScriptEventDefinition` / `ListScriptEventDefinitions`; ingress validates several catalogue fields. It does not discover producer-owned manifests, mechanically compose them, expose a catalogue revision or digest, validate declared compatibility across revisions, or retain entries from an authoritative supported-patch reference set. Game Design consumption is not proved as one read-only end-to-end authoring and publication path.

Implementation proof must cover manifest ownership and schema-reference validation; deterministic materialization independent of input ordering; duplicate key and duplicate owner rejection; missing producer manifests; incompatible same-version edits; allowed additive changes; new-version breaking changes; complete revision/digest stability and change detection; partial or failed materialization preserving the prior accepted catalogue; Game Design read-only consumption; ingress use of the same revision; producer/catalogue skew; deprecation blocking new authoring without breaking supported runtime; retention for every supported patch reference; safe removal after the authoritative support set advances; restart, rollout, and mixed-node convergence; and audit explanation using the exact catalogue revision.

## Reversibility and Revisit Triggers

Manifest encoding, materialization tooling, catalogue storage, digest algorithm, distribution transport, and cache implementation may evolve while producer semantic ownership and one Automation-owned materialized enforcement catalogue remain unchanged. Revisit this decision before permitting runtime admission directly from unmaterialized producer assertions, allowing Game Design to author registry truth, rewriting a schema version incompatibly, or removing catalogue history still referenced by supported patches.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-event-registry.md`
- `design/architecture/microservices/automation-scripting-service/api-contracts.md`
