# FireMUD Scripting & Automation: Rollout and Rollback

This document defines operator-driven promotion, rollback, convergence, timeout, and degraded-operations workflows for scripting and plugin control-plane changes. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the underlying RPC contracts and mutable state boundaries, and [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md), which defines the workflow sequencing and cleanup steps.

## Patch Promotion (Operator-Driven)

1. Validate patch is `READY` in Automation & Scripting for the tenant (`GetScriptPatchStatus`).
2. Call `SetPinnedScriptPatchVersion` in Game Session.
3. Game Session emits `ScriptPatchPinChanged`.
4. Call `CancelPendingWorkItemsForPatch` for the previous patch in scope so outbox work produced under displaced patch state cannot continue handing off indefinitely.
5. Call `PurgeQueuedTickCommandsForScriptPatch` for the previous patch (and plugin equivalents when plugin version changes are coupled with the promotion).
6. Automation & Scripting must reconcile durable schedules and timers for the newly pinned patch before timer admission resumes:
   - schedules absent from the newly pinned patch are removed or tombstoned;
   - schedules that still exist may be carried forward only through explicit reconciliation to the new version identity;
   - reconciliation creates only replacement schedule identities; a due candidate must pass admission before the scheduler creates its firing claim or `scriptEventId`;
   - displaced patch or plugin versions must not be able to generate new `scriptEventId` values after promotion.
7. Wait for pin-convergence acknowledgments from both Automation & Scripting and Game Session for the requested `controlPlaneRequestId`.
8. Wait for `GetAutomationDrainStatus` to report `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the promotion scope under the current `admissionEpoch`.
9. Automation & Scripting observes the committed pin event for visibility (not for authority) and treats the pinned patch as the expected active one for tick handoffs.
10. Schedulers use a bounded-staleness pin cache for admission and timer firing decisions. If cached pin data is stale beyond the configured max-age, they must refresh from authoritative control-plane APIs and events before admitting new work. If fresh authoritative pin data cannot be obtained, admission must fail closed with `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`, and an explicit `finalReason`. If fresh authoritative pin data is available but differs from the request version for the instance, admission must fail closed with `finalOutcome=version_unavailable` and a bounded mismatch reason; Automation must not silently substitute a patch.
11. Operators monitor `script_event_audit` and automation metrics; per-event correlation uses `scriptEventId` in audit, logs, and traces, not metric labels.

## Patch Rollback (Operator-Driven, Required)

1. Call `PauseTicks` for the affected scope with `controlPlaneRequestId`, `actor`, and `reason`.
2. Call `SetAutomationAdmissionMode(..., mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId, actor, reason)` for the same scope.
3. Call `RollbackScriptPatchVersion` (or `SetPinnedScriptPatchVersion`) with `controlPlaneRequestId`, `actor`, and `reason` to repin to the target known-good patch.
4. Automation & Scripting must perform and durably complete schedule/timer reconciliation immediately after repin and before cancel or purge; the system-owned mutation records the same `controlPlaneRequestId`, `actor`, and `reason`:
   - timers owned by the displaced patch or plugin version are retired only after their target replacement identity is durable, or tombstoned when no target schedule exists;
   - only schedules present in the rollback target may survive reconciliation;
   - replacement creation and retirement are one atomic durable result, or a resumable and idempotent operation that creates or confirms the target schedule identity before retiring the displaced row; an interrupted rollback must not lose a schedule;
   - reconciliation creates no firing claim or `scriptEventId`; those are deferred until a due candidate passes admission;
   - cancellation of outbox work alone is not sufficient rollback cleanup.
5. Call `CancelPendingWorkItemsForPatch` in Automation & Scripting for the rolled-back patch with `controlPlaneRequestId`, `actor`, and `reason`.
6. If plugin versions are also being rolled back, disabled, or revoked, call `CancelPendingWorkItemsForPluginVersion` with `controlPlaneRequestId`, `actor`, and `reason`.
7. Call `PurgeQueuedTickCommandsForScriptPatch` (and, if applicable, `PurgeQueuedTickCommandsForPluginVersion`) with `controlPlaneRequestId`, `actor`, and `reason` so mismatched queued entries do not accumulate after repin.
8. Wait for pin-convergence acknowledgments from both Automation & Scripting and Game Session for the new pin (`controlPlaneRequestId` must match).
9. Wait for `GetAutomationDrainStatus` to report `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the rollback scope under the current `admissionEpoch`.
10. Call `SetAutomationAdmissionMode(..., mode=NORMAL, controlPlaneRequestId, actor, reason)` once schedule reconciliation, convergence, and cleanup complete.
11. Resume ticks with `ResumeTicks(controlPlaneRequestId, actor, reason)`.

Concrete example:

- `tenantId=11111111-1111-4111-8111-111111111111`, `gameInstanceId=44444444-4444-4444-8444-444444444444`, current pin `P22`, rollback target `P21`, `controlPlaneRequestId=RB-42`.
- Step 1: `PauseTicks(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 2: `SetAutomationAdmissionMode(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 3: `RollbackScriptPatchVersion(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, targetScriptPatchVersion=P21, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 4: Run the system-owned durable schedule/timer reconciliation for target `P21` immediately after repin; replacement creation and `P22` retirement are one atomic durable result, or a resumable idempotent operation that creates or confirms the `P21` schedule identity before retiring `P22`. It carries due state only for matching `scheduleDefinitionId` values, creates no firing claim or `scriptEventId`, and records `controlPlaneRequestId=RB-42`, `actor=system:automation`, and `reason="rollback RB-42"`.
- Step 5: Run patch-scoped cancellation for displaced `P22` work with `controlPlaneRequestId=RB-42`, `actor=operator:alice`, and `reason="rollback RB-42"`; if plugin versions are also rolled back, run the corresponding plugin-scoped cancellation.
- Step 6: Purge queued tick commands for displaced `P22` patch and plugin versions with the same request, actor, and reason.
- Step 7: Poll `GetAutomationPinConvergence(11111111-1111-4111-8111-111111111111, 44444444-4444-4444-8444-444444444444)` and `GetGameSessionPinConvergence(11111111-1111-4111-8111-111111111111, 44444444-4444-4444-8444-444444444444)` until both report `observedPinnedScriptPatchVersion=P21` and `lastObservedControlPlaneRequestId=RB-42`.
- Step 8: Poll `GetAutomationDrainStatus(11111111-1111-4111-8111-111111111111, 44444444-4444-4444-8444-444444444444)` until active executions and cancelable pending work are both zero.
- Step 9: `SetAutomationAdmissionMode(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, mode=NORMAL, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.
- Step 10: `ResumeTicks(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")`.

Ordering is intentional: reconciliation follows repin and precedes cancellation and purge; Automation admission returns to `NORMAL` only after convergence and drain complete, and ticks resume last.

## Rollback Orchestration State Machine (Required)

Rollback orchestration must expose and persist a state machine so partial failures are recoverable and retries are deterministic.

Ownership and source-of-truth requirements:

- Game Session is the producer-of-record for rollback orchestration state keyed by `controlPlaneRequestId`.
- Logging & Admin may expose convenience orchestration APIs, but these must call the Game Session workflow APIs and read back the same canonical workflow state; they must not persist a competing rollback-state machine.
- Automation & Scripting participates via idempotent step APIs (`SetAutomationAdmissionMode`, cancel/purge hooks, convergence reads) and must not infer orchestration completion from local state alone.

Required states:

- `PAUSING` -> `REPINNING` -> `RECONCILING_SCHEDULES` -> `CANCELING` -> `PURGING` -> `CONVERGING` -> `DRAINING` -> `RESUMING` -> `COMPLETED`
- Terminal failure state: `ROLLBACK_CONVERGENCE_TIMEOUT`

State rules:

- Each transition must be idempotent and keyed by `controlPlaneRequestId`.
- Re-running a request in the same state must return current state, not restart from scratch.
- `RECONCILING_SCHEDULES` must complete before timer admission, normal admission, or tick resumption can proceed. Replacement creation and displaced-row retirement must be one atomic durable result or a resumable, idempotent operation keyed by `controlPlaneRequestId`; retries must create or confirm only target-version schedule identities before retiring displaced rows, so an interrupted rollback cannot lose a schedule. Reconciliation must not create firing claims or `scriptEventId`; those are deferred until a due candidate passes admission.
- Failures in `CANCELING` or `PURGING` must not auto-resume admission or ticks.
- Operator retries must continue from the last durable state.
- `ROLLBACK_CONVERGENCE_TIMEOUT` keeps admission and ticks paused until explicit operator action.
- `DRAINING` is required. Rollback must not resume admission or ticks until the current rollback-scope `admissionEpoch` has no active pre-pause executions and no remaining cancelable outbox work according to `GetAutomationDrainStatus`.

Convergence timeout semantics (required):

- Rollback orchestration must apply a bounded convergence timeout (for example `ROLLBACK_CONVERGENCE_TIMEOUT_MS`) to the convergence wait.
- If timeout is reached before both convergence APIs report the expected `controlPlaneRequestId`, the rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In `ROLLBACK_CONVERGENCE_TIMEOUT`, Automation admission remains paused for scope safety and ticks remain paused until an operator explicitly issues resume or abort actions.
- The system must emit terminal event `ScriptRollbackConvergenceTimedOut` and increment `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
- While timeout terminal state remains active, pre-resolution ingress admissions in scope must record an event-scope ingress audit outcome `rollback_convergence_timeout` with a bounded reason. If handler-scoped work is already resolved when the timeout state is observed, its `script_event_audit` row must use `finalStage=ADMISSION`, `finalOutcome=rollback_convergence_timeout`, and a bounded `finalReason`.

## Pin-State Degraded Operations Policy (Required)

`pin_state_unavailable` is fail-closed by default. Any override mode must be explicit and tightly constrained:

- Override must be activated by an authenticated operator action with `controlPlaneRequestId`, `actor`, `reason`, and a bounded TTL.
- Override scope must be explicit (`tenantId` + `gameInstanceId` minimum).
- Override must emit control-plane audit and event records so post-incident reconciliation can prove exactly when fail-closed behavior was bypassed.
- On TTL expiry, fail-closed behavior (`pin_state_unavailable`) must resume automatically.

Notes:

- Even without an explicit purge, Game Session’s version fence prevents execution of commands produced under the rolled-back patch, but rollback must still drain and purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation such as restore, rollback data, or targeted admin operations.
