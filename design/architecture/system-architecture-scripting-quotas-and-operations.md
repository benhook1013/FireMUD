# FireMUD Scripting Quotas & Operations

This document focuses on **sandboxing, quotas, budgets, and operational workflows** for the scripting and automation framework.

It is intended as the main reference for operators, SREs, and platform engineers responsible for safe multi-tenant operation of scripts.

Companion docs:

- `design/architecture/system-architecture-scripting-dsl-and-lifecycle.md` – terminology, DSL semantics, event lifecycle, determinism.
- `design/architecture/system-architecture-scripting-examples-and-patterns.md` – worked examples (for example, `onEnterRegion`, periodic patrol).
- `design/architecture/system-architecture-scripting.md` – high-level hub and TL;DR flow.

## Table of Contents

- [Audience](#audience)
- [Sandboxing & Security](#sandboxing--security)
- [Fairness & Abuse Prevention](#fairness--abuse-prevention)
- [Resource Isolation and Multi-Level Budgets](#resource-isolation-and-multi-level-budgets)
- [Quota & Budget Summary](#quota--budget-summary)
- [Auditability & Metrics](#auditability--metrics)
- [Outcome-to-Metric Mapping](#outcome-to-metric-mapping)
- [Operational Cookbook: Quotas, Budgets, and Metrics](#operational-cookbook-quotas-budgets-and-metrics)
- [Operational Disable / Throttle Flows](#operational-disable--throttle-flows)
- [Environment Variables](#environment-variables)
- [Rollback & Recovery Cookbook](#rollback--recovery-cookbook)
- [Developer Tools](#developer-tools)

---

## Audience

- **Operators, SREs, and platform engineers**
  - Use this document as the primary reference for runtime knobs, quotas, and operational workflows around scripting.
  - Pair with:
    - `design/architecture/system-architecture-logging-monitoring.md`
    - `design/architecture/system-architecture-redis.md`
    - Automation & Scripting Service README: `design/architecture/microservices/automation-scripting-service/README.md`

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

For lower-level sandbox and runtime internals, see `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`.

Dry-run and test execution paths exposed by the Automation & Scripting Service share the same sandbox and guardrails as live traffic:

- They execute handlers through the same engine with the same CPU/iteration and memory budgets.
- They record `sandbox_error` and `infrastructure_error` outcomes in `script_event_audit` so failure modes are observable.
- By default they do **not** consume per-script quotas or per-tenant budgets enforced by `ScriptQuotaService`; instead, they are restricted to privileged principals (for example, designers and operators) and should be further protected by separate rate limits or ACLs at the API gateway or Logging & Admin layer.
  - Dry-run/test executions must not increment live-traffic error counters. Sandbox failures observed during tests are emitted via dry-run/test-only metric families (for example `automation_script_test_sandbox_failures_total`) so production SLO dashboards do not conflate privileged tooling with live automation reliability.

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

These controls work alongside the **failure-rate circuit breaker**, which can automatically place scripts into a `disabled_due_to_errors` state when error rates exceed configured thresholds in a window.

### Plugin Workloads

Plugins executed via the modding framework share the same underlying quota and scheduling infrastructure as regular scripts:

- Each plugin is represented in the Automation & Scripting Service as a script-like runtime object with a distinct identifier (for example, `scriptType=PLUGIN` plus `pluginId` and `pluginVersionId` metadata) and participates in the same per-script quota and concurrency model.
- Plugin triggers (for example, `onEnterRoom` or `onItemUse` events wired through the plugin system) run under the same multi-level budgeting model:
  - Per-plugin quotas enforced by `ScriptQuotaService`.
  - Per-tenant budgets, including priority tiers (for example, `high`, `normal`, `background`).
  - Cluster-wide ceilings and automation tick budgets.
- From an observability perspective, plugin executions are recorded in `script_event_audit` alongside other script runs, with additional tags such as `pluginId` and `pluginVersionId` so operators can distinguish plugin activity from core automation.
- Plugin enforcement also respects a centrally managed component policy. When a plugin references a component that is disallowed by the current environment policy, its triggers are rejected at admission with `script_event_audit.finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked component/policy, and corresponding metrics (for example, `automation_plugin_policy_violations_total`) so operators can distinguish policy violations from quota or sandbox failures.

This alignment ensures that plugin code cannot bypass or weaken the resource-isolation guarantees of the scripting system; operational tooling and metrics apply uniformly to both plugins and regular scripts. For the structural lifecycle of plugins (versioning, enable/disable states, and rollback), see `design/architecture/microservices/game-design-service/modding-framework.md`; Logging & Admin APIs provide the control plane for changing `pluginState` and `activeVersionId` while the Automation & Scripting Service enforces quotas, budgets, sandbox rules, and component policy at runtime.

### Per-Script Scheduling Policies

Per-script scheduling knobs control how often scripts are allowed to run and how they behave under load:

- **`intervalTicks` (cadence)** – defines the target cadence for scheduler-driven events (for example, “every N ticks”). The scheduler uses tick heartbeats and timer indexes to decide when an `onInterval` or timer-based handler becomes due, keeping script cadence aligned with the canonical `tickId`.
- **`concurrencyPolicy` and `maxConcurrent`** – govern what happens when new triggers arrive while runs are already in progress:
  - `concurrencyPolicy=queue_until_free` keeps a short queue of pending triggers up to configured limits and runs them once existing executions complete.
  - `concurrencyPolicy=drop_new` skips new triggers while the script is already running, favoring bounded concurrency over backlog growth.
  - Queued triggers still count toward the script’s quota window; once quota limits are exceeded, additional triggers are dropped with `script_event_audit.finalStage=ADMISSION` and `finalOutcome=quota_denied` (or a more specific quota/concurrency outcome) and matching metrics.
- **`priorityTag`** – assigns a priority tier (`high`, `normal`, `background`) that interacts with per-tenant budgets and cluster ceilings. When capacity is tight, the scheduler continues to admit `high`-priority work preferentially and defers or drops lower-priority triggers according to budget and quota rules.

---

## Resource Isolation and Multi-Level Budgets

Budgets operate at three main levels:

- **Per-script**:
  - Quotas and cadence (`intervalTicks`) limit how often a script may run.
  - Concurrency and queue policies cap how many runs may be active or buffered at a time.

- **Per-tenant**:
  - Budgets per tenant and priority tier are tracked via metrics such as `automation_script_tenant_budget_seconds{tenantId, tier}`.
  - When a tenant exhausts its budget for a tier, lower-priority work for that tenant is skipped (`automation_script_skips_total{reason="tenant_budget_exceeded"}`) while other tenants continue to make progress.

- **Cluster-wide**:
  - Global ceilings on automation work (for example, CPU/time budgets and `AUTOMATION_TICK_MAX_EVENTS`) protect the cluster.
  - When limits are reached, the scheduler favors `high`-priority, latency-sensitive scripts and defers or drops `background` work.

All script-side keys and metrics are scoped by `tenantId`, and leadership leases such as `script-leader:{<tenantId>}` ensure that each tenant’s automation workload can be reasoned about and tuned independently while still sharing the same infrastructure.

### Quota & Budget Summary

The table below summarizes the major quota and budget types that apply to scripting, along with their scope, governing settings, and key metrics:

| Type | Scope | Governing settings / sources | Primary metrics |
| --- | --- | --- | --- |
| **Per-script quota** | Per script (`tenantId`, `scriptId`) | `SCRIPT_QUOTA_LIMIT`, `SCRIPT_QUOTA_WINDOWSECONDS`, evaluated by `ScriptQuotaService` before a run starts. | Quota-allow/deny and drop metrics for individual scripts; see the Automation & Scripting Service README for exact meter names and labels. |
| **Per-script cadence & concurrency** | Per script | `intervalTicks`, `concurrencyPolicy` (`drop_new` / `queue_until_free`), `maxConcurrent`. Stored in script metadata and used by the scheduler when deciding which triggers to admit. | Queue delay and drop metrics plus stage-aware audit fields (`finalStage`, `finalOutcome`, `finalReason`) such as `finalStage=ADMISSION` with `finalOutcome=quota_denied`. |
| **Per-script priority** | Per script | `priorityTag` (`high`, `normal`, `background`) and per-tier enqueue budgets (for example, `high=8/min`, `normal=4/min`, `background=2/min`). | Tiered trigger/skip metrics that show how often high/normal/background work is admitted or throttled. |
| **Per-tenant tier budgets** | Per tenant and priority tier | Tenant-scoped automation budgets per tier, tracked as aggregates such as `automation_script_tenant_budget_seconds{tenantId, tier}`. | Budget consumption and skip metrics per tenant/tier, plus matching audit entries with `finalStage=ADMISSION` and `finalOutcome=tenant_budget_exceeded`. |
| **Cluster-wide safety limits** | Entire Automation & Scripting cluster | Global ceilings on automation work, including `AUTOMATION_TICK_MAX_EVENTS` and cluster-level CPU/time budgets. | Cluster-level throughput and drop metrics that indicate when global ceilings are hit. |

---

## Auditability & Metrics

Every scheduler decision emits an audit record stored in a lightweight `script_event_audit` table in PostgreSQL. `scriptEventId` uniquely identifies the trigger instance so retries, replays, and downstream side effects can be correlated across audit queries, logs, and traces (not as a metric label). The authoritative audit field and stage model is defined in `design/architecture/system-architecture-scripting-observability-contract.md`.

Normative tables for Trigger Identity fields and metric label sets are centralized in `design/architecture/system-architecture-scripting-normative-contract-tables.md` so this document does not drift from other design docs.

The canonical `script_event_audit` schema includes:

- **Core identifiers**
  - `scriptEventId` – unique identifier for a single trigger/run.
  - `tenantId` – tenant/game owning the script.
  - `gameInstanceId` – running game instance that emitted the trigger (required for multi-instance tenants).
  - `regionId` – region (where applicable) associated with the trigger.
  - `scriptId` – script definition that handled the trigger.
  - `eventType` – logical event key (for example, `onEnterRegion`, `onInterval`, `inventory.item_added`).
  - `scriptPatchVersion` – logical script patch identifier supplied by Game Session and Game Design and used to resolve the runtime script set.
  - `versionId` – optional internal compiled script version identifier used by the Automation & Scripting Service for engine-level debugging and migrations.
  - `tickId` – canonical tick identifier associated with the trigger when the trigger is tick-aligned or once commands are accepted into the tick system.

- **Stage-aware outcome**
  - `finalStage` – the last stage reached for the trigger (for example `ADMISSION`, `DSL_EVAL`, `WORK_ITEM_PERSIST`, `TICK_HANDOFF`).
  - `finalOutcome` / `finalReason` – canonical classification and diagnostic reason for what happened at `finalStage`.
  - Optional structured stage breakdown (for example a `stages` JSON array) so operators can distinguish “rejected before DSL” from “DSL evaluated but handoff failed”, following the observability contract.

- **Operational details**
  - Timestamps and duration fields.
  - Optional actor principal for administrative actions (disable/enable/throttle).
  - `isDryRun` – boolean flag indicating whether this execution was a non-committing dry-run/test (`true`) or live traffic (`false`).

Retention and sizing are governed by environment variables described below and in the Automation & Scripting Service README; in particular, `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS` control how long audit rows are retained and how large the table is allowed to grow (current defaults are 30 days and 1,000,000 rows, but the README remains the authoritative source).

Metrics such as:

- `automation_script_triggers_total`
- `automation_script_skips_total`
- `automation_script_triggers_dropped_total`
- `script_quota_allowed_total`
- `script_quota_denied_total`
- `automation_tick_events_enqueued_total`
- `automation_script_test_runs_total`
- `automation_script_test_runtime_seconds`

are updated throughout the scripting pipeline so operators can monitor how often scripts fire, how many are skipped by policy, and how much automation work is being handed to the tick system. See `design/architecture/system-architecture-logging-monitoring.md` for broader metrics and alerting guidance. For dry-runs specifically:

- `automation_script_test_runs_total{tenantId, scriptId, pluginId, eventType, result}` – counts non-committing test executions, tagged with a `result` dimension (for example, `result="success"`, `result="denied_quota"`, `result="error"`).
- `automation_script_test_runtime_seconds{tenantId, scriptId, pluginId, eventType}` – measures runtime for dry-run/test executions, separate from live traffic.

Additional queue-health metrics help detect automation backlogs that are not draining into ticks as expected:

- `automation_queue_orphaned_entries_total` – counts work items that have remained in `automation:queue:<tenantId>:<entityId>` beyond a bounded age window (for example, N ticks or seconds) without corresponding staging in `automation:tick:{tenantScriptTag}:...` or entries in the tick effect ledger.
- `automation_queue_oldest_entry_age_seconds` – records the age of the oldest sampled queue item per tenant/script so operators can see when automation queues are falling behind.

A small, bounded inspector loop in `ScriptTickService` periodically samples a subset of queues to update these metrics; it does not attempt to repair or delete items itself, but surfaces misalignment between automation and tick processing for investigation.

For scripting and automation, these metrics follow shared naming and labeling conventions so dashboards and alerts remain consistent across services:

- `automation_script_triggers_total{tenantId, scriptId, pluginId, pluginVersionId, eventType, outcome}` – counts all admitted triggers, tagged with the final `outcome` for the run (the metric label value must match `script_event_audit.finalOutcome`, not “DSL eval success”).
- `automation_script_skips_total{tenantId, scriptId, pluginId, reason}` – counts triggers that were intentionally skipped before sandbox execution (for example, `reason="reloading"`, `reason="disabled"`, `reason="priority_throttled"`).
- `automation_script_triggers_dropped_total{tenantId, scriptId, pluginId, reason}` – counts triggers that could not be processed (for example, `reason="quota"`, `reason="cluster_limit_reached"`, `reason="version_unavailable"`).
- `script_quota_allowed_total{tenantId, scriptId}` / `script_quota_denied_total{tenantId, scriptId, reason}` – per-script quota decisions before sandbox work begins.
- `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}` – sandbox-level failures such as `reason="cpu_budget_exceeded"` or `reason="memory_budget_exceeded"`.
- `automation_script_errors_total{tenantId, scriptId, pluginId, reason}` – higher-level error classification, including downstream failures.
- `automation_script_tenant_budget_seconds{tenantId, tier}` – per-tenant, per-priority-tier budget consumption.
- `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}` – distribution of sandbox runtime per script/plugin and event type.
- `automation_plugin_policy_violations_total{tenantId, pluginId, pluginVersionId, componentId, reason}` – counts plugin triggers rejected due to component policy; each violation should correspond to a `script_event_audit` entry with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` indicating the blocked component/policy decision.

Plugin executions use the same metrics but typically add `pluginId` and `pluginVersionId` labels where relevant so dashboards and alerts can distinguish plugin behavior from core automation. Policy-specific behavior is surfaced via `automation_plugin_policy_violations_total` so operators can separate policy enforcement from quota or sandbox failures.

### Cross-Service Correlation

Script execution spans several services (Game Design, Game Session, Automation & Scripting, Game Logic, Logging & Admin). To support end-to-end debugging and replay, the system relies on a shared set of identifiers:

- `tenantId`, `gameInstanceId`, and `regionId` – identify the running game instance and region.
- `regionEpoch` – fences triggers and tick effects across scoped coordination resets.
- `entityId` – identifies the target entity for script-driven work.
- `scriptId` and `scriptPatchVersion` – identify the script definition and patch.
- `scriptEventId` – uniquely identifies a particular trigger from the caller’s perspective.
- `tickId` – identifies the authoritative game tick in which commands execute (paired with `regionEpoch`).
- `correlationId` – optional cross-service correlation token for Sagas and user-visible flows.

These identifiers appear consistently in:

- `script_event_audit` records in the Automation & Scripting Service.
- Tick logs and effect ledgers in the Game Session and Game Logic services.
- Logs and traces emitted by the Logging & Admin Service.

A typical troubleshooting flow for a problematic script or plugin is:

1. Start from a player-visible issue or a game tick log that includes `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, `entityId`, and `tickId`.
2. Use the tick log’s `scriptEventId` (or a derived `correlationId`) to locate matching entries in `script_event_audit` and in logs/traces. Do not rely on `scriptEventId` as a metric label; use metrics to understand aggregate rates by `scriptId`/`eventType`/`tenantId` and use audit/log queries for per-event correlation.
3. From those records, identify the responsible `scriptId`, `scriptPatchVersion`, and, where applicable, `pluginId`/`pluginVersionId`.
4. Cross-reference the associated publish or plugin enable/disable actions in Game Design and Logging & Admin using the same identifiers.

By consistently tagging metrics and audits with these identifiers, operators can follow a single script event across authoring, publishing, execution, and downstream effects without needing ad hoc joins or heuristics.

### Dry-Run Budgets & Limits

Dry-run and test executions share the same sandbox engine and guards as live traffic but are subject to their own **budgets and rate limits** so they cannot bypass safety mechanisms:

- Dry-runs do **not** consume ScriptQuotaService windows or per-tenant automation budgets that gate live triggers, but they:
  - Execute through the same sandbox, CPU, and memory budgets described in `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`.
  - Contribute to dry-run/test-only metrics (for example, `automation_script_test_runs_total`, `automation_script_test_runtime_seconds`, `automation_script_test_sandbox_failures_total`) so behavior is observable without affecting live-traffic dashboards and SLOs.
- Dry-run/test triggers must use a separate idempotency namespace from live traffic (for example include `isDryRun=true` in Trigger Identity) so test executions cannot dedupe, suppress, or overwrite live trigger handling/audit rows.
- By default, dry-run/test executions must not contribute to failure-rate circuit breakers that can disable live scripts (`runtimeStatus=DISABLED_DUE_TO_ERRORS`); if dry-run failures are used for gating, they must be isolated (separate breaker or explicit opt-in).
- To prevent abuse, the Automation & Scripting Service enforces additional dry-run ceilings, such as:
  - Per-tenant and per-principal maximum runs per window (for example, `SCRIPT_TEST_MAX_RUNS_PER_MINUTE`).
  - Maximum concurrent dry-runs per tenant or cluster-wide (for example, `SCRIPT_TEST_MAX_CONCURRENCY`).
- Dry-run activity is surfaced via dedicated metrics (for example, `automation_script_test_runs_total`, `automation_script_test_runtime_seconds`, `automation_script_test_sandbox_failures_total`) so operators can distinguish test traffic from live automation.
- Logging & Admin and Game Design tools are responsible for exposing dry-run entry points only to privileged users and for applying complementary API gateway limits; test endpoints must not be wired into game traffic or public-facing flows.
- When a dry-run request exceeds `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` or `SCRIPT_TEST_MAX_CONCURRENCY` ceilings, the Automation & Scripting Service rejects it with `finalOutcome=quota_denied` and `finalReason=dry_run_budget_exceeded` in `script_event_audit`, and increments `automation_script_test_runs_total` with a label (for example, `result="denied_quota"`) so operators can see overuse of test facilities.

### Outcome-to-Metric Mapping

This section is **illustrative**, not normative. The **authoritative definitions** for metric names, labels, and alerting behavior live in:

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

Implementations should align emitted metrics with those documents; the intent here is only to show how common outcomes map conceptually to “counted”, “skipped”, or “dropped” signals so readers understand the observability story.

At a high level:

- **Pre-admission quota and budgeting outcomes**
  - `finalStage=ADMISSION`, `finalOutcome=quota_denied` – trigger was rejected by `ScriptQuotaService` before sandbox execution; increments `script_quota_denied_total` and contributes to `automation_script_triggers_dropped_total{reason="quota"}` but does **not** increment sandbox failure metrics.
  - `finalStage=ADMISSION`, `finalOutcome=tenant_budget_exceeded` (or other budget-related outcomes) – trigger was not admitted because per-tenant or cluster budgets were exhausted; contributes to `automation_script_skips_total` or `automation_script_triggers_dropped_total` with appropriate `reason` tags but does not run the sandbox.

- **Sandbox-level failures**
  - `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error` – the DSL runtime rejected the run or hit a guard (for example, `finalReason=cpu_budget_exceeded`, `finalReason=memory_budget_exceeded`, `finalReason=iteration_budget_exceeded`); increments `automation_script_sandbox_failures_total{reason=...}` and `automation_script_errors_total{reason=...}` and feeds into failure-rate circuit breakers for live traffic (`isDryRun=false`).

- **Infrastructure-level failures**
  - `finalOutcome=infrastructure_error` (with a `finalStage` that reflects where it failed) – transport or infrastructure problems (for example, Redis timeouts, gRPC `UNAVAILABLE`); counted separately from sandbox errors, may trigger retries at lower layers using idempotency keys, and contribute to infra-focused alerts.

The failure-rate circuit breaker primarily considers **sandbox_error** and other logical script failures when deciding to transition a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`. Quota denials and purely infrastructure-level errors do not, by themselves, trigger disables, although they should still be visible in metrics and dashboards.

---

## Operational Cookbook: Quotas, Budgets, and Metrics

Use the following patterns to answer common operational questions:

- **“Which scripts are being hard-dropped by per-script quotas or queues?”**
  - Look at `automation_script_triggers_dropped_total{reason="quota"}` for per-script window drops and `automation_script_triggers_dropped_total{reason="concurrency"}` / `automation_script_triggers_dropped_total{reason="concurrency_policy_drop_new"}` for drops caused by concurrency/queue limits.
  - Pair with `script_quota_denied_total` and audit rows with `finalStage=ADMISSION` and `finalOutcome=quota_denied` (or other quota/concurrency outcomes).

- **“Is a tenant being throttled by its own automation budget?”**
  - Check `automation_script_skips_total{reason="tenant_budget_exceeded", tenantId=...}` and audit rows with `finalStage=ADMISSION` and `finalOutcome=tenant_budget_exceeded`.
  - Use `automation_script_tenant_budget_seconds{tenantId, tier}` to see which tiers are consuming budget.

- **“Are cluster-wide ceilings causing drops?”**
  - Monitor `automation_script_triggers_dropped_total{reason="cluster_limit_reached"}` alongside `automation_tick_events_enqueued_total` and infrastructure-level CPU/time metrics. This combination indicates pressure at the cluster layer rather than within a single script or tenant.

- **“Are lower-priority scripts being throttled in favor of higher-priority ones?”**
  - Use `automation_script_skips_total{reason="priority_throttled"}` and compare `automation_script_triggers_total` broken out by `priorityTag` to confirm that background work is yielding capacity to high-priority scripts as configured.

- **“Are reloads or version issues causing skips?”**
  - Inspect `automation_script_triggers_total{outcome="skipped_reloading"}`, `automation_script_triggers_total{outcome="skipped_rollback_pause"}`, and `automation_script_triggers_dropped_total{reason="version_unavailable"}` (paired with `script_event_audit.finalStage=ADMISSION` and `finalOutcome=skipped_reloading` / `skipped_rollback_pause` / `version_unavailable`) to distinguish reload pauses, rollback pauses, and missing or failed script versions.
  - For stale control-plane pin visibility, inspect admissions with `finalOutcome=pin_state_unavailable` and corresponding drop metrics keyed by the bounded `finalReason`.

### Tuning Playbook: Misbehaving Scripts

When a script or tenant consumes too many resources, adjust settings in this order:

1. **Per-script cadence and concurrency** – Start with the script’s own knobs in [Per-Script Scheduling Policies](#per-script-scheduling-policies): increase `intervalTicks`, reduce `maxConcurrent`, or switch `concurrencyPolicy` from `queue_until_free` to `drop_new` so the script enqueues less often and runs fewer overlapping instances.
2. **Per-script quota window** – If the script still runs too frequently, tighten `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOWSECONDS` for that script (see [Fairness & Abuse Prevention](#fairness--abuse-prevention) and [Quota & Budget Summary](#quota--budget-summary)) so abusive patterns are capped before they hit the tick queues.
3. **Per-tenant tier budgets** – When one tenant’s background work threatens others, adjust that tenant’s budgets per tier (for example, reduce `background` capacity) using the controls described under [Resource Isolation and Multi-Level Budgets](#resource-isolation-and-multi-level-budgets), watching `automation_script_skips_total{reason="tenant_budget_exceeded"}`.
4. **Cluster-wide ceilings and capacity** – Only after tuning the above should you raise or lower global ceilings such as `AUTOMATION_TICK_MAX_EVENTS` or cluster CPU budgets. Use the metrics in [Quota & Budget Summary](#quota--budget-summary) and [Auditability & Metrics](#auditability--metrics) to confirm whether you are cluster-bound or script/tenant-bound.

### Worked Example: Noisy Background Script vs High-Priority Script

Consider two tenants sharing the same Automation & Scripting cluster:

- **Tenant A** – runs a noisy background script `npc-logger` tagged `priorityTag=background` that logs non-critical events frequently.
- **Tenant B** – runs a high-priority script `boss-ai` tagged `priorityTag=high` that drives a raid boss encounter.

Assumptions:

- Per-script quotas allow both scripts to run a reasonable number of times per window under normal conditions.
- Tenant budgets are configured so each tenant has its own automation budget per priority tier.
- Cluster-level ceilings cap total automation work per second across all tenants.

Under light load:

- `npc-logger` and `boss-ai` both operate within their per-script quotas.
- Tenant A and Tenant B remain within their per-tenant budgets.
- Cluster ceilings are not reached; both scripts run as expected.

Under heavy load from Tenant A:

1. **Per-script quota layer**: `npc-logger` may hit its per-script quota first; additional triggers for that script in the current window are skipped with `automation_script_triggers_dropped_total{reason="quota"}` and audit entries with `finalStage=ADMISSION` and `finalOutcome=quota_denied`. `boss-ai` remains within its own per-script quota and continues to run when triggered.

2. **Per-tenant budget layer**: If Tenant A continues to generate background triggers, it may exhaust its tenant-level budget for the `background` tier. Once Tenant A’s background budget is exceeded, further background triggers for Tenant A (including `npc-logger`) are throttled or skipped and `automation_script_skips_total{reason="tenant_budget_exceeded", tenantId="A"}` increases. Tenant B’s budgets are independent; its `high`-priority `boss-ai` script is unaffected as long as Tenant B stays within its own budgets.

3. **Cluster-level ceilings**: If total automation work across all tenants (including other games) approaches the cluster ceiling, the scheduler continues to admit `high`-priority scripts like `boss-ai` as long as possible and preferentially defers or drops `background` work such as `npc-logger`, reflected in `automation_script_triggers_dropped_total` with reasons like `cluster_limit_reached`.

This example illustrates how the layers interact:

- Per-script quotas prevent any single script from running unboundedly.
- Per-tenant budgets prevent one tenant’s background scripts from starving another tenant’s automation.
- Cluster ceilings ensure the entire cluster remains healthy under extreme load, favoring high-priority, latency-sensitive scripts when trade-offs are required.

---

## Operational Disable / Throttle Flows

Operators can disable or throttle scripts to respond to failures or abuse:

- **Disable now (hard stop)**:
  - Mark a script as disabled via the Game Design or Logging & Admin tools.
  - The Automation & Scripting Service flips `runtimeStatus=DISABLED` in script metadata.
  - The scheduler stops accepting **new triggers** for that script immediately (recording `script_event_audit.finalStage=ADMISSION`, `finalOutcome=skipped_disabled`, and a suitable `finalReason`, such as `admin_hard_disable`), but does not preempt in-flight runs; they are allowed to complete under existing quotas.

- **Soft-disable after current run**:
  - For scripts that should drain gracefully, administrators can set `runtimeStatus=DISABLE_AFTER_DRAIN`.
  - The scheduler continues to run any currently queued triggers up to a small grace window, then transitions the script to `DISABLED` once its active and queued counts reach zero.
  - Subsequent triggers are skipped and logged with `finalStage=ADMISSION`, `finalOutcome=skipped_disabled`, and a `finalReason` that reflects the drain behavior.

- **Throttling**:
  - Throttling is modeled as a temporary adjustment of per-script and per-tenant budgets rather than a separate toggle.
  - Operators can reduce `SCRIPT_QUOTA_LIMIT`, increase `intervalTicks`, or change `priorityTag` to `background`; the scheduler immediately applies the new configuration when evaluating triggers.
  - In addition, the failure-rate circuit breaker may place a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, which behaves like a hard disable until an administrator explicitly clears the status; these transitions are captured in `script_event_audit` using canonical `finalOutcome` values (for example `disabled_due_to_errors`, `tenant_budget_exceeded`) paired with specific `finalReason` strings.

All disable/enable and throttle actions are **idempotent** and recorded with the acting principal (where available) via the `actorPrincipal` field, so operators can trace when and why a script stopped executing.

---

## Rollback & Recovery Scenarios

This section summarizes common failure and rollback scenarios and how operators should respond. It complements the per-feature lifecycle details in the DSL reference and modding framework documents.

- **Script patch `onLoad` failures or patch status `FAILED`**
  - Symptoms:
    - For a given `<tenantId, scriptPatchVersion>`, audit entries with `eventType=onLoad` and `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error` (or other logical failures) so you can distinguish `onLoad` evaluation failures from downstream persistence/handoff problems.
    - Triggers referencing that patch produce `finalStage=ADMISSION`, `finalOutcome=version_unavailable` (or a more specific variant) and drop metrics such as `automation_script_triggers_dropped_total{reason="version_unavailable"}`.
  - Behavior:
    - The Automation & Scripting Service marks the patch `FAILED` for that tenant; the previous `activePatchVersion` remains in use.
    - No automatic rollback beyond “keep the last known good patch active” occurs.
  - Operator actions:
    - Use Game Design tooling to inspect and fix the script configuration, then publish a new patch.
    - Optionally disable the faulty script entirely (`runtimeStatus=DISABLED`) to stop further admission attempts while iterating.

- **Plugin version failures or misbehavior**
  - Symptoms:
    - Plugin lifecycle state in the Automation & Scripting Service shows `pluginState=FAILED` or frequent `sandbox_error` outcomes for `pluginId` / `pluginVersionId`.
    - Audit entries in `script_event_audit` tagged with `pluginId` / `pluginVersionId` show repeated failures, and plugin-specific metrics spike.
  - Behavior:
    - The modding framework keeps `activeVersionId` unchanged when a new plugin version fails validation or initialization; triggers for the failed version are rejected.
  - Operator actions:
    - Use Logging & Admin APIs to set the plugin to a disabled or drain state for affected tenants.
    - Roll back to a previous `pluginVersionId` by promoting it to `activeVersionId` if still trusted, or require a new signed bundle upload via the Game Design Service.

- **Heavy timer drops or throttled `onInterval` handlers**
  - Symptoms:
    - High counts for `automation_script_triggers_dropped_total{reason="tenant_budget_exceeded"}` or `reason="cluster_limit_reached"` for `eventType=onInterval`.
    - Audit entries for `onInterval` with `finalStage=ADMISSION` and `finalOutcome=quota_denied`, `tenant_budget_exceeded`, or `version_unavailable`.
  - Behavior:
    - Timer-based triggers are at-most-once per scheduled firing; dropped or skipped intervals are not replayed, although future firings may still occur.
  - Operator actions:
    - Reduce cadence (increase `intervalTicks`) or lower priority for noisy timers.
    - Adjust per-tenant budgets or cluster ceilings if drops reflect legitimate load rather than misbehaving scripts.
    - For persistent version-related drops, investigate patch status and either fix and republish or explicitly disable the affected scripts.

In all of these cases, `script_event_audit` is the primary source of truth for what happened to individual triggers, and metrics from this document’s glossary indicate whether the problem is localized to a script/plugin, a tenant budget, or cluster capacity.

---

## Environment Variables

The **authoritative, up-to-date list of environment variables and defaults** lives in the Automation & Scripting Service README (`design/architecture/microservices/automation-scripting-service/README.md#environment-variables`). This section only calls out conceptual categories so it remains stable as new settings are added:

- **Quota knobs** – control per-script and per-tenant quota windows and budgets used by `ScriptQuotaService` and the multi-level budgeting model (for example, limits on how many triggers a script or tenant may execute per window).
- **Tick batch knobs** – bound how much automation work `ScriptTickService` performs per automation tick, including batch sizes, per-tick budgets, and cluster-wide ceilings on automation events.
- **Timer and scheduling knobs** – influence `onInterval` / `onTimerExpire` behavior, including cadence, maximum timers per tenant or region, and any backoff or delay settings applied when regions are degraded.
- **Audit and retention knobs** – govern how long `script_event_audit` and related records remain available for troubleshooting, and how large those tables are allowed to grow before automated cleanup; retention is typically controlled via `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS`, with exact defaults and semantics documented in the Automation & Scripting Service README.

For the exact variable names, defaults, and any future additions, always refer to the Automation & Scripting Service README rather than this document.

---

## Developer Tools

Several helper scripts streamline common tasks when working with scripting and automation flows:

- `dev-tools/firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `dev-tools/docs/generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `dev-tools/docs/generate-grpc-docs.sh` – generates Markdown documentation from protobuf definitions.
- `dev-tools/seed/seed-automation-scripting-data.sh` – populates the Automation & Scripting Service with sample scripts, actions, and quotas so you can observe scheduler behavior without manual editing.

These scripts complement the web-based editor and allow creators and operators to automate routine actions when testing or observing the scripting system.

---

## Rollback & Recovery Cookbook

This section summarizes common rollback and recovery scenarios for scripting and automation. It complements the broader backup and recovery guidance in `design/architecture/system-architecture-backup-recovery.md` and the versioning rules in `design/architecture/system-architecture-versioning-runtime.md`.

### Rollback Protocol (Required)

Rollback must prevent previously queued work from a rolled-back `scriptPatchVersion` from continuing to affect gameplay.

At a minimum, rollback consists of:

1. **Fence new evaluation**
   - Pause tick execution and set Automation & Scripting admission to rollback pause mode for the affected scope before repin, so new triggers do not refill queues during cleanup.
   - Repin the affected game instance(s) to the target `scriptPatchVersion` using Game Session / Logging & Admin control-plane APIs.
   - Ensure Automation & Scripting rejects triggers for non-`READY` patches and records explicit outcomes (for example `version_unavailable`) rather than silently falling back.
2. **Drain/purge queued automation work**
   - Drain or purge queued script work items and staging entries that carry the rolled-back patch so they cannot enqueue into tick queues after repin.
   - If plugin versions are also being rolled back/disabled/revoked, cancel pending work for those `pluginVersionId` values before queue purge.
   - Any purge must be scoped and auditable (tenant/region/script as appropriate) and must not require ad-hoc `redis-cli` deletes.
3. **Enforce execution-time version fencing**
   - Game Session must reject any queued tick commands whose embedded `scriptPatchVersion` does not match the instance’s currently pinned version, and record the rejection so operators can diagnose rollback impact.
4. **Resume in order**
   - Return Automation & Scripting admission to normal only after cancel/purge completes, then resume ticks.

These requirements are summarized in `design/architecture/system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety`.

### Misbehaving Script Patch After Activation

Symptoms:

- A script patch has already been marked `READY` for a tenant and pinned as the active `scriptPatchVersion`, but automation metrics and `script_event_audit` show sustained `sandbox_error` or `infrastructure_error` outcomes for one or more scripts.
- Players or operators report regressions that correlate with the newly active patch (for example, NPCs stuck in loops, missing timers, or over-aggressive automation).

Behavior:

- The Automation & Scripting Service continues to enforce quotas, budgets, and failure-rate circuit breakers for individual scripts; misbehaving handlers may be transitioned to `runtimeStatus=DISABLED_DUE_TO_ERRORS`.
- Timer and event triggers remain **at-most-once per firing**; skipped or failed triggers are not automatically replayed even if the patch is later rolled back.

Operator actions:

1. Identify the affected scripts and patch
   - Use `script_event_audit` filtered by `tenantId`, `scriptPatchVersion`, and `scriptId` to confirm which handlers are failing.
   - Correlate with automation metrics such as `automation_script_sandbox_failures_total`, `automation_script_errors_total`, and `automation_script_triggers_dropped_total` to determine scope and severity.
2. Contain impact at the script level
   - Use the normal disable/throttle flows in this document to set offending scripts to `runtimeStatus=DISABLED` or a drain state while you triage (for example, `DISABLE_AFTER_DRAIN`).
3. Roll back the active script patch if necessary
   - If regressions are widespread or difficult to isolate, use Logging & Admin or Game Session tooling to repin the game back to the previous known-good `scriptPatchVersion` for the affected tenant and game instance. Concretely:
     - Query the Automation & Scripting Service via read-only APIs such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` and `GetScriptPatchInstanceRolloutStatus(...)` (or consume `ScriptPatchTenantStatusChanged` / `ScriptPatchInstanceRolloutChanged` events) to confirm tenant readiness and instance rollout state.
     - Call the Game Session control-plane APIs to update the pin (for example `SetPinnedScriptPatchVersion` or `RollbackScriptPatchVersion`) following the contract in `design/architecture/system-architecture-scripting-control-plane-api.md`.
   - Repinning does **not** attempt to backfill skipped triggers or rewrite existing automation queues; automation and tick processing continue from the current point in time under the older patch, and at-most-once guarantees for past triggers are preserved.
   - Repinning must also ensure rollback safety:
     - Automation admission should remain paused for the affected scope while repin and cancel/purge steps run.
     - Queued automation work items and staging entries that carry the rolled-back `scriptPatchVersion` are drained/purged so they cannot enqueue or execute after repin.
     - If plugin versions are also being rolled back/disabled/revoked, pending work for displaced `pluginVersionId` values is canceled before queue purge.
     - Game Session enforces a version fence at execution time and must reject any tick-queue entries whose embedded `scriptPatchVersion` does not match the currently pinned value.
     - These drops must be visible in `script_event_audit` and operator dashboards so rollback impact is diagnosable.
4. Repair and republish
   - Fix the underlying script configuration in the Game Design Service and publish a new script-only patch version.
   - Verify that the new patch reaches `patchStatus=READY` for the tenant and that `onLoad` initialization succeeds before promoting it to the active `scriptPatchVersion` again.
