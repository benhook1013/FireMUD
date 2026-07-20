# FireMUD Scripting & Automation: Rollout and Rollback

This document defines operator-driven promotion, rollback, convergence, timeout, and degraded-operations workflows for scripting and plugin control-plane changes. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the underlying RPC contracts and mutable state boundaries, and [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md), which defines the workflow sequencing and cleanup steps.

## Patch Promotion (Operator-Driven)

1. Validate patch is `READY` in Automation & Scripting for the tenant (`GetScriptPatchStatus`).
2. When the rollout requires instance-specific preparation, ask Automation & Scripting to prepare or preload the exact tenant-`READY` compiled artifact before pin commit. This candidate state is not active authority and cannot admit gameplay work. A failure stops the rollout with the current Game Session pin and `scriptPinEpoch` unchanged.
3. Call `SetPinnedScriptPatchVersion` in Game Session. Game Session atomically commits the exact `scriptPatchVersion`, advances `scriptPinEpoch`, and returns that tuple; same-request retry returns the same committed result.
4. Game Session emits the committed `ScriptPatchPinChanged` event carrying the exact version and epoch for reconstruction and projection.
5. Call `CancelPendingWorkItemsForPatch` for the previous version and epoch in scope so outbox work produced under displaced pin state cannot continue handing off indefinitely.
6. Call `PurgeQueuedTickCommandsForScriptPatch` for the previous version and epoch (and plugin equivalents when plugin version changes are coupled with the promotion).
7. Automation & Scripting must reconcile durable schedules and timers for the newly pinned exact artifact before timer admission resumes:
   - schedules absent from the newly pinned patch are removed or tombstoned;
   - schedules that still exist may be carried forward only through explicit reconciliation to the new version identity;
   - displaced patch or plugin versions must not be able to generate new `scriptEventId` values after promotion.
8. Wait for pin-convergence acknowledgments from both Automation & Scripting and Game Session for the requested `controlPlaneRequestId`, `scriptPatchVersion`, and `scriptPinEpoch`.
9. Wait for `GetAutomationDrainStatus` to report `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the promotion scope under the current `admissionEpoch`.
10. Automation & Scripting observes the committed pin tuple for visibility and exact-version execution, not as authority to choose another active version. Already-started work may finish evaluation against its captured immutable graph, but later persistence, handoff, and gameplay execution remain fenced by the captured version and epoch.
11. Schedulers use a bounded-staleness pin cache for admission and timer firing decisions. If cached pin data is stale beyond the configured max-age, they must refresh from authoritative control-plane APIs and events before admitting new work. If fresh authoritative pin data cannot be obtained, admission must fail closed with `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`, and an explicit `finalReason`. If fresh authoritative pin data differs from the request `(scriptPatchVersion, scriptPinEpoch)`, admission must fail closed with `finalOutcome=version_unavailable` and a bounded mismatch reason; Automation must not silently substitute a patch or epoch.
12. If exact-version loading or reconciliation fails after pin commit, keep new admission fail-closed. Restoring the previous patch requires an explicit repin to that still-`READY`, base-compatible version; Automation does not resume a prior local graph as fallback.
13. Operators monitor `script_event_audit` and automation metrics; per-event correlation uses `scriptEventId` in audit, logs, and traces, not metric labels.

## Patch Rollback (Operator-Driven, Required)

1. Confirm the rollback target remains tenant-`READY` and base-compatible, then prepare or preload the exact artifact while the old pin remains authoritative. Preparation failure leaves the old pin and epoch unchanged.
2. Call `SetAutomationAdmissionMode(..., mode=PAUSED_FOR_ROLLBACK)` for the affected instance so new script triggers do not refill work during cutover.
3. At the serialized Game Session authority boundary, call `RollbackScriptPatchVersion` (or `SetPinnedScriptPatchVersion`). Game Session atomically commits the target and a new `scriptPinEpoch`, even when that version was used before.
4. Automation observes the committed tuple and reconciles its immutable graph, durable schedules, and timers:
   - timers owned by the displaced patch or plugin version are removed or tombstoned;
   - only schedules present in the rollback target may survive reconciliation;
   - cancellation of outbox work alone is not sufficient rollback cleanup.
5. Resume Automation admission only after exact-pin convergence and required schedule reconciliation. Ordinary player ticks and player-command admission continue throughout this routine workflow.
6. Run displaced-version cancel, purge, and drain work asynchronously with bounded retries, retention, metrics, and operator visibility. The final version-and-epoch fence, not cleanup completion, prevents old work from mutating gameplay.
7. If Automation convergence times out, keep Automation admission fail-closed and expose repair or explicit repin actions; do not freeze gameplay or silently fall back.

Concrete example:

- `tenantId=T1`, `gameInstanceId=G7`, current pin `P22`, rollback target `P21`, `controlPlaneRequestId=RB-42`.
- Step 1: Confirm `P21` is still tenant-`READY` and prepare its exact artifact while `P22` remains authoritative.
- Step 2: `SetAutomationAdmissionMode(T1, G7, PAUSED_FOR_ROLLBACK, RB-42)`.
- Step 3: Call `RollbackScriptPatchVersion(T1, G7, P21, RB-42)` at the safe Game Session authority boundary and record the returned `scriptPinEpoch`.
- Step 4: Reconcile Automation to the exact returned tuple, then return Automation admission to normal. Gameplay ticks have continued throughout.
- Step 5: Run patch/plugin-scoped cancel, purge, and drain cleanup for displaced `P22` asynchronously; old-epoch work is already non-applicable at final effect fences.

Ordering is intentional: candidate preparation precedes pin commit, Automation admission resumes only after exact-target reconciliation, and operational cleanup does not determine gameplay availability.

## Rollback Orchestration State Machine (Required)

Rollback orchestration must expose and persist a state machine so partial failures are recoverable and retries are deterministic.

Ownership and source-of-truth requirements:

- Game Session is the producer-of-record for rollback orchestration state keyed by `controlPlaneRequestId`.
- Logging & Admin may expose convenience orchestration APIs, but these must call the Game Session workflow APIs and read back the same canonical workflow state; they must not persist a competing rollback-state machine.
- Automation & Scripting participates via idempotent step APIs (`SetAutomationAdmissionMode`, cancel/purge hooks, convergence reads) and must not infer orchestration completion from local state alone.

Required semantic progress is `PREPARING_TARGET -> AUTOMATION_PAUSED -> PIN_COMMITTED -> RECONCILING -> COMPLETED`, with terminal Automation outcome `ROLLBACK_CONVERGENCE_TIMEOUT`. Cancel, purge, and drain progress is recorded separately as asynchronous cleanup rather than inserted into the correctness-critical path.

State rules:

- Each transition must be idempotent and keyed by `controlPlaneRequestId`.
- Re-running a request in the same state must return current state, not restart from scratch.
- Cleanup failure remains visible and retries, but it does not freeze player ticks or undo a committed pin.
- Operator retries must continue from the last durable state.
- `ROLLBACK_CONVERGENCE_TIMEOUT` keeps Automation admission paused until repair or explicit repin; ordinary gameplay continues.

Convergence timeout semantics (required):

- Rollback orchestration must apply a bounded Automation convergence timeout.
- If timeout is reached before both convergence APIs report the expected `controlPlaneRequestId`, the rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In `ROLLBACK_CONVERGENCE_TIMEOUT`, Automation admission remains paused for scope safety while gameplay ticks continue.
- The system must emit terminal event `ScriptRollbackConvergenceTimedOut` and increment `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
- While timeout terminal state remains active, pre-resolution ingress admissions in scope must record an event-scope ingress audit outcome `rollback_convergence_timeout` with a bounded reason. If handler-scoped work is already resolved when the timeout state is observed, its `script_event_audit` row must use `finalStage=ADMISSION`, `finalOutcome=rollback_convergence_timeout`, and a bounded `finalReason`.

## Pin-State Degraded Operations Policy (Required)

`pin_state_unavailable` is fail-closed by default. Any override mode must be explicit and tightly constrained:

- Override must be activated by an authenticated operator action with `controlPlaneRequestId`, `actor`, `reason`, and a bounded TTL.
- Override scope must be explicit (`tenantId` + `gameInstanceId` minimum).
- Override must emit control-plane audit and event records so post-incident reconciliation can prove exactly when fail-closed behavior was bypassed.
- On TTL expiry, fail-closed behavior (`pin_state_unavailable`) must resume automatically.

Notes:

- Even without an explicit purge, Game Session’s version-and-epoch fence prevents execution of commands produced under the displaced pin, but rollback must still drain and purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation such as restore, rollback data, or targeted admin operations.
