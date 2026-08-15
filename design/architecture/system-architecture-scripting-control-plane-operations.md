# FireMUD Scripting & Automation: Control Plane Operations

This document defines the service participation and API-usage layer for scripting and automation control-plane changes. It covers pause/resume, drain/purge, dead-letter recovery, convergence reads, and operator audit flows. The canonical promotion and rollback sequence, state machine, timeouts, and degraded-operation policy live in [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md).

The direct API surface and request/response contracts for pinning, plugin activation, plugin drain, patch visibility, and admission outcomes live in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md).

The accepted rollback boundary is epoch-fenced and does not routinely pause gameplay; see [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md). Game Session owns the exact pin tuple and rollout history ([ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md)); Automation owns only scoped admission, convergence, schedule, and recovery consequences. Stage-aware dead-letter recovery follows [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), and no degraded admission follows [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md).

## Normative Target Contract

The workflow APIs below are target-state contracts. Automation's durable work model has two layers: pre-DSL trigger state and evaluated descriptor/outbox evidence. A target evaluation atomically commits all emitted descriptors, parent outbox evidence, and the terminal evaluation outcome by transitioning the trigger to `EVALUATED_COMMITTED`; recovery replays those descriptors without DSL re-entry. `EVALUATED_COMMITTED` is not an active evaluation and is excluded from `activeExecutionCount`; unresolved descriptor children remain represented by their `PENDING`/`INDEXED` or `HANDOFF_IN_FLIGHT` descriptor states. `PENDING_EVALUATION` and `EVALUATING` remain pre-DSL states, while the existing evaluated-descriptor statuses retain their meanings.

Purge is evidence cleanup, not trigger recovery. It rejects `PENDING_EVALUATION` and `EVALUATING` triggers and all active/nonterminal descriptor rows. An `EVALUATED_COMMITTED` parent marker is not deleted; purge may remove only its retention-eligible terminal `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` descriptor/outbox evidence. It preserves the trigger marker, corresponding `script_event_audit`, and replay-causation claims or records, and never cancels, reclaims, dead-letters, or replays work.

## Implementation Status

This section records current behavior only. The current Automation claim boundary is `PENDING_EVALUATION` -> `EVALUATING`; it has no recovery owner, evaluation lease expiry/fencing generation, or descriptor-commit marker/status, and its recovery behavior is unimplemented and unverified. `GetAutomationDrainStatus` currently counts `EVALUATING` and `HANDOFF_IN_FLIGHT` rows in `activeExecutionCount`, including unresolved stale `EVALUATING` rows, and counts every handoff-capable `PENDING_EVALUATION` row in `pendingCancelableWorkItemCount`. Current terminal-row cleanup is retention-based; the target terminal-evidence purge rule is not current trigger-state recovery and does not establish that the current cleanup preserves an `EVALUATED_COMMITTED` marker, audit, or replay causation. These implementation facts are a cleanup-projection gap; they do not relax the target exact-artifact convergence and schedule-reconciliation gates for Automation admission.

## Table of Contents

- [Scope](#scope)
- [Normative Target Contract](#normative-target-contract)
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
- Rollback convergence reads used to decide when it is safe to resume normal operation, with drain-status reads retained as diagnostic cleanup progress.
- Queue cleanup for script patch and plugin version changes.
- Outbox, dead-letter, and stuck-workitem recovery.
- Operator workflow APIs that orchestrate the above steps.

This document does not redefine the direct API request/response contracts or canonical admission outcome enums; see the API companion document for those shapes.

## Principles

- **Fail closed.** If the workflow cannot prove the current pin, convergence, or signer-policy state, admission stays blocked; cleanup and drain projections are diagnostic and do not substitute for those gates.
- **Idempotent workflow steps.** Every orchestration action must be safe to retry with the same `controlPlaneRequestId`.
- **Instance-first scope.** Workflow actions must preserve `(tenantId, gameInstanceId)` isolation, with narrower scopes only when explicitly allowed.
- **Epoch fence before script resume.** Automation admission stays paused through exact-artifact preparation, serialized pin/epoch commit, and schedule reconciliation. Ordinary player commands and ticks continue; `PauseTicks`/`ResumeTicks` are exceptional controls for a specifically declared unfenced effect or migration, not routine script rollback.
- **Audit every operator step.** Workflow actions must be visible in durable audit/logging surfaces so operators can reconstruct what happened.

## Actors and Responsibilities

- **Game Session Service**
  - Owns tick-scheduling pause/resume.
  - Owns the canonical rollback workflow state keyed by `controlPlaneRequestId`.
  - Is the sole producer of the pin-change and rollback-timeout events used for operator visibility.

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
- This is not part of routine script rollback. It prevents new tick scheduling and command intake only for a separately declared unfenced effect family, migration, or explicit operational remediation whose smallest complete scope is recorded in the workflow.

#### `ResumeTicks`

Inputs: same scope model as `PauseTicks` + `controlPlaneRequestId` + `actor` + `reason`.

Semantics:

- Idempotent.
- Resumes the exceptional paused scope after its declared unfenced boundary and proof of tick/effect quiescence complete. It is not a prerequisite for ordinary script rollback completion.

### Automation & Scripting: Admission Pause/Resume (Rollback Support)

Rollback requires an Automation-side admission barrier so new script triggers are not admitted while the exact pin is repinned and schedules are reconciled. Cancellation, purge, and drain cleanup continue asynchronously and do not gate Automation resumption. Ordinary gameplay ticks continue; only a separately declared unfenced-effect workflow adds an exceptional tick pause. For mutating control-plane requests, `actor` identifies the authenticated requesting principal and is persisted in audit as `requestedBy`; it must not be reused for the worker that executes a system-owned step. Audit records for such steps use `executedBy=system:automation` as a separate field.

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
- During pause, ingress calls return explicit rollback backpressure outcomes and remain visible in the event-scope ingress audit; they do not create handler-scoped `script_event_audit` rows before handler resolution.
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
- Rollback orchestration uses this API to expose asynchronous cleanup progress. Drain counts do not gate Automation resumption or ordinary gameplay ticks; exact target-artifact convergence and schedule reconciliation are the admission gates. A cached, stale, or earlier-epoch response remains diagnostic rather than convergence evidence, and bounded cleanup may remain pending after `COMPLETED` under the displaced exact epoch fence.

### Rollback Convergence Readiness (Required)

Rollback orchestration must verify that runtime services have observed the new pin before Automation admission resumes. Ordinary gameplay ticks do not wait for this convergence check.

#### `GetAutomationPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `observedScriptPinEpoch`
- `lastObservedControlPlaneRequestId`
- `observedAt`
- `projectionAsOfMs`
- `projectionLagMs`
- `isProjectionStale`

Semantics:

- Read-only.
- Reports the latest pin observation used by admission and scheduler logic. For rollback convergence, the observation is an acknowledgment only when the expected `(scriptPatchVersion, scriptPinEpoch, controlPlaneRequestId)` tuple is present, `isProjectionStale=false`, and `projectionLagMs` is inside the configured freshness bound; a stale stored observation remains diagnostic data, not convergence proof. The freshness bound is the configured `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` value from [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md).
- Reports only pin observation and projection freshness; it does not return an admission decision, `finalStage`, `finalOutcome`, `finalReason`, or a handler outcome.

#### `GetGameSessionPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion`
- `scriptPinEpoch`
- `controlPlaneRequestId` (the committed pin mutation request represented by this authoritative Game Session read)
- `observedAt`

Semantics:

- Read-only.
- Reports the exact pin used by tick command intake and execution-time version fences through a direct authoritative read of the committed Game Session pin.
- This is not a projection read; projection lag and stale-projection fields do not apply, and no projection fields are returned. For rollback/promotion convergence, the read is fresh only when it completes before the operation deadline and the exact `(scriptPatchVersion, scriptPinEpoch, controlPlaneRequestId)` matches the expected tuple; `observedAt` is the timestamp from that committed pin.

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
- `scriptPatchVersion` (the displaced patch version to remove from queues)
- `scriptPinEpoch` (the displaced pin epoch; the operation must reject rows from any other epoch)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- `reason` is required and must be non-blank; Game Session validates it before reading the owned instance or purging queue or durable command state.
- Idempotent.
- Under the shared queue mutation/tick lease, terminal-marks matching durable Game Session command rows before post-commit Redis queue/pending cleanup. Pre-batch commands use `executionOutcome = LOST_BEFORE_STAGING`; an explicitly retryable command with a durable prior tick-effect binding uses `executionOutcome = ABANDONED`. Both use `gameplayResult = NOT_APPLIED`, `failureCode = ROLLBACK_PURGED`, and the validated nonblank ingress `reason` as `failureMessage`.
- Batch-bound work that is not in the explicit retry queue is not purged through this hook; it requires effect-ledger remediation or rollback recovery because it has crossed the tick-batch boundary.
- Emits an operator-visible metric for purge activity and for version-fence drops; exact metric names, labels, and increment units follow [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix), while audit and handoff diagnostics follow the observability contract.

Outputs:

- `purgedCount` (count of durable command rows terminal-marked by the operation)

#### `PurgeQueuedTickCommandsForPluginVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional scope: `regionId`
- `pluginId`
- `pluginVersionId` (the plugin version to remove from queues)
- `scriptPatchVersion` (the displaced script patch version carried by the command)
- `scriptPinEpoch` (the displaced pin epoch; the operation must reject rows from any other epoch)
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
- `scriptPinEpoch` (the displaced pin epoch; cancellation must match the stored exact tuple)
- Optional scope: `gameInstanceId`, `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Applies the canonical two-layer cancellation mapping: `PENDING_EVALUATION` transitions to terminal `CANCELED` without DSL evaluation with `finalStage=ADMISSION`, `finalOutcome=canceled`; `EVALUATING` is fenced and its descriptor-commit marker is inspected, with committed descriptors continuing through descriptor cancellation, explicit no-descriptor cancellation using `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and stale recovery using terminal `DEAD_LETTERED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`; evaluated descriptors in `PENDING` or `INDEXED` transition to `CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`. No path re-enters the DSL.
- Cancellation selects only rows whose stored `(scriptPatchVersion, scriptPinEpoch)` exactly matches the displaced tuple. A same-version repin with a newer epoch is not eligible for this request. Emits an operator audit entry and applies the rollback-specific metric consequence defined by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix).

Outputs:

- `canceledCount` (best-effort count; may be approximate for large batches)

### Automation & Scripting: Outbox and Dead-Letter Operations (Required)

These APIs provide deterministic operator hooks for stuck/canceled work so control-plane rollback and recovery do not depend on ad-hoc database access.

The persisted `outboxWorkItemId` and its public/current wire aliases (`workItemId` and `automationWorkItemId`) are parent correlation and the bounded mutation selector for this API family, not command identity. Mutation requests accept bounded explicit `workItemIds[]` only; descriptor references and filter-based mutation/replay are deferred until preview plus stable per-row proof exists. The current replay mutation selects parent `workItemIds` and requeues eligible `script_work_items` rows; it does not select independent command descriptors or assign a synthetic command identity. Read-only listing may expose descriptor identity for diagnosis, but that reference is not a mutation selector.

#### `ListOutboxWorkItems`

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `regionId`, `scriptPatchVersion`, `scriptPinEpoch`, `pluginId`, `pluginVersionId`, `workItemStatus`, `createdAfter`, `createdBefore`
- Pagination: `pageSize`, `pageToken`

Semantics:

- Read-only.
- Must support bounded pagination and stable sort order so large tenants can be inspected without full scans.

Outputs:

- `items[]` (including `workItemId`, the public alias for canonical `outboxWorkItemId`, the exact stored `scriptPatchVersion` and `scriptPinEpoch`, optional descriptor identity for diagnosis when the row is an evaluated descriptor, `workItemStatus`, `createdAt`, `updatedAt`, `cancelReason`). For pre-DSL `PENDING_EVALUATION` or `EVALUATING` rows, descriptor identity is omitted/null because no command identity exists.
- `nextPageToken`

#### `ReplayDeadLetteredWorkItems`

Dead-letter recovery is stage-aware. The bounded explicit `workItemIds[]` selector and per-row outcomes below are the target contract; descriptor references and filter-based mutation remain deferred until preview plus stable per-row proof. The current parent-row requeue implementation is an explicit proof gap and must not be described as recovery of post-evaluation output.

Inputs:

- `tenantId`
- Optional scope: `gameInstanceId`, `regionId`
- Selector: bounded explicit `workItemIds[]` only; filters and descriptor references are listing/preview inputs, not mutation selectors
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Repeating the same `controlPlaneRequestId` for the same `workItemId` and canonical request fingerprint returns the previously recorded recovery result rather than creating another recovery attempt or changing the original identity.
- Must enforce bounded batch size per request.
- Each selected work item is recovered under its stored stage evidence. Evaluation-stage retry invokes the DSL evaluator again using the original work-item and `scriptEventId` identity, frozen input/manifest evidence, and exact admitted immutable graph; it remains outside the normal admission path, creates no new `automationDispatchId`, and must converge on the original work item and child identities. Post-evaluation recovery resumes the stored child-dispatch ledger without invoking the DSL, preserving its original child identities, payload digests, ordinals, and acknowledgements; accepted or terminal children no-op and only unfinished children dispatch. Neither stage regenerates an uncorrelated logical trigger.
- Reusing a `controlPlaneRequestId` with a different canonical request fingerprint returns an idempotency conflict and cannot alter the stored recovery result. An exact retry returns the same per-work-item result; it does not reopen the original audit row.
- Resolves each selected `workItemId` against its own stored trigger/work-item identity and stage evidence atomically. A parent with multiple committed descriptors is recovered from its stored child ledger; descriptor references are not accepted as selectors and filters are not expanded into a mutation batch.
- Every selected work item must match the request `tenantId` and any explicit `gameInstanceId` or `regionId` scope using its own stored identity. The operation must resolve the authoritative current tuple for that instance and must not borrow another instance's pin or binding.
- For plugin-backed work, resolve the current binding for that same tenant, instance, and plugin, then compare the stored immutable `(pluginId, pluginVersionId, bindingId)` tuple to that binding.
- Must enforce recovery eligibility against current authoritative state before progressing:
  - Work items with `(scriptPatchVersion, scriptPinEpoch)` that is not currently pinned for the scoped instance must be rejected from recovery, including a repin to the same patch with a new epoch.
  - Plugin work items whose immutable `(pluginId, pluginVersionId, bindingId)` tuple does not match the currently active binding for the same scoped `<tenantId, gameInstanceId, pluginId>` must be rejected from replay.
- Ineligible rows must return `outcome=rejected` with the specific bounded `rejectionReason` that explains the failed fence or evidence check, remain `DEAD_LETTERED`, and produce no recovery record.

Outputs:

- `replayedCount` (bounded count of selected work items that progressed)
- `results[]` (bounded to the selected input batch, with one result per selected `workItemId`):
  - `workItemId`
  - `recoveryStage` (`EVALUATION` or `POST_EVALUATION_DISPATCH`)
  - `outcome` (`retried_evaluation`, `resumed_dispatch`, `already_recovered`, or `rejected`)
  - `rejectionReason` only when `outcome=rejected`, using bounded values such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `script_pin_epoch_mismatch`, `plugin_binding_mismatch`, or `runtime_scope_mismatch`

For an exact retry of the same `controlPlaneRequestId`, canonical request fingerprint, and selected IDs, `results[]` returns the identical stored per-work-item outcomes, including `rejected`, without new work or relabeling. A new request with a different `controlPlaneRequestId` that finds a row already recovered by an earlier successful request returns `outcome=already_recovered`; rejected work items remain without a recovery record. `replayedCount` and this stable-request idempotency behavior are retained.

Ineligible rows remain `DEAD_LETTERED` and produce no recovery record. Their rejected result is not a success claim, so a later request may retry after authoritative evidence or the exact pin/epoch becomes eligible. Missing or contradictory stage evidence remains dead-lettered.

#### `PurgeOutboxWorkItems`

Inputs:

- `tenantId`
- Optional scope: `gameInstanceId`, `regionId`
- `scriptPatchVersion` (the displaced patch version; required for rollback-scoped cleanup)
- `scriptPinEpoch` (the displaced pin epoch; required for rollback-scoped cleanup and enforced against each selected row)
- Selector: bounded explicit `workItemIds[]` only; descriptor references and filters are listing/preview inputs, not mutation selectors
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Resolves each selected `workItemId` against durable trigger and descriptor/outbox state before mutating anything. For rollback-scoped cleanup, every selected row must match the request's exact displaced `(scriptPatchVersion, scriptPinEpoch)` tuple; a same-version repin with a newer epoch is rejected. A row in `PENDING_EVALUATION` or `EVALUATING`, or an evaluated descriptor in `PENDING`, `INDEXED`, or `HANDOFF_IN_FLIGHT`, is rejected with a bounded application reason; an `EVALUATED_COMMITTED` parent is resolved as retained trigger evidence rather than rejected wholesale. Purge does not cancel, reclaim, dead-letter, replay, or otherwise mutate active/nonterminal work.
- May purge only a coherent retained evidence bundle whose terminal evaluated descriptor/outbox evidence is in `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` status after the configured age/capacity eligibility, including terminal children under an `EVALUATED_COMMITTED` parent. A row advertised as recoverable and all of its stage, manifest, evaluated-output, and child-dispatch evidence must remain one bundle; cleanup must not delete supporting evidence independently while leaving the row recoverable. Terminal evidence still inside its retention window is rejected with a bounded retention reason, and incoherent evidence fails closed. Explicit purge follows the same whole-bundle rule, while the `EVALUATED_COMMITTED` marker, corresponding `script_event_audit`, and replay-causation evidence remain preserved under their owning retention contracts after purge.
- Must emit operator-auditable records for every purge request.

Outputs:

- `purgedCount` (best-effort count; may be approximate for large batches)

#### `CancelPendingWorkItemsForPluginVersion`

Inputs:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- `scriptPatchVersion`
- `scriptPinEpoch` (the displaced pin epoch; cancellation must match the stored exact tuple)
- Optional scope: `gameInstanceId`, `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- **Target-state semantics:** cancellation is fencing-aware across both durable layers. `PENDING_EVALUATION` transitions by compare-and-set to `CANCELED` without entering the DSL. `EVALUATING` is fenced and its descriptor-commit marker is inspected first; a committed descriptor is resumed from durable descriptors without DSL re-entry, while an explicit cancellation with no committed descriptor transitions the trigger to `CANCELED` with `finalStage=DSL_EVAL` and stale recovery uses the canonical `DEAD_LETTERED` mapping. Evaluated descriptors in eligible `PENDING` or `INDEXED` status transition to `workItemStatus=CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`. Each transition records the corresponding stage-aware `script_event_audit` cancellation outcome. The current implementation lacks the descriptor layer and recovery owner, so these are target-state semantics rather than current live proof.
- Cancellation selects only rows whose stored `(scriptPatchVersion, scriptPinEpoch)` exactly matches the displaced tuple. A same-version repin with a newer epoch is not eligible. Required for plugin disable/rollback/revocation workflows to avoid repeated execution-time plugin version fence drops and queue growth.

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

The canonical rollback ordering, durable state machine, convergence timeout, diagnostic cleanup progress, schedule reconciliation, and degraded-operation policy remain owned by [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#patch-rollback-operator-driven-required). Use that owner document for command ordering and state transitions. The [operations cookbook](./system-architecture-scripting-operations-cookbook.md#rollback-protocol-example-non-authoritative) is only a non-authoritative worked example and must not be treated as a source of command ordering.

This document retains only the participating API consequences:

- Automation & Scripting implements the admission barrier, durable schedule reconciliation, work-item cancellation, drain-status reads, pin-convergence reads, and auditable cleanup APIs described above.
- Game Session owns the durable rollback workflow, exact patch/epoch pin, and rollout history, and is the sole producer of the rollback-timeout signal. Routine rollback advances the script epoch while ordinary ticks/player commands continue; Automation consumes the durable timeout state/signal and must not create a competing timeout or recovery state machine. Logging & Admin may orchestrate those APIs but must not persist a competing workflow state machine.
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
- [Scripting Observability Contract](./system-architecture-scripting-observability-contract.md) defines audit and handoff diagnostics referenced by the workflow steps in this document; [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) owns metric names, labels, and increment units, while the normative audit tables and scripting lifecycle/rollout documents own audit stage/outcome semantics and final status transitions.
