# FireMUD Scripting & Automation: Control Plane Operations

This document defines the service participation and API-usage layer for scripting and automation control-plane changes. It covers pause/resume, drain/purge, dead-letter recovery, convergence reads, and operator audit flows. The canonical promotion and rollback sequence, state machine, timeouts, and degraded-operation policy live in [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md).

The direct API surface and request/response contracts for pinning, plugin activation, plugin drain, patch visibility, and admission outcomes live in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md).

The accepted rollback boundary is epoch-fenced and does not routinely pause gameplay; see [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md). Game Session owns the exact pin tuple and rollout history ([ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md)); Automation owns only scoped admission, convergence, schedule, and recovery consequences. Stage-aware dead-letter recovery follows [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), no degraded admission follows [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), emergency component revocation follows [ADR 0116](./decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md), and per-instance plugin activation fencing follows [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md).

## Normative Target Contract

The workflow APIs below are target-state contracts. Automation's durable work model has two layers: pre-DSL trigger state and evaluated descriptor/outbox evidence, with a separate parent recovery aggregate for whether retained evidence is currently dead-lettered. A target evaluation atomically commits all emitted descriptors, parent outbox evidence, and the terminal evaluation outcome by transitioning the trigger to the retained `EVALUATED_COMMITTED` descriptor marker; recovery replays those descriptors without DSL re-entry. If a permanent required-child failure makes the committed output eligible for post-evaluation recovery, the parent recovery aggregate atomically transitions to `DEAD_LETTERED` while retaining that marker and complete output/child ledger. `EVALUATED_COMMITTED` is not an active evaluation and is excluded from `activeExecutionCount`; unresolved descriptor children remain represented by their `PENDING`/`INDEXED` or `HANDOFF_IN_FLIGHT` descriptor states. `PENDING_EVALUATION` and `EXECUTING` remain pre-DSL states, while the existing evaluated-descriptor statuses retain their meanings.

Purge is evidence cleanup, not trigger recovery. It rejects `PENDING_EVALUATION` and `EXECUTING` triggers and all active/nonterminal descriptor rows. For this operation, `retention-eligible` means that every applicable recovery, rollback-diagnostic, command-status, audit, and downstream-replay horizon for the evidence has elapsed; age or capacity cleanup alone is insufficient. An `EVALUATED_COMMITTED` parent marker is not deleted; purge may remove its retention-eligible descriptor/outbox/evaluated-output evidence only after every child under that parent is terminal (`HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED`) and every eligible evidence item is purged atomically. If any sibling is `PENDING`, `INDEXED`, `HANDOFF_IN_FLIGHT`, otherwise nonterminal, or not retention-eligible, the entire parent purge is rejected and no `descriptorEvidencePurgedAt` marker is written. A successful purge persists that marker in the same transaction. A marked parent is non-recoverable and non-replayable, and consumers reject it deterministically rather than treating missing children as recoverable. The trigger marker, corresponding `script_event_audit`, and replay-causation claims or records remain retained under their owning contracts, and purge never cancels, reclaims, dead-letters, or replays work.

Separately, a terminal pre-DSL `DEAD_LETTERED` row with no committed descriptor may have only retention-eligible trigger/failure payload material purged after its recovery eligibility window has expired. No new schema marker is introduced for this path. The retained purge audit/idempotency outcome is the durable evidence of the purge and makes the row permanently non-recoverable; any later replay attempt is rejected deterministically with `rejectionReason=stage_evidence_unavailable`. Its immutable identity/digest, failure-stage, corresponding `script_event_audit`, recovery-causation evidence, and audit/idempotency outcomes remain retained under their owning retention contracts.

## Implementation Status

This section records current behavior only. The current Automation claim boundary is `PENDING_EVALUATION` -> `EVALUATING`; it has no recovery owner, evaluation lease expiry/fencing generation, or descriptor-commit marker/status, and its recovery behavior is unimplemented and unverified. The current replay mutation still accepts optional `gameInstanceId`/`regionId` scope fields and empty-ID selection, selects parent `workItemIds`, requeues eligible `script_work_items` rows as `PENDING_EVALUATION`, and returns aggregate counts instead of target per-row recovery results; it does not select independent command descriptors or prove post-evaluation recovery. `GetAutomationPinConvergence` currently serves the durable `ScriptPatchPinProjection`/`script_patch_pin_projections` view without `observedScriptPinEpoch` or `observedConvergenceAttemptGeneration`; that patch-only observation is diagnostic/incomplete evidence, so missing `observedScriptPinEpoch` keeps exact-tuple admission/replay fail-closed, while missing `observedConvergenceAttemptGeneration` separately prevents attempt-bound convergence proof. Expanding those fields in runtime/proto code is an implementation follow-up outside this documentation parcel. `GetAutomationDrainStatus` currently counts `EVALUATING` and `HANDOFF_IN_FLIGHT` rows in `activeExecutionCount`, including unresolved stale `EVALUATING` rows, and counts every handoff-capable `PENDING_EVALUATION` row in `pendingCancelableWorkItemCount`; both counts are within the requested scope, and while `PAUSED_FOR_ROLLBACK` include only pre-pause rows with `workItem.admissionEpoch < current admissionEpoch`; normal mode counts scoped rows. Current terminal-row cleanup is retention-based; the target terminal-evidence purge rule is not current trigger-state recovery and does not establish that the current cleanup preserves an `EVALUATED_COMMITTED` marker, audit, or replay causation. These implementation facts are a cleanup-projection gap; they do not relax the target exact-artifact convergence and schedule-reconciliation gates for Automation admission.

## Table of Contents

- [Scope](#scope)
- [Normative Target Contract](#normative-target-contract)
- [Implementation Status](#implementation-status)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane Workflow APIs (Normative)](#control-plane-workflow-apis-normative)
- [Emergency Component Revocation Workflow (Required)](#emergency-component-revocation-workflow-required)
- [Pin Transition and Recovery Workflow](#pin-transition-and-recovery-workflow)
- [Related Control Plane Contracts](#related-control-plane-contracts)

---

## Scope

This document covers:

- Tick pause/resume and admission pause/resume for pin-transition orchestration, with tick pause remaining exceptional.
- Explicit emergency component revocation containment for affected Automation scopes.
- Pin convergence reads used to decide when it is safe to resume normal operation after promotion or rollback, with drain-status reads retained as diagnostic cleanup progress.
- Queue cleanup for script patch and plugin version changes.
- Outbox, dead-letter, and stuck-workitem recovery.
- Operator workflow APIs that orchestrate the above steps.

This document does not redefine the direct API request/response contracts or canonical admission outcome enums; see the API companion document for those shapes.

## Principles

- **Fail closed.** If the workflow cannot prove the current pin, convergence, or signer-policy state, admission stays blocked; cleanup and drain projections are diagnostic and do not substitute for those gates.
- **Idempotent workflow steps.** Every orchestration action must be safe to retry with the same `controlPlaneRequestId`.
- **Instance-first scope.** Workflow actions must preserve `(tenantId, gameInstanceId)` isolation, with narrower scopes only when explicitly allowed.
- **Epoch fence before script resume.** Optional exact target-artifact preparation occurs while the current pin remains active; when used, Automation persists and returns the immutable preparation evidence defined by [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), and Game Session revalidates the current tenant-`READY` state, artifact digest, readiness revision, and applicable policy revisions at pin commit. Without preparation, Game Session performs the equivalent authoritative commit-time checks. Only after preparation succeeds, when that path is used, does Automation pause scoped script admission for serialized pin/epoch commit and schedule reconciliation. Ordinary player commands and ticks continue; `PauseTicks`/`ResumeTicks` are exceptional controls for a specifically declared unfenced effect or migration, not routine script rollback.
- **Audit every operator step.** Workflow actions must be visible in durable audit/logging surfaces so operators can reconstruct what happened.

## Actors and Responsibilities

- **Game Session Service**
  - Owns tick-scheduling pause/resume.
  - Owns the canonical pin-transition workflow state keyed by `controlPlaneRequestId` and immutable `operationKind`.
  - Is the sole producer of the pin-change and pin-convergence-timeout events used for operator visibility.

- **Automation & Scripting Service**
  - Owns admission pause/resume.
  - Owns drain-status reads, outbox cleanup, and dead-letter recovery.
  - Reconciles durable schedules, timers, and queued work after pin changes.

- **Logging & Admin Service**
  - Presents operator workflows and calls the underlying workflow APIs.
  - Must not become a competing source of truth for pin-transition state.

## Control Plane Workflow APIs (Normative)

The operations below are workflow APIs. Their direct contract shapes are intentionally kept separate from the direct API surface document.

Every mutating pin-transition or cleanup call in this document, including pause/resume, repin, cancel, and purge operations, requires `controlPlaneRequestId`, a non-blank `actor` principal, and a non-blank `reason`. Services validate all three before reading or mutating owned state; read-only convergence and status calls do not require these mutation fields.

### Game Session: Tick Pause/Resume (Rollback Support)

Only a separately declared unfenced effect family, migration, or explicitly scoped remediation requires a coordination barrier so gameplay does not execute mixed-version work during that exceptional transition; routine script rollback uses epoch fencing while ordinary gameplay continues.

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

- Idempotency is scoped to each normalized scope-and-mode step under the workflow-scoped request ID: an exact retry of a given step, including its other normalized request fields, returns that step's stored result without reapplying the transition. The same workflow-scoped ID deliberately spans the distinct `PAUSED_FOR_ROLLBACK` and `NORMAL` steps; that mode transition is not an idempotency misuse.
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
- `admissionMode` (`NORMAL` | `PAUSED_FOR_ROLLBACK`)
- `admissionEpoch`
- `activeExecutionCount`
- `oldestActiveExecutionStartedAt` (nullable)
- `pendingCancelableWorkItemCount`
- `observedAt`

Semantics:

- Read-only.
- Reports whether any pre-pause executions or already-persisted work remain in the rollback scope after the current `admissionEpoch` took effect.
- For authoritative pause/resume readback, the returned tenant, instance, and optional region scope must match the requested scope, `admissionMode` must match the requested mode, `admissionEpoch` must be current for that scope, and `observedAt` must be fresh. Missing, stale, earlier-epoch, or contradictory readback is diagnostic only and cannot authorize workflow advancement or completion. The microservice-local API contract documents the same projection; Automation's durable admission state remains the authority.
- Rollback orchestration uses this API to expose asynchronous cleanup progress. Drain counts do not gate Automation resumption or ordinary gameplay ticks; the [Pin Convergence Acknowledgment Predicate](./system-architecture-scripting-rollout-and-rollback.md#pin-convergence-acknowledgment-predicate), including fresh exact-tuple acknowledgments from `GetAutomationPinConvergence` and `GetGameSessionPinConvergence`, exact target-artifact convergence, and schedule reconciliation are the admission gates. For a scope with applicable plugin-backed admission, fresh signer-policy convergence is an additional admission gate: missing, stale, revoked, or otherwise fail-closed signer evidence keeps plugin admission blocked and cannot be replaced by drain counts. A cached, stale, or earlier-epoch response remains diagnostic rather than convergence evidence, and bounded cleanup may remain pending after `COMPLETED` under the displaced exact epoch fence.

### Pin Convergence Readiness (Required)

Promotion and rollback orchestration must verify that runtime services have observed the new pin before Automation admission resumes. Ordinary gameplay ticks do not wait for this convergence check.

#### `GetAutomationPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` (nullable; absent only for a never-pinned semantic `UNPINNED`)
- `observedScriptPinEpoch` (nullable; absent only for a never-pinned semantic `UNPINNED`)
- `observedConvergenceAttemptGeneration` (positive generation observed with the exact pair and request identity; absent when no workflow observation exists)
- `lastObservedControlPlaneRequestId` (nullable; absent only when the instance has never been pinned and the observed pair is semantic `UNPINNED`; retained with a pinned pair when the projection is stale, representing the last committed pin mutation associated with the observation)
- `observedAt`
- `projectionAsOfMs`
- `projectionLagMs`
- `isProjectionStale`

Semantics:

- Read-only.
- Reports the latest pin observation used by admission and scheduler logic. Exact-tuple admission/replay requires the observed `(scriptPatchVersion, scriptPinEpoch)` pair and fails closed when the epoch is missing or stale. For promotion or rollback convergence, the observation is an acknowledgment only when the expected `(scriptPatchVersion, scriptPinEpoch, convergenceAttemptGeneration, controlPlaneRequestId)` tuple is present, `isProjectionStale=false`, and `projectionLagMs` is inside the configured freshness bound; a missing attempt generation separately prevents convergence proof. The freshness bound is the configured `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` value from [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md).
- Semantic `UNPINNED` is a valid observation only before the first pin, represented by an absent `observedPinnedScriptPatchVersion`/`observedScriptPinEpoch` pair, never by a sentinel or partial pair. `lastObservedControlPlaneRequestId` is absent only for that never-pinned observation; a pinned observation retains its request identity and cannot satisfy promotion/rollback acknowledgment unless it also carries the requested pinned tuple and matching request identity.
- Reports only pin observation and projection freshness; it does not return an admission decision, `finalStage`, `finalOutcome`, `finalReason`, or a handler outcome.

#### `GetGameSessionPinConvergence`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `observedPinnedScriptPatchVersion` (nullable; absent only for a never-pinned semantic `UNPINNED`)
- `observedScriptPinEpoch` (nullable; absent only for a never-pinned semantic `UNPINNED`)
- `currentConvergenceAttemptGeneration` (positive current generation; absent when no workflow exists)
- `lastObservedControlPlaneRequestId` (nullable; the committed pin mutation request represented by this authoritative Game Session read; absent only for a never-pinned semantic `UNPINNED` observation)
- `observedAt`

Semantics:

- Read-only.
- Reports a Game Session owner-side convergence observation/acknowledgment for rollback and promotion. It is not the authoritative current-pin or rollout-history read and is not used by tick command intake or execution-time version fences; those use `GetPinnedScriptPatchVersion` and Game Session local owner state.
- This is not an Automation projection read; projection lag and stale-projection fields do not apply, and no projection fields are returned. For rollback/promotion convergence, the observation is an acknowledgment only when it completes before the operation deadline and the exact `(scriptPatchVersion, scriptPinEpoch, convergenceAttemptGeneration, controlPlaneRequestId)` matches the expected tuple; `observedAt` is the timestamp from the owner-side observation.
- Semantic `UNPINNED` is a valid observation only before the first pin, represented by an absent `observedPinnedScriptPatchVersion`/`observedScriptPinEpoch` pair, never by a sentinel or partial pair. `lastObservedControlPlaneRequestId` is absent only for that never-pinned observation and cannot make an observation satisfy promotion/rollback acknowledgment without the requested pinned tuple and matching request identity.

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
- `gameInstanceId` (required; patch cancellation is instance-scoped)
- Optional scope: `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Applies the canonical two-layer cancellation mapping: `PENDING_EVALUATION` transitions to terminal `CANCELED` without DSL evaluation with `finalStage=ADMISSION`, `finalOutcome=canceled`; target `EXECUTING` is fenced and its descriptor-commit marker is inspected. Descriptor commit and cancellation serialize on one durable compare-and-set/transaction over the parent marker: if descriptor commit wins, cancellation observes committed state and applies only the committed-child mapping below; if cancellation wins, descriptor commit is rejected and the parent remains terminal `CANCELED` with no dispatchable children. If the descriptor commit is present, cancellation never resumes or re-dispatches a committed child: evaluated `PENDING` and `INDEXED` children compare-and-set to `CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`; `HANDOFF_IN_FLIGHT` fences further retry and reconciles the durable downstream outcome (remaining active/unresolved when ambiguous); and `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` children retain their outcome and no-op. Explicit cancellation of an uncommitted `EXECUTING` row transitions to terminal `CANCELED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and bounded cancellation metadata, and is never replay-eligible. Only the distinct expired-stale recovery-owner path may transition an uncommitted `EXECUTING` row to terminal `DEAD_LETTERED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`; this cancellation API does not dead-letter stale displaced rows. No path re-enters the DSL.
- Cancellation selects only rows belonging to the supplied `gameInstanceId` whose stored `(scriptPatchVersion, scriptPinEpoch)` exactly matches the displaced tuple. A same-version repin with a newer epoch is not eligible for this request. Emits an operator audit entry and applies the rollback-specific metric consequence defined by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix).

Outputs:

- `canceledCount` (best-effort count; may be approximate for large batches)

### Automation & Scripting: Outbox and Dead-Letter Operations (Required)

These APIs provide deterministic operator hooks for stuck/canceled work so control-plane rollback and recovery do not depend on ad-hoc database access.

The persisted `outboxWorkItemId` and its public/current wire aliases (`workItemId` and `automationWorkItemId`) are parent correlation and the bounded mutation selector for this API family, not command identity. Mutation requests accept bounded explicit `workItemIds[]` only; descriptor references and filter-based mutation/replay are deferred until preview plus stable per-row proof exists. Read-only listing may expose descriptor identity for diagnosis, but that reference is not a mutation selector.

#### `ListOutboxWorkItems`

Inputs:

- `tenantId`
- Optional filters: `gameInstanceId`, `regionId`, `scriptPatchVersion`, `scriptPinEpoch`, `pluginId`, `pluginVersionId`, `workItemStatus`, `createdAfter`, `createdBefore`
- Pagination: `pageSize`, `pageToken`

Semantics:

- Read-only.
- Must support bounded pagination and stable sort order so large tenants can be inspected without full scans.

Outputs:

- `items[]` (including `workItemId`, the public alias for canonical `outboxWorkItemId`, the exact stored `scriptPatchVersion` and `scriptPinEpoch`, optional descriptor identity for diagnosis when the row is an evaluated descriptor, `workItemStatus`, `createdAt`, `updatedAt`, `cancelReason`). For target pre-DSL `PENDING_EVALUATION` or `EXECUTING` rows, and for current live `EVALUATING` rows, descriptor identity is omitted/null because no command identity exists.
- `nextPageToken`

#### `ReplayDeadLetteredWorkItems`

Dead-letter recovery is stage-aware. The bounded explicit `workItemIds[]` selector and per-row outcomes below are the target contract; descriptor references and filter-based mutation remain deferred until preview plus stable per-row proof.

Inputs:

- `tenantId`
- Selector: non-empty, unique, nonblank explicit `workItemIds[]` only, subject to the maximum and deterministic validation error owned by [Control-Plane API](./system-architecture-scripting-control-plane-api.md#replaydeadletteredworkitems). Invalid selection fails before canonical request fingerprinting or claim acquisition. Filters and descriptor references are listing/preview inputs, not mutation selectors
- `controlPlaneRequestId`
- `actor`
- `reason`

Failure-generation identity is Automation-owned and distinct from every execution or admission fence. For each durable parent `(tenantId, outboxWorkItemId)`, the first atomic transition of the parent recovery aggregate into `DEAD_LETTERED` initializes `failureGeneration=1`; each later transition into `DEAD_LETTERED` advances it exactly once in that same transition, including a permanent required-child failure after an `EVALUATED_COMMITTED` evaluation and a later failure after successful recovery. The retained `EVALUATED_COMMITTED` descriptor marker and output/child ledger are not replaced by this aggregate transition. An exact retry or no-op of the same transition does not advance it. The durable dead-letter evidence, request per-item result, recovery claim/attempt, success evidence, and `already_recovered` result each bind the tuple `(tenantId, outboxWorkItemId, failureGeneration)`. Selectors remain work-item IDs; the mutation resolves the current generation atomically. `failureGeneration` is not `rowVersion` or `admissionEpoch`, and it does not extend Trigger Identity or Command-Handoff Identity.

Semantics:

- Idempotent.
- The Automation owner persists one durable request-level replay result keyed by `controlPlaneRequestId` and the canonical request fingerprint, binding the canonical selected `workItemIds` and one per-item outcome to the resolved `(tenantId, outboxWorkItemId, failureGeneration)`. This request result is separate from recovery claim/attempt records: rejected non-`DEAD_LETTERED` or otherwise ineligible rows are recorded there as `outcome=rejected` while remaining unchanged and creating no recovery claim/attempt record. A claim/attempt that was created but reached terminal `FAILED` is recorded as `outcome=recovery_failed`; its `failureReason` is required and bounded, using the established stage-aware failure-reason vocabulary, and `rejectionReason` is absent. An exact retry returns the stored result and reason for the generation captured by that request without creating another attempt; a new request ID resolves the current generation atomically with current authority and row state and may retry only after the prior attempt is terminal `FAILED` and fresh eligibility/fence checks pass.
- After exact-request idempotency has been resolved and the current `failureGeneration` has been atomically resolved, immutable successful-recovery evidence bound to that same current generation returns `outcome=already_recovered` before aggregate-status or active-claim eligibility checks; evidence for an older generation is non-matching. After that precedence check, a row whose applicable pre-DSL recovery payload or evaluated descriptor/child evidence was purged, including an `EVALUATED_COMMITTED` parent marked by `descriptorEvidencePurgedAt`, returns `outcome=rejected` with `rejectionReason=stage_evidence_unavailable` and creates no recovery claim/attempt for that generation. Only a row without same-generation successful-recovery evidence or purged recovery evidence whose current parent recovery aggregate status is `DEAD_LETTERED` is eligible for recovery. The operation must compare-and-set that aggregate status and persist a recovery claim/attempt record containing the generation, expected exact `(scriptPatchVersion, scriptPinEpoch)`, runtime scope, applicable plugin binding, captured `(pluginActivationEpoch, lifecycleRevision)`, and `controlPlaneRequestId` in the same durable transaction before evaluation or dispatch. A post-evaluation parent may retain its `EVALUATED_COMMITTED` descriptor marker and complete output/child ledger while its separate recovery aggregate is `DEAD_LETTERED`; that is the eligible `resumed_dispatch` case only while `descriptorEvidencePurgedAt` is absent. A selected row in any other aggregate status remains unchanged and returns `outcome=rejected` with `rejectionReason=work_item_not_dead_lettered`, without a recovery claim/attempt record. A new request may recover the current generation even when immutable successful-recovery evidence exists for an older generation; that older evidence neither produces `already_recovered` nor blocks the current generation. A `DEAD_LETTERED` row with a mismatched exact fence, runtime scope, plugin binding, plugin epoch, or lifecycle revision remains `DEAD_LETTERED` and returns the applicable bounded mismatch reason (`script_pin_epoch_mismatch`, `runtime_scope_mismatch`, `plugin_binding_mismatch`, or `plugin_activation_epoch_mismatch`), without a recovery claim/attempt; the existing lifecycle/plugin mismatch disposition also covers a `lifecycleRevision` mismatch. If a different `controlPlaneRequestId` targets a row with an active `IN_PROGRESS` claim for the current generation, its stored deterministic request result is `outcome=rejected` with `rejectionReason=recovery_in_progress` and it creates no new claim/attempt, evaluation, or dispatch.
- Recovery revalidates the persisted exact fence and binding immediately before evaluation and again before dispatch. Every recovery-owned claim, stage transition, evaluation commit, child dispatch, and audit write compare-and-sets the persisted attempt identity and current owner fence. Reclaiming an expired claim reuses the same attempt record and identity while atomically advancing that owner fence rather than creating a duplicate attempt or audit record; the prior owner then fails closed on every recovery write. Lease expiry alone does not allow a different request to create an attempt. An exact retry of the same `controlPlaneRequestId`, `workItemId`, and canonical request fingerprint while the claim remains `IN_PROGRESS` attaches to or reads the same attempt and returns that attempt's eventual stored terminal per-work-item result; it does not create or persist a temporary per-row result, and if the caller's transport deadline expires first no different application result is stored. It never creates another recovery attempt; once the attempt is terminal, the exact retry returns its stored result.
- The target claim/attempt record has one active `IN_PROGRESS` claim per `(tenantId, outboxWorkItemId, failureGeneration)`, enforced by a uniqueness condition so concurrent owners cannot evaluate or dispatch the same current generation. Claim acquisition atomically checks the parent recovery aggregate's `DEAD_LETTERED` status, resolves the current generation, and checks the exact fences before inserting or claiming that record. A successful evaluation-stage recovery atomically commits the complete evaluated-output/descriptor evidence, including a terminal zero-command evaluation when applicable, transitions the pre-DSL parent to `EVALUATED_COMMITTED` by creating its descriptor marker, and clears the parent recovery aggregate's `DEAD_LETTERED` status; a successful post-evaluation recovery resolves all eligible unfinished children and atomically clears the parent recovery aggregate's `DEAD_LETTERED` status while retaining the existing marker and complete output/child ledger. That durable parent/child commit is the success boundary. The same transaction also persists an attempt-finalization marker or equivalent durable success evidence, fenced by the same attempt identity and `failureGeneration` and containing or identifying the stored success result. Only after that boundary may the same record become terminal `SUCCEEDED`, the per-item result be `retried_evaluation` or `resumed_dispatch`, and immutable successful-recovery evidence for that generation be stored; a retry or reclaim that sees `IN_PROGRESS` after the boundary finalizes this same attempt as `SUCCEEDED` and returns the stored success without DSL re-entry or redispatch. A deterministic failure before that success boundary transitions the attempt to terminal `FAILED` while the parent recovery aggregate remains `DEAD_LETTERED`; an ambiguous post-evaluation child remains unresolved and cannot produce success. A stale owner or later failed write cannot demote a parent/child commit that already won its attempt- and generation-fenced compare-and-set. `FAILED` applies only to that attempt and does not advance `failureGeneration` while the parent remains in the same `DEAD_LETTERED` generation; its request result is `recovery_failed` with the bounded `failureReason` already used by the failed stage. Claim/attempt identity, committed evidence, and terminal attempt results are immutable; lease/lifecycle status and owner-fence fields may advance only through the defined atomic expiry/reclaim compare-and-set, which reuses the same attempt identity. The expired owner or stale generation cannot evaluate or dispatch. A later request with a new `controlPlaneRequestId` may create exactly one new attempt for the still-`DEAD_LETTERED` current generation only after the prior attempt is terminal `FAILED` and the normal fresh eligibility and exact-fence checks succeed; it must not create a competing attempt.
- Must enforce the direct API's batch maximum and over-limit rejection before request fingerprinting or claim acquisition.
- Each selected work item is recovered under its stored stage evidence. For replayable instance-bound gameplay/runtime work whose persisted failure class is retryable under [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#failure-modes-and-error-handling), evaluation-stage retry invokes the DSL evaluator again using the original work-item and `scriptEventId` identity, frozen input/manifest evidence, and exact admitted immutable graph; non-retryable classes remain terminal/ineligible. Recovery remains outside the normal admission path and allocates no dispatch or command-child identity before a winning descriptor commit. If commands are emitted, that first atomic evaluated-descriptor/outbox commit allocates and persists the `automationDispatchId` and canonical ordinals; retries after a winning commit reuse them. A valid zero-command evaluation creates no dispatch identity and recovery must converge on the original work item. Tenant-readiness `onLoad` work is ineligible for this evaluation replay: it remains at-most-once under its original readiness identity and must not re-enter the DSL after stale terminalization; retry requires a newly published immutable patch. Post-evaluation recovery uses the immutable evaluated output and complete child-dispatch ledger keyed by the full Command-Handoff Identity without invoking the DSL, preserving per-child recovery state, payload digests, ordinals, and acknowledgements. `HANDED_OFF` (accepted), `CANCELED`, and `DEAD_LETTERED` children are terminal and no-op. An already `HANDOFF_IN_FLIGHT` child is fenced and reconciled against the durable downstream outcome, never blindly redispatched; if that outcome is ambiguous, the child remains active/unresolved. Immediately before evaluation, and immediately before each post-evaluation dispatch, the owner revalidates the exact patch/epoch, runtime scope, routing bundle, captured `(pluginActivationEpoch, lifecycleRevision)`, and applicable plugin activation/lifecycle, component-policy, capability-grant, and signer/publication evidence. For same-epoch `DRAINING`, only the immediately preceding admitted `ENABLED` lifecycle revision is a valid predecessor; the canonical bounded predecessor rule is defined by [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md). `DRAINING` blocks only new trigger admission. Only a lifecycle transition that invalidates admitted work, such as `DISABLED`, revocation, or policy-driven disablement, uses `plugin_disabled`. Only after dispatch-time revalidation succeeds may a `PENDING` or `INDEXED` child transition by durable compare-and-set to `HANDOFF_IN_FLIGHT` under the existing recovery claim/attempt fence and dispatch. Before a current-generation claim/attempt exists, missing, stale, or temporarily unavailable authority, component-policy, or signer evidence leaves the row `DEAD_LETTERED` with `outcome=rejected`, the applicable bounded `rejectionReason`, and no recovery claim/attempt; after the current-generation claim/attempt exists, the same failure during pre-evaluation or dispatch revalidation terminalizes that attempt as `FAILED` while the parent remains `DEAD_LETTERED`, returning `outcome=recovery_failed` with a `failureReason` that is required and bounded and no `rejectionReason`. Neither stage regenerates an uncorrelated logical trigger.
- Reusing a `controlPlaneRequestId` with a different canonical request fingerprint returns an idempotency conflict and cannot alter the stored request result. An exact retry returns the same per-work-item result; it does not reopen the original audit row.
- Same-epoch `DRAINING` recovery is eligible only for the immediately preceding admitted `ENABLED` lifecycle revision whose winning admission/fence compare-and-set committed before the durable `DRAINING` admission barrier was created; arbitrary lower or non-predecessor revisions remain rejected.
- Resolves each selected `workItemId` against its own stored trigger/work-item identity and stage evidence atomically. A parent with multiple committed descriptors is recovered from its stored child ledger; descriptor references are not accepted as selectors and filters are not expanded into a mutation batch.
- Every selected work item must match the request `tenantId`; the operation resolves the authoritative current tuple from that row's own stored instance identity and must not borrow another instance's pin or binding. The target mutation has no caller-supplied instance or region scope.
- Before recovery claim acquisition for plugin-backed work, resolve the current binding and `(pluginActivationEpoch, lifecycleRevision)` for that same tenant, instance, and plugin, then compare the stored immutable `(pluginId, pluginVersionId, bindingId)` tuple and captured lifecycle fence tuple to that current evidence. Same-epoch `DRAINING` may use only the immediately preceding admitted `ENABLED` revision whose winning admission/fence compare-and-set committed before the durable `DRAINING` admission barrier was created, as defined by [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md); arbitrary lower revisions are rejected.
- Before recovery claim acquisition, enforce recovery eligibility against current authoritative state:
  - Work items with `(scriptPatchVersion, scriptPinEpoch)` that is not currently pinned for the scoped instance must be rejected from recovery, including a repin to the same patch with a new epoch. A proven `script_pin_epoch_mismatch` is permanently ineligible for that work item: rollback or another same-version repin cannot make the old epoch current again.
- Plugin work items whose immutable `(pluginId, pluginVersionId, bindingId)` tuple or captured `(pluginActivationEpoch, lifecycleRevision)` does not match the currently active binding/lifecycle fence for the same scoped `<tenantId, gameInstanceId, pluginId>` must be rejected from replay, except for the immediately preceding admitted `ENABLED` revision during same-epoch `DRAINING` when its winning admission/fence compare-and-set committed before the durable `DRAINING` admission barrier was created, under the ADR 0119 bounded predecessor rule. An epoch or lifecycle-revision mismatch is permanently ineligible for that work item because the Automation-owned fences are monotonic.
- Pre-claim ineligible rows must return `outcome=rejected` with the specific bounded `rejectionReason` that explains the failed fence or evidence check, remain `DEAD_LETTERED`, and produce no recovery claim/attempt record. A failure of the same check after a claim/attempt exists follows the stored `recovery_failed`/`failureReason` rule above and never adds a `rejectionReason`.

Outputs:

- `replayedCount` (the number of `results[]` rows whose stored `outcome` is `retried_evaluation` or `resumed_dispatch`; `already_recovered`, `recovery_failed`, and `rejected` do not contribute)
- `results[]` (bounded to the selected input batch, with one result per selected `workItemId`):
  - `workItemId`
  - `recoveryStage` (optional/nullable: `EVALUATION` or `POST_EVALUATION_DISPATCH` when trustworthy stage evidence exists; `null` for `not_found_or_not_owned` or `stage_evidence_unavailable`)
  - `failureGeneration` (the resolved generation for the selected parent; the stored generation for an exact retry; nullable only when no owned row/generation can be resolved)
  - `outcome` (`retried_evaluation`, `resumed_dispatch`, `already_recovered`, `recovery_failed`, or `rejected`)
  - `rejectionReason` only when `outcome=rejected`, using established bounded values such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `work_item_not_dead_lettered`, `recovery_in_progress`, `script_pin_epoch_mismatch`, `plugin_binding_mismatch`, `plugin_activation_epoch_mismatch`, `runtime_scope_mismatch`, `plugin_disabled`, `plugin_version_not_published`, `plugin_component_policy_blocked`, `component_policy_unavailable`, `signer_policy_unavailable`, `signer_revoked`, or `authority_unavailable`
  - `failureReason` only when `outcome=recovery_failed`; it is required and bounded, using the established stage-aware failure-reason vocabulary for the terminal failed attempt rather than introducing a parallel taxonomy

For an exact retry of the same `controlPlaneRequestId`, canonical request fingerprint, and selected IDs, `results[]` returns the identical stored per-work-item outcomes and stored `failureGeneration`, including `recovery_failed` with its stored `failureReason` or `rejected`, without new work or relabeling. `replayedCount` is derived from those stored results using the counted-outcome set above, so an exact retry returns the same aggregate. A new request with a different `controlPlaneRequestId` returns `outcome=already_recovered` only when immutable prior successful-recovery evidence exists for the same current generation; evidence for an older generation remains immutable and cannot satisfy or block the current one. Otherwise an eligible current `DEAD_LETTERED` row is evaluated for recovery, while a non-`DEAD_LETTERED` row returns `outcome=rejected` with `rejectionReason=work_item_not_dead_lettered`. Rejected work items remain without a recovery claim/attempt record; a new attempt after `recovery_failed` is permitted only after the prior attempt is terminal `FAILED` and fresh eligibility/fence checks pass. This stable-request idempotency behavior is retained.

Ineligible `DEAD_LETTERED` rows remain `DEAD_LETTERED` and produce no recovery claim/attempt record for that generation. A proven `script_pin_epoch_mismatch` or current plugin fence mismatch remains ineligible for that current generation under every later request unless the owning parent later leaves and re-enters `DEAD_LETTERED`, which atomically creates the next failure generation; exact-request retries return the stored rejection. Older-generation success or mismatch evidence is immutable and cannot be reused as the current generation's result or permanent block. A rejection caused only by temporarily unavailable authoritative evidence may be retried by a later request after that evidence becomes available. Non-`DEAD_LETTERED` rows without same-generation immutable prior successful-recovery evidence remain unchanged and return `work_item_not_dead_lettered`; missing or contradictory stage evidence remains dead-lettered.

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
- For each selected dead-lettered row, purge resolves the current `(tenantId, outboxWorkItemId, failureGeneration)` and binds that exact tuple to its per-item result, audit, and idempotency evidence. Purge and replay require exact-generation matching; retained purge evidence for an older generation cannot satisfy, block, or mark a newer dead-letter generation.
- Resolves each selected `workItemId` against durable trigger and descriptor/outbox state before mutating anything. For rollback-scoped cleanup, every selected row must match the request's exact displaced `(scriptPatchVersion, scriptPinEpoch)` tuple; a same-version repin with a newer epoch is rejected. A target row in `PENDING_EVALUATION` or `EXECUTING` (or a current live row in `EVALUATING`), or an evaluated descriptor in `PENDING`, `INDEXED`, or `HANDOFF_IN_FLIGHT`, is rejected with a bounded application reason; an `EVALUATED_COMMITTED` parent is resolved as retained trigger evidence rather than rejected wholesale. Purge does not cancel, reclaim, dead-letter, replay, or otherwise mutate active/nonterminal work.
- For evaluated descriptor/output evidence, purge may remove only a coherent retained evidence bundle whose every child under an `EVALUATED_COMMITTED` parent is terminal (`HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED`) and whose descriptor/outbox/evaluated-output evidence is retention-eligible across every applicable recovery, rollback-diagnostic, command-status, audit, and downstream-replay horizon. A row advertised as recoverable and all of its stage, manifest, evaluated-output, and child-dispatch evidence must remain one bundle; cleanup must not delete supporting evidence independently while leaving the row recoverable. If any sibling is `PENDING`, `INDEXED`, `HANDOFF_IN_FLIGHT`, otherwise nonterminal, or not retention-eligible, the entire parent purge is rejected and no marker is written. Terminal evidence still inside any applicable horizon is rejected with a bounded retention reason, and incoherent evidence fails closed. Explicit purge follows the same whole-bundle rule; the same purge transaction atomically purges the eligible evidence and persists `descriptorEvidencePurgedAt` on the retained `EVALUATED_COMMITTED` parent. A marked parent is non-recoverable/non-replayable and consumers reject it deterministically rather than treating missing children as recoverable, while the trigger marker, corresponding `script_event_audit`, and replay-causation evidence remain preserved under their owning retention contracts after purge.
- Separately, a terminal pre-DSL `DEAD_LETTERED` row with no committed descriptor may have only retention-eligible trigger/failure payload material purged after its recovery eligibility window has expired. Before deletion, purge resolves the current `failureGeneration` and atomically CASes against recovery claim/attempt state; any current-generation `IN_PROGRESS` claim, including an expired claim not yet canonically reclaimed, rejects with `recovery_in_progress` and deletes no evidence. Only when no current-generation claim exists or its claim/attempt is terminal may a later retention-eligible purge proceed. No new schema marker is introduced for this path. The retained purge audit/idempotency outcome is the durable evidence of the purge and makes the row permanently non-recoverable; any later replay attempt is rejected deterministically with `rejectionReason=stage_evidence_unavailable`. Its immutable identity/digest, failure-stage, corresponding `script_event_audit`, recovery-causation evidence, and audit/idempotency outcomes remain retained under their owning retention contracts.
- Must emit operator-auditable records for every purge request.

Outputs:

- `purgedCount` (best-effort count; may be approximate for large batches)

#### `CancelPendingWorkItemsForPluginVersion`

Inputs:

- `tenantId`
- `gameInstanceId` (required; plugin cancellation is instance-scoped)
- `pluginId`
- `pluginVersionId`
- `pluginActivationEpoch` (the displaced activation epoch; cancellation must match the stored exact plugin activation tuple)
- `scriptPatchVersion` (the displaced script patch version carried by the plugin-produced work)
- `scriptPinEpoch` (the displaced pin epoch; cancellation must match the stored exact runtime tuple)
- `workItemIds[]` (required, nonempty, unique explicit parent work-item IDs; maximum 100)
- Optional scope: `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- `workItemIds[]` is validated as a nonempty, unique, nonblank list of at most 100 IDs before candidate reads or mutation. Over-limit, duplicate, blank, or otherwise invalid selector input is rejected with `INVALID_ARGUMENT`; an exact request is never silently truncated or paged. Callers repeat bounded batches with new request IDs. The canonical request digest binds the normalized sorted ID set together with the complete operation name, exact selection scope `(tenantId, gameInstanceId, pluginId, pluginVersionId, pluginActivationEpoch, scriptPatchVersion, scriptPinEpoch, optional regionId)`, `actor`, and `reason`; an exact retry returns the stored batch result without selection or mutation, while a changed digest conflicts first.
- Each requested ID resolves across all applicable plugin-owned bindings under the exact scope; `bindingId` is per-row provenance/fence evidence, not a selector. Missing or not-owned IDs return `not_found_or_not_owned`; tuple, scope, and other precondition failures return `rejected` before mutation. There is no tenant-wide fallback or unbounded scope scan.
- **Target-state semantics:** cancellation is fencing-aware across both durable layers and is scoped to the required `gameInstanceId`; `tenantId` is not a tenant-wide epoch selector. A same-version repin with a newer `scriptPinEpoch` is not eligible. `PENDING_EVALUATION` transitions by compare-and-set to `CANCELED` without entering the DSL. Target `EXECUTING` is fenced and its descriptor-commit marker is inspected first. Descriptor commit and cancellation use the same durable compare-and-set/transaction and winner semantics described for patch cancellation above: a committed descriptor is reconciled without resume/redispatch, while a cancellation winner rejects descriptor commit and leaves no dispatchable children. If committed, cancellation never resumes or re-dispatches a committed child: evaluated `PENDING` and `INDEXED` children compare-and-set to `workItemStatus=CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`; `HANDOFF_IN_FLIGHT` fences further retry and reconciles the durable downstream outcome (remaining active/unresolved when ambiguous); and `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` children retain their outcome and no-op. An uncommitted trigger transitions to `CANCELED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and bounded cancellation metadata and is never replay-eligible. Only the distinct expired-stale recovery-owner path uses the canonical `DEAD_LETTERED` mapping with `finalReason=stale_execution_fenced`; the cancellation API itself does not dead-letter stale displaced rows. Each transition records the corresponding stage-aware `script_event_audit` cancellation outcome. The current implementation lacks the descriptor layer and recovery owner, so these are target-state semantics rather than current live proof.
- Each requested parent yields exactly one aggregated result with priority `not_found_or_not_owned`/`rejected` for precondition failures, `recovery_in_progress` when any selected parent or child remains active or ambiguous (including `HANDOFF_IN_FLIGHT`), `canceled` only when this request changed at least one eligible row and every applicable child is terminal afterward, and `already_terminal` only when no mutation was needed and all applicable children were already terminal. Bounded child-state counts or evidence reasons may be returned. `canceledCount` counts only parent results whose outcome is `canceled`.
- This is bounded asynchronous cleanup for rollback, disable, and revocation resource convergence. `lifecycleRevision` is not a request selector or request-digest field because cleanup is asynchronous and the revision is not its correctness fence. When stored, exhaustive per-row/final-fence evidence may retain and revalidate it beside `pluginActivationEpoch`; exact runtime and plugin fences, including the row's `bindingId` evidence and activation epoch, remain authoritative before handoff and final effects.

Outputs:

- `results[]` (bounded, one result per requested parent `workItemId`; each result retains the stable `workItemId` and the stored `bindingId` evidence when present)
  - `outcome` is exactly one of `canceled`, `already_terminal`, `recovery_in_progress`, `not_found_or_not_owned`, or `rejected`.
  - `reason` is present only when applicable, including for `outcome=rejected`, and uses a bounded reason such as `plugin_binding_mismatch`, `plugin_activation_epoch_mismatch`, `script_pin_epoch_mismatch`, `runtime_scope_mismatch`, `component_policy_unavailable`, `signer_policy_unavailable`, or `authority_unavailable`.
- `canceledCount` (derived from `results[]` by counting only `outcome=canceled`; an exact retry returns the same aggregate)

### Automation & Scripting: Plugin Drain Workflow

`DrainPlugin` is a direct control-plane API defined in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md#drainplugin). This workflow section defines how operators use that API in combination with convergence reads and cancellation APIs; it does not redefine the request/response contract. The workflow waits for the API's exact target tuple and durable Game Session install acknowledgement before treating the transition or containment as complete; pending-transition serialization, completion CAS, and the same-epoch `DRAINING` predecessor rule remain owned by [ADR 0119](./decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) and the canonical control-plane API.

### Logging & Admin: Operator Workflow APIs

Logging & Admin may expose a single high-level orchestration API (internally driving the lower-level calls above) for operators:

- `RequestScriptPatchRollback(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RequestScriptPatchPromotion(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RecoverScriptPinConvergence(tenantId, gameInstanceId, controlPlaneRequestId, expectedTimedOutConvergenceAttemptGeneration, actor, reason)`

If implemented, these APIs must remain thin orchestration and must not become another source of truth for the pinned version. Recovery must forward the explicit Game Session owner mutation and must not infer recovery from a retry of the original promotion or rollback request.

## Emergency Component Revocation Workflow (Required)

Routine `UNSAFE` classification is migration-required / new-use-blocked policy. It blocks future publication and readiness but does not invoke this workflow or mutate already-`READY` or pinned behavior.

Emergency revocation is a distinct explicit, audited platform-security action under [ADR 0116](./decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md), reserved for critical sandbox escape, arbitrary execution, cross-tenant access, or private-data access. Once accepted at the authoritative security-policy boundary:

1. Immediately reject new evaluation of every handler whose exact component dependency set includes the revoked component, including handlers in otherwise `READY` or pinned patches. Work already evaluating must recheck the emergency fence before durable persistence or handoff and discard output after the fence. Quarantine and replace workers when the evaluator process may be compromised.
2. Discover affected published patches, active pins, and current Automation scopes from authoritative dependency and runtime indexes; do not rely on tenants to self-identify exposure. Stale, contradictory, or unavailable required indexes make the discovery proof incomplete and fail closed: retain the emergency component fence, keep every discovered affected scope contained or paused, do not resume affected Automation, and retry the same audited workflow until the authoritative indexes converge on one coherent discovery result.
3. Pause each affected Automation scope through the normal audited admission control so new triggers cannot refill work during containment.
4. Where an exact safe target exists, prepare it and drive the normal explicit disable or fenced rollback, including pin/epoch convergence and displaced-work cancellation and purge.
5. Resume Automation only after the affected scope converges to a safe state. If no safe target exists, keep affected Automation fail closed while unrelated gameplay continues.

Every step is durably auditable and retryable. Routine component reclassification must not trigger this authority implicitly, and emergency containment does not claim that already-applied gameplay effects can be reversed.

## Pin Transition and Recovery Workflow

The canonical promotion and rollback ordering, durable state machine, convergence timeout, diagnostic cleanup progress, schedule reconciliation, and degraded-operation policy remain owned by [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#pin-transition-orchestration-state-machine-required). Use that owner document for command ordering and state transitions. The [operations cookbook](./system-architecture-scripting-operations-cookbook.md#rollback-protocol-and-diagnostics) is only a non-authoritative rollback example and must not be treated as a source of command ordering.

This document retains only the participating API consequences:

- Automation & Scripting implements the admission barrier, durable schedule reconciliation, work-item cancellation, drain-status reads, pin-convergence reads, and auditable cleanup APIs described above; when requested, it also persists and returns the non-authoritative immutable preparation evidence defined by [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md).
- Game Session owns the durable promotion/rollback workflow, immutable `operationKind`, exact patch/epoch pin, and rollout history, and is the sole producer of the pin-convergence-timeout signal. When preparation is used, its pin mutation consumes the evidence, revalidates the current tenant-`READY` state, artifact digest, readiness revision, and applicable policy revisions, and rejects missing, mismatched, or stale evidence; without preparation, it performs the equivalent authoritative commit-time checks. Routine pin transitions advance the script epoch while ordinary ticks/player commands continue; Automation consumes the durable timeout state/signal and must not create a competing timeout or recovery state machine. Logging & Admin may orchestrate those APIs but must not persist a competing workflow state machine.
- The same `controlPlaneRequestId`, authenticated operator `actor`/`requestedBy`, and `reason` flow through every mutating step. System-owned reconciliation records use a separate `executedBy=system:automation` rather than replacing the operator identity.
- Patch- and plugin-scoped cancel/purge calls omit `regionId` for an instance-wide repin so every affected region is covered. API retries remain idempotent under the owning request/response contracts.

Operationally, use control-plane APIs rather than direct data-store edits for pending and dead-lettered work:

- `ListOutboxWorkItems` for scoped inspection.
- `ReplayDeadLetteredWorkItems` for bounded replay of recoverable items.
- `PurgeOutboxWorkItems` for auditable cleanup of terminally invalid or stale items.

## Related Control Plane Contracts

- [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md) defines the direct request/response contracts, canonical errors, and admission rules.
- [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) defines promotion and rollback ordering, orchestration state, convergence, and degraded-operation policy.
- [Scripting & Automation: Control Plane Notifications](./system-architecture-scripting-control-plane-events.md) defines advisory notification families and the separately named durable event flows emitted by the workflow APIs in this document.
- [Scripting Observability Contract](./system-architecture-scripting-observability-contract.md) defines audit and handoff diagnostics referenced by the workflow steps in this document; [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) owns metric names, labels, and increment units, while the normative audit tables and scripting lifecycle/rollout documents own audit stage/outcome semantics and final status transitions.
