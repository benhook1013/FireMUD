# Automation & Scripting Service: Script Sandbox & Resource Limits

This document describes how the Automation & Scripting Service enforces script sandboxing, CPU and memory limits, and what operators observe when limits are hit. It refines the high-level behavior described in:

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-ticks.md`

## Implementation Status

This document describes the **target-state architecture** for script sandboxing and resource limits. Some aspects may be partially implemented or stubbed out in the current codebase.

For the latest progress, see:

- `design/project-management/task-list-automation-scripting-service.md`
- `design/architecture/system-architecture-scripting.md#sandboxing--security`

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
   - `ScriptQuotaService` checks per-script and per-tenant quotas. If the quota is exceeded, the trigger is rejected with outcome `quota_denied` and no sandbox work occurs.

2. **Sandbox setup**
   - The scheduler allocates a **sandbox context** containing:
     - Tenant and script identifiers
     - The pinned `scriptPatchVersion`
     - Per-run budgets (CPU/time, memory, and concurrency)
   - The run is submitted to a **bounded thread pool** dedicated to script execution.

3. **Graph evaluation**
   - The engine evaluates the script’s component graph:
     - It walks nodes and edges in topological order, honoring bounded loops.
     - It periodically checks the remaining **time budget** and **iteration budget**.
   - If a budget check fails or a runtime guard trips (for example, too-large payload), evaluation is interrupted with a sandbox error.

4. **Command staging**
   - Successful runs emit a list of commands which are staged into the entity’s command queue via `ScriptTickService`.
   - Staging is subject to automation tick limits (`AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) and uses the same Redis Lua scripts and hash tags as described in the tick architecture.

5. **Outcome recording**
   - The engine records a **structured outcome** for the run:
     - `success`
     - `quota_denied`
     - `sandbox_error` (with a specific reason)
     - `infrastructure_error`
   - Outcomes are written to the `script_event_audit` store and exposed via metrics for dashboards and alerts. Pre-admission quota denials (`quota_denied`) are handled by `ScriptQuotaService` before sandbox work begins and do **not** contribute to sandbox failure metrics; sandbox errors (for example, budget violations) do, and are considered by the failure-rate circuit breaker. See `design/architecture/system-architecture-scripting-quotas-and-operations.md#outcome-to-metric-mapping` for how these outcomes map to metrics and disable behavior.

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
  - `outcome = sandbox_error`
  - `reason = cpu_budget_exceeded`
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
  - `outcome = sandbox_error`
  - `reason = memory_budget_exceeded`
  - Counts for nodes visited, collections sizes, and approximate bytes used (where available)
- Metrics are incremented:
  - `automation_script_sandbox_failures_total{reason="memory_budget_exceeded"}`

If instead the JVM or container hits a hard limit and restarts:

- The run, and possibly other concurrent runs, fail with `infrastructure_error`.
- Standard platform health checks and alerts (logging, Prometheus, OpenTelemetry) report the outage.
- Upon recovery, the scheduler continues from the next tick; at-most-once guarantees ensure the failed run is not retried automatically. In this case the `script_event_audit` record uses `outcome = infrastructure_error` to match the canonical `outcome` enum described in the scripting architecture.

---

## Interaction with Quotas, Scheduling, and Multi-Tenancy

Sandbox limits do not replace existing quotas and scheduling policies; they **layer on top** of them:

- **Quotas first, then sandbox**
  - `ScriptQuotaService` and per-tenant budgets decide whether a run is allowed to start.
  - Only admitted runs consume CPU and memory budgets in the sandbox.

- **Per-script vs per-tenant safety**
  - Per-script sandbox failures (`sandbox_error`) count toward:
    - Per-script failure-rate circuit breakers
    - Per-tenant error metrics, for example `automation_script_errors_total{tenantId, reason=...}`
  - This prevents one script from repeatedly failing without impacting other tenants’ automation workloads.

- **Tick alignment**
  - Script runs are scheduled using the tick heartbeat stream as described in the tick architecture.
  - Sandbox-enforced timeouts ensure that runaway scripts do not cause automation work to exceed the configured `AUTOMATION_TICK_BUDGET_MS` per tick window.

The combined effect is that noisy or buggy scripts are throttled or disabled quickly, while well-behaved scripts continue to run at their configured cadence.

---

## Operator Guidance

When diagnosing sandbox-related issues in production, operators should:

- Check `script_event_audit` records for:
  - `outcomeType` (`sandbox_error`, `infrastructure_error`, `quota_denied`)
  - `reason` fields (`cpu_budget_exceeded`, `memory_budget_exceeded`, other sandbox reasons)
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
