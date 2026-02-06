# FireMUD Scripting & Automation: Observability Contract

This document defines the **authoritative** observability contract for scripting and automation: what is recorded in `script_event_audit`, what is emitted as metrics, and which identifiers may be used for correlation.

If other documents conflict on metric names/labels or audit field semantics, treat this document as the tie-breaker.

## Correlation Rules (High Cardinality)

- `scriptEventId` is for `script_event_audit`, logs, and traces.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- Metrics may include lower-cardinality identifiers such as `tenantId`, `scriptId`, `pluginId`, `pluginVersionId`, and `eventType` as documented below.

## `script_event_audit` (Required Fields)

Each admitted trigger must write (or update) a single audit record keyed by the trigger identity described in `design/architecture/system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe`.

Audit records must include at least:

- Identity and versioning
  - `tenantId`
  - `regionId`
  - `regionEpoch` (when the trigger is tick-aligned)
  - `entityId` (for entity-scoped events)
  - `scriptId`
  - `eventType`
  - `scriptPatchVersion`
  - `scriptEventId`
  - `isDryRun` (boolean)
- Scheduling context (when applicable)
  - `triggerMode` (for example `NORMAL` vs `CATCH_UP`)
  - `dueTickId` and/or `dueAt` (for timers/intervals)
- Outcomes (stage-aware)
  - `finalOutcome` and `finalReason` (canonical outcome taxonomy used by dashboards and operators)
  - A stage marker that distinguishes:
    - DSL evaluation outcome
    - Work-item persistence outcome (if using a durable outbox)
    - Handoff/enqueue outcome into the tick system

Outcome fields must be sufficient to distinguish “DSL evaluated successfully” from “commands were accepted into the tick system”. Do not collapse these into a single `success` signal.

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
  - `automation_script_queue_delay_seconds{tenantId, scriptId}`
  - `automation_queue_orphaned_entries_total{tenantId}` (when applicable)
- Sandbox and runtime health
  - `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_errors_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
- Dry-run/test traffic (separate from live)
  - `automation_script_test_runs_total{tenantId, scriptId, pluginId, eventType, result}`
  - `automation_script_test_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
- Plugin policy
  - `automation_plugin_policy_violations_total{tenantId, pluginId, pluginVersionId, componentId, reason}`

Label rules:

- `scriptEventId` is forbidden as a metric label.
- If `tenantId` labeling becomes too high-cardinality in practice, it must be moved behind aggregation (for example per-tier or sampled) rather than introducing ad-hoc per-event labels.

## Required Links

This contract is referenced by:

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/system-architecture-logging-monitoring.md`

