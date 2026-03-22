# FireMUD Scripting & Automation: Control Plane API and Events

This document specifies the **control plane** operations and event contracts required to operate scripting and automation safely across Game Session, Automation & Scripting, Game Design, and Logging & Admin.

It exists to remove ambiguity from “conceptual APIs” referenced in service READMEs: this is the target-state contract that must be implemented in protos/services over time.

## Table of Contents

- [Scope](#scope)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane APIs (Normative)](#control-plane-apis-normative)
- [Control Plane Events (Normative)](#control-plane-events-normative)
- [Rollout and Rollback Protocols](#rollout-and-rollback-protocols)
- [Idempotency, AuthZ, and Audit](#idempotency-authz-and-audit)

---

## Scope

This document covers:

- Pinning and rolling back `scriptPatchVersion` for a running `gameInstanceId`.
- Patch lifecycle visibility (`READY`, `FAILED`, `SUPERSEDED`) plus per-instance rollout/rollback visibility as an operator-facing contract.
- Operational interactions needed for safe rollback (pause/resume, drain/purge).
- Plugin lifecycle operations (enable/disable/rollback) scoped to a running `gameInstanceId`, as part of the same operational surface as scripts.

This document does not define the designer-facing DSL, sandbox internals, or per-trigger runtime semantics (see the scripting DSL reference and sandbox runtime docs).

## Principles

- **Game Session owns tick safety.** Game Session is the only writer for `tick:*` and enforces the version fence at execution time. Automation never writes `tick:*` directly.
- **Pinned versions are explicit.** Runtime must never “auto-upgrade” to a newer patch without an operator/designer action captured in the control plane.
- **Control plane is idempotent.** Every mutating operation must accept a caller-provided `controlPlaneRequestId` and be safely retryable.
- **Auditable and observable.** Every mutating action must emit an audit entry and a durable status event that downstream tooling can consume.
- **Pin visibility is bounded-staleness.** Services that cache pinned patch/plugin versions must enforce a max staleness bound and fail closed on stale/unknown pin state for admission-critical decisions.
- **Runtime scope is instance-first.** Tenant-level patch readiness is only an eligibility gate; pause/resume, rollback convergence, timer ownership, and plugin lifecycle actions must preserve `(tenantId, gameInstanceId)` isolation.

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
  - Presents operator workflows for patch rollout/rollback and plugin management.
  - Drives changes by calling Game Session and Automation & Scripting APIs, never by writing Redis keys directly.
  - Consumes lifecycle and pin events to render status to operators.

## Control Plane APIs (Normative)

The API shapes below are described in gRPC-style terms but may be exposed via REST in operator-only deployments; the **fields and semantics are the contract**.

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

- Equivalent to `SetPinnedScriptPatchVersion` but semantically indicates rollback; tooling may treat it as higher urgency.
- Target patch readiness requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must be `READY` for the tenant or the request fails with a deterministic application error (`SCRIPT_PATCH_NOT_READY`).
- Base-version cohesion requirements are identical to `SetPinnedScriptPatchVersion`: rollback targets must have `baseVersionId` equal to the instance `runtimeVersionId` or the request fails with `SCRIPT_PATCH_BASE_VERSION_MISMATCH`.
- On success, emits `ScriptPatchRollbackRequested` (or `ScriptPatchPinChanged` with `changeType=ROLLBACK`).

Outputs: same as `SetPinnedScriptPatchVersion`.

### Game Session: Tick Pause/Resume (Rollback Support)

Rollback protocols require a coordination barrier so gameplay does not execute mixed-version work during the transition.

#### `PauseTicks`

Inputs:

- `tenantId`
- `gameInstanceId` (required for rollback-safe orchestration scope)
- Optional narrower scope: `regionId` (allowed only for targeted operational interventions, not as a substitute for full-instance rollback fencing)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Prevents new tick scheduling and new command intake for the scope.

#### `ResumeTicks`

Inputs: same scope model as `PauseTicks` + `controlPlaneRequestId` + `actor` + `reason`.

Semantics:

- Idempotent.
- Resumes normal scheduling after rollback/drain steps complete.

### Automation & Scripting: Admission Pause/Resume (Rollback Support)

Rollback requires an Automation-side admission barrier in addition to tick pause so new triggers are not admitted while control-plane cleanup is in progress.

#### `SetAutomationAdmissionMode`

Inputs:

- `tenantId`
- `gameInstanceId` (required for rollback-safe orchestration scope)
- Optional narrower scope: `regionId` (allowed only for targeted operational interventions, not as a substitute for full-instance rollback fencing)
- `mode` (`NORMAL` | `PAUSED_FOR_ROLLBACK`)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- `PAUSED_FOR_ROLLBACK` prevents admission of new external and scheduler triggers for the scope while allowing already-admitted work to be drained or canceled.
- During pause, ingress calls return explicit rollback backpressure outcomes (`finalOutcome=skipped_rollback_pause`) and remain audit-visible.
- Entering `PAUSED_FOR_ROLLBACK` must also advance a scope-local **admission epoch**. Every already-admitted execution carries the epoch under which it was accepted, and any later outbox-persist or tick-handoff attempt must re-check that epoch before committing side effects.
- If an execution admitted under an earlier epoch reaches persist or handoff after the scope has advanced to a newer rollback epoch, it must not create new live work. The execution transitions to a non-success canceled outcome and remains visible in `script_event_audit`.

#### `GetAutomationDrainStatus`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional narrower scope: `regionId`

Outputs:

- `tenantId`, `gameInstanceId`
- Optional `regionId`
- `admissionEpoch`
- `activeExecutionCount`
- `oldestActiveExecutionStartedAt` (nullable)
- `pendingCancelableWorkItemCount`
- `observedAt`

Semantics:

- Read-only.
- Reports whether any pre-pause executions or already-persisted work remain in the rollback scope after the current `admissionEpoch` took effect.
- Rollback orchestration uses this API together with cancel/purge hooks to decide when it is safe to resume normal admission.

### Rollback Convergence Readiness (Required)

Rollback orchestration must verify that runtime services have observed the new pin before admission/ticks resume.

#### `GetAutomationPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `lastObservedControlPlaneRequestId`
- `observedAt`

Semantics:

- Read-only.
- Reports the latest pin observation used by admission and scheduler logic.

#### `GetGameSessionPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `lastObservedControlPlaneRequestId`
- `observedAt`

Semantics:

- Read-only.
- Reports the latest pin observation used by tick command intake and execution-time version fences.

### Signer Policy Convergence (Required)

Signer-policy enforcement for plugins must be observable the same way pin convergence is observable.

#### `GetSignerPolicyConvergence`

Inputs:

- Optional scope: `tenantId`, `gameInstanceId`

Outputs:

- `serviceName`
- `serviceInstanceId` (optional for aggregated views)
- `observedSignerPolicyVersion`
- `observedAt`
- `refreshLagMs`
- `enforcementMode` (`REPORT_ONLY` | `ENFORCING`)

Semantics:

- Read-only.
- Used by operator tooling to confirm signer allowlist/revocation policy has propagated before declaring revocation complete.

### Game Session: Purge Queued Tick Commands (Rollback Support)

Rollback safety relies on execution-time fences, but operators also need a deterministic cleanup hook so queues do not accumulate mismatched entries after a pin/disable event.

#### `PurgeQueuedTickCommandsForScriptPatch`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional scope: `regionId`
- `scriptPatchVersion` (the patch version to remove from queues)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Removes (or moves to a bounded dead-letter store) any queued tick commands whose embedded `scriptPatchVersion` matches the supplied value for the scope.
- Emits an operator-visible metric for purge activity and for version-fence drops (exact metric names and label sets follow the observability contract, including separate script and plugin version-fence metric families).

Outputs:

- `purgedCount` (best-effort count; may be approximate for large batches)

#### `PurgeQueuedTickCommandsForPluginVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional scope: `regionId`
- `pluginId`
- `pluginVersionId` (the plugin version to remove from queues)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics and outputs: same as `PurgeQueuedTickCommandsForScriptPatch`, scoped to plugin-produced commands.

### Automation & Scripting: Patch Lifecycle Visibility

#### `GetScriptPatchStatus`

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

#### `ListScriptPatchStatuses`

Inputs:

- `tenantId`
- Optional filters: `status`, `changedAfter`, `changedBefore`

Outputs:

- A list of `GetScriptPatchStatus` records.

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

### Automation & Scripting: Drain/Purge Hooks (Rollback Support)

Rollback safety requires draining or invalidating pending automation work items produced under the rolled-back patch.

#### `CancelPendingWorkItemsForPatch`

Inputs:

- `tenantId`
- `scriptPatchVersion`
- Optional scope: `gameInstanceId`, `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Marks all pending outbox work items for the specified patch/scope as canceled so they are never handed off again.
- Emits an operator audit entry and increments a rollback-specific metric (exact metric names are defined by the observability contract).

Outputs:

- `canceledCount` (best-effort count; may be approximate for large batches)

### Automation & Scripting: Outbox and Dead-Letter Operations (Required)

These APIs provide deterministic operator hooks for stuck/canceled work so control-plane rollback and recovery do not depend on ad-hoc database access.

#### `ListOutboxWorkItems`

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `regionId`, `scriptPatchVersion`, `pluginId`, `pluginVersionId`, `workItemStatus`, `createdAfter`, `createdBefore`
- Pagination: `pageSize`, `pageToken`

Semantics:

- Read-only.
- Must support bounded pagination and stable sort order so large tenants can be inspected without full scans.

Outputs:

- `items[]` (including `outboxWorkItemId`, Trigger Identity, `workItemStatus`, `createdAt`, `updatedAt`, `cancelReason`)
- `nextPageToken`

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
- `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED`
- `TRIGGER_ADMISSION_OUTCOME_SCRIPT_DISABLED`
- `TRIGGER_ADMISSION_OUTCOME_PLUGIN_DISABLED`
- `TRIGGER_ADMISSION_OUTCOME_PLUGIN_COMPONENT_BLOCKED`
- `TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE`

Contract rules:

- Backpressure outcomes (`*_BACKPRESSURE_*`) must include bounded `retryAfterMs`.
- `admissionOutcome` and `admissionReason` describe the **event-scope ingress decision** only. They must not be interpreted as a summary of all handler-scoped outcomes created after binding resolution.
- Event-scope `admissionOutcome` and `admissionReason` must map 1:1 to the ingress-time admission result recorded in ingress audit/logging surfaces for that request.
- Admission failures are application-level outcomes and must not be surfaced as transport errors.
- For events that fan out to multiple handlers:
  - `admitted=true` means the request passed ingress-time fences and was accepted for handler resolution.
  - Per-handler Trigger Identities and outcomes are recorded asynchronously in `script_event_audit` (one row per resolved handler).
  - If all handlers later fail individually, the ingress response still remains `admitted=true`; callers do not retry based on those handler-level outcomes.
- Implementations may expose optional informational fields such as `resolvedHandlerCount`, but those fields must not replace per-handler audit records as the source of truth.

#### `ReplayDeadLetteredWorkItems`

Inputs:

- `tenantId`
- Optional scope: `gameInstanceId`, `regionId`
- Selector: explicit `outboxWorkItemIds[]` or bounded filter (`scriptPatchVersion`, `pluginVersionId`, `createdAfter`, `createdBefore`)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Transitions selected `DEAD_LETTERED` work items back to replayable state (`PENDING` or equivalent) without re-running DSL evaluation for original triggers.
- Must enforce bounded batch size per request.
- Must enforce replay eligibility against current control-plane state before transition:
  - Work items with `scriptPatchVersion` that is not currently pinned for the scoped instance must be rejected from replay.
  - Plugin work items whose `(pluginId, pluginVersionId)` do not match currently active plugin state for the scoped instance must be rejected from replay.
  - Ineligible rows must return deterministic bounded application errors (for example `REPLAY_VERSION_FENCE_MISMATCH`) and must remain `DEAD_LETTERED`.

Outputs:

- `replayedCount` (best-effort count; may be approximate for large batches)

#### `PurgeOutboxWorkItems`

Inputs:

- `tenantId`
- Optional scope: `gameInstanceId`, `regionId`
- Selector: explicit `outboxWorkItemIds[]` or bounded filter (`workItemStatus`, `scriptPatchVersion`, `pluginVersionId`, `createdBefore`)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Permanently removes selected outbox rows or marks them purged in bounded batches.
- Must emit operator-auditable records for every purge request.

Outputs:

- `purgedCount` (best-effort count; may be approximate for large batches)

#### `CancelPendingWorkItemsForPluginVersion`

Inputs:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- Optional scope: `gameInstanceId`, `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Marks pending outbox work items produced by the specified plugin version as canceled so they are never handed off again.
- Required for plugin disable/rollback/revocation workflows to avoid repeated execution-time plugin version fence drops and queue growth.

Outputs:

- `canceledCount` (best-effort count; may be approximate for large batches)

### Logging & Admin: Operator Workflow APIs

Logging & Admin may expose a single high-level orchestration API (internally driving the lower-level calls above) for operators:

- `RequestScriptPatchRollback(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RequestScriptPatchPromotion(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`

If implemented, these APIs must remain thin orchestration and must not become another source of truth for the pinned version.

## Control Plane Events (Normative)

All events must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry stable identity fields; include `controlPlaneRequestId` when operator-caused).
- Emitted only after the producing service commits its state change.

### Event Transport Contract (Required)

To keep control-plane behavior predictable, transport and ordering guarantees must be explicit:

- **Partition key (instance-scoped events)**: events scoped to a running instance (for example `ScriptPatchPinChanged`, `ScriptPatchInstanceRolloutChanged`, and plugin lifecycle events) must use `tenantId` + `gameInstanceId` so ordering is stable for that instance.
- **Partition key (tenant-scoped patch lifecycle events)**: tenant patch readiness events (`ScriptPatchTenantStatusChanged`) must use `tenantId` only.
- **Ordering**: consumers may assume per-partition order within each event family/scope, but must not assume global order across tenants or instances.
- **Monotonic sequencing (required)**:
  - All instance-scoped event families must carry `instanceSequence` (monotonic per `(tenantId, gameInstanceId)`).
  - Tenant-scoped patch readiness events must carry `tenantSequence` (monotonic per `tenantId`).
  - Read models must apply events by sequence (not arrival time) and ignore stale or duplicate sequence numbers.
- **Replay**: new consumers must be able to replay at least N days of control-plane events (or reconstruct state from durable service APIs) so operator UIs can be rebuilt without data loss.
- **Idempotency**: consumers must treat `controlPlaneRequestId` as the primary idempotency key for operator-driven events and must be safe under at-least-once delivery.

### `ScriptPatchPinChanged` (Game Session → Event Bus)

Emitted whenever the pinned patch changes.

Fields:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion`
- `changeType` (`SET` | `ROLLBACK`)
- `instanceSequence`
- `controlPlaneRequestId`
- `actor` and `reason`
- `occurredAt`

### `ScriptPatchRollbackRequested` (Game Session → Event Bus)

Optional dedicated event; if not used, `ScriptPatchPinChanged(changeType=ROLLBACK)` is required.

### `ScriptPatchTenantStatusChanged` (Automation & Scripting → Event Bus)

Emitted whenever tenant-scoped readiness lifecycle changes.

Fields:

- `tenantId`
- `scriptPatchVersion`
- `previousStatus`
- `newStatus`
- `causedBy` (`RUNTIME_VALIDATION` | `SYSTEM` | `OPERATOR`)
- `controlPlaneRequestId` (optional; required when `causedBy=OPERATOR`)
- `tenantSequence`
- `statusReason` (optional)
- `occurredAt`

Operator consumption rule:

- Use this event family for tenant patch readiness gates and publish validation UX (`READY`, `FAILED`, `SUPERSEDED`).

### `ScriptPatchInstanceRolloutChanged` (Game Session → Event Bus)

Emitted whenever instance rollout history changes for a patch.

Fields:

- `tenantId`
- `gameInstanceId`
- `scriptPatchVersion`
- `previousRolloutStatus`
- `newRolloutStatus` (`PINNED` | `ROLLED_BACK` | `REPINNED`)
- `causedBy` (`OPERATOR` | `SYSTEM`)
- `instanceSequence`
- `controlPlaneRequestId` (required when `causedBy=OPERATOR`)
- `statusReason` (optional)
- `occurredAt`

Operator consumption rule:

- Use this event family for instance rollout history, rollback audit trails, and per-instance pin progression.

### `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting → Event Bus)

Emitted when operator actions change plugin active versions or disablement state.

Fields:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `newState` (`ENABLED` | `DISABLED` | `DRAINING`)
- `instanceSequence`
- `controlPlaneRequestId` (if operator-driven)
- `actor` and `reason` (if operator-driven)
- `occurredAt`

### `SignerPolicyVersionObserved` (Automation & Scripting → Event Bus)

Emitted when Automation & Scripting observes or refreshes plugin signer policy for a scope.

Fields:

- `tenantId` (nullable for global policy snapshots)
- `serviceInstanceId`
- `observedSignerPolicyVersion`
- `observedAt`
- `policySource` (for example `signed_config_artifact`)

### `SignerRevocationApplied` (Automation & Scripting → Event Bus)

Emitted when signer revocation enforcement transitions one or more plugins to disabled state.

Fields:

- `tenantId`
- `gameInstanceId`
- `signerKeyId`
- `affectedPluginCount`
- `instanceSequence`
- `controlPlaneRequestId` (optional when operator-driven rollout change is correlated)
- `occurredAt`

### `ScriptRollbackConvergenceTimedOut` (Game Session → Event Bus)

Emitted when rollback orchestration reaches terminal state `ROLLBACK_CONVERGENCE_TIMEOUT` before both convergence APIs acknowledge the expected `controlPlaneRequestId`. Logging & Admin may initiate orchestration, but Game Session is the mandatory producer-of-record for this event.

Fields:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `instanceSequence`
- `controlPlaneRequestId`
- `timeoutMs`
- `reason` (bounded enum/code)
- `occurredAt`

## Rollout and Rollback Protocols

### Patch Promotion (Operator-Driven)

1. Validate patch is `READY` in Automation & Scripting for the tenant (`GetScriptPatchStatus`).
2. Call `SetPinnedScriptPatchVersion` in Game Session.
3. Game Session emits `ScriptPatchPinChanged`.
4. Call `CancelPendingWorkItemsForPatch` for the previous patch in scope so outbox work produced under displaced patch state cannot continue handing off indefinitely.
5. Call `PurgeQueuedTickCommandsForScriptPatch` for the previous patch (and plugin equivalents when plugin version changes are coupled with the promotion).
6. Automation & Scripting must reconcile durable schedules/timers for the newly pinned patch before timer admission resumes:
   - schedules absent from the newly pinned patch are removed or tombstoned;
   - schedules that still exist may be carried forward only through explicit reconciliation to the new version identity;
   - displaced patch/plugin versions must not be able to generate new `scriptEventId` values after promotion.
7. Wait for pin-convergence acknowledgments from both Automation & Scripting and Game Session for the requested `controlPlaneRequestId`.
8. Wait for `GetAutomationDrainStatus` to report `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the promotion scope under the current `admissionEpoch`.
9. Automation & Scripting observes the committed pin event for visibility (not for authority) and treats the pinned patch as the expected active one for tick handoffs.
10. Schedulers use a bounded-staleness pin cache for admission and timer firing decisions. If cached pin data is stale beyond the configured max-age, they must refresh from authoritative control-plane APIs/events before admitting new work. If fresh authoritative pin data cannot be obtained, admission must fail closed with `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`, and an explicit `finalReason`. If fresh authoritative pin data is available but differs from the request version for the instance, admission must fail closed with `finalOutcome=version_unavailable` and a bounded mismatch reason; Automation must not silently substitute a patch.

11. Operators monitor `script_event_audit` and automation metrics; per-event correlation uses `scriptEventId` in audit/logs/traces, not metric labels.

### Patch Rollback (Operator-Driven, Required)

1. Call `PauseTicks` for the affected scope.
2. Call `SetAutomationAdmissionMode(..., mode=PAUSED_FOR_ROLLBACK)` for the same scope.
3. Call `RollbackScriptPatchVersion` (or `SetPinnedScriptPatchVersion`) to repin to the target known-good patch.
4. Call `CancelPendingWorkItemsForPatch` in Automation & Scripting for the rolled-back patch (and optionally purge volatile coordination indexes).
5. If plugin versions are also being rolled back/disabled/revoked, call `CancelPendingWorkItemsForPluginVersion`.
6. Call `PurgeQueuedTickCommandsForScriptPatch` (and, if applicable, `PurgeQueuedTickCommandsForPluginVersion`) so mismatched queued entries do not accumulate after repin.
7. Automation & Scripting must reconcile durable schedules/timers before resuming admission:
   - timers owned by the displaced patch/plugin version are removed or tombstoned;
   - only schedules present in the rollback target may survive reconciliation;
   - cancellation of outbox work alone is not sufficient rollback cleanup.
8. Wait for pin-convergence acknowledgments from both Automation & Scripting and Game Session for the new pin (`controlPlaneRequestId` must match).
9. Wait for `GetAutomationDrainStatus` to report `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the rollback scope under the current `admissionEpoch`.
10. Call `SetAutomationAdmissionMode(..., mode=NORMAL)` once convergence and cleanup complete.
11. Resume ticks with `ResumeTicks`.

Concrete example:

- `tenantId=T1`, `gameInstanceId=G7`, current pin `P22`, rollback target `P21`, `controlPlaneRequestId=RB-42`.
- Step 1: `PauseTicks(T1, G7, RB-42)`.
- Step 2: `SetAutomationAdmissionMode(T1, G7, PAUSED_FOR_ROLLBACK, RB-42)`.
- Step 3: `RollbackScriptPatchVersion(T1, G7, P21, RB-42)`.
- Step 4: Poll `GetAutomationPinConvergence(T1, G7)` and `GetGameSessionPinConvergence(T1, G7)` until both report `observedPinnedScriptPatchVersion=P21` and `lastObservedControlPlaneRequestId=RB-42`.
- Step 5: Run patch/plugin-scoped cancel or purge hooks for displaced `P22` work, then poll `GetAutomationDrainStatus(T1, G7)` until active executions and cancelable pending work are both zero.
- Step 6: `SetAutomationAdmissionMode(T1, G7, NORMAL, RB-42)`.
- Step 7: `ResumeTicks(T1, G7, RB-42)`.

Ordering is intentional: Automation admission returns to `NORMAL` only after convergence and drain complete, and ticks resume last.

### Rollback Orchestration State Machine (Required)

Rollback orchestration must expose and persist a state machine so partial failures are recoverable and retries are deterministic.

Ownership and source-of-truth requirements:

- Game Session is the producer-of-record for rollback orchestration state keyed by `controlPlaneRequestId`.
- Logging & Admin may expose convenience orchestration APIs, but these must call the Game Session workflow APIs and read back the same canonical workflow state; they must not persist a competing rollback-state machine.
- Automation & Scripting participates via idempotent step APIs (`SetAutomationAdmissionMode`, cancel/purge hooks, convergence reads) and must not infer orchestration completion from local state alone.

Required states:

- `PAUSING` -> `REPINNING` -> `CANCELING` -> `PURGING` -> `CONVERGING` -> `DRAINING` -> `RESUMING` -> `COMPLETED`
- Terminal failure state: `TIMED_OUT`

State rules:

- Each transition must be idempotent and keyed by `controlPlaneRequestId`.
- Re-running a request in the same state must return current state, not restart from scratch.
- Failures in `CANCELING` or `PURGING` must not auto-resume admission or ticks.
- Operator retries must continue from the last durable state.
- `TIMED_OUT` keeps admission and ticks paused until explicit operator action.
- `DRAINING` is required. Rollback must not resume admission or ticks until the current rollback-scope `admissionEpoch` has no active pre-pause executions and no remaining cancelable outbox work according to `GetAutomationDrainStatus`.

Convergence timeout semantics (required):

- Rollback orchestration must apply a bounded convergence timeout (for example `ROLLBACK_CONVERGENCE_TIMEOUT_MS`) for step 7.
- If timeout is reached before both convergence APIs report the expected `controlPlaneRequestId`, the rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In `ROLLBACK_CONVERGENCE_TIMEOUT`, Automation admission remains paused for scope safety and ticks remain paused until an operator explicitly issues resume/abort actions.
- The system must emit terminal event `ScriptRollbackConvergenceTimedOut` and increment `automation_rollback_convergence_timeout_total{tenantId, gameInstanceId, reason}`.
- While timeout terminal state remains active, ingress admissions in scope must record `script_event_audit.finalStage=ADMISSION`, `finalOutcome=rollback_convergence_timeout`, and a bounded `finalReason`.

### Pin-State Degraded Operations Policy (Required)

`pin_state_unavailable` is fail-closed by default. Any override mode must be explicit and tightly constrained:

- Override must be activated by an authenticated operator action with `controlPlaneRequestId`, `actor`, `reason`, and a bounded TTL.
- Override scope must be explicit (`tenantId` + `gameInstanceId` minimum).
- Override must emit control-plane audit/event records so post-incident reconciliation can prove exactly when fail-closed behavior was bypassed.
- On TTL expiry, fail-closed behavior (`pin_state_unavailable`) must resume automatically.

Notes:

- Even without an explicit purge, Game Session’s version fence prevents execution of commands produced under the rolled-back patch, but rollback must still drain/purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation (restore, rollback data, or targeted admin operations).

## Idempotency, AuthZ, and Audit

- All mutating operations accept `controlPlaneRequestId` and must be safe to retry.
- All mutating operations require operator/admin authorization. Tenant-scoped operator actions must be auditable with actor identity and reason.
- Operator actions must be reflected in audit logs and in durable status events so UIs can reconstruct history.

For runtime trigger audit fields and metrics naming/label rules, see `design/architecture/system-architecture-scripting-observability-contract.md`.
