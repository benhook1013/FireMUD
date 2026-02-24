# FireMUD Scripting & Automation: Observability Contract

This document defines the **authoritative** observability contract for scripting and automation: what is recorded in `script_event_audit`, what is emitted as metrics, and which identifiers may be used for correlation.

If other documents conflict on metric names/labels or audit field semantics, treat this document as the tie-breaker.

For the normative “single source of truth” tables (Trigger Identity required fields, audit stages/outcomes, timer semantics, and metric label sets), see `design/architecture/system-architecture-scripting-normative-contract-tables.md`.

## Correlation Rules (High Cardinality)

- `scriptEventId` is for `script_event_audit`, logs, and traces.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- Metrics may include lower-cardinality identifiers such as `tenantId`, `scriptId`, `pluginId`, `pluginVersionId`, and `eventType` as documented below.

## `script_event_audit` (Required Fields)

Each admitted trigger must write (or update) a single audit record keyed by the trigger identity described in `design/architecture/system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe`.

Audit records must include at least:

- Identity and versioning
  - `tenantId`
  - `gameInstanceId`
  - `regionId`
  - `regionEpoch` (when the trigger is tick-aligned)
  - `entityId` (for entity-scoped events)
  - `scriptId`
  - `pluginId` and `pluginVersionId` (required for plugin triggers)
  - `eventType`
  - `scriptPatchVersion`
  - `scriptEventId`
  - `isDryRun` (boolean)
- Scheduling context (when applicable)
  - `triggerMode` (for example `NORMAL` vs `CATCH_UP`)
  - `dueTickId` and/or `dueAt` (for timers/intervals)
- Outcomes (stage-aware)
  - `finalStage` (the last stage reached for this trigger; see below)
  - `finalOutcome` and `finalReason` (canonical outcome taxonomy used by dashboards and operators)
  - A stage-aware breakdown that distinguishes:
    - Admission/backpressure decisions (no DSL run)
    - DSL evaluation outcome
    - Work-item persistence outcome (if using a durable outbox)
    - Handoff/enqueue outcome into the tick system
  - `policyViolations` (optional array, plugin policy rollouts only; see schema below)

Outcome fields must be sufficient to distinguish “DSL evaluated successfully” from “commands were accepted into the tick system”. Do not collapse these into a single `success` signal.

### Stage Model (Required)

`script_event_audit` outcomes must be **stage-aware** so operators can answer: “Did the trigger fail before evaluation, during evaluation, during persistence, or during tick handoff?”

Stages:

- `ADMISSION` – the trigger was accepted/rejected before any DSL evaluation (quotas, reload backpressure, disabled scripts, invalid version, policy enforcement).
- `DSL_EVAL` – the DSL graph was evaluated in the sandbox (validation, loop safety, runtime guards).
- `WORK_ITEM_PERSIST` – the resulting script work item was persisted durably (for example, into a Postgres outbox) before being indexed for automation ticks.
- `TICK_HANDOFF` – the work item was handed off to Game Session and accepted into tick queues (the point at which `finalOutcome=success` is allowed).

Required fields:

- `finalStage` must be one of the stages above and must match the last stage attempted for the trigger.
- `finalOutcome` / `finalReason` must describe what happened at `finalStage`.

Recommended (strongly preferred) structured representation:

- `stages` – a JSON array of stage entries in order, where each entry includes:
  - `stage` (one of the stage names above)
  - `outcome` and `reason`
  - `at` (timestamp)

If a structured `stages` array is not used, equivalent per-stage fields must exist (for example `admissionOutcome`, `dslOutcome`, `workItemOutcome`, `tickHandoffOutcome`) so tooling can still differentiate failures.

Stage semantics:

- `finalOutcome=success` must imply `finalStage=TICK_HANDOFF` (commands were accepted into tick queues). “DSL evaluated successfully but handoff failed” is not success.
- Backpressure outcomes like `skipped_reloading` must use `finalStage=ADMISSION`.
- Quota denials must use `finalStage=ADMISSION` unless quotas are evaluated inside the DSL runtime for a given trigger (rare; avoid mixing).

### Canonical Outcome Taxonomy (Required)

Audit writers must use the canonical `finalOutcome` values defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative`.

In particular:

- Use `version_unavailable` (never `skipped_version_unavailable`).
- Encode specific cause in `finalReason` (for example `onload_failed`, `plugin_version_failed`, `script_patch_missing`).

### `policyViolations` Schema (Required When Present)

`policyViolations` is used only for plugin component policy report-only/enforcing flows.

- Type: JSON array
- Max entries: `20`
- Max serialized size: `16 KiB`
- Entry schema:
  - `policyVersion` (string, required)
  - `componentId` (string, required)
  - `decision` (enum string, required: `REPORT_ONLY` or `BLOCKED`)
  - `reason` (string, required, bounded enum or stable short code)
  - `observedAt` (timestamp, required)

If limits are exceeded, writers must truncate deterministically and set `finalReason` or an auxiliary field to indicate truncation.

## Metrics (Authoritative Names and Label Rules)

The following metric families are the supported contract for automation/scripting operations:

- Trigger admission and drops
  - `automation_script_triggers_total{tenantId, scriptId, pluginId, pluginVersionId, eventType, outcome}`
  - `automation_script_skips_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_triggers_dropped_total{tenantId, scriptId, pluginId, reason}`
- Quotas and budgets
  - `script_quota_allowed_total{tenantId, scriptId}`
  - `script_quota_denied_total{tenantId, scriptId, reason}`
  - `automation_script_tenant_budget_seconds{tenantId, tier}`
- Tick integration and queueing
  - `automation_tick_events_enqueued_total{tenantId}`
  - `automation_tick_version_fence_dropped_total{tenantId, scriptId, reason}`
  - `automation_tick_plugin_version_fence_dropped_total{tenantId, pluginId, pluginVersionId, reason}`
  - `automation_script_queue_delay_seconds{tenantId, scriptId}`
  - `automation_queue_orphaned_entries_total{tenantId}` (when applicable)
- Sandbox and runtime health
  - `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_errors_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
- Dry-run/test traffic (separate from live)
  - `automation_script_test_runs_total{tenantId, scriptId, pluginId, eventType, result}`
  - `automation_script_test_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
  - `automation_script_test_sandbox_failures_total{tenantId, scriptId, pluginId, eventType, reason}`
- Plugin policy
  - `automation_plugin_policy_violations_total{tenantId, pluginId, pluginVersionId, componentId, reason}`

Label rules:

- `scriptEventId` is forbidden as a metric label.
- If `tenantId` labeling becomes too high-cardinality in practice, it must be moved behind aggregation (for example per-tier or sampled) rather than introducing ad-hoc per-event labels.

Dry-run/test traffic must not increment live-traffic counters such as `automation_script_sandbox_failures_total` or `automation_script_errors_total`. Live dashboards and SLOs must remain interpretable without privileged tooling skewing error rates.

## Required Links

This contract is referenced by:

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/system-architecture-logging-monitoring.md`
