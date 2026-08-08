# FireMUD Scripting & Automation: Control Plane Operations

This document defines the service participation and API-usage layer for scripting and automation control-plane changes. It covers pause/resume, drain/purge, dead-letter recovery, convergence reads, and operator audit flows. The canonical promotion and rollback sequence, state machine, timeouts, and degraded-operation policy live in [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md).

The direct API surface and request/response contracts for pinning, plugin activation, plugin drain, patch visibility, and admission outcomes live in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md).

## Implementation Status

The workflow APIs below are target-state contracts. In the target state, Automation & Scripting is the recovery owner for stale evaluation claims: it reads the durable descriptor-commit marker/status, replays committed descriptor rows without DSL re-entry, and dead-letters only stale claims with no committed marker/status under the runtime and normative lifecycle contracts. The current Automation claim boundary is `PENDING_EVALUATION` -> `EVALUATING`; it has no recovery owner, evaluation lease expiry/fencing generation, or descriptor-commit marker/status, and its recovery behavior is unimplemented and unverified. `GetAutomationDrainStatus` currently counts `EVALUATING` and `HANDOFF_IN_FLIGHT` rows in `activeExecutionCount`, including unresolved stale `EVALUATING` rows, and counts every handoff-capable `PENDING_EVALUATION` row in `pendingCancelableWorkItemCount`. These implementation facts do not change the fail-closed zero-count rule in the normative API below.

## Table of Contents

- [Scope](#scope)
- [Implementation Status](#implementation-status)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane Workflow APIs (Normative)](#control-plane-workflow-apis-normative)
- [Rollback and Recovery Workflow](#rollback-and-recovery-workflow)
- [Related Control Plane Contracts](#related-control-plane-contracts)

---

## Scope

This document covers:

- Tick pause/resume and admission pause/resume for rollback-safe orchestration.
- Rollback convergence reads and drain-status reads used to decide when it is safe to resume normal operation.
- Queue cleanup for script patch and plugin version changes.
- Outbox, dead-letter, and stuck-workitem recovery.
- Operator workflow APIs that orchestrate the above steps.

This document does not redefine the direct API request/response contracts or canonical admission outcome enums; see the API companion document for those shapes.

## Principles

- **Fail closed.** If the workflow cannot prove the current pin, drain, or signer-policy state, admission stays blocked.
- **Idempotent workflow steps.** Every orchestration action must be safe to retry with the same `controlPlaneRequestId`.
- **Instance-first scope.** Workflow actions must preserve `(tenantId, gameInstanceId)` isolation, with narrower scopes only when explicitly allowed.
- **Drain before resume.** Admission stays paused through convergence and cleanup. `ResumeTicks` succeeds first; only then may Automation return to `NORMAL`.
- **Audit every operator step.** Workflow actions must be visible in durable audit/logging surfaces so operators can reconstruct what happened.

## Actors and Responsibilities

- **Game Session Service**
  - Owns tick-scheduling pause/resume.
  - Owns the canonical rollback workflow state keyed by `controlPlaneRequestId`.
  - Produces the pin-change and rollback-timeout events used for operator visibility.

- **Automation & Scripting Service**
  - Owns admission pause/resume.
  - Owns drain-status reads, outbox cleanup, and dead-letter recovery.
  - Reconciles durable schedules, timers, and queued work after pin changes.

- **Logging & Admin Service**
  - Presents operator workflows and calls the underlying workflow APIs.
  - Must not become a competing source of truth for rollback state.

## Control Plane Workflow APIs (Normative)

The operations below are workflow APIs. Their direct contract shapes are intentionally kept separate from the direct API surface document.

Every mutating rollback or cleanup call in this document, including pause/resume, repin, cancel, and purge operations, requires `controlPlaneRequestId`, a non-blank `actor` principal, and a non-blank `reason`. Services validate all three before reading or mutating owned state; read-only convergence and status calls do not require these mutation fields.

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

`reason` is required and must be non-blank; Game Session validates it before reading the owned instance or purging queue/durable command state.

Semantics:

- Idempotent.
- Prevents new tick scheduling and new command intake for the scope.

#### `ResumeTicks`

Inputs: same scope model as `PauseTicks` + `controlPlaneRequestId` + `actor` + `reason`.

Semantics:

- Idempotent.
- Resumes normal scheduling after rollback/drain steps complete.

### Automation & Scripting: Admission Pause/Resume (Rollback Support)

Rollback requires an Automation-side admission barrier in addition to tick pause so new triggers are not admitted while control-plane cleanup is in progress. For mutating control-plane requests, `actor` identifies the authenticated requesting principal and is persisted in audit as `requestedBy`; it must not be reused for the worker that executes a system-owned step. Audit records for such steps use `executedBy=system:automation` as a separate field.

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
- `PAUSED_FOR_ROLLBACK` prevents admission of new external, scheduler, and timer triggers for the scope while allowing already-admitted work to be drained or canceled.
- During pause, ingress calls return explicit rollback backpressure outcomes and remain audit-visible.
- Entering `PAUSED_FOR_ROLLBACK` must also advance a scope-local **admission epoch**. Every already-admitted execution carries the epoch under which it was accepted, and any later outbox-persist or tick-handoff attempt must re-check that epoch before committing side effects.
- If an execution admitted under an earlier epoch reaches persist or handoff after the scope has advanced to a newer rollback epoch, it must not create new live work. The execution transitions to `finalOutcome=canceled` with a bounded `finalReason` such as `rollback_epoch_advanced` and remains visible in `script_event_audit`.

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
- Rollback orchestration uses this API together with cancel/purge hooks to decide when it is safe to resume normal admission. Drain is fail-closed: `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` are both required before normal admission or ticks resume; unresolved active or pending work keeps the workflow paused.

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
- `projectionAsOfMs`
- `projectionLagMs`
- `isProjectionStale`

Semantics:

- Read-only.
- Reports the latest pin observation used by admission and scheduler logic. For rollback convergence, the observation is an acknowledgment only when the expected patch and `controlPlaneRequestId` are present, `isProjectionStale=false`, and `projectionLagMs` is inside the configured freshness bound; a stale stored observation remains diagnostic data, not convergence proof. The freshness bound is the configured `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` value from [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md).
- Reports only pin observation and projection freshness; it does not return an admission decision, `finalStage`, `finalOutcome`, `finalReason`, or a handler outcome.

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
- Reports the latest pin observation used by tick command intake and execution-time version fences through a direct authoritative read of the committed Game Session pin.
- This is not a projection read; projection lag and stale-projection fields do not apply, and no projection fields are returned. For rollback/promotion convergence, the read is fresh only when it completes before the operation deadline, the observed patch and `lastObservedControlPlaneRequestId` match the expected patch and request, and `observedAt` is the timestamp from that committed pin.

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

- `reason` is required and must be non-blank; Game Session validates it before reading the owned instance or purging queue or durable command state.
- Idempotent.
- Under the shared queue mutation/tick lease, terminal-marks matching durable Game Session command rows before post-commit Redis queue/pending cleanup. Pre-batch commands use `executionOutcome = LOST_BEFORE_STAGING`; an explicitly retryable command with a durable prior tick-effect binding uses `executionOutcome = ABANDONED`. Both use `gameplayResult = NOT_APPLIED`, `failureCode = ROLLBACK_PURGED`, and the validated nonblank ingress `reason` as `failureMessage`.
- Batch-bound work that is not in the explicit retry queue is not purged through this hook; it requires effect-ledger remediation or rollback recovery because it has crossed the tick-batch boundary.
- Emits an operator-visible metric for purge activity and for version-fence drops (exact metric names and label sets follow the observability contract, including separate script and plugin version-fence metric families).

Outputs:

- `purgedCount` (count of durable command rows terminal-marked by the operation)

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

`reason` is required and must be non-blank; Game Session validates it before reading the owned instance or purging queue/durable command state.

Semantics and outputs: same as `PurgeQueuedTickCommandsForScriptPatch`, including the pre-batch `LOST_BEFORE_STAGING` versus durably batch-bound retry `ABANDONED` outcome split, `gameplayResult = NOT_APPLIED`, `failureCode = ROLLBACK_PURGED`, and the validated nonblank ingress `reason` as `failureMessage`, scoped to plugin-produced commands by the `pluginId` and `pluginVersionId` provenance carried from Automation into Game Session during handoff.

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
- Creates one durable replay record with a new execution identity for each eligible selected `DEAD_LETTERED` item. The original identity remains terminal and becomes causation evidence for the new replay; its audit row is never reopened or appended with replay execution history. The canonical identity and idempotency rules are defined in [Operator Replay of DEAD_LETTERED Work](./system-architecture-scripting-runtime-execution.md#operator-replay-of-dead_lettered-work).
- Repeating the same `controlPlaneRequestId` for the same original execution identity returns the previously created replay record/result rather than creating another replay identity.
- Must enforce bounded batch size per request.
- Must enforce replay eligibility against current control-plane state before creating the replay:
  - Work items with `scriptPatchVersion` that is not currently pinned for the scoped instance must be rejected from replay.
  - Plugin work items whose `(pluginId, pluginVersionId)` do not match currently active plugin state for the scoped instance must be rejected from replay.
  - Ineligible rows must return deterministic bounded application errors (for example `REPLAY_VERSION_FENCE_MISMATCH`), remain `DEAD_LETTERED`, and produce no replay identity.

Outputs:

- `replayedCount` (best-effort count; may be approximate for large batches)
- `results[]` (bounded to the selected input batch, with one result per selected item):
  - `originalExecutionIdentity`
  - `replayExecutionIdentity` when a replay identity was created or reused; identity semantics remain owned by [Operator Replay of DEAD_LETTERED Work](./system-architecture-scripting-runtime-execution.md#operator-replay-of-dead_lettered-work)
  - `outcome` (`created`, `reused`, or `rejected`)
  - `rejectionReason` when `outcome=rejected`, using a bounded application reason such as `REPLAY_VERSION_FENCE_MISMATCH`

For a repeated `controlPlaneRequestId` and original execution identity, `results[]` returns the same replay identity with `outcome=reused`; rejected items remain without a replay identity. `replayedCount` and this stable-request idempotency behavior are retained.

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

### Automation & Scripting: Plugin Drain Workflow

`DrainPlugin` is a direct control-plane API defined in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md#drainplugin). This workflow section defines how operators use that API in combination with convergence reads and cancellation APIs; it does not redefine the request/response contract.

### Logging & Admin: Operator Workflow APIs

Logging & Admin may expose a single high-level orchestration API (internally driving the lower-level calls above) for operators:

- `RequestScriptPatchRollback(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RequestScriptPatchPromotion(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`

If implemented, these APIs must remain thin orchestration and must not become another source of truth for the pinned version.

## Rollback and Recovery Workflow

The canonical rollback ordering, durable state machine, convergence timeout, drain gate, schedule reconciliation, and degraded-operation policy remain owned by [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#patch-rollback-operator-driven-required). Use that owner document for command ordering and state transitions. The [operations cookbook](./system-architecture-scripting-operations-cookbook.md#rollback-protocol-example-non-authoritative) is only a non-authoritative worked example and must not be treated as a source of command ordering.

This document retains only the participating API consequences:

- Automation & Scripting implements the admission barrier, durable schedule reconciliation, work-item cancellation, drain-status reads, pin-convergence reads, and auditable cleanup APIs described above.
- Game Session owns the durable rollback workflow and patch pin, and exposes tick pause/resume, queued-command purge, and convergence APIs. Logging & Admin may orchestrate those APIs but must not persist a competing workflow state machine.
- The same `controlPlaneRequestId`, authenticated operator `actor`/`requestedBy`, and `reason` flow through every mutating step. System-owned reconciliation records use a separate `executedBy=system:automation` rather than replacing the operator identity.
- Patch- and plugin-scoped cancel/purge calls omit `regionId` for an instance-wide repin so every affected region is covered. API retries remain idempotent under the owning request/response contracts.

Operationally, use control-plane APIs rather than direct data-store edits for pending and dead-lettered work:

- `ListOutboxWorkItems` for scoped inspection.
- `ReplayDeadLetteredWorkItems` for bounded replay of recoverable items.
- `PurgeOutboxWorkItems` for auditable cleanup of terminally invalid or stale items.

## Related Control Plane Contracts

- [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md) defines the direct request/response contracts, canonical errors, and admission rules.
- [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) defines promotion and rollback ordering, orchestration state, convergence, and degraded-operation policy.
- [Scripting & Automation: Control Plane Events](./system-architecture-scripting-control-plane-events.md) defines the durable event families emitted by the workflow APIs in this document.
- [Scripting Observability Contract](./system-architecture-scripting-observability-contract.md) defines telemetry fields, metric names, labels, and correlation guidance referenced by the workflow steps in this document; the normative audit tables and scripting lifecycle/rollout documents own audit stage/outcome semantics and final status transitions.
