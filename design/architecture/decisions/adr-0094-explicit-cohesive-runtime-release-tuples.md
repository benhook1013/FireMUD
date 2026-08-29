# ADR 0094: Explicit Cohesive Runtime Release Tuples

## Status

Accepted

## Implementation Status

Explicit tuple selection is target state. Current design and implementation evidence does not yet prove complete `READY` and base-version enforcement, stable idempotent tuple resolution, or launch-time patch and plugin compatibility proof. See [launch descriptor version-resolution rules](../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules) and [versioned publishing and runtime configuration](../system-architecture-versioning-runtime.md#game-version-publishing).

## Canonical Design

- [Launch descriptor version-resolution rules](../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules)
- [Version activation and rollback](../system-architecture-versioning-runtime.md#version-activation--rollback)
- [Game Design version control and publication](../microservices/game-design-service/version-control.md#change-vehicle-selection-matrix)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CONTENT-02`
- Primary capability: `AR-3.3` immutable runtime version selection
- Affected capabilities: `AR-1.5`, `AR-3.2`, `AA-3.3`, `GR-1.1`, `PO-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of cohesive release selection, patch and plugin compatibility, mutable aliases, launch readiness, reproducibility, and rollback
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CONTENT-02`

## Context

Runtime behavior can depend on a base game version, its published release bundle and manifest, a script-only patch, and enabled plugin versions. Selecting those parts independently or following a mutable `latest` pointer can create a combination that was never validated together. A publication, patch, or alias change could then alter a running game or make a nominal rollback select different content from the original launch.

Operators and creators still benefit from friendly channels such as `production` and `preview`. The boundary must preserve that convenience without turning the channel into mutable runtime authority.

## Decision

Every game instance pins one explicit legal runtime release tuple containing:

- the base game version;
- the immutable release bundle and manifest;
- the selected script patch, if any; and
- the enabled plugin versions.

The selected script patch proves compatibility against the selected base version and, where required for launch, tenant runtime readiness before the tuple becomes launchable. Each plugin proves immutable publication and compatibility against that base version for descriptor selection; plugin runtime readiness, binding resolution, and activation fencing remain post-launch Automation activation/resume gates rather than launch-descriptor predicates. The launch descriptor records the complete resolved tuple before instance identity is allocated or runtime admission succeeds. Changing any member creates a new recorded tuple and follows the applicable rollout process; a running instance never follows `latest` or silently substitutes another compatible-looking member.

Friendly aliases such as `production` and `preview` may select a release at the start of a new launch or rollout. Alias resolution produces a concrete immutable version, after which the launch or rollout pins that result. Later alias movement does not mutate existing descriptors, running instances, restart behavior, or rollback targets.

Readiness and publication are explicit state transitions. A tuple is legal only when the base release, bundle, manifest, patch, plugins, required attestations, remap proof, and applicable tenant readiness satisfy their declared launch gates; plugin runtime readiness is evaluated by Automation at activation/resume under its own lifecycle and fence contract. Retry of tuple creation under the same idempotency identity returns the same resolved tuple rather than resolving a mutable alias again.

Rollback selects a previously recorded concrete tuple. It does not reconstruct a former release by consulting current aliases or independently selecting component versions.

## Consequences

- Launch, restart, recovery, and rollback reproduce the same content and executable behavior from a concrete recorded selection.
- Publishing a new release, patch, or plugin and moving an alias cannot silently alter existing instances.
- Creators retain convenient release channels for initiating new launches and rollouts.
- Publication and rollout require explicit compatibility evidence, readiness checks, descriptor state, and idempotent resolution.
- A patch or plugin that might work but lacks proof against the selected base remains unavailable until its compatibility and readiness are established.
- Storage and operational surfaces must retain the immutable manifests and tuple records needed by supported recovery and rollback windows.

## Alternatives Considered

### Mutable Release Channels and Independently Selected Components

Runtime could follow `latest`, `production`, or another mutable pointer and independently choose the current base release, asset bundle, patch, and plugin versions. This reduces descriptor and rollout bookkeeping and makes a publication immediately available. It is rejected because an alias movement or component publication could change restart behavior, create an untested mixed release, silently alter existing games, and make rollback non-reproducible.

### Pin Only the Base Version

Runtime could pin the base game version while allowing patches, plugins, or derived assets to follow their own current selections. This is rejected because those components can materially change runtime behavior and therefore belong to the same compatibility and reproducibility boundary.

## Implementation and Proof Obligations

Define one canonical launch-descriptor schema and validation path for the complete runtime release tuple. Before admission, enforce immutable normalized references, base-version compatibility, patch readiness and applicable tenant readiness, plugin publication and base-version compatibility, required attestations, and stable idempotent alias resolution. Plugin runtime readiness, binding resolution, and activation fencing remain Automation-owned post-launch activation/resume gates. Alias or channel resolution is permitted only for a new launch or rollout; an already-recorded complete compatible tuple remains the pinned authority for its existing instance, restart or recovery, and authorized rollback even after the alias moves. Runtime reads only the recorded tuple. Admission rejects explicitly revoked, absent, incomplete, mixed, incompatible, unattested, tenant-not-ready, or otherwise invalid tuple selections; alias supersession alone is not revocation and must not invalidate a complete recorded tuple.

Proof must cover first launch, same-identity retry after alias movement, restart and recovery, rollout to a changed member, authorized rollback to an earlier recorded complete compatible tuple after alias movement, explicit revocation and its admission rejection, missing or non-ready patch, incompatible plugin, incomplete attestation, tenant-not-ready state, and concurrent alias updates. It must demonstrate that existing instances remain pinned, that alias supersession does not invalidate their recorded tuple, that explicit revocation does invalidate admission, and that no runtime path follows a mutable channel.

Current design and implementation evidence does not yet prove complete `READY` and base-version enforcement, stable idempotent tuple resolution, or launch-time patch compatibility proof. This decision records the target contract and does not claim those gaps are implemented.

## Reversibility and Revisit Triggers

Tuple members and compatibility evidence can evolve without changing the immutable-selection boundary. Revisit only if runtime gains a separately justified live-update model with atomic whole-instance transition, durable provenance, compatibility proof, deterministic recovery, and rollback semantics equivalent to a recorded tuple.
