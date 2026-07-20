# FireMUD Scripting & Automation: Control Plane API

This document specifies the direct **control plane API** surface required to operate scripting and automation safely across Game Session, Automation & Scripting, Game Design, and Logging & Admin.

It exists to remove ambiguity from “conceptual APIs” referenced in service READMEs: this is the target-state contract that must be implemented in protos/services over time.

Workflow sequencing for rollback, pause/resume, drain/purge, dead-letter recovery, and operator audit flows lives in [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).

Routing note:

- Use this document for control-plane API shape, authoritative ownership, and state-mutation contracts.
- Use [system-architecture-scripting-rollout-and-rollback.md](./system-architecture-scripting-rollout-and-rollback.md) for drain/rollback workflow sequencing.
- Use [system-architecture-scripting-control-plane-operations.md](./system-architecture-scripting-control-plane-operations.md) for operator workflow execution details.

## Table of Contents

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
- **Auditable and observable.** Every mutating action must commit durable audit/history evidence; optional outbox notifications may accelerate downstream refresh.
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
  - Maintains only the local observed-pin, convergence, and freshness projection needed for safe runtime work.

- **Game Session Service (gameplay + tick control plane)**
  - Owns the pinned `(scriptPatchVersion, scriptPinEpoch)` and append-only rollout history for each `(tenantId, gameInstanceId)`.
  - Enforces the version fence on execution: commands produced under a non-pinned patch must not execute.
  - Exposes admin-only APIs to pause/resume ticks, mutate the pin, and read current pin and bounded authoritative history.
  - May emit a committed pin-change notification after a successful update to accelerate consumers; the notification is not the history authority.

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
- `signerKeyId`
- `distributionManifestHash` (nullable; required when the signed plugin manifest declares runtime-consumable `assetRefs[]`)
- `distributionManifestPath` (nullable; required when the signed plugin manifest declares runtime-consumable `assetRefs[]`)
- `publishedAt` (nullable; required when `designStatus=PUBLISHED`)
- `statusReason` (optional; required for deterministic design-time failures or revocation)

Contract rules:

- `designStatus=PUBLISHED` means the immutable plugin bundle is signed, validated, and eligible for runtime activation. It does not imply that any instance has activated it.
- Runtime activation and drain/disable state remain the responsibility of Automation & Scripting via `GetPluginStatus`; callers must not infer `ENABLED` or `DISABLED` from Game Design publication state.
- A plugin version that is not `PUBLISHED` must be rejected by runtime activation APIs with deterministic application errors rather than being partially loaded and then downgraded later.
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

- ordered `PublishedPluginVersion` rows containing `tenantId`, `pluginId`, `pluginVersionId`, `publicationId`, `baseVersionId`, `publicationState`, `abilitySchemaDigest`, `bundleDigest`, distribution-manifest metadata, signer metadata, and `lastChangedAtMs`

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
- `pinnedScriptPatchVersion`
- `scriptPinEpoch`
- `pinnedAt` (timestamp)
- `pinnedBy` (actor principal, optional)
- `controlPlaneRequestId` (nullable; the idempotent request that last changed the pin)

#### `GetGameSessionPinConvergence`

Implementation note: the current Game Session implementation exposes this convergence read directly from the persisted game-instance pin record and returns the observed patch, timestamp, and persisted `controlPlaneRequestId`. Returning and persisting the exact `scriptPinEpoch` across this surface remains target-state follow-through.

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `observedScriptPinEpoch`
- `lastObservedControlPlaneRequestId`
- `observedAt`

Contract rules:

- This is the canonical Game Session-side convergence read for rollback/promotion orchestration.
- The response must be derived from the same persisted pin mutation that `SetPinnedScriptPatchVersion` / `RollbackScriptPatchVersion` commit, not reconstructed from logs or operator events.

#### `SetPinnedScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `controlPlaneRequestId` (idempotency key)
- `actor` (operator identity metadata, required for audit)
- `reason` (free-form, required)

Semantics:

- Idempotent: repeating the same request with the same `controlPlaneRequestId` must return the same result without reapplying.
- The operation must validate that `targetScriptPatchVersion` is `READY` for the tenant before pinning.
- If the target patch is not `READY`, the operation must fail deterministically with an application error (for example `errorCode=SCRIPT_PATCH_NOT_READY`) and must not mutate pin state.
- The operation must also validate base-version cohesion: the target patch's `baseVersionId` must match the game instance's currently pinned `runtimeVersionId`. If they do not match, the operation must fail deterministically with `errorCode=SCRIPT_PATCH_BASE_VERSION_MISMATCH` and must not mutate pin state.
- On success, Game Session atomically persists the new exact pin and an append-only rollout-history record for `(tenantId, gameInstanceId)`. It may emit `ScriptPatchPinChanged` as a refresh notification from the same transactional outbox.

Outputs:

- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion` (the new value)
- `scriptPinEpoch` (the committed epoch)
- `controlPlaneRequestId`
- `errorCode` (optional on failure; required for deterministic business failures such as `SCRIPT_PATCH_NOT_READY`)

#### `RollbackScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion` (previous known-good patch)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Equivalent to `SetPinnedScriptPatchVersion` but semantically indicates rollback; tooling may treat it as higher urgency. Operational sequencing and convergence checks live in [Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).
- Target patch readiness requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must be `READY` for the tenant or the request fails with a deterministic application error (`SCRIPT_PATCH_NOT_READY`).
- Base-version cohesion requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must have `baseVersionId` equal to the instance `runtimeVersionId` or the request fails with `SCRIPT_PATCH_BASE_VERSION_MISMATCH`.
- On success, atomically commits the new exact pin and rollback-history record. It may emit `ScriptPatchRollbackRequested` or `ScriptPatchPinChanged(changeType=ROLLBACK)` as a refresh notification from that commit.

Outputs: same as `SetPinnedScriptPatchVersion`.

#### `ListScriptPatchRolloutHistory`

Inputs:

- `tenantId`
- Optional `gameInstanceId`
- Optional `scriptPatchVersion`
- Optional operation or outcome filter
- Optional `changedAfter` / `changedBefore`
- Bounded page size and continuation token

Outputs:

- Stable ordered history rows containing `tenantId`, `gameInstanceId`, `controlPlaneRequestId`, operation kind, previous and resulting exact `(scriptPatchVersion, scriptPinEpoch)` tuples, actor and reason when operator-driven, outcome, and commit time.

Contract rules:

- Game Session serves this authoritative history from the same durable owner boundary that commits and enforces the current pin.
- A successful mutation commits its history row atomically with the pin. Repeating a `controlPlaneRequestId` returns the same recorded outcome and does not append a second logical row.
- Rollback or repin to a previously used patch remains a distinct epoch and history entry.
- Logging & Admin joins these records with Automation readiness and `GetAutomationPinConvergence`; it reports any disagreement as projection lag rather than selecting Automation as a competing history authority.
- Retention and pagination are bounded operator contracts. A later non-authoritative cache or projection must be rebuildable from this owner read and is introduced only after measured need.

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
- Operators may use `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` as the current drain-empty condition for the active rollback epoch in that scope.

#### `ListScriptHandoffEvents`

Inputs:

- `tenantId`
- Optional `gameInstanceId`
- Optional `scriptPatchVersion`
- Optional `workItemId`
- Optional `handoffOutcome`
- Optional target runtime scope filters (`targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`)
- Optional durable remote-id filters (`remoteCoordinatorId`, `remoteFollowupId`)
- Optional origin identity filters (`scriptId`, `pluginId`, `automationDispatchId`)
- Optional `changedAfter` / `changedBefore`
- Optional bounded `limit`

Outputs:

- ordered event rows containing `eventId`, `tenantId`, `gameInstanceId`, `scriptPatchVersion`, `scriptId`, optional plugin identity, `workItemId`, `commandOrdinal`, `automationDispatchId`, optional `gameSessionCommandId`, explicit target runtime scope (`targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`), optional remote follow-up ids (`remoteCoordinatorId`, `remoteFollowupId`), current owned target runtime scope (`currentTargetRuntimeGameInstanceId`, `currentTargetRuntimeRegionId`, `currentTargetRuntimeRegionEpoch`) plus the current owned routing bundle (`currentTargetRuntimePlayableStateScope`, `currentTargetRuntimeWorldSlug`, `currentTargetRuntimeRealmSlug`, `currentTargetRuntimePointerVersion`) and stale-scope/routing signaling, later Game Session gameplay-command execution truth (`gameplayCommandExecutionOutcome`, `gameplayCommandGameplayResult`, failure details, and remote-state tail), `targetEntityId`, resolved `playableStateScope`, rendered `emittedCommandText`, `handoffOutcome`, `handoffReason`, and `observedAt`

Contract rules:

- This is the per-command observability companion to work-item-level audit and dead-letter reads. Multi-command work items must not collapse handoff chronology into one row.
- Automation must persist one durable handoff event per attempted emitted command, including pre-handoff rollback fencing and Game Session acceptance/rejection outcomes.
- `automationDispatchId` is the canonical low-cardinality correlation key between Automation handoff history and the Game Session gameplay-command ledger; metrics still must not label by it. Operator/debug reads can resolve the Game Session side either from the returned `gameSessionCommandId` or from the full automation identity tuple `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` when the command id is not yet known to the caller.
- Operators use this read to answer which emitted command ordinal reached Game Session, which rendered command text, target entity, and target runtime scope it addressed, whether it stayed local or became a durable remote follow-up, and whether the failure happened before handoff, at Game Session admission, or after later gameplay-side execution disposition.
- Because remote follow-up legs are now durable first-class runtime rows, this read must support direct filtering by target runtime scope, remote coordinator/follow-up ids, and origin script/plugin/dispatch identity rather than assuming one bulk history scan plus client-side correlation.
- The read must also expose the current owned target runtime scope and routing bundle from Game Session when the target instance still exists, so operators can see directly whether the persisted target runtime scope or admitted routing bundle has gone stale without a separate ownership-status lookup.
- When `gameSessionCommandId` is known, the read should also expose the later Game Session gameplay-command execution outcome instead of stopping at handoff-time admission, so operator diagnostics can stay on one handoff-history surface through local and remote gameplay execution tails.

#### `CancelPendingWorkItemsForPluginVersion`

Inputs:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- Optional `gameInstanceId`
- Optional `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Outputs:

- `canceledCount`

Contract rules:

- This is the plugin-version companion to `CancelPendingWorkItemsForPatch`.
- It cancels only Automation-owned work items that have not started evaluation or handoff yet. Work already evaluating must converge through drain status, and work already handed to Game Session must be handled by Game Session queue purge or tick/effect remediation.
- Cancellation updates handler audit with `finalStage=ADMISSION`, `finalOutcome=canceled`, and the bounded reason used for the operation.

#### `GetAutomationPinConvergence`

Implementation note: the current Automation & Scripting implementation now persists a durable `script_patch_pin_projections` view keyed by `(tenantId, gameInstanceId)`. Automation refreshes that projection opportunistically from the same shared Game Session runtime-state surface already used by admission and replay checks, then serves `GetAutomationPinConvergence` from the persisted projection so freshness and temporary Game Session read failures do not force operator reads to be raw pass-through calls. Projection stale flags use the `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` runtime knob.

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `lastObservedControlPlaneRequestId`
- `observedAt`
- `projectionAsOfMs`
- `projectionLagMs`
- `isProjectionStale`

Contract rules:

- This is a read-only operator surface for the latest pin observation currently visible to Automation-side admission and replay logic.
- The live implementation is a durable Automation-owned projection refreshed from authoritative Game Session runtime state, not a raw pass-through query.
- When Game Session runtime state reports multiple current admission pointers for one runtime target, Automation must treat the singular runtime-state routing bundle as unavailable and fail closed for any consumer that needs one unambiguous `{worldSlug, realmSlug, pointerVersion}` identity.
- If refresh from Game Session fails but Automation still has a stored observation, the API must continue returning that stored observation with freshness flags set from the projection timestamp instead of failing closed for operator visibility.

#### `ListScriptScheduleInstances`

Implementation note: the current Automation & Scripting implementation now exposes the first durable instance-scoped timer materialization read from `script_schedule_instances`. Those rows are refreshed from the same observed Game Session pin state used by admission and rollout reads, and they project the currently pinned patch's durable schedule definitions into one `(tenantId, gameInstanceId)` scope. Materialization is now per matching event binding rather than per raw script definition only, so each row carries target-scope identity and binding priority alongside schedule definition identity. Wall-clock timers already compute `nextDueAt`; tick-aligned schedules are persisted explicitly as `PENDING_RUNTIME_PROGRESS` until heartbeat-driven `nextTick` materialization lands.

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional filter: `scriptPatchVersion`
- `limit` (bounded by the service)

Outputs:

- Instance-scoped schedule entries containing `scriptPatchVersion`, `scriptId`, plugin owner metadata, resolved `playableStateScope`, `scheduleDefinitionId`, event type, cadence, scheduler priority tag, target-scope identity (`targetScopeType`, `targetScopeId`), binding priority/exclusivity flags, materialization status, due-point fields, observed runtime version id, observed pin request id, pin observation time, row timestamps, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted scheduler row scope.

Contract rules:

- This is a read-only operator/debugging surface for the first durable scheduler substrate below Redis timer indexes.
- The live implementation must report tick-aligned schedules honestly as not-yet-advanced when no heartbeat-derived due point exists; it must not invent synthetic tick coordinates.
- Reconciliation across repins is keyed by stable `scheduleDefinitionId` plus plugin owner metadata and binding target identity, not by inferred semantic similarity.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so operators can tell directly whether persisted scheduler scope has gone stale without a second runtime-state lookup.

#### `ListScriptTimerAuditEvents`

Implementation note: the current Automation & Scripting implementation now exposes the scheduler-owned subset of `script_event_audit` directly for timer troubleshooting. This read is bounded to `sourceKind=SCHEDULE_TIMER` and includes both due-point admissions that persisted work items and scheduler-owned dropped candidates such as `catch_up_truncated` and `runtime_scope_changed`, so operators no longer have to infer timer truncation/fence behavior from aggregate metrics alone.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`, `scriptId`, `eventType`, `finalReason`
- Optional `changedAfter` / `changedBefore`
- `limit` (bounded by the service)

Outputs:

- newest-first timer audit rows containing Trigger Identity fields, resolved `playableStateScope`, admitted routing bundle, plugin owner metadata, trigger mode, scheduler source state/ordinal/due-point fields, optional `workItemId`, final stage/outcome/reason, row timestamps, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted timer row scope

Contract rules:

- This is a read-only operator/debugging surface for scheduler-owned timer decisions; it must not mutate work-item or schedule state.
- The live implementation is sourced from durable `script_event_audit` rows, not reconstructed from metrics or volatile queue indexes.
- Timer-fired work that reached durable work-item persistence and timer-fired work intentionally dropped by scheduler fences/truncation share this history surface so operators can correlate a due point without joining multiple ad hoc tables first.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so operators can distinguish stale timer history from current-timeline timer activity without a second runtime-state lookup.

#### `ListScriptDeadLetters`

Implementation note: the current Automation & Scripting API exposes this read directly from durable `script_work_items` rows with `status=DEAD_LETTERED`. It is an operator inspection surface separate from the controlled replay mutation API.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`
- `limit` (bounded by the service)

Outputs:

- Newest-first dead-letter entries containing `workItemId`, Trigger Identity fields, resolved `playableStateScope`, script/event identity, `status`, bounded failure/cancel reason, `createdAt`, `updatedAt`, and the current owned runtime scope (`currentRuntimeGameInstanceId`, `currentRuntimeRegionId`, `currentRuntimeRegionEpoch`) plus the current owned routing bundle (`currentRuntimePlayableStateScope`, `currentRuntimeWorldSlug`, `currentRuntimeRealmSlug`, `currentRuntimePointerVersion`) and stale-scope/routing signaling beside the persisted dead-letter row scope.

Boundary rule:

- Operators use this read to decide whether a replay or manual remediation workflow is needed; replay itself remains a separate controlled operation so listing dead letters cannot accidentally mutate runtime state.
- The read must also expose the current owned runtime scope from Game Session when the instance still exists so stale timeline dead letters are visible directly on the dead-letter row instead of only via manual runtime-state correlation.

#### `ReplayDeadLetteredWorkItems`

Implementation note: the current Automation & Scripting implementation requeues eligible rows as `PENDING_EVALUATION`. That generic behavior contradicts the target stage-aware contract below and is not claimed as safe recovery proof.

Inputs:

- `tenantId`
- Explicit bounded `workItemIds` (numeric durable work-item identifiers)
- `controlPlaneRequestId`
- `actor`
- `reason`

Outputs:

- One deterministic result per requested ID, including evaluation retried, dispatch resumed, already recovered, not found/not owned, missing stage evidence, and exact fence mismatch outcomes.

Contract rules:

- An evaluation-stage row may retry only from the original trigger, frozen manifest, and exact immutable graph under the same identity.
- A post-evaluation row resumes only unfinished children from its durable evaluated-output/dispatch ledger and never invokes the evaluator.
- Current patch, `scriptPinEpoch`, plugin, region epoch, playable-state scope, world/realm identity, and routing pointer must exactly match the admitted row. Unavailable or stale authority fails closed.
- Missing or contradictory stage evidence and every fence mismatch leave the row `DEAD_LETTERED`.
- `controlPlaneRequestId` has a durable request-result record so retry returns the same per-row outcomes without repeating evaluation or dispatch.

### Automation & Scripting: Plugin Lifecycle Management

Plugins are controlled by operators via Logging & Admin, but the runtime registry and enforcement live in Automation & Scripting. The authoritative row for `<tenantId, gameInstanceId, pluginId>` binds exact `pluginVersionId`, monotonic `pluginActivationEpoch`, and lifecycle state. All mutating plugin operations are scoped to a running instance and use a stable request identity bound to a canonical digest of the complete operation input; exact replay returns the recorded result and changed-input reuse is rejected.

#### `GetPluginStatus`

Implementation note: the current Automation & Scripting implementation persists and serves the runtime registry for `(tenantId, gameInstanceId, pluginId)`, and `SetPluginActiveVersion` now consults the live Game Design `GetPublishedPluginVersion` read surface plus the shared Game Session runtime-state read for runtime version, launch descriptor, version/release identifiers, and script-patch pin metadata before mutating that registry. That means design-time publication eligibility, signer revocation, component-policy decisions, `baseVersionId` compatibility, and `abilitySchemaDigest` compatibility are now enforced in the live control-plane path. The activation path also now re-checks the currently pinned script-patch binding surface for the target instance, validates `COMMAND_ALIAS` bindings against Game Session's authoritative built-in command registry, and rejects instance-scoped binding conflicts before runtime state changes. Enabled plugin runtime states are also rechecked on a bounded scheduled cadence so already-active plugins are disabled if their publication state, signer metadata, or component-policy decision becomes fail-closed after activation; `REPORT_ONLY` policy decisions remain activatable and do not trigger fail-closed reconciliation. Plugin-trigger ingress uses the persisted `lastPolicyCheckedAt` evidence and fails closed with `signer_policy_unavailable` when that check is older than `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS`, and `GetPluginStatus` now exposes both `lastPolicyCheckedAtMs` and `policyCheckStale` so operators can see that freshness directly.

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`

Outputs:

- `tenantId`, `gameInstanceId`, `pluginId`
- `activePluginVersionId` (nullable)
- `pluginActivationEpoch`
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

- repeated `events[]` with `eventId`, `tenantId`, `gameInstanceId`, `pluginId`, `previousPluginVersionId`, `activePluginVersionId`, `previousPluginActivationEpoch`, `pluginActivationEpoch`, `pluginState`, `statusReason`, `controlPlaneRequestId`, `actor`, and `observedAtMs`
- `error`

Contract rules:

- This is append-only runtime lifecycle history, not a projection of design-time publication events.
- `SetPluginActiveVersion`, `DisablePlugin`, `DrainPlugin`, and scheduled policy reconciliation must append one event only when they materially change runtime plugin state or the active version.
- Exact idempotent retries must not append duplicate events, advance `pluginActivationEpoch`, or update the latest-row `lastChangedAt`. Reusing `controlPlaneRequestId` with a different canonical operation digest fails deterministically.
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
- repeated `violations[]` with `gameInstanceId`, `pluginId`, `activePluginVersionId`, `pluginActivationEpoch`, `reason`, and `lastChangedAtMs`
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
- Validates exact immutable platform-acceptance evidence, required capability grants, fresh signer/component policy, and environment eligibility. Publisher signature is one accepted provenance path under ADR 0108, not a substitute for platform acceptance or runtime policy.
- Validates runtime-version compatibility before activation:
  - `plugin.baseVersionId` must equal the instance `runtimeVersionId`.
  - `plugin.abilitySchemaDigest` must match the immutable digest recorded for the same base version used by the running instance.
  - Any mismatch fails deterministically with an application error (for example `PLUGIN_BASE_VERSION_MISMATCH` or `PLUGIN_ABILITY_SCHEMA_MISMATCH`) and must not mutate active plugin state.
- Current implementation note: the live control-plane path now enforces `PUBLISHED` design-time state, non-revoked signer metadata, non-blocking component-policy decisions, `plugin.baseVersionId == runtimeVersionId`, `plugin.abilitySchemaDigest` matching the Automation participant digest in the running published release bundle, supported built-in `COMMAND_ALIAS` bindings, and no instance-scoped binding conflicts against the currently pinned script patch plus already-enabled plugins before updating the runtime registry.
- On success, advances `pluginActivationEpoch` and durably installs the exact new epoch/state at Game Session through an idempotent control-plane command before admitting work under the activation. Automation does not report activation complete until Game Session acknowledges the final-execution fence. If installation fails, activation remains non-admitting and retries the same transition identity. Durable plugin-owned schedules, pending Automation work, remote follow-ups, and Game Session commands are reconciled asynchronously; the version-and-epoch fence, not cleanup completion, prevents displaced work from mutating gameplay.

Outputs:

- `previousPluginVersionId` (nullable)
- `activePluginVersionId`
- `pluginActivationEpoch`
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

- Digest-bound idempotent.
- Transitions the plugin into a non-admitting, non-executable state and advances `pluginActivationEpoch`. Containment is complete only after Game Session durably acknowledges that epoch/state; failure leaves Automation admission paused and the transition retryable rather than reporting a permissive success.
- Triggers are rejected at admission with a dedicated outcome (for example `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`.
- Already admitted work becomes stale at the Game Session final fence immediately. Queue, schedule, follow-up, and work-item cleanup is asynchronous.
- Emits `PluginVersionDisabled(newState=DISABLED)`.

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
- Transitions the plugin to `DRAINING` so no new triggers are admitted while previously admitted work is allowed to complete within bounded limits.
- Drain begins under the current `pluginActivationEpoch`. When bounded admitted work completes, the terminal disable transition advances the epoch. If the drain times out or policy requires immediate containment, a forced transition advances the epoch, rejects remaining old work at the final fence, and continues cleanup asynchronously.
- Emits `PluginVersionDisabled(newState=DRAINING)` (or a dedicated draining event if introduced later).

Final execution and projection rules:

- Every plugin trigger, work item, schedule/timer firing, remote follow-up, staged command, and gameplay command carries `pluginId`, exact `pluginVersionId`, and `pluginActivationEpoch`.
- Game Session applies activation projection updates monotonically by epoch and enforces version, epoch, and permitted lifecycle state immediately before final gameplay execution.
- Lifecycle mutation uses a required idempotent Game Session fence-install command and acknowledgement. Asynchronous notification may refresh other projections but cannot replace this transition barrier.
- The tick path uses the local projection and never performs a synchronous Automation, Game Design, or policy lookup.
- Revocation follows the immediate-disable containment rule: stop admission, advance the epoch into a non-executable state, durably install and acknowledge that final fence at Game Session, and reconcile displaced work asynchronously.

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
- `TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE`

Contract rules:

- Backpressure outcomes (`*_BACKPRESSURE_*`) must include bounded `retryAfterMs`.
- `admissionOutcome` and `admissionReason` describe the **event-scope ingress decision** only. They must not be interpreted as a summary of all handler-scoped outcomes created after binding resolution.
- Event-scope `admissionOutcome` and `admissionReason` must map directly to the ingress-time admission result recorded in ingress audit/logging surfaces for that request; they are not the same thing as later handler-scoped `finalOutcome` values recorded in `script_event_audit`.
- Handler-scoped denials such as `quota_denied`, `script_disabled`, `plugin_disabled`, and `plugin_component_blocked` remain handler/audit outcomes after binding resolution. They are not valid event-scope ingress `admissionOutcome` values in the general fan-out contract.
- Admission failures are application-level outcomes and must not be surfaced as transport errors.
- Event ingress uses `TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED` only for request-envelope limits it can decide before handler resolution, such as an oversized ingress payload. Output generated later by a resolved handler is incrementally bounded and recorded as that handler's `DSL_EVAL` outcome under ADR 0088; it does not retroactively change event-scope admission.
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
