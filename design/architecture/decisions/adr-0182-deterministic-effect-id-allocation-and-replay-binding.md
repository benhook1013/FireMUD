# ADR 0182: Deterministic Command-Plan and Generated-Child EffectId Allocation

## Status

Proposed - Pending Human Review

## Implementation Status

This proposal is not current target state and is not an accepted implementation requirement. The current services, wire contracts, and focused proof remain implementation gaps as recorded by the linked canonical documents. No implementation may treat this proposal as approved before human review.

## Decision Record

- Human review status: Pending
- Human review date: Not yet reviewed
- Human review disposition: Pending
- Review source: `AI-AUTHORED-PENDING`
- Decision date: 2026-08-31
- Decision key: `TICK-20`
- Primary capability: `GR-1.2` effect execution and reconciliation
- Affected capabilities: `GR-1.4`, `GR-4.1`, `AS-1.2`, `AS-1.4`, `SF-1.4`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: AI-authored proposal requiring explicit human review of identity format, persistence, ownership, compatibility, security, and operational proof

## Context

[ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md) accepts at-least-once execution and one logical terminal outcome, but its accepted decision does not select the allocator contract for generated children or a precise scalar format. The current implementation also derives effect IDs from batch or effect-key material and exposes narrower service-local replay tables. Those paths are documented implementation gaps and cannot be made canonical by inference.

Generated chains and global fan-out need an identity that survives retries, crashes, replay, runtime replacement, and reconciliation without turning semantic fields into an ID. Command roots additionally need a durable plan/ordinal binding so a changed plan cannot silently reuse an old root. Candidate suppression must remain auditable without inventing an ID for work that never entered execution.

This proposal supplies one owner-bound allocation contract for those gaps. It is deliberately pending: its exact format and cross-service persistence boundary require human approval before any accepted ADR or implementation depends on them.

## Decision

### Scalar identity and allocation authority

The canonical scalar `EffectId` is an opaque, lowercase, hyphenated UUIDv7 textual value of exactly 36 characters. UUIDv7 supplies collision-resistant allocation ordering properties without giving producers semantic parsing or meaning. Producers and participants must treat the value as an opaque scalar; they must not derive it from command text, batch IDs, effect keys, participant fields, ordinals, operation names, targets, or mutable payloads.

Game Session owns command-root allocation. The owner of a generated child, non-command root, or remote/fan-out leg owns its corresponding allocation boundary. Each boundary allocates once, durably persists the value, and atomically binds it to the complete immutable owner scope, operation, exact target where applicable, request digest, enclosing root, parent, and stable ordinal. Insert-if-absent uniqueness covers the logical mapping and the scalar claim. A collision or any conflicting binding fails closed; it never remints or substitutes an ID.

Retries, replay, failover, and reconciliation read and reuse the persisted mapping and exact scalar. Participants receive the persisted mutation identity and use it in their guard, ledger, response, and terminal outcome. The enclosing root remains lineage and reconciliation context for a child; it does not replace the child's identity or collapse siblings. A post-abandon re-drive receives a new explicitly linked identity under its own admission contract.

### Command roots and versioned plan manifests

Before staging a non-empty admitted command, Game Session freezes the typed command/action or `ResolvedEffectPlan` semantic order and persists a versioned root-plan manifest. The manifest records its schema version, registered canonical serialization identifier, canonical bytes/digest, ordered logical operations, and the frozen request/runtime/namespace binding. A root receives one `planOrdinal` in that order and one allocated opaque `EffectId`; zero-effect plans allocate neither.

The durable allocation row is unique on `(tenantId, gameInstanceId, commandId, planOrdinal)` and binds the manifest schema version, serializer, digest, exact root scalar claim, and frozen command binding. Replay and reuse require every one of those values to match. Unknown, duplicate, ambiguous, noncanonical, or changed order/manifest evidence fails before staging or side effects. Automation handoff identity maps exactly to one durable target command before this command-root allocation is attempted; trigger and correlation identities are not allocation inputs.

### Generated-child candidates and suppression

Before enqueue or apply, the owning chain boundary seals one versioned child-candidate manifest using its registered canonical serialization. Each candidate records root/parent lineage, stable ordinal, typed operation, exact target, depth, required/optional classification, immutable request or plan digest, and resolved count/cost/per-target budget revisions. The candidate manifest schema version, serializer, canonical digest, and ordered candidate set are immutable replay evidence.

Candidates receive no child `EffectId` at manifest creation. For an admitted candidate, one serializable transaction or explicitly equivalent locked multi-key atomic boundary validates the manifest, reserves the ordinal, checks and charges only the admitted candidate's count/cost/per-target budgets, allocates and persists the opaque scalar mapping together with the manifest schema version, registered serializer identifier, and canonical digest (or an immutable manifest reference resolving to those exact fields), and commits the mapping before enqueue/apply. Replay and retry must exact-compare that schema version, serializer, digest/reference, candidate ordinal, and all other immutable binding fields before reusing the mapping or enqueueing work. Concurrent sibling admission cannot double-charge, reserve two mappings for one ordinal, or leave partial budget, ordinal, mapping, or suppression state. Exact retries reuse the committed mapping.

An over-limit candidate is suppressed without a child ID. The same atomic boundary records immutable ID-free suppression evidence containing root/parent identity, the sealed candidate-manifest schema/serializer/digest, candidate ordinal, operation and exact target, feature/script/version, required/optional classification, reason, configured and actual limits, and outcome. Suppressed candidates are never enqueued or applied; committed parents and earlier children remain authoritative.

### Global fan-out and regional child binding

At acceptance, the fan-out parent freezes the affected region set and topology generation. Each regional child or leg is one exact member of that sealed set and gets its own one-time allocated child `EffectId` before its wake signal. The durable child row binds root and parent lineage, child ordinal, exact operation, target region/aggregate, request digest, and the versioned child-manifest schema, registered serializer, canonical bytes/digest, and expected-participant projection. It also retains the root binding needed for reconciliation.

The child scalar and all binding fields are persisted before wake delivery. Retry, duplicate wake, late result, and reconciliation look up the same row and scalar. A missing, extra, duplicate, reordered, schema/serializer/digest-conflicting, target-conflicting, ordinal-conflicting, or identity-conflicting child fails closed. Wake markers remain disposable latency hints and cannot create, expand, or replace a child.

## Rationale

An exact scalar format makes wire and storage compatibility testable while keeping identity opaque. Durable allocation separates identity from semantic derivation and allows a participant or coordinator to prove that a replay is the same logical mutation. Versioned canonical manifests make plan and fan-out interpretation stable across deployments, while ID-free suppression evidence prevents rejected work from appearing to have executed. A serializable allocation/budget boundary preserves chain accounting under concurrency and a frozen fan-out set prevents topology changes from creating new work during recovery.

## Alternatives Considered

### Derive IDs from command, batch, ordinal, or payload fields

Rejected because mutable or replay-order-dependent fields can collide, change identity, or allow a participant to invent a value. Derivation also makes fan-out and generated-child reuse depend on semantics rather than durable allocation.

### Permit UUID/ULID producer choice without an exact scalar contract

Rejected for the canonical cross-service boundary because callers could disagree on casing, width, textual form, or parser behavior. The selected pending proposal uses lowercase hyphenated UUIDv7 text while retaining opaque semantics.

### Allocate at manifest creation for every candidate

Rejected because suppressed work would acquire an execution identity and could be mistaken for an admitted mutation. Allocation belongs to the atomic admission boundary after budget and ordinal checks.

### Use a best-effort global fan-out allocator or wake message identity

Rejected because notifications can be lost or duplicated and cannot own durable correctness. The parent-owned child row and sealed manifest binding are authoritative.

## Consequences

- Game Session and each generated-effect or fan-out owner need durable allocation rows with uniqueness, immutable binding fields, and conflict-safe replay behavior.
- Root and child manifests require registered schema versions and canonical serializers whose bytes and digests are stable across supported readers.
- Chain admission must use a serializable or explicitly equivalent atomic boundary and retain ID-free suppression evidence.
- Existing string protobuf and database fields remain wire/storage-compatible with a 36-character scalar, but current derivation, optional fields, and narrower replay guards remain gaps until separately implemented and proved.
- Participants, ledgers, coordinators, and operational reconciliation must carry both mutation identity and enclosing-root lineage where applicable.
- Adoption is a breaking target convergence for identity allocation; compatibility shims must not preserve semantic derivation or silently accept conflicting formats.

## Reversibility and Revisit Triggers

Before acceptance, human review may change the scalar format, allocator ownership, or manifest boundary without a migration obligation. After acceptance and persisted use, changing the scalar or canonical serialization requires a versioned migration and explicit old/new binding and replay policy; it must not reinterpret existing IDs. Revisit UUIDv7 only if a reviewed cross-service identity standard provides equal exact textual compatibility, opaque semantics, collision handling, and operational proof.

## Security and Privacy

Opaque UUIDv7 values must not encode tenant, account, character, target, operation, or authorization meaning. Exact scope, namespace, runtime-fence, target, and request-digest validation remains separate evidence. Collision, parser, schema, digest, or binding conflicts fail closed before domain mutation. Raw IDs remain durable audit/reconciliation fields but are excluded from bounded metric labels and player-facing messages. Suppression and replay evidence must not expose unauthorized target or tenant data across scope boundaries.

## Operations and Recovery

Game Session or the owning allocation boundary exposes durable allocation, collision, manifest-mismatch, budget-suppression, fan-out-injection, and reconciliation outcomes with bounded reason labels. Recovery retries the same persisted mapping and manifest; it never regenerates an ID or expands a frozen candidate/region set from current topology. An unresolved binding, partial participant set, or uncertain old-epoch effect remains reconciliation-required and non-terminal under the original mutation identity until the accepted evidence policy permits terminalization.

## Implementation and Proof Obligations

Implementation and focused proof must cover:

- lowercase, hyphenated, exactly 36-character UUIDv7 scalar validation and opaque treatment at every supported wire/storage boundary;
- one-time allocation, insert-if-absent uniqueness, collision/conflict failure, crash recovery, concurrent allocation races, and exact replay/reconciliation reuse;
- zero-, one-, and multi-root plans; frozen semantic order; root manifest schema version, serializer, canonical bytes/digest, ordinal, command binding, and mismatch rejection;
- child-candidate schema version/serializer/digest, candidate ordering, depth and budget revisions, serializable sibling admission, no double charge, and no partial commit;
- ID-free suppression evidence proving no enqueue/apply and preserving committed parent/earlier-child outcomes;
- fan-out frozen region/topology set, exact child ordinal and target binding, child-manifest schema/serializer/digest, request digest, pre-wake durability, duplicate wake, late result, and topology-conflict handling;
- participant guard, ledger, coordinator, and terminal projection exactness across root/child identity and enclosing-root lineage; and
- current protobuf/database compatibility, rejected blank/invalid IDs, migration/replay behavior, bounded observability, tenant isolation, and old-epoch recovery.

The focused proof must be added only after human acceptance of this proposal and must include the existing [tick failure and operations proof obligations](../system-architecture-tick-failures-and-operations.md), [transaction contract](../system-architecture-transactions.md), [tick execution flows](../system-architecture-tick-execution-flows.md), [tick incident runbook](../system-architecture-tick-incident-runbook.md), [Entity Management API contract](../microservices/entity-management-service/api-contracts.md), and [gameplay implementation tracker](../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md).
