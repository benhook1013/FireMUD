# FireMUD Scripting & Automation: Control Plane API

This document specifies the direct **control plane API** surface required to operate scripting and automation safely across Game Session, Automation & Scripting, Game Design, and Logging & Admin.

It exists to remove ambiguity from “conceptual APIs” referenced in service READMEs: this is the target-state contract that must be implemented in protos/services over time.

Workflow sequencing for rollback, pause/resume, drain/purge, dead-letter recovery, and operator audit flows lives in [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).

The exact pin/epoch authority and stage-aware recovery rules used by these APIs are defined in [Scripting & Automation: Cross-Service Contracts](./system-architecture-scripting-contracts.md), with accepted transition rationale in [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](./decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0111](./decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).

## Implementation Status

The API shapes below are target-state contracts. Current Automation exposes bounded readiness, convergence, rollout, schedule, plugin, dead-letter, and replay surfaces, but `GetAutomationPinConvergence` does not yet expose `observedScriptPinEpoch`, instance-rollout lookup remains patch-version-only rather than exact-epoch lookup, and replay remains aggregate parent-row requeue rather than stage-aware per-row recovery. The current Game Session proto/runtime also lacks the tagged `expectedCurrentPin` field for pin CAS; that target precondition remains an implementation and proof gap. These are implementation and proof gaps, not alternate API semantics; see the [Automation and Scheduler Runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) and [Game Session Runtime and Tick Coordination tracker](../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status).

Routing note:

- Use this document for control-plane API shape, authoritative ownership, and state-mutation contracts.
- Use [system-architecture-scripting-rollout-and-rollback.md](./system-architecture-scripting-rollout-and-rollback.md) for drain/rollback workflow sequencing.
- Use [system-architecture-scripting-control-plane-operations.md](./system-architecture-scripting-control-plane-operations.md) for operator workflow execution details.

## Table of Contents

- [Implementation Status](#implementation-status)
- [Scope](#scope)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane APIs (Normative)](#control-plane-apis-normative)
- [Related Control Plane Contracts](#related-control-plane-contracts)
- [Idempotency, AuthZ, and Audit](#idempotency-authz-and-audit)

---

## Scope

This document covers:

- Pinning and rolling back `scriptPatchVersion` for a running `gameInstanceId`.
- Patch lifecycle visibility (`READY`, `FAILED`, `SUPERSEDED`) plus per-instance rollout/rollback visibility as an operator-facing contract.
- Plugin lifecycle operations (enable/disable/drain) scoped to a running `gameInstanceId`.
- Event-ingress admission contracts and canonical application errors for control-plane decisions.

This document does not define the designer-facing DSL, sandbox internals, per-trigger runtime semantics, or workflow sequencing (see the scripting DSL reference, sandbox runtime docs, and Control Plane Operations).

The canonical event-registry entry model referenced by ingress APIs lives in `design/architecture/system-architecture-scripting-event-registry.md`.

Compact publication-to-runtime sequence:

| Flow | Design-time acceptance owner | Runtime readiness / eligibility | Runtime activation owner |
| --- | --- | --- | --- |
| Script patch publish -> runtime pin | Game Design publishes the immutable patch artifact | Automation & Scripting reports tenant readiness for `scriptPatchVersion` | Game Session pins the ready patch per `{tenantId, gameInstanceId}` |
| Plugin upload/publish -> runtime activation | Game Design publishes the immutable plugin version and signer-policy-visible status | Automation & Scripting exposes plugin runtime/status visibility and signer-policy convergence | Automation & Scripting activates/drains/disables the plugin per `{tenantId, gameInstanceId, pluginId}` |

## Principles

- **Game Session owns tick safety.** Game Session is the only writer for `tick:*` and enforces the version fence at execution time. Automation never writes `tick:*` directly.
- **Pinned versions are explicit.** Runtime must never “auto-upgrade” to a newer patch without an operator/designer action captured in the control plane.
- **Control plane is idempotent.** Every mutating operation must accept a caller-provided `controlPlaneRequestId` and be safely retryable.
- **Auditable and observable.** Every mutating action must emit an audit entry and a durable status event that downstream tooling can consume.
- **Pin visibility is bounded-staleness.** Services that cache pinned patch/plugin versions must enforce a max staleness bound and fail closed on stale/unknown pin state for admission-critical decisions.
- **Runtime scope is instance-first.** Tenant-level patch readiness is only an eligibility gate; direct API mutations and read surfaces must preserve `(tenantId, gameInstanceId)` isolation.

## Actors and Responsibilities

- **Game Design Service (designer control plane)**
  - Publishes script patches and plugin bundles.
  - Owns the immutable design-time publication lifecycle for plugin versions and exposes whether a plugin version is eligible for runtime activation.
  - Triggers runtime reload via publication notifications.
  - Does not repin running games by itself; repinning is an operator action.

- **Automation & Scripting Service (runtime + patch lifecycle)**
  - Evaluates triggers, persists script work items durably, and hands off to Game Session.
  - Tracks per-tenant patch lifecycle state (`READY`, `FAILED`, `SUPERSEDED`) and enforces admission rules (“only `READY` is runnable”).
  - Emits tenant patch readiness lifecycle events (`ScriptPatchTenantStatusChanged`) when readiness state changes.
  - Consumes Game Session pin events to project non-authoritative observed pin/convergence projections; it never owns or derives rollout history.

- **Game Session Service (gameplay + tick control plane)**
  - Owns the pinned `scriptPatchVersion` for each `(tenantId, gameInstanceId)`.
  - Enforces the version fence on execution: commands produced under a non-pinned patch must not execute.
  - Exposes admin-only APIs to pause/resume ticks and to update the pin.
  - Emits a pin change event after a successful pin update.

- **Logging & Admin Service (operator control plane)**
  - Presents operator workflows by orchestrating the operations companion doc, and surfaces the direct API responses to operators.
  - Drives changes by calling Game Session and Automation & Scripting APIs, never by writing Redis keys directly.
  - Consumes lifecycle and pin events to render status to operators.

## Control Plane APIs (Normative)

The API shapes below are described in gRPC-style terms but may be exposed via REST in operator-only deployments; the **fields and semantics are the contract**.

### Game Design: Design-Time Publication Visibility

These read surfaces expose immutable publication truth from Game Design. They are intentionally separate from tenant readiness and instance activation reads so operators and services do not collapse publication, readiness, and activation into one state machine.

#### `GetPublishedScriptPatchVersion`

Inputs:

- `tenantId`
- `scriptPatchVersion`

Outputs:

- `tenantId`, `scriptPatchVersion`
- `designStatus` (`PUBLISHED`, `PUBLISH_FAILED_DESIGN`, `SUPERSEDED_DESIGN`)
- `baseVersionId`
- `abilitySchemaDigest`
- `publishedAt` (nullable; required when `designStatus=PUBLISHED`)
- `supersededByScriptPatchVersion` (nullable; required when `designStatus=SUPERSEDED_DESIGN`)
- `statusReason` (optional; required for deterministic design-time failures)

Contract rules:

- `designStatus=PUBLISHED` means Game Design accepted and recorded the immutable script-patch artifact for the referenced `baseVersionId`; it does not imply tenant runtime readiness.
- Runtime readiness remains the responsibility of Automation & Scripting via `GetScriptPatchStatus`; callers must not infer `READY` from Game Design publication alone.
- If Game Design rejects the publish attempt (`PUBLISH_FAILED_DESIGN`), Automation & Scripting must not create or expose a tenant lifecycle row for that patch version.

#### `GetPublishedPluginVersion`

Inputs:

- `tenantId`
- `pluginId`
- `pluginVersionId`

Outputs:

- `tenantId`, `pluginId`, `pluginVersionId`
- `designStatus` (`DRAFT`, `UPLOAD_REJECTED`, `SIGNATURE_VERIFIED`, `VALIDATION_FAILED_DESIGN`, `PUBLISHED`, `SUPERSEDED`, `REVOKED_DESIGN`)
- `baseVersionId`
- `abilitySchemaDigest`
- `bundleDigest`
- Target: `verifiedSignatures[]`, the complete canonically ordered verified signature set bound to `bundleDigest`; each entry carries `signerKeyId`, `ed25519Signature`, and optional `signatureCreatedAt`. The signature-set contract is owned by [In-Game Modding and Plugin Framework](microservices/game-design-service/modding-framework.md#signing-and-key-lifecycle-required).
- Current live response: singular `signerKeyId` selected by the current allowlisted-signer intake; it is not proof of full-set target convergence.
- `distributionManifestHash` (nullable; required when the signed plugin manifest declares runtime-consumable `assetRefs[]`)
- `distributionManifestPath` (nullable; required when the signed plugin manifest declares runtime-consumable `assetRefs[]`)
- `publishedAt` (nullable; required when `designStatus=PUBLISHED`)
- `statusReason` (optional; required for deterministic design-time failures or revocation)

Contract rules:

- `designStatus=PUBLISHED` means the immutable plugin bundle is signed, validated, and eligible for runtime activation. It does not imply that any instance has activated it.
- Runtime activation and drain/disable state remain the responsibility of Automation & Scripting via `GetPluginStatus`; callers must not infer `ENABLED` or `DISABLED` from Game Design publication state.
- A plugin version that is not `PUBLISHED` must be rejected by runtime activation APIs with deterministic application errors rather than being partially loaded and then downgraded later.
- A displaced exact `(scriptPatchVersion, scriptPinEpoch)` tuple belongs on instance-scoped plugin cancellation or purge APIs, not on this design-time publication read.
- `distributionManifestHash` and `distributionManifestPath` describe the plugin-version-scoped asset distribution manifest owned by Game Design. They must not point into or mutate the base version's `published_release_bundle`.

#### `ListPluginVersionStatuses`

Implementation note: the current Game Design proto/service path now exposes this broader publication listing read over the same immutable plugin publication rows used by `GetPublishedPluginVersion`, with optional filtering by `pluginId`, `publicationState`, `changedAfterMs`, and `changedBeforeMs`.

Inputs:

- `tenantId`
- Optional `pluginId`
- Optional `publicationState`
- Optional `changedAfterMs` / `changedBeforeMs`
- Optional bounded `limit`

Outputs:

- ordered `PublishedPluginVersion` rows containing `tenantId`, `pluginId`, `pluginVersionId`, `publicationId`, `baseVersionId`, `publicationState`, `abilitySchemaDigest`, `bundleDigest`, distribution-manifest metadata, and `lastChangedAtMs`; target rows expose the same complete `verifiedSignatures[]` evidence as `GetPublishedPluginVersion`, while the current live rows expose only the singular selected `signerKeyId`

Contract rules:

- This read remains design-time publication truth only. Tooling that needs runtime activation or drain state must join it with Automation & Scripting reads such as `GetPluginStatus` and `ListPluginRuntimeEvents`.
- Ordering is newest-first by publication change time so operator tooling can poll recent design-time publication changes without reconstructing chronology from runtime rows.
- This API must not collapse design-time publication and instance runtime activation into one synthetic lifecycle enum.

### Game Session: Patch Pinning

#### `GetPinnedScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `pinnedScriptPatchVersion` and `scriptPinEpoch` as a nullable pair (both present for a pin; both absent for semantic `UNPINNED`, never a sentinel; the epoch advances on every pin selection change, including repin to the same version)
- `pinnedAt` (timestamp; nullable/inapplicable when the instance has never been pinned and is semantically `UNPINNED`)
- `pinnedBy` (actor principal, nullable/inapplicable when the instance has never been pinned and is semantically `UNPINNED`)
- `controlPlaneRequestId` (nullable; the idempotent request that last changed the pin, absent before the first pin)

#### `GetGameSessionPinConvergence`

Implementation note: the current Game Session implementation now exposes this convergence read directly from the persisted game-instance pin record. The live service returns the observed pinned patch, observational `lastObservedControlPlaneRequestId`, and observed timestamp instead of leaving convergence identity implicit in actor/reason text; the current proto/implementation does not yet expose `observedScriptPinEpoch`, which remains required by the target exact-tuple contract below. Until that field exists, exact pinned-tuple convergence is unavailable and a missing epoch never matches a pinned target tuple; semantic `UNPINNED` is represented separately by both tuple fields being absent, while a partial tuple is invalid owner state, cannot satisfy `EXPECT_UNPINNED` or `EXPECT_EPOCH`, and is rejected/fails closed even for an `UNCONDITIONAL` request.

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` and `observedScriptPinEpoch` as the observed nullable exact Game Session pair (both present for a pin; both absent for semantic `UNPINNED`, never a sentinel)
- `lastObservedControlPlaneRequestId` (nullable; absent for a never-pinned `UNPINNED` observation; otherwise the committed pin mutation request represented by this authoritative Game Session read)
- `observedAt`

Contract rules:

- This is the canonical Game Session-side convergence read for rollback/promotion orchestration.
- The response must be derived from the same persisted pin mutation that `SetPinnedScriptPatchVersion` / `RollbackScriptPatchVersion` commit, not reconstructed from logs or operator events.

#### `SetPinnedScriptPatchVersion`

Both `SetPinnedScriptPatchVersion` and `RollbackScriptPatchVersion` require an explicit tagged `expectedCurrentPin` precondition. The tag is exactly `UNCONDITIONAL`, `EXPECT_UNPINNED`, or `EXPECT_EPOCH(scriptPinEpoch)`; a missing or unknown tag, or a missing epoch value for `EXPECT_EPOCH`, fails validation. `UNCONDITIONAL` performs no current-pin comparison, `EXPECT_UNPINNED` atomically requires both current tuple fields to be absent, and `EXPECT_EPOCH(scriptPinEpoch)` requires the exact current epoch. The complete tag/value is included in the normalized request digest. A valid precondition mismatch is a deterministic, non-mutating validation/preparation failure and follows the existing unsuccessful history and idempotency rules; it does not introduce a new result family.

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `expectedCurrentPin` (required tagged compare-and-set precondition; the committed result always returns the resulting epoch)
- `controlPlaneRequestId` (idempotency key)
- `actor` (operator identity metadata, required for audit)
- `reason` (free-form, required)

Semantics:

- Idempotent: repeating the same request with the same `controlPlaneRequestId` must return the same result without reapplying.
- The operation must validate that `targetScriptPatchVersion` is `READY` for the tenant before pinning.
- If the target patch is not `READY`, the operation must fail deterministically with an application error (for example `errorCode=SCRIPT_PATCH_NOT_READY`) and must not mutate pin state.
- The operation must also validate base-version cohesion: the target patch's `baseVersionId` must match the game instance's currently pinned `runtimeVersionId`. If they do not match, the operation must fail deterministically with `errorCode=SCRIPT_PATCH_BASE_VERSION_MISMATCH` and must not mutate pin state.
- Once a syntactically valid request is accepted and its `controlPlaneRequestId` is bound to the normalized request digest, any deterministic validation or preparation failure returns and stores one unsuccessful immutable Game Session history result with identical previous/resulting exact tuples and no epoch advance. An exact retry of that request returns the stored result without another history entry; a different normalized digest under the same request ID is an idempotency conflict.
- On success, Game Session persists the new pin for `(tenantId, gameInstanceId)` and emits `ScriptPatchPinChanged`.
- On success, Game Session atomically persists `(pinnedScriptPatchVersion, scriptPinEpoch)` and the corresponding append-only rollout-history record. The resulting epoch is new even when the target version equals the previous version.
- When the target equals the currently pinned patch, this general pin mutation is the intentional same-version epoch-only repin and is classified as `REPIN` in the resulting history/event.

Outputs:

- `previousScriptPatchVersion` (nullable)
- `previousScriptPinEpoch` (nullable; the two previous fields are all-present or all-absent)
- `pinnedScriptPatchVersion` (nullable resulting tuple member; present together with `scriptPinEpoch` or absent together for semantic `UNPINNED`; on deterministic failure the resulting tuple equals the previous tuple)
- `scriptPinEpoch` (nullable resulting exact authority epoch, paired with `pinnedScriptPatchVersion`; deterministic failure does not advance it)
- `controlPlaneRequestId`
- `errorCode` (optional on failure; required for deterministic business failures such as `SCRIPT_PATCH_NOT_READY`)

#### `RollbackScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion` (previous known-good patch)
- `expectedCurrentPin` (required tagged compare-and-set precondition)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Equivalent to `SetPinnedScriptPatchVersion` but semantically indicates rollback; tooling may treat it as higher urgency. It is an explicit repin to a previously published, tenant-`READY`, base-compatible immutable patch and advances `scriptPinEpoch`; operational sequencing and convergence checks live in [Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).
- This operation must reject a target equal to the currently pinned script patch. An intentional same-version epoch-only repin uses `SetPinnedScriptPatchVersion` and is classified as `REPIN`.
- The accepted-request failure-history rule from `SetPinnedScriptPatchVersion` applies equally here: after the normalized digest is bound, deterministic validation or preparation failure stores one unsuccessful immutable history result with identical previous/resulting exact tuples and no epoch advance; exact same-ID retries return it and a different digest conflicts.
- Target patch readiness requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must be `READY` for the tenant or the request fails with a deterministic application error (`SCRIPT_PATCH_NOT_READY`).
- Base-version cohesion requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must have `baseVersionId` equal to the instance `runtimeVersionId` or the request fails with `SCRIPT_PATCH_BASE_VERSION_MISMATCH`.
- On success, emits only `ScriptPatchPinChanged` with `changeType=ROLLBACK`; the reserved `ScriptPatchRollbackRequested` family is neither emitted nor consumed.

Outputs: same as `SetPinnedScriptPatchVersion`.

### Automation & Scripting: Patch Lifecycle Visibility

#### `GetScriptPatchStatus`

Implementation note: the current Automation & Scripting API exposes these reads from durable `script_work_items` and now enriches them with Game Design publication metadata. The live response includes the current runtime-readiness summary plus the published script patch `baseVersionId` and the current Automation participant `abilitySchemaDigest` derived from the published release bundle for that base version. `supersededByScriptPatchVersion` still remains target-state follow-through rather than a shipped field.

Inputs:

- `tenantId`
- `scriptPatchVersion`

Outputs:

- `tenantId`, `scriptPatchVersion`
- `status` (for example `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, `SUPERSEDED`)
- `statusReason` (optional)
- `baseVersionId` (required)
- `abilitySchemaDigest` (required for compatibility/audit surfaces)
- `lastChangedAt`

Boundary rule:

- This API reports tenant runtime readiness only. Operator UIs that need to explain "published but not ready" must join this read with `GetPublishedScriptPatchVersion` instead of inventing a fused status enum.

#### `ListScriptPatchStatuses`

Inputs:

- `tenantId`
- Optional filters: `status`, `changedAfter`, `changedBefore`

Outputs:

- A list of `GetScriptPatchStatus` records, including `baseVersionId` and `abilitySchemaDigest`.

#### `GetAutomationDrainStatus`

Implementation note: the current Automation & Scripting implementation now persists a scope-local `automation_admission_states` record keyed by `(tenantId, gameInstanceId, regionId)`, exposes `SetAutomationAdmissionMode`, stamps admitted `script_work_items` with the current `admissionEpoch`, and serves `GetAutomationDrainStatus` from that durable admission state plus durable work-item truth. While paused for rollback, drain counts are scoped to pre-pause work (`workItem.admissionEpoch < current admissionEpoch`) rather than all work items in the scope.

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional narrower scope: `regionId`

Outputs:

- `tenantId`, `gameInstanceId`
- Optional `regionId`
- `admissionMode`
- `admissionEpoch`
- `activeExecutionCount`
- `oldestActiveExecutionStartedAt` (nullable/zero when no active work exists)
- `pendingCancelableWorkItemCount`
- `observedAt`

Contract rules:

- This is a read-only operator surface for rollback/promotion drain checks; it must not mutate work-item state.
- The live response is backed by durable Automation-owned admission mode/epoch state plus durable work-item truth already owned by Automation & Scripting.
- This read is diagnostic cleanup progress only. Its counts do not gate Automation resumption or ordinary gameplay ticks; exact target-artifact convergence and schedule reconciliation are the Automation admission gates. Cleanup remains asynchronous and may be bounded pending after the workflow reaches `COMPLETED`, subject to the displaced `(scriptPatchVersion, scriptPinEpoch)` fence.

#### `ListScriptPatchInstanceRolloutEvents`

Inputs:

- `tenantId`
- Optional `gameInstanceId`
- Optional `scriptPatchVersion`
- Optional `rolloutStatus`
- Optional `changedAfter` / `changedBefore`
- Optional bounded `limit`
- Optional opaque `pageToken` bound to the tenant and normalized filters

Outputs:

- ordered authoritative Game Session history rows containing `eventId`, `tenantId`, `gameInstanceId`, `operationKind` (`SET` | `ROLLBACK` | `REPIN`), nullable previous tuple (`previousScriptPatchVersion`, `previousScriptPinEpoch`), nullable resulting tuple (`scriptPatchVersion`, `scriptPinEpoch`), nullable `rolloutStatus`, `controlPlaneRequestId`, `actor`, `reason`, `outcome`, `committedAt`, and bounded pagination metadata. Each tuple is all-present or all-absent; both absent is semantic `UNPINNED`, never a sentinel.
- `nextPageToken` (opaque continuation token; absent when there are no more rows).
- Rows are ordered deterministically by `committedAt` descending (newest first), then by unique `eventId` ascending. The page token resumes this order without requiring an unbounded history read.

Contract rules:

- This is the bounded history read from the same Game Session owner that commits the current exact pin and epoch. A successful pin, rollback, or repin appends one immutable record atomically with the pin mutation; a successful first pin records absent previous -> present resulting values. A deterministic first-pin failure records absent previous -> absent resulting values; an idempotent request retry returns the existing result without another logical history entry. Successful rows map `operationKind=SET` to `rolloutStatus=PINNED`, `ROLLBACK` to `ROLLED_BACK`, and `REPIN` to `REPINNED`.
- A deterministic failed request history row has nullable `rolloutStatus`; its failure is represented by `outcome` and `reason` and it is never classified as `PINNED`, `ROLLED_BACK`, or `REPINNED`; the attempted `operationKind` remains retained on the immutable history row.
- Automation's observed-pin and convergence projections are not rollout-history authority. Logging & Admin composes this read with readiness/freshness state and presents projection lag rather than selecting a competing history.

#### `ListScriptHandoffEvents`

Inputs:

- `tenantId`
- Optional `gameInstanceId`
- Optional `scriptPatchVersion`
- Optional exact source pin filter (`scriptPinEpoch`)
- Optional source runtime scope filters (`playableStateScope`, `regionId`, `regionEpoch`)
- Optional `workItemId`
- Optional `handoffOutcome`
- Optional target runtime scope filters (`targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, `targetRegionEpoch`)
- Optional durable remote-id filters (`remoteCoordinatorId`, `remoteFollowupId`)
- Optional origin identity filters (`scriptId`, `pluginId`, `automationDispatchId`)
- Optional `changedAfter` / `changedBefore`
- Optional bounded `limit`

Outputs:

- ordered event rows containing `eventId`, `tenantId`, source runtime scope (`gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`), exact source pin `scriptPatchVersion`/`scriptPinEpoch`, `scriptId`, optional plugin identity and target-state `pluginActivationGeneration`, `workItemId`, `commandOrdinal`, `automationDispatchId`, target-state `handoffRequirement`, optional `gameSessionCommandId`, distinct target runtime scope when applicable (`targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, `targetRegionEpoch`), optional remote follow-up ids (`remoteCoordinatorId`, `remoteFollowupId`), current owned target runtime scope (`currentTargetRuntimeGameInstanceId`, `currentTargetRuntimeRegionId`, `currentTargetRuntimeRegionEpoch`) plus the current owned routing bundle (`currentTargetRuntimePlayableStateScope`, `currentTargetRuntimeWorldSlug`, `currentTargetRuntimeRealmSlug`, `currentTargetRuntimePointerVersion`) and stale-scope/routing signaling, later Game Session gameplay-command execution truth (`gameplayCommandExecutionOutcome`, `gameplayCommandGameplayResult`, failure details, and remote-state tail), `targetEntityId`, rendered `emittedCommandText`, `handoffOutcome`, `handoffReason`, and `observedAt`

Contract rules:

- This is the per-command observability companion to work-item-level audit and dead-letter reads. Multi-command work items must not collapse handoff chronology into one row.
- Automation must persist one durable handoff event per attempted emitted command, including pre-handoff rollback fencing and Game Session acceptance/rejection outcomes.
- `automationDispatchId` is the canonical low-cardinality correlation key between Automation handoff history and the Game Session gameplay-command ledger; metrics still must not label by it. Operator/debug reads can resolve the Game Session side either from the returned `gameSessionCommandId` or from the full automation identity tuple `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` when the command id is not yet known to the caller.
- Operators use this read to answer which emitted command ordinal reached Game Session, which rendered command text, target entity, and target runtime scope it addressed, whether it stayed local or became a durable remote follow-up, and whether the failure happened before handoff, at Game Session admission, or after later gameplay-side execution disposition.
- Because remote follow-up legs are now durable first-class runtime rows, this read must support direct filtering by target runtime scope, remote coordinator/follow-up ids, and origin script/plugin/dispatch identity rather than assuming one bulk history scan plus client-side correlation.
- Source `playableStateScope`, `regionId`, `regionEpoch`, and exact `scriptPinEpoch` are the persisted source Trigger/Command-Handoff Identity fields. `targetPlayableStateScope` and the other `target*` scope fields are distinct optional target-state fields used only when the handoff targets a different runtime scope; they must not be inferred from source scope. These complete fields are target-state only: the current live handoff proto, storage, and read remain narrower and do not establish live support for this contract.
- The read must also expose the current owned target runtime scope and routing bundle from Game Session when the target instance still exists, so operators can see directly whether the persisted target runtime scope or admitted routing bundle has gone stale without a separate ownership-status lookup.
- When `gameSessionCommandId` is known, the read should also expose the later Game Session gameplay-command execution outcome instead of stopping at handoff-time admission, so operator diagnostics can stay on one handoff-history surface through local and remote gameplay execution tails.

#### `CancelPendingWorkItemsForPluginVersion`

Inputs:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- `scriptPatchVersion` (the displaced script patch version carried by the plugin-produced work)
- `scriptPinEpoch` (the displaced pin epoch; cancellation must match the stored exact tuple, and a same-version repin with a newer epoch is not eligible)
- `gameInstanceId` (required; plugin cancellation is instance-scoped)
- Optional `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Outputs:

- `canceledCount`

Contract rules:

- This is the plugin-version companion to `CancelPendingWorkItemsForPatch`.
- **Target-state cancellation semantics:** the request is scoped to the supplied `gameInstanceId`; `tenantId` authorizes and audits the request but does not apply one epoch tenant-wide. It selects only rows whose stored `(scriptPatchVersion, scriptPinEpoch)` exactly matches the request's displaced tuple; a same-version repin with a newer epoch is not eligible. `PENDING_EVALUATION` is compare-and-set to `CANCELED` without DSL evaluation. An `EXECUTING` row is fenced and its descriptor-commit marker inspected; if committed, cancellation never resumes or re-dispatches a committed child: `PENDING` and `INDEXED` children compare-and-set to `CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`; `HANDOFF_IN_FLIGHT` fences further retry and reconciles the durable downstream outcome (remaining active/unresolved when ambiguous); and `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` children retain their outcome and no-op. An uncommitted `EXECUTING` row is explicitly canceled with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and bounded cancellation metadata, and is never replay-eligible. Only the distinct expired-stale recovery-owner path may use the `DEAD_LETTERED`/`stale_execution_fenced` mapping; this cancellation request does not dead-letter stale displaced rows. The current live surface remains limited to pre-evaluation/non-handoff rows until descriptor persistence and downstream reconciliation are implemented.

#### `GetAutomationPinConvergence`

Implementation note: the current Automation & Scripting implementation now persists a durable `script_patch_pin_projections` view keyed by `(tenantId, gameInstanceId)`. Automation refreshes that projection opportunistically from the same shared Game Session runtime-state surface already used by admission and replay checks, then serves `GetAutomationPinConvergence` from the persisted projection so freshness and temporary Game Session read failures do not force operator reads to be raw pass-through calls. The current proto/implementation does not yet expose `observedScriptPinEpoch`; the target output below requires it for exact-tuple convergence. Projection stale flags use the `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` runtime knob.

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` and `observedScriptPinEpoch` (nullable exact pair; both present or both absent for semantic `UNPINNED`, never a sentinel or partial projection)
- `lastObservedControlPlaneRequestId` (nullable; absent when the observed pair is semantic `UNPINNED`; when present, atomically associated with that exact observed pair and retained when the projection is stale)
- `observedAt`
- `projectionAsOfMs`
- `projectionLagMs`
- `isProjectionStale`

Contract rules:

- This is a read-only operator surface for the latest pin observation currently visible to Automation-side admission and replay logic.
- The live implementation is a durable Automation-owned projection refreshed from authoritative Game Session runtime state, not a raw pass-through query.
- `ScriptPatchPinChanged` delivery is a refresh/invalidation hint only; after missed, duplicate, or out-of-order delivery, Automation reconciles or rebuilds its projection from authoritative Game Session reads. Event payloads never become pin-state or rollout-history authority.
- This projection is explicitly non-authoritative and must not be used to admit work unless it is fresh enough and matches the exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple together with its associated owner request identity. No stale/local pin override exists; until the observed epoch field exists on the current surface, exact-tuple convergence is unavailable and a missing epoch never matches.
- When Game Session runtime state reports multiple current admission pointers for one runtime target, Automation must treat the singular runtime-state routing bundle as unavailable and fail closed for any consumer that needs one unambiguous `{worldSlug, realmSlug, pointerVersion}` identity.
- If refresh from Game Session fails but Automation still has a stored observation, the API must continue returning that stored observation, including its associated pair and request identity, with freshness flags set from the projection timestamp instead of failing closed for operator visibility.

#### `ListScriptScheduleInstances`

Implementation note: the current Automation & Scripting implementation now exposes the first durable instance-scoped timer materialization read from `script_schedule_instances`. Those rows are refreshed from the same observed Game Session pin state used by admission and rollout reads, and they project the currently pinned patch's durable schedule definitions into one `(tenantId, gameInstanceId)` scope. Materialization is now per matching event binding rather than per raw script definition only, so each row carries target-scope identity and binding priority alongside schedule definition identity. Wall-clock timers currently compute `nextDueAt`, which maps to target durable `dueAt` plus projected `nextRunAt`, not to independent deadlines; tick-aligned schedules are persisted explicitly as `PENDING_RUNTIME_PROGRESS` until heartbeat-driven `nextTick` materialization lands.

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional filters: `scriptPatchVersion`, `scriptPinEpoch`
- `limit` (bounded by the service)

Outputs:

- Instance-scoped schedule entries containing the exact `scriptPatchVersion` and `scriptPinEpoch`, `scriptId`, plugin owner metadata, resolved `playableStateScope`, `scheduleDefinitionId`, event type, cadence, scheduler priority tag, target-scope identity (`targetScopeType`, `targetScopeId`), binding priority/exclusivity flags, materialization status, due-point fields, observed runtime version id, the pin operation's `controlPlaneRequestId`, pin observation time, row timestamps, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted scheduler row scope.

Contract rules:

- This is a read-only operator/debugging surface for the first durable scheduler substrate below Redis timer indexes.
- The live implementation must report tick-aligned schedules honestly as not-yet-advanced when no heartbeat-derived due point exists; it must not invent synthetic tick coordinates.
- Reconciliation across repins is keyed by stable `scheduleDefinitionId` plus plugin owner metadata and binding target identity, not by inferred semantic similarity.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so operators can tell directly whether persisted scheduler scope has gone stale without a second runtime-state lookup.

#### `ListScriptTimerAuditEvents`

Implementation note: the current Automation & Scripting implementation now exposes the scheduler-owned subset of `script_event_audit` directly for timer troubleshooting. This read is bounded to `sourceKind=SCHEDULE_TIMER` and includes both due-point admissions that persisted work items and scheduler-owned dropped candidates such as `catch_up_truncated` and `runtime_scope_changed`, so operators no longer have to infer timer truncation/fence behavior from aggregate metrics alone.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`, `scriptPinEpoch`, `scriptId`, `eventType`, `finalReason`
- Optional `changedAfter` / `changedBefore`
- `limit` (bounded by the service)

Outputs:

- newest-first timer audit rows containing Trigger Identity fields including the exact `scriptPatchVersion` and `scriptPinEpoch`, resolved `playableStateScope`, admitted routing bundle, plugin owner metadata, trigger mode, scheduler source state/ordinal/due-point fields, optional `workItemId`, final stage/outcome/reason, row timestamps, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted timer row scope

Contract rules:

- This is a read-only operator/debugging surface for scheduler-owned timer decisions; it must not mutate work-item or schedule state.
- The live implementation is sourced from durable `script_event_audit` rows, not reconstructed from metrics or volatile queue indexes.
- Timer-fired work that reached durable work-item persistence and timer-fired work intentionally dropped by scheduler fences/truncation share this history surface so operators can correlate a due point without joining multiple ad hoc tables first.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so operators can distinguish stale timer history from current-timeline timer activity without a second runtime-state lookup.

#### `ListScriptDeadLetters`

Implementation note: the current Automation & Scripting API exposes this read directly from durable `script_work_items` rows with `status=DEAD_LETTERED`. It is an operator inspection surface separate from the controlled replay mutation API. The current proto/service readback does not expose `failureGeneration`; that field is target-state only and remains an implementation/proof gap.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`, `scriptPinEpoch`
- `limit` (bounded by the service)

Outputs:

- **Target-state output:** newest-first dead-letter entries contain `workItemId`, the Automation-owned `failureGeneration`, the exact stored `scriptPatchVersion` and `scriptPinEpoch`, Trigger Identity fields, resolved `playableStateScope`, script/event identity, `status`, bounded failure/cancel reason, `createdAt`, `updatedAt`, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted dead-letter row scope.

Boundary rule:

- Operators use this read to decide whether a replay or manual remediation workflow is needed; replay itself remains a separate controlled operation so listing dead letters cannot accidentally mutate runtime state.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so stale timeline dead letters are visible directly on the dead-letter row instead of only via manual runtime-state correlation.

#### `ReplayDeadLetteredWorkItems`

Implementation note: the current Automation & Scripting implementation exposes a bounded parent-row replay mutation, still accepts optional `gameInstanceId`/`regionId` scope fields and empty-ID selection, and returns aggregate counts rather than target per-item `failureGeneration`, `outcome`, and `rejectionReason` results. It does not yet prove the target stage-aware recovery contract. The target API resumes from immutable failure-stage evidence: evaluation-stage rows may retry with their frozen manifest/graph and original identity; post-evaluation rows resume the stored output/child ledger without DSL re-entry. A post-evaluation parent retains its `EVALUATED_COMMITTED` descriptor marker while its separate recovery aggregate may be `DEAD_LETTERED` after a permanent required-child failure, making the parent selector eligible for `resumed_dispatch`; the marker and ledger remain the recovery input. Missing or contradictory evidence remains dead-lettered.

Inputs:

- `tenantId`
- Bounded, nonempty, unique `workItemIds[]` only (durable parent work-item identifiers); duplicate IDs fail deterministic request validation before request fingerprinting or claim acquisition. Descriptor references and filters are listing/preview inputs, not mutation selectors. Bulk filter replay remains deferred until preview plus stable per-row proof.
- `controlPlaneRequestId`
- `actor`
- `reason`

Target-state outputs:

- `results[]` (one deterministic result per requested `workItemId`, including `workItemId`, `outcome`, optional/nullable `recoveryStage`, `rejectionReason` only when rejected, and `failureReason` only when recovery failed; `recoveryStage` is `null` when the result is `not_found_or_not_owned` or `stage_evidence_unavailable` because no trustworthy stage is available)
  - `failureGeneration` is the resolved generation for the selected parent, or the generation stored with an exact retry result; it is absent only when no owned generation can be resolved.
  - `outcome` is exactly one of `retried_evaluation`, `resumed_dispatch`, `already_recovered`, `recovery_failed`, or `rejected`.
  - `rejectionReason` is present only with `outcome=rejected` and uses established bounded values such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `work_item_not_dead_lettered`, `recovery_in_progress`, `script_pin_epoch_mismatch`, `plugin_binding_mismatch`, `plugin_activation_generation_mismatch`, `runtime_scope_mismatch`, `plugin_disabled`, `plugin_version_not_published`, `plugin_component_policy_blocked`, `component_policy_unavailable`, `signer_policy_unavailable`, `signer_revoked`, or `authority_unavailable`.
  - `failureReason` is required only with `outcome=recovery_failed`, absent for every other outcome, and reuses the established stage-aware failure-reason vocabulary for the terminal failed recovery attempt rather than introducing a parallel taxonomy.
- `replayedCount` (bounded count of selected work items that progressed)
- `rejectedCount`

Contract rules:

- Replay is fail-closed per work item. Eligibility requires exact current `(scriptPatchVersion, scriptPinEpoch)`, applicable plugin identity/version/binding and captured `pluginActivationGeneration`, region/`regionEpoch`, and routing-bundle match to the immutable admitted evidence. For plugin work, the current scoped plugin activation/lifecycle, component-policy decision, capability-grant evidence, and signer/publication evidence must also be fresh and valid.
- Recovery atomically resolves the current `failureGeneration` and compares-and-sets the current parent recovery aggregate `status=DEAD_LETTERED` together with a persisted recovery claim/attempt record containing `(tenantId, outboxWorkItemId, failureGeneration)`, the expected exact `(scriptPatchVersion, scriptPinEpoch)`, runtime scope, applicable plugin binding tuple and captured `pluginActivationGeneration`, and `controlPlaneRequestId`, before evaluation or dispatch. A post-evaluation parent may retain its `EVALUATED_COMMITTED` descriptor marker and complete output/child ledger while this separate recovery aggregate is `DEAD_LETTERED`; that is the eligible `resumed_dispatch` case. A selected row in any other aggregate status remains unchanged and returns `outcome=rejected` with `rejectionReason=work_item_not_dead_lettered`. If a different `controlPlaneRequestId` targets a row with an active `IN_PROGRESS` claim for that generation, its stored deterministic request result is `outcome=rejected` with `rejectionReason=recovery_in_progress` and it creates no new claim/attempt, evaluation, or dispatch. If a created claim/attempt reaches terminal `FAILED`, its stored request result is `outcome=recovery_failed` with required bounded `failureReason` and no `rejectionReason`; an exact retry returns that stored result and reason without a new attempt. An expired claim is reclaimed under the same attempt record and identity (with a new owner fence when needed), never by inserting a duplicate recovery attempt or audit record; lease expiry alone does not allow a different request to create an attempt. A later new request may create one new attempt only after the prior attempt is terminal `FAILED` and fresh eligibility/fence checks pass. A prior successful generation remains immutable; it is not `already_recovered` evidence for a newer current generation.
- Evaluation-stage recovery uses the frozen original trigger/input manifest and exact graph, preserving the original work-item and `scriptEventId` identity without a new dispatch identity; it may invoke the DSL evaluator again for eligible gameplay/runtime work outside normal admission. Tenant-readiness `onLoad` remains at-most-once and is excluded from evaluation replay. Post-evaluation recovery uses only the immutable evaluated output and complete child-dispatch ledger keyed by the full Command-Handoff Identity, preserving original child identities and per-child recovery state without invoking the DSL. `HANDED_OFF` (accepted), `CANCELED`, and `DEAD_LETTERED` children are terminal and no-op. An already `HANDOFF_IN_FLIGHT` child is fenced and reconciled against the durable downstream outcome, never blindly redispatched; if that outcome is ambiguous, the child remains active/unresolved. Immediately before evaluation, and immediately before each post-evaluation dispatch, it revalidates the recorded exact fence, binding, and captured plugin generation plus the current scoped plugin activation/lifecycle, component-policy, capability-grant, and signer/publication evidence. `DRAINING` blocks new trigger admission but does not invalidate already-admitted recovery; only a lifecycle transition that invalidates admitted work, such as `DISABLED`, revocation, or policy-driven disablement, uses `plugin_disabled`. Only after that dispatch-time revalidation succeeds may a `PENDING` or `INDEXED` child transition by durable compare-and-set to `HANDOFF_IN_FLIGHT` under the existing recovery claim/attempt fence and dispatch. Missing, stale, or temporarily unavailable evidence fails closed with the applicable established bounded reason (`authority_unavailable`, `component_policy_unavailable`, or `signer_policy_unavailable`); proven publication, component/capability, signer, tuple, generation, or scope mismatches use `plugin_version_not_published`, `plugin_component_policy_blocked`, `signer_revoked`, `plugin_binding_mismatch`, `plugin_activation_generation_mismatch`, `script_pin_epoch_mismatch`, or `runtime_scope_mismatch` as applicable. It never resolves a latest/local graph, and never regenerates an output after a child was accepted.
- Rows with missing/contradictory stage evidence or any fence mismatch remain `DEAD_LETTERED` and return a deterministic per-row `outcome=rejected` with the applicable bounded `rejectionReason`; a rejected row is not partially changed or counted as successful.
- The request is bounded and idempotent by `controlPlaneRequestId`, actor, reason, and explicit work-item IDs. An exact retry of the same `controlPlaneRequestId` and canonical request fingerprint returns the identical stored per-row outcomes and stored generation, including `recovery_failed` with its stored `failureReason` or `rejected`, without evaluation, dispatch, or relabeling. A new request that finds the current generation already recovered returns `outcome=already_recovered`; older-generation success does not block recovery of the current generation. No new Trigger Identity or Command-Handoff Identity is introduced. Purge is a separate operation and never masquerades as recovery.

#### `GetScriptPatchInstanceRolloutStatus`

Implementation note: the current Automation & Scripting implementation exposes a non-authoritative pin/convergence projection from a durable local `script_patch_instance_rollout_projections` read model rather than a raw shared-runtime query. The current proto/implementation resolves this read by patch version only and does not yet provide exact epoch lookup; the target contract below requires the requested exact `(scriptPatchVersion, scriptPinEpoch)` tuple. Current storage is patch-only `(tenantId, gameInstanceId, scriptPatchVersion)`, and refresh combines observed Game Session pin state with local work-item/current-pin state to derive non-authoritative `PINNED`, `REPINNED`, and `ROLLED_BACK` projection statuses/events. These local rows/events are implementation drift and diagnostics, not rollout-history authority; the target projection must be exact-tuple observed state and must not derive rollout history. Game Session's authoritative append-only history remains the owner for `PINNED`, `ROLLED_BACK`, and `REPINNED` transition history.

Inputs:

- `tenantId`
- `gameInstanceId`
- `scriptPatchVersion`
- `scriptPinEpoch` (required exact epoch for this status lookup)

Outputs:

- `tenantId`, `gameInstanceId`, `scriptPatchVersion`
- `scriptPinEpoch`
- `projectionStatus` (`OBSERVED` or `STALE`; non-historical projection state, never `PINNED`, `ROLLED_BACK`, or `REPINNED`)
- `statusReason` (optional)
- `lastChangedAt`
- `projectionAsOf` (timestamp of projection snapshot used for this read)
- `projectionLagMs` (non-negative projection staleness estimate)
- `isProjectionStale` (boolean; `true` when lag breaches the published freshness SLO; `projectionStatus=STALE` if and only if this is `true`, otherwise `projectionStatus=OBSERVED`)

Read-model ownership:

- The authoritative source for rollout transitions is Game Session pin mutations and committed `ScriptPatchPinChanged` events.
- The current Automation & Scripting implementation persists an Automation-owned patch-only observation keyed by `(tenantId, gameInstanceId, scriptPatchVersion)` and does not yet persist `scriptPinEpoch` on this projection. Its refresh implementation also derives non-authoritative local projection statuses/events from observed Game Session pin state plus durable work-item/current-pin state. These local rows/events are implementation drift and diagnostics, not rollout-history authority; the target projection is keyed by the exact `(scriptPatchVersion, scriptPinEpoch)` tuple and must not synthesize history from work-item transitions.
- These Automation reads are non-authoritative projection reads, not Game Session rollout-history reads. Target reads return only observed projection rows for the requested exact tuple and must not synthesize historical `PINNED`, `ROLLED_BACK`, or `REPINNED` status; current patch-only rows and locally derived events are implementation drift and must not be treated as committed history. When an exact-tuple target projection row is absent, the read returns a deterministic not-found result; use `ListScriptPatchInstanceRolloutEvents` for committed rollout history.

#### `ListScriptPatchInstanceRollouts`

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`, `scriptPinEpoch`, `projectionStatus`, `changedAfter`, `changedBefore`
- `limit` (service-bounded maximum number of rows)
- `pageToken` (opaque continuation token bound to the tenant and normalized filters)

Outputs:

- A list of `GetScriptPatchInstanceRolloutStatus` records.
- `nextPageToken` (opaque continuation token; absent when there are no more rows).
- Rows are ordered deterministically by `lastChangedAt` descending (newest first), then by the exact identity tie-breakers `tenantId`, `gameInstanceId`, `scriptPatchVersion`, and `scriptPinEpoch` ascending. The page token resumes this order without requiring an unbounded tenant read.
- The read model must publish and enforce explicit freshness SLOs:
  - P95 `projectionLagMs <= 5000`
  - P99 `projectionLagMs <= 30000`
- `isProjectionStale` is true when that row's `projectionLagMs` exceeds the per-row `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS`; P95 and P99 remain aggregate monitoring SLOs rather than response-level predicates. A stale row includes a bounded `statusReason` such as `projection_lag_exceeded` so operators can distinguish row-level staleness from a failed rollout.

### Automation & Scripting: Plugin Lifecycle Management

Plugins are controlled by operators via Logging & Admin, but the runtime registry and enforcement live in Automation & Scripting. All mutating plugin operations must be idempotent and scoped to a running instance.

#### `GetPluginStatus`

Implementation note: the current Automation & Scripting implementation persists and serves the runtime registry for `(tenantId, gameInstanceId, pluginId)`, and `SetPluginActiveVersion` now consults the live Game Design `GetPublishedPluginVersion` read surface plus the shared Game Session runtime-state read for runtime version, launch descriptor, version/release identifiers, and script-patch pin metadata before mutating that registry. That means design-time publication eligibility, signer revocation, component-policy decisions, `baseVersionId` compatibility, and `abilitySchemaDigest` compatibility are now enforced in the live control-plane path. The activation path also now re-checks the currently pinned script-patch binding surface for the target instance, validates `COMMAND_ALIAS` bindings against Game Session's authoritative built-in command registry, and rejects instance-scoped binding conflicts before runtime state changes. Enabled plugin runtime states are also rechecked on a bounded scheduled cadence so already-active plugins are disabled if their publication state, signer metadata, or component-policy decision becomes fail-closed after activation; `REPORT_ONLY` policy decisions remain activatable and do not trigger fail-closed reconciliation. Plugin-trigger ingress uses the persisted `lastPolicyCheckedAt` evidence and fails closed with `signer_policy_unavailable` when that check is older than `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS`, and `GetPluginStatus` now exposes both `lastPolicyCheckedAtMs` and `policyCheckStale` so operators can see that freshness directly.

Target state additionally exposes the Automation-owned monotonic `pluginActivationGeneration` for the scoped runtime row and resulting lifecycle events. A newly created runtime row starts at generation `0` while no activation is admitted; the first successful activation/enable atomically establishes generation `1` and emits it. The current runtime/status implementation does not yet persist or expose that generation; same-version re-enable fencing remains an implementation and proof gap.

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`

Outputs:

- `tenantId`, `gameInstanceId`, `pluginId`
- `activePluginVersionId` (nullable)
- `pluginActivationGeneration` (the Automation-owned runtime fence; target state)
- `pendingPluginVersionId` (nullable)
- `pluginState` (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`)
- `statusReason` (optional; required for security/policy-driven disablement such as `signer_revoked`)
- `lastChangedAt`
- `controlPlaneRequestId` (nullable; the last idempotent mutating request that changed this runtime row)
- `actor` (nullable; the last operator/system principal recorded on the runtime row)

Boundary rule:

- This API reports runtime state for one `(tenantId, gameInstanceId, pluginId)` only. It must not be overloaded to synthesize design-time publication status or signer-verification history from Game Design.

#### `ListPluginRuntimeEvents`

Purpose: provide append-only runtime lifecycle history for one tenant's plugin activations, drains, disables, and policy-reconcile fail-closed transitions so tooling does not reconstruct operator history from the latest registry row.

Request fields:

- `tenantId`
- optional `gameInstanceId`
- optional `pluginId`
- optional `pluginState`
- optional `activePluginVersionId`
- optional `changedAfterMs`
- optional `changedBeforeMs`
- optional `limit`

Response fields:

- repeated `events[]` with `eventId`, `tenantId`, `gameInstanceId`, `pluginId`, `previousPluginVersionId`, `activePluginVersionId`, `pluginActivationGeneration`, `pluginState`, `statusReason`, `controlPlaneRequestId`, `actor`, and `observedAtMs`
- `error`

Contract rules:

- This is append-only runtime lifecycle history, not a projection of design-time publication events.
- `SetPluginActiveVersion`, `DisablePlugin`, `DrainPlugin`, and scheduled policy reconciliation must append one event only when they materially change runtime plugin state or the active version.
- Idempotent no-op retries against an already-applied target must not append duplicate events or advance the latest-row `lastChangedAt`.
- An already-applied idempotent request (the requested active version and resulting runtime state already equal the committed state) returns the existing committed state without updating `lastChangedAt`, appending history, emitting the durable event, or triggering projections.
- A newly created runtime row starts at generation `0` while no activation is admitted; the first successful activation/enable atomically establishes generation `1` and emits it. Thereafter, every committed transition advances `pluginActivationGeneration` exactly once if and only if it invalidates current plugin work, including a materially changed activation or binding when that change invalidates admitted work. A material runtime-state change that does not invalidate admitted work, including `DRAINING`, carries the unchanged current generation. Failed operations, no-ops, and exact retries of the same committed control-plane request reuse the stored generation and do not advance it; a later same-version enable or activation has a newer generation.
- Operators that need the current runtime truth still use `GetPluginStatus`; operators that need transition history use this read rather than inferring chronology from row timestamps.

#### `GetPluginPolicyConvergence`

Purpose: provide an operator-visible signer/component-policy convergence read for enabled plugin runtime states so scheduled reconciliation is not an invisible background process.

Request fields:

- `tenantId`
- optional `gameInstanceId`
- optional `maxResults`

Response fields:

- `inspectedCount`
- `failClosedCount`
- `converged`
- `evaluatedAtMs`
- repeated `violations[]` with `gameInstanceId`, `pluginId`, `activePluginVersionId`, `reason`, and `lastChangedAtMs`
- `error`

Current implementation note: Automation evaluates enabled runtime states against current Game Design publication metadata on demand. Reasons match the scheduled reconciler's fail-closed reasons, including `signer_policy_unavailable`, `signer_revoked`, `plugin_component_policy_blocked`, `component_policy_unavailable`, and `plugin_version_not_published`.

#### `SetPluginActiveVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `targetPluginVersionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Validates that the target plugin version is `PUBLISHED` in the Game Design design-time lifecycle before any runtime mutation occurs. Non-published versions must fail deterministically with an application error (for example `PLUGIN_VERSION_NOT_PUBLISHED`).
- Validates that the target bundle is allowed for the environment (signature verified, signer allowed, component policy satisfied).
- Validates runtime-version compatibility before activation:
  - `plugin.baseVersionId` must equal the instance `runtimeVersionId`.
  - `plugin.abilitySchemaDigest` must match the immutable digest recorded for the same base version used by the running instance.
  - Any mismatch fails deterministically with an application error (for example `PLUGIN_BASE_VERSION_MISMATCH` or `PLUGIN_ABILITY_SCHEMA_MISMATCH`) and must not mutate active plugin state.
- Current implementation note: the live control-plane path now enforces `PUBLISHED` design-time state, non-revoked signer metadata, non-blocking component-policy decisions, `plugin.baseVersionId == runtimeVersionId`, `plugin.abilitySchemaDigest` matching the Automation participant digest in the running published release bundle, supported built-in `COMMAND_ALIAS` bindings, and no instance-scoped binding conflicts against the currently pinned script patch plus already-enabled plugins before updating the runtime registry.
- On success, if `targetPluginVersionId` materially displaces the currently active version or a same-version activation/enable advances `pluginActivationGeneration` to re-admit work after invalidation, updates the registry for `(tenantId, gameInstanceId, pluginId)`, reconciles durable plugin-owned schedules/timers against the new version/generation fence before new trigger admission, and emits `PluginVersionRuntimeStateChanged` with the committed runtime state. Old candidates retain their displaced evidence and are not reused under the new fence.
- If the requested active version and resulting runtime state already equal the committed state, returns the existing committed state without updating `lastChangedAt`, appending history, emitting the durable event, triggering projections, or reconciling schedules/timers.
- If the target version is already active but the runtime state changes without invalidating or re-admitting current work, including entry into `DRAINING`, updates the registry and emits `PluginVersionRuntimeStateChanged` without reconciling schedules/timers. A same-version activation/enable after an invalidating state is instead the generation-advancing reconciliation case above.
- A newly created runtime row starts at generation `0` while no activation is admitted; the first successful activation/enable atomically establishes generation `1` and emits it. Thereafter, a committed transition advances `pluginActivationGeneration` exactly once if and only if it invalidates current plugin work, including a materially changed activation or binding when that change invalidates admitted work. A material runtime-state change that does not invalidate admitted work, including `DRAINING`, carries the unchanged current generation; a same-version enable or activation therefore publishes a newer generation when it re-admits work after invalidation. Exact idempotent retries, failed operations, and no-op requests do not mint another generation.

Outputs:

- `previousPluginVersionId` (nullable)
- `activePluginVersionId`
- `pluginActivationGeneration`
- `controlPlaneRequestId`

#### `DisablePlugin`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- When the plugin is not already `DISABLED`, transitions it into a non-admitting state immediately.
- Triggers are rejected at admission with a dedicated outcome (for example `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`.
- The committed disable transition advances `pluginActivationGeneration` exactly once; a repeated disable is an idempotent no-op and does not advance it. Revocation or policy-driven disable follows the same generation rule.
- If the plugin is already `DISABLED`, returns the existing committed state without updating `lastChangedAt`, appending history, emitting the durable event, or triggering projections. Otherwise, emits `PluginVersionRuntimeStateChanged(newState=DISABLED)` after the committed state change.

#### `DrainPlugin`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- When the plugin is not already `DRAINING`, transitions it to `DRAINING` so no new triggers are admitted while previously admitted work is allowed to complete within bounded limits.
- `DRAINING` does not invalidate already-admitted work: recovery and completion remain permitted while exact version, binding, generation, policy, and runtime fences pass. Only new trigger admission is rejected by the draining lifecycle.
- If the plugin is already `DRAINING`, returns the existing committed state without updating `lastChangedAt`, appending history, emitting the durable event, or triggering projections. Otherwise, emits `PluginVersionRuntimeStateChanged(newState=DRAINING)` after the committed state change.

### Automation & Scripting: Event Ingress Admission Contract (Normative)

`TriggerScriptEvent` and equivalent ingress RPCs must return a structured admission result so callers can implement retries without inferring behavior from transport errors.

Required response fields:

- `admitted` (`true` when admitted to pipeline; `false` otherwise)
- `admissionOutcome` (enum)
- `admissionReason` (bounded code/string)
- `retryAfterMs` (optional server hint; required for backpressure outcomes where retry is expected)

Required enum values:

- `TRIGGER_ADMISSION_OUTCOME_ADMITTED`
- `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`
- `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK`
- `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE`
- `TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE`
- `TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED`
- `TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED`
- `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED`
- `TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE`

Contract rules:

- Backpressure outcomes (`*_BACKPRESSURE_*`) must include bounded `retryAfterMs`.
- A terminal rollback convergence timeout uses `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` with `admissionReason=rollback_convergence_timeout` and no `retryAfterMs`; it is fail-closed until repair or repin, not retryable backpressure.
- `admissionOutcome` and `admissionReason` describe the **event-scope ingress decision** only. They must not be interpreted as a summary of all handler-scoped outcomes created after binding resolution.
- Event-scope `admissionOutcome` and `admissionReason` must map directly to the ingress-time admission result recorded in ingress audit/logging surfaces for that request; they are not the same thing as later handler-scoped `finalOutcome` values recorded in `script_event_audit`.
- Handler-scoped denials such as `quota_denied`, `script_disabled`, `plugin_disabled`, and `plugin_component_blocked` remain handler/audit outcomes after binding resolution. They are not valid event-scope ingress `admissionOutcome` values in the general fan-out contract.
- `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED` is reserved for deterministic dry-run/test budget or policy decisions made before handler binding. After binding, a handler capacity denial uses `finalStage=ADMISSION`, `finalOutcome=quota_denied` with its bounded reason.
- If route/lease, worker, dependency, or capacity-policy infrastructure is unavailable and prevents producing an admission result, return canonical non-OK gRPC `RESOURCE_EXHAUSTED` or `UNAVAILABLE`, as applicable; do not encode infrastructure unavailability as `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED`.
- Expected event-scope admission decisions use the typed `admitted`/`admissionOutcome`/`admissionReason` fields. This does not suppress canonical non-OK gRPC status for transport/pre-domain validation or authentication failure, missing preconditions, resource exhaustion, dependency unavailability, deadlines/cancellation, or internal failure; the [gRPC outcome classification](./system-architecture-grpc.md#outcome-and-transport-classification) owns that split.
- Current ingress enforces `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` on the request `payloadJson` input envelope before durable work-item persistence and rejects an oversized envelope with `TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED` / `work_item_size_exceeded`. This event-scope input-envelope check is distinct from target generated-output serialized-size violations: runtime evaluation additionally verifies the artifact-pinned cost metadata/cap digests and incrementally meters command count, per-entity count, serialized bytes, and data-dependent bounds before constructing output; a handler-local generated-output violation is `DSL_EVAL` / `work_item_size_exceeded`, and the atomic output contract leaves no generated output or handoff.
- Current plugin-trigger ingress requires the request `(pluginId, pluginVersionId)` to match Automation's enabled runtime registry state for `(tenantId, gameInstanceId, pluginId)` before handler work is materialized. Missing, disabled, or displaced plugin versions are rejected at ingress with `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` and a bounded reason such as `plugin_not_active`, `plugin_disabled`, or `plugin_version_unavailable`.
- For events that fan out to multiple handlers:
  - `admitted=true` means the request passed ingress-time fences and was accepted for handler resolution.
  - Per-handler Trigger Identities and outcomes are recorded asynchronously in `script_event_audit` (one row per resolved handler).
  - If all handlers later fail individually, the ingress response still remains `admitted=true`; callers do not retry based on those handler-level outcomes.
- Implementations may expose optional informational fields such as `resolvedHandlerCount`, but those fields must not replace per-handler audit records as the source of truth.

## Related Control Plane Contracts

The detailed event and orchestration contracts now live in focused sibling docs:

- [Scripting Control Plane Events](./system-architecture-scripting-control-plane-events.md) defines durable event families, transport/ordering guarantees, and required event payloads.
- [Scripting Control Plane Operations](./system-architecture-scripting-control-plane-operations.md) defines promotion, rollback, pause/resume, drain/purge, dead-letter, convergence, timeout, and degraded-operations workflows.
- [Scripting Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) provides the higher-level operator workflow summary.

## Idempotency, AuthZ, and Audit

- All mutating operations accept `controlPlaneRequestId` and must be safe to retry.
- All mutating operations require operator/admin authorization. Tenant-scoped operator actions must be auditable with actor identity and reason.
- Operator actions must be reflected in audit logs and in durable status events so UIs can reconstruct history.

For runtime trigger audit fields and metrics naming/label rules, see `design/architecture/system-architecture-scripting-observability-contract.md`.
