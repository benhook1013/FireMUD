# FireMUD Scripting & Automation: Control Plane API

This document specifies the direct **control plane API** surface required to operate scripting and automation safely across Game Session, Automation & Scripting, Game Design, and Logging & Admin.

It exists to remove ambiguity from “conceptual APIs” referenced in service READMEs: this is the target-state contract that must be implemented in protos/services over time.

Workflow sequencing for rollback, pause/resume, drain/purge, dead-letter recovery, and operator audit flows lives in [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).

The exact pin/epoch authority and stage-aware recovery rules used by these APIs are defined in [Scripting & Automation: Cross-Service Contracts](./system-architecture-scripting-contracts.md), with accepted transition rationale in [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](./decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), [ADR 0111](./decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md), [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md), and [ADR 0120](./decisions/adr-0120-owner-read-first-control-plane-notifications.md).

## Implementation Status

The API shapes below are target-state contracts. Current Automation exposes bounded readiness, convergence, rollout, schedule, plugin, dead-letter, and replay surfaces, but `GetAutomationPinConvergence` does not yet expose `observedScriptPinEpoch` or `observedConvergenceAttemptGeneration`, instance-rollout lookup remains patch-version-only rather than exact-epoch lookup, and replay remains aggregate parent-row requeue rather than stage-aware per-row recovery. Current `CancelPendingWorkItemsForPluginVersion` accepts only tenant/plugin-version/instance/region plus request/audit fields and returns aggregate `canceledCount`; the activation/script tuples, explicit work-item batch, deterministic per-parent results, and descriptor/child reconciliation below are target-only. A missing current/observed epoch keeps exact-tuple admission/replay fail-closed; a missing current/observed attempt generation separately prevents attempt-bound convergence proof. The current Game Session proto/runtime also lacks the tagged `expectedCurrentPin` field for pin CAS and the target `currentConvergenceAttemptGeneration` read field; those target fields remain implementation and proof gaps. These are implementation and proof gaps, not alternate API semantics; see the [Automation and Scheduler Runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) and [Game Session Runtime and Tick Coordination tracker](../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status). Validation and focused runtime proof selection follows the [validation and runtime proof workflow](../developer-workflows/validation-and-runtime-proof.md).

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
- **Auditable and observable.** Every accepted mutating request that commits a state change must produce its applicable durable audit/history record. Exact retries, no-ops, and already-applied requests reuse existing committed evidence rather than appending duplicate history; failed attempts retain whatever bounded request/audit evidence their owning contract requires. Named durable event families emit only their contracted status events; other consumers recover committed state and chronology from the owning service's durable state/history and advisory wake-ups. Consumers do not infer deterministic failed attempts from success-only change events.
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
  - Tracks per-tenant patch lifecycle state (`READY`, `FAILED`, `SUPERSEDED`) and requires `READY` for a new pin or progression. A newer publish supersedes only an unpinned candidate in `PENDING_VALIDATION` or `ONLOAD_RUNNING`. An already-pinned `READY` patch is not relabeled `SUPERSEDED` merely because a newer publish arrives; it remains admissible only under its exact current Game Session tuple until an explicit Game Session repin or rollback.
  - Emits tenant patch readiness lifecycle events (`ScriptPatchTenantStatusChanged`) when readiness state changes.
  - Consumes Game Session pin events to project non-authoritative observed pin/convergence projections; it never owns or derives rollout history.

- **Game Session Service (gameplay + tick control plane)**
  - Owns the exact pinned `(scriptPatchVersion, scriptPinEpoch)` tuple for each `(tenantId, gameInstanceId)`.
  - Enforces the exact version-and-epoch fence on execution: commands whose patch or epoch does not match the current tuple must not execute, even when the patch version is unchanged.
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
- `pinnedScriptPatchVersion` and `scriptPinEpoch` as a nullable pair (both present for a pin; both absent only for a never-pinned semantic `UNPINNED`, never a sentinel; the epoch advances on every pin selection change, including repin to the same version)
- `pinnedAt` (timestamp; nullable/inapplicable when the instance has never been pinned and is semantically `UNPINNED`)
- `pinnedBy` (actor principal, nullable/inapplicable when the instance has never been pinned and is semantically `UNPINNED`)
- `controlPlaneRequestId` (nullable; the idempotent request that last changed the pin, absent before the first pin)

#### `GetGameSessionPinConvergence`

Implementation note: the current Game Session implementation now exposes this convergence read directly from the persisted game-instance pin record. The live service returns the observed pinned patch, observational `lastObservedControlPlaneRequestId`, and observed timestamp instead of leaving convergence identity implicit in actor/reason text; the current proto/implementation does not yet expose `observedScriptPinEpoch` or `currentConvergenceAttemptGeneration`, which remain required by the target exact-tuple/attempt contract below. Until `observedScriptPinEpoch` exists, exact pinned-tuple admission/replay and convergence are unavailable; a missing `currentConvergenceAttemptGeneration` separately prevents attempt-bound convergence proof even when the exact pin tuple is present. Semantic `UNPINNED` is represented separately by both tuple fields being absent only before the first pin, while a partial tuple is invalid owner state, cannot satisfy `EXPECT_UNPINNED` or `EXPECT_EPOCH`, and is rejected/fails closed even for an `UNCONDITIONAL` request.

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` and `observedScriptPinEpoch` as the observed nullable exact Game Session pair (both present for a pin; both absent only for a never-pinned semantic `UNPINNED`, never a sentinel)
- `currentConvergenceAttemptGeneration` (positive generation for the current pin-transition workflow attempt; absent when no workflow exists)
- `lastObservedControlPlaneRequestId` (nullable; absent only when the instance has never been pinned and the observation is semantic `UNPINNED`; present with every pinned observation as the committed pin-mutation request represented by this authoritative read)
- `observedAt`

Contract rules:

- This is the canonical Game Session-side convergence read for rollback/promotion orchestration.
- The response must be derived from the same persisted pin mutation that `SetPinnedScriptPatchVersion` / `RollbackScriptPatchVersion` commit, not reconstructed from logs or operator events.

#### `GetScriptPinTransitionWorkflowStatus`

Implementation status: target-state, read-only Game Session owner surface. The current proto/runtime does not expose this read, and no implementation or proof is claimed.

Inputs:

- `tenantId`
- `gameInstanceId`
- `controlPlaneRequestId` (exact durable pin-transition workflow selector)

Outputs:

- `tenantId`, `gameInstanceId`, `controlPlaneRequestId`
- `operationKind` (`SET` | `ROLLBACK` | `REPIN`)
- `targetScriptPatchVersion` and `targetScriptPinEpoch` (the exact target tuple bound to the workflow)
- `workflowState` (the current durable pin-transition state)
- `currentConvergenceAttemptGeneration` (the current attempt generation)
- `convergenceDeadlineAt` (nullable before the current attempt enters `CONVERGING`; once assigned, the immutable absolute deadline for that attempt)
- `terminalTimeoutOutcome` (nullable; `PIN_CONVERGENCE_TIMEOUT` only when the current attempt is terminal)
- `terminalTimeoutEvidence` (nullable immutable `{timeoutMs, reason, occurredAt}` captured with the exactly-once timeout transition/event; absent for a non-terminal current attempt)

Contract rules:

- This is an authoritative, read-only Game Session workflow read; it does not mutate, recover, or resume the workflow and is distinct from `GetGameSessionPinConvergence`.
- The exact `(tenantId, gameInstanceId, controlPlaneRequestId)` selector identifies one durable workflow. Missing, incomplete, or ambiguous workflow authority is unavailable and is never interpreted as semantic `UNPINNED`.
- Timeout consumers compare the event's exact target tuple, `operationKind`, `convergenceAttemptGeneration`, and request ID with this current workflow result before acting. A delayed event from a prior generation fails that comparison after explicit recovery has advanced the current generation.

#### `SetPinnedScriptPatchVersion`

Both `SetPinnedScriptPatchVersion` and `RollbackScriptPatchVersion` require an explicit tagged `expectedCurrentPin` precondition. The tag is exactly `UNCONDITIONAL`, `EXPECT_UNPINNED`, or `EXPECT_EPOCH(scriptPinEpoch)`; a missing or unknown tag, or a missing epoch value for `EXPECT_EPOCH`, fails validation. `UNCONDITIONAL` performs no current-pin comparison only on the explicit `platformAdmin` break-glass repair branch; it skips only the current-tuple value comparison and still runs through the same Game Session owner transaction and serialization/conflict fence as every other pin mutation, with the resulting tuple, history, and idempotency state committed atomically so only one exact tuple is authoritative. It still requires the matrix's `privileged_control` authorization and coherent Game Session owner state, readiness, and other validation. `EXPECT_UNPINNED` atomically requires both current tuple fields to be absent, and `EXPECT_EPOCH(scriptPinEpoch)` requires the exact current epoch. The owner semantics are defined in [Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#pin-compare-and-set-preconditions). The complete tag/value and the preparation-attestation reference's presence and identity are included in the normalized request digest, so changing evidence under a reused request ID is an idempotency conflict. A valid precondition mismatch is a deterministic, non-mutating validation/preparation failure and follows the existing unsuccessful history and idempotency rules; it does not introduce a new result family.

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `preparationAttestationRef` (optional; required when candidate preparation is used; immutable Automation & Scripting-owned evidence identity/reference)
- `expectedCurrentPin` (required tagged compare-and-set precondition; the committed result always returns the resulting epoch)
- `controlPlaneRequestId` (idempotency key)
- `actor` (operator identity metadata, required for audit)
- `reason` (free-form, required)

Semantics:

- Idempotent: repeating the same request with the same `controlPlaneRequestId` must return the same result without reapplying.
- When candidate preparation is used, `preparationAttestationRef` resolves immutable Automation & Scripting-owned evidence bound to the exact `(tenantId, gameInstanceId, controlPlaneRequestId, targetScriptPatchVersion, tenant-READY artifact digest, readiness revision, applicable policy revisions)`. Game Session rejects missing, mismatched, or stale supplied evidence and revalidates at commit that the tenant remains `READY`, the exact artifact digest is current, and the readiness and applicable policy revisions still match authoritative current values.
- When `preparationAttestationRef` is omitted, the no-preparation path performs those same authoritative commit-time checks; omitting preparation does not weaken the gate.
- The operation must validate that `targetScriptPatchVersion` is `READY` for the tenant before pinning.
- If the target patch is not `READY`, the operation must fail deterministically with an application error (for example `errorCode=SCRIPT_PATCH_NOT_READY`) and must not mutate pin state.
- The operation must also validate base-version cohesion: the target patch's `baseVersionId` must match the game instance's currently pinned `runtimeVersionId`. If they do not match, the operation must fail deterministically with `errorCode=SCRIPT_PATCH_BASE_VERSION_MISMATCH` and must not mutate pin state.
- Once a syntactically valid request is accepted and its `controlPlaneRequestId` is bound to the normalized request digest, any deterministic validation, attestation, or preparation failure returns and stores one unsuccessful immutable Game Session history result with identical previous/resulting exact tuples and no epoch advance. An exact retry of that request returns the stored result without another history entry; a different normalized digest under the same request ID is an idempotency conflict.
- On success, Game Session atomically persists the exact `(pinnedScriptPatchVersion, scriptPinEpoch)` tuple and its corresponding append-only rollout-history record for `(tenantId, gameInstanceId)`. Only after that authoritative commit may it publish the advisory `ScriptPatchPinChanged` reread wake-up. The resulting epoch is new even when the target version equals the previous version.
- When the target equals the currently pinned patch, this general pin mutation is the intentional same-version epoch-only repin and is classified as `REPIN` in the resulting history/event.

Outputs:

- `previousScriptPatchVersion` (nullable)
- `previousScriptPinEpoch` (nullable; the two previous fields are all-present or all-absent)
- `pinnedScriptPatchVersion` (nullable resulting tuple member; present together with `scriptPinEpoch` or absent together only for a never-pinned semantic `UNPINNED`; on deterministic failure the resulting tuple equals the previous tuple)
- `scriptPinEpoch` (nullable resulting exact authority epoch, paired with `pinnedScriptPatchVersion`; deterministic failure does not advance it)
- `controlPlaneRequestId`
- `errorCode` (optional on failure; required for deterministic business failures such as `SCRIPT_PATCH_NOT_READY`)

#### `RollbackScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion` (previous known-good patch)
- `preparationAttestationRef` (optional; required when candidate preparation is used; immutable Automation & Scripting-owned evidence identity/reference)
- `expectedCurrentPin` (required tagged compare-and-set precondition)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Equivalent to `SetPinnedScriptPatchVersion` but semantically indicates rollback; tooling may treat it as higher urgency. It is an explicit repin to a previously published, tenant-`READY`, base-compatible immutable patch and advances `scriptPinEpoch`; operational sequencing and convergence checks live in [Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).
- Its `preparationAttestationRef`, stale or mismatched evidence rejection, commit-time readiness/artifact/policy revision revalidation, equivalent no-preparation checks, and normalized-digest behavior are identical to `SetPinnedScriptPatchVersion`.
- This operation must reject a target equal to the currently pinned script patch. An intentional same-version epoch-only repin uses `SetPinnedScriptPatchVersion` and is classified as `REPIN`.
- The accepted-request failure-history rule from `SetPinnedScriptPatchVersion` applies equally here: after the normalized digest is bound, deterministic validation or preparation failure stores one unsuccessful immutable history result with identical previous/resulting exact tuples and no epoch advance; exact same-ID retries return it and a different digest conflicts.
- Target patch readiness requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must be `READY` for the tenant or the request fails with a deterministic application error (`SCRIPT_PATCH_NOT_READY`).
- Base-version cohesion requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must have `baseVersionId` equal to the instance `runtimeVersionId` or the request fails with `SCRIPT_PATCH_BASE_VERSION_MISMATCH`.
- On success, Game Session first atomically persists the exact resulting pin tuple and corresponding append-only rollout-history record, then emits only the advisory `ScriptPatchPinChanged` reread wake-up with `changeType=ROLLBACK`; the reserved `ScriptPatchRollbackRequested` family is neither emitted nor consumed.

Outputs: same as `SetPinnedScriptPatchVersion`.

#### `RecoverScriptPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`
- `controlPlaneRequestId` (the original pin-transition workflow request ID)
- `expectedTimedOutConvergenceAttemptGeneration` (the terminal attempt generation being recovered)
- `actor`
- `reason`

Semantics:

- This is a Game Session owner mutation. Logging & Admin may expose or forward it but must not persist recovery or pin-transition state.
- The recovery idempotency identity is `(controlPlaneRequestId, expectedTimedOutConvergenceAttemptGeneration)`. The normalized fingerprint also binds `tenantId`, `gameInstanceId`, `actor`, and `reason`; an exact retry returns the recorded resulting generation and deadline, while a changed actor, reason, or other normalized field conflicts before mutation.
- After exact recovery-request idempotency is resolved, a new recovery input atomically verifies that the current workflow is `PIN_CONVERGENCE_TIMEOUT` at the expected generation and transitions it to `CONVERGING` with the new generation and deadline; a mismatch is a non-mutating conflict. On success it records the mapping from the expected timed-out generation to the resulting generation, increments `convergenceAttemptGeneration` exactly once, persists fresh `CONVERGING` state and an absolute `convergenceDeadlineAt`, and leaves the pinned exact tuple and immutable `operationKind` unchanged. An exact retry returns the recorded mapping even after the workflow has advanced beyond the expected timed-out generation.
- A retry of the original pin-transition mutation is not recovery: it returns that request's stored terminal result and does not allocate a generation. Only this explicit recovery mutation can move `PIN_CONVERGENCE_TIMEOUT` back to `CONVERGING`.

Outputs:

- `tenantId`, `gameInstanceId`
- `controlPlaneRequestId`
- `expectedTimedOutConvergenceAttemptGeneration`
- `convergenceAttemptGeneration` (the resulting generation)
- `convergenceDeadlineAt`
- `workflowState` (`CONVERGING` on a successful new recovery attempt)

Shared timeout event producer rule: for each valid persisted transition of the pin-transition workflow into `PIN_CONVERGENCE_TIMEOUT`, Game Session must emit exactly one `ScriptPinConvergenceTimedOut` event, and only from workflow evidence whose exact `(tenantId, gameInstanceId, targetScriptPatchVersion, targetScriptPinEpoch, operationKind, convergenceAttemptGeneration, controlPlaneRequestId)` matches the event payload. `operationKind` remains the existing `SET`, `ROLLBACK`, or `REPIN` value; a request ID or partial/stale observation alone is insufficient. Workflow deadline and recovery sequencing are owned by [Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#pin-convergence-acknowledgment-predicate).

### Automation & Scripting: Patch Lifecycle Visibility

#### `GetScriptPatchStatus`

Implementation note: the current Automation & Scripting API exposes these reads from durable `script_work_items` and now enriches them with Game Design publication metadata. The live response includes the current runtime-readiness summary plus the published script patch `baseVersionId`, but it incorrectly labels the Automation participant's aggregate release digest as `abilitySchemaDigest`. The target field is the separately attested Game Logic-owned ability-schema digest for that base version; until the release bundle and response carry it, compatibility proof remains incomplete. `supersededByScriptPatchVersion` also remains target-state follow-through rather than a shipped field.

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
- This read is diagnostic cleanup progress only. Its counts do not gate Automation resumption or ordinary gameplay ticks; exact target-artifact convergence and schedule reconciliation are unconditional Automation admission gates, and fresh signer-policy convergence is an additional admission gate only for plugin-backed scopes. Missing, stale, revoked, or otherwise fail-closed signer evidence keeps plugin admission blocked and cannot be replaced by drain counts. Cleanup remains asynchronous and may be bounded pending after the workflow reaches `COMPLETED`, subject to the displaced `(scriptPatchVersion, scriptPinEpoch)` fence.

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

- ordered authoritative Game Session history rows containing `eventId`, `tenantId`, `gameInstanceId`, `operationKind` (`SET` | `ROLLBACK` | `REPIN`), nullable previous tuple (`previousScriptPatchVersion`, `previousScriptPinEpoch`), nullable resulting tuple (`scriptPatchVersion`, `scriptPinEpoch`), nullable `rolloutStatus`, `controlPlaneRequestId`, `actor`, `reason`, `outcome`, `committedAt`, and bounded pagination metadata. Each tuple is all-present or all-absent; both absent is semantic `UNPINNED` only for a never-pinned instance, never a sentinel.
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

- ordered event rows containing `eventId`, `tenantId`, source runtime scope (`gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`), exact source pin `scriptPatchVersion`/`scriptPinEpoch`, `scriptId`, optional plugin identity and target-state `pluginActivationEpoch`/captured `lifecycleRevision`, `workItemId`, `commandOrdinal`, `automationDispatchId`, target-state `handoffRequirement`, optional `gameSessionCommandId`, distinct target runtime scope when applicable (`targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, `targetRegionEpoch`), optional remote follow-up ids (`remoteCoordinatorId`, `remoteFollowupId`), current owned target runtime scope (`currentTargetRuntimeGameInstanceId`, `currentTargetRuntimeRegionId`, `currentTargetRuntimeRegionEpoch`) plus the current owned routing bundle (`currentTargetRuntimePlayableStateScope`, `currentTargetRuntimeWorldSlug`, `currentTargetRuntimeRealmSlug`, `currentTargetRuntimePointerVersion`) and stale-scope/routing signaling, later Game Session gameplay-command execution truth (`gameplayCommandExecutionOutcome`, `gameplayCommandGameplayResult`, failure details, and remote-state tail), `targetEntityId`, rendered `emittedCommandText`, `handoffOutcome`, `handoffReason`, and `observedAt`

Contract rules:

- This is the per-command observability companion to work-item-level audit and dead-letter reads. Multi-command work items must not collapse handoff chronology into one row. For plugin-backed rows, the captured `pluginActivationEpoch` and `lifecycleRevision` are retained as fence evidence rather than inferred from current plugin state.
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
- `gameInstanceId` (required; plugin cancellation is instance-scoped)
- `pluginId`
- `pluginVersionId`
- `pluginActivationEpoch` (the displaced activation epoch; cancellation must match the stored exact plugin activation tuple)
- `scriptPatchVersion` (the displaced script patch version carried by the plugin-produced work)
- `scriptPinEpoch` (the displaced pin epoch; cancellation must match the stored exact runtime tuple, and a same-version repin with a newer epoch is not eligible)
- `workItemIds[]` (required, nonempty, unique explicit parent work-item IDs; maximum 100)
- Optional `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Outputs:

- `results[]` (bounded, one result per requested parent `workItemId`; each result retains the stable `workItemId`, the stored `bindingId` evidence, and stored plugin fence evidence `(pluginActivationEpoch, lifecycleRevision)` when present)
  - `outcome` is exactly one of `canceled`, `already_terminal`, `recovery_in_progress`, `not_found_or_not_owned`, or `rejected`.
  - `reason` is present only when applicable, including for `outcome=rejected`, and uses a bounded reason such as `plugin_binding_mismatch`, `plugin_activation_epoch_mismatch`, `script_pin_epoch_mismatch`, `runtime_scope_mismatch`, `component_policy_unavailable`, `signer_policy_unavailable`, or `authority_unavailable`.
- `canceledCount` (derived from `results[]` by counting only `outcome=canceled`; an exact retry returns the same aggregate)

Contract rules:

- This is the plugin-version companion to `CancelPendingWorkItemsForPatch`.
- `workItemIds[]` is validated as a nonempty, unique, nonblank list of at most 100 IDs before candidate reads or mutation. Over-limit, duplicate, blank, or otherwise invalid selector input is rejected with `INVALID_ARGUMENT`; the service never silently truncates or pages an exact request. Callers repeat bounded batches with new request IDs. The canonical request digest binds the normalized, set-canonical (sorted unique) IDs together with the complete operation name, exact selection scope `(tenantId, gameInstanceId, pluginId, pluginVersionId, pluginActivationEpoch, scriptPatchVersion, scriptPinEpoch, optional regionId)`, `actor`, and `reason`. An exact retry returns the same stored batch results without selection or mutation; a changed digest conflicts first.
- Each requested ID is resolved against all applicable plugin-owned rows under that exact activation/version/runtime tuple and optional region; rows may belong to any binding. `bindingId` is per-row provenance and final-fence evidence, never a request selector. Missing or not-owned IDs return `not_found_or_not_owned`; tuple, scope, or other precondition failures return `rejected`, before mutation. There is no tenant-wide fallback or unbounded scope scan.
- **Target-state cancellation semantics:** the request is scoped to the supplied `gameInstanceId`; `tenantId` authorizes and audits the request but does not apply one epoch tenant-wide. A same-version repin with a newer `scriptPinEpoch` is not eligible. `PENDING_EVALUATION` is compare-and-set to `CANCELED` without DSL evaluation. An `EXECUTING` row is fenced and its descriptor-commit marker inspected; if committed, cancellation never resumes or re-dispatches a committed child: `PENDING` and `INDEXED` children compare-and-set to `CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`; `HANDOFF_IN_FLIGHT` fences further retry and reconciles the durable downstream outcome (remaining active/unresolved when ambiguous); and `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` children retain their outcome and no-op. An `EXECUTING` row with no descriptor commit (a started-but-uncommitted evaluation) is explicitly canceled with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and bounded cancellation metadata, and is never replay-eligible. Only the distinct expired-stale recovery-owner path for an `EXECUTING` row with no descriptor commit may use the `DEAD_LETTERED`/`stale_execution_fenced` mapping; this cancellation request does not dead-letter stale displaced rows. The current live surface remains limited to pre-evaluation/non-handoff rows until descriptor persistence and downstream reconciliation are implemented.
- Each requested parent produces exactly one result, aggregating its parent and applicable child states with deterministic priority: `not_found_or_not_owned` or `rejected` for precondition failure; `recovery_in_progress` if any selected parent/child remains active or ambiguous (including `HANDOFF_IN_FLIGHT`), even when siblings were canceled; `canceled` only when this request changed at least one eligible parent/child and every applicable child is terminal afterward; and `already_terminal` only when no mutation was needed and the parent and every applicable child was already terminal. Results may include bounded child-state counts and a reason/evidence code. `canceledCount` is derived only by counting parent results whose outcome is `canceled`.
- This is bounded asynchronous cleanup for rollback, disable, and revocation resource convergence. `lifecycleRevision` is not a request selector or request-digest field: cleanup is asynchronous and the lifecycle revision is not its correctness fence. When stored, per-row/final-fence evidence may retain and revalidate it beside `pluginActivationEpoch`; exact runtime and plugin fences, including the row's `bindingId` evidence and activation epoch, remain authoritative before handoff and final effects.

#### `GetAutomationPinConvergence`

Implementation note: the current Automation & Scripting implementation now persists a durable `script_patch_pin_projections` view keyed by `(tenantId, gameInstanceId)`. Automation refreshes that projection opportunistically from the shared Game Session runtime-state surface and serves `GetAutomationPinConvergence` from the persisted projection for operator visibility, so freshness and temporary Game Session read failures do not force raw pass-through reads. The current proto/implementation does not yet expose `observedScriptPinEpoch` or `observedConvergenceAttemptGeneration`; until those fields propagate, this patch-only projection is diagnostic only. Missing `observedScriptPinEpoch` means exact-tuple admission/replay must fail closed; missing `observedConvergenceAttemptGeneration` separately means attempt-bound convergence proof must fail closed. The target output below requires both fields for the distinct exact-tuple and attempt-bound convergence contracts. Projection stale flags use the `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` runtime knob; see the [cross-service version-fencing contract](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety).

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` and `observedScriptPinEpoch` (nullable exact pair; both present or both absent only for a never-pinned semantic `UNPINNED`, never a sentinel or partial projection)
- `observedConvergenceAttemptGeneration` (positive generation observed with the exact pair and request identity; absent when no workflow observation exists)
- `lastObservedControlPlaneRequestId` (nullable; absent only when the instance has never been pinned and the observed pair is semantic `UNPINNED`; retained with a pinned pair when the projection is stale, and otherwise represents the last committed pin mutation associated with the observation)
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

Implementation note: the current Automation & Scripting implementation now exposes the first durable instance-scoped timer materialization read from `script_schedule_instances`. Those rows are refreshed from the same observed Game Session pin state used by admission and rollout reads, and they project the currently pinned patch's durable schedule definitions into one `(tenantId, gameInstanceId)` scope. Current rows and wire responses remain patch-only and do not persist or expose `scriptPinEpoch`, so they are diagnostic rather than exact-tuple fence proof. Materialization is now per matching event binding rather than per raw script definition only, so each row carries target-scope identity and binding priority alongside schedule definition identity. Wall-clock timers currently compute `nextDueAt`, which maps to target durable `dueAt` plus projected `nextRunAt`, not to independent deadlines; tick-aligned schedules are persisted explicitly as `PENDING_RUNTIME_PROGRESS` until heartbeat-driven `nextTick` materialization lands.

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional current filter: `scriptPatchVersion`; target state additionally supports exact `scriptPinEpoch` filtering.
- `limit` (bounded by the service)

Outputs:

- **Current live output:** patch-only instance-scoped schedule entries omit `scriptPinEpoch` and therefore cannot prove an exact pin tuple.
- **Target-state output:** instance-scoped schedule entries contain the exact `scriptPatchVersion` and `scriptPinEpoch`, `scriptId`, plugin owner metadata, resolved `playableStateScope`, `scheduleDefinitionId`, event type, cadence, scheduler priority tag, target-scope identity (`targetScopeType`, `targetScopeId`), binding priority/exclusivity flags, materialization status, due-point fields, observed runtime version id, the pin operation's `controlPlaneRequestId`, pin observation time, row timestamps, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted scheduler row scope.

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
- Nonempty, unique `workItemIds[]` only, with a maximum of 100 durable parent work-item identifiers. More than 100 IDs returns gRPC `INVALID_ARGUMENT` with bounded reason `work_item_ids_limit_exceeded`; an empty list, blank ID, or duplicate ID returns gRPC `INVALID_ARGUMENT` with bounded reason `invalid_work_item_ids`. Both validation paths run before request fingerprinting or claim acquisition. Descriptor references and filters are listing/preview inputs, not mutation selectors. Bulk filter replay remains deferred until preview plus stable per-row proof.
- `controlPlaneRequestId`
- `actor`
- `reason`

Target-state outputs:

- `results[]` (one deterministic result per requested `workItemId`, including `workItemId`, `outcome`, optional/nullable `recoveryStage`, `rejectionReason` only when rejected, and `failureReason` only when recovery failed; `recoveryStage` is `null` when the result is `not_found_or_not_owned` or `stage_evidence_unavailable` because no trustworthy stage is available)
  - `failureGeneration` is the resolved generation for the selected parent, or the generation stored with an exact retry result; it is absent only when no owned generation can be resolved.
  - `outcome` is exactly one of `retried_evaluation`, `resumed_dispatch`, `already_recovered`, `recovery_failed`, or `rejected`.
  - `rejectionReason` is present only with `outcome=rejected` and uses established bounded values such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `work_item_not_dead_lettered`, `recovery_in_progress`, `script_pin_epoch_mismatch`, `plugin_binding_mismatch` (including an ineligible `lifecycleRevision` mismatch), `plugin_activation_epoch_mismatch`, `runtime_scope_mismatch`, `plugin_disabled`, `plugin_version_not_published`, `plugin_component_policy_blocked`, `component_policy_unavailable`, `signer_policy_unavailable`, `signer_revoked`, or `authority_unavailable`.
  - `failureReason` is a required, bounded failureReason only with `outcome=recovery_failed`, is absent for every other outcome, and reuses the established stage-aware failure-reason vocabulary for the terminal failed recovery attempt rather than introducing a parallel taxonomy.
- `replayedCount` (the number of `results[]` rows whose stored `outcome` is `retried_evaluation` or `resumed_dispatch`; `already_recovered`, `recovery_failed`, and `rejected` do not contribute, and exact retries derive the same count from the stored results)
- `rejectedCount`

Public `workItemId` values are selector/result aliases only. Before a recovery claim is acquired, the mutation atomically resolves each alias against current owned parent state to the internal identity `(tenantId, outboxWorkItemId, failureGeneration)`; claim/attempt, exact-retry, and recovery evidence comparisons bind that internal identity.

Contract rules:

- For plugin-backed work, the recovery claim and attempt capture the exact `(pluginActivationEpoch, lifecycleRevision)` fence in addition to the script pin tuple. Work that references a revocable component also retains the applicable component-revocation security-policy fence as an independent recovery input; neither plugin lifecycle evidence nor ordinary component-policy evidence substitutes for it. Claim acquisition and each pre-evaluation/dispatch revalidation compare both lifecycle values with current owner evidence; a `lifecycleRevision` mismatch outside ADR 0119's same-epoch `DRAINING` predecessor eligibility is rejected with `plugin_binding_mismatch`, while `plugin_disabled` remains reserved for a lifecycle transition that invalidates admitted work.
- Same-epoch `DRAINING` is the only predecessor exception: already-admitted work captured under the immediately preceding `ENABLED` lifecycle revision may finish only when its winning admission/fence compare-and-set committed before the durable `DRAINING` admission barrier was created (and therefore before the lifecycle transition) and every other exact fence passes. Arbitrary lower or non-predecessor revisions are rejected; see [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md).

- Replay is fail-closed per work item. Eligibility requires exact current `(scriptPatchVersion, scriptPinEpoch)`, applicable plugin identity/version/binding and captured `(pluginActivationEpoch, lifecycleRevision)`, region/`regionEpoch`, and routing-bundle match to the immutable admitted evidence. For plugin work, the current scoped plugin activation/lifecycle, component-policy decision, capability-grant evidence, and signer/publication evidence must also be fresh and valid.
- After exact-request idempotency and current `failureGeneration` resolution, immutable successful-recovery evidence bound to that same current generation returns `outcome=already_recovered` before aggregate-status or active-claim checks; `recovery_in_progress` applies only when no same-generation successful-recovery evidence exists and an active claim remains. After that precedence check, retained evidence proving that the selected parent's recovery inputs were purged—including `descriptorEvidencePurgedAt` for an `EVALUATED_COMMITTED` parent or the retained purge outcome for a pre-DSL row—returns `outcome=rejected` with `rejectionReason=stage_evidence_unavailable` and creates no claim/attempt. Using that internal identity, recovery compares-and-sets the current parent recovery aggregate `status=DEAD_LETTERED` together with a persisted recovery claim/attempt record containing `(tenantId, outboxWorkItemId, failureGeneration)`, the expected exact `(scriptPatchVersion, scriptPinEpoch)`, runtime scope, applicable plugin binding tuple and captured `(pluginActivationEpoch, lifecycleRevision)`, and `controlPlaneRequestId`, before evaluation or dispatch. A post-evaluation parent may retain its `EVALUATED_COMMITTED` descriptor marker and complete output/child ledger while this separate recovery aggregate is `DEAD_LETTERED`; that is the eligible `resumed_dispatch` case. A selected row in any other aggregate status remains unchanged and returns `outcome=rejected` with `rejectionReason=work_item_not_dead_lettered`. If a different `controlPlaneRequestId` targets a row with an active `IN_PROGRESS` claim for that generation, its stored deterministic request result is `outcome=rejected` with `rejectionReason=recovery_in_progress` and it creates no new claim/attempt, evaluation, or dispatch. If a created claim/attempt reaches terminal `FAILED`, its stored request result is `outcome=recovery_failed` with a required, bounded failureReason and no `rejectionReason`; an exact retry returns that stored result and reason without a new attempt. An expired claim is reclaimed under the same attempt record and identity (with a new owner fence when needed), never by inserting a duplicate recovery attempt or audit record; lease expiry alone does not allow a different request to create an attempt. A later new request may create one new attempt only after the prior attempt is terminal `FAILED` and fresh eligibility/fence checks pass. A prior successful recovery generation remains immutable; it is not `already_recovered` evidence for a newer current failure generation.
- Evaluation-stage recovery uses the frozen original trigger/input manifest and exact graph, preserving the original work-item and `scriptEventId` identity; before its winning evaluated-descriptor/outbox commit it allocates no dispatch or command-child identity. If it emits commands, that first atomic descriptor commit allocates and persists the `automationDispatchId` and deterministic ordinals; retries after a winning commit reuse them. A valid zero-command evaluation creates no dispatch identity. It may invoke the DSL evaluator again for eligible gameplay/runtime work outside normal admission. Tenant-readiness `onLoad` remains at-most-once and is excluded from evaluation replay. Post-evaluation recovery uses only the immutable evaluated output and complete child-dispatch ledger keyed by the full Command-Handoff Identity, preserving original child identities and per-child recovery state without invoking the DSL. `HANDED_OFF` (accepted), `CANCELED`, and `DEAD_LETTERED` children are terminal and no-op. An already `HANDOFF_IN_FLIGHT` child is fenced and reconciled against the durable downstream outcome, never blindly redispatched; if that outcome is ambiguous, the child remains active/unresolved, the same recovery attempt remains `IN_PROGRESS`, no temporary or terminal per-row application result is stored, and `resumed_dispatch` is not reported. The initiating request or an exact retry attaches to that same attempt until a terminal result is available or its transport deadline expires; a transport deadline does not store a different result. Immediately before evaluation, and immediately before each post-evaluation dispatch, it revalidates the immutable admitted source runtime scope `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)`, optional distinct target runtime scope `(targetGameInstanceId, targetPlayableStateScope, targetRegionId, targetRegionEpoch)`, exact `(scriptPatchVersion, scriptPinEpoch)`, and admitted routing bundle `(playableStateScope, worldSlug, realmSlug, pointerVersion)`, together with the recorded binding, captured `(pluginActivationEpoch, lifecycleRevision)`, and current scoped plugin activation/lifecycle, component-policy, capability-grant, and signer/publication evidence. For any revocable component, the retained component-revocation security-policy fence is independently revalidated at evaluation, each post-evaluation dispatch, and immediately before gameplay effects; it is not substituted by lifecycle or ordinary component-policy evidence. `DRAINING` blocks new trigger admission but does not invalidate already-admitted recovery; only a lifecycle transition that invalidates admitted work, such as `DISABLED`, revocation, or policy-driven disablement, uses `plugin_disabled`. Only after that dispatch-time revalidation succeeds may a `PENDING` or `INDEXED` child transition by durable compare-and-set to `HANDOFF_IN_FLIGHT` under the existing recovery claim/attempt fence and dispatch. Missing, stale, or temporarily unavailable evidence fails closed with the applicable established bounded reason (`authority_unavailable`, `component_policy_unavailable`, or `signer_policy_unavailable`); proven publication, component/capability, signer, tuple, epoch, or scope mismatches use `plugin_version_not_published`, `plugin_component_policy_blocked`, `signer_revoked`, `plugin_binding_mismatch`, `plugin_activation_epoch_mismatch`, `script_pin_epoch_mismatch`, or `runtime_scope_mismatch` as applicable. A `lifecycleRevision` mismatch outside the bounded `DRAINING` predecessor exception uses `plugin_binding_mismatch`; `plugin_disabled` remains reserved for an invalidating lifecycle transition. It never resolves a latest/local graph, and never regenerates an output after a child was accepted.
- Rows with missing/contradictory stage evidence or any fence mismatch discovered before claim acquisition remain `DEAD_LETTERED` and return a deterministic per-row `outcome=rejected` with the applicable bounded `rejectionReason`, with no claim/attempt. If the same evidence failure occurs after a claim/attempt exists, that attempt terminalizes as `FAILED` and returns/stores `outcome=recovery_failed` with a required, bounded failureReason and no `rejectionReason`; an exact retry returns the stored deterministic result. A rejected row is not partially changed or counted as successful.
- The request is bounded and idempotent by `controlPlaneRequestId`, actor, reason, and explicit work-item IDs. An exact retry of the same `controlPlaneRequestId` and canonical request fingerprint returns the identical stored per-row outcomes and stored generation, including `recovery_failed` with its stored `failureReason` or `rejected`, without evaluation, dispatch, or relabeling. Reusing a `controlPlaneRequestId` with a changed canonical request fingerprint is a request-level conflict detected before claim acquisition, evaluation, or dispatch; it cannot alter stored per-row results and is not represented as a per-row rejection. A new request that finds the current generation already recovered returns `outcome=already_recovered`; older-generation success does not block recovery of the current generation. No new Trigger Identity is introduced; a Command-Handoff Identity can first be allocated only by the winning descriptor commit, and later recovery never creates a second one. Purge is a separate operation and never masquerades as recovery.

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

- The authoritative source for rollout transitions is Game Session's durable current pin state and append-only rollout history. A committed `ScriptPatchPinChanged` notification is an advisory reread wake-up, not rollout-history authority.
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

Plugins are controlled by operators via Logging & Admin, but the runtime registry and enforcement live in Automation & Scripting. The authoritative row for `(tenantId, gameInstanceId, pluginId)` binds the exact `pluginVersionId`, monotonic `pluginActivationEpoch`, lifecycle state, and monotonic owner `lifecycleRevision`. Mutating operations are scoped to a running instance, are idempotent against a canonical operation digest, and must install and durably acknowledge the corresponding Game Session final-execution fence before reporting activation or containment complete. Notifications are advisory refresh wakeups, not that fence.

Lifecycle mutations use one durable pending-transition slot per `(tenantId, gameInstanceId, pluginId)`. Initiation compare-and-sets from the captured current `(pluginVersionId, pluginActivationEpoch, pluginState, lifecycleRevision)` only when no transition is pending. Every state-changing target reserves `targetLifecycleRevision = current lifecycleRevision + 1`; an epoch-advancing target additionally reserves `targetPluginActivationEpoch = current + 1` exactly once. An exact same-request retry resumes that slot with the same reserved tuple, while any different activation, switch, drain, disable, revocation, reactivation, or policy mutation returns `transition_in_progress`/fails closed rather than reusing or overwriting the target. Completion compare-and-sets the unchanged captured tuple plus pending request identity/digest, target epoch, and target lifecycle revision; the owner advances `lifecycleRevision` exactly once only when the Game Session install has been durably acknowledged, and stale completion cannot overwrite newer state. Security, component, and signer-policy fences remain independent immediate fail-closed checks and are not delayed by this serialization.

#### `GetPluginStatus`

Implementation note: the current Automation & Scripting implementation persists and serves the runtime registry for `(tenantId, gameInstanceId, pluginId)`, and `SetPluginActiveVersion` now consults the live Game Design `GetPublishedPluginVersion` read surface plus the shared Game Session runtime-state read for runtime version, launch descriptor, version/release identifiers, and script-patch pin metadata before mutating that registry. Design-time publication eligibility, signer revocation, component-policy decisions, and `baseVersionId` compatibility are enforced, but exact `abilitySchemaDigest` compatibility is not: the live path compares against Automation's aggregate participant digest instead of the required dedicated Game Logic-owned release attestation. The activation path also now re-checks the currently pinned script-patch binding surface for the target instance, validates `COMMAND_ALIAS` bindings against Game Session's authoritative built-in command registry, and rejects instance-scoped binding conflicts before runtime state changes. Enabled plugin runtime states are also rechecked on a bounded scheduled cadence so already-active plugins are disabled if their publication state, signer metadata, or component-policy decision becomes fail-closed after activation; `REPORT_ONLY` policy decisions remain activatable and do not trigger fail-closed reconciliation. Plugin-trigger ingress uses the persisted `lastPolicyCheckedAt` evidence and fails closed with `signer_policy_unavailable` when that check is older than `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS`, and `GetPluginStatus` now exposes both `lastPolicyCheckedAtMs` and `policyCheckStale` so operators can see that freshness directly.

Target state additionally exposes the Automation-owned monotonic `pluginActivationEpoch` and `lifecycleRevision` for the scoped runtime row and resulting lifecycle history. A newly created runtime row starts at epoch `0` and revision `0` while no activation is admitted. The first successful activation/enable atomically establishes epoch `1` and advances the revision once. A version switch, completed disable of an active/current lifecycle, final drain or forced drain, same-version reactivation after invalidation, or revocation of an active/current lifecycle advances the epoch exactly once; every committed state-changing transition, including same-epoch entry into `DRAINING`, advances `lifecycleRevision` exactly once. Never-active disable, failed operations, no-ops, and exact retries advance neither counter. The current runtime/status implementation does not yet persist or expose these target fences; same-version re-enable fencing remains an implementation and proof gap.

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`

Outputs:

- `tenantId`, `gameInstanceId`, `pluginId`
- `activePluginVersionId` (nullable)
- `pluginActivationEpoch` (the Automation-owned runtime fence; target state)
- `lifecycleRevision` (the Automation-owned monotonic lifecycle cursor; target state)
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

- repeated `events[]` with `eventId`, `tenantId`, `gameInstanceId`, `pluginId`, `previousPluginVersionId`, `activePluginVersionId`, `pluginActivationEpoch`, `lifecycleRevision`, `pluginState`, `statusReason`, `controlPlaneRequestId`, `actor`, and `observedAtMs`
- `error`

Contract rules:

- This is append-only runtime lifecycle history, not a projection of design-time publication events.
- `SetPluginActiveVersion`, `DisablePlugin`, `DrainPlugin`, and scheduled policy reconciliation must append one event only when they materially change runtime plugin state or the active version.
- Idempotent no-op retries against an already-applied target must not append duplicate events or advance the latest-row `lastChangedAt`.
- An already-applied idempotent request (the requested active version and resulting runtime state already equal the committed state) reuses the existing committed state and history evidence without updating `lastChangedAt`, appending duplicate history, emitting an advisory notification, or triggering projections.
- [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) owns epoch and lifecycle-cursor advancement: a successful committed active-version change, completed disable of an active/current lifecycle, final drain or forced drain, same-version reactivation after invalidation, or revocation of an active/current lifecycle advances `pluginActivationEpoch` exactly once; every committed state-changing transition advances `lifecycleRevision` exactly once, including entry into `DRAINING` without an epoch advance. Never-active disable, failed operations, exact idempotent retries, and no-op requests advance neither counter. A notification may refresh projections after the owner history commit but cannot replace the owner history or fence acknowledgement.
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

- Digest-bound idempotent.
- Validates that the target plugin version is `PUBLISHED` in the Game Design design-time lifecycle before any runtime mutation occurs. Non-published versions must fail deterministically with an application error (for example `PLUGIN_VERSION_NOT_PUBLISHED`).
- Validates that the target bundle is allowed for the environment (signature verified, signer allowed, component policy satisfied).
- Validates runtime-version compatibility before activation:
  - `plugin.baseVersionId` must equal the instance `runtimeVersionId`.
  - `plugin.abilitySchemaDigest` must match the dedicated immutable `abilitySchemaDigest` in a fresh, supported Game Design `GetPublishedReleaseBundle` attestation for the exact base/release version used by the running instance; the Game Logic-owned ability-schema snapshot is the source of that field, not an Automation participant aggregate digest. A missing, unsupported, stale, or otherwise non-matching attestation/digest fails closed before activation.
  - Any mismatch or unavailable attestation fails deterministically with an application error (for example `PLUGIN_BASE_VERSION_MISMATCH`, `PLUGIN_ABILITY_SCHEMA_MISMATCH`, `RELEASE_BUNDLE_NOT_FOUND`, `SCHEMA_VERSION_UNSUPPORTED`, or `RELEASE_ATTESTATION_MISMATCH`) and must not mutate active plugin state.
- Current implementation note: the live control-plane path enforces `PUBLISHED` design-time state, non-revoked signer metadata, non-blocking component-policy decisions, `plugin.baseVersionId == runtimeVersionId`, supported built-in `COMMAND_ALIAS` bindings, and no instance-scoped binding conflicts against the currently pinned script patch plus already-enabled plugins before updating the runtime registry. Its `plugin.abilitySchemaDigest` check incorrectly compares the Automation participant's aggregate digest; it remains unproved until it consumes the dedicated Game Logic-owned `abilitySchemaDigest` from the running release attestation.
- On success, when `targetPluginVersionId` changes the active version or a same-version activation/enable re-admits work after invalidation, the epoch-advancement rule above applies. The operation first reserves the exact target epoch/state/revision in the pending-transition slot, installs that tuple at Game Session through an idempotent control-plane command, and waits for durable fence acknowledgement. It must not commit the Automation owner target state, history, result, or notification before that acknowledgement; one completion compare-and-set then commits the owner tuple and advances its revision exactly once. Durable plugin schedules, pending work, and follow-ups reconcile asynchronously; the version-and-epoch/revision fence, not cleanup completion, prevents displaced work from mutating gameplay. The committed owner history may publish `PluginVersionRuntimeStateChanged` as an advisory notification.
- If the requested active version and resulting runtime state already equal the committed state, returns the existing committed state without updating `lastChangedAt`, appending history, emitting an advisory notification, triggering projections, or reconciling schedules/timers.
- The epoch-advancement rule above applies to this operation: every successful activation or version switch advances the epoch and lifecycle revision, while exact idempotent retries, failed operations, and no-op requests do not mint another epoch or revision. `DrainPlugin`, not this operation, owns entry into `DRAINING`.

Outputs:

- `previousPluginVersionId` (nullable)
- `activePluginVersionId`
- `pluginActivationEpoch`
- `lifecycleRevision` (the committed Automation-owned lifecycle cursor for the returned state)
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

- Digest-bound idempotent: the `controlPlaneRequestId` is bound to the canonical digest of the complete operation input; a changed input conflicts before mutation, and an exact retry either resumes the same pending transition or, after completion, returns its stored result.
- For an active/current lifecycle, initiation atomically creates a durable request-digest-bound pending transition/admission-barrier record with the captured current tuple, one reserved target epoch (`current + 1`), and `targetLifecycleRevision = current lifecycleRevision + 1`, plus target `pluginState=DISABLED`. It durably blocks new admission, survives restart, and does not change the current epoch/state/revision at initiation; admission must check the barrier before admitting work.
- Game Session installs that exact target epoch/state/revision idempotently. A failed or lost acknowledgement leaves the barrier and pending transition in place and fail closed; an exact retry resumes the same target tuple and install command and does not return a completed or no-op result while pending. After durable acknowledgement, one Automation transaction compare-and-sets the captured tuple and advances the current epoch/state/revision exactly once, marks the transition complete, and stores the result; exact retries after completion return that stored result.
- Triggers are rejected at admission with a dedicated outcome (for example `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`.
- Disabling a never-active plugin at epoch `0` is an idempotent no-op and does not advance the epoch. An already-`DISABLED` request is a no-op only when the corresponding transition and Game Session fence acknowledgement are complete; otherwise it resumes the pending transition. Failed operations and retries do not add epochs. The barrier is not automatically cleared on timeout or failure; any clearance requires a separate authorized, audited pre-fence cancellation compare-and-set and is forbidden once the target fence may have installed. After the completed owner transition, `PluginVersionRuntimeStateChanged(newState=DISABLED)` may be published as an advisory notification; it follows completed owner state and is never the containment barrier. See [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) for the lifecycle authority.

Target-state outputs:

- `activePluginVersionId` (the committed exact plugin version, nullable when no version is bound)
- `pluginActivationEpoch`
- `lifecycleRevision`
- `pluginState` (the resulting committed lifecycle state)
- `controlPlaneRequestId`
- An exact retry returns the stored response for that request identity; it does not mint a new lifecycle revision or epoch.

#### `DrainPlugin`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Digest-bound idempotent.
- This operation exclusively owns entry into `DRAINING`. A new drain requires an active current lifecycle with `pluginState=ENABLED`; never-active or other non-executable states use the applicable established deterministic no-op/rejection taxonomy and do not create a drain transition. An exact retry of an already pending drain resumes that transition and its existing target tuple. When the plugin is not already `DRAINING`, it atomically persists a request-digest-bound pending drain transition and admission barrier containing the current exact tuple (plugin version, `pluginActivationEpoch`, current `lifecycleRevision`, and state) plus same-epoch `targetLifecycleRevision = current lifecycleRevision + 1`, before issuing the Game Session install. Every trigger, timer, work-item, follow-up, staged-command, and gameplay-command admission path checks that durable barrier; it survives restart. Only installation and durable acknowledgement of the exact `{pluginActivationEpoch, DRAINING, targetLifecycleRevision}` Game Session fence permits the Automation owner to commit `DRAINING` state/history/revision. New admission is blocked; previously admitted work may complete only within bounded limits when it carries the exact captured version, activation epoch, and lifecycle revision, its policy and runtime fences still pass, and its winning admission/fence compare-and-set durably committed that immediately preceding `ENABLED` lifecycle revision before the pending drain barrier was created. Capturing or merely observing a tuple is not admission proof. Entry into `DRAINING` does not advance `pluginActivationEpoch`.
- At bounded completion or forced timeout, it reserves `targetPluginActivationEpoch = current + 1` and `targetLifecycleRevision = current + 1` for the final non-executable state, installs and durably acknowledges that exact final Game Session fence, and then advances the Automation-owned epoch/state/revision exactly once by completion compare-and-set. Cleanup remains asynchronous and cannot delay the non-executable fence.
- If the plugin is already `DRAINING`, returns the existing committed state without updating `lastChangedAt`, appending history, or emitting a notification. Otherwise, records owner history and may publish `PluginVersionRuntimeStateChanged(newState=DRAINING)` as an advisory notification after the committed transition.

Target-state outputs:

- `activePluginVersionId` (the committed exact plugin version, nullable when no version is bound)
- `pluginActivationEpoch`
- `lifecycleRevision`
- `pluginState` (the resulting committed lifecycle state)
- `controlPlaneRequestId`
- An exact retry returns the stored response for that request identity; it does not mint a new lifecycle revision or epoch.

### Automation & Scripting: Event Ingress Admission Contract (Normative)

`TriggerScriptEvent` and equivalent ingress RPCs must return a structured admission result so callers can implement retries without inferring behavior from transport errors.

Required response fields:

- `admitted` (`true` when admitted to pipeline; `false` otherwise)
- `admissionOutcome` (enum)
- `admissionReason` (bounded code/string)
- `retryAfterMs` (optional server hint; required for backpressure outcomes where retry is expected)
- `resolvedHandlerCount` (non-negative count of the complete handler set resolved for the finalized ingress attempt; immutable with the ingress result and `0` when no handler set was resolved)

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
- A terminal pin-transition convergence timeout uses `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` with `admissionReason=pin_convergence_timeout` and no `retryAfterMs`; it is fail-closed until the same workflow transitions from `PIN_CONVERGENCE_TIMEOUT` back to `CONVERGING` with the same `controlPlaneRequestId`, immutable `operationKind`, and a fresh deadline, explicit repair, or repin, and it is not retryable backpressure.
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
- `resolvedHandlerCount` is a finalized ingress result, not an estimate or a summary of handler outcomes; it must not replace per-handler audit records as the source of truth. The current gRPC exception/unavailable fallback leaves the protobuf scalar at its default `0` because no finalized admission result exists; this fallback is implementation behavior, not target proof of a resolved handler count.

## Related Control Plane Contracts

The detailed event and orchestration contracts now live in focused sibling docs:

- [Scripting Control Plane Events](./system-architecture-scripting-control-plane-events.md) defines advisory notification families, owner-read recovery, targeted durable-delivery admission, and required payload projections.
- [Scripting Control Plane Operations](./system-architecture-scripting-control-plane-operations.md) defines promotion, rollback, pause/resume, drain/purge, dead-letter, convergence, timeout, and degraded-operations workflows.
- [Scripting Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) provides the higher-level operator workflow summary.

## Idempotency, AuthZ, and Audit

- All mutating operations accept `controlPlaneRequestId` and must be safe to retry.
- All mutating operations require operator/admin authorization. Tenant-scoped operator actions must be auditable with actor identity and reason.
- Operator actions must be reflected in their applicable durable audit/history records; named durable event families emit their contracted status events, while other consumers reconstruct state from owner reads and history rather than assuming every committed state change has a durable notification.

For runtime trigger audit fields and metrics naming/label rules, see `design/architecture/system-architecture-scripting-observability-contract.md`.
