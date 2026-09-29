# ADR 0183: Deterministic Command-Plan and Generated-Child EffectId Allocation

## Status

Proposed - Pending Human Review

## Implementation Status

All allocation requirements in this proposal are pending and do not define current target state or accepted implementation requirements. ADR 0069 accepts persisted mutation identity and digest-bound replay, together with evidence-qualified terminal outcomes; it does not select deterministic command-plan ordering, `planOrdinal`, a command-root owner or key, or an ordered plan manifest/allocation binding. No implementation or proof may treat this proposal as approved before human review.

The move from the conflicting draft number ADR 0182 to ADR 0183 is mechanical preservation only. It neither accepts this allocator decision nor authorizes proposal-dependent implementation; independently authoritative effect and replay obligations continue to apply on their own authority.

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

[ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md) establishes the at-least-once effect, persisted mutation identity, immutable request-digest, replay, and terminal-outcome rules. It does not select command-plan ordering, `planOrdinal`, a command-root allocation owner or key, or a plan manifest/allocation binding. This proposal presents those command-root choices alongside generated-child and fan-out allocation. It also proposes an exact scalar format, versioned canonical serialization, and atomic manifest/allocation behavior. The current implementation derives effect IDs from batch or effect-key material and exposes narrower service-local replay tables. Those paths are implementation gaps and cannot determine the pending design by inference.

Generated chains and global fan-out need an identity that survives retries, crashes, replay, runtime replacement, and reconciliation without turning semantic fields into an ID. This proposal considers a durable semantic command plan, stable `planOrdinal`, and command-root binding, as well as generated-child and fan-out allocation. Candidate suppression must remain auditable without inventing an ID for work that never entered execution.

This proposal records design options for command-plan order and root allocation, exact UUIDv7 scalar identity, versioned canonical serialization, generated-child/fan-out manifests, and atomic allocation. All remain pending human approval. ADR 0069's independently accepted identity, replay, and terminal-outcome rules continue to apply without establishing any of these allocation choices.

## Decision

### Scalar identity and allocation authority

The canonical scalar `EffectId` is an opaque, lowercase, hyphenated UUIDv7 textual value of exactly 36 characters. UUIDv7 supplies collision-resistant allocation ordering properties without giving producers semantic parsing or meaning. Producers and participants must treat the value as an opaque scalar; they must not derive it from command text, batch IDs, effect keys, participant fields, ordinals, operation names, targets, or mutable payloads.

If accepted, Game Session would own command-root allocation, while the owner of a generated child, non-command root, or remote/fan-out leg would own its corresponding allocation boundary. Each boundary would allocate once, durably persist the value, and atomically bind it to the complete immutable owner scope, operation, exact target where applicable, request digest, enclosing root, parent, and stable ordinal. Insert-if-absent uniqueness would cover the logical mapping and scalar claim. A collision or any conflicting binding would fail closed; it would not remint or substitute an ID.

Retries, replay, failover, and reconciliation read and reuse the persisted mapping and exact scalar. Participants receive the persisted mutation identity and use it in their guard, ledger, response, and terminal outcome. The enclosing root remains lineage and reconciliation context for a child; it does not replace the child's identity or collapse siblings. A post-abandon re-drive receives a new explicitly linked identity under its own admission contract.

### Proposed command-root serialization refinements

The proposal assigns each logical command root a stable `planOrdinal` in semantic order and binds an opaque root `EffectId` to a frozen command/runtime context and ordered plan manifest. It further proposes an explicit manifest schema version, registered canonical serialization identifier, canonical bytes, and serialized ordered logical operations and request/runtime/namespace binding. These command-plan, ordinal, and root-allocation choices are all pending; they are not requirements of ADR 0069. Under this proposed model, a zero-effect plan allocates neither an ordinal nor a root `EffectId`.

The proposed durable allocation row is unique on `(tenantId, gameInstanceId, commandId, planOrdinal)` and binds the frozen command context and ordered plan-manifest digest. This proposal also binds the manifest schema version, serializer, canonical bytes, and exact root scalar claim, requiring exact matches on replay. Unknown, duplicate, ambiguous, noncanonical, or changed order/manifest evidence fails before staging or side effects if this design is accepted. Automation handoff identity would map exactly to one durable target command before this command-root allocation; trigger and correlation identities would not be allocation inputs.

### Generated-child candidates and suppression

Before enqueue or apply, the owning chain boundary seals one versioned child-candidate manifest using its registered canonical serialization. Each candidate records root/parent lineage, stable ordinal, typed operation, exact target, depth, required/optional classification, immutable request or plan digest, and resolved count/cost/per-target budget revisions. The candidate manifest schema version, serializer, canonical digest, and ordered candidate set are immutable replay evidence.

Candidates receive no child `EffectId` at manifest creation. For an admitted candidate, one serializable transaction or explicitly equivalent locked multi-key atomic boundary validates the manifest, reserves the ordinal, checks and charges only the admitted candidate's count/cost/per-target budgets, allocates and persists the opaque scalar mapping together with the manifest schema version, registered serializer identifier, and canonical digest (or an immutable manifest reference resolving to those exact fields), and commits the mapping before enqueue/apply. Replay and retry must exact-compare that schema version, serializer, digest/reference, candidate ordinal, and all other immutable binding fields before reusing the mapping or enqueueing work. Concurrent sibling admission cannot double-charge, reserve two mappings for one ordinal, or leave partial budget, ordinal, mapping, or suppression state. Exact retries reuse the committed mapping.

An over-limit candidate is suppressed without a child ID. The same atomic boundary records immutable ID-free suppression evidence containing root/parent identity, the sealed candidate-manifest schema/serializer/digest, candidate ordinal, operation and exact target, feature/script/version, required/optional classification, reason, configured and actual limits, and outcome. Suppressed candidates are never enqueued or applied; committed parents and earlier children remain authoritative.

### Global fan-out and regional child binding

At acceptance, the fan-out parent freezes the affected region set and topology generation. Each regional child or leg is one exact member of that sealed set and gets its own one-time allocated child `EffectId` before its wake signal. The durable child row binds root and parent lineage, child ordinal, exact operation, target region/aggregate, request digest, and the versioned child-manifest schema, registered serializer, canonical bytes/digest, and expected-participant projection. It also retains the root binding needed for reconciliation.

The child scalar and all binding fields are persisted before wake delivery. Retry, duplicate wake, late result, and reconciliation look up the same row and scalar. A missing, extra, duplicate, reordered, schema/serializer/digest-conflicting, target-conflicting, ordinal-conflicting, or identity-conflicting child fails closed. Wake markers remain disposable latency hints and cannot create, expand, or replace a child.

## Rationale

The proposed exact scalar format makes wire and storage compatibility testable while keeping identity opaque. Durable allocation separates identity from semantic derivation and allows a participant or coordinator to prove that a replay is the same logical mutation. The proposed versioned canonical manifests make plan and fan-out interpretation stable across deployments, while ID-free suppression evidence prevents rejected work from appearing to have executed. The proposed serializable allocation/budget boundary preserves chain accounting under concurrency, and a frozen fan-out set prevents topology changes from creating new work during recovery.

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

- If accepted, the command-plan, `planOrdinal`, command-root ownership/key/binding, and manifest-digest requirements proposed here would join ADR 0069's identity, replay, and terminal-outcome rules.
- Each generated-effect or fan-out owner would need the proposed durable allocation rows, manifest bindings, and conflict-safe atomic replay behavior if this proposal is accepted.
- If accepted, this proposal would require root and child manifests to use registered schema versions and canonical serializers whose bytes and digests are stable across supported readers.
- If accepted, chain admission would use a serializable or explicitly equivalent atomic boundary and retain ID-free suppression evidence.
- The proposed 36-character scalar fits existing string protobuf and database fields; current derivation, optional fields, and narrower replay guards remain gaps until independently converged on an accepted contract and proved.
- Participants, ledgers, coordinators, and operational reconciliation must carry both mutation identity and enclosing-root lineage where applicable.
- Adoption is a breaking target convergence for identity allocation; compatibility shims must not preserve semantic derivation or silently accept conflicting formats.

## Reversibility and Revisit Triggers

Before acceptance, human review may change the proposed command-plan ordering, `planOrdinal`, root owner/key/binding, scalar format, generated-child/fan-out ownership, or serialization boundary without a migration obligation. After acceptance and persisted use, changing the scalar or canonical serialization requires a versioned migration and explicit old/new binding and replay policy; it must not reinterpret existing IDs. Revisit UUIDv7 only if a reviewed cross-service identity standard provides equal exact textual compatibility, opaque semantics, collision handling, and operational proof.

## Security and Privacy

Opaque UUIDv7 values must not encode tenant, account, character, target, operation, or authorization meaning. Exact scope, namespace, runtime-fence, target, and request-digest validation remains separate evidence. Collision, parser, schema, digest, or binding conflicts fail closed before domain mutation. Raw IDs remain durable audit/reconciliation fields but are excluded from bounded metric labels and player-facing messages. Suppression and replay evidence must not expose unauthorized target or tenant data across scope boundaries.

## Operations and Recovery

Game Session or the owning allocation boundary exposes durable allocation, collision, manifest-mismatch, budget-suppression, fan-out-injection, and reconciliation outcomes with bounded reason labels. Recovery retries the same persisted mapping and manifest; it never regenerates an ID or expands a frozen candidate/region set from current topology. An unresolved binding, partial participant set, or uncertain old-epoch effect remains reconciliation-required and non-terminal under the original mutation identity until the accepted evidence policy permits terminalization.

## Implementation and Proof Obligations

Implementation and focused proof must cover:

- if accepted, lowercase, hyphenated, exactly 36-character UUIDv7 scalar validation and opaque treatment at every supported wire/storage boundary;
- if accepted, command-plan ordering; zero-, one-, and multi-root plans; `planOrdinal`; command-root owner/key/binding; ordered manifest digest; mismatch rejection; one-time scalar claims; insert-if-absent uniqueness; collision/conflict failure; crash recovery; concurrent allocation races; and exact replay/reconciliation reuse;
- if accepted, the proposed root manifest schema version, serializer, canonical bytes, exact scalar claim, and their mismatch rejection;
- child-candidate schema version/serializer/digest, candidate ordering, depth and budget revisions, serializable sibling admission, no double charge, and no partial commit;
- ID-free suppression evidence proving no enqueue/apply and preserving committed parent/earlier-child outcomes;
- fan-out frozen region/topology set, exact child ordinal and target binding, child-manifest schema/serializer/digest, request digest, pre-wake durability, duplicate wake, late result, and topology-conflict handling;
- participant guard, ledger, coordinator, and terminal projection exactness across root/child identity and enclosing-root lineage; and
- current protobuf/database compatibility, rejected blank/invalid IDs, migration/replay behavior, bounded observability, tenant isolation, and old-epoch recovery.

The focused proof must be added only after human acceptance of this proposal and must include the existing [tick failure and operations proof obligations](../system-architecture-tick-failures-and-operations.md), [transaction contract](../system-architecture-transactions.md), [tick execution flows](../system-architecture-tick-execution-flows.md), [tick incident runbook](../system-architecture-tick-incident-runbook.md), [Entity Management API contract](../microservices/entity-management-service/api-contracts.md), and [gameplay implementation tracker](../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md).
