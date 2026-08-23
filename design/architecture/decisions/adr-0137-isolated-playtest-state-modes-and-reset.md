# ADR 0137: Isolated Playtest State Modes and Reset

## Status

Accepted

## Implementation Status

This decision is not implemented. The complete playtest coordinator, namespace-aware owner-manifest protocol, reset pointer transition, isolation enforcement, external-effect suppression, and focused proof remain gaps. Existing grant and realm-routing seams do not demonstrate implementation of this contract.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TENANT-03`
- Decision date: 2026-07-20
- Decision key: `TENANT-03`
- Primary capability: `AR-3.4` playtest forks, reset, expiry, and isolation
- Affected capabilities: `AA-3.2`, `GR-2.1`, `GR-3.1`, `GR-3.2`, `AA-1.2`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of playtest initialization, snapshot scope and consistency, durable-state identity, reset, external effects, and merge-back

## Context

Playtests need enough isolation to exercise a game without modifying production, but not every playtest needs or should receive a complete production clone. The previous contract described every creator-managed fork as a source-realm snapshot and keyed copied durable state to its `gameInstanceId`. It did not let creators choose a fresh or seeded start, define whether a snapshot covered an entire realm or selected testers, or require all domain owners to prove a complete and mutually consistent snapshot before the fork became admissible.

`gameInstanceId` identifies replaceable runtime execution, not durable playable state. [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) establishes `playableStateNamespaceId` as the durable identity and requires each new playtest lifecycle to receive a fresh namespace. Playtest initialization and reset therefore need an explicit namespace-aware contract rather than an in-place best-effort copy.

## Decision

### Initialization Modes and Identity

Creating a playtest explicitly selects one initialization mode:

- `fresh` creates an empty isolated playable-state namespace and materializes only the minimum state required by the selected published build and its normal creation policy.
- `seeded` creates an isolated namespace from an explicit, versioned seed or sample-data definition. Mutable production runtime state is not an implicit seed.
- `snapshot` creates an isolated namespace from an explicit source realm and snapshot scope.

Every new playtest lifecycle receives a fresh immutable `playtestLifecycleId`, a fresh `playableStateNamespaceId` under [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md), and an initial positive monotonic `playtestStateGeneration`. The lifecycle ID identifies one lifecycle for the exact tenant/world/realm target and is never reused for another lifecycle. Replacing a runtime instance within that lifecycle retains the lifecycle ID, namespace, and generation; creating another playtest lifecycle does not reuse them. The selected runtime `gameInstanceId` remains the execution and routing identity, not the durable-state key.

The `playtestLifecycleId` is required, unchanged, in the durable lifecycle and preparation records, every owner manifest, reset evidence, the admission pointer, cleanup acknowledgements, and all lifecycle-bound admission, grant, revocation, and operator-target bindings. A record or binding without the exact lifecycle ID, or with a conflicting lifecycle ID, is not evidence for this lifecycle and cannot open admission. The coordinator aggregates owner outcomes only for this lifecycle: any mix of `APPLIED` and `NOT_APPLIED`, unknown outcome, or contradictory evidence produces `RECONCILIATION_REQUIRED` and keeps admission closed.

For private/playtest admission, the authoritative pointer and discovery proof carry the positive `playtestStateGeneration` separately from the route `pointerVersion`; `pointerVersion` is the admission-pointer CAS fence and is not a substitute for the playtest generation. Public-production pointers and snapshots omit this field rather than fabricating a zero or null generation. Every lifecycle-bound owner fence, pointer audit/readback, reset digest, and exact retry compares the applicable lifecycle ID, namespace, generation, and pointer fence together.

### Snapshot Scope and Preparation

A snapshot request declares either:

- `whole-realm`, containing the complete source-realm state included by the playtest snapshot contract; or
- `selected-roster`, naming the accounts or characters intended for the playtest.

Selected-roster does not mean copying only character rows. Each owning service must enumerate and provide the complete dependency closure required for those selected players and the chosen playtest behavior, including namespace-scoped progression, resources, inventory, equipment, learned abilities, loadouts, ownership associations, and referenced or shared runtime/world state that cannot be omitted without changing represented behavior. If an owner cannot classify, resolve, or validate that closure, snapshot creation is rejected rather than silently producing a partial fork.

Every preparation records the exact target tenant/world/realm, fresh target `playableStateNamespaceId`, positive `playtestStateGeneration`, initialization mode, target runtime version, optional script patch, and participant evidence. Snapshot preparation additionally records the exact source tenant/world/realm, source `gameInstanceId`, source `playableStateNamespaceId`, and shared `sourceSnapshotFence`. Preparation and every owner manifest bind the exact common state-family/owner-registry revision, or the complete owner-scoped catalog epochs, used to enumerate required owners and state families; a revision or epoch change invalidates the attempt and keeps admission closed for revalidation. Every required owning service must return a mode-specific manifest. For `selected-roster`, each data-family entry must state whether the family is necessary for the represented behavior, whether its inclusion is authorized by the selected roster and scope, and its redaction classification (`unredacted`, `redacted`, or `excluded`); a necessary family that is not authorized, or an unauthorized inclusion, rejects preparation. Owner manifests also record included families, scope, counts or equivalent completeness evidence, target namespace/generation/build identity, and the mode-appropriate provenance and digest evidence defined below.

For `snapshot`, the coordinator establishes one shared point-in-time `sourceSnapshotFence` for the source realm/namespace and every owner binds its manifest to that exact fence; independently timed owner reads are not a shared snapshot. A `fresh` manifest has no source realm, instance, namespace, or snapshot identity and must not fabricate one. A `seeded` manifest carries the versioned seed/sample identifier, provenance, and seed digest. A `snapshot` manifest carries the source tenant/world/realm, source `gameInstanceId`, source `playableStateNamespaceId`, `playableStateScope`, and shared `sourceSnapshotFence`. Preparation is all-or-nothing: Game Session must not publish or admit the playtest realm until all required manifests agree on the exact owner-registry revision/epochs, the target namespace is complete, and the chosen target build has validated. Partial materialization remains non-admissible and must be retried or cleaned up rather than exposed as a playable fork.

The mechanism used to establish the owner-consistent boundary may evolve, but it must not compose unrelated best-effort reads while production is changing and call the result one snapshot. Owners must either participate in a coordinated fence/checkpoint or provide immutable source evidence that the coordinator can prove belongs to the same accepted boundary.

### Isolation and Exclusions

All initialization modes isolate mutable gameplay state in the playtest namespace. Snapshot copies are new fork-local records associated with the same platform accounts and tenant; they are not live references to production records.

The following are never cloned as active playtest authority:

- billing records, invoices, payment methods, purchases, subscriptions, or entitlement authority;
- authentication sessions, credentials, token or connect-token replay state;
- source moderation cases, source audit history, or source incident records.

Playtest activity creates its own scoped operational evidence where required. Outbound integrations, monetization effects, and other irreversible external actions are suppressed or redirected to explicit test sinks; no initialization mode may mutate production through an external side effect.

### Reset and Merge Boundary

Reset is generation replacement, not an in-place destructive rewrite. It retains the `playtestLifecycleId`, prepares a fresh `playableStateNamespaceId` from the selected `fresh`, `seeded`, or `snapshot` input, advances to the next positive `playtestStateGeneration`, validates the same completeness and isolation rules, and then moves the playtest realm's admission pointer to a runtime bound to that new namespace. Before that pointer CAS, each owner performs the owner-local fence and flush/drain, durably acknowledges the result, and classifies and reads back the outcome as `APPLIED`, `NOT_APPLIED`, or unknown, using the existing [cutover hold and readback vocabulary](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md). The reset pointer operation binds the exact current `playtestLifecycleId`, `playtestStateGeneration`, namespace, pointer fence/version, and ADR 0122 hold identity/fence into the authoritative pointer/CAS/readback record; rollback, abort, or reopen must reread and exact-match that binding before any state transition. The preparation, owner manifests, reset digest, owner readbacks, and final admission decision also bind the exact common state-family/owner-registry revision or complete owner-scoped catalog epochs used for enumeration; any revision or epoch change invalidates the attempt and keeps admission closed for reconciliation. Missing, stale, mismatched, contradictory, or unavailable pointer, owner-registry, or hold evidence keeps admission closed in `RECONCILIATION_REQUIRED`. The coordinator aggregates those outcomes before Game Session performs the admission-pointer CAS: only an aggregate of `APPLIED` for every required owner permits advancement; any mix of `APPLIED` and `NOT_APPLIED`, unknown outcome, or contradictory lifecycle/generation/revision evidence produces `RECONCILIATION_REQUIRED` and keeps admission closed. Every incoming write must validate the current `playtestStateGeneration` and pointer fence at its owner boundary, so stale generation or pointer-fence evidence is rejected throughout the transition. This is coordinated owner-local evidence and readback followed by a Game Session pointer CAS, not a cross-owner atomic transaction. If the pointer CAS fails after any owner has reported `APPLIED`, the old generation cannot reopen until [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) authoritative reconciliation proves the current pointer, hold, fence, owner-registry revision/epochs, and owner outcomes; no generic compensation is inferred. The old generation is fenced from new admission and retired through the bounded lifecycle and retention contract. Failure before the pointer move leaves the old generation authoritative and admissible according to its prior state only after every required owner has durably cleared its reset fence and returned a known, non-contradictory `NOT_APPLIED` unfence readback; missing, unknown, mixed, or contradictory evidence keeps admission closed pending [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) authoritative reconciliation. It does not expose a partially reset namespace.

Runtime writes from a playtest never merge automatically into production or another playtest. Production promotion uses the normal published-build launch and cutover workflow, not copied runtime currency, items, progression, or world mutations. A future narrow diagnostic export may be added for explicitly classified, non-authoritative evidence, but it does not establish a general gameplay-state import or merge contract.

Playtest visibility, grant ownership, expiry, revocation, and session treatment remain owned by `PLAYTEST-01`; this decision does not broaden playtest admission authority.

## Consequences

- Creators can choose a cheap clean environment, repeatable sample data, or high-fidelity production-derived state without treating all three as the same operation.
- Every lifecycle and reset has a distinct durable namespace, preventing stale runtime work or failed cleanup from writing into the new playtest state.
- Snapshot creation can be slower or fail when an owning service is unavailable, source state is changing without usable checkpoint evidence, or dependency closure is incomplete. This is the cost of not presenting an internally inconsistent test environment as a valid snapshot.
- Selected-roster snapshots can reduce copied private data and storage, but owners must understand shared dependencies and cannot assume roster filtering alone is a complete state boundary.
- Reset needs capacity for the new namespace and old-generation retention during the bounded transition.
- Excluding production authority and suppressing external effects makes some production integrations unavailable or fake in playtests; those paths require explicit test sinks to be exercised meaningfully.
- There is no automatic workflow for carrying playtest-earned value or world changes into production.

## Alternatives Considered

### Require Every Playtest to Clone the Whole Production Realm

Rejected because it copies unnecessary player data and state, increases preparation time and storage, and makes lightweight fresh or fixture-based testing needlessly expensive. Whole-realm snapshot remains available when full simulation is actually required.

### Key Playtest State to the Runtime Instance and Reset It in Place

Rejected because runtime instances are replaceable and stale work, partial deletion, or an interrupted reset could corrupt the replacement state. A fresh namespace plus atomic admission-pointer movement creates a clear authority boundary and follows [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md).

### Best-Effort Per-Service Copy

Rejected because independently timed copies can represent impossible combinations of player, inventory, progression, and world state. A fork must either prove one accepted source boundary and complete dependency closure or fail before admission.

### Support General Merge-Back

Rejected because reconciling concurrent production and playtest mutations across currency, unique items, progression, inventory, and world state would create conflict policy, duplication, authorization, and audit obligations comparable to a distributed data-migration product. Normal design publication and production cutover cover promotion of authored content without importing runtime value.

## Implementation and Proof Obligations

Game Session needs durable `playtestLifecycleId`, `playableStateNamespaceId`, positive `playtestStateGeneration`, initialization-mode, scope, source/build-evidence, participant-manifest, preparation-state, and admission-pointer records. Every owning domain needs lifecycle- and namespace-aware creation and cleanup plus deterministic preparation evidence for the state families it owns, bound to the exact common state-family/owner-registry revision or complete owner-scoped catalog epochs; cleanup acknowledgements, grant/revocation bindings, and operator-target bindings carry the same lifecycle ID and applicable generation. Selected-roster proof must demonstrate complete dependency closure and reject missing, unclassified, wrong-source, wrong-build, stale, duplicate, changed-registry, or partially materialized data.

End-to-end proof must cover all three initialization modes, whole-realm and selected-roster snapshots, concurrent source mutation, participant outage and disagreement, owner-registry revision changes between preparation and admission, retry after ambiguous failure, cleanup of abandoned preparation, durable owner flush/drain acknowledgement with `APPLIED`, `NOT_APPLIED`, or unknown readback, including durable reset-fence clearing and the known, non-contradictory `NOT_APPLIED` unfence readback required before reopening the old generation after a pre-pointer failure, aggregate `RECONCILIATION_REQUIRED` handling for mixed or contradictory outcomes, reset failure before and after pointer movement, CAS failure after an `APPLIED` owner outcome and the required ADR 0122 reconciliation, stale old-generation work, replacement within one playtest lifecycle, exclusion of production authority, suppressed/test-sink external effects, and absence of automatic merge-back. It must also prove that no partially prepared namespace becomes visible or admissible.

The current architecture and implementation do not establish this complete snapshot coordinator, owner manifest protocol, namespace-aware reset, or end-to-end proof. Existing playtest grant and realm-routing substrates are supporting boundaries, not evidence that this decision is implemented.

## Reversibility and Revisit Triggers

Seed formats, participant manifest schemas, checkpoint mechanisms, retention periods, and selected-roster closure representations may evolve while retaining explicit initialization mode, fresh namespace identity, all-or-nothing owner consistency, production-authority exclusions, side-effect isolation, and no automatic merge-back. Revisit the merge boundary only when a concrete product use case identifies exact transferable state families and conflict rules; prefer a narrow typed promotion or diagnostic flow over a generic state merge.

## Required Documentation Alignment

- [Versioning and runtime](../system-architecture-versioning-runtime.md)
- [Creator journeys](../../product/user-journeys/creators.md)
- [Operator journeys](../../product/user-journeys/operators.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [ADR 0122](./adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md)
