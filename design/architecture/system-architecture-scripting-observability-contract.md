# FireMUD Scripting & Automation: Observability Contract

This document defines the observability contract for scripting and automation: what is recorded in `script_event_audit`, what is emitted as metrics, and which identifiers may be used for correlation.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document is authoritative for observability details not fully enumerated in the normative tables.

## Correlation Rules (High Cardinality)

- `scriptEventId` is for `script_event_audit`, logs, and traces.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- Metrics may include lower-cardinality identifiers such as `tenantId`, `scriptId`, `pluginId`, `pluginVersionId`, and `eventType` as documented below.

## `script_event_audit` (Required Fields)

Each observed trigger (admitted or rejected at admission) must write (or update) a single audit record keyed by the trigger identity described in `design/architecture/system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe`.

Write behavior requirements:

- Storage must enforce uniqueness for full Trigger Identity so retries and duplicate deliveries update one logical record.
- Audit writers must be idempotent and stage-monotonic; `finalStage` must never move backwards.
- Conflicting concurrent writes for the same Trigger Identity must converge on a single row with deterministic precedence (higher stage wins).
- Admission rejections and backpressure outcomes (for example `quota_denied`, `skipped_reloading`, `skipped_rollback_pause`) must still produce the row for that Trigger Identity with `finalStage=ADMISSION`.

Audit records must include at least:

- Identity and versioning
  - `tenantId`
  - `gameInstanceId`
  - `regionId`
  - `regionEpoch` (required for gameplay/runtime and scheduler triggers; exceptions must be explicitly documented in the normative Trigger Identity table)
  - `entityId` (for entity-scoped events)
  - `scriptId`
  - `pluginId` and `pluginVersionId` (required for plugin triggers)
  - `eventType`
  - `scriptPatchVersion`
  - `scriptEventId`
  - `isDryRun` (boolean)
  - `sourceService` (required for custom/service-specific events; omitted for built-in events that originate entirely within Automation & Scripting)
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

For output-budget failures, writers must use bounded canonical `finalReason` values rather than free-form strings. Minimum required reasons:

- `command_count_exceeded`
- `per_entity_command_limit_exceeded`
- `work_item_size_exceeded`

Dry-run/test executions must use a separate idempotency namespace from live traffic. A dry-run record must never dedupe or overwrite a live trigger with the same `scriptEventId`.

Any API, proto, or query surface that exposes `script_event_audit` records must include `sourceService` whenever it presents custom/service-specific events. External tooling must not assume a smaller audit schema than this contract.

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
- Rollback pause backpressure `skipped_rollback_pause` must use `finalStage=ADMISSION`.
- Quota denials must use `finalStage=ADMISSION` unless quotas are evaluated inside the DSL runtime for a given trigger (rare; avoid mixing).

### Canonical Outcome Taxonomy (Required)

Audit writers must use the canonical `finalOutcome` values defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative`.

In particular:

- Use `version_unavailable` (never `skipped_version_unavailable`).
- Encode specific cause in `finalReason` (for example `onload_failed`, `plugin_version_failed`, `script_patch_missing`).
- Use `pin_state_unavailable` when admission fails closed because bounded-staleness pin data cannot be refreshed.
- Use `signer_policy_unavailable` when plugin admission fails closed because signer policy cannot be refreshed/verified from authoritative policy sources.
- Use `script_disabled` for operator disable/drain admission skips (never `skipped_disabled`).
- Use `rollback_convergence_timeout` when rollback pause remains active after convergence timeout terminal state.
- Output-budget failures may be represented by different `finalOutcome` values depending on the last attempted stage, but they must use one of the bounded canonical `finalReason` values above and must never be collapsed into an unstructured catch-all.

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

### Policy Decision to Outcome Rules (Required)

When `policyViolations` is present, `decision` values and final outcomes must align with policy mode:

- If all entries have `decision=REPORT_ONLY`, execution may continue and `finalOutcome` must still represent pipeline result (`success`, `sandbox_error`, `infrastructure_error`, and so on). `finalOutcome=plugin_component_blocked` is not valid in this case.
- If any entry has `decision=BLOCKED`, admission must stop with `finalStage=ADMISSION` and `finalOutcome=plugin_component_blocked`.
- `automation_plugin_policy_violations_total` must be emitted in both report-only and enforcing modes so operators can compare rollout behavior before and after enforcement.

## Metrics (Authoritative Names and Label Rules)

The normative metric-family catalog lives in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix`. This section describes observability behavior and grouping expectations for those families.

- Trigger admission and drops
  - `automation_script_triggers_total{tenantId, scriptId, pluginId, pluginVersionId, eventType, outcome, priorityTag}`
  - `automation_script_skips_total{tenantId, scriptId, pluginId, reason, priorityTag}`
  - `automation_script_triggers_dropped_total{tenantId, scriptId, pluginId, reason, priorityTag}`
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
  - `automation_script_timer_catchup_truncated_total{tenantId, scriptId, eventType, reason}`
- Sandbox and runtime health
  - `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_errors_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_output_budget_exceeded_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
- Dry-run/test traffic (separate from live)
  - `automation_script_test_runs_total{tenantId, scriptId, pluginId, eventType, result}`
  - `automation_script_test_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
  - `automation_script_test_sandbox_failures_total{tenantId, scriptId, pluginId, eventType, reason}`
- Plugin policy
  - `automation_plugin_policy_violations_total{tenantId, pluginId, pluginVersionId, componentId, reason}`
- Rollback convergence timeout
  - `automation_rollback_convergence_timeout_total{tenantId, gameInstanceId, reason}`
- Rollback drain fencing
  - `automation_rollback_drain_canceled_total{tenantId, gameInstanceId, finalStage, reason}`

Label rules:

- `scriptEventId` is forbidden as a metric label.
- If `tenantId` labeling becomes too high-cardinality in practice, it must be moved behind aggregation (for example per-tier or sampled) rather than introducing ad-hoc per-event labels.

Metric semantics:

- `automation_script_triggers_total` counts all observed triggers (admitted and non-admitted), tagged with canonical final stage-aware outcomes.
- `automation_rollback_drain_canceled_total` counts old-epoch executions intentionally fenced during rollback draining before live work could persist or hand off. It must be used for bounded rollback-drain visibility rather than a generic infrastructure failure counter.

Dry-run/test traffic must not increment live-traffic counters such as `automation_script_sandbox_failures_total` or `automation_script_errors_total`. Live dashboards and SLOs must remain interpretable without privileged tooling skewing error rates.

## Required Links

This contract is referenced by:

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/system-architecture-logging-monitoring.md`
