# FireMUD Scripting Quotas & Operations

This document focuses on **sandboxing, quotas, budgets, and operational workflows** for the scripting and automation framework.

It is intended as the main reference for operators, SREs, and platform engineers responsible for safe multi-tenant operation of scripts.

Routing note:

- Use this document for quota policy, enforcement, and runtime/operator controls.
- Use `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` for DSL/lifecycle semantics.
- Use `design/architecture/system-architecture-scripting-runtime-execution.md` for execution-state behavior.

Companion docs:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` – terminology, DSL semantics, event lifecycle, determinism.
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
- [Related Operations Cookbook](#related-operations-cookbook)
- [Environment Variables](#environment-variables)
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

For **core scripts**, `UNSAFE` is a publish/readiness classification, not a live runtime policy rollout:

- New publishes or readiness transitions that reference an `UNSAFE` component must fail deterministically with `validation_error` / `unsafe_component`.
- Already-`READY` or already-pinned patches do not become implicitly disabled just because a component was reclassified later.
- Immediate containment of a live script that uses a newly `UNSAFE` component is an operator action through the existing disable/rollback controls, not a separate implicit admission policy.

For lower-level sandbox and runtime internals, see `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`.

Dry-run and test execution paths exposed by the Automation & Scripting Service share the same sandbox and guardrails as live traffic:

- They execute handlers through the same engine with the same CPU/iteration and memory budgets.
- They record `sandbox_error` and `infrastructure_error` outcomes in `script_event_audit` so failure modes are observable.
- By default they do **not** consume per-script quotas or per-tenant budgets enforced by `ScriptQuotaService`; instead, they are restricted to privileged principals (for example, designers and operators) and should be further protected by separate rate limits or ACLs at the API gateway or Logging & Admin layer.
  - Dry-run/test executions must not increment live-traffic error counters. Sandbox failures observed during tests are emitted via dry-run/test-only metric families (for example `automation_script_test_sandbox_failures_total`) so production SLO dashboards do not conflate privileged tooling with live automation reliability.
- Dry-run/test traffic must not be allowed to consume the same last-resort execution capacity reserved for live automation:
  - Implementations should use separate executor pools or explicit worker reservations for `isDryRun=true`.
  - When the cluster is under pressure, live traffic must be admitted ahead of dry-run/test traffic even if dry-run quotas have not been exceeded.
  - Queue limits and concurrency ceilings for dry-run/test work must be enforced independently from live execution queues.

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

### `onLoad` Initialization Capacity

Patch readiness initialization uses a separate admission class from ordinary live triggers:

- `onLoad` is part of the publish/readiness lifecycle for `<tenantId, scriptPatchVersion>`, not part of steady-state gameplay traffic.
- `onLoad` must **not** consume ordinary live-trigger quota windows or compete indefinitely in the same admission queues as `onEnterRegion`, `onInterval`, or other runtime events.
- In the first implementation slice, `onLoad` capacity exists only for **ephemeral readiness work**. Durable or semi-durable artifact creation is not part of the `onLoad` contract and must be rejected at design/runtime review until a dedicated cleanup lifecycle exists.
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

Quota and budget policy must be applied at fixed charge points so operators can predict what a burst will cost and retries cannot distort usage:

- **Per-script quota windows**
  - Charged once per resolved handler-scoped Trigger Identity at handler admission time.
  - Handlers admitted into a bounded `queue_until_free` backlog consume quota immediately and are not re-charged when they later start.
  - Duplicate deliveries of the same handler-scoped Trigger Identity must not consume additional quota.
- **Per-tenant tier budgets**
  - Charged when a handler-scoped run is reserved onto live sandbox execution capacity.
  - Event-scope ingress acceptance alone does not charge tenant runtime budget.
  - Mixed fan-out therefore consumes tenant runtime budget only for handlers that actually leave admission and reserve execution capacity.
- **Cluster-wide execution ceilings**
  - Applied at the same execution-reservation point as tenant runtime budgets.
  - Admission rejections due purely to cluster exhaustion must remain `ADMISSION` outcomes and must not burn sandbox CPU/memory budget.
- **Output-budget and post-admission failures**
  - Output-budget failures, sandbox errors, rollback-epoch cancellations after admission, and downstream infrastructure failures do not refund quota or execution budget that has already been charged.
  - These runs still consumed or reserved scarce runtime capacity and must remain visible as charged non-success outcomes.
- **`onLoad`**
  - Uses its own publish-time capacity class and is excluded from the live per-script quota window and tenant runtime budget accounting above.

Concrete mixed fan-out accounting example:

- One inbound `TriggerScriptEvent` for `onEnterRegion` is admitted at event scope and resolves to three handler-scoped Trigger Identities: `S1`, `S2`, and `S3`.
- `S1` is rejected immediately with `finalStage=ADMISSION`, `finalOutcome=quota_denied`. It consumes no tenant runtime execution budget and no sandbox CPU/memory budget.
- `S2` is accepted under `concurrencyPolicy=queue_until_free`. It consumes one per-script quota slot immediately when queued, but it does not consume tenant runtime execution budget until it later reserves sandbox capacity and starts running.
- `S3` is admitted directly to execution. It consumes one per-script quota slot at handler admission and consumes tenant runtime execution budget when it reserves sandbox capacity.
- If `S2` later reaches execution and fails with `sandbox_error`, or `S3` later fails with `work_item_size_exceeded`, the already-charged quota/execution budget is not refunded.

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

Per-trigger output is also part of the quota model even when the run itself was admitted successfully:

- Each admitted script/plugin run must be constrained by explicit output ceilings such as `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes`.
- Output-budget failures must be surfaced as stage-aware non-success outcomes and must not be treated as successful handoff merely because the DSL graph began evaluating.
- Game Design validation should reject graphs whose conservatively bounded worst-case fan-out cannot fit within those runtime ceilings.

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
  - `sourceService` – producing service identity for custom/service-specific events so operators can diagnose routing and authorization problems.
  - `tickId` – canonical tick identifier associated with the trigger when the trigger is tick-aligned or once commands are accepted into the tick system.

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
At the metric layer, these rows should contribute to the same bounded rollback/drain visibility used for the paused scope rather than disappearing into generic infrastructure noise. Use `automation_rollback_drain_canceled_total{tenantId, gameInstanceId, finalStage, reason}` as defined in the canonical observability contract so operators can confirm that draining work was fenced intentionally rather than lost unexpectedly.

`script_event_audit` remains the authoritative record for Automation-owned stages through `TICK_HANDOFF`, but post-handoff execution-time version fences must also be correlated back to the same trigger:

- If Game Session later drops a handed-off command because its embedded `scriptPatchVersion` or plugin version no longer matches the instance's active pin, operator tooling must be able to locate that drop directly from the originating Trigger Identity.
- The canonical mechanism is the supplementary execution-disposition contract in `design/architecture/system-architecture-scripting-observability-contract.md`: Game Session reports a bounded post-handoff disposition keyed by Trigger Identity rather than forcing operators to infer the relationship from metrics alone.
- Dashboards and incident tooling should therefore show both:
  - Automation pipeline completion (`finalStage`, `finalOutcome`) and
  - any later execution-time fence rejection (`executionDisposition.outcome=version_fence_dropped`, bounded reason).

Concrete rollback-visibility example:

- Trigger Identity `T123` reaches `finalStage=TICK_HANDOFF`, `finalOutcome=success` after Automation & Scripting hands off its commands to Game Session.
- Before the queued command executes, operators roll the instance back to an older `scriptPatchVersion`.
- Game Session rejects the queued command on its execution-time version fence and publishes `executionDisposition={ outcome=version_fence_dropped, reason=script_patch_mismatch, sourceService=game-session }` for Trigger Identity `T123`.
- Operator tooling for `T123` must therefore show both the successful Automation pipeline result and the later execution-time fence drop, rather than overwriting one with the other.

Retention and sizing are governed by environment variables described below and in the Automation & Scripting Service README; in particular, `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS` control how long audit rows are retained and how large the table is allowed to grow (current defaults are 30 days and 1,000,000 rows, but the README remains the authoritative source).
Dead-letter stores used for rejected queue entries or non-progressing outbox work must also define explicit `maxAge`, `maxRows`, cleanup cadence, and alert thresholds; unbounded dead-letter growth is not an acceptable operational mode. These controls should be exposed as operator knobs (for example, `SCRIPT_DEAD_LETTER_MAX_ROWS`, `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS`, `SCRIPT_DEAD_LETTER_CLEANUP_INTERVAL_SECONDS`, `SCRIPT_DEAD_LETTER_ALERT_THRESHOLD_ROWS`) rather than implicit defaults.

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

- `automation_queue_orphaned_entries_total` – counts work items that have remained in `automation:queue:{tenantInstanceTag}:<entityId>` beyond a bounded age window (for example, N ticks or seconds) without corresponding staging in `automation:tick:{tenantInstanceScriptTag}:...` or entries in the tick effect ledger.
- `automation_queue_oldest_entry_age_seconds` – records the age of the oldest sampled queue item per tenant/script so operators can see when automation queues are falling behind.

A small, bounded inspector loop in `ScriptTickService` periodically samples a subset of queues to update these metrics; it does not attempt to repair or delete items itself, but surfaces misalignment between automation and tick processing for investigation.

For scripting and automation, these metrics follow shared naming and labeling conventions so dashboards and alerts remain consistent across services:

- `automation_script_triggers_total{tenantId, scriptId, pluginId, pluginVersionId, eventType, outcome, priorityTag}` – counts all observed triggers (admitted and non-admitted), tagged with final stage-aware `outcome` (must match `script_event_audit.finalOutcome`).
- `automation_script_skips_total{tenantId, scriptId, pluginId, reason, priorityTag}` – counts triggers that were intentionally skipped before sandbox execution (for example, `reason="reloading"`, `reason="disabled"`, `reason="priority_throttled"`).
- `automation_script_triggers_dropped_total{tenantId, scriptId, pluginId, reason, priorityTag}` – counts triggers that could not be processed to tick acceptance (for example, `reason="quota"`, `reason="cluster_limit_reached"`, `reason="version_unavailable"`).
- `script_quota_allowed_total{tenantId, scriptId}` / `script_quota_denied_total{tenantId, scriptId, reason}` – per-script quota decisions before sandbox work begins.
- `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}` – sandbox-level failures such as `reason="cpu_budget_exceeded"` or `reason="memory_budget_exceeded"`.
- `automation_script_errors_total{tenantId, scriptId, pluginId, reason}` – higher-level error classification, including downstream failures.
- `automation_script_output_budget_exceeded_total{tenantId, scriptId, pluginId, reason}` – counts runs rejected because emitted work exceeded bounded output ceilings such as `command_count_exceeded` or `work_item_size_exceeded`.
- `automation_script_tenant_budget_seconds{tenantId, tier}` – per-tenant, per-priority-tier budget consumption.
- `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}` – distribution of sandbox runtime per script/plugin and event type.
- `automation_plugin_policy_violations_total{tenantId, pluginId, pluginVersionId, componentId, reason}` – counts plugin triggers rejected due to component policy; each violation should correspond to a `script_event_audit` entry with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` indicating the blocked component/policy decision.
- `automation_script_timer_catchup_truncated_total{tenantId, scriptId, eventType, reason}` – counts timer catch-up firings intentionally truncated by resume-window limits.

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
- Dry-run/test executions must also have isolated execution capacity:
  - Separate queues or worker reservations are required so privileged test traffic cannot starve live automation workers.
  - When shared infrastructure is saturated, dry-run/test work must be shed before live gameplay automation for the same scope/tier.
- Per-principal limits require deterministic identity:
  - Use a stable principal key derived from authenticated actor claims (for example, subject/`actorPrincipal`) in dry-run quota keys.
  - Reject missing principal identity for endpoints configured with per-principal enforcement.
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
  - `finalStage=ADMISSION`, `finalOutcome=signer_policy_unavailable` – plugin trigger was rejected because signer policy could not be refreshed/verified under fail-closed rules; contributes to drop metrics with a bounded policy-availability reason and does not run the sandbox.

- **Sandbox-level failures**
  - `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error` – the DSL runtime rejected the run or hit a guard (for example, `finalReason=cpu_budget_exceeded`, `finalReason=memory_budget_exceeded`, `finalReason=iteration_budget_exceeded`); increments `automation_script_sandbox_failures_total{reason=...}` and `automation_script_errors_total{reason=...}` and feeds into failure-rate circuit breakers for live traffic (`isDryRun=false`).
  - Output-budget failures (for example `finalReason=command_count_exceeded`, `finalReason=per_entity_command_limit_exceeded`, `finalReason=work_item_size_exceeded`) must increment `automation_script_output_budget_exceeded_total{reason=...}` and remain non-success stage-aware outcomes; they must not be counted as successful handoff.

- **Infrastructure-level failures**
  - `finalOutcome=infrastructure_error` (with a `finalStage` that reflects where it failed) – transport or infrastructure problems (for example, Redis timeouts, gRPC `UNAVAILABLE`); counted separately from sandbox errors, may trigger retries at lower layers using idempotency keys, and contribute to infra-focused alerts.
  - `finalStage=ADMISSION`, `finalOutcome=rollback_convergence_timeout` – rollback convergence timeout terminal state is active for scope and admission remains paused; increments `automation_rollback_convergence_timeout_total{tenantId, gameInstanceId, reason}`.

The failure-rate circuit breaker primarily considers **sandbox_error** and other logical script failures when deciding to transition a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`. Quota denials and purely infrastructure-level errors do not, by themselves, trigger disables, although they should still be visible in metrics and dashboards.

---

## Related Operations Cookbook

The steady-state quota, budget, and observability contracts stay in this parent doc. The operational playbooks now live in a focused sibling doc:

- [Scripting Operations Cookbook](./system-architecture-scripting-operations-cookbook.md) covers quota/budget troubleshooting, disable/throttle flows, rollback and recovery scenarios, and rollback recovery procedures.

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
