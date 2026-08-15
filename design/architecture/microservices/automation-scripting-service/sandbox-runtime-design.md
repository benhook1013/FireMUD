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
| Plugin sandbox reuse | Plugins run in the same sandbox engine with component allowlists and stricter quotas where policy requires; plugin activation/version lifecycle remains distinct from embedded script publication. |

---

## Goals

- Keep script execution **safe and predictable** for multi-tenant games.
- Bound **CPU time** and **memory usage** per script and per tenant.
- Ensure **deterministic, auditable outcomes** for any sandbox failure.
- Integrate cleanly with the existing **tick**, **quota**, and **multi-tenancy** models.

The sandbox context is a projection of the canonical sealed handler manifest defined by the [DSL lifecycle read-consistency contract](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract). The locally useful categories below are intentionally non-exhaustive; the linked contract owns the complete manifest schema, owner-versioned evidence, causal floor, digest, and retry/recovery reuse rules. The context retains the complete applicable owner/runtime scope, tenant/script identity, exact `(scriptPatchVersion, scriptPinEpoch)` tuple, and plugin provenance when applicable through evaluation and final handoff; tenant-readiness `onLoad` retains its declared pre-instance-pin exception. Missing or stale required evidence fails closed without a fallback. Stage-aware recovery is a runtime/control-plane behavior; distinct embedded/plugin lifecycle metrics are local observability consequences. Detailed authority and recovery rules live in [Scripting Runtime Execution](../../system-architecture-scripting-runtime-execution.md), the [scripting control-plane API](../../system-architecture-scripting-control-plane-api.md), and [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).

---

## Sandbox Model Overview

Sandboxing for scripts is implemented as a **multi-layer model**:

1. **Language / DSL sandbox**
   - Scripts are defined as **graphs of curated components**, not arbitrary code.
   - The runtime exposes only vetted operations and DTOs; user scripts cannot open sockets, allocate arbitrary threads, or call reflection APIs.
   - Input sizes (for example, text, arrays, collections) are validated and clamped before being passed into script evaluation.

2. **Execution sandbox**
   - Scripts run inside a **dedicated executor** within the Automation & Scripting Service.
   - In the target-state execution model, quota-allowed handlers are durable queued work. The later execution scheduler considers that work in canonical order, admits the ordered prefix whose immutable artifact-pinned estimated millisecond costs fit the cumulative `AUTOMATION_TICK_BUDGET_MS` reservation, and defers the remainder under unchanged Trigger Identity. For a selected handler, it acquires the separately fenced execution-capacity lease and uses the executor-acceptance transaction defined by the [quota lifecycle contract](../../system-architecture-scripting-quotas-and-operations.md#budget-accounting-rules) before evaluation. The lease is a bounded concurrency/occupancy fence, not the estimate reservation; actual runtime is calibration telemetry only and does not create a same-tick refund. Current live enforcement remains the aggregate per-tenant, priority-tier reservation described below.
   - Charge points are fixed by the [quota lifecycle contract](../../system-architecture-scripting-quotas-and-operations.md#budget-accounting-rules): one durable full-Trigger-Identity handler charge record has separate exactly-once admission and execution-start markers. A queued handler holds no capacity; execution starts only under a separately fenced, reclaimable capacity lease. Duplicate/recovery attempts reuse the markers, lease reclamation is not a refund, and no post-marker failure reverses a charge.
   - `AUTOMATION_TICK_BUDGET_MS` is a cumulative artifact-estimate reservation, not actual execution time or a per-run timeout. The separately fenced execution-capacity lease is a bounded concurrency/occupancy fence, not a millisecond debit; the estimate reservation remains separate from that lease, and its artifact-pinned estimate is defined by ADR 0088. The executor separately enforces each run's wall-clock deadline and iteration limit; a run timing out does not create another capacity lease, and actual runtime does not reopen or refund the same tick.

3. **Process / container isolation**
   - The service runs in containers with **Kubernetes CPU and memory limits**.
   - Catastrophic failures (for example, heap exhaustion, JVM `OutOfMemoryError`) are treated as **infrastructure errors** and surfaced via `infrastructure_error` outcomes and standard platform alerts.

The remainder of this document focuses on the execution sandbox and how CPU and memory limits translate into observable behavior.

---

## Script Execution Lifecycle

Each script run follows a consistent lifecycle:

1. **Trigger admission**
   - A trigger arrives from the scheduler (event, timer, interval, or manual test run).
   - At the scheduler's event/handler admission boundary, after raw event ingress, the service performs the registry and quota/charge checks. It does not acquire a lease or perform the later execution-scheduler estimate reservation. An authoritative policy denial is recorded as the applicable typed handler outcome (`finalStage=ADMISSION`, `finalOutcome=quota_denied` or `tenant_budget_exceeded`). If authoritative resource/capacity exhaustion prevents producing that decision, return non-OK `RESOURCE_EXHAUSTED`; if route, charge, worker, or dependency infrastructure is unavailable and cannot produce it, return non-OK `UNAVAILABLE`.
   - For each admitted handler, the service seals the handler input manifest and durably creates and queues `PENDING_EVALUATION` work. Queued work holds no capacity or execution marker; the later execution scheduler considers queued work for its canonical estimate reservation and leaves unselected work durably queued under the same identity.

2. **Sandbox setup and executor acceptance**
   - The scheduler allocates a **sandbox context** from the sealed handler manifest. The following locally useful categories are not an exhaustive schema; the [canonical read-consistency contract](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract) defines every required field and evidence:
     - For instance-bound gameplay/runtime runs, the complete applicable owner/runtime scope (`tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `entityId` when applicable)
     - Tenant/script identity (`scriptId`, `eventType`, `eventSchemaVersion`, `scriptEventId`, and `isDryRun`)
     - The exact pinned `(scriptPatchVersion, scriptPinEpoch)` tuple for instance-bound gameplay/runtime runs
     - Plugin provenance (`pluginId`, `pluginVersionId`, and `bindingId`) when applicable
     - Per-run budgets (CPU/time, memory, and concurrency)
     - For tenant-readiness `onLoad`, the declared readiness identity and configuration/runtime metadata are the applicable context; it is pre-instance-pin and does not require or fabricate instance scope or epoch
   - Missing, stale, or contradictory manifest, applicable runtime tuple/scope, or applicable plugin provenance evidence fails closed before evaluation or final handoff. The declared `onLoad` readiness context is the applicable exception and must not be rejected for absent instance tuple, scope, or epoch.
   - The run is submitted to a **bounded thread pool** dedicated to script execution.
   - Dry-run/test work must use isolated execution capacity (separate pool, reserved worker share, or equivalent partition) so live automation retains guaranteed worker availability under load.
   - Immediately before evaluation, and only at this step, the worker acquires the lease and in one Automation-owned durable executor-acceptance transaction revalidates its current fence, durably accepts/claims the run for the executor, persists the exactly-once execution-start marker, and advances the work item to `EXECUTING`. Only after that commit may evaluation begin. If executor acceptance fails, the transition does not commit, the lease is released or reclaimed, and no execution charge is recorded; a crash after commit recovers from the durable executor claim and may reacquire a lease but never admits a second marker.

3. **Graph evaluation**
   - The engine evaluates the script’s component graph:
     - It walks nodes and edges in topological order, honoring bounded loops.
     - It periodically checks the remaining **time budget** and **iteration budget**.
   - If a budget check fails or a runtime guard trips (for example, too-large payload), evaluation is interrupted with a sandbox error.

4. **Command staging**
   - Successful runs emit bounded descriptors which are staged into the already admitted per-handler durable work item and persisted atomically, then follow the separate durable Game Session handoff path. The already admitted `automation:queue:*` projection remains the pre-evaluation discovery pointer and is not a handoff queue.
   - Target-state handoff through `EnqueueAutomationCommandIfAbsent` gives each emitted command child the complete Command-Handoff Identity: source `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)`, optional distinct target `(targetGameInstanceId, targetPlayableStateScope, targetRegionId, targetRegionEpoch)`, the persisted `automationDispatchId` reserved before evaluation, and a deterministic `commandOrdinal` starting at `0` in canonical emitted-command order. Game Session deduplicates the complete child identity. The parent Trigger Identity and `outboxWorkItemId` are correlation-only and are excluded from command-child uniqueness and deduplication; they are not substitutes for the complete child identity. The live handoff remains narrower and is not complete child-identity proof; see the [current runtime handoff status](../../system-architecture-scripting-runtime-execution.md#current-implementation-status) and [normative Command-Handoff Identity](../../system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state).
   - Before constructing each generated command or data-dependent collection, the engine applies the artifact-pinned conservative prospective serialized-byte bound together with command-count, per-entity, and data-dependent caps. Before atomic persistence, it checks the exact bounded serialized size of the staged descriptors against the pinned ceiling. Any cap exceed is a non-success outcome; all staged output for that handler is discarded and no partial work item or handoff is committed. The evaluator accepts only the artifact-pinned `componentCostRegistryDigest` and `artifactRuntimeCapDigest` plus their embedded payloads as validated by the runtime owner; it does not estimate from a newer local registry.
   - Work reaches this stage only after the earlier execution-scheduler selection over queued work. Target-state handoff uses the event-count ceiling (`AUTOMATION_TICK_MAX_EVENTS`) and only documented `automation:*` Redis prefixes for projection and quotas. The Automation & Scripting Service never writes `tick:*` keys directly; it hands off commands to Game Session over internal gRPC so Game Session can enqueue tick commands under its own tick and locking model. Current live capacity enforcement is the aggregate per-tenant, priority-tier reservation described below.

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

- **Current live enforcement**
  - Live execution reserves aggregate capacity through per-tenant, priority-tier budgets (`SCRIPT_TENANT_BUDGET_*`) before definition evaluation. This is not a per-trigger allowance and does not create separate `gameInstanceId` or `regionId` capacity buckets.

- **Target-state automation tick configuration**
  - `AUTOMATION_TICK_BUDGET_MS` defines the **cumulative artifact-estimate reservation** for the ordered prefix admitted during one target-state tick window. It is not actual execution time, a separate allowance for each script or trigger, or a lease debit. Any allocation by runtime instance or region is target-state and must not be read as the current live enforcement model.
  - The later execution scheduler owns the target-state `AUTOMATION_TICK_BUDGET_MS` reservation over queued work; selected work then requires a valid quota-owner capacity lease before execution. Lease fencing and reclamation remain owned by the quota lifecycle; the lease is only a concurrency/occupancy fence. A run cannot start merely because its per-run timeout is available, and a timeout cannot be used as a substitute for tick-window admission. See [runtime execution](../../system-architecture-scripting-runtime-execution.md) for the owner contract.
  - The separate scheduler envelope and occupancy leases remain subject to the cluster-wide execution ceiling, so scope-level scheduling cannot expand total container or cluster capacity.
  - `playableStateScope` is not a separate budget partition. Shared and isolated playable-state namespaces within the same runtime scope consume the same aggregate capacity budget, while per-script quotas, per-tenant tier budgets, priority ordering, and cluster ceilings provide fairness. The scope remains mandatory in Trigger Identity and fencing so capacity sharing cannot cause state or deduplication collisions.

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
  - In the target-state model, the later execution scheduler applies the cumulative `AUTOMATION_TICK_BUDGET_MS` estimate reservation over queued work, while the quota owner's fenced capacity leases bound concurrent occupancy. Lease acquisition must not be inferred to measure or settle milliseconds. Sandbox-enforced per-run timeouts remain a separate runaway-execution guard and do not by themselves enforce the reservation; actual runtime is calibration telemetry only and does not create a same-tick refund.

The combined effect is that noisy or buggy scripts are throttled or disabled quickly, while well-behaved scripts continue to run at their configured cadence.

---

## Configuration Knobs

Sandbox behavior is shaped by a combination of in-code defaults and environment variables exposed by the Automation & Scripting Service. The authoritative list of environment variables and their defaults lives in the service configuration doc (`design/architecture/microservices/automation-scripting-service/configuration.md`); this section highlights the ones most directly related to the budgets described above:

- `AUTOMATION_TICK_DURATION_MS` – bounds the wall-clock duration of an automation tick. This, together with the scheduler’s batching strategy, constrains how often sandboxed runs are admitted.
- `AUTOMATION_TICK_MAX_EVENTS` – caps how many automation events (including script runs) are staged from `automation:queue` per automation tick.
- `AUTOMATION_TICK_BUDGET_MS` – **target-state** cumulative estimate reservation for queued script work selected by the later execution scheduler inside a single automation tick. It is separate from the fenced occupancy lease required before execution, is not actual execution time or a per-run timeout, and is not the current live per-trigger or per-region enforcement bucket.
- `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` / `SCRIPT_EVENT_AUDIT_MAX_ROWS` – control how long sandbox outcomes (for example, `sandbox_error` with `cpu_budget_exceeded` or `memory_budget_exceeded`) remain queryable in `script_event_audit`.

Per-script and per-tenant quotas (for example, `SCRIPT_QUOTA_LIMIT`, `SCRIPT_QUOTA_WINDOW_SECONDS`, and `SCRIPT_TENANT_BUDGET_NORMAL_RUNS_PER_MINUTE`) are documented in the service configuration and operations docs and in `design/architecture/system-architecture-scripting-quotas-and-operations.md`; they determine current live admission, while the target-state sandbox budgets describe how future tick-window capacity and per-run limits may be layered on top.

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
