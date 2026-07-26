# FireMUD Scripting & Automation: Observability Contract

This document defines the observability contract for scripting and automation: what is recorded in `script_event_audit`, what is returned by `ListScriptHandoffEvents`, what is emitted as metrics, and which identifiers may be used for correlation.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document is authoritative for observability details not fully enumerated in the normative tables.

## Live Versus Target-State Handoff Diagnostics

The complete per-command handoff diagnostic model below is **target-state**. The live Game Session proto carries `automationDispatchId`, command id/text, and selected provenance fields, but it does not yet carry `commandOrdinal` or the full Trigger Identity needed for the target-state child record. Current live command status/readbacks therefore expose a narrower diagnostic surface; the examples below must not be read as evidence that the full target-state handoff contract is already implemented.

## Correlation Rules (High Cardinality)

- `scriptEventId` is for `script_event_audit`, logs, and traces.
- `automationDispatchId` is for per-command handoff history, logs, and cross-service correlation with Game Session command admission, not for Prometheus labels.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- `automationDispatchId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- Metric families in this design may use bounded semantic dimensions such as `eventType`, `outcome`, `reason`, `priorityTag`, and an explicitly approved low-cardinality `scope`, but raw `tenantId`, `scriptId`, `pluginId`, and `pluginVersionId` belong in audit rows, logs, traces, and control-plane queries rather than ordinary canonical Prometheus labels. When the metric catalog below refers to those logical dimensions, treat them as grouping concepts that still require bounded producer-side normalization before they are emitted.

## Ingress Audit vs Handler Audit

Event-scope ingress decisions and handler-scoped execution outcomes are separate observability facts.

- Event-scope ingress audit/logging records pre-resolution decisions for the incoming event, such as auth failure, reload backpressure, rollback pause, pin-state unavailability, or version unavailability. These records are keyed by the event-scope identity in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields` and must not invent a synthetic `scriptId`.
- `script_event_audit` records handler-scoped, scheduler/timer-scoped, tenant-readiness `onLoad`, and dry-run/test executions after a concrete script or plugin handler identity exists.
- per-command handoff history is a separate durable child surface keyed by the complete applicable command-handoff scope plus the target-state `(automationDispatchId, commandOrdinal)` pair; `outboxWorkItemId` is retained only as parent-work correlation so one handler audit row can still correlate to multiple emitted gameplay commands.
- A successful event-scope ingress record means the event was accepted for handler resolution. It is not a summary of every handler outcome.
- If ingress is accepted and resolves three handlers, tooling should expect one event-scope ingress record and up to three handler-scoped `script_event_audit` records, one per resolved Trigger Identity.
- If one resolved handler emits three gameplay commands, tooling should expect one handler-scoped `script_event_audit` row plus three durable handoff-event rows under `ListScriptHandoffEvents`.

### Per-Command Handoff Records (Target-State)

A resolved handler may emit zero, one, or many gameplay commands. `script_event_audit` remains one handler-scoped row per Trigger Identity and must not contain a single command dispatch field or a single post-handoff outcome for the whole Trigger Identity.

- Persist or return one command-handoff record for each attempted emitted command.
- Each gameplay command record is keyed by the target-state scope-complete command-level handoff identity `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, automationDispatchId, commandOrdinal>` plus any other applicable target identity dimensions. It includes the parent `outboxWorkItemId` only as correlation, the complete parent Trigger Identity (including `scriptEventId` and plugin `bindingId` when applicable), handoff outcome/reason, and any later gameplay execution outcome/reason. A child record must retain these fields rather than relying on an implicit join to the handler row for scope identity.
- `ListScriptHandoffEvents` is the canonical query surface for these records. A query that combines handler and command data must expose a collection such as `commandHandoffDispositions[]`; it must not collapse sibling commands into one dispatch ID or one disposition on the handler audit row.
- A version-fence drop on one command updates only that command-handoff record. It must not overwrite the handler audit row or the dispositions of sibling commands.

## `script_event_audit` (Required Fields)

Each observed handler-scoped trigger, scheduler/timer trigger, tenant-readiness `onLoad` trigger, or dry-run/test execution must write (or update) a single audit record keyed by the trigger identity described in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields`.

Write behavior requirements:

- Storage must enforce uniqueness for full Trigger Identity so retries and duplicate deliveries update one logical record.
- Audit writers must be idempotent and stage-monotonic; `finalStage` must never move backwards.
- Conflicting concurrent writes for the same Trigger Identity must converge on a single row with deterministic precedence (higher stage wins).
- Handler-scoped admission rejections and backpressure outcomes (for example `quota_denied`, `script_disabled`, or timer-scope `skipped_reloading`) must still produce the row for that Trigger Identity with `finalStage=ADMISSION`. Pre-resolution event-scope denials belong in the ingress audit/logging surface described above.

Audit records must include at least:

- Identity and versioning
  - `tenantId`
  - `gameInstanceId` (absent for tenant-readiness `onLoad`)
  - `regionId`
  - `regionEpoch` (required for gameplay/runtime and scheduler triggers; exceptions must be explicitly documented in the normative Trigger Identity table)
  - `entityId` (for entity-scoped events)
  - `scriptId`
  - `pluginId`, `pluginVersionId`, and `bindingId` (required for resolved plugin handlers)
  - `eventType`
  - `scriptPatchVersion`
  - `scriptEventId`
  - `isDryRun` (boolean)
  - `sourceService` (required for custom/service-specific events; omitted for built-in events that originate entirely within Automation & Scripting)
- Scheduling context (when applicable)
  - `triggerMode` (for example `NORMAL` vs `CATCH_UP`)
  - Exactly one of `dueTickId` or `dueAt` (for timers/intervals); the alternate field is absent/`NULL`
- Outcomes (stage-aware)
  - `finalStage` (the last stage reached for this trigger; see below)
  - `finalOutcome` and `finalReason` (canonical outcome taxonomy used by dashboards and operators)
  - A stage-aware breakdown that distinguishes:
    - Admission/backpressure decisions (no DSL run)
    - DSL evaluation outcome
    - Work-item persistence outcome (if using a durable outbox)
    - Handoff/enqueue outcome into the tick system
  - A query-composed `commandHandoffDispositions[]` collection whenever emitted command child records exist, including initial handoff-only records before a later execution-time result is known. These target-state child records are not part of the Automation-owned `finalStage` progression and are keyed by the complete applicable command-handoff scope plus `(automationDispatchId, commandOrdinal)`, with the parent Trigger Identity retained for correlation. `outboxWorkItemId` is correlation metadata, not a substitute for the child key.
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
- `WORK_ITEM_PERSIST` – the resulting script work item was persisted durably (for example, into a Postgres outbox) before being indexed into the rebuildable automation queue projection.
- `TICK_HANDOFF` – the work item was handed off to Game Session and accepted into tick queues (the point at which live `finalOutcome=success` is allowed).
- `DRY_RUN_RESULT` – a non-committing dry-run/test execution completed after DSL evaluation and returned the would-be commands to the authorized caller without persisting a work item or handing off to tick queues.

Required fields:

- `finalStage` must be one of the stages above and must match the last stage attempted for the trigger. Live executions must not use `DRY_RUN_RESULT`; dry-run/test executions must not use `WORK_ITEM_PERSIST` or `TICK_HANDOFF`.
- `finalOutcome` / `finalReason` must describe what happened at `finalStage`.

Recommended (strongly preferred) structured representation:

- `stages` – a JSON array of stage entries in order, where each entry includes:
  - `stage` (one of the stage names above)
  - `outcome` and `reason`
  - `at` (timestamp)

If a structured `stages` array is not used, equivalent per-stage fields must exist (for example `admissionOutcome`, `dslOutcome`, `workItemOutcome`, `tickHandoffOutcome`) so tooling can still differentiate failures.

Stage semantics:

- `finalOutcome=success` must imply `finalStage=TICK_HANDOFF` (commands were accepted into tick queues). “DSL evaluated successfully but handoff failed” is not success.
- Tenant-readiness `onLoad` completion must use `finalStage=DSL_EVAL` and `finalOutcome=readiness_success`; it is not a live gameplay success signal.
- `finalOutcome=dry_run_success` must imply `finalStage=DRY_RUN_RESULT` and `isDryRun=true`. It means only that the non-committing test evaluation completed and returned inspectable would-be commands.
- Backpressure outcomes like `skipped_reloading` must use `finalStage=ADMISSION`.
- Rollback pause backpressure `rollback_paused` must use `finalStage=ADMISSION`.
- Quota denials must use `finalStage=ADMISSION` unless quotas are evaluated inside the DSL runtime for a given trigger (rare; avoid mixing).
- Intentional rollback/control-plane fencing after admission must stay visible as `finalOutcome=canceled` at the last attempted live stage, with bounded `finalReason` values such as `rollback_epoch_advanced`, `superseded_by_newer_patch`, `operator_canceled`, or `operator_purged`.

### Per-Command Handoff and Post-Handoff Outcomes (Required When Present)

`script_event_audit` is the canonical Automation-owned lifecycle record through `TICK_HANDOFF`, but it is not the sole post-handoff surface and it must not contain a single disposition for a fan-out trigger. In the target state, `ListScriptHandoffEvents` is the canonical durable query for per-command records: an initial handoff-only child is recorded for every attempted emitted command, and later Game Session acceptance, rejection, or execution-time version-fence results update or extend that command's disposition. A combined trigger read must expose those records as `commandHandoffDispositions[]`, with one element per emitted command keyed by its complete applicable command-handoff scope plus `(automationDispatchId, commandOrdinal)`. Each child retains the parent Trigger Identity, including plugin `bindingId` when applicable; tooling must not rely on metrics alone to correlate the records back to the original trigger.

When a downstream service reports a later handoff or execution result, the target-state command-handoff surface must expose or update a child disposition keyed to the affected `(automationDispatchId, commandOrdinal)` pair with:

- `automationDispatchId` – the stable identity of the emitted gameplay command.
- `commandOrdinal` – the deterministic ordinal of that emitted command within the handler handoff.
- `outcome` – bounded enum. Minimum required value: `version_fence_dropped`.
- `reason` – bounded reason such as `script_patch_mismatch` or `plugin_version_mismatch`.
- `recordedAt` – timestamp.
- `sourceService` – producer of the disposition (for example `game-session`).

Each returned child retains the parent `outboxWorkItemId` only for correlation and retains the applicable Trigger Identity fields needed for diagnosis, including plugin `bindingId` when applicable; the target-state `(automationDispatchId, commandOrdinal)` pair is part of the scope-complete command-level key and must not be replaced with the parent `scriptEventId`.

Rules:

- A command-handoff disposition does **not** replace `finalStage` / `finalOutcome`; those fields remain the Automation-owned handler pipeline result.
- A handler may therefore show `finalStage=TICK_HANDOFF`, `finalOutcome=success`, while one child command disposition has `outcome=version_fence_dropped` and sibling command dispositions remain successful.
- When present, UI/query surfaces must return both views together so operators can distinguish “accepted into tick queues” from “later fenced before execution.”

During rollback, operator views must show the handler's `finalStage`/`finalOutcome` beside the `commandHandoffDispositions[]` returned from `ListScriptHandoffEvents`. A successful `TICK_HANDOFF` therefore remains visible even when one or more individual commands later receive `version_fence_dropped`; a child result must never overwrite the handler result or collapse sibling command records.

Concrete example:

- `script_event_audit` row for Trigger Identity `T123` ends with `finalStage=TICK_HANDOFF`, `finalOutcome=success`.
- The handler emitted two commands. Later, Game Session rejects only `(automationDispatchId=work-9, commandOrdinal=1)` during rollback convergence and appends a child disposition with `outcome=version_fence_dropped`, `reason=script_patch_mismatch`, `sourceService=game-session`, and `recordedAt=...`; `(automationDispatchId=work-9, commandOrdinal=0)` remains a separate sibling record.
- Queries for `T123` must surface the handler row plus both command-handoff records so operators can tell that Automation succeeded and which gameplay command was later fenced.

Illustrative record shape:

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "gameInstanceId": "44444444-4444-4444-8444-444444444444",
  "playableStateScope": "isolated",
  "regionId": "R2",
  "regionEpoch": 14,
  "entityId": "npc-guard-9",
  "scriptId": "guard-on-enter",
  "eventType": "onEnterRegion",
  "eventSchemaVersion": 1,
  "scriptPatchVersion": "P22",
  "scriptEventId": "evt-7f4c",
  "isDryRun": false,
  "finalStage": "TICK_HANDOFF",
  "finalOutcome": "success",
  "finalReason": "accepted_into_tick_queue",
  "stages": [
    {
      "stage": "ADMISSION",
      "outcome": "admitted",
      "reason": "ok",
      "at": "2026-03-19T08:10:00Z"
    },
    {
      "stage": "DSL_EVAL",
      "outcome": "evaluated",
      "reason": "ok",
      "at": "2026-03-19T08:10:00Z"
    },
    {
      "stage": "WORK_ITEM_PERSIST",
      "outcome": "persisted",
      "reason": "ok",
      "at": "2026-03-19T08:10:00Z"
    },
    {
      "stage": "TICK_HANDOFF",
      "outcome": "success",
      "reason": "accepted_into_tick_queue",
      "at": "2026-03-19T08:10:01Z"
    }
  ],
  "commandHandoffDispositions": [
    {
      "tenantId": "11111111-1111-4111-8111-111111111111",
      "gameInstanceId": "44444444-4444-4444-8444-444444444444",
      "regionId": "R2",
      "regionEpoch": 14,
      "entityId": "npc-guard-9",
      "scriptId": "guard-on-enter",
      "eventType": "onEnterRegion",
      "eventSchemaVersion": 1,
      "scriptPatchVersion": "P22",
      "scriptEventId": "evt-7f4c",
      "isDryRun": false,
      "commandOrdinal": 0,
      "automationDispatchId": "work-9#0",
      "outboxWorkItemId": "work-9",
      "playableStateScope": "isolated",
      "outcome": "accepted",
      "reason": "game_session_accepted",
      "sourceService": "game-session",
      "recordedAt": "2026-03-19T08:10:02Z"
    },
    {
      "tenantId": "11111111-1111-4111-8111-111111111111",
      "gameInstanceId": "44444444-4444-4444-8444-444444444444",
      "regionId": "R2",
      "regionEpoch": 14,
      "entityId": "npc-guard-9",
      "scriptId": "guard-on-enter",
      "eventType": "onEnterRegion",
      "eventSchemaVersion": 1,
      "scriptPatchVersion": "P22",
      "scriptEventId": "evt-7f4c",
      "isDryRun": false,
      "commandOrdinal": 1,
      "automationDispatchId": "work-9#1",
      "outboxWorkItemId": "work-9",
      "playableStateScope": "isolated",
      "outcome": "version_fence_dropped",
      "reason": "script_patch_mismatch",
      "sourceService": "game-session",
      "recordedAt": "2026-03-19T08:10:03Z"
    }
  ]
}
```

This target-state example is illustrative rather than prescriptive about JSON column layout, but any API or query surface must preserve the same information model: one Trigger Identity, one Automation-owned handler final stage/outcome, and zero or more later per-command handoff dispositions keyed by `(automationDispatchId, commandOrdinal)`.

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
  - `automation_script_triggers_total{scope, script_category, plugin_family, plugin_version_family, eventType, outcome, priorityTag}`
  - `automation_script_skips_total{scope, script_category, plugin_family, reason, priorityTag}`
  - `automation_script_triggers_dropped_total{scope, script_category, plugin_family, reason, priorityTag}`
- Quotas and budgets
  - `script_quota_allowed_total{scope, script_category}`
  - `script_quota_denied_total{scope, script_category, reason}`
  - `automation_script_tenant_budget_allowed_total{scope, tier}` / `automation_script_tenant_budget_denied_total{scope, tier}`
- Tick integration and queueing
  - `automation_tick_events_enqueued_total{scope}`
  - `automation_tick_version_fence_dropped_total{scope, script_category, reason}`
  - `automation_tick_plugin_version_fence_dropped_total{scope, plugin_family, plugin_version_family, reason}`
  - `automation_script_queue_delay_seconds{scope, script_category}`
  - `automation_queue_orphaned_entries_total{scope}` (when applicable)
  - `automation_script_timer_catchup_truncated_total{scope, script_category, eventType, reason}`
- Sandbox and runtime health
  - `automation_script_sandbox_failures_total{scope, script_category, plugin_family, reason}`
  - `automation_script_errors_total{scope, script_category, plugin_family, reason}`
  - `automation_script_output_budget_exceeded_total{scope, script_category, plugin_family, reason}`
  - `automation_script_runtime_seconds{scope, script_category, plugin_family, eventType}`
- Dry-run/test traffic (separate from live)
  - `automation_script_test_runs_total{scope, script_category, plugin_family, eventType, result}`
  - `automation_script_test_runtime_seconds{scope, script_category, plugin_family, eventType}`
  - `automation_script_test_sandbox_failures_total{scope, script_category, plugin_family, eventType, reason}`
- Plugin policy
  - `automation_plugin_policy_violations_total{scope, plugin_family, plugin_version_family, component_class, reason}`
- Rollback convergence timeout
  - `automation_rollback_convergence_timeout_total{scope, operation, reason}`
- Rollback drain fencing
  - `automation_rollback_drain_canceled_total{scope, operation, finalStage, reason}`

Label rules:

- `scriptEventId` is forbidden as a metric label.
- Raw tenant, script, plugin, and runtime identifiers are not approved ordinary Prometheus labels here. Producers must emit bounded `scope`, category, family, or operation dimensions instead of raw IDs.

Metric semantics:

- `automation_script_triggers_total` counts all observed triggers (admitted and non-admitted), tagged with canonical final stage-aware outcomes.
- `automation_rollback_drain_canceled_total` counts old-epoch executions intentionally fenced during rollback draining before live work could persist or hand off. It must be used for bounded rollback-drain visibility rather than a generic infrastructure failure counter.
  It is not the counter for ordinary operator-initiated cancel/purge actions on not-yet-running work items unless those items had already crossed into execution and were then fenced by rollback epoch advancement.

Dry-run/test traffic must not increment live-traffic counters such as `automation_script_sandbox_failures_total` or `automation_script_errors_total`. Live dashboards and SLOs must remain interpretable without privileged tooling skewing error rates.

## Required Links

This contract is referenced by:

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/system-architecture-logging-monitoring.md`
