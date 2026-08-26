# FireMUD Scripting Quotas & Operations

This document focuses on **sandboxing, quotas, budgets, and operational workflows** for the scripting and automation framework.

It is intended as the main reference for operators, SREs, and platform engineers responsible for safe multi-tenant operation of scripts.

Routing note:

- Use this document for quota policy, enforcement, and runtime/operator controls.
- Use the [DSL and lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md) for DSL/lifecycle semantics.
- Use [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md) for execution-state behavior.

Exact script selection is the Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple; this document owns only quota/capacity consequences when that tuple is unavailable or fenced. Routine rollback does not pause gameplay; see [Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) and [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md). Plugin artifact/lifecycle distinctions remain in [DSL Reference & Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#one-dsl-distinct-artifact-and-lifecycle-roles).

## Target-State Quota and Budget Contract

The target runtime applies deterministic, layered limits without allowing test traffic or one scope to consume another scope's live capacity:

- Event-scope admission does not charge live per-script quota or execution capacity. After handler resolution, Automation creates one durable handler-charge record keyed by the full applicable Trigger Identity (including each `onLoad` handler) and records an exactly-once admission marker.
- Execution capacity is a separate, fenced, reclaimable lease. A handler waiting in `queue_until_free` holds no capacity; the execution-start marker is recorded exactly once only when a valid lease is acquired. Duplicate deliveries and recovery attempts reuse the existing charge record and markers.
- Admission and execution-start charges are not refunded after their markers commit. Lease reclamation returns capacity for reuse but never reverses a charge or creates a second execution-start marker.
- `onLoad` readiness uses a separate bounded `PUBLISH_READINESS` capacity class and is excluded from ordinary live quota and budget windows.
- Dry-run/test execution uses isolated budgets and capacity and is represented only by dry-run/test metric families, never live work-item outcome metrics.
- Output ceilings bound emitted commands and serialized work before durable live work is persisted or handed off.
- Target-state scheduler admission uses each immutable artifact-pinned estimated millisecond cost to admit the deterministic ordered prefix whose cumulative reservation fits `AUTOMATION_TICK_BUDGET_MS`; the remainder is deferred. Actual runtime is calibration telemetry only and does not create a same-tick refund. This reservation is separate from durable usage charges and the fenced sandbox occupancy lease.

The detailed charge points, resource levels, and operator procedures below refine this contract without changing its ownership or charge points.

## Implementation Status

Current Automation quota and budget behavior is consolidated here. The policy sections below define the target contract and do not repeat these implementation details.

- **Live per-script quota:** Current ingress acquires `ScriptQuotaService` for `STANDARD_RUNTIME` resolved handlers before durable `script_work_items` materialization. A denial writes a handler-scoped audit row with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=script_quota_denied`; no outbox work item is created. The target durable full-Trigger-Identity charge record and exactly-once admission marker are not yet implemented.
- **Live tenant budget/capacity:** Current execution persists `priorityTag` and `quotaClass` on durable work items and applies `ScriptTenantBudgetService` to non-dry-run `STANDARD_RUNTIME` work before DSL evaluation. A denial terminally cancels the work item with `finalStage=ADMISSION`, `finalOutcome=tenant_budget_exceeded`, and `finalReason=tenant_budget_exceeded`. The target separately fenced/reclaimable execution-capacity lease and exactly-once execution-start marker remain implementation gaps.
- **Target scheduler reservation:** Artifact-pinned estimated-millisecond ordered-prefix admission through `AUTOMATION_TICK_BUDGET_MS` is not implemented by the current runtime; remaining work is not yet deferred by that target reservation, and actual runtime provides calibration telemetry only.
- **Dry-run/test limits:** Current ingress enforces per-minute tenant and principal dry-run ceilings before handler resolution, returning event-scope `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED` with `admissionReason=dry_run_budget_exceeded` without creating handler work. Materialized dry-runs skip live per-script and tenant-budget acquisition, then reserve isolated tenant/cluster capacity through `ScriptDryRunCapacityService`. A capacity denial is handler-scoped with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=dry_run_capacity_exhausted`; it does not increment the live per-script quota family and is visible through the Table 4 test-capacity consequence plus the trigger outcome/audit.
- **Publish/readiness capacity:** Current execution reserves dedicated tenant and cluster readiness capacity for non-dry-run `PUBLISH_READINESS` work before DSL evaluation. Exhaustion cancels the work item with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=onload_budget_exceeded`; it is not charged to live per-script quota or tenant runtime budget.

See the [normative metric matrix](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) for metric units and labels. The [runtime execution](./system-architecture-scripting-runtime-execution.md) document owns execution-state behavior.

Companion docs:

- [DSL and lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md) – terminology, DSL semantics, event lifecycle, determinism.
- [Scripting examples and patterns](./system-architecture-scripting-examples-and-patterns.md) – worked examples (for example, `onEnterRegion`, periodic patrol).
- [Scripting and Automation hub](./system-architecture-scripting.md) – high-level hub and TL;DR flow.

## Table of Contents

- [Target-State Quota and Budget Contract](#target-state-quota-and-budget-contract)
- [Implementation Status](#implementation-status)
- [Audience](#audience)
- [Sandboxing & Security](#sandboxing--security)
- [Fairness & Abuse Prevention](#fairness--abuse-prevention)
- [Resource Isolation and Multi-Level Budgets](#resource-isolation-and-multi-level-budgets)
- [Quota & Budget Summary](#quota--budget-summary)
- [Auditability & Metrics](#auditability--metrics)
- [Outcome-to-Metric Mapping](#outcome-to-metric-mapping)
- [Related Operations Cookbook](#related-operations-cookbook)
- [Environment Variables](#environment-variables)
- [Developer Tools](#developer-tools)

---

## Audience

- **Operators, SREs, and platform engineers**
  - Use this document as the primary reference for runtime knobs, quotas, and operational workflows around scripting.
  - Pair with:
    - [Logging & Monitoring](./system-architecture-logging-monitoring.md)
    - [Redis Architecture](./system-architecture-redis.md)
    - [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md#document-map)

- **Backend developers and maintainers**
  - Use this document when implementing new quota types, metrics, or operational flows.

---

## Sandboxing & Security

Scripts execute inside a **sandboxed runtime** owned by the Automation & Scripting Service. Key properties:

- No raw code execution: designers build behavior from curated components; there is no direct access to Lua or a general-purpose language.
- Whitelisted capabilities only: components encapsulate allowed operations (for example, querying world state, enqueuing commands) and validate inputs strictly.
- No direct database or Redis access from scripts: all state changes flow through domain services and the Game Session Service.
- Runtime guards:
  - Per-run iteration limits protect against hot loops even if a graph slips past static analysis.
  - Execution budgets bound CPU and time per script run.
  - Dangerous components can be flagged as `UNSAFE` and enforced via migration and deprecation flows.

For **core scripts**, component policy has two distinct authority classes under [ADR 0116](./decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md):

- Routine `UNSAFE` classification means migration-required / new-use-blocked. New publishes or readiness transitions fail deterministically with `validation_error` / `unsafe_component`; already-`READY` or pinned patches do not become implicitly disabled, and routine reclassification is not a live runtime rollout.
- Emergency revocation is a separate explicit, audited platform-security action for critical sandbox or data-boundary risks. It blocks new affected evaluation even under an otherwise `READY` or pinned patch, fences output before persistence or handoff, and uses the scoped containment workflow below.
- The emergency action discovers and pauses affected Automation scopes, then drives explicit disable or fenced rollback. If no safe target exists, affected Automation remains fail closed while unrelated gameplay continues.

For lower-level sandbox and runtime internals, see the [sandbox runtime design](./microservices/automation-scripting-service/sandbox-runtime-design.md).

Dry-run and test execution paths exposed by the Automation & Scripting Service share the same sandbox and guardrails as live traffic:

- They execute handlers through the same engine with the same CPU/iteration and memory budgets.
- Materialized handler-scoped dry-run/test attempts record `sandbox_error` and `infrastructure_error` outcomes in `script_event_audit` so failure modes are observable. Pre-handler dry-run rejection, pre-handler signer-policy unavailability, and retryable rollback-pause backpressure are ingress-audit outcomes only; an admitted event with zero handlers is metric-only.
- By default they do **not** consume per-script quotas or per-tenant budgets enforced by live runtime budget services; instead, they are restricted to privileged principals (for example, designers and operators) and should be further protected by separate rate limits or ACLs at the API gateway or Logging & Admin layer.
  - Dry-run/test executions must not increment live-traffic error counters. Sandbox failures observed during tests are emitted via dry-run/test-only metric families (for example `automation_script_test_sandbox_failures_total`) so production SLO dashboards do not conflate privileged tooling with live automation reliability.
- Dry-run/test traffic must not be allowed to consume the same last-resort execution capacity reserved for live automation:
  - Implementations should use separate executor pools or explicit worker reservations for `isDryRun=true`.
  - When the cluster is under pressure, live traffic must be admitted ahead of dry-run/test traffic even if dry-run quotas have not been exceeded.
  - Queue limits and concurrency ceilings for dry-run/test work must be enforced independently from live execution queues.

Current enforcement details are consolidated in [Implementation Status](#implementation-status).

---

## Fairness & Abuse Prevention

Fairness and abuse prevention combine **per-script quotas**, **per-tenant budgets**, and **cluster-wide safety limits** so that:

- No single script can run unboundedly.
- No single tenant can starve others.
- The cluster remains healthy even under extreme load.

The main mechanisms include:

- **Per-script quotas** enforced by `ScriptQuotaService` before a handler runs.
- **Concurrency and queue limits** per script (`maxConcurrent`, `concurrencyPolicy` such as `drop_new` or `queue_until_free`).
- **Per-tenant tier budgets** that cap automation work per tenant and priority tier.
- **Cluster-wide ceilings** on automation work, including CPU/time budgets and `AUTOMATION_TICK_MAX_EVENTS`.
- **Priority tags** (`high`, `normal`, `background`) that determine how scripts are throttled when budgets are tight.

These controls work alongside the **failure-rate circuit breaker**, which can automatically place one exact immutable script/plugin version and runtime activation scope into `DISABLED_DUE_TO_ERRORS` when, within the configured rolling window, eligible classified samples are greater than or equal to the configured minimum and qualifying handler-attributable failures divided by eligible classified samples are greater than or equal to the configured failure-rate threshold, with all settings within platform hard bounds.

Only handler-attributable deterministic evaluation, sandbox-limit, and authored-output failures count. Quota/capacity denial, infrastructure or owner unavailability, rollback/version fencing, expected gameplay precondition rejection, dry-run/test traffic, operator cancellation, and player-controlled invalid input do not. A trip blocks new admission but does not cancel accepted work or reverse effects. Recovery requires a new version or explicit audited reset after validation; emergency component revocation remains a separate immediate fence. The classified samples and policy version are retained as durable audit evidence.

For plugin handlers, the trip changes only the separate `breakerState` for the exact active `pluginVersionId` and runtime scope; it does not change lifecycle `pluginState`, `pluginActivationEpoch`, or `lifecycleRevision`. A newly resolved handler is denied with `finalStage=ADMISSION`, `finalOutcome=disabled_due_to_errors`, and bounded `finalReason=failure_rate_breaker`; a new version starts `CLOSED`, and an audited reset is independent of lifecycle mutation.

### Plugin Workloads

Plugins executed via the modding framework share the same underlying quota and scheduling infrastructure as regular scripts:

- Each plugin uses the same DSL/runtime and quota model as an embedded script, while retaining an independently versioned bundle identity (`pluginId` plus exact `pluginVersionId`) and a separate instance-scoped enable/drain/disable/update lifecycle.
- Plugin triggers (for example, `onEnterRoom` or `onItemUse` events wired through the plugin system) run under the same multi-level budgeting model:
  - Per-plugin quotas enforced by `ScriptQuotaService`.
  - Per-tenant budgets, including priority tiers (for example, `high`, `normal`, `background`).
  - Cluster-wide ceilings and automation tick budgets.
- From an observability perspective, plugin executions are recorded in `script_event_audit` alongside other script runs, with `pluginId`, `pluginVersionId`, and the resolved `bindingId` so operators can distinguish individual plugin handlers from core automation and from sibling bindings in the same plugin version.
- Plugin enforcement also respects a centrally managed component policy. When a plugin references a component that is disallowed by the current environment policy, its triggers are rejected at admission with a handler-scoped audit outcome and a bounded reason that identifies the blocked component/policy, plus corresponding metrics (for example, `automation_plugin_policy_violations_total`) so operators can distinguish policy violations from quota or sandbox failures. When signer-policy evidence is older than `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS` and cannot be refreshed, the current Automation runtime rejects the event before handler work is materialized for requests that explicitly carry plugin identity; no handler quota or sandbox capacity is consumed and no handler audit row is created. Target behavior requires generic ingress to revalidate current signer/publication policy immediately after resolving the concrete plugin-owned handler identity and before handler quota charging or work-item materialization; stale, missing, contradictory, or unavailable policy evidence fails closed by committing that handler's `script_event_audit` row with `finalStage=ADMISSION` and `finalOutcome=signer_policy_unavailable`, while proven signer revocation uses the canonical handler disposition `finalOutcome=plugin_disabled` with `finalReason=signer_revoked`. The current generic path does not yet enforce this check and remains an implementation gap; operators must not treat it as current fail-closed signer-policy enforcement. Handler outcomes after binding resolution remain governed by the audit contract.

This alignment ensures that plugin code cannot bypass or weaken the resource-isolation guarantees of the scripting system; operational tooling and metrics apply uniformly to both plugins and regular scripts. For the structural lifecycle of plugins (versioning, enable/disable states, and rollback), see `design/architecture/microservices/game-design-service/modding-framework.md`; Logging & Admin provides operator ingress, while the Automation & Scripting Service owns the authoritative instance-scoped runtime plugin state and active exact `(pluginId, pluginVersionId, bindingId)` plugin provenance plus captured Automation-owned `(pluginActivationEpoch, lifecycleRevision)` execution-fence evidence. **Target state requires** revalidation of that pair at every applicable lifecycle boundary defined by [Scripting & Automation: Cross-Service Contracts §8](./system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope), including persistence and staged/final-effect enforcement, and enforcement of quotas, budgets, sandbox rules, and component policy at runtime. For a handoff under `DRAINING`, only same-version/same-epoch work whose winning admission/fence CAS committed the immediately preceding `ENABLED` revision before the durable drain barrier is eligible; Game Session consumes Automation-owned projection/evidence at its final fence and does not allocate or advance plugin lifecycle. Neither lifecycle field is part of Command-Handoff Identity. That independent plugin lifecycle remains separate from the Game Session script tuple fence; when a command carries both provenances, both fences apply.

### Per-Script Scheduling Policies

Per-script scheduling knobs control how often scripts are allowed to run and how they behave under load:

- **`intervalTicks` (cadence)** – defines the target cadence for scheduler-driven events (for example, “every N ticks”). The scheduler uses tick heartbeats and timer indexes to decide when an `onInterval` or timer-based handler becomes due, keeping script cadence aligned with the canonical `tickId`.
- **`concurrencyPolicy` and `maxConcurrent`** – govern what happens when new triggers arrive while runs are already in progress:
  - `concurrencyPolicy=queue_until_free` keeps a short queue of pending triggers up to configured limits and runs them once existing executions complete.
  - `concurrencyPolicy=drop_new` skips new triggers while the script is already running, favoring bounded concurrency over backlog growth.
  - Queued triggers retain their durable admission charge but hold no execution capacity. An event-scope limiter may reject an incoming trigger before handler resolution, but that is not a per-script quota charge and uses event-scope ingress audit and drop metrics. After handler resolution, `ScriptQuotaService` records one charge for each resolved handler and may deny it with `script_event_audit.finalStage=ADMISSION`, `finalOutcome=quota_denied`, and the handler outcome metric rather than the dropped-ingress metric.
- **`priorityTag`** – assigns a priority tier (`high`, `normal`, `background`) that interacts with per-tenant budgets and cluster ceilings. When capacity is tight, the scheduler applies tenant fairness first, then admits `high`-priority work preferentially within each tenant's allocated share, deferring or dropping lower-priority triggers according to budget and quota rules.

Timer and interval limits are evaluated against the canonical runtime scope tuple `<tenantId, gameInstanceId, regionId>`. A per-tenant or per-game-instance timer limit must not substitute for that tuple and accidentally couple unrelated instances or regions; any broader aggregate ceiling is an additional explicitly named safety limit. `playableStateScope` remains part of trigger identity and handler/work fencing, but it does not replace the scheduler's runtime scope tuple for these timer-capacity limits.

### `onLoad` Initialization Capacity

Patch readiness initialization uses a separate admission class from ordinary live triggers:

- `onLoad` is part of the publish/readiness lifecycle for `<tenantId, scriptPatchVersion>`, not part of steady-state gameplay traffic.
- `onLoad` must **not** consume ordinary live-trigger quota windows or compete indefinitely in the same admission queues as `onEnterRegion`, `onInterval`, or other runtime events.
- The canonical registry classification for that split is `quotaClass=PUBLISH_READINESS`, and Automation must carry that class onto durable work items so later execution-time budget decisions do not fall back to event-name inference.
- In the first implementation slice, `onLoad` capacity exists only for **ephemeral readiness work**. `onLoad` handlers may not create durable or semi-durable artifacts in databases, Redis, object storage, or other shared stores. Platform-owned execution, readiness, fencing/recovery, and `script_event_audit` metadata remain allowed and are not handler-created artifacts.
- Implementations must reserve bounded publish-time capacity for `onLoad`, including:
  - explicit concurrency ceilings,
  - explicit timeout/CPU/memory ceilings, and
  - bounded infrastructure retry policy for transient failures.
- Exhausting this dedicated initialization capacity must fail the patch deterministically with an explicit bounded reason (for example `onload_budget_exceeded`) rather than leaving readiness pending indefinitely or consuming arbitrary live runtime budget.
- Operators must be able to distinguish:
  - publish/readiness capacity exhaustion,
  - logical `onLoad` failures,
  - sandbox-limit failures, and
  - ordinary live-traffic quota denials.

This separation is required so patch publication remains predictable under load and so live automation traffic cannot accidentally block all progress on new script patch readiness.

### Budget Accounting Rules

Quota and budget policy must be applied at fixed durable charge points so operators can predict what a burst costs and retries cannot distort usage. This section owns the lifecycle; runtime and sandbox documents retain only their local consequences:

- **One handler charge record:** For every resolved handler, including `onLoad`, Automation creates or reuses one durable record keyed by the full applicable Trigger Identity. The record has separate exactly-once `admissionCharged` and `executionStarted` markers. A duplicate or recovery attempt with the same identity reads and reuses those markers rather than charging again.
- **Admission marker:** The ordinary live per-script quota is charged once when handler admission is accepted. Event-scope ingress acceptance alone does not charge it. A handler may enter a bounded queue after this marker, but queued work holds no tenant or cluster execution capacity.
- **Execution-start marker and lease:** Before DSL evaluation, the worker acquires a separately fenced, reclaimable tenant/cluster capacity lease, then in one Automation-owned durable executor-acceptance transaction revalidates the current fence, durably accepts/claims the run for the executor, persists the exactly-once execution-start marker, and transitions the work item to `EXECUTING`. Evaluation begins only after that commit. If the executor cannot accept the run, the transition does not commit, the lease is released or reclaimed, and no execution charge is recorded. A crash after commit recovers from the durable executor claim and may reacquire a lease without creating another marker; a lease is not a quota refund.
- **No refund:** Output-budget failures, sandbox errors, rollback fencing, and downstream infrastructure failures do not reverse committed markers. They remain visible as charged non-success outcomes.
- **`PUBLISH_READINESS`:** `onLoad` uses an isolated readiness charge/lease class. It never consumes ordinary live per-script quota or live tenant/cluster execution capacity and must not be inferred from the event name at execution time; `quotaClass` is persisted on durable work.

Concrete mixed fan-out accounting example:

- One inbound `TriggerScriptEvent` for `onEnterRegion` is admitted at event scope and resolves to three handler-scoped Trigger Identities: `S1`, `S2`, and `S3`.
- `S1` is rejected immediately with `finalStage=ADMISSION`, `finalOutcome=quota_denied`. It consumes no tenant runtime execution budget and no sandbox CPU/memory budget.
- `S2` is accepted under `concurrencyPolicy=queue_until_free`. Its handler charge record records the admission marker, but it holds no execution capacity until a fenced lease is acquired and the execution-start marker is committed.
- `S3` is admitted directly to execution. Its handler charge record records the admission marker and then the exactly-once execution-start marker under a valid capacity lease.
- If `S2` later reaches execution and fails with `sandbox_error`, or `S3` later fails with `work_item_size_exceeded`, the already-charged quota/execution budget is not refunded.

---

## Resource Isolation and Multi-Level Budgets

Budgets operate at three main levels:

- **Per-script**:
  - Quotas and cadence (`intervalTicks`) limit how often a script may run.
  - Concurrency and queue policies cap how many runs may be active or buffered at a time.

- **Per-tenant**:
  - Budgets per tenant and priority tier are tracked through the live reservation counters `automation:tenant-budget:<tenantId>:tier:<tier>` and the bounded tenant-budget metric consequences defined by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix).
  - When a tenant exhausts its budget for a tier, lower-priority work for that tenant is skipped with the Table 4 pre-evaluation skip consequence using reason `tenant_budget_exceeded`, while other tenants continue to make progress.

- **Cluster-wide**:
  - Global ceilings on automation work (for example, CPU/time budgets and `AUTOMATION_TICK_MAX_EVENTS`) protect the cluster.
  - Scheduling provides tenant fairness before applying priority within each tenant's share. Bounded weights favor `high` over `normal` and `background`, but no tier bypasses per-script, tenant, sandbox-capacity, or cluster ceilings.
  - Under sustained overload, declared best-effort background work may be delayed or dropped and its starvation must be measurable. Correctness-bearing work must use a recovery class that does not depend on eventual execution of best-effort background traffic.

All script-side keys remain scoped by `tenantId`, and scheduler ownership must remain explicit enough that each tenant’s automation workload can be reasoned about and tuned independently while still sharing the same infrastructure. Operator-facing metrics, however, must use the bounded `scope`, category, family, or tier labels defined in canonical Table 4 rather than raw tenant/runtime identifiers. Do not infer a separate canonical `script-leader:*` prefix unless the Redis coordination docs explicitly add one.

See [ADR 0166](./decisions/adr-0166-attributable-script-breakers-and-tenant-first-fairness.md).

### Quota & Budget Summary

The table below summarizes the major quota and budget types that apply to scripting, along with their scope, governing settings, and key metrics:

| Type | Scope | Governing settings / sources | Primary metrics |
| --- | --- | --- | --- |
| **Per-script quota** | Per script (`tenantId`, `scriptId`) | `SCRIPT_QUOTA_LIMIT`, `SCRIPT_QUOTA_WINDOW_SECONDS`, evaluated by `ScriptQuotaService` before a run starts. | Quota-allow/deny and drop consequences defined by Table 4; use the audit and control-plane reads for individual-script diagnosis. |
| **Per-script cadence & concurrency** | Per script | `intervalTicks`, `concurrencyPolicy` (`drop_new` / `queue_until_free`), `maxConcurrent`. Stored in script metadata and used by the scheduler when deciding which triggers to admit. | Queue delay and drop metrics plus stage-aware audit fields (`finalStage`, `finalOutcome`, `finalReason`) such as `finalStage=ADMISSION` with `finalOutcome=quota_denied`. |
| **Per-script priority** | Per script | `priorityTag` (`high`, `normal`, `background`) and per-tier enqueue budgets (for example, `high=8/min`, `normal=4/min`, `background=2/min`). | Tiered trigger/skip metrics that show how often high/normal/background work is admitted or throttled. |
| **Per-tenant tier budgets** | Per tenant and priority tier | `SCRIPT_TENANT_BUDGET_HIGH_RUNS_PER_MINUTE`, `SCRIPT_TENANT_BUDGET_NORMAL_RUNS_PER_MINUTE`, and `SCRIPT_TENANT_BUDGET_BACKGROUND_RUNS_PER_MINUTE`, evaluated by `ScriptTenantBudgetService` when live work reserves execution capacity. | Budget allow/deny metrics by bounded `scope`/`tier`, plus matching audit entries with `finalStage=ADMISSION` and `finalOutcome=tenant_budget_exceeded` for tenant-specific investigation. |
| **Cluster-wide safety limits** | Entire Automation & Scripting cluster | Global ceilings on automation work, including `AUTOMATION_TICK_MAX_EVENTS` and cluster-level CPU/time budgets. | Cluster-level throughput and drop metrics that indicate when global ceilings are hit. |

Per-trigger output is also part of the quota model even when the run itself was admitted successfully:

- Each admitted script/plugin run must be constrained by explicit output ceilings such as `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes`.
- Output-budget failures must be surfaced as stage-aware non-success outcomes and must not be treated as successful handoff merely because the DSL graph began evaluating.
- Game Design validation must reject graphs whose conservatively bounded worst-case fan-out cannot fit within those runtime ceilings, using the [static output cost contract](./system-architecture-scripting-runtime-execution.md#static-output-cost-contract).

---

## Auditability & Metrics

Every resolved-handler or materialized-work lifecycle decision emits an audit record stored in a lightweight `script_event_audit` table in PostgreSQL. Pre-handler dry-run rejection, pre-handler signer-policy unavailability, and retryable rollback-pause backpressure are recorded in the event-scope ingress audit instead; an admitted event with zero handlers is metric-only. `scriptEventId` is one field within the full applicable Trigger Identity; it must never be treated as unique on its own because runtime scope, handler, event, patch, and other conditional identity fields can distinguish otherwise equal tokens. Retries, replays, and downstream side effects must be correlated using the complete identity (not as a metric label). The authoritative audit field and stage model is defined in the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md).

Normative tables for Trigger Identity fields, metric label sets, and metric increment units are centralized in the [scripting normative contract tables](./system-architecture-scripting-normative-contract-tables.md) so this document does not drift from other design docs.

Metric troubleshooting must use only the bounded labels defined by Table 4. Never put raw `tenantId`, `gameInstanceId`, or `regionId` on a metric; retain those identifiers in `script_event_audit`, audit records, logs, traces, Redis inspection, or control-plane queries for drilldown.

The **target-state** canonical `script_event_audit` schema includes:

- **Core identifiers**
  - `scriptEventId` – idempotency token within the full applicable Trigger Identity; it is not a standalone unique audit key.
  - `tenantId` – tenant/game owning the script.
  - `gameInstanceId` – running game instance that emitted a gameplay/runtime trigger; absent for tenant-readiness `onLoad`.
  - `regionId` – region (where applicable) associated with the trigger.
  - `scriptId` – script definition that handled the trigger.
  - `eventType` – logical event key (for example, `onEnterRegion`, `onInterval`, `inventory.item_added`).
  - `scriptPatchVersion` – for instance-scoped gameplay/runtime rows, the logical script patch identifier supplied by Game Session and used to resolve the runtime script set; tenant-readiness `onLoad` uses the candidate patch from Automation's tenant-readiness lifecycle.
  - `scriptPinEpoch` – exact Game Session selection epoch paired with `scriptPatchVersion` for instance-scoped gameplay/runtime rows; version-only observations cannot authorize admission or execution. It is absent for tenant-readiness `onLoad`, whose identity remains tenant/script/event-schema/patch/event-type/`isDryRun`/`scriptEventId`.
  - `versionId` – optional internal compiled script version identifier used by the Automation & Scripting Service for engine-level debugging and migrations.
  - `sourceService` – producing service identity for custom/service-specific events so operators can diagnose routing and authorization problems.
  - `tickId` – canonical tick identifier associated with the trigger when the trigger is tick-aligned or once commands are accepted into the tick system.

For tenant-readiness `onLoad` triggers, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, and `scriptPinEpoch` are omitted; they must not be populated with sentinel values. Their existing identity remains `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun, scriptEventId>`. Gameplay/runtime triggers require `scriptPinEpoch` with `scriptPatchVersion` and include the other fields when applicable according to the normative Trigger Identity table.

The current Automation admission, audit, and work-item surfaces do not yet propagate `scriptPinEpoch` end to end; see the [Automation & Scripting Service implementation status](./microservices/automation-scripting-service/runtime-and-data.md#implementation-status).

- **Stage-aware outcome**
  - `finalStage` – the last stage reached for the trigger (for example `ADMISSION`, `DSL_EVAL`, `WORK_ITEM_PERSIST`, `TICK_HANDOFF`).
  - `finalOutcome` / `finalReason` – canonical classification and diagnostic reason for what happened at `finalStage`.
  - Optional structured stage breakdown (for example a `stages` JSON array) so operators can distinguish “rejected before DSL” from “DSL evaluated but handoff failed”, following the observability contract.

- **Operational details**
  - Timestamps and duration fields.
  - Optional actor principal for administrative actions (disable/enable/throttle).
  - `isDryRun` – boolean flag indicating whether this execution was a non-committing dry-run/test (`true`) or live traffic (`false`).
  - Optional dead-letter linkage (`deadLetterId` or equivalent) when work transitions to bounded dead-letter stores during version-fence drops or outbox failure handling.

During rollback draining, operators should also expect a bounded number of old-epoch rows whose runs started before pause but were fenced before persistence or handoff. Those rows should appear as non-success canceled outcomes for the original Trigger Identity, not as silently dropped work. A typical example is `finalStage=WORK_ITEM_PERSIST`, `finalOutcome=canceled`, `finalReason=rollback_epoch_advanced`, paired with rollback/drain metrics for the same scope.
At the metric layer, these rows should contribute to the bounded rollback/drain visibility defined by Table 4 for the paused scope rather than disappearing into generic infrastructure noise. This local consequence lets operators confirm that draining work was fenced intentionally rather than lost unexpectedly.

`script_event_audit` remains the authoritative record for Automation-owned stages through `TICK_HANDOFF`, but it is not the sole post-handoff surface. The complete per-command handoff diagnostics below are **target-state**: the live Game Session proto carries `automationDispatchId`, command id/text, and selected provenance fields, but not `commandOrdinal` or the full Trigger Identity. Current live status/readback therefore remains narrower. In the target state, per-command handoff and execution-time version-fence results are queried through `ListScriptHandoffEvents` and composed as `commandHandoffDispositions[]`, with one child keyed by the complete Command-Handoff Identity defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state) and correlated to the complete parent Trigger Identity. `automationDispatchId` is a dispatch-group identifier, not a globally unique child key:

- If Game Session later drops a handed-off script command because its embedded `(scriptPatchVersion, scriptPinEpoch)` no longer matches Game Session's current exact tuple, operator tooling must be able to locate that drop directly from the originating Trigger Identity. A plugin-produced command is independently dropped when its embedded exact `(pluginId, pluginVersionId, bindingId)` tuple or captured `(pluginActivationEpoch, lifecycleRevision)` fence pair does not match Automation's current authoritative runtime evidence, or when Automation-revalidated fresh activation/lifecycle, publication, signer-policy, component-policy, or capability-grant evidence fails; the child disposition retains the captured pair as execution evidence. A present captured activation epoch that differs from current authoritative epoch evidence uses `plugin_activation_epoch_mismatch`; a present plugin or lifecycle provenance mismatch uses `plugin_binding_mismatch`; general runtime-authority evidence that is unavailable or unverifiable uses `authority_unavailable`, component-policy evidence that is unavailable or unverifiable uses `component_policy_unavailable`, signer-policy evidence that is unavailable or unverifiable uses `signer_policy_unavailable`, and a proven blocked component uses `plugin_component_policy_blocked`. When a plugin command carries both kinds of provenance, both independent fences apply.
- The target-state mechanism is the per-command handoff contract in the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md): Game Session reports a bounded child handoff result through `ListScriptHandoffEvents`, retaining the parent Trigger Identity and `outboxWorkItemId` while keying the command record by the complete Command-Handoff Identity. `outboxWorkItemId` is parent correlation only and is excluded from command identity, uniqueness, and deduplication keys; plugin `bindingId` is retained only as plugin provenance/correlation/fence evidence and is excluded from Command-Handoff child uniqueness, deduplication, and replay selection.
- Dashboards and incident tooling should therefore show both:
  - Automation pipeline completion (`finalStage`, `finalOutcome`) and
  - the later per-command handoff result in `commandHandoffDispositions[]` (for example `outcome=version_fence_dropped`, with a bounded reason).

Concrete rollback-visibility example:

- Trigger Identity `T123` reaches `finalStage=TICK_HANDOFF`, `finalOutcome=handoff_accepted` after every required child dispatch is durably accepted by Game Session.
- Before the queued command executes, operators roll the instance back to an older `scriptPatchVersion`, advancing the instance's `scriptPinEpoch`.
- Game Session rejects only the command-handoff child selected by the complete Command-Handoff Identity (complete source/target runtime scope plus `(automationDispatchId=dispatch-9, commandOrdinal=1)`); see the [normative identity table](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state). The parent Trigger Identity, including `bindingId` when applicable, and `outboxWorkItemId=work-9` remain correlation-only and are excluded from child uniqueness and deduplication. `ListScriptHandoffEvents` returns that target-state child with `outcome=version_fence_dropped`, `reason=script_patch_mismatch`, and `sourceService=game-session`, while the sibling child with the same complete source/target scope and `(automationDispatchId=dispatch-9, commandOrdinal=0)` remains a separate result.
- Operator tooling for `T123` must therefore show `finalStage=TICK_HANDOFF`/`finalOutcome=handoff_accepted` together with the complete per-dispatch outcome collection, rather than overwriting the handler result or collapsing the commands into one disposition.

Retention and sizing are governed by the environment catalog in [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md#service-specific-variables); in particular, `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS` control how long audit rows are retained and how large the table is allowed to grow. This document retains the local control consequences; the catalog owns defaults and exact variable semantics. Retention cleanup must keep a recoverable row and its stage/manifest/output/child evidence as one coherent bundle, fail closed on incoherence, and never leave a row advertised recoverable after independently deleting its supporting evidence.
Dead-letter stores used for rejected queue entries or non-progressing outbox work must also define explicit `maxAge`, `maxRows`, cleanup cadence, and alert thresholds; unbounded dead-letter growth is not an acceptable operational mode. These controls should be exposed as operator knobs (for example, `SCRIPT_DEAD_LETTER_MAX_ROWS`, `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS`, `SCRIPT_DEAD_LETTER_CLEANUP_INTERVAL_SECONDS`, `SCRIPT_DEAD_LETTER_ALERT_THRESHOLD_ROWS`) rather than implicit defaults.

Dead-letter recovery evidence follows the owner contract in [Scripting Control Plane Operations](./system-architecture-scripting-control-plane-operations.md#replaydeadletteredworkitems): Automation's monotonic `failureGeneration` is initialized or advanced exactly once with the atomic parent transition into `DEAD_LETTERED`, and the dead-letter row, per-item request result, recovery claim/attempt, success evidence, and `already_recovered` readback bind `(tenantId, outboxWorkItemId, failureGeneration)`. Retention and cleanup must keep each generation's evidence immutable and coherent; an older generation's evidence cannot satisfy or block a newer current-generation recovery.

Metric-family names, labels, and increment units are owned exclusively by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix); this document does not repeat those schemas. See the [Observability Contract](./system-architecture-scripting-observability-contract.md) and `design/architecture/system-architecture-logging-monitoring.md` for local diagnostic and alerting guidance. Operators use the Table 4 families to monitor admission, policy skips, quota/budget decisions, and tick handoff. An admitted event that resolves zero handlers has only the Table 4 metric-only consequence `outcome="admitted_no_handlers"`; it is not an ingress-response or audit-field outcome. For dry-runs specifically:

- Materialized dry-run/test attempts, their runtime, and isolated capacity denials use the dedicated test-only families defined in Table 4. The current legacy materialized path records a successful handler attempt as `finalStage=DSL_EVAL`, `finalOutcome=dry_run_completed`, and `finalReason=dry_run_no_handoff`; it does not use the target preview taxonomy. Target ADR 0114 isolated previews instead use `finalStage=DRY_RUN_RESULT` and `finalOutcome=dry_run_success`, return or retain would-be commands without a handler `script_event_audit` row, and are not live `finalOutcome=handoff_accepted`. Pre-resolution denials remain event-scope ingress/drop outcomes.
- Dry-run capacity denials remain isolated from per-script live quota decisions and do not increment `script_quota_denied_total`.

The following are local interpretations of the Table 4 queue-health metric families and help detect automation backlogs that are not draining into ticks as expected:

- `automation_queue_orphaned_entries_total` – counts work items that have remained in `automation:queue:{tenantInstanceTag}:<entityId>` beyond a bounded age window (for example, N ticks or seconds) without corresponding durable-executor progress or entries in the tick effect ledger.
- `automation_queue_oldest_entry_age_seconds` – records the age of the oldest sampled queue item per bounded scope so operators can see when automation queues are falling behind; tenant, instance, region, and script drilldown remains in audit/log/trace records.

A small, bounded Automation-owned inspector loop periodically samples a subset of queues to update these metrics; it does not attempt to repair or delete items itself, but surfaces misalignment between queue projection, durable executor progress, and tick processing for investigation.

The Table 4 live/test family split requires live work-item, sandbox, error, and runtime observations to be separated from dry-run/test observations by execution mode. This keeps quota, audit, queue, and dashboard consequences attributable to live automation or privileged test traffic without duplicating the metric schema here.

Plugin executions use the Table 4 bounded plugin classifications rather than raw ids. Policy-specific behavior is surfaced through the Table 4 policy-violation metric consequence so operators can separate policy enforcement from quota or sandbox failures.

### Cross-Service Correlation

Script execution spans several services (Game Design, Game Session, Automation & Scripting, Game Logic, Logging & Admin). To support end-to-end debugging and replay, the system relies on a shared set of identifiers:

- `tenantId`, `gameInstanceId`, and `regionId` – identify the running game instance and region.
- `playableStateScope` (gameplay/runtime only) – identifies the resolved admitted gameplay-state namespace; shared and isolated realm state must not collide. Retain it in applicable audit/log/trace correlation, not as a metric label. See the [normative Trigger Identity table](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields); tenant-readiness `onLoad` intentionally omits it.
- `regionEpoch` – fences triggers and tick effects across scoped coordination resets.
- `entityId` – identifies the target entity for script-driven work.
- `scriptId` and `scriptPatchVersion` identify the script definition; gameplay/runtime rows also require `scriptPinEpoch` to identify the Game Session selection epoch. Tenant-readiness `onLoad` rows omit `scriptPinEpoch`.
- `scriptEventId` – caller-scoped idempotency token within the full applicable Trigger Identity; it is not a standalone execution identity.
- `tickId` – identifies the authoritative game tick in which commands execute (paired with `regionEpoch`).
- `correlationId` – optional cross-service correlation token for Sagas and user-visible flows.

These identifiers are intended to appear in the following observability surfaces. The per-command handoff surface follows the target/current distinction above: target records use the full identity, while current Game Session readback may remain narrower:

- `script_event_audit` records in the Automation & Scripting Service.
- Tick logs and effect ledgers in the Game Session and Game Logic services.
- Logs and traces emitted by the Logging & Admin Service.

A typical troubleshooting flow for a problematic script or plugin is:

1. Start from a player-visible issue or a game tick log that includes `tenantId`, `gameInstanceId`, `playableStateScope` when gameplay/runtime-scoped, `regionId`, `regionEpoch`, `entityId`, and `tickId`.
2. Join the tick log, `script_event_audit`, and logs/traces by the complete applicable Trigger Identity: `tenantId`, `gameInstanceId`, `playableStateScope` when gameplay/runtime-scoped, `regionId`, `regionEpoch`, `entityId` when applicable, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptPinEpoch` when applicable, `scriptEventId`, `isDryRun`, and plugin/scheduler fields when applicable. `scriptEventId` and `correlationId` are scoped filters that narrow a search; neither is a standalone execution identity and neither may be used as the join key without the remaining applicable fields. Do not rely on `scriptEventId` as a metric label; use metrics to understand aggregate rates by bounded `scope` / `script_kind` / `event_class` dimensions and use audit/log/trace queries for per-trigger correlation.
3. From the identity-matched records, identify the responsible `scriptId`, `scriptPatchVersion`, and, where applicable, `pluginId`/`pluginVersionId`/`bindingId`.
4. Cross-reference the associated publish or plugin enable/disable actions in Game Design and Logging & Admin using the same identifiers.

By retaining the complete applicable Trigger Identity in audit/log/trace records, operators can follow a single script event across authoring, publishing, execution, and downstream effects without ad hoc identity joins or heuristics; metrics remain aggregate and bounded.

### Dry-Run Budgets & Limits

Dry-run and test executions share the same sandbox engine and guards as live traffic but are subject to their own **budgets and rate limits** so they cannot bypass safety mechanisms:

- Dry-runs do **not** consume ScriptQuotaService windows or per-tenant automation budgets that gate live triggers, but they:
  - Execute through the same sandbox, CPU, and memory budgets described in the [sandbox runtime design](./microservices/automation-scripting-service/sandbox-runtime-design.md).
  - Contribute to dry-run/test-only metrics (for example, `automation_script_test_runs_total`, `automation_script_test_runtime_seconds`, `automation_script_test_sandbox_failures_total`) so behavior is observable without affecting live-traffic dashboards and SLOs.
- Dry-run/test triggers must use a separate idempotency namespace from live traffic (for example include `isDryRun=true` in Trigger Identity) so test executions cannot dedupe, suppress, or overwrite live trigger handling/audit rows.
- Dry-run/test executions must never contribute to failure-rate circuit breakers that can disable live scripts (`runtimeStatus=DISABLED_DUE_TO_ERRORS`). Any safety gate based on dry-run failures must use an always-isolated test-only breaker or gate whose state cannot affect live breaker state or live-script admission; no environment, tenant, or request opt-in may cross that boundary.
- To prevent abuse, the Automation & Scripting Service enforces additional dry-run ceilings, such as:
  - Per-tenant and per-principal maximum runs per window (for example, `SCRIPT_TEST_MAX_RUNS_PER_MINUTE`).
  - Maximum concurrent dry-runs per tenant and cluster-wide (for example, `SCRIPT_TEST_MAX_CONCURRENCY` and `SCRIPT_TEST_MAX_CLUSTER_CONCURRENCY`).
- Dry-run/test executions must also have isolated execution capacity:
  - Separate queues or worker reservations are required so privileged test traffic cannot starve live automation workers.
  - When shared infrastructure is saturated, dry-run/test work must be shed before live gameplay automation for the same scope/tier.
- Per-principal limits require deterministic identity:
  - Use a stable principal key derived from authenticated actor claims (for example, subject/`actorPrincipal`) in dry-run quota keys.
  - Reject missing principal identity for endpoints configured with per-principal enforcement.
- Dry-run activity is surfaced via dedicated metrics (for example, `automation_script_test_runs_total`, `automation_script_test_runtime_seconds`, `automation_script_test_sandbox_failures_total`) so operators can distinguish test traffic from live automation.
- Logging & Admin and Game Design tools are responsible for exposing dry-run entry points only to privileged users and for applying complementary API gateway limits; test endpoints must not be wired into game traffic or public-facing flows.
- When a dry-run request exceeds `SCRIPT_TEST_MAX_RUNS_PER_MINUTE`, Automation rejects it at event scope with `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED` / `dry_run_budget_exceeded` before handler resolution. When a materialized dry-run exceeds `SCRIPT_TEST_MAX_CONCURRENCY` or `SCRIPT_TEST_MAX_CLUSTER_CONCURRENCY`, Automation cancels that handler-scoped work item before evaluation with `finalOutcome=quota_denied` and `finalReason=dry_run_capacity_exhausted` in `script_event_audit`, and applies the Table 4 test-capacity consequence. The latter is not a per-script quota denial and must not increment the live per-script quota family.

### Outcome-to-Metric Mapping

This section is **illustrative**, not normative. The **authoritative metric definitions** for family names, labels, and increment units live in Table 4. Local audit/diagnostic behavior and broader alerting guidance live in:

- [Scripting normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix)
- [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)

Implementations should align emitted metrics with Table 4; the intent here is only to show illustrative local consequences for common outcomes so readers understand the observability story. These examples do not define additional labels, units, or metric families.

At a high level:

- **Handler-level quota and budgeting outcomes**
  - `finalStage=ADMISSION`, `finalOutcome=quota_denied`, `finalReason=script_quota_denied` – per-script quota denial is recorded in `script_event_audit` and `automation_script_triggers_total{outcome="quota_denied"}`; it increments `script_quota_denied_total` but does **not** increment the pre-resolution dropped metric or sandbox failure metrics.
  - `finalStage=ADMISSION`, `finalOutcome=quota_denied`, `finalReason=dry_run_capacity_exhausted` – handler-scoped dry-run capacity denial is recorded in `script_event_audit` and uses the Table 4 test-only consequences `automation_script_test_runs_total` and `automation_script_test_capacity_denied_total{scope}`; it must not increment live `automation_script_triggers_total`, `script_quota_denied_total`, or other live families.
  - `finalStage=ADMISSION`, `finalOutcome=tenant_budget_exceeded` (or other budget-related outcomes) – handler-level budget denial is recorded in `script_event_audit` and `automation_script_triggers_total{outcome="tenant_budget_exceeded"}`; it does **not** increment the pre-resolution dropped metric or run the sandbox.

- **Pre-resolution ingress outcomes**
  - `automation_script_triggers_dropped_total` is reserved for trigger requests rejected before handler resolution and their event-scope ingress audit records. Intentional pre-eval skips remain represented by `automation_script_skips_total`.
  - Signer-policy unavailability before handler resolution is event-scope and ingress-audit-only: it rejects the event without consuming handler quota or sandbox capacity and records only `script_event_ingress_audit`, not a handler-scoped `finalOutcome` or `script_event_audit` row. **Target-state:** signer-policy unavailability after binding resolution remains handler-scoped in `script_event_audit` with `finalStage=ADMISSION` and `finalOutcome=signer_policy_unavailable`; in the current runtime, the stale-policy check is pre-handler only for requests that explicitly carry plugin identity, while generic ingress whose plugin-owned handler is resolved later does not yet enforce that stale-policy check.

- **Sandbox-level failures**
  - `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error` – the DSL runtime rejected the run or hit a guard (for example, `finalReason=cpu_budget_exceeded`, `finalReason=memory_budget_exceeded`, `finalReason=iteration_budget_exceeded`); for live traffic (`isDryRun=false`), this contributes to the live sandbox/error consequences defined by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) and feeds failure-rate circuit breakers. Dry-run/test failures use the corresponding test-only families instead.
  - Output-budget failures (for example `finalReason=command_count_exceeded`, `finalReason=per_entity_command_limit_exceeded`, `finalReason=work_item_size_exceeded`) have the Table 4 output-budget metric consequence and remain non-success stage-aware outcomes; they must not be counted as successful handoff.

- **Infrastructure-level failures**
  - `finalOutcome=infrastructure_error` (with a `finalStage` that reflects where it failed) – transport or infrastructure problems (for example, Redis timeouts, gRPC `UNAVAILABLE`); counted separately from sandbox errors, may trigger retries at lower layers using idempotency keys, and contribute to infra-focused alerts.

- **Event-scope ingress outcomes**
  - During an ordinary rollback pause or convergence backpressure before terminal timeout, a rejected pre-handler ingress uses `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK`, `admissionReason=rollback_pause`, and a bounded `retryAfterMs` when retry is allowed; `script_event_ingress_audit` records the denial and the same event-scope claim may be reclaimed under the bounded retry lifecycle. This is retryable ingress backpressure only, not a handler-scoped final outcome.
  - While promotion or rollback pin convergence timeout terminal state is active, each rejected pre-handler ingress returns `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE`, `admissionReason=pin_convergence_timeout`, and no `retryAfterMs`; `script_event_ingress_audit` records the same fields and has the Table 4 ingress-drop consequence once for that rejected ingress. This is terminal fail-closed state, not retryable backpressure. The separate Table 4 terminal-timeout metric consequence applies only once when the owner workflow enters the terminal state, not for each rejected ingress. This condition does not create a handler-scoped `finalOutcome`.

The failure-rate circuit breaker primarily considers **sandbox_error**, **`validation_error` from live handler/runtime validation**, and other qualifying deterministic handler failures when deciding to transition a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS` or a plugin's separate `breakerState=DISABLED_DUE_TO_ERRORS`. Quota denials and purely infrastructure-level errors do not, by themselves, trigger disables, although they should still be visible in metrics and dashboards. For plugins, this breaker state is keyed to the exact active version/runtime scope and must not be represented by `pluginState`.

---

## Related Operations Cookbook

The steady-state quota, budget, and observability contracts stay in this parent doc. The operational playbooks now live in a focused sibling doc:

- [Scripting Operations Cookbook](./system-architecture-scripting-operations-cookbook.md) covers quota/budget troubleshooting, disable/throttle flows, rollback and recovery scenarios, and rollback recovery procedures.

---

## Environment Variables

The **authoritative, up-to-date list of environment variables and defaults** lives in [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md#service-specific-variables). The [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md#document-map) only maps related service documents. This section only calls out conceptual categories so it remains stable as new settings are added:

- **Quota knobs** – control per-script and per-tenant quota windows and budgets used by `ScriptQuotaService` and the multi-level budgeting model (for example, limits on how many triggers a script or tenant may execute per window).
- **Execution batch knobs** – bound how much automation work the durable executor performs per scheduling window, including batch sizes, per-window budgets, and cluster-wide ceilings on automation events.
- **Timer and scheduling knobs** – influence `onInterval` / `onTimerExpire` behavior, including cadence and maximum timers evaluated per canonical runtime scope tuple `<tenantId, gameInstanceId, regionId>`, plus any backoff or delay settings applied when regions are degraded.
- **Audit and retention knobs** – govern how long `script_event_audit` and related records remain available for troubleshooting, and how large those tables are allowed to grow before automated cleanup; retention is typically controlled via `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS`, with exact defaults and semantics documented in [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md#service-specific-variables).

For the exact variable names, defaults, and any future additions, always refer to [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md#service-specific-variables) rather than the README or this document.

---

## Developer Tools

Several helper scripts streamline common tasks when working with scripting and automation flows:

- `dev-tools/firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `dev-tools/docs/generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `dev-tools/docs/generate-grpc-docs.sh` – generates Markdown documentation from protobuf definitions.
- `dev-tools/seed/seed-automation-scripting-data.sh` – populates the Automation & Scripting Service with sample scripts, actions, and quotas so you can observe scheduler behavior without manual editing.

These scripts complement the web-based editor and allow creators and operators to automate routine actions when testing or observing the scripting system.
