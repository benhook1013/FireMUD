# FireMUD Scripting & Automation: Control Plane Operations

This document defines the workflow layer for scripting and automation control-plane changes. It covers rollback, pause/resume, drain/purge, dead-letter recovery, convergence checks, and operator audit flows.

The direct API surface and request/response contracts for pinning, plugin activation, plugin drain, patch visibility, and admission outcomes live in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md).

## Table of Contents

- [Scope](#scope)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane Workflow APIs (Normative)](#control-plane-workflow-apis-normative)
- [Rollback and Recovery Workflow](#rollback-and-recovery-workflow)
- [Rollback Orchestration State Machine (Required)](#rollback-orchestration-state-machine-required)
- [Pin-State Degraded Operations Policy (Required)](#pin-state-degraded-operations-policy-required)
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
- **Drain before resume.** Admission and tick processing only return to `NORMAL` after convergence and cleanup are complete.
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

### Automation & Scripting: Plugin Drain Workflow

`DrainPlugin` is a direct control-plane API defined in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md#drainplugin). This workflow section defines how operators use that API in combination with convergence reads and cancellation APIs; it does not redefine the request/response contract.

### Logging & Admin: Operator Workflow APIs

Logging & Admin may expose a single high-level orchestration API (internally driving the lower-level calls above) for operators:

- `RequestScriptPatchRollback(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RequestScriptPatchPromotion(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`

If implemented, these APIs must remain thin orchestration and must not become another source of truth for the pinned version.

## Rollback and Recovery Workflow

Rollback orchestration must prevent previously queued work from a rolled-back `scriptPatchVersion` from continuing to affect gameplay.

At a minimum, rollback consists of:

1. Fence new evaluation by pausing ticks and setting Automation admission to rollback-pause mode for the affected scope.
2. Repin the affected game instance(s) to the target `scriptPatchVersion` using the Game Session control-plane API.
3. Drain or purge queued script work items and staging entries that carry the rolled-back patch.
4. If plugin versions are also being rolled back, disabled, or revoked, cancel pending work for those `pluginVersionId` values before queue purge.
5. Verify pin convergence and drain completion before resuming normal admission.
6. Resume ticks only after the workflow state machine reaches a terminal completed state.

Concrete rollback sequence example:

1. Call `PauseTicks(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` so Game Session stops new tick scheduling and command intake for that instance.
2. Call `SetAutomationAdmissionMode(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` so new external and scheduler triggers are rejected with rollback backpressure.
3. Call `RollbackScriptPatchVersion(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, targetScriptPatchVersion=P21, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` to repin the instance to the known-good patch.
4. Poll `GetAutomationPinConvergence` and `GetGameSessionPinConvergence` until both report `observedPinnedScriptPatchVersion=P21` and the latest observed `controlPlaneRequestId=RB-42`.
5. Call `CancelPendingWorkItemsForPatch(tenantId=11111111-1111-4111-8111-111111111111, scriptPatchVersion=P22, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` and `PurgeQueuedTickCommandsForScriptPatch(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, scriptPatchVersion=P22, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`; then poll `GetAutomationDrainStatus` until `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the paused scope.
6. Call `SetAutomationAdmissionMode(..., mode=NORMAL, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` only after convergence and drain checks pass.
7. Call `ResumeTicks(..., controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` last so gameplay resumes only after both runtime services agree on the rollback target and old-version work has quiesced.

Operationally, use control-plane APIs rather than direct data-store edits for pending and dead-lettered work:

- `ListOutboxWorkItems` for scoped inspection.
- `ReplayDeadLetteredWorkItems` for bounded replay of recoverable items.
- `PurgeOutboxWorkItems` for auditable cleanup of terminally invalid or stale items.

## Rollback Orchestration State Machine (Required)

Rollback orchestration must expose and persist a state machine so partial failures are recoverable and retries are deterministic.

Ownership and source-of-truth requirements:

- Game Session is the producer-of-record for rollback orchestration state keyed by `controlPlaneRequestId`.
- Logging & Admin may expose convenience orchestration APIs, but these must call the Game Session workflow APIs and read back the same canonical workflow state; they must not persist a competing rollback-state machine.
- Automation & Scripting participates via idempotent step APIs (`SetAutomationAdmissionMode`, cancel/purge hooks, convergence reads) and must not infer orchestration completion from local state alone.

Required states:

- `PAUSING` -> `REPINNING` -> `CANCELING` -> `PURGING` -> `CONVERGING` -> `DRAINING` -> `RESUMING` -> `COMPLETED`
- Terminal failure state: `ROLLBACK_CONVERGENCE_TIMEOUT`

State rules:

- Each transition must be idempotent and keyed by `controlPlaneRequestId`.
- Re-running a request in the same state must return current state, not restart from scratch.
- Failures in `CANCELING` or `PURGING` must not auto-resume admission or ticks.
- Operator retries must continue from the last durable state.
- `ROLLBACK_CONVERGENCE_TIMEOUT` keeps admission and ticks paused until explicit operator action.
- `DRAINING` is required. Rollback must not resume admission or ticks until the current rollback-scope `admissionEpoch` has no active pre-pause executions and no remaining cancelable outbox work according to `GetAutomationDrainStatus`.

Convergence timeout semantics (required):

- Rollback orchestration must apply a bounded convergence timeout for the convergence step.
- If timeout is reached before both convergence APIs report the expected `controlPlaneRequestId`, the rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In `ROLLBACK_CONVERGENCE_TIMEOUT`, Automation admission remains paused for scope safety and ticks remain paused until an operator explicitly issues resume or abort actions.
- The system must emit terminal event `ScriptRollbackConvergenceTimedOut` and increment the corresponding rollback timeout metric.
- While timeout terminal state remains active, ingress admissions in scope must record a rollback timeout admission outcome and a bounded final reason.

## Pin-State Degraded Operations Policy (Required)

`pin_state_unavailable` is fail-closed by default. Any override mode must be explicit and tightly constrained:

- Override must be activated by an authenticated operator action with `controlPlaneRequestId`, `actor`, `reason`, and a bounded TTL.
- Override scope must be explicit (`tenantId` + `gameInstanceId` minimum).
- Override must emit control-plane audit and event records so post-incident reconciliation can prove exactly when fail-closed behavior was bypassed.
- On TTL expiry, fail-closed behavior (`pin_state_unavailable`) must resume automatically.

Notes:

- Even without an explicit purge, Game Session’s version fence prevents execution of commands produced under the rolled-back patch, but rollback must still drain and purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation such as restore, rollback data, or targeted admin operations.

## Related Control Plane Contracts

- [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md) defines the direct request/response contracts, canonical errors, and admission rules.
- [Scripting & Automation: Control Plane Events](./system-architecture-scripting-control-plane-events.md) defines the durable event families emitted by the workflow APIs in this document.
- [Scripting Observability Contract](./system-architecture-scripting-observability-contract.md) defines the metric names, labels, and audit surfaces referenced by the workflow steps in this document.
