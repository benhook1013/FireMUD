# ADR 0111: Unified DSL with Distinct Embedded Script and Plugin Lifecycles

## Status

Accepted

## Implementation Status

One DSL runtime with distinct embedded-script and linked-plugin lifecycles is target state. FireMUD has signed ZIP intake, immutable plugin metadata and asset storage, publication rows, runtime plugin state, activation preflight, policy reconciliation, durable script work items, and shared sandbox seams. Current intake requires an allowlisted Ed25519 signature and currently selects, persists, and exposes one allowlisted `signerKeyId`; it does not yet persist or expose the complete target `verifiedSignatures[]` set. It parses only part of the documented plugin manifest and does not yet demonstrate one complete bundle-to-compiled-runtime path for bindings, component requirements, capability grants, or unsigned platform-attested intake; the current evaluator is also narrower than the complete graph runtime described by the target architecture. The target Automation-owned plugin lifecycle fence `(pluginActivationEpoch, lifecycleRevision)` is not yet persisted, exposed, or propagated through the current runtime work and handoff surfaces, so same-version re-enable fencing remains an implementation and proof gap.

## Canonical Design

- [Scripting DSL reference and lifecycle](../system-architecture-scripting-dsl-reference-and-lifecycle.md)
- Current-state inventory: [Implementation Status](#implementation-status)
- [Scripting DSL for designers](../system-architecture-scripting-dsl-for-designers.md)
- [Scripting quotas and operations](../system-architecture-scripting-quotas-and-operations.md)
- [Scripting event registry](../system-architecture-scripting-event-registry.md)
- [Per-instance plugin activation epoch and final fence](./adr-0119-epoch-fenced-per-instance-plugin-activation.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CONTENT-03`
- Primary capability: `AS-1.2` sandboxed game-authored behavior
- Affected capabilities: `AR-1.3`, `AR-1.5`, `AS-1.5`, `AS-1.6`, `GR-4.1`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of embedded scripts, reusable plugins, external and marketplace distribution, profile compatibility, capability grants, sandboxing, and DSL runtime cost
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CONTENT-03`

## Context

FireMUD needs game-authored behavior without making arbitrary code, direct database access, or unbounded execution part of the gameplay runtime. It also needs a coherent way to distinguish behavior owned by one game from reusable add-ons that can be distributed and activated independently.

Earlier design language tied ordinary scripts to in-product authoring and plugins to externally authored signed bundles. That mixes several independent concerns: execution language, ownership, packaging, provenance, distribution channel, activation lifecycle, and capability policy. A graph does not become a different or more dangerous language merely because it arrived through a repository or file upload, and a marketplace label does not make its behavior safe.

At the same time, making every gameplay rule a DSL handler would move foundational invariants and routine hot-path work into an asynchronous, quota-bounded automation pipeline. The extension boundary therefore needs to preserve the typed gameplay engine as well as the shared DSL sandbox.

## Decision

FireMUD has one component-based DSL, compiler, validator, sandbox, and execution runtime for game-authored automation behavior. There is no separate in-product DSL and external-plugin DSL.

A **script** is one executable DSL graph or handler entrypoint. Ordinary embedded scripts are game-owned design data. They are authored or materialized into the Game Design revision model and released as part of an immutable game version or script-only patch. They follow that version or patch's publication, readiness, pinning, and rollback lifecycle.

A **linked plugin** is an immutable, independently versioned and independently activated bundle. It contains graphs written in the same DSL, their bindings and bounded configuration, and optional plugin-owned assets. It retains a stable `pluginId`, exact `pluginVersionId`, compatibility evidence, provenance, and a separate instance-scoped enable, drain, disable, update, and rollback lifecycle. A plugin is therefore a packaging and lifecycle role, not another execution language or an automatic trust tier.

Automation owns the monotonic `pluginActivationEpoch` and owner `lifecycleRevision` for each `(tenantId, gameInstanceId, pluginId)`. The pair is plugin runtime-state fence evidence independent of Game Session's script pin and `scriptPinEpoch`; Automation captures it on admitted plugin work and preserves and revalidates it through evaluation, durable persistence, handoff, staged and final effect execution, retry, replay, and recovery. The detailed transition matrix, Game Session projection, durable fence acknowledgement, and final-execution rules are owned by [ADR 0119](./adr-0119-epoch-fenced-per-instance-plugin-activation.md). Game Session consumes the monotonic local projection and does not become the plugin lifecycle authority or make a synchronous Automation call in the tick path.

The plugin lifecycle fence has an independent instance-scoped transition policy. Its exact epoch/revision advancement matrix, projection, acknowledgement, and final-execution rules are owned exclusively by [ADR 0119](./adr-0119-epoch-fenced-per-instance-plugin-activation.md); this decision retains only the independent plugin-versus-embedded lifecycle boundary.

Game Design's persisted launch-descriptor record binds the immutable requested plugin selection to immutable publication and compatibility evidence for each exact plugin version and target release. The launch response need not duplicate or transport mutable/current signer, component, or capability policy evidence. Automation remains authoritative for revalidating current signer, component, and capability policy at activation and on resume or recovery.

Marketplace catalogs, source repositories, local files, and other future delivery mechanisms are provenance and distribution channels for the same package contract. They may affect publisher identity, approval workflow, update discovery, support status, and operator policy. They do not change DSL semantics, sandbox enforcement, deterministic command validation, or runtime ownership.

An imported package that contains ordinary world, entity, ability, action, or other base-version DML cannot become an independently layered runtime plugin. That content must be materialized into a Game Design Draft, validated as game-owned content, and republished in a new game version. Independently activated plugins may use their own attested assets and reference compatible published game contracts, but they do not create a second mutable content authority above the base release.

External packages may arrive without an author signature, but an unsigned package cannot activate merely because a user accepted a warning. Before publication or activation, Game Design must establish an immutable package digest, validate its complete contents, record an authorized explicit approval, and issue or record a platform acceptance attestation bound to the exact package version and digest. Hosted operators may prohibit unsigned packages entirely. Author signatures provide stronger publisher provenance and revocation/update continuity, but source or signature alone grants neither elevated capabilities nor automatic updates.

For a signed bundle with multiple signatures, Game Design persists the complete canonically ordered verified signature set bound to the bundle digest; it must not select or persist one preferred signer. Verification requires at least one allowlisted signer and rejects any signature by an explicitly revoked signer. Automation re-verifies the complete set against current policy at activation and on resume or recovery. Logging & Admin visibility and plugin distribution evidence reflect the complete set where locally relevant.

DSL components and capabilities are admitted by exact-version, target-scope grants controlled by platform and operator policy. A package may declare what it requires, but it cannot grant those capabilities to itself. Marketplace review, an author signature, in-product authoring, or import into a Draft may establish provenance or eligibility for review; none bypasses capability admission, quotas, output bounds, sandbox checks, or domain-command authorization.

Starter-profile identity is only a compatibility and discovery hint. Profiles materialize editable game-owned content, so a creator may have changed or removed the definitions a plugin expects. Plugin publication and activation validate the actual stable identifiers, schemas, extension contracts, capabilities, and digests required by the exact target release. They do not infer compatibility merely from a recorded profile label.

Foundational invariants and routine hot-path mechanics remain typed platform or game-domain engine behavior. The DSL may orchestrate events, timers, quests, NPC behavior, conditional flows, and typed domain commands, but it does not replace the owner of atomic mutations, authorization, money or inventory correctness, tick fencing, or other core gameplay invariants. Profile-supplied defaults may include ordinary DML and embedded scripts, but ubiquitous mechanics should not require avoidable distributed DSL round trips when a bounded typed runtime primitive is the appropriate contract.

Embedded scripts and linked plugins retain the same sandbox, quota, output-budget, dry-run isolation, audit, and no-partial-command-set guarantees. Trust and packaging differences must not create a weaker execution path.

## Consequences

- Designers and package authors learn and target one behavior language and component model.
- Embedded scripts remain editable game-owned content, while linked plugins preserve independent distribution, activation, provenance, and update lifecycles.
- Marketplace and external packages can share one artifact schema without treating marketplace origin as proof of safety.
- Unsigned external intake remains possible where operator policy permits it, but it requires exact artifact attestation and explicit authorization rather than warning-based activation.
- Capability grants, compatibility checks, and update selection become explicit policy decisions bound to immutable versions and scopes.
- Packages containing ordinary base-game DML require Draft materialization and a game publish, so linked plugins cannot silently create layered world or gameplay-schema authorities.
- Profile-oriented plugins require stable, validated extension contracts; a profile name alone cannot make a modified game compatible.
- The typed engine remains responsible for hot-path and correctness-bearing behavior. Extensive DSL use still consumes durable work-item, quota, audit, handoff, and tick-queue capacity.
- Supporting multiple provenance channels adds intake, attestation, approval, and operator-policy work even though execution stays unified.

## Alternatives Considered

### Separate In-Product and External DSLs

Rejected because it duplicates compilers, validators, component semantics, security proof, and author knowledge without creating a useful runtime boundary. Distribution source is not an execution language.

### Require Every Plugin to Carry an Allowlisted Author Signature

Rejected as the only intake model. Signatures are valuable for publisher authentication, update continuity, and signer-wide revocation, but privately produced or legacy external packages can be admitted safely only through explicit operator policy, exact digest validation, and platform acceptance attestation. A warning without those controls remains insufficient.

### Treat Every Script as an Independently Activated Plugin

Rejected because it would impose package, activation, and trust-policy machinery on ordinary game-owned behavior and fragment a game's coherent version history.

### Materialize Every Plugin into the Game Draft

This is the strongest simpler alternative. It would leave one publication and activation lifecycle and make all imported content editable. It is not the only supported model because it loses independently selectable add-ons, immutable upstream provenance, per-instance activation, and bounded plugin-specific disable or update workflows.

### Permit Plugins to Layer Arbitrary DML over a Published Game

Rejected because it creates competing runtime authorities for world, entity, ability, and other version-owned content. Such packages must instead materialize into a Draft and pass the normal publication boundary.

### Use the DSL for All Non-Default Gameplay Mechanics

Rejected as a blanket rule. The durable asynchronous scripting path is appropriate for bounded orchestration but is not the default execution substrate for foundational invariants or routine latency-sensitive mechanics.

## Implementation and Proof Obligations

Define one canonical graph and component artifact contract consumed by embedded scripts and linked plugins. Prove identical compilation, loop and output-cost validation, sandbox budgets, dry-run isolation, command staging, domain-command authorization, and audit outcomes regardless of provenance channel.

Plugin runtime proof must cover the exact pair and transition rules in [ADR 0119](./adr-0119-epoch-fenced-per-instance-plugin-activation.md), including entry into `DRAINING` without an epoch advance but with exactly one `lifecycleRevision` advance; in-flight completion and restart recovery under the unchanged exact plugin version and activation-epoch fence while draining, using only the immediately preceding `ENABLED` lifecycle revision whose winning admission/fence compare-and-set committed before the durable drain barrier; one epoch advance for each successful version switch, completed active disable, final drain/forced drain, same-version reactivation after invalidation, revocation, or policy-driven containment, with the corresponding committed lifecycle-revision advance; rejection of the displaced epoch or lifecycle revision after each such transition; unchanged epoch for never-active disable, failed operations, no-ops, exact retries, and bookkeeping that does not change the active version or invalidate or re-admit current work; exact current lifecycle revision required for `ENABLED`; and stale, reordered, contradictory, arbitrary-lower, or capture-only lifecycle evidence rejected at every evaluation, persistence, handoff, staged/final effect, retry, replay, and recovery boundary. The proof must keep the Automation-owned plugin `(pluginActivationEpoch, lifecycleRevision)` pair independent from Game Session's script pin epoch and out of execution identity.

Plugin intake must persist an immutable digest, complete manifest and content identity, provenance channel, explicit approver and approval scope, platform acceptance attestation, requested components and capabilities, granted exact-version capabilities, compatibility evidence, and publication status. For signed bundles, it also persists the complete canonically ordered `verifiedSignatures[]` set bound to the bundle digest; unsigned bundles use the separate explicit-approval and platform-attestation path. Activation and resume/recovery revalidate the applicable complete signature set and must reject a digest mismatch, revoked signer, attestation mismatch, missing approval, incompatible target contract, absent grant, prohibited unsigned origin, or stale or revoked provenance without partially changing runtime state. At runtime evaluation, each dispatch, and final-effect fences, the applicable current signer/publication/provenance policy is revalidated fail-closed under [Scripting Contracts §8](../system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope), without repeating full cryptographic complete-set verification at every boundary; unsigned packages continue to use the separate exact-digest, validation, scoped-approval, and platform-attestation path.

Proof must cover marketplace, signed external, and operator-permitted unsigned packages; a forged or changed package under an existing version; approval reuse against different bytes; an unsigned package with only a UI warning; signer and exact-package revocation; capability escalation attempts; update discovery without automatic activation; and hosted policy that rejects unsigned intake.

Compatibility proof must cover a profile-derived unmodified game, edited or removed profile definitions, equivalent actual contracts without the profile label, stable-ID and schema mismatch, plugin-owned assets, and a package containing base-version DML that is routed to Draft materialization rather than linked activation.

Performance proof must measure representative trigger fan-out, evaluation, durable-row and audit amplification, handoff latency, tick-queue delay, quota behavior, and tenant isolation. Correctness-bearing mechanics must prove their typed owner and failure semantics independently of best-effort or quota-deniable DSL execution.

## Reversibility and Revisit Triggers

Provenance channels, marketplace policy, attestation format, package schema, and capability vocabulary may evolve while retaining one DSL/runtime and explicit embedded-versus-linked lifecycles. Revisit linked plugins if measured use shows that Draft materialization covers nearly all packages and the independent activation control plane is not justified. Define a separate layered-content decision before allowing linked plugins to introduce ordinary version-owned DML, and require measured runtime evidence before moving routine hot-path mechanics into DSL execution.
