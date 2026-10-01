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

This proposal records design options for command-plan ordering and `planOrdinal`, command-root identity/allocation owner and key, exact `EffectId` scalar format, versioned canonical serialization, additional allocator mechanics, command-plan/manifest binding, and atomic allocation; these remain pending human approval. Separately, [ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md), [ADR 0075](./adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md), and [ADR 0077](./adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md) establish the accepted owner-defined child ordinal and durable owner-scope/root/parent/ordinal-to-child-`EffectId` mapping. Those child-identity requirements remain target contract independently of this proposal; ADR 0183 does not replace them or make its additional format, allocator, or manifest choices current. The independently accepted identity, replay, and terminal-outcome rules of ADR 0069 also continue to apply.

## Decision

Every clause in this Decision section is a proposal, conditional on human acceptance. In particular, the exact UUIDv7 scalar format and the additional command-plan, candidate-manifest, allocator, and fan-out-manifest mechanisms below do not extend the independently accepted owner-defined child ordinal and durable owner-scope/root/parent/ordinal-to-child-`EffectId` mapping in ADR 0069, ADR 0075, and ADR 0077.

### Proposed scalar identity and allocation authority

If accepted, this proposal would define the scalar `EffectId` as opaque, lowercase, hyphenated UUIDv7 text of exactly 36 characters. Producers and participants would treat it as an opaque scalar and would not derive it from command text, batch IDs, effect keys, participant fields, ordinals, operation names, targets, or mutable payloads. This exact scalar format is not an accepted cross-service requirement today.

If accepted, Game Session would own command-root allocation, while the owner of a generated child, non-command root, or remote/fan-out leg would own its corresponding allocation boundary. Each boundary would allocate once, durably persist the value, and atomically bind it to the complete immutable owner scope, operation, exact target where applicable, request digest, enclosing root, parent, and stable ordinal. Insert-if-absent uniqueness would cover the logical mapping and scalar claim. A collision or conflicting binding would fail closed without reminting or substituting an ID.

Under this proposal, a command root's enclosing root would be its own persisted `EffectId`, and its parent would be explicitly absent (`null`). Its first allocation would durably bind that root mapping; retry and replay would reuse the same scalar and binding. Participants would receive the persisted mutation identity for their guard, ledger, response, and terminal outcome. The enclosing root would remain lineage and reconciliation context for a child without replacing its identity or collapsing siblings. A post-abandon re-drive would receive a new explicitly linked identity under its own admission contract.

### Proposed command-root serialization refinements

The proposal assigns each logical command root a stable `planOrdinal` in semantic order and binds an opaque root `EffectId` to a frozen command/runtime context and ordered plan manifest. It further proposes an explicit manifest schema version, registered canonical serialization identifier, canonical bytes, and serialized ordered logical operations and request/runtime/namespace binding. These command-plan, ordinal, and root-allocation choices are all pending; they are not requirements of ADR 0069. Under this proposed model, a zero-effect plan allocates neither an ordinal nor a root `EffectId`.

The proposed durable allocation row is unique on `(tenantId, gameInstanceId, commandId, planOrdinal)` and binds the frozen command context and ordered plan-manifest digest. This proposal also binds the manifest schema version, serializer, canonical bytes, and exact root scalar claim, requiring exact matches on replay. Unknown, duplicate, ambiguous, noncanonical, or changed order/manifest evidence fails before staging or side effects if this design is accepted. Automation handoff identity would map exactly to one durable target command before this command-root allocation; trigger and correlation identities would not be allocation inputs.

### Proposed generated-child candidates and suppression

If accepted, the owning chain boundary would seal a versioned child-candidate manifest before enqueue or apply. Each candidate would record root/parent lineage, stable ordinal, typed operation, exact target, depth, required/optional classification, immutable request or plan digest, and resolved count/cost/per-target budget revisions. The manifest schema version, serializer, canonical digest, and ordered candidate set would be immutable replay evidence.

Under this proposed mechanism, candidates would receive no child `EffectId` at manifest creation. For an admitted candidate, one serializable transaction or explicitly equivalent locked multi-key atomic boundary would validate the manifest, reserve the ordinal, check and charge only the admitted candidate's count/cost/per-target budgets, allocate and persist the scalar mapping with the manifest schema version, registered serializer identifier, and canonical digest (or an immutable reference resolving to those fields), then commit the mapping before enqueue/apply. Replay and retry would exact-compare that schema version, serializer, digest/reference, candidate ordinal, and other immutable bindings before reusing the mapping or enqueueing work. Concurrent sibling admission would not double-charge, reserve two mappings for one ordinal, or leave partial budget, ordinal, mapping, or suppression state. Exact retries would reuse the committed mapping.

If accepted, an over-limit candidate would be suppressed without a child ID. The same atomic boundary would record immutable ID-free suppression evidence containing root/parent identity, the sealed candidate-manifest schema/serializer/digest, candidate ordinal, operation and exact target, feature/script/version, required/optional classification, reason, configured and actual limits, and outcome. Suppressed candidates would not be enqueued or applied; committed parents and earlier children would remain authoritative.

### Proposed global fan-out and regional child binding

If accepted, the fan-out parent would freeze the affected region set and topology generation at acceptance. Each regional child or leg would be one member of that sealed set and receive its own one-time allocated child `EffectId` before its wake signal. The durable child row would bind root and parent lineage, child ordinal, exact operation, target region/aggregate, request digest, and the proposed child-manifest schema, registered serializer, canonical bytes/digest, and expected-participant projection. It would retain the root binding needed for reconciliation.

Under this proposal, the child scalar and all binding fields would be persisted before wake delivery. Retry, duplicate wake, late result, and reconciliation would look up the same row and scalar. A missing, extra, duplicate, reordered, schema/serializer/digest-conflicting, target-conflicting, ordinal-conflicting, or identity-conflicting child would fail closed. Wake markers would remain disposable latency hints and could not create, expand, or replace a child.

## Rationale

The proposed exact scalar format makes wire and storage compatibility testable while keeping identity opaque. Durable allocation separates identity from semantic derivation and allows a participant or coordinator to prove that a replay is the same logical mutation. The proposed versioned canonical manifests make plan and fan-out interpretation stable across deployments, while ID-free suppression evidence prevents rejected work from appearing to have executed. The proposed serializable allocation/budget boundary preserves chain accounting under concurrency, and a frozen fan-out set prevents topology changes from creating new work during recovery.

## Alternatives Considered

### Derive IDs from command, batch, ordinal, or payload fields

This proposal rejects deriving IDs from mutable or replay-order-dependent fields because they can collide, change identity, or allow a participant to invent a value. In the proposed design, derivation would also make fan-out and generated-child reuse depend on semantics rather than durable allocation.

### Permit UUID/ULID producer choice without an exact scalar contract

This proposal rejects caller-selected UUID/ULID formats for its proposed cross-service boundary because callers could disagree on casing, width, textual form, or parser behavior. Its pending candidate is lowercase hyphenated UUIDv7 text with opaque semantics; this is not the accepted canonical format.

### Allocate at manifest creation for every candidate

This proposal rejects allocating at manifest creation for every candidate because suppressed work would acquire an execution identity and could be mistaken for an admitted mutation. Under the proposal, allocation would belong to the atomic admission boundary after budget and ordinal checks.

### Use a best-effort global fan-out allocator or wake message identity

This proposal rejects a best-effort global fan-out allocator or wake message identity because notifications can be lost or duplicated and cannot own durable correctness. If accepted, the parent-owned child row and sealed manifest binding would be authoritative.

## Consequences

- If accepted, the command-plan, `planOrdinal`, command-root ownership/key/binding, and manifest-digest requirements proposed here would join ADR 0069's identity, replay, and terminal-outcome rules.
- Each generated-effect or fan-out owner would need the proposed durable allocation rows, manifest bindings, and conflict-safe atomic replay behavior if this proposal is accepted.
- If accepted, this proposal would require root and child manifests to use registered schema versions and canonical serializers whose bytes and digests are stable across supported readers.
- If accepted, chain admission would use a serializable or explicitly equivalent atomic boundary and retain ID-free suppression evidence.
- The proposed 36-character scalar appears to fit existing string protobuf and database fields. Current ID derivation and replay paths are implementation facts; they do not accept or prove the pending scalar, allocator, or manifest choices.
- If accepted, participants, ledgers, coordinators, and operational reconciliation would carry both mutation identity and enclosing-root lineage where applicable.
- If accepted, adoption would be a breaking target convergence for identity allocation; compatibility shims would not preserve semantic derivation or silently accept conflicting formats.

## Reversibility and Revisit Triggers

Before acceptance, human review may change the proposed command-plan ordering, `planOrdinal`, root owner/key/binding, scalar format, generated-child/fan-out ownership, or serialization boundary without a migration obligation. After acceptance and persisted use, changing the scalar or canonical serialization requires a versioned migration and explicit old/new binding and replay policy; it must not reinterpret existing IDs. Revisit UUIDv7 only if a reviewed cross-service identity standard provides equal exact textual compatibility, opaque semantics, collision handling, and operational proof.

## Security and Privacy

If accepted, opaque UUIDv7 values would not encode tenant, account, character, target, operation, or authorization meaning. Exact scope, namespace, runtime-fence, target, and request-digest validation would remain separate evidence. Collision, parser, schema, digest, or binding conflicts would fail closed before domain mutation. Raw IDs would remain durable audit/reconciliation fields but be excluded from bounded metric labels and player-facing messages. Suppression and replay evidence would not expose unauthorized target or tenant data across scope boundaries.

## Operations and Recovery

If accepted, Game Session or the owning allocation boundary would expose durable allocation, collision, manifest-mismatch, budget-suppression, fan-out-injection, and reconciliation outcomes with bounded reason labels. Recovery would retry the same persisted mapping and manifest; it would not regenerate an ID or expand a frozen candidate/region set from current topology. An unresolved binding, partial participant set, or uncertain old-epoch effect would remain reconciliation-required and non-terminal under the original mutation identity until the accepted evidence policy permits terminalization.

## Implementation and Proof Obligations

Implementation and focused proof must cover:

- if accepted, lowercase, hyphenated, exactly 36-character UUIDv7 scalar validation and opaque treatment at every supported wire/storage boundary;
- if accepted, command-plan ordering; zero-, one-, and multi-root plans; `planOrdinal`; command-root owner/key/binding; ordered manifest digest; mismatch rejection; one-time scalar claims; insert-if-absent uniqueness; collision/conflict failure; crash recovery; concurrent allocation races; and exact replay/reconciliation reuse;
- if accepted, the proposed root manifest schema version, serializer, canonical bytes, exact scalar claim, and their mismatch rejection;
- if accepted, child-candidate schema version/serializer/digest, candidate ordering, depth and budget revisions, serializable sibling admission, no double charge, and no partial commit;
- if accepted, ID-free suppression evidence proving no enqueue/apply and preserving committed parent/earlier-child outcomes;
- if accepted, fan-out frozen region/topology set, exact child ordinal and target binding, child-manifest schema/serializer/digest, request digest, pre-wake durability, duplicate wake, late result, and topology-conflict handling;
- if accepted, participant guard, ledger, coordinator, and terminal projection exactness across root/child identity and enclosing-root lineage; and
- if accepted, current protobuf/database compatibility, rejected blank/invalid IDs, migration/replay behavior, bounded observability, tenant isolation, and old-epoch recovery.

The focused proof must be added only after human acceptance of this proposal and must include the existing [tick failure and operations proof obligations](../system-architecture-tick-failures-and-operations.md), [transaction contract](../system-architecture-transactions.md), [tick execution flows](../system-architecture-tick-execution-flows.md), [tick incident runbook](../system-architecture-tick-incident-runbook.md), [Entity Management API contract](../microservices/entity-management-service/api-contracts.md), and [gameplay implementation tracker](../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md).
