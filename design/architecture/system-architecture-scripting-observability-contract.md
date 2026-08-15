# FireMUD Scripting & Automation: Observability Contract

This document defines the observability contract for scripting and automation: what is recorded in `script_event_audit`, what is returned by `ListScriptHandoffEvents`, what is emitted as metrics, and which identifiers may be used for correlation.

For gameplay/runtime records, observability preserves the exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple. The tuple is diagnostic identity as well as a final-fence input; a version-only audit row cannot prove execution authority.

Document conflict resolution order is defined in the [normative document precedence](./system-architecture-scripting-normative-contract-tables.md#document-precedence-normative). The normative tables own metric-family names, labels, and increment units; this document is authoritative for observability details not fully enumerated there.

## Target-State Command Identity and Handoff Contract

The target per-command handoff contract records one child disposition for each emitted command. Each child carries the complete applicable Trigger Identity plus the `(automationDispatchId, commandOrdinal)` command discriminator described by the [Command-Handoff Identity](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state). `outboxWorkItemId` is retained solely as parent correlation and is excluded from command identity, uniqueness, and deduplication keys. `automationDispatchId` is not assumed globally unique, and a pair shown without its parent/scope context is only a display suffix. `script_event_audit` remains one handler row per Trigger Identity and is never a substitute for the command collection.

## Implementation Status

The complete per-command handoff diagnostic model is **target-state**. The live `EnqueueAutomationCommandIfAbsent` contract currently carries the narrower scope, dispatch/work-item, script/plugin provenance, target-command, routing, and origin-source fields listed in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#work-item-outbox-contract-normative), plus the returned Game Session `commandId`/admission outcome. It does not yet carry `commandOrdinal` or the full applicable Trigger Identity, including fields such as `bindingId`, `eventType`, `eventSchemaVersion`, `scriptEventId`, `isDryRun`, and scheduler identity. Current live command status/readbacks therefore expose only that narrower fallback surface; the examples below must not be read as evidence that the full target-state handoff contract is already implemented.

## Correlation Rules (High Cardinality)

- `scriptEventId` is for `script_event_audit`, logs, and traces.
- `automationDispatchId` is for per-command handoff history, logs, and cross-service correlation with Game Session command admission, not for Prometheus labels.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- `automationDispatchId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).
- Metric schema is owned by [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix). The bounded dimensions used in the local diagnostic examples below, such as `eventType`, `outcome`, `reason`, `priorityTag`, and `scope`, must be interpreted and emitted only as Table 4 permits; raw `tenantId`, `scriptId`, `pluginId`, and `pluginVersionId` belong in audit rows, logs, traces, and control-plane queries.

## Ingress Audit vs Handler Audit

Event-scope ingress decisions and handler-scoped execution outcomes are separate observability facts.

- Event-scope ingress audit/logging records pre-resolution decisions for the incoming event, such as auth failure, reload backpressure, rollback pause, pin-state unavailability, signer-policy unavailability, or version unavailability. A rejected pre-handler ingress returns `admitted=false` with its event-scope `admissionOutcome` and `admissionReason`, records the same pair in `script_event_ingress_audit`, and has the corresponding Table 4 ingress-drop consequence using the bounded reason. These records use the uniqueness key and atomic first-claim/retry rules in [Table 1](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields), must not invent a synthetic `scriptId`, `bindingId`, or command identity, and must not create a handler-scoped `script_event_audit` row.
- `script_event_audit` records resolved-handler/materialized-work lifecycles, including scheduler/timer-scoped, tenant-readiness `onLoad`, and dry-run/test executions after a concrete script or plugin handler identity exists. For event ingress, pre-handler dry-run rejection, signer-policy unavailability, and rollback backpressure remain ingress-audit outcomes; scheduler due-candidate skips use the `scheduleCandidateId` candidate-audit surface. A live admitted event with zero handlers is metric-only.
- per-command handoff history is a separate durable child surface keyed by the complete Command-Handoff Identity; `outboxWorkItemId` is retained only as parent-work correlation so one handler audit row can still correlate to multiple emitted gameplay commands.
- A successful event-scope ingress record means the event was accepted for handler resolution. It is not a summary of every handler outcome.
- If live ingress is accepted and resolves three handlers, tooling should expect one event-scope ingress record and three handler-scoped `script_event_audit` records, one per resolved Trigger Identity. The Table 4 metric consequence does not add a separate admitted-event increment.
- If one resolved handler emits three gameplay commands, tooling should expect one handler-scoped `script_event_audit` row plus three durable handoff-event rows under `ListScriptHandoffEvents`.

### Per-Command Handoff Records (Target-State)

A resolved handler may emit zero, one, or many gameplay commands. `script_event_audit` remains one handler-scoped row per Trigger Identity and must not contain a single command dispatch field or a single post-handoff outcome for the whole Trigger Identity.

- Persist or return one command-handoff record for each attempted emitted command.
- Each gameplay command record is keyed by the target-state complete Command-Handoff Identity defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state). It includes the parent `outboxWorkItemId` only as correlation, the complete parent Trigger Identity (including `scriptEventId` and plugin `bindingId` when applicable), handoff outcome/reason, and any later gameplay execution outcome/reason. A child record must retain these fields rather than relying on an implicit join to the handler row for scope identity.
- `ListScriptHandoffEvents` is the canonical query surface for these records. A query that combines handler and command data must expose a collection such as `commandHandoffDispositions[]`; it must not collapse sibling commands into one dispatch ID or one disposition on the handler audit row.
- A version-fence drop on one command updates only that command-handoff record. It must not overwrite the handler audit row or the dispositions of sibling commands.

## `script_event_audit` (Required Fields)

Each observed resolved-handler/materialized-work trigger, scheduler/timer trigger, tenant-readiness `onLoad` trigger, or materialized dry-run/test execution must write (or update) a single audit record keyed by the trigger identity described in [Table 1](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). For event ingress, pre-handler dry-run rejection, signer-policy unavailability, and rollback backpressure remain ingress-audit outcomes; scheduler due-candidate skips use the `scheduleCandidateId` candidate-audit surface. A live admitted event with zero handlers remains metric-only.

Write behavior requirements:

- Storage must enforce uniqueness for the event-scope key before handler resolution and for full Trigger Identity after resolution, so retries and duplicate deliveries update one logical record at each scope.
- The event-scope first claim and its admission outcome must be written atomically with the one `script_event_ingress_audit` row. The durable claim remains `IN_PROGRESS` until handler resolution and all matched-handler materialization attempts complete. A pre-handler transient backpressure denial may be recorded as retry-eligible only when policy explicitly permits it: persist the denial/audit and a bounded retry eligibility window exposed by a bounded `retryAfterMs` hint, perform no resolution/fan-out, and retain the same event-scope key. After the indicated retry time, a retry with that same identity may atomically reclaim/transition the existing claim to `IN_PROGRESS` for a new admission attempt; fencing keeps concurrent attempts from resolving or fanning out. A crash or stale owner is recovered under the same claim and fence. The ingress audit keeps one logical row/key while retaining the attempt/decision history (or equivalent durable audit/log/trace detail) under that same key, so retries remain observable without duplicate event-scope identities. If the bounded window expires without reclaim, the denial is terminal. A concurrent retry that finds `IN_PROGRESS` waits for the same fenced claim or receives a retryable signal without an admission result; it must not resolve/fan out again, return a partial count, or write another ingress row. A retry with the same terminal finalized key returns the exact stored admission response, and a changed applicable identity field is an identity conflict, not a retry.
- Audit writers must be idempotent and stage-monotonic; `finalStage` must never move backwards.
- Conflicting concurrent writes for the same Trigger Identity must converge on a single row with deterministic precedence (higher stage wins).
- Handler-scoped admission rejections and backpressure outcomes (for example `quota_denied`, `script_disabled`, timer-scope `skipped_reloading`, or post-resolution `rollback_paused`) must still produce the row for that Trigger Identity with `finalStage=ADMISSION`; handler retries reuse that full identity and propagated `scriptEventId`. Pre-resolution event-scope denials belong in the ingress audit/logging surface described above and follow the [event-scope claim retry lifecycle](./system-architecture-scripting-normative-contract-tables.md#event-scope-claim-and-retry-semantics-normative). Timer/cadence candidate skips remain on the `scheduleCandidateId` candidate-audit surface rather than becoming event-scope claims.

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
  - `scriptPinEpoch` (required for gameplay/runtime and scheduler triggers; absent for tenant-readiness `onLoad`)
  - `scriptEventId`
  - `isDryRun` (boolean)
  - `sourceService` (derived from authenticated producer/workload identity for custom/service-specific events; the same value used in ingress dedupe and persisted unchanged in ingress and handler audit; omitted for built-in events that originate entirely within Automation & Scripting)
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
  - A query-composed `commandHandoffDispositions[]` collection whenever emitted command child records exist, including initial handoff-only records before a later execution-time result is known. These target-state child records are not part of the Automation-owned `finalStage` progression and are keyed by the complete Command-Handoff Identity, with the parent Trigger Identity retained for correlation. `outboxWorkItemId` is correlation metadata, not a substitute for the child key.
  - `policyViolations` (optional array, plugin policy rollouts only; see schema below)

Outcome fields must be sufficient to distinguish “DSL evaluated successfully” from “commands were durably accepted into the tick system”. Do not collapse these into a single undifferentiated outcome signal.

For output-budget failures, writers must use bounded canonical `finalReason` values rather than free-form strings. Minimum required reasons:

- `command_count_exceeded`
- `per_entity_command_limit_exceeded`
- `work_item_size_exceeded`

Dry-run/test executions must use a separate idempotency namespace from live traffic. A dry-run record must never dedupe or overwrite a live trigger with the same `scriptEventId`.

Any API, proto, or query surface that exposes `script_event_audit` records must include `sourceService` whenever it presents custom/service-specific events. External tooling must not assume a smaller audit schema than this contract.

### Stage Model (Required)

`script_event_audit` outcomes must be **stage-aware** so operators can answer: “Did the trigger fail before evaluation, during evaluation, during persistence, or during tick handoff?”

Stages:

- `ADMISSION` – the resolved handler was accepted/rejected before any DSL evaluation (quotas, reload backpressure, disabled scripts, invalid version, policy enforcement). For event ingress, pre-handler rollback pause remains an ingress-audit decision; timer candidates use the `scheduleCandidateId` candidate-audit rule in the normative timer table.
- `DSL_EVAL` – the DSL graph was evaluated in the sandbox (validation, loop safety, runtime guards).
- `WORK_ITEM_PERSIST` – the resulting script work item was persisted durably (for example, into a Postgres outbox) before being indexed into the rebuildable automation queue projection.
- `TICK_HANDOFF` – every required child dispatch was durably accepted by Game Session (the point at which `finalOutcome=handoff_accepted` is allowed).
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

- `finalOutcome=handoff_accepted` must imply `finalStage=TICK_HANDOFF` and durable acceptance of every required child dispatch. It does not imply gameplay application.
- A valid live handler that intentionally emits no commands uses `finalStage=DSL_EVAL`, `finalOutcome=completed_no_commands`.
- Tenant-readiness `onLoad` completion must use `finalStage=DSL_EVAL` and `finalOutcome=readiness_success`; a valid live handler emitting no commands uses `finalOutcome=completed_no_commands`; neither is a live gameplay handoff signal.
- `finalOutcome=dry_run_success` must imply `finalStage=DRY_RUN_RESULT` and `isDryRun=true`. It means only that the non-committing test evaluation completed and returned inspectable would-be commands.
- Backpressure outcomes like `skipped_reloading` must use `finalStage=ADMISSION`.
- Post-resolution handler rollback pause backpressure `rollback_paused` must use `finalStage=ADMISSION`; for event ingress, pre-handler rollback pause is recorded only as the event-scope ingress outcome in `script_event_ingress_audit`, while timer candidates use the `scheduleCandidateId` candidate-audit outcome. Evaluated descriptors canceled from `PENDING` or `INDEXED` use `finalStage=WORK_ITEM_PERSIST`, `finalOutcome=canceled`, and the bounded cancellation reason.
- Quota denials must use `finalStage=ADMISSION` unless quotas are evaluated inside the DSL runtime for a given trigger (rare; avoid mixing).
- Intentional rollback/control-plane fencing after admission must stay visible as `finalOutcome=canceled` at the last attempted live stage, with bounded `finalReason` values such as `rollback_epoch_advanced`, `superseded_by_newer_patch`, `operator_canceled`, or `operator_purged`.

### Per-Command Handoff and Post-Handoff Outcomes (Required When Present)

`script_event_audit` is authoritative only for the Automation-owned handler pipeline through `TICK_HANDOFF`; it is not the sole post-handoff surface and it must not contain a single disposition for a fan-out trigger. Every emitted command has one durable child dispatch record. In the target state, `ListScriptHandoffEvents` is the canonical durable query for those per-command records: an initial handoff record is retained for every emitted command, and later Game Session acceptance, rejection, or execution results update or extend that command's disposition. A combined trigger read exposes those records as `commandHandoffDispositions[]`, with one element per emitted command keyed by the complete Command-Handoff Identity. Each child retains the parent Trigger Identity, including plugin `bindingId` when applicable; tooling must not rely on metrics alone to correlate the records back to the original trigger.

When a downstream service reports a later handoff or execution result, the target-state command-handoff surface must expose or update a child disposition keyed to the affected complete Command-Handoff Identity, including its `(automationDispatchId, commandOrdinal)` dispatch-group fields, with:

- `automationDispatchId` – the stable dispatch-group identifier shared by the emitted gameplay commands; `commandOrdinal` distinguishes each command under it within the complete command-handoff scope.
- `commandOrdinal` – the deterministic ordinal of that emitted command within the handler handoff.
- `gameSessionCommandId` – the Game Session command identity when assigned; it is `null`/absent while the child has not been accepted into Game Session.
- `handoffOutcome` – the durable handoff result for this child, present after a handoff attempt and independent of later gameplay execution.
- `executionOutcome` – the authoritative Game Session execution lifecycle result when available; it remains `null`/absent until execution reaches a result or terminal disposition.
- `gameplayResult` – the terminal gameplay result when available, using the canonical uppercase vocabulary `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, or `NOT_APPLIED`; it remains `null`/absent while execution is unresolved.
- `commandStatusLink` – a link or stable reference to the authoritative Game Session command-status record when `gameSessionCommandId` is assigned; it is `null`/absent before that point.
- `outcome` – closed target enum: `accepted`, `rejected`, `execution_updated`, or `version_fence_dropped`. `accepted` and `rejected` are handoff dispositions; `execution_updated` points to the separate authoritative `executionOutcome` and `gameplayResult` fields; `version_fence_dropped` records execution-time fencing. Terminality is established only by those separate authoritative fields, not by `outcome` alone.
- `reason` – bounded reason such as `script_patch_mismatch` or `plugin_version_mismatch`.
- `recordedAt` – timestamp.
- `sourceService` – producer of the disposition (for example `game-session`).

`outcome` and `reason` describe the child handoff disposition and remain separate from effect status, `executionOutcome`, and `gameplayResult`. In particular, `outcome=version_fence_dropped` (and its reason) does not by itself prove that gameplay was not applied: leave `executionOutcome` and `gameplayResult` `null` while authoritative application evidence is unresolved. Proven no-mutation maps to `executionOutcome=ABANDONED` and `gameplayResult=NOT_APPLIED`; proven commit maps to `executionOutcome=APPLIED` and the canonical command-result rules. A fence disposition must never fabricate terminalization.

Each returned child retains the parent `outboxWorkItemId` only for correlation and retains the complete applicable Trigger Identity needed for diagnosis, including plugin `bindingId` when applicable; the target-state `(automationDispatchId, commandOrdinal)` fields complete that child identity and must not be treated as globally unique or replaced with the parent `scriptEventId`. A pair shown without the Trigger Identity and scope is only a display suffix, never an identity, uniqueness, or deduplication key.

Rules:

- Per-dispatch outcomes do **not** replace `finalStage` / `finalOutcome`; those fields remain the Automation-owned handler pipeline result.
- A handler may therefore show `finalStage=TICK_HANDOFF`, `finalOutcome=handoff_accepted` while one child command is applied and another is abandoned or fenced.
- A handler-level post-handoff summary is a rebuildable projection containing counts and links. It may report all applied, none applied, partial, or abandoned, but never becomes command-outcome authority.
- UI/query surfaces must return the pipeline record, derived summary, and dispatch links together so operators can distinguish durable handoff from later gameplay results.

During rollback, operator views must show the handler's `finalStage`/`finalOutcome` beside the `commandHandoffDispositions[]` returned from `ListScriptHandoffEvents`. A `TICK_HANDOFF` with `finalOutcome=handoff_accepted` therefore remains visible even when one or more individual commands later receive `version_fence_dropped`; a child result must never overwrite the handler result or collapse sibling command records.

Concrete example. Both child records below use the complete `T123` Trigger Identity (`tenantId=11111111-1111-4111-8111-111111111111`, `gameInstanceId=44444444-4444-4444-8444-444444444444`, `playableStateScope=isolated`, `regionId=R2`, `regionEpoch=14`, `entityId=npc-guard-9`, `scriptId=guard-on-enter`, `eventType=onEnterRegion`, `eventSchemaVersion=1`, `scriptPatchVersion=P22`, `scriptPinEpoch=23`, `scriptEventId=evt-7f4c`, `isDryRun=false`) plus the command discriminator. `automationDispatchId=dispatch-9` identifies the dispatch group, while `outboxWorkItemId=work-9` is parent correlation only. The `(automationDispatchId, commandOrdinal)` notation in the bullets is a display suffix, not a standalone key.

- `script_event_audit` row for Trigger Identity `T123` ends with `finalStage=TICK_HANDOFF`, `finalOutcome=handoff_accepted`.
- The handler emitted two commands. Later, Game Session rejects only the child ending in `(automationDispatchId=dispatch-9, commandOrdinal=1)` under the same complete command-handoff scope during rollback convergence and appends a child disposition with `outcome=version_fence_dropped`, `reason=script_patch_mismatch`, `sourceService=game-session`, and `recordedAt=...`; the child ending in `(automationDispatchId=dispatch-9, commandOrdinal=0)` remains a separate sibling record.
- Queries for `T123` must surface the handler row, derived summary, and both authoritative command-handoff records so operators can distinguish durable handoff from the gameplay command that was later fenced.

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
  "scriptPinEpoch": 23,
  "scriptEventId": "evt-7f4c",
  "isDryRun": false,
  "finalStage": "TICK_HANDOFF",
  "finalOutcome": "handoff_accepted",
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
      "outcome": "handoff_accepted",
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
      "scriptPinEpoch": 23,
      "scriptEventId": "evt-7f4c",
      "isDryRun": false,
      "commandOrdinal": 0,
      "automationDispatchId": "dispatch-9",
      "outboxWorkItemId": "work-9",
      "playableStateScope": "isolated",
      "gameSessionCommandId": "gs-cmd-0",
      "handoffOutcome": "accepted",
      "executionOutcome": null,
      "gameplayResult": null,
      "commandStatusLink": "/commands/gs-cmd-0",
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
      "scriptPinEpoch": 23,
      "scriptEventId": "evt-7f4c",
      "isDryRun": false,
      "commandOrdinal": 1,
      "automationDispatchId": "dispatch-9",
      "outboxWorkItemId": "work-9",
      "playableStateScope": "isolated",
      "gameSessionCommandId": "gs-cmd-1",
      "handoffOutcome": "accepted",
      "executionOutcome": null,
      "gameplayResult": null,
      "commandStatusLink": "/commands/gs-cmd-1",
      "outcome": "version_fence_dropped",
      "reason": "script_patch_mismatch",
      "sourceService": "game-session",
      "recordedAt": "2026-03-19T08:10:03Z"
    }
  ]
}
```

This target-state example is illustrative rather than prescriptive about JSON column layout, but any API or query surface must preserve the same information model: one Trigger Identity, one Automation-owned handler final stage/outcome, and zero or more later per-command handoff dispositions keyed by the complete Command-Handoff Identity.

### Canonical Outcome Taxonomy (Required)

Audit writers must use the canonical `finalOutcome` values defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative`.

In particular:

- Use `version_unavailable` (never `skipped_version_unavailable`).
- Encode specific cause in `finalReason` (for example `onload_failed`, `plugin_version_failed`, `script_patch_missing`).
- Use `pin_state_unavailable` when a resolved handler's admission fails closed because bounded-staleness pin data cannot be refreshed; pre-resolution failures use the event-scope mapping in the normative contract tables.
- Use `signer_policy_unavailable` for a resolved handler's admission failure after binding resolution when signer policy cannot be refreshed/verified from authoritative policy sources; pre-handler signer-policy unavailability uses the event-scope mapping in the normative contract tables and is not a handler `finalOutcome`.
- Use `script_disabled` for operator disable/drain admission skips (never `skipped_disabled`).
- A rollback convergence timeout is an event-scope ingress decision, not a handler `finalOutcome`: return `admitted=false` with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK` and `admissionReason=rollback_convergence_timeout`, and record the same pair in `script_event_ingress_audit`.
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

- If all entries have `decision=REPORT_ONLY`, execution may continue and `finalOutcome` must still represent the stage-qualified pipeline result (`handoff_accepted`, `completed_no_commands`, `sandbox_error`, `infrastructure_error`, and so on). `finalOutcome=plugin_component_blocked` is not valid in this case.
- If any entry has `decision=BLOCKED`, admission must stop with `finalStage=ADMISSION` and `finalOutcome=plugin_component_blocked`.
- The Table 4 plugin-policy metric consequence is emitted in both report-only and enforcing modes so operators can compare rollout behavior before and after enforcement.

## Metrics Consequences (Table 4-Owned Schema)

The metric-family catalog, labels, and increment units live exclusively in [Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix). This section records illustrative observability consequences and local diagnostic guidance; it does not define or extend metric schemas.

- Dry-run/test executions remain separate from live dashboards and SLOs. Use the Table 4 dry-run/test consequences for materialized handler attempts and its single ingress-drop consequence for pre-handler denials; the handler audit row remains the source of per-execution detail.

Metric semantics:

- Event-scope admission, skip, and drop outcomes remain distinct from resolved handler outcomes. A live admitted event that resolves zero handlers has only the bounded, metric-only `outcome="admitted_no_handlers"` consequence defined by Table 4, with `script_category="UNRESOLVED"` and no `plugin_family` or `plugin_version_family`; it is not returned in an ingress response or written to any `script_event_ingress_audit` or `script_event_audit` field.
- Each rejected pre-handler event has the Table 4 ingress-drop consequence with its bounded event-scope `admissionReason`, including `signer_policy_unavailable` when applicable. It does not imply a handler audit row or handler `finalOutcome`.
- The Table 4 rollback-drain metric consequence counts old-epoch executions intentionally fenced during rollback draining before live work could persist or hand off. Use it for bounded rollback-drain visibility rather than a generic infrastructure failure counter.
  It is not the counter for ordinary operator-initiated cancel/purge actions on not-yet-running work items unless those items had already crossed into execution and were then fenced by rollback epoch advancement.

## Required Links

This contract is referenced by:

- [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md)
- [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md#document-map)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
