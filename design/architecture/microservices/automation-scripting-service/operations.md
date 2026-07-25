# Automation & Scripting Service Operations

This document collects the service readiness model, quota and fairness behavior, rollback and convergence operational expectations, and operator-facing observability guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Operators and SREs should pair this document with [Scripting Quotas and Operations](../../system-architecture-scripting-quotas-and-operations.md), [Scripting Operations Cookbook](../../system-architecture-scripting-operations-cookbook.md), [Scripting Observability Contract](../../system-architecture-scripting-observability-contract.md), and [Redis Architecture](../../system-architecture-redis.md).

## Readiness and Liveness

- `liveness` is local-only and indicates that the process is alive and the scheduler/runtime loops are not wedged.
- `readiness` is runtime-safety for the currently exposed automation slice. The service is ready only when its durable PostgreSQL state, required Redis coordination paths, and the script-patch readiness ingestion loop are able to accept and reconcile new work safely.
- A process that can answer `Ping` locally but cannot ingest tenant patch updates, persist automation work, or reconcile runtime-scope pin changes is not ready for new automation traffic.
- Readiness must reflect rollback and convergence safety as well as simple process health, so partially initialized runtimes or failed reload loops remain unready until they return to a safe state.

## Fairness Quotas and Budgets

`ScriptQuotaService` limits how many times a script may execute within a configurable window. Counters are stored in Redis using keys of the form `automation:quota:<tenantId>:<scriptId>`. When a quota is exceeded the event is ignored and `script_quota_denied_total{scope, script_category, reason}` is incremented. Saga orchestration emits separate Saga-specific metrics and must not be conflated with quota enforcement.

Dry-run and test executions use separate budgets and isolated capacity so privileged tooling cannot starve live automation.

The staging Lua script processes only a limited number of events each tick, controlled by `AUTOMATION_TICK_MAX_EVENTS`, to keep automation work predictable.

## Patch Rollout, Rollback, and Convergence

Game Session and Logging & Admin use the script patch visibility APIs and events described in [API Contracts](./api-contracts.md) to decide which `scriptPatchVersion` values may be passed to runtime. The Automation & Scripting Service does not own authoritative pin mutations, but it must expose enough convergence status for rollback orchestration to prove that runtime scopes observed the intended pin.

Rollback orchestration rules:

- Pinning must satisfy base-version cohesion (`patch.baseVersionId == runtimeVersionId` for the instance).
- Rollback convergence waiting is bounded. If `GetAutomationPinConvergence` plus Game Session convergence checks do not match the expected `controlPlaneRequestId` before the configured timeout, rollback enters terminal timeout state (`ROLLBACK_CONVERGENCE_TIMEOUT`) and admission and ticks remain paused until explicit operator action.
- Timeout transition must emit `ScriptRollbackConvergenceTimedOut` and increment `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
- Rollback orchestration should be implemented as an explicit durable state machine (`PAUSING`, `REPINNING`, `RECONCILING_SCHEDULES`, `CANCELING`, `PURGING`, `CONVERGING`, `DRAINING`, `RESUMING`, `COMPLETED`, terminal `ROLLBACK_CONVERGENCE_TIMEOUT`) so partial failures can resume from last durable state instead of restarting or accidentally unpausing.
- `DRAINING` remains active until `GetAutomationDrainStatus` confirms that the current rollback-scope `admissionEpoch` has no active pre-pause executions and no remaining cancelable outbox work.

## Metrics and Audit Guidance

The authoritative observability contract lives in [Scripting Observability Contract](../../system-architecture-scripting-observability-contract.md). Service-level metric examples include:

- `automation_script_triggers_total`, `automation_script_skips_total`, and `automation_script_triggers_dropped_total` for scheduler activity and drops.
- `automation_script_queue_delay_seconds` and `automation_script_leadership_changes_total` for queue latency and leader stability.
- `automation_script_timer_catchup_truncated_total` for catch-up firings intentionally truncated by resume-window limits.
- `automation_script_tenant_budget_allowed_total{scope, tier}` / `automation_script_tenant_budget_denied_total{scope, tier}` for bounded operator-facing automation budget pressure, with tenant-specific drilldown coming from audit rows and control-plane reads rather than raw metric labels.
- `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` for quota enforcement and tick integration.
- `automation_script_sandbox_failures_total{scope, script_category, reason}`, `automation_script_errors_total{scope, script_category, reason}`, and `automation_script_runtime_seconds{scope, script_category, eventType}` for sandbox and runtime health.

Queue and quota behavior must be observable either through the canonical `cache.automation_queue_*` patterns in `system-architecture-redis-cache.md` or through the mapped automation metrics documented above.

## Operator Guidance

When diagnosing sandbox-related or automation-runtime issues in production, operators should:

- Check `script_event_audit` records for `finalStage`, `finalOutcome`, `finalReason`, and associated scope fields such as `tenantId`, `scriptId`, `gameInstanceId`, `regionId`, `regionEpoch`, `tickId`, resolved `playableStateScope` (`shared` or `isolated`), and `sourceService` when present. For emitted commands, inspect the supplementary command-handoff records for `automationDispatchId`, Game Session command id, and handoff outcome/reason; inspect rendered command text/shape only when required by the canonical command-handoff schema, so shared-state and isolated-state work can be distinguished without treating one handler audit row as one command.
- Inspect sandbox and runtime metrics such as `automation_script_sandbox_failures_total`, `automation_script_runtime_seconds`, and queue delay metrics.
- Verify patch and pin convergence using `GetScriptPatchStatus`, `GetScriptPatchInstanceRolloutStatus`, and `GetAutomationPinConvergence`.
- Verify plugin policy/runtime convergence using `GetPluginStatus`, `ListPluginRuntimeEvents`, and `GetPluginPolicyConvergence` together with the design-time publication reads from Game Design.
