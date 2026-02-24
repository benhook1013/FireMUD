# FireMUD Scripting & Automation: Normative Contract Tables

This document centralizes the **normative tables** for scripting and automation contracts so other design docs do not drift.

If another document conflicts with these tables (field names, required identifiers, timer semantics, or metric label sets), treat this document as the tie-breaker.

## Table of Contents

- [Table 1: Trigger Identity (Required Fields)](#table-1-trigger-identity-required-fields)
- [Table 2: `script_event_audit` Stages and Outcomes](#table-2-script_event_audit-stages-and-outcomes)
- [Table 3: Timer Semantics Matrix](#table-3-timer-semantics-matrix)
- [Table 4: Metrics Label Matrix](#table-4-metrics-label-matrix)

---

## Table 1: Trigger Identity (Required Fields)

Trigger Identity is the idempotency identity for “evaluate handlers for this trigger at most once” and the natural primary key for `script_event_audit`.

All event-ingress RPCs into Automation & Scripting (for example `TriggerScriptEvent`) are idempotent with respect to Trigger Identity. Retries must reuse the same identity fields (including `scriptEventId`).

Required identity fields for all triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `tenantId` | Yes | Tenant/project identity. |
| `gameInstanceId` | Yes | Required to avoid collisions when a tenant has multiple instances. Must be carried through audit and downstream idempotency. |
| `regionId` | Yes (gameplay/runtime) | Region context for the trigger. If a trigger is not region-scoped (rare), it must declare its own scope explicitly and still include `gameInstanceId`. |
| `regionEpoch` | Yes (gameplay/runtime) | Required for any trigger emitted from, aligned to, or fenced by the tick timeline. This includes standard gameplay lifecycle events emitted after tick commit (for example `onEnterRegion`, `onSpawn`, `onCommand`) and all scheduler/timer events. |
| `entityId` | Yes (entity-scoped) | Required for entity-scoped triggers. |
| `scriptId` | Yes | The script definition identity that will run. If bindings fan out, each `<scriptId>` is a distinct Trigger Identity. |
| `eventType` | Yes | Logical event key (for example `onEnterRegion`, `onInterval`). |
| `scriptPatchVersion` | Yes | Pinned patch version for the game instance at the time the trigger is emitted. |
| `scriptEventId` | Yes | Caller-supplied idempotency token. Must be stable across retries. Must not be used as a Prometheus label. |

Additional required fields for plugin triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `pluginId` | Yes (plugin triggers) | Required to distinguish plugin-triggered runs from core scripts when the same `scriptId` model is reused. |
| `pluginVersionId` | Yes (plugin triggers) | Required for rollback safety, audit correlation, and version-fence drops. |

Additional required fields for scheduler/timer triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `dueTickId` and/or `dueAt` | Yes | A due point is required so scheduler `scriptEventId` can be deterministic and so catch-up behavior can be audited. |
| `triggerMode` | Yes | `NORMAL` vs `CATCH_UP` to make bounded catch-up observable. |

Notes:

- If an implementation wants to treat some gameplay lifecycle events as “not tick-aligned”, it must define that exception explicitly and document why it is safe across scoped coordination resets. The default contract is that gameplay lifecycle triggers are fenced by `regionEpoch`.
- Downstream service calls made from scripts must propagate an idempotency token derived from Trigger Identity plus tick context when applicable (for example `tickId`), following `design/architecture/system-architecture-transactions.md`.

## Table 2: `script_event_audit` Stages and Outcomes

`script_event_audit` must be stage-aware so operators can distinguish “rejected before evaluation” from “evaluated but not handed off” and from “accepted into tick queues”.

### Required Stage Set

| `finalStage` | Meaning | “Success” allowed? |
| --- | --- | --- |
| `ADMISSION` | Pre-evaluation decisions: quotas, reload backpressure, disabled scripts, invalid version, policy enforcement. No DSL run occurs. | No |
| `DSL_EVAL` | DSL graph evaluation and sandbox enforcement (validation, loop safety, runtime budgets). | No |
| `WORK_ITEM_PERSIST` | Durable persistence of the resulting work item (outbox). | No |
| `TICK_HANDOFF` | Handoff to Game Session and acceptance into tick queues. | Yes |

### Required Outcome Rules (Normative)

| Stage | Required rule |
| --- | --- |
| `ADMISSION` | Must record explicit backpressure outcomes during `reloadState=RELOADING` (for example `finalOutcome=skipped_reloading`) rather than silent drops. |
| `DSL_EVAL` | Sandbox failures must be recorded as `finalOutcome=sandbox_error` with a specific `finalReason` (for example `cpu_budget_exceeded`, `memory_budget_exceeded`). |
| `WORK_ITEM_PERSIST` | If durable persistence fails, the audit record must not show success. It must record a persistence failure outcome and must not claim that effects were enqueued. |
| `TICK_HANDOFF` | `finalOutcome=success` is permitted only when Game Session has accepted commands into tick queues. “DSL evaluated successfully but handoff failed” must be a non-success handoff outcome. |

### Canonical `finalOutcome` Values (Normative)

Use a single canonical outcome taxonomy across docs, protos, metrics, and dashboards. Aliases are not allowed in new writes.

| Canonical value | Stage | Notes |
| --- | --- | --- |
| `success` | `TICK_HANDOFF` | Commands accepted into tick queues. |
| `skipped_reloading` | `ADMISSION` | Explicit reload backpressure; caller may retry with same Trigger Identity if policy allows. |
| `quota_denied` | `ADMISSION` | Script quota or concurrency/capacity denial before DSL evaluation. |
| `tenant_budget_exceeded` | `ADMISSION` | Tenant budget exhausted. |
| `version_unavailable` | `ADMISSION` | Unknown/failed/not-ready patch or plugin version. |
| `plugin_component_blocked` | `ADMISSION` | Plugin rejected by component policy. |
| `plugin_disabled` | `ADMISSION` | Plugin disabled or draining state. |
| `sandbox_error` | `DSL_EVAL` | Runtime or guard failure; reason required. |
| `validation_error` | `DSL_EVAL` | Static/semantic validation failure before effect persistence. |
| `infrastructure_error` | Any non-success stage | Transport/storage/runtime infrastructure failure. |
| `disabled_due_to_errors` | `ADMISSION` | Script disabled by failure-rate policy. |

Deprecated aliases:

- `skipped_version_unavailable` is deprecated. Use `finalOutcome=version_unavailable` with `finalReason` for specificity.

### Required Cleanup Rule for Version Fencing

If Game Session rejects a queued command because its embedded `scriptPatchVersion` does not match the currently pinned patch (or a plugin-produced command does not match the currently active `pluginVersionId` for its `pluginId`), it must:

- Record the drop with identifiers sufficient for diagnosis (including `scriptEventId`, `scriptId`, `scriptPatchVersion`, `gameInstanceId`, `regionId`, `entityId`).
- Remove the rejected queue entry (or move it to a bounded dead-letter store) so mismatched entries cannot accumulate unboundedly after a rollback.
- Emit an operator-visible metric for version-fence drops:
  - `automation_tick_version_fence_dropped_total{tenantId, scriptId, reason}` for script patch mismatches (for example `reason="script_patch_mismatch"`).
  - `automation_tick_plugin_version_fence_dropped_total{tenantId, pluginId, pluginVersionId, reason}` for plugin version mismatches (for example `reason="plugin_version_mismatch"`).

## Table 3: Timer Semantics Matrix

Timer-driven handlers (`onInterval`, `onTimerExpire`) are best-effort, at-most-once per Trigger Identity.

The matrix below defines what the scheduler does when a firing becomes due under different conditions:

| Condition | Behavior | Audit requirements |
| --- | --- | --- |
| Normal operation | Evaluate once per due firing under budgets and quotas. | One `script_event_audit` row per due Trigger Identity with stage-aware outcomes. |
| Quota/budget denied | Skip the firing; do not replay later. | `finalStage=ADMISSION` and an explicit deny outcome/reason. |
| `reloadState=RELOADING` | Do not admit new timer firings; do not backfill by default. | `finalStage=ADMISSION` with `finalOutcome=skipped_reloading` (or equivalent). |
| Leader failover / short downtime | May perform bounded catch-up for missed cadence boundaries: at most one synthetic firing per cadence boundary crossed, and never more than a bounded limit per resume window. | Catch-up firings must use `triggerMode=CATCH_UP` and deterministic `scriptEventId` derived from the due point. |
| Long downtime or sustained overload | No guarantee of eventual execution for every firing; the system converges by running future firings once capacity returns. | Missed firings must be visible as skips/drops in metrics and audit. |
| Infrastructure error after admission | Do not re-run the DSL body for the same `scriptEventId`. Only idempotent downstream ops may retry. | `finalStage` must reflect where it failed; do not record `success`. |

## Table 4: Metrics Label Matrix

This table defines the authoritative label sets for scripting metrics. Metric names and label rules must also follow `design/architecture/system-architecture-scripting-observability-contract.md`.

General rules:

- `scriptEventId` is forbidden as a metric label.
- If `tenantId` becomes too high-cardinality in practice, the system must introduce aggregation/sampling rather than introducing per-event labels.

| Metric family | Required labels | Forbidden labels | Notes |
| --- | --- | --- | --- |
| `automation_script_triggers_total` | `tenantId`, `scriptId`, `eventType`, `outcome`, optional `pluginId`, `pluginVersionId` | `scriptEventId` | Outcome is final, stage-aware outcome as defined by audit rules; do not treat “DSL eval success” as success if handoff failed. |
| `automation_script_skips_total` | `tenantId`, `scriptId`, `reason`, optional `pluginId` | `scriptEventId` | “Skip” is pre-eval. |
| `automation_script_triggers_dropped_total` | `tenantId`, `scriptId`, `reason`, optional `pluginId` | `scriptEventId` | “Dropped” indicates the trigger was not processed to tick acceptance. |
| `script_quota_allowed_total` | `tenantId`, `scriptId` | `scriptEventId` | Quota decisions are pre-eval. |
| `script_quota_denied_total` | `tenantId`, `scriptId`, `reason` | `scriptEventId` |  |
| `automation_tick_events_enqueued_total` | `tenantId` | `scriptEventId` | Counts successful tick handoffs, not DSL evaluations. |
| `automation_tick_version_fence_dropped_total` | `tenantId`, `scriptId`, `reason` | `scriptEventId` | Counts commands dropped at execution-time due to script patch version fence mismatches. |
| `automation_tick_plugin_version_fence_dropped_total` | `tenantId`, `pluginId`, `pluginVersionId`, `reason` | `scriptEventId` | Counts commands dropped at execution-time due to plugin version fence mismatches. |
| `automation_script_runtime_seconds` | `tenantId`, `scriptId`, `eventType`, optional `pluginId` | `scriptEventId` | Runtime is sandbox eval time (not tick execution time). |
| `automation_script_sandbox_failures_total` | `tenantId`, `scriptId`, `reason`, optional `pluginId` | `scriptEventId` |  |
| `automation_script_test_runs_total` | `tenantId`, `scriptId`, `eventType`, `result`, optional `pluginId` | `scriptEventId` | Must be separate from live-traffic counters. |
| `automation_script_test_sandbox_failures_total` | `tenantId`, `scriptId`, `eventType`, `reason`, optional `pluginId` | `scriptEventId` | Dry-run/test-only sandbox failures; must not increment live sandbox failure counters. |
