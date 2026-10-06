# ADR 0183: Deterministic Command-Plan and Generated-Child EffectId Allocation

## Status

Accepted

## Implementation Status

This ADR records accepted target state. Implementation and focused proof remain incomplete. ADR 0069 continues to own at-least-once effect execution, persisted mutation identity, immutable request-digest replay, and evidence-qualified terminal outcomes; this ADR adds the accepted command-plan, root-allocation, exact scalar, and manifest-binding choices without weakening those rules.

The move from the conflicting draft number ADR 0182 to ADR 0183 was mechanical preservation only. Acceptance is recorded separately below; independently authoritative effect and replay obligations continue to apply.

## Decision Record

- Human review status: Completed
- Human review date: 2026-10-04
- Human review disposition: Accepted
- Review source: `TICK-20`
- Decision date: 2026-10-04
- Decision key: `TICK-20`
- Primary capability: `GR-1.2` effect execution and reconciliation
- Affected capabilities: `GR-1.4`, `GR-4.1`, `AS-1.2`, `AS-1.4`, `SF-1.4`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Decision provenance: The human owner delegated the technical choice to the Overseer on 2026-10-04; the Overseer selected acceptance. This records delegated acceptance and does not claim that the human owner performed a detailed technical review.

## Context

[ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md) establishes the at-least-once effect, persisted mutation identity, immutable request-digest, replay, and terminal-outcome rules. It does not select command-plan ordering, `planOrdinal`, a command-root allocation owner or key, or a plan manifest/allocation binding. This ADR decides those command-root choices alongside generated-child and fan-out allocation, including the exact scalar format, versioned canonical serialization, and atomic manifest/allocation behavior. The current implementation derives effect IDs from batch or effect-key material and exposes narrower service-local replay tables. Those paths are implementation gaps and do not satisfy the accepted design.

Generated chains and global fan-out need an identity that survives retries, crashes, replay, runtime replacement, and reconciliation without turning semantic fields into an ID. The accepted design uses a durable semantic command plan, stable `planOrdinal`, and command-root binding, as well as generated-child and fan-out allocation. Candidate suppression must remain auditable without inventing an ID for work that never entered execution.

This decision establishes command-plan ordering and `planOrdinal`, command-root identity/allocation owner and key, the exact `EffectId` scalar format, versioned canonical serialization, additional allocator mechanics, command-plan/manifest binding, and atomic allocation. Separately, [ADR 0069](./adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md), [ADR 0075](./adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md), and [ADR 0077](./adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md) establish the accepted owner-defined child ordinal and durable owner-scope/root/parent/ordinal-to-child-`EffectId` mapping. Those child-identity requirements remain target contract independently of this ADR; ADR 0183 adds format, allocator, and manifest choices without replacing them. The independently accepted identity, replay, and terminal-outcome rules of ADR 0069 also continue to apply.

## Decision

Every clause in this Decision section is accepted target state. The exact UUIDv7 scalar format and the additional command-plan, candidate-manifest, allocator, and fan-out-manifest mechanisms below extend, but do not replace or weaken, the independently accepted owner-defined child ordinal and durable owner-scope/root/parent/ordinal-to-child-`EffectId` mapping in ADR 0069, ADR 0075, and ADR 0077.

### Scalar identity and allocation authority

This decision defines the scalar `EffectId` as opaque, lowercase, hyphenated UUIDv7 text of exactly 36 characters. Producers and participants treat it as an opaque scalar and must not derive it from command text, batch IDs, effect keys, participant fields, ordinals, operation names, targets, or mutable payloads.

Game Session owns command-root allocation, while the owner of a generated child, non-command root, or remote/fan-out leg owns its corresponding allocation boundary. Each boundary allocates once, durably persists the value, and atomically binds it to the complete immutable owner scope, operation, exact target where applicable, request digest, enclosing root, parent, and stable ordinal. Insert-if-absent uniqueness covers the logical mapping and scalar claim. A collision or conflicting binding fails closed without reminting or substituting an ID.

A command root's enclosing root is its own persisted `EffectId`, and its parent is explicitly absent (`null`). Its first allocation durably binds that root mapping; retry and replay reuse the same scalar and binding. Participants receive the persisted mutation identity for their guard, ledger, response, and terminal outcome. The enclosing root remains lineage and reconciliation context for a child without replacing its identity or collapsing siblings. A post-abandon re-drive receives a new explicitly linked identity under its own admission contract.

### Command-root serialization

Each logical command root receives a stable `planOrdinal` in semantic order and binds an opaque root `EffectId` to a frozen command/runtime context and ordered plan manifest. The manifest uses an explicit schema version, registered canonical serialization identifier, canonical bytes, ordered logical operations, and request/runtime/namespace binding. These command-plan, ordinal, and root-allocation choices supplement ADR 0069. Under this model, a zero-effect plan allocates neither an ordinal nor a root `EffectId`.

The durable allocation row is unique on `(tenantId, gameInstanceId, commandId, planOrdinal)` and binds the frozen command context and ordered plan-manifest digest. The row also binds the manifest schema version, serializer, canonical bytes, and exact root scalar claim, requiring exact matches on replay. Unknown, duplicate, ambiguous, noncanonical, or changed order/manifest evidence fails before staging or side effects under this accepted design. Automation handoff identity maps exactly to one durable target command before this command-root allocation; trigger and correlation identities are not allocation inputs.

### Generated-child candidates and suppression

The owning chain boundary seals a versioned child-candidate manifest before enqueue or apply. Each candidate records root/parent lineage, stable ordinal, typed operation, exact target, depth, required/optional classification, immutable request or plan digest, and resolved count/cost/per-target budget revisions. The manifest schema version, serializer, canonical digest, and ordered candidate set are immutable replay evidence.

Under this mechanism, candidates receive no child `EffectId` at manifest creation. For an admitted candidate, one serializable transaction or explicitly equivalent locked multi-key atomic boundary validates the manifest, reserves the ordinal, checks and charges only the admitted candidate's count/cost/per-target budgets, allocates and persists the scalar mapping with the manifest schema version, registered serializer identifier, and canonical digest (or an immutable reference resolving to those fields), then commits the mapping before enqueue/apply. Replay and retry exact-compare that schema version, serializer, digest/reference, candidate ordinal, and other immutable bindings before reusing the mapping or enqueueing work. Concurrent sibling admission cannot double-charge, reserve two mappings for one ordinal, or leave partial budget, ordinal, mapping, or suppression state. Exact retries reuse the committed mapping.

An over-limit candidate is suppressed without a child ID. The same atomic boundary records immutable ID-free suppression evidence containing root/parent identity, the sealed candidate-manifest schema/serializer/digest, candidate ordinal, operation and exact target, feature/script/version, required/optional classification, reason, configured and actual limits, and outcome. Suppressed candidates are not enqueued or applied; committed parents and earlier children remain authoritative.

### Global fan-out and regional child binding

The fan-out parent freezes the affected region set and topology generation at acceptance. Each regional child or leg is one member of that sealed set and receives its own one-time allocated child `EffectId` before its wake signal. The durable child row binds root and parent lineage, child ordinal, exact operation, target region/aggregate, request digest, and the child-manifest schema, registered serializer, canonical bytes/digest, and expected-participant projection. It retains the root binding needed for reconciliation.

The child scalar and all binding fields are persisted before wake delivery. Retry, duplicate wake, late result, and reconciliation look up the same row and scalar. A missing, extra, duplicate, reordered, schema/serializer/digest-conflicting, target-conflicting, ordinal-conflicting, or identity-conflicting child fails closed. Wake markers remain disposable latency hints and cannot create, expand, or replace a child.

## Rationale

The exact scalar format makes wire and storage compatibility testable while keeping identity opaque. Durable allocation separates identity from semantic derivation and allows a participant or coordinator to prove that a replay is the same logical mutation. The versioned canonical manifests make plan and fan-out interpretation stable across deployments, while ID-free suppression evidence prevents rejected work from appearing to have executed. The serializable allocation/budget boundary preserves chain accounting under concurrency, and a frozen fan-out set prevents topology changes from creating new work during recovery.

## Alternatives Considered

### Derive IDs from command, batch, ordinal, or payload fields

This decision rejects deriving IDs from mutable or replay-order-dependent fields because they can collide, change identity, or allow a participant to invent a value. Under this design, derivation would also make fan-out and generated-child reuse depend on semantics rather than durable allocation.

### Permit UUID/ULID producer choice without an exact scalar contract

This decision rejects caller-selected UUID/ULID formats for the cross-service boundary because callers could disagree on casing, width, textual form, or parser behavior. The canonical scalar is lowercase hyphenated UUIDv7 text with opaque semantics.

### Allocate at manifest creation for every candidate

This decision rejects allocating at manifest creation for every candidate because suppressed work would acquire an execution identity and could be mistaken for an admitted mutation. Under this design, allocation belongs to the atomic admission boundary after budget and ordinal checks.

### Use a best-effort global fan-out allocator or wake message identity

This decision rejects a best-effort global fan-out allocator or wake message identity because notifications can be lost or duplicated and cannot own durable correctness. The parent-owned child row and sealed manifest binding are authoritative.

## Consequences

- The command-plan, `planOrdinal`, command-root ownership/key/binding, and manifest-digest requirements join ADR 0069's identity, replay, and terminal-outcome rules.
- Each generated-effect or fan-out owner needs the durable allocation rows, manifest bindings, and conflict-safe atomic replay behavior under this ADR.
- Root and child manifests use registered schema versions and canonical serializers whose bytes and digests are stable across supported readers.
- Chain admission uses a serializable or explicitly equivalent atomic boundary and retains ID-free suppression evidence.
- The accepted 36-character scalar is expected to fit existing string protobuf and database fields. Current ID derivation and replay paths are implementation facts; they do not implement or prove the accepted scalar, allocator, or manifest choices.
- Participants, ledgers, coordinators, and operational reconciliation carry both mutation identity and enclosing-root lineage where applicable.
- Adoption is a breaking target convergence for identity allocation; compatibility shims would not preserve semantic derivation or silently accept conflicting formats.

## Reversibility and Revisit Triggers

Before acceptance, the human owner delegated these choices to the Overseer, who selected the target recorded here. A later decision may change the command-plan ordering, `planOrdinal`, root owner/key/binding, scalar format, generated-child/fan-out ownership, or serialization boundary without a migration obligation before persisted adoption. After acceptance and persisted use, changing the scalar or canonical serialization requires a versioned migration and explicit old/new binding and replay policy; it must not reinterpret existing IDs. Revisit UUIDv7 only if a reviewed cross-service identity standard provides equal exact textual compatibility, opaque semantics, collision handling, and operational proof.

## Security and Privacy

Opaque UUIDv7 values do not encode tenant, account, character, target, operation, or authorization meaning. Exact scope, namespace, runtime-fence, target, and request-digest validation remains separate evidence. Collision, parser, schema, digest, or binding conflicts fail closed before domain mutation. Raw IDs remain durable audit/reconciliation fields but are excluded from bounded metric labels and player-facing messages. Suppression and replay evidence does not expose unauthorized target or tenant data across scope boundaries.

## Operations and Recovery

Game Session or the owning allocation boundary exposes durable allocation, collision, manifest-mismatch, budget-suppression, fan-out-injection, and reconciliation outcomes with bounded reason labels. Recovery retries the same persisted mapping and manifest; it does not regenerate an ID or expand a frozen candidate/region set from current topology. An unresolved binding, partial participant set, or uncertain old-epoch effect remains reconciliation-required and non-terminal under the original mutation identity until the accepted evidence policy permits terminalization.

## Implementation and Proof Obligations

Implementation and focused proof must cover:

- lowercase, hyphenated, exactly 36-character UUIDv7 scalar validation and opaque treatment at every supported wire/storage boundary;
- command-plan ordering; zero-, one-, and multi-root plans; `planOrdinal`; command-root owner/key/binding; ordered manifest digest; mismatch rejection; one-time scalar claims; insert-if-absent uniqueness; collision/conflict failure; crash recovery; concurrent allocation races; and exact replay/reconciliation reuse;
- the root manifest schema version, serializer, canonical bytes, exact scalar claim, and their mismatch rejection;
- child-candidate schema version/serializer/digest, candidate ordering, depth and budget revisions, serializable sibling admission, no double charge, and no partial commit;
- ID-free suppression evidence proving no enqueue/apply and preserving committed parent/earlier-child outcomes;
- fan-out frozen region/topology set, exact child ordinal and target binding, child-manifest schema/serializer/digest, request digest, pre-wake durability, duplicate wake, late result, and topology-conflict handling;
- participant guard, ledger, coordinator, and terminal projection exactness across root/child identity and enclosing-root lineage; and
- current protobuf/database compatibility, rejected blank/invalid IDs, migration/replay behavior, bounded observability, tenant isolation, and old-epoch recovery.

The focused proof remains outstanding and must include the existing [tick failure and operations proof obligations](../system-architecture-tick-failures-and-operations.md), [transaction contract](../system-architecture-transactions.md), [tick execution flows](../system-architecture-tick-execution-flows.md), [tick incident runbook](../system-architecture-tick-incident-runbook.md), [Entity Management API contract](../microservices/entity-management-service/api-contracts.md), and [gameplay implementation tracker](../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md).
