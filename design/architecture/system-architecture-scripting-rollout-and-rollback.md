# FireMUD Scripting & Automation: Rollout and Rollback

This document is the canonical owner for operator-driven promotion, rollback, workflow sequencing, convergence, timeout, and degraded-operations contracts for scripting and plugin control-plane changes. [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md) defines the underlying RPC contracts and mutable state boundaries. [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md) records each service's participation and cleanup APIs, while the [operations cookbook](./system-architecture-scripting-operations-cookbook.md) provides executable operator examples.

For mutating workflow calls, `actor` is the authenticated operator principal and is projected to audit as `requestedBy`. System-owned reconciliation and cleanup records use a separate `executedBy=system:automation`; they must not replace `requestedBy` or overload `actor` with the worker identity.

## Implementation Status

The rollout and rollback state machine below is target-state canonical. The live Game Session handoff still lacks complete per-command Trigger Identity propagation; the detailed current fallback is recorded in [Command Identity and Live Handoff Boundary](#command-identity-and-live-handoff-boundary). `targetEntityIds[]` multi-target fan-out is target-state only because the existing owners do not define persisted deterministic per-target identity, scope validation, and deduplication at the live boundary. Automation currently claims work through `PENDING_EVALUATION` to `EVALUATING`; its implementation lacks the target lease/fencing-generation recovery and separate evaluated descriptor/outbox boundary. The target state mapping and recovery owner are canonical in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#current-state-mapping-drain-and-rebuild-rules). Current `GetAutomationDrainStatus` counts `EVALUATING` and `HANDOFF_IN_FLIGHT` in `activeExecutionCount`, including unresolved stale `EVALUATING`, while `pendingCancelableWorkItemCount` covers current handoff-capable `PENDING_EVALUATION` rows; this is a current projection gap, not permission to infer a reclaim. Metric schemas come from [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix); audit and handoff diagnostics come from the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md).

## Patch Promotion (Operator-Driven)

1. Validate patch is `READY` in Automation & Scripting for the tenant (`GetScriptPatchStatus`).
2. Enter the Automation admission barrier before repin with `SetAutomationAdmissionMode(..., mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId, actor, reason)`. This pauses external, scheduler, and timer admission for the affected scope; an implementation may make the barrier atomic with repin, but it must not repin outside the barrier.
3. Call `PauseTicks` in Game Session for the same scope before repin or purge. Ticks and Automation admission are now both fenced for the promotion scope.
4. Call `SetPinnedScriptPatchVersion` in Game Session while both fences remain active.
5. Game Session emits `ScriptPatchPinChanged`.
6. Call `CancelPendingWorkItemsForPatch` for the previous patch in scope so outbox work produced under displaced patch state cannot continue handing off indefinitely; keep both fences active.
7. Call `PurgeQueuedTickCommandsForScriptPatch` for the previous patch (and plugin equivalents when plugin version changes are coupled with the promotion); keep both fences active.
8. Automation & Scripting must reconcile durable schedules and timers for the newly pinned patch before timer admission resumes:
   - schedules absent from the newly pinned patch are removed or tombstoned;
   - schedules that still exist may be carried forward only through explicit reconciliation when `scheduleDefinitionId`, `playableStateScope`, and `scheduleSemanticsHash` all match; a changed definition, semantics digest, or playable-state namespace requires a new schedule identity and due state;
   - reconciliation creates only replacement schedule identities; a due candidate must pass admission before the scheduler creates its firing claim or `scriptEventId`;
   - displaced patch or plugin versions must not be able to generate new `scriptEventId` values after promotion.
9. Wait for the [Pin Convergence Acknowledgment Predicate](#pin-convergence-acknowledgment-predicate) to succeed for the requested `controlPlaneRequestId`.
10. Wait for `GetAutomationDrainStatus` to report the canonical two-layer drain complete for the promotion scope under the current `admissionEpoch`; current projections expose `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0`, but these fields must include every applicable pre-DSL and evaluated-descriptor state after convergence.
11. Call `ResumeTicks(controlPlaneRequestId, actor, reason)` while Automation admission remains paused. Successful promotion completion requires an idempotent successful tick resume.
12. After `ResumeTicks` succeeds, call `SetAutomationAdmissionMode(..., mode=NORMAL, controlPlaneRequestId, actor, reason)` only after cancellation, purge, schedule/timer reconciliation, pin convergence, and drain complete.
13. Automation & Scripting observes the committed pin event for visibility (not for authority) and treats the pinned patch as the expected active one for tick handoffs.
14. Schedulers use a bounded-staleness pin cache for admission and timer firing decisions. If cached pin data is stale beyond the configured max-age, they must refresh from authoritative control-plane APIs and events before admitting new work. If fresh authoritative pin data cannot be obtained, pre-handler-resolution admission must fail closed with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE` and `admissionReason=pin_state_unavailable` in the ingress response and `script_event_ingress_audit`; `script_event_audit.finalStage` and `script_event_audit.finalOutcome` are reserved for flows after handler resolution. If fresh authoritative pin data is available but differs from the request version for the instance, pre-handler-resolution admission must fail closed with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` and `admissionReason=pin_state_mismatch_requested_vs_observed`; Automation must not silently substitute a patch.
15. Operators monitor `script_event_audit` and the Table 4 metric consequences, using the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md) for local audit and handoff diagnostics; per-event correlation uses `scriptEventId` in audit, logs, and traces, not metric labels.

If promotion fails after `PauseTicks`, it must not automatically clear either fence. The operator may issue an explicit idempotent `ResumeTicks` only after the durable promotion state is safe to resume; otherwise ticks and admission remain paused for operator action. The admission mode returns to `NORMAL` only after that successful resume.

## Patch Rollback (Operator-Driven, Required)

1. Call `SetAutomationAdmissionMode(..., mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId, actor, reason)` for the affected scope. This pauses external, scheduler, and timer admission before any tick barrier is acquired; keep it active through reconciliation, cancellation, purge, convergence, and drain.
2. After the Automation admission barrier is acknowledged, call `PauseTicks` for the same scope with `controlPlaneRequestId`, `actor`, and `reason`. A future operation may acquire both barriers atomically, but it must not pause ticks first while Automation admission remains open.
3. Call `RollbackScriptPatchVersion` (or `SetPinnedScriptPatchVersion`) with `controlPlaneRequestId`, `actor`, and `reason` to repin to the target known-good patch.
4. Automation & Scripting must perform and durably complete schedule/timer reconciliation immediately after repin and before cancel or purge while the admission barrier remains active; the system-owned mutation records the same `controlPlaneRequestId`, `requestedBy`, and `reason`, plus `executedBy=system:automation`:
   - timers owned by the displaced patch or plugin version are retired only after their target replacement identity is durable, or tombstoned when no target schedule exists;
   - only schedules present in the rollback target with matching `scheduleDefinitionId`, `playableStateScope`, and `scheduleSemanticsHash` may carry due state forward;
   - replacement creation and retirement are one atomic durable result, or a resumable and idempotent operation that creates or confirms the target schedule identity before retiring the displaced row; an interrupted rollback must not lose a schedule;
   - reconciliation creates no firing claim or `scriptEventId`; those are deferred until a due candidate passes admission;
   - cancellation of outbox work alone is not sufficient rollback cleanup.
5. Call `CancelPendingWorkItemsForPatch` in Automation & Scripting for the rolled-back patch with `controlPlaneRequestId`, `actor`, and `reason`.
6. If plugin versions are also being rolled back, disabled, or revoked, call `CancelPendingWorkItemsForPluginVersion` with `controlPlaneRequestId`, `actor`, and `reason`.
7. Call `PurgeQueuedTickCommandsForScriptPatch` (and, if applicable, `PurgeQueuedTickCommandsForPluginVersion`) with `controlPlaneRequestId`, `actor`, and `reason` so mismatched queued entries do not accumulate after repin.
8. Wait for the [Pin Convergence Acknowledgment Predicate](#pin-convergence-acknowledgment-predicate) to succeed for the new pin before its deadline.
9. Wait for a fresh authoritative `GetAutomationDrainStatus` response, taken after the final reconciliation, cancellation, and purge step, to report the canonical two-layer drain complete for the rollback scope. Its `admissionEpoch` must match the current rollback-scope epoch; a cached, stale, or earlier-epoch response cannot authorize resume. Current projections expose `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0`, but these fields must include every applicable pre-DSL and evaluated-descriptor state after convergence.
10. Resume ticks with `ResumeTicks(controlPlaneRequestId, actor, reason)` while Automation admission remains paused.
11. After `ResumeTicks` succeeds, call `SetAutomationAdmissionMode(..., mode=NORMAL, controlPlaneRequestId, actor, reason)`. Only then may the workflow complete.

Concrete example:

- `tenantId=11111111-1111-4111-8111-111111111111`, `gameInstanceId=44444444-4444-4444-8444-444444444444`, current pin `P22`, rollback target `P21`, `controlPlaneRequestId=RB-42`.
- Step 1: `SetAutomationAdmissionMode(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 2: after the admission barrier is acknowledged, `PauseTicks(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 3: `RollbackScriptPatchVersion(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, targetScriptPatchVersion=P21, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 4: Run the system-owned durable schedule/timer reconciliation for target `P21` immediately after repin while the admission barrier remains active; replacement creation and `P22` retirement are one atomic durable result, or a resumable idempotent operation that creates or confirms the `P21` schedule identity before retiring `P22`. It carries due state only when `scheduleDefinitionId`, `playableStateScope`, and `scheduleSemanticsHash` all match, creates no firing claim or `scriptEventId`, and records `controlPlaneRequestId=RB-42`, `requestedBy=operator:alice`, `executedBy=system:automation`, and `reason="rollback RB-42"`.
- Step 5: Run patch-scoped cancellation for displaced `P22` work with `controlPlaneRequestId=RB-42`, `actor=operator:alice`, and `reason="rollback RB-42"`; if plugin versions are also rolled back, run the corresponding plugin-scoped cancellation.
- Step 6: Purge queued tick commands for displaced `P22` patch and plugin versions with the same request, actor, and reason.
- Step 7: Apply the [Pin Convergence Acknowledgment Predicate](#pin-convergence-acknowledgment-predicate) to `GetAutomationPinConvergence` and `GetGameSessionPinConvergence` for `controlPlaneRequestId=RB-42` and target `P21`; do not treat one owner's acknowledgment or a stale/out-of-bound response as convergence.
- Step 8: Poll `GetAutomationDrainStatus(11111111-1111-4111-8111-111111111111, 44444444-4444-4444-8444-444444444444)` until the canonical two-layer drain is complete. **Target state only:** cancel `PENDING_EVALUATION` durably without entering the DSL; for `EVALUATING`, resume committed descriptors without DSL re-entry, transition an explicitly canceled uncommitted trigger to terminal `CANCELED`, and transition an expired stale uncommitted trigger to terminal `DEAD_LETTERED` with audited `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`. **Current behavior:** every handoff-capable `PENDING_EVALUATION` remains counted as pending and an expired `EVALUATING` remains active/unresolved, so drain completion remains fail-closed and lease expiry must not be treated as terminalization, reclaim, or safe resumption.
- Step 9: `ResumeTicks(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` while Automation admission remains paused.
- Step 10: After `ResumeTicks` succeeds, `SetAutomationAdmissionMode(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, mode=NORMAL, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.

Ordering is intentional: the admission barrier precedes repin and remains active through reconciliation, cancellation, purge, convergence, and drain; `ResumeTicks` runs while admission is still paused, and Automation returns to `NORMAL` only after `ResumeTicks` succeeds.

## Two-Stage Event Deduplication

Rollback and normal event ingress use two deduplication stages:

- Before handler resolution, incoming request dedupe uses event-scope identity. `scriptId` and `bindingId` are unavailable at ingress and must not be invented.
- After binding resolution, each resolved handler dedupes independently by the full applicable Trigger Identity and retains its own handler-scoped audit outcome.

## Command Identity and Live Handoff Boundary

Rollback diagnosis uses the command-identity contract rather than treating a handler-level trigger identity as a command identity:

- **Target state:** Game Session rejects queued commands whose embedded `scriptPatchVersion` or plugin version does not match the current instance pin and records one per-command handoff result through `ListScriptHandoffEvents` / `commandHandoffDispositions[]`. Each result retains the complete applicable Trigger Identity and command identity, including `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, `isDryRun`, `automationDispatchId`, `outboxWorkItemId`, and `commandOrdinal`; plugin handlers additionally retain `pluginId`, `pluginVersionId`, and `bindingId`, while scheduler/timer handlers additionally retain `scheduleDefinitionId`, `triggerMode`, and exactly one due-point field (`dueTickId` or `dueAt`). Per-command durable progress is independent: the already-owned `(automationDispatchId, commandOrdinal)` discriminator remains linked to `outboxWorkItemId`, and retries resume unresolved commands without dropping unattempted commands or replaying accepted or terminally rejected commands.
- **Current live boundary:** `EnqueueAutomationCommandIfAbsent` carries `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, optional `dueTickId`, `automationDispatchId`, `automationWorkItemId`, `scriptId`, `scriptPatchVersion`, target entity, rendered command text, `requiresSoloTick`, `pluginId`, `pluginVersionId`, `playableStateScope`, routing fields, and origin-source fields. It does not yet carry the target `commandOrdinal`, complete event/command identity, or complete scheduler metadata, including `bindingId`, `eventType`, `eventSchemaVersion`, `scriptEventId`, `isDryRun`, `scheduleDefinitionId`, and `triggerMode`; `dueTickId` is present when applicable. Until the producer and consumer carry stable per-command identity and dedupe, multi-command work items must be rejected rather than admitted. The live diagnostic/retry fallback correlates `script_event_audit`, Automation's narrower handoff/work-item rows, parent `outboxWorkItemId`, `automationDispatchId`, `gameSessionCommandId`, and the Game Session command/result/fence fields currently exposed; this does not establish complete Trigger Identity, target command-level deduplication, or complete fence proof.

Operators must therefore use the current fallback for live rollback diagnosis and must not infer a complete per-command execution disposition from fields the live handoff does not carry. The target per-command view is the contract to converge to; it does not describe current proto coverage.

## Rollback Orchestration State Machine (Required)

Rollback orchestration must expose and persist a state machine so partial failures are recoverable and retries are deterministic.

Ownership and source-of-truth requirements:

- Game Session is the producer-of-record for rollback orchestration state keyed by `controlPlaneRequestId`.
- Logging & Admin may expose convenience orchestration APIs, but these must call the Game Session workflow APIs and read back the same canonical workflow state; they must not persist a competing rollback-state machine.
- Automation & Scripting participates via idempotent step APIs (`SetAutomationAdmissionMode`, cancel/purge hooks, convergence reads) and must not infer orchestration completion from local state alone.

Required states:

- `PAUSING` -> `REPINNING` -> `RECONCILING_SCHEDULES` -> `CANCELING` -> `PURGING` -> `CONVERGING` -> `DRAINING` -> `RESUMING` (invoke `ResumeTicks`, then set admission `NORMAL`) -> `COMPLETED`
- Terminal failure state: `ROLLBACK_CONVERGENCE_TIMEOUT`

State rules:

- Each transition must be idempotent and keyed by `controlPlaneRequestId`.
- Re-running a request in the same state must return current state, not restart from scratch.
- `RECONCILING_SCHEDULES` must complete before timer admission, normal admission, or tick resumption can proceed. Replacement creation and displaced-row retirement must be one atomic durable result or a resumable, idempotent operation keyed by `controlPlaneRequestId`; retries must create or confirm only target-version schedule identities with matching `scheduleDefinitionId`, `playableStateScope`, and `scheduleSemanticsHash` before retiring displaced rows, so an interrupted rollback cannot lose a schedule. Reconciliation must not create firing claims or `scriptEventId`; those are deferred until a due candidate passes admission.
- Failures in `CANCELING`, `PURGING`, or `RESUMING` must not auto-resume admission or ticks; admission remains paused until `ResumeTicks` succeeds and the `NORMAL` transition succeeds.
- Operator retries must continue from the last durable state.
- `CONVERGING` transitions to `DRAINING` only after the Pin Convergence Acknowledgment Predicate succeeds. If the configured deadline is reached while that predicate remains unsatisfied, `CONVERGING` transitions directly and durably to terminal `ROLLBACK_CONVERGENCE_TIMEOUT`; it must not advance to `DRAINING`, `RESUMING`, or `COMPLETED`.
- `ROLLBACK_CONVERGENCE_TIMEOUT` keeps admission and ticks paused until explicit operator action.
- `DRAINING` is required. Rollback must not resume admission or ticks until a fresh authoritative `GetAutomationDrainStatus` response, taken after final reconciliation, cancellation, and purge, reports both counts zero for the current rollback-scope `admissionEpoch`. **Target-state mapping:** `activeExecutionCount` is exactly `EVALUATING` pre-DSL triggers plus `HANDOFF_IN_FLIGHT` evaluated descriptors; `pendingCancelableWorkItemCount` is exactly `PENDING_EVALUATION` pre-DSL triggers plus `PENDING`/`INDEXED` evaluated descriptors. A cached, stale, or earlier-epoch response is unsatisfied evidence.
- **Current fail-closed mapping:** the live projection counts `EVALUATING`, including unresolved stale rows, and `HANDOFF_IN_FLIGHT` as active, while every handoff-capable `PENDING_EVALUATION` is pending because the separate `PENDING`/`INDEXED` descriptor layer is not yet persisted. Any nonzero count keeps `DRAINING` active.
- During target-state cancellation and `DRAINING`, `PENDING_EVALUATION` transitions durably to terminal `CANCELED` with `finalStage=ADMISSION`, `finalOutcome=canceled`, and the bounded rollback/operator reason without entering the DSL. An `EVALUATING` trigger is fenced and its descriptor-commit marker is read first: committed descriptors resume without DSL re-entry; an explicitly canceled uncommitted trigger transitions to terminal `CANCELED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and the bounded cancellation reason; an expired stale uncommitted trigger transitions to terminal `DEAD_LETTERED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`. Drain remains closed until each such trigger is terminal or its committed descriptors reach terminal descriptor states, without re-entering the DSL.

## Pin Convergence Acknowledgment Predicate

Promotion and rollback pin convergence succeeds only when, before the configured deadline, both fresh owner acknowledgments are present for the same scope and request:

- Game Session reports the requested target `scriptPatchVersion` and `controlPlaneRequestId` from a fresh authoritative convergence read.
- Automation & Scripting reports the requested target `scriptPatchVersion` and `controlPlaneRequestId`, `isProjectionStale=false`, and `projectionLagMs` within the configured `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` bound from a fresh authoritative convergence read.

An acknowledgment is fresh only when it reflects this promotion/rollback operation rather than a stale cache or an earlier request, and it must arrive before the deadline. A missing, stale, mismatched, or out-of-bound acknowledgment from either owner is a non-terminal unsatisfied observation until the configured deadline. Fresh matching acknowledgments from both owners before the deadline satisfy the predicate; one owner's successful acknowledgment never substitutes for the other's.

Convergence timeout semantics (required):

- Rollback orchestration must apply a bounded convergence timeout (for example `ROLLBACK_CONVERGENCE_TIMEOUT_MS`) to the convergence wait.
- Before the deadline, missing, stale, mismatched, or out-of-bound acknowledgments leave convergence unsatisfied and the workflow continues authoritative reads; they do not by themselves enter a terminal state.
- If the deadline is reached before the [Pin Convergence Acknowledgment Predicate](#pin-convergence-acknowledgment-predicate) succeeds, including because either owner's acknowledgment remains missing, stale, mismatched, or out-of-bound at the deadline, the rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In `ROLLBACK_CONVERGENCE_TIMEOUT`, Automation admission remains paused for scope safety and ticks remain paused until an operator explicitly issues the supported idempotent `ResumeTicks` action after the durable rollback state is safe to resume.
- The system must emit terminal event `ScriptRollbackConvergenceTimedOut` and apply the Table 4 terminal-timeout metric consequence exactly once when the terminal state is entered.
- While timeout terminal state remains active, pre-handler ingress must return `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK` (the existing rollback backpressure response enum), and bounded `admissionReason=rollback_convergence_timeout`; `script_event_ingress_audit` must record the same event-scope admission outcome and reason. `scriptId` and `bindingId` are unavailable for this decision. No handler-scoped `script_event_audit` row may use `finalOutcome=rollback_convergence_timeout`; already-resolved handlers retain their applicable handler-scoped outcome.

## Pin-State Degraded Operations Policy (Required)

`pin_state_unavailable` is fail-closed by default. Any override mode must be explicit and tightly constrained:

- Override must be activated by an authenticated operator action with `controlPlaneRequestId`, `actor`, `reason`, and a bounded TTL.
- Override scope must be explicit (`tenantId` + `gameInstanceId` minimum).
- Override must emit control-plane audit and event records so post-incident reconciliation can prove exactly when fail-closed behavior was bypassed.
- On TTL expiry, fail-closed behavior (`pin_state_unavailable`) must resume automatically.

Notes:

- Even without an explicit purge, Game Session’s version fence prevents execution of commands produced under the rolled-back patch, but rollback must still drain and purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation such as restore, rollback data, or targeted admin operations.
