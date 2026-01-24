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

This alignment ensures that plugin code cannot bypass or weaken the resource-isolation guarantees of the scripting system; operational tooling and metrics apply uniformly to both plugins and regular scripts.

### Per-Script Scheduling Policies

Per-script scheduling knobs control how often scripts are allowed to run and how they behave under load:

- **`intervalTicks` (cadence)** – defines the target cadence for scheduler-driven events (for example, “every N ticks”). The scheduler uses tick heartbeats and timer indexes to decide when an `onInterval` or timer-based handler becomes due, keeping script cadence aligned with the canonical `tickId`.
- **`concurrencyPolicy` and `maxConcurrent`** – govern what happens when new triggers arrive while runs are already in progress:
  - `concurrencyPolicy=queue_until_free` keeps a short queue of pending triggers up to configured limits and runs them once existing executions complete.
  - `concurrencyPolicy=drop_new` skips new triggers while the script is already running, favoring bounded concurrency over backlog growth.
  - Queued triggers still count toward the script’s quota window; once quota limits are exceeded, additional triggers are dropped with audit outcomes such as `dropped_quota` and matching metrics.
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
| **Per-script cadence & concurrency** | Per script | `intervalTicks`, `concurrencyPolicy` (`drop_new` / `queue_until_free`), `maxConcurrent`. Stored in script metadata and used by the scheduler when deciding which triggers to admit. | Queue delay and drop metrics plus audit `outcome` / `reason` values such as `dropped_quota`. |
| **Per-script priority** | Per script | `priorityTag` (`high`, `normal`, `background`) and per-tier enqueue budgets (for example, `high=8/min`, `normal=4/min`, `background=2/min`). | Tiered trigger/skip metrics that show how often high/normal/background work is admitted or throttled. |
| **Per-tenant tier budgets** | Per tenant and priority tier | Tenant-scoped automation budgets per tier, tracked as aggregates such as `automation_script_tenant_budget_seconds{tenantId, tier}`. | Budget consumption and skip metrics per tenant/tier, plus matching audit outcomes such as `tenant_budget_exceeded`. |
| **Cluster-wide safety limits** | Entire Automation & Scripting cluster | Global ceilings on automation work, including `AUTOMATION_TICK_MAX_EVENTS` and cluster-level CPU/time budgets. | Cluster-level throughput and drop metrics that indicate when global ceilings are hit. |

---

## Auditability & Metrics

Every scheduler decision emits an audit record stored in a lightweight `script_event_audit` table in PostgreSQL. `scriptEventId` uniquely identifies the trigger instance so retries, replays, and downstream side effects can be correlated across logs, metrics, and traces.

The canonical `script_event_audit` schema includes:

- **Core identifiers**
  - `scriptEventId` – unique identifier for a single trigger/run.
  - `tenantId` – tenant/game owning the script.
  - `regionId` – region (where applicable) associated with the trigger.
  - `scriptId` – script definition that handled the trigger.
  - `eventType` – logical event key (for example, `onEnterRegion`, `onInterval`, `inventory.item_added`).
  - `versionId` – effective script version or `scriptPatchVersion` applied at runtime.
  - `tickId` – canonical tick identifier associated with the trigger.

- **Outcome and reason**
  - `outcome` – canonical classification such as `success`, `quota_denied`, `sandbox_error`, `disabled_due_to_errors`, `skipped_disabled`, `skipped_reloading`, `dropped_quota`, `tenant_budget_exceeded`, `version_unavailable`, or `infrastructure_error`.
  - `reason` – more detailed, free-form reason string for diagnosis (for example, `iteration_budget_exceeded`, `admin_hard_disable`, `tenant_budget_exceeded_background`, `cluster_limit_reached`).

- **Operational details**
  - Timestamps and duration fields.
  - Optional actor principal for administrative actions (disable/enable/throttle).

Retention and sizing are governed by environment variables described below and in the Automation & Scripting Service README; in particular, `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS` control how long audit rows are retained and how large the table is allowed to grow (current defaults are 30 days and 1,000,000 rows, but the README remains the authoritative source).

Metrics such as:

- `automation_script_triggers_total`
- `automation_script_skips_total`
- `automation_script_triggers_dropped_total`
- `script_quota_allowed_total`
- `script_quota_denied_total`
- `automation_tick_events_enqueued_total`

are updated throughout the scripting pipeline so operators can monitor how often scripts fire, how many are skipped by policy, and how much automation work is being handed to the tick system. See `design/architecture/system-architecture-logging-monitoring.md` for broader metrics and alerting guidance.

Additional queue-health metrics help detect automation backlogs that are not draining into ticks as expected:

- `automation_queue_orphaned_entries_total` – counts work items that have remained in `automation:queue:<tenantId>:<entityId>` beyond a bounded age window (for example, N ticks or seconds) without corresponding staging in `automation:tick:{tenantScriptTag}:...` or entries in the tick effect ledger.
- `automation_queue_oldest_entry_age_seconds` – records the age of the oldest sampled queue item per tenant/script so operators can see when automation queues are falling behind.

A small, bounded inspector loop in `ScriptTickService` periodically samples a subset of queues to update these metrics; it does not attempt to repair or delete items itself, but surfaces misalignment between automation and tick processing for investigation.

### Cross-Service Correlation

Script execution spans several services (Game Design, Game Session, Automation & Scripting, Game Logic, Logging & Admin). To support end-to-end debugging and replay, the system relies on a shared set of identifiers:

- `tenantId` and `regionId` – identify the game and region.
- `entityId` – identifies the target entity for script-driven work.
- `scriptId` and `scriptPatchVersion` – identify the script definition and patch.
- `scriptEventId` – uniquely identifies a particular trigger from the caller’s perspective.
- `tickId` – identifies the authoritative game tick in which commands execute.
- `correlationId` – optional cross-service correlation token for Sagas and user-visible flows.

These identifiers appear consistently in:

- `script_event_audit` records in the Automation & Scripting Service.
- Tick logs and effect ledgers in the Game Session and Game Logic services.
- Logs and traces emitted by the Logging & Admin Service.

A typical troubleshooting flow for a problematic script or plugin is:

1. Start from a player-visible issue or a game tick log that includes `tenantId`, `regionId`, `entityId`, and `tickId`.
2. Use the tick log’s `scriptEventId` (or a derived `correlationId`) to locate matching entries in `script_event_audit` and automation metrics (for example, `automation_script_triggers_total` and `automation_script_triggers_dropped_total`).
3. From those records, identify the responsible `scriptId`, `scriptPatchVersion`, and, where applicable, `pluginId`/`pluginVersionId`.
4. Cross-reference the associated publish or plugin enable/disable actions in Game Design and Logging & Admin using the same identifiers.

By consistently tagging metrics and audits with these identifiers, operators can follow a single script event across authoring, publishing, execution, and downstream effects without needing ad hoc joins or heuristics.

### Outcome-to-Metric Mapping

This section is **illustrative**, not normative. The **authoritative definitions** for metric names, labels, and alerting behavior live in:

- `design/architecture/system-architecture-logging-monitoring.md`
- Automation & Scripting Service README: `design/architecture/microservices/automation-scripting-service/README.md`

Implementations should align emitted metrics with those documents; the intent here is only to show how common outcomes map conceptually to “counted”, “skipped”, or “dropped” signals so readers understand the observability story.

---

## Operational Cookbook: Quotas, Budgets, and Metrics

Use the following patterns to answer common operational questions:

- **“Which scripts are being hard-dropped by per-script quotas or queues?”**
  - Look at `automation_script_triggers_dropped_total{reason="quota"}` for per-script window drops and `automation_script_triggers_dropped_total{reason="concurrency"}` / `automation_script_triggers_dropped_total{reason="concurrency_policy_drop_new"}` for drops caused by concurrency/queue limits.
  - Pair with `script_quota_denied_total` and audit rows with `outcome=quota_denied` / `dropped_quota`.

- **“Is a tenant being throttled by its own automation budget?”**
  - Check `automation_script_skips_total{reason="tenant_budget_exceeded", tenantId=...}` and audit rows with `outcome=tenant_budget_exceeded`.
  - Use `automation_script_tenant_budget_seconds{tenantId, tier}` to see which tiers are consuming budget.

- **“Are cluster-wide ceilings causing drops?”**
  - Monitor `automation_script_triggers_dropped_total{reason="cluster_limit_reached"}` alongside `automation_tick_events_enqueued_total` and infrastructure-level CPU/time metrics. This combination indicates pressure at the cluster layer rather than within a single script or tenant.

- **“Are lower-priority scripts being throttled in favor of higher-priority ones?”**
  - Use `automation_script_skips_total{reason="priority_throttled"}` and compare `automation_script_triggers_total` broken out by `priorityTag` to confirm that background work is yielding capacity to high-priority scripts as configured.

- **“Are reloads or version issues causing skips?”**
  - Inspect `automation_script_triggers_total{outcome="skipped_reloading"}` and `automation_script_triggers_dropped_total{reason="version_unavailable"}` (paired with audit outcomes `version_unavailable` / `skipped_version_unavailable`) to distinguish reload pauses from missing or failed script versions.

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

1. **Per-script quota layer**
   - `npc-logger` may hit its per-script quota first; additional triggers for that script in the current window are skipped with `automation_script_triggers_dropped_total{reason="quota"}` and audit outcomes such as `quota_denied`.
   - `boss-ai` remains within its own per-script quota and continues to run when triggered.

2. **Per-tenant budget layer**
   - If Tenant A continues to generate background triggers, it may exhaust its **tenant-level budget** for the `background` tier.
   - Once Tenant A’s background budget is exceeded:
     - Further background triggers for Tenant A (including `npc-logger`) are throttled or skipped.
     - `automation_script_skips_total{reason="tenant_budget_exceeded", tenantId="A"}` increases.
   - Tenant B’s budgets are independent; its `high`-priority `boss-ai` script is unaffected as long as Tenant B stays within its own budgets.

3. **Cluster-level ceilings**
   - If total automation work across all tenants (including other games) approaches the cluster ceiling, the scheduler:
     - Continues to admit `high`-priority scripts like `boss-ai` as long as possible.
     - Preferentially defers or drops `background` work such as `npc-logger`, reflected in `automation_script_triggers_dropped_total` with reasons like `cluster_limit_reached`.

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
  - The scheduler stops accepting **new triggers** for that script immediately (recording `outcome=skipped_disabled` and a suitable `reason`, such as `admin_hard_disable`, in `script_event_audit`), but does not preempt in-flight runs; they are allowed to complete under existing quotas.

- **Soft-disable after current run**:
  - For scripts that should drain gracefully, administrators can set `runtimeStatus=DISABLE_AFTER_DRAIN`.
  - The scheduler continues to run any currently queued triggers up to a small grace window, then transitions the script to `DISABLED` once its active and queued counts reach zero.
  - Subsequent triggers are skipped and logged with `outcome=skipped_disabled` and a reason that reflects the drain behavior.

- **Throttling**:
  - Throttling is modeled as a temporary adjustment of per-script and per-tenant budgets rather than a separate toggle.
  - Operators can reduce `SCRIPT_QUOTA_LIMIT`, increase `intervalTicks`, or change `priorityTag` to `background`; the scheduler immediately applies the new configuration when evaluating triggers.
  - In addition, the failure-rate circuit breaker may place a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, which behaves like a hard disable until an administrator explicitly clears the status; these transitions are captured in `script_event_audit` using canonical outcomes (`disabled_due_to_errors`, `tenant_budget_exceeded`, etc.) paired with specific `reason` strings.

All disable/enable and throttle actions are **idempotent** and recorded with the acting principal (where available) via the `actorPrincipal` field, so operators can trace when and why a script stopped executing.

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
