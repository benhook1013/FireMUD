# Automation & Scripting Service Operations

This document collects the service readiness model, quota and fairness behavior, rollback and convergence operational expectations, and operator-facing observability guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Operators and SREs should pair this document with [Scripting Quotas and Operations](../../system-architecture-scripting-quotas-and-operations.md), [Scripting Operations Cookbook](../../system-architecture-scripting-operations-cookbook.md), [Scripting Observability Contract](../../system-architecture-scripting-observability-contract.md), and [Redis Architecture](../../system-architecture-redis.md).

## Implementation Status

The current service implementation still combines pre-DSL trigger state and later handoff processing in its work-item row, lacks evaluation lease/fencing-generation recovery, and does not carry stable per-command identity and dedupe through the live producer/consumer boundary. Multi-command work items are therefore not a conformant admitted capability until that boundary is widened. The target durable boundary, state mapping, and recovery behavior are owned by [Scripting Runtime Execution](../../system-architecture-scripting-runtime-execution.md#pre-dsl-trigger-and-evaluated-descriptor-boundary).

## Readiness and Liveness

- `liveness` is local-only and indicates that the process is alive and the scheduler/runtime loops are not wedged.
- `readiness` is runtime-safety for the currently exposed automation slice. The service is ready only when its durable PostgreSQL state, required Redis coordination paths, and the script-patch readiness ingestion loop are able to accept and reconcile new work safely.
- A process that can answer `Ping` locally but cannot ingest tenant patch updates, persist automation work, or reconcile runtime-scope pin changes is not ready for new automation traffic.
- Readiness must reflect rollback and convergence safety as well as simple process health, so partially initialized runtimes or failed reload loops remain unready until they return to a safe state.

## Fairness Quotas and Budgets

`ScriptQuotaService` limits how many times a script may execute within a configurable window. Counters are stored in Redis using keys of the form `automation:quota:<tenantId>:<scriptId>`. When a quota is exceeded the event is denied under the owning admission and audit semantics. The metric family, labels, and increment unit for that decision are defined only by [Table 4](../../system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix). Saga orchestration emits separate Saga-specific metrics and must not be conflated with quota enforcement.

Dry-run and test executions use separate budgets and isolated capacity so privileged tooling cannot starve live automation.

The staging Lua script processes only a limited number of events each tick, controlled by `AUTOMATION_TICK_MAX_EVENTS`, to keep automation work predictable.

## Patch Rollout, Rollback, and Convergence

Game Session and Logging & Admin use the script patch visibility APIs and events described in [API Contracts](./api-contracts.md) to decide which `scriptPatchVersion` values may be passed to runtime. The Automation & Scripting Service does not own authoritative pin mutations, but it must expose enough convergence status for rollback orchestration to prove that runtime scopes observed the intended pin.

Rollback orchestration rules:

- Pinning must satisfy base-version cohesion (`patch.baseVersionId == runtimeVersionId` for the instance).
- Rollback convergence waiting is bounded and delegates to the [Pin Convergence Acknowledgment Predicate](../../system-architecture-scripting-rollout-and-rollback.md#pin-convergence-acknowledgment-predicate). Convergence is acknowledged only when the Game Session owner result's exact patch and `scriptPinEpoch` match Automation's observed patch and `observedScriptPinEpoch`, the owner request identity matches the admitted transition, and projection freshness and lag bounds pass; the same patch under a new epoch remains non-converged. This service must not declare convergence from its own acknowledgment; it consumes the owner workflow outcome and keeps new Automation admission paused when the canonical predicate has not succeeded. Routine rollback does not pause ordinary Game Session gameplay ticks; a full gameplay pause is exceptional and belongs to the owner contract for an explicitly unfenced effect family.
- Game Session alone owns the convergence deadline, `ROLLBACK_CONVERGENCE_TIMEOUT` transition, and terminal timeout signal. Automation consumes that durable workflow state or signal; it must not run a competing timeout, emit the timeout signal, or define a local timeout metric. Any metric consequence follows [Table 4](../../system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix).
- Rollback orchestration follows the durable state machine in [Scripting Rollout and Rollback](../../system-architecture-scripting-rollout-and-rollback.md#rollback-orchestration-state-machine-required). This service must resume its idempotent participation from the last durable owner state rather than restarting or accidentally unpausing.
- **Target-state drain mapping:** `DRAINING` remains active until a fresh `GetAutomationDrainStatus` response for the current `{tenantId, gameInstanceId, regionId?}` scope and `admissionEpoch` reports `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0`. `activeExecutionCount` is exactly current-epoch `EVALUATING` pre-DSL triggers plus `HANDOFF_IN_FLIGHT` evaluated descriptors; `pendingCancelableWorkItemCount` is exactly current-epoch `PENDING_EVALUATION` pre-DSL triggers plus `PENDING`/`INDEXED` evaluated descriptors. Terminal states, rows from another scope or epoch, and derived queue pointers are excluded.
- **Current fail-closed counts:** the live projection counts `EVALUATING`, including unresolved stale rows, and `HANDOFF_IN_FLIGHT` as active; it counts every current handoff-capable `PENDING_EVALUATION` row as pending because the separate `PENDING`/`INDEXED` descriptor layer is not yet persisted. Any unresolved count, stale response, or earlier-epoch response keeps `DRAINING` active.
- During target-state `DRAINING`, `PENDING_EVALUATION` is durably canceled without entering the DSL. `EVALUATING` is resolved only after reading the descriptor-commit marker: committed descriptors resume without DSL re-entry; an explicitly canceled uncommitted trigger becomes terminal `CANCELED`, while an expired stale uncommitted trigger becomes terminal `DEAD_LETTERED`. The status, reason, and audit mapping are defined in [Scripting Runtime Execution](../../system-architecture-scripting-runtime-execution.md#pre-dsl-trigger-and-evaluated-descriptor-boundary).

The service must fail closed when the authoritative Game Session pin/epoch is missing, stale, or mismatched. It may continue ordinary non-script runtime work while scoped script admission is unavailable. Cleanup of displaced work is bounded and asynchronous; cleanup completion is not the epoch-fence correctness barrier. Operators must use owner pin/history reads together with the Automation convergence projection and must not repair or purge rows by direct SQL.

## Metrics and Audit Guidance

The authoritative metric-family names, labels, and increment units live only in [Table 4](../../system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix). The examples below are pointers to those definitions, not service-local metric schemas:

- `automation_script_triggers_total`, `automation_script_skips_total`, and `automation_script_triggers_dropped_total` for scheduler activity and drops.
- `automation_script_queue_delay_seconds` and `automation_script_leadership_changes_total` for queue latency and leader stability.
- `automation_script_timer_catchup_truncated_total` for catch-up firings intentionally truncated by resume-window limits.
- `automation_script_tenant_budget_allowed_total` / `automation_script_tenant_budget_denied_total` for bounded operator-facing automation budget pressure, with tenant-specific drilldown coming from audit rows and control-plane reads rather than raw metric labels.
- `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` for quota enforcement and tick integration.
- `automation_script_sandbox_failures_total`, `automation_script_errors_total`, and `automation_script_runtime_seconds` for sandbox and runtime health.

Queue and quota behavior must be observable either through the canonical `cache.automation_queue_*` patterns in `system-architecture-redis-cache.md` or through the mapped automation metrics documented above.

## Operator Guidance

When diagnosing sandbox-related or automation-runtime issues in production, operators should:

- Check `script_event_ingress_audit` for event-scope admission outcomes, and `script_event_audit` records for `finalStage`, `finalOutcome`, `finalReason`, and associated scope fields such as `tenantId`, `scriptId`, `gameInstanceId`, `regionId`, `regionEpoch`, `tickId`, resolved `playableStateScope` (`shared` or `isolated`), and `sourceService` when present. For emitted-command diagnostics, the complete Command-Handoff Identity is target-state only because `commandOrdinal` and the full identity are not exposed at the current live boundary; `(automationDispatchId, commandOrdinal)` is only that identity's dispatch-group suffix. Current correlation uses `outboxWorkItemId`, `automationDispatchId`, `gameSessionCommandId`, and the exposed Game Session command/result/fence fields. Inspect target-state command-handoff records by the complete Command-Handoff Identity only after that boundary is widened, and inspect rendered command text/shape only when required by the canonical command-handoff schema, so shared-state and isolated-state work can be distinguished without treating one handler audit row as one command.
- Inspect sandbox and runtime metrics such as `automation_script_sandbox_failures_total`, `automation_script_runtime_seconds`, and queue delay metrics.
- Verify patch visibility using `GetScriptPatchStatus` and `GetScriptPatchInstanceRolloutStatus`. When a promotion or rollback is active, the named [Pin Convergence Acknowledgment Predicate](../../system-architecture-scripting-rollout-and-rollback.md#pin-convergence-acknowledgment-predicate) must perform both `GetAutomationPinConvergence` and `GetGameSessionPinConvergence` reads and accept convergence only when both fresh responses match the same target `scriptPatchVersion` and `controlPlaneRequestId`; the Automation response must additionally have `isProjectionStale=false` and `projectionLagMs <= SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS`. One owner's acknowledgment never substitutes for the other.
- Verify plugin policy/runtime convergence using `GetPluginStatus`, `ListPluginRuntimeEvents`, and `GetPluginPolicyConvergence` together with the design-time publication reads from Game Design.
