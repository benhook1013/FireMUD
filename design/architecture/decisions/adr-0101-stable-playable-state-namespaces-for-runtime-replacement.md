# ADR 0101: Stable Playable-State Namespaces for Runtime Replacement

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `MS-GR-REPLACEMENT-STATE`
- Primary capability: `AR-3.3` live version replacement and state continuity
- Affected capabilities: `GR-2.1`, `GR-3.1`, `GR-3.2`, `AR-3.4`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of playable-state identity, realm isolation, durable-state classification, mapping authority, cutover preflight, fencing, and data-loss boundaries

## Context

A `gameInstanceId` identifies one runtime instance and changes when a realm is replaced. Using it as the identity of durable playable state forces replacement to copy state between runtime containers and makes survival depend on instance lifecycle details. It also creates pressure to discard data when compatibility work is difficult or to infer mappings that have not been approved.

FireMUD needs a stable identity for the player-visible state that is intended to survive replacement, explicit rules for state that requires version migration, and a fail-closed cutover when ownership or classification is incomplete.

## Decision

FireMUD assigns every playable runtime a stable `playableStateNamespaceId` separate from `gameInstanceId`. A replacement instance for the same logical playable realm retains the source namespace while receiving a new runtime instance identity.

Shared-state production realms use one stable tenant playable-state namespace. An isolated-state realm uses its own stable realm namespace, which does not change when its runtime instance is replaced. Each new playtest lifecycle receives a new namespace so playtest state cannot enter a production or other playtest namespace implicitly; a replacement within that same playtest lifecycle retains its namespace.

State is classified by its owning domain and namespace semantics:

- `S1` is durable state that survives unchanged within the same playable-state namespace. Replacement changes the active runtime pointer, not the identity of this state, and does not copy it into a new namespace.
- `S2` is durable state whose references or representation depend on the target content version. It survives only through a concrete, versioned mapping that the owning domain has approved, validated against the exact source and target versions, and successfully applied. Supplying or echoing a `remapSetId` without owner validation and application is insufficient.
- `S3` is state explicitly classified by its owner as instance-scoped and disposable at replacement, such as transient topology or encounter state. Only declared S3 families may be discarded with the old instance.

Unknown, unowned, or unclassified state blocks replacement cutover. It is never treated as S3 by default. Paid value, currency, unique items, progression, account ownership, and other durable player value must not be classified as S3 merely because a mapping is difficult, missing, or expensive.

Replacement preflight produces a durable summary bound to the tenant, playable-state namespace, source instance and version, target instance and version, owning domains, state families and counts, S1/S2/S3 classifications, required and approved mapping versions, validation and application status, unknowns, data-loss boundary, and freshness evidence. Preflight fails closed on unknown state, missing ownership, incomplete classification, unavailable owners, or any required S2 mapping that is absent, unapproved, invalid, or unapplied.

Cutover fences new source writes, flushes admitted durable writes, and revalidates compatibility and preflight freshness against the exact state, versions, namespace, mappings, and owner epochs used for the decision. Stale evidence requires a new validation; cutover does not assume that a previously successful preflight remains valid.

The old instance is retained and drained while the replacement is prepared and validated. Routing changes through a fenced compare-and-swap of the playable realm's active-instance pointer only after all required owners report compatible, fresh state. Failure before that swap leaves the old instance authoritative. The old instance is not terminated or cleaned up until the swap is durable, old admission is fenced, sessions have drained or moved under the lifecycle contract, and all owning services acknowledge that cleanup is safe.

This decision supersedes guidance that keys an isolated realm's durable playable identity directly to `gameInstanceId` or treats an unclassified row family as S3.

## Consequences

- Runtime instances can be replaced without copying S1 state or changing its durable identity.
- Shared realms intentionally see the tenant namespace, isolated realms retain stable separation, and new playtests cannot inherit another namespace accidentally.
- S2 survival becomes auditable and domain-owned instead of depending on best-effort name, slug, position, or identifier matching.
- Replacement can be delayed by unknown state, unavailable owners, mapping work, stale preflight evidence, or an incomplete drain; this is the required cost of preventing silent data loss.
- Services need namespace-aware keys and mutation authorization, explicit state-family registries, mapping application surfaces, freshness epochs, and fenced lifecycle coordination.
- Retaining the old instance through cutover consumes capacity but preserves one authoritative source until routing and cleanup are safe.
- Rollback after a mapping or pointer swap is not implied; it requires its own compatible state and mapping decision.

## Alternatives Considered

### Key Durable State to Each Runtime Instance and Copy It During Replacement

Rejected because instance identity is intentionally replaceable. Copying durable state creates duplicate-authority windows, partial-copy recovery problems, and avoidable ambiguity about which instance owns player value.

### Infer Mappings and Discard Anything That Cannot Be Mapped

Rejected because names, slugs, positions, and apparent similarity are not authoritative identity. This approach can silently corrupt or destroy durable value and turns implementation difficulty into an undeclared data-loss policy.

### Treat Every State Family as Durable and Require It to Survive

Rejected because transient topology, encounters, ambient state, and other explicitly instance-scoped data can be safely recreated or discarded. Requiring migration for all such data would make replacement unnecessarily expensive while obscuring the important S1 and S2 guarantees.

## Implementation and Proof Obligations

The data model and public contracts must resolve `playableStateNamespaceId` independently of `gameInstanceId`, preserve it across replacement, and enforce the tenant-shared, stable isolated-realm, and fresh playtest namespace rules. Durable-state reads and writes must authorize the full tenant, namespace, and active-instance context rather than a globally unique entity identifier alone.

Each owning domain must publish an exhaustive state-family classification and expose deterministic inventory, validation, mapping-application, flush, and freshness evidence for the families it owns. S2 proof must validate actual versioned mapping contents and their application, not merely the presence of an identifier. Negative proof must cover missing, stale, unapproved, partial, wrong-version, and wrong-namespace mappings, as well as paid, currency, unique-item, and progression rows that cannot be mapped. Every unclassified family and unexpected row must block cutover.

End-to-end proof must cover all three namespace modes, unchanged S1 continuity, explicit S2 transformation, declared S3 cleanup, complete preflight summaries, mutations and mapping changes between preflight and cutover, unavailable owners, drain timeout, concurrent cutover attempts, stale fences, failure before and after pointer swap, retry convergence, and prevention of simultaneous authoritative admission by old and new instances. It must prove that the old instance and its disposable state are retained until fenced routing and owner cleanup acknowledgements make termination safe.

Current replacement validation is shallow and the required implementation and runtime proof are not claimed by this ADR. Existing family enumeration or acceptance of a supplied mapping identifier does not prove stable namespaces, exhaustive classification, owner-approved mapping application, freshness revalidation, or fenced end-to-end cutover.

## Reversibility and Revisit Triggers

Namespace representation, preflight schemas, mapping formats, and retention periods may evolve while preserving stable playable identity, owner-controlled S2 survival, explicit-only S3 disposal, and fail-closed cutover. Revisit namespace scope if product policy introduces intentional state sharing across tenants or isolated realms, but require an explicit migration and authorization design. Revisit old-instance retention when a proven snapshot or rollback mechanism provides equivalent safety; do not weaken fencing, freshness, classification, or durable-value protections merely to accelerate replacement.
