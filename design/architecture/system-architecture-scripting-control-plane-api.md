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
  - Consumes Game Session pin events to project rollout history read models.

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

### Game Session: Patch Pinning

#### `GetPinnedScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `pinnedScriptPatchVersion`
- `pinnedAt` (timestamp)
- `pinnedBy` (actor principal, optional)

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
- On success, Game Session persists the new pin for `(tenantId, gameInstanceId)` and emits `ScriptPatchPinChanged`.

Outputs:

- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion` (the new value)
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
- On success, emits `ScriptPatchRollbackRequested` (or `ScriptPatchPinChanged` with `changeType=ROLLBACK`).

Outputs: same as `SetPinnedScriptPatchVersion`.

### Automation & Scripting: Patch Lifecycle Visibility

#### `GetScriptPatchStatus`

Implementation note: the current Automation & Scripting API exposes the runtime-readiness subset of this contract from durable `script_work_items`: `status`, `statusReason`, and `lastChangedAt`. The richer design-time compatibility fields such as `baseVersionId`, `abilitySchemaDigest`, and `supersededByScriptPatchVersion` remain target-state companion data from the publication/control-plane model rather than current response fields.

Inputs:

- `tenantId`
- `scriptPatchVersion`

Outputs:

- `tenantId`, `scriptPatchVersion`
- `status` (for example `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, `SUPERSEDED`)
- `statusReason` (optional)
- `baseVersionId` (required)
- `abilitySchemaDigest` (required for compatibility/audit surfaces)
- `supersededByScriptPatchVersion` (nullable; required when `status=SUPERSEDED`)
- `lastChangedAt`

Boundary rule:

- This API reports tenant runtime readiness only. Operator UIs that need to explain "published but not ready" must join this read with `GetPublishedScriptPatchVersion` instead of inventing a fused status enum.

#### `ListScriptPatchStatuses`

Inputs:

- `tenantId`
- Optional filters: `status`, `changedAfter`, `changedBefore`

Outputs:

- A list of `GetScriptPatchStatus` records.

#### `ListScriptDeadLetters`

Implementation note: the current Automation & Scripting API exposes this read directly from durable `script_work_items` rows with `status=DEAD_LETTERED`. It is an operator inspection surface separate from the controlled replay mutation API.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`
- `limit` (bounded by the service)

Outputs:

- Newest-first dead-letter entries containing `workItemId`, Trigger Identity fields, script/event identity, `status`, bounded failure/cancel reason, `createdAt`, and `updatedAt`.

Boundary rule:

- Operators use this read to decide whether a replay or manual remediation workflow is needed; replay itself remains a separate controlled operation so listing dead letters cannot accidentally mutate runtime state.

#### `ReplayDeadLetteredWorkItems`

Implementation note: the current Automation & Scripting implementation now exposes the first bounded replay mutation on top of durable `script_work_items`. Replay currently requeues eligible rows by setting `status=PENDING_EVALUATION`, clearing the terminal cancel reason, and recording `finalStage=REPLAY` plus `finalOutcome=requeued` on the handler-scoped audit row. Broader convergence and richer replay-policy signaling remain follow-up work.

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `regionId`, `scriptPatchVersion`, `createdAfterMs`, `createdBeforeMs`
- Optional explicit `workItemIds` (numeric durable work-item identifiers; when present, replay selection is limited to these rows)
- `limit` (bounded by the service)
- `controlPlaneRequestId`
- `actor`
- `reason`

Outputs:

- `replayedCount`
- `rejectedCount`

Contract rules:

- Replay is fail-closed per work item. A candidate row may be requeued only if the current Game Session runtime state still reports the same pinned `scriptPatchVersion` recorded on the dead-lettered work item.
- When the original ingress audit identifies a plugin-backed handler, replay is additionally allowed only if the currently active plugin version for `(tenantId, gameInstanceId, pluginId)` still matches the ingress-audited `pluginVersionId`.
- Rows that fail these checks remain `DEAD_LETTERED` and count toward `rejectedCount`; the operation does not partially mutate them into an intermediate state.
- Replay does not bypass later admission or runtime checks. Requeued rows re-enter the normal evaluation pipeline and may dead-letter again if the underlying failure condition still exists.

#### `GetScriptPatchInstanceRolloutStatus`

Inputs:

- `tenantId`
- `gameInstanceId`
- `scriptPatchVersion`

Outputs:

- `tenantId`, `gameInstanceId`, `scriptPatchVersion`
- `rolloutStatus` (for example `PINNED`, `ROLLED_BACK`, `REPINNED`)
- `statusReason` (optional)
- `lastChangedAt`
- `projectionAsOf` (timestamp of projection snapshot used for this read)
- `projectionLagMs` (non-negative projection staleness estimate)
- `isProjectionStale` (boolean; `true` when lag breaches published freshness SLO)

Read-model ownership:

- The authoritative source for rollout transitions is Game Session pin mutations and committed `ScriptPatchPinChanged` events.
- If Automation & Scripting serves this API, it does so as a projection that is replayable from control-plane events and keyed by `(tenantId, gameInstanceId, scriptPatchVersion, controlPlaneRequestId)` for idempotent updates.

#### `ListScriptPatchInstanceRollouts`

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `scriptPatchVersion`, `rolloutStatus`, `changedAfter`, `changedBefore`

Outputs:

- A list of `GetScriptPatchInstanceRolloutStatus` records.
- The read model must publish and enforce explicit freshness SLOs:
  - P95 `projectionLagMs <= 5000`
  - P99 `projectionLagMs <= 30000`
- Responses that breach the published SLO must set `isProjectionStale=true` and include a bounded stale reason code in `statusReason` (for example `projection_lag_exceeded`) so operators can distinguish stale read models from failed rollouts.

### Automation & Scripting: Plugin Lifecycle Management

Plugins are controlled by operators via Logging & Admin, but the runtime registry and enforcement live in Automation & Scripting. All mutating plugin operations must be idempotent and scoped to a running instance.

#### `GetPluginStatus`

Implementation note: the current Automation & Scripting implementation persists and serves the runtime registry for `(tenantId, gameInstanceId, pluginId)`, and `SetPluginActiveVersion` now consults the live Game Design `GetPublishedPluginVersion` read surface plus Game Session runtime-instance metadata before mutating that registry. That means design-time publication eligibility and `baseVersionId` compatibility are now enforced in the live control-plane path. Signature-policy enforcement, richer component-policy gating, and `abilitySchemaDigest` comparison against the running instance remain follow-up work rather than already-proven runtime checks.

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`

Outputs:

- `tenantId`, `gameInstanceId`, `pluginId`
- `activePluginVersionId` (nullable)
- `pendingPluginVersionId` (nullable)
- `pluginState` (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`)
- `statusReason` (optional; required for security/policy-driven disablement such as `signer_revoked`)
- `lastChangedAt`

Boundary rule:

- This API reports runtime state for one `(tenantId, gameInstanceId, pluginId)` only. It must not be overloaded to synthesize design-time publication status or signer-verification history from Game Design.

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
- Current implementation note: the live control-plane path now enforces `PUBLISHED` design-time state and `plugin.baseVersionId == runtimeVersionId` before updating the runtime registry. The `abilitySchemaDigest` comparison and the richer signer/component-policy gates remain target-state follow-through.
- On success, updates the registry for `(tenantId, gameInstanceId, pluginId)`, reconciles any durable plugin-owned schedules/timers so the displaced `pluginVersionId` cannot keep minting new triggers, and emits `PluginVersionActivated` (or `PluginVersionDisabled` as appropriate if this operation also transitions state).

Outputs:

- `previousPluginVersionId` (nullable)
- `activePluginVersionId`
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
- Transitions the plugin into a non-admitting state immediately.
- Triggers are rejected at admission with a dedicated outcome (for example `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`.
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

- Idempotent.
- Transitions the plugin to `DRAINING` so no new triggers are admitted while previously admitted work is allowed to complete within bounded limits.
- Emits `PluginVersionDisabled(newState=DRAINING)` (or a dedicated draining event if introduced later).

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
- `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK_PAUSE`
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
- Current ingress enforces `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` before durable work-item persistence and rejects oversized payloads with `TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED` / `work_item_size_exceeded`.
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
