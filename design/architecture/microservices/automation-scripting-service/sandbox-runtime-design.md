# Automation & Scripting Service: Script Sandbox & Resource Limits

This document describes how the Automation & Scripting Service enforces script sandboxing, CPU and memory limits, and what operators observe when limits are hit. It refines the high-level behavior described in:

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-normative-contract-tables.md`

## Implementation Status

This document describes the **target-state architecture** for script sandboxing and resource limits. Together with `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`, it is the canonical specification for sandbox semantics; the high-level hub in `design/architecture/system-architecture-scripting.md` summarizes behavior and should defer to this document when there is any conflict.

For the latest progress and implementation notes, see:

- `design/project-management/implementation-tracking/automation-and-scheduler-runtime.md`
- `design/architecture/system-architecture-scripting.md#sandboxing--security`

Implementation-progress tracking policy:

- Keep implementation-progress status in the task list, not in this normative design doc.
- If this doc includes a status note for an incident or rollout reason, it must include `verifiedDate`, `verifiedBy`, and `verifiedCommit`.

The table below captures the required sandbox behavior contract (target-state semantics), independent of current rollout phase.

| Feature | Contract requirement |
| --- | --- |
| Per-run wall-clock timeout | Abort a script run that exceeds its allocated wall-clock budget and record `finalStage=DSL_EVAL` with `finalOutcome=sandbox_error` and `finalReason=cpu_budget_exceeded`. |
| Iteration / loop guards | Enforce per-run iteration limits so even bounded loops cannot hot-loop indefinitely. |
| Soft memory guards | Approximate tracking of script-local data sizes and early abort with `finalOutcome=sandbox_error` and `finalReason=memory_budget_exceeded` before JVM OOM. |
| Outcome taxonomy | Use canonical stage-aware audit outcomes (`finalStage` + `finalOutcome` / `finalReason`) in `script_event_audit` consistent with the observability and normative contract docs. |
| Failure-rate circuit breaker integration | Use live-traffic sandbox failures to transition scripts into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, with dry-run/test isolation by default. |
| Test / dry-run parity | Dry-run executions share the same sandbox limits as live runs while remaining isolated for quotas, budgets, and metrics. |
| Plugin sandbox reuse | Plugins run in the same sandbox engine with component allowlists and stricter quotas where policy requires. |

---

## Goals

- Keep script execution **safe and predictable** for multi-tenant games.
- Bound **CPU time** and **memory usage** per script and per tenant.
- Ensure **deterministic, auditable outcomes** for any sandbox failure.
- Integrate cleanly with the existing **tick**, **quota**, and **multi-tenancy** models.

---

## Sandbox Model Overview

Sandboxing for scripts is implemented as a **multi-layer model**:

1. **Language / DSL sandbox**
   - Scripts are defined as **graphs of curated components**, not arbitrary code.
   - The runtime exposes only vetted operations and DTOs; user scripts cannot open sockets, allocate arbitrary threads, or call reflection APIs.
   - Input sizes (for example, text, arrays, collections) are validated and clamped before being passed into script evaluation.

2. **Execution sandbox**
   - Scripts run inside a **dedicated executor** within the Automation & Scripting Service.
   - Each script run receives a **budget token** derived from `AUTOMATION_TICK_BUDGET_MS`, per-script quotas, and per-tenant budgets.
   - Charge points are fixed by the lifecycle contract:
     - per-script quota is charged once per handler-scoped admission,
     - tenant and cluster execution budgets are charged when the run is reserved onto sandbox capacity,
     - no post-admission failure refunds already consumed execution budget.
   - The executor enforces **wall-clock timeouts** and **iteration limits**; when a budget is exhausted, the run is terminated and recorded as a sandbox failure.

3. **Process / container isolation**
   - The service runs in containers with **Kubernetes CPU and memory limits**.
   - Catastrophic failures (for example, heap exhaustion, JVM `OutOfMemoryError`) are treated as **infrastructure errors** and surfaced via `infrastructure_error` outcomes and standard platform alerts.

The remainder of this document focuses on the execution sandbox and how CPU and memory limits translate into observable behavior.

---

## Script Execution Lifecycle

Each script run follows a consistent lifecycle:

1. **Trigger admission**
   - A trigger arrives from the scheduler (event, timer, interval, or manual test run).
   - `ScriptQuotaService` checks per-script and per-tenant quotas. If the quota is exceeded, the trigger is rejected at admission (`finalStage=ADMISSION`, `finalOutcome=quota_denied`) and no sandbox work occurs.

2. **Sandbox setup**
   - The scheduler allocates a **sandbox context** containing:
     - Tenant and script identifiers
     - The pinned `scriptPatchVersion`
     - Per-run budgets (CPU/time, memory, and concurrency)
   - The run is submitted to a **bounded thread pool** dedicated to script execution.
   - Dry-run/test work must use isolated execution capacity (separate pool, reserved worker share, or equivalent partition) so live automation retains guaranteed worker availability under load.

3. **Graph evaluation**
   - The engine evaluates the script’s component graph:
     - It walks nodes and edges in topological order, honoring bounded loops.
     - It periodically checks the remaining **time budget** and **iteration budget**.
   - If a budget check fails or a runtime guard trips (for example, too-large payload), evaluation is interrupted with a sandbox error.

4. **Command staging**
   - Successful runs emit a list of commands which are persisted as part of a durable work item (outbox) and then indexed into the rebuildable `automation:queue:*` projection for later durable execution.
   - Before persistence, the engine must enforce explicit output budgets such as `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes`; exceeding those ceilings is a non-success outcome and must not partially commit an oversized work item.
   - Handoff is subject to automation execution limits (`AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) and uses only documented `automation:*` Redis prefixes for projection and quotas. The Automation & Scripting Service never writes `tick:*` keys directly; it hands off commands to Game Session over internal gRPC so Game Session can enqueue tick commands under its own tick and locking model.

5. **Outcome recording**
   - The engine records a **stage-aware outcome** for the run in `script_event_audit`:
     - `finalStage` (`ADMISSION`, `DSL_EVAL`, `WORK_ITEM_PERSIST`, `TICK_HANDOFF`)
     - `finalOutcome` and `finalReason`
   - Pre-admission quota denials (`finalStage=ADMISSION`, `finalOutcome=quota_denied`) are handled by `ScriptQuotaService` before sandbox work begins and do **not** contribute to sandbox failure metrics; sandbox errors (`finalStage=DSL_EVAL`, `finalOutcome=sandbox_error`) do, and are considered by the failure-rate circuit breaker. Dry-run/test executions must emit failures via test-only metrics (for example `automation_script_test_sandbox_failures_total`) rather than incrementing live-traffic error counters. See `design/architecture/system-architecture-scripting-observability-contract.md` for the authoritative metric families and label sets.

---

## CPU / Time Limits

### Budget Source

CPU and time limits are derived from three layers:

- **Per-script configuration**
  - Scripts define scheduling hints (`intervalTicks`, `maxConcurrent`, `priorityTag`).
  - These hints produce a **per-run budget** so that high-priority scripts can consume more time per run than background scripts, while still respecting global caps.

- **Automation tick configuration**
  - `AUTOMATION_TICK_BUDGET_MS` defines the **soft budget** for automation work per tick window.
  - The scheduler divides this budget across eligible scripts for that tenant and region when deciding how many runs to start.

- **Cluster policies**
  - Cluster-level policies define absolute ceilings per container (for example, 100 ms per run, 500 ms per tick window) to protect overall latency and resource usage.

### Enforcement Mechanism

Time limits are enforced using a combination of:

- **Timed executor**
  - Each run is submitted as a task with a **deadline**.
  - The executor periodically compares the current time against the deadline using a monotonic clock.

- **Cooperative checks in the engine**
  - The graph evaluation loop checks the remaining time after:
    - Visiting a node
    - Emitting commands
    - Completing a bounded loop iteration
  - If the remaining time is zero or negative, the engine aborts the run with `sandbox_error` / `reason=cpu_budget_exceeded`.

This approach keeps enforcement deterministic and observable without relying on low-level CPU accounting inside the JVM.

### Observable Failure Modes for CPU Limits

When a script exceeds its CPU/time budget:

- The run stops immediately; no further nodes are evaluated.
- Any commands already staged for the current run are **discarded** before commit.
- The `script_event_audit` record is written with:
  - `finalStage = DSL_EVAL`
  - `finalOutcome = sandbox_error`
  - `finalReason = cpu_budget_exceeded`
  - The elapsed time and node count
- Metrics are incremented:
  - `automation_script_sandbox_failures_total{reason="cpu_budget_exceeded"}`
  - `automation_script_runtime_seconds` (histogram bucket for the partial run)

Repeated CPU budget violations contribute to the failure-rate circuit breaker. Once thresholds are exceeded, the script transitions to `runtimeStatus=DISABLED_DUE_TO_ERRORS` and new triggers are skipped until an administrator intervenes.

---

## Memory Limits

### Design Principles

Memory safety relies on three complementary mechanisms:

1. **Bounded data inputs**
   - Script-visible collections and payloads (for example, NPC memory entries, text blobs, lists of nearby entities) are sized and paged before being handed to the engine.
   - The engine enforces **maximum collection sizes** and rejects attempts to construct or accumulate larger structures inside a single run.

2. **Lightweight script state**
   - Component evaluations operate on small, immutable DTOs rather than arbitrary data structures.
   - Long-term state (for example, NPC memory) persists in the database and Redis, not in heap-bound structures that grow without limit.

3. **Container and JVM limits**
   - Containers and JVM options enforce hard ceilings on memory usage.
   - Catastrophic violations (for example, `OutOfMemoryError`) cause the container to be restarted and are treated as infrastructure issues, not script-level sandbox errors.

### Memory Enforcement Mechanism

Within a script run, the engine implements **soft memory guards**:

- It tracks the approximate size of script-local data structures (for example, number of nodes visited, buffered commands, and in-memory collections).
- When a run attempts to exceed configured thresholds, the engine aborts evaluation with `sandbox_error` / `reason=memory_budget_exceeded` before the JVM is under severe pressure.

Hard memory limits remain the responsibility of:

- Container limits and JVM heap size.
- Kubernetes restart policies and platform alerts.

### Observable Failure Modes for Memory Limits

When a script exceeds its soft memory budget:

- The run is aborted before further allocations.
- Any commands staged from that run are discarded prior to commit.
- The `script_event_audit` record is written with:
  - `finalStage = DSL_EVAL`
  - `finalOutcome = sandbox_error`
  - `finalReason = memory_budget_exceeded`
  - Counts for nodes visited, collections sizes, and approximate bytes used (where available)
- Metrics are incremented:
  - `automation_script_sandbox_failures_total{reason="memory_budget_exceeded"}`

If instead the JVM or container hits a hard limit and restarts:

- The run, and possibly other concurrent runs, fail with `infrastructure_error`.
- Standard platform health checks and alerts (logging, Prometheus, OpenTelemetry) report the outage.
- Upon recovery, the scheduler continues from the next tick; at-most-once guarantees ensure the failed run is not retried automatically. In this case `script_event_audit.finalOutcome=infrastructure_error` (with an appropriate `finalStage`) matches the canonical taxonomy described in the observability contract.

---

## Interaction with Quotas, Scheduling, and Multi-Tenancy

Sandbox limits do not replace existing quotas and scheduling policies; they **layer on top** of them:

- **Quotas first, then sandbox**
  - `ScriptQuotaService` and per-tenant budgets decide whether a run is allowed to start.
  - Only admitted runs consume CPU and memory budgets in the sandbox.

- **Per-script vs per-tenant safety**
  - Per-script sandbox failures (`sandbox_error`) count toward:
    - Per-script failure-rate circuit breakers
    - Per-scope error metrics, for example `automation_script_errors_total{scope, script_category, reason=...}`
  - This prevents one script from repeatedly failing without impacting other tenants’ automation workloads.

- **Tick alignment**
  - Script runs are scheduled using the tick heartbeat stream as described in the tick architecture.
  - Sandbox-enforced timeouts ensure that runaway scripts do not cause automation work to exceed the configured `AUTOMATION_TICK_BUDGET_MS` per tick window.

The combined effect is that noisy or buggy scripts are throttled or disabled quickly, while well-behaved scripts continue to run at their configured cadence.

---

## Configuration Knobs

Sandbox behavior is shaped by a combination of in-code defaults and environment variables exposed by the Automation & Scripting Service. The authoritative list of environment variables and their defaults lives in the service configuration doc (`design/architecture/microservices/automation-scripting-service/configuration.md`); this section highlights the ones most directly related to the budgets described above:

- `AUTOMATION_TICK_DURATION_MS` – bounds the wall-clock duration of an automation tick. This, together with the scheduler’s batching strategy, constrains how often sandboxed runs are admitted.
- `AUTOMATION_TICK_MAX_EVENTS` – caps how many automation events (including script runs) are staged from `automation:queue` per automation tick.
- `AUTOMATION_TICK_BUDGET_MS` – provides a soft execution budget for script work performed inside a single automation tick; it informs the per-run budget tokens allocated to sandboxed evaluations.
- `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` / `SCRIPT_EVENT_AUDIT_MAX_ROWS` – control how long sandbox outcomes (for example, `sandbox_error` with `cpu_budget_exceeded` or `memory_budget_exceeded`) remain queryable in `script_event_audit`.

Per-script and per-tenant quotas (for example, `SCRIPT_QUOTA_LIMIT`, `SCRIPT_QUOTA_WINDOW_SECONDS`, and `SCRIPT_TENANT_BUDGET_NORMAL_RUNS_PER_MINUTE`) are documented in the service configuration and operations docs and in `design/architecture/system-architecture-scripting-quotas-and-operations.md`; they work in tandem with the sandbox budgets to determine whether a run is admitted and how much CPU and memory it can consume.

Additional resource-related environment variables may be introduced over time. New knobs should be documented first in the Automation & Scripting Service configuration doc and, where they materially affect sandbox semantics, referenced from this section so operators and implementers can correlate configuration changes with the behavior described above.

---

## Operator Guidance

When diagnosing sandbox-related issues in production, operators should:

- Check `script_event_audit` records for:
  - `finalStage` (`ADMISSION`, `DSL_EVAL`, `WORK_ITEM_PERSIST`, `TICK_HANDOFF`)
  - `finalOutcome` (`sandbox_error`, `infrastructure_error`, `quota_denied`)
  - `finalReason` (`cpu_budget_exceeded`, `memory_budget_exceeded`, other sandbox reasons)
  - Associated `tenantId`, `scriptId`, and `tickId`
- Inspect metrics such as:
  - `automation_script_sandbox_failures_total` (broken down by `reason`)
  - `automation_script_runtime_seconds`
  - `script_quota_denied_total` and related quota metrics
- Verify whether the script has entered `runtimeStatus=DISABLED_DUE_TO_ERRORS` and, if so, decide whether to:
  - Adjust quotas or budgets
  - Fix the script definition
  - Re-enable the script after remediation

Future implementation work should keep this observable behavior intact even if the internal enforcement mechanisms evolve (for example, moving from cooperative checks to dedicated worker processes).
