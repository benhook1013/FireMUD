# FireMUD Scripting & Automation: Normative Contract Tables

This document centralizes the **normative tables** for scripting and automation contracts so other design docs do not drift.

If another document conflicts with these tables (field names, required identifiers, timer semantics, or metric label sets), treat this document as the tie-breaker.

## Document Precedence (Normative)

When documents disagree, resolve conflicts in this order:

1. `system-architecture-scripting-normative-contract-tables.md` (this document)
2. `system-architecture-scripting-observability-contract.md` (metric/audit behavior not fully enumerated in tables)
3. `system-architecture-scripting-contracts.md` (cross-service invariants)
4. `system-architecture-scripting-control-plane-api.md`, `system-architecture-scripting-control-plane-events.md`, and `system-architecture-scripting-control-plane-operations.md` (operator API shapes, event families, and workflow sequencing)
5. DSL/service/hub docs (`system-architecture-scripting-dsl-reference-and-lifecycle.md`, service READMEs, and overview hubs)

## Table of Contents

- [Table 1: Trigger Identity (Required Fields)](#table-1-trigger-identity-required-fields)
- [Table 1A: Event Ingress `scriptEventId` Ownership Matrix](#table-1a-event-ingress-scripteventid-ownership-matrix)
- [Table 2: `script_event_audit` Stages and Outcomes](#table-2-script_event_audit-stages-and-outcomes)
- [Table 3: Timer Semantics Matrix](#table-3-timer-semantics-matrix)
- [Table 4: Metrics Label Matrix](#table-4-metrics-label-matrix)

---

## Table 1: Trigger Identity (Required Fields)

Trigger Identity is the composite idempotency identity for “evaluate this resolved handler for this trigger at most once” and the natural primary key for handler-scoped `script_event_audit` records. `scriptEventId` is one required field, not a substitute for all other applicable identity fields. Incoming requests use the separate event-scope identity defined below until handler resolution completes.

Event-ingress RPCs into Automation & Scripting (for example `TriggerScriptEvent`) use two-stage deduplication:

- Before handler resolution, incoming request dedupe uses the event-scope identity. `scriptId` and `bindingId` are unavailable at ingress and must not be invented or used as synthetic identity fields.
- After binding resolution, each resolved handler dedupes independently by its full applicable Trigger Identity. Retries of that handler must reuse the same identity fields, including `scriptEventId`.

Required identity fields for all triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `tenantId` | Yes | Tenant/project identity. |
| `gameInstanceId` | Yes | Required to avoid collisions when a tenant has multiple instances. Must be carried through audit and downstream idempotency. |
| `playableStateScope` | Yes (gameplay/runtime) | Resolved admitted gameplay-state namespace. Shared and isolated realm state must not collide even when other runtime identity fields match. |
| `regionId` | Yes (gameplay/runtime) | Region context for the trigger. If a trigger is not region-scoped (rare), it must declare its own scope explicitly and still include `gameInstanceId`. |
| `regionEpoch` | Yes (gameplay/runtime) | Required for any trigger emitted from, aligned to, or fenced by the tick timeline. This includes standard gameplay lifecycle events emitted after tick commit (for example `onEnterRegion`, `onSpawn`, `onCommand`) and all scheduler/timer events. |
| `entityId` | Yes (entity-scoped) | Required for entity-scoped triggers. |
| `scriptId` | Yes | The script definition identity that will run. If bindings fan out, each `<scriptId>` is a distinct Trigger Identity. |
| `eventType` | Yes | Logical event key (for example `onEnterRegion`, `onInterval`). |
| `eventSchemaVersion` | Yes | Version of the event and payload contract. Different schema versions must not share Trigger Identity. |
| `scriptPatchVersion` | Yes | Pinned patch version for the game instance at the time the trigger is emitted. |
| `scriptEventId` | Yes | Idempotency token within the full applicable Trigger Identity. Live ingress uses caller-supplied IDs that must be stable across retries. Dry-run/test ingress may use server-generated IDs by default. Must not be used as a Prometheus label. |
| `isDryRun` | Yes | Required identity dimension for dedupe/audit namespace separation. Dry-run/test and live traffic must never share the same idempotency namespace. |

Additional required fields for plugin triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `pluginId` | Yes (plugin triggers) | Required to distinguish plugin-triggered runs from core scripts when the same `scriptId` model is reused. |
| `pluginVersionId` | Yes (plugin triggers) | Required for rollback safety, audit correlation, and version-fence drops. |
| `bindingId` | Yes (resolved plugin handlers) | Stable signed-bundle binding identity. Required after handler resolution so multiple handlers contributed by one plugin version cannot alias in dedupe, audit, quota, timer firing, or handoff state. |

Additional required fields for scheduler/timer triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `scheduleDefinitionId` | Yes | Identifies the pinned schedule definition whose due firing produced the trigger and prevents distinct schedules with otherwise identical trigger fields from colliding. |
| Exactly one of `dueTickId` or `dueAt` | Yes | A tagged due point is required so scheduler `scriptEventId` can be deterministic and catch-up behavior can be audited. The alternate field must be absent/`NULL`; both populated or both absent are invalid for scheduler triggers. |
| `triggerMode` | Yes | `NORMAL` vs `CATCH_UP` to make bounded catch-up observable. |

Additional required fields and exceptions for tenant-readiness lifecycle triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `tenantId` | Yes | Tenant whose patch is being validated. |
| `scriptId` | Yes | Script definition running readiness initialization. |
| `eventType` | Yes | Must be `onLoad`. |
| `eventSchemaVersion` | Yes | Must identify the admitted `onLoad` event contract version. |
| `scriptPatchVersion` | Yes | Pending patch being validated for tenant readiness. |
| `scriptEventId` | Yes | Deterministically generated by Automation & Scripting from all non-generated applicable onLoad identity fields, including `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun=false>`. |
| `isDryRun` | Yes | Must be `false` for readiness `onLoad`; dry-run/test `onLoad` probes use the dry-run/test ingress contract instead. |
| `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId` | No | `onLoad` is tenant-readiness work and is not tied to a running instance, gameplay-state namespace, region, tick epoch, or entity. These fields must be absent rather than populated with sentinel values. |

Event-scope ingress identity before handler resolution:

| Field | Required | Notes |
| --- | --- | --- |
| `tenantId` | Yes | Tenant/project identity. |
| `gameInstanceId` | Yes (runtime ingress) | Required for runtime ingress fences such as reload, rollback pause, and pin visibility. |
| `playableStateScope` | Yes (gameplay/runtime ingress) | Resolved admitted gameplay-state namespace carried before handler fan-out. |
| `regionId`, `regionEpoch`, `entityId` | Yes when the incoming event is gameplay/runtime-scoped | These fields describe the source event before handler fan-out. |
| `eventType` | Yes | Logical event key being admitted for handler resolution. |
| `eventSchemaVersion` | Yes | Version of the event and payload contract being admitted. |
| `scriptPatchVersion` | Yes | Patch version supplied by the producer for pin/version checks. |
| `scriptEventId` | Yes | Caller-supplied live ingress idempotency token, or service-generated dry-run/test token. |
| `isDryRun` | Yes | Separates live and dry-run/test ingress namespaces. |

Event-scope ingress outcomes are recorded in the existing `script_event_ingress_audit` surface using its event-scope identity, `scriptEventId` correlation, `createdAt` retention anchor, and `admissionOutcome`/`admissionReason` fields, not in handler-scoped `script_event_audit`. Once the event is accepted for handler resolution, each resolved handler produces its own full applicable Trigger Identity and `script_event_audit` row. Pre-resolution denials such as auth failure, stale pin state, reload backpressure, rollback pause, or version unavailability must not invent a synthetic `scriptId` or `bindingId`.

Signer-policy unavailability before handler resolution is an event-scope denial: return `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE` with bounded `admissionReason=signer_policy_unavailable`, record the same pair in `script_event_ingress_audit`, and do not create a handler-scoped `finalOutcome` or `script_event_audit` row. If signer policy is evaluated after binding resolution for a concrete handler, the handler-scoped outcome remains `finalOutcome=signer_policy_unavailable` under Table 2.

Dry-run/test budget exhaustion before handler resolution is an event-scope denial: return `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED` with bounded `admissionReason=dry_run_budget_exceeded`, record the same pair in `script_event_ingress_audit`, and do not create a handler-scoped `finalOutcome` or `script_event_audit` row.

Notes:

- If an implementation wants to treat some gameplay lifecycle events as “not tick-aligned”, it must define that exception explicitly in this table (not only in prose docs) and document why it is safe across scoped coordination resets. The default contract is that gameplay lifecycle triggers are fenced by `regionEpoch`.
- Dry-run/test ingress must use a namespace separate from live ingress (`isDryRun=true`) regardless of whether `scriptEventId` is caller-generated or server-generated.
- Downstream service calls made from scripts must propagate a per-command idempotency token derived from the full applicable Trigger Identity plus tick context when applicable (for example `tickId`), the emitted command's `automationDispatchId` (or an equivalent command-level identity), and the target aggregate type and ID, following `design/architecture/system-architecture-transactions.md`; `scriptEventId` alone is insufficient for fan-out.

## Table 1A: Event Ingress `scriptEventId` Ownership Matrix

The ingress endpoint determines who owns `scriptEventId` generation and retry behavior. Implementations must reject requests that violate these ownership rules.

| Ingress surface | `scriptEventId` owner | Required behavior |
| --- | --- | --- |
| Live external ingress (`TriggerScriptEvent`) | Caller | Caller must supply `scriptEventId` and must reuse it across retries for the same trigger identity. Missing ID is a deterministic validation error. |
| Scheduler/timer internal ingress (`onInterval`, `onTimerExpire`) | Automation scheduler | Scheduler must generate deterministic IDs from exactly one tagged due-point identity (`dueTickId` or `dueAt`) plus all required Trigger Identity fields. |
| Tenant-readiness lifecycle ingress (`onLoad`) | Automation readiness worker | Worker must generate deterministic IDs from all non-generated applicable fields, including `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun=false>`. The DSL evaluation is at-most-once; bounded retries are permitted only for independently idempotent external infrastructure steps and reuse the same persisted ID. Superseded patches must not mint replacement IDs for the same readiness tuple. |
| Dry-run/test ingress (`RunScriptDryRun` or equivalent) | Service/test harness by default | Service generates `scriptEventId` by default. If caller-supplied IDs are accepted, service must validate dry-run namespace and reject collisions deterministically. |

## Table 2: `script_event_audit` Stages and Outcomes

`script_event_audit` must be stage-aware so operators can distinguish handler-scoped “rejected before evaluation” from “evaluated but not handed off” and from “accepted into tick queues”. Pre-handler event-scope rejections are represented by `script_event_ingress_audit` and its `admissionOutcome`/`admissionReason` fields instead.

### Required Stage Set

| `finalStage` | Meaning | “Success” allowed? |
| --- | --- | --- |
| `ADMISSION` | Pre-evaluation decisions: quotas, reload backpressure, disabled scripts, invalid version, policy enforcement. No DSL run occurs. | No |
| `DSL_EVAL` | DSL graph evaluation and sandbox enforcement (validation, loop safety, runtime budgets). | No |
| `WORK_ITEM_PERSIST` | Durable persistence of the resulting work item (outbox). | No |
| `TICK_HANDOFF` | Handoff to Game Session and acceptance into tick queues. | Yes |
| `DRY_RUN_RESULT` | Non-committing dry-run/test result after DSL evaluation; would-be commands are returned to the authorized caller and are not persisted or handed off. Allowed only when `isDryRun=true`. | Yes, but only with `finalOutcome=dry_run_success`. |

### Required Audit Write Semantics (Normative)

`script_event_audit` writes must be deterministic under retries and concurrent updates:

- There must be at most one row per full Trigger Identity (Table 1 + plugin/timer required fields where applicable).
- Implementations must enforce uniqueness at storage level (composite unique key over Trigger Identity).
- Retries and duplicate deliveries must update the existing row instead of inserting a new one.
- Stage progression must be monotonic for live runs (`ADMISSION` <= `DSL_EVAL` <= `WORK_ITEM_PERSIST` <= `TICK_HANDOFF`); writers must not regress `finalStage`. Dry-run/test runs use the non-committing terminal branch `ADMISSION` <= `DSL_EVAL` <= `DRY_RUN_RESULT` and must never progress to `WORK_ITEM_PERSIST` or `TICK_HANDOFF`.
- On conflicting updates, the higher stage wins; if stages are equal, preserve the first terminal non-success outcome unless a later write provides a strictly higher-fidelity reason for the same stage.

### Required Outcome Rules (Normative)

| Stage | Required rule |
| --- | --- |
| `ADMISSION` | Must record explicit backpressure outcomes during `reloadState=RELOADING` (`finalOutcome=skipped_reloading`) and `PAUSED_FOR_ROLLBACK` (`finalOutcome=rollback_paused`) rather than silent drops. |
| `DSL_EVAL` | Sandbox failures must be recorded as `finalOutcome=sandbox_error` with a specific `finalReason` (for example `cpu_budget_exceeded`, `memory_budget_exceeded`). |
| `WORK_ITEM_PERSIST` | If durable persistence fails, the audit record must not show success. It must record a persistence failure outcome and must not claim that effects were enqueued. |
| `TICK_HANDOFF` | `finalOutcome=success` is permitted only when Game Session has accepted commands into tick queues. “DSL evaluated successfully but handoff failed” must be a non-success handoff outcome. |
| `DRY_RUN_RESULT` | `finalOutcome=dry_run_success` is permitted only for authorized `isDryRun=true` executions after DSL evaluation completes and the non-committing result has been returned or stored for inspection. It must not imply durable work-item persistence or tick handoff. |

Additional non-committing terminal outcome rules:

- Tenant-readiness `onLoad` completion must use `finalStage=DSL_EVAL`, `finalOutcome=readiness_success`, and a bounded `finalReason` such as `ready_for_tenant`. It must not use live `finalOutcome=success`, because no gameplay work item or tick handoff exists for readiness-only execution.
- Control-plane or rollback fencing that intentionally prevents an already admitted execution from persisting or handing off must use `finalOutcome=canceled` with a bounded `finalReason` such as `rollback_epoch_advanced`, `superseded_by_newer_patch`, `operator_canceled`, or `operator_purged`.

Supplementary post-handoff correlation rule:

- **Target-state command-ordinal contract:** execution-time version/plugin fence drops that happen after tick handoff must not be left as metrics-only signals. They must be exposed on the affected per-command handoff disposition keyed by `(automationDispatchId, commandOrdinal)`, with the parent Trigger Identity retained for correlation, using bounded reasons such as `script_patch_mismatch` or `plugin_version_mismatch`.
- **Current live fallback:** the Game Session handoff currently carries `automationDispatchId`, command id/text, selected provenance fields, and parent work-item correlation, but not `commandOrdinal` or the complete Trigger Identity. Current diagnostics use those fields and must be labeled as the narrower fallback rather than as proof of the target-state contract.

Per-command handoff correlation rule:

- `script_event_audit` remains one handler record per Trigger Identity, even when that handler emits multiple gameplay commands. It must not contain a single command dispatch field or a single post-handoff outcome for the whole Trigger Identity.
- **Target-state:** handoff and later execution dispositions are represented as a child/collection surface with one record per emitted command. One stable `automationDispatchId` identifies the persisted handoff/work item, and `commandOrdinal` distinguishes each emitted command under that dispatch. Each record is keyed by `(automationDispatchId, commandOrdinal)` and retains the parent `outboxWorkItemId` and complete Trigger Identity for correlation. `ListScriptHandoffEvents` is the canonical query surface for these records.
- A handler may therefore have zero, one, or many command-handoff records; a later version-fence drop on one command must not overwrite or summarize the handler audit row or the dispositions of sibling commands.

### Canonical `finalOutcome` Values (Normative)

Use a single canonical outcome taxonomy across docs, protos, metrics, and dashboards. Aliases are not allowed in new writes.

Taxonomy governance rule:

- Keep `finalOutcome` intentionally small and stable; add a new canonical value only when operator behavior, routing, or alert semantics materially change. Use `finalReason` for finer-grained diagnosis.
- Event-scope admission failures that occur before handler resolution are recorded in the ingress response and `script_event_ingress_audit`, not in this handler-scoped taxonomy. In particular, pre-resolution unavailable pin state uses `TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE` / `pin_state_unavailable`, a requested-versus-observed pin mismatch uses `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` / `pin_state_mismatch_requested_vs_observed`, signer-policy unavailability uses `TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE` / `signer_policy_unavailable`, and an active rollback convergence timeout uses the existing `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK` / `rollback_convergence_timeout` pair. After handler resolution, unavailable pin state and signer-policy unavailability remain handler-scoped with `finalStage=ADMISSION` and their corresponding `finalOutcome` values.

| Canonical value | Stage | Notes |
| --- | --- | --- |
| `success` | `TICK_HANDOFF` | Commands accepted into tick queues. |
| `readiness_success` | `DSL_EVAL` | Tenant-readiness `onLoad` completed successfully and contributed to patch readiness. No work item or tick handoff was created. |
| `dry_run_success` | `DRY_RUN_RESULT` | Non-committing dry-run/test execution completed and returned would-be commands for inspection. |
| `skipped_reloading` | `ADMISSION` | Explicit reload backpressure; caller may retry with same Trigger Identity if policy allows. |
| `rollback_paused` | `ADMISSION` | Explicit rollback backpressure while control-plane rollback pause is active. |
| `quota_denied` | `ADMISSION` | Script quota or concurrency/capacity denial before DSL evaluation. |
| `tenant_budget_exceeded` | `ADMISSION` | Tenant budget exhausted. |
| `version_unavailable` | `ADMISSION` | Unknown/failed/not-ready patch or plugin version. |
| `pin_state_unavailable` | `ADMISSION` | Handler-scoped admission fails closed after handler resolution when fresh instance pin state cannot be obtained. Pre-resolution pin-state failures remain event-scope ingress records. |
| `signer_policy_unavailable` | `ADMISSION` | Plugin admission fails closed because signer policy cannot be refreshed/verified from authoritative policy sources. |
| `plugin_component_blocked` | `ADMISSION` | Plugin rejected by component policy. |
| `plugin_disabled` | `ADMISSION` | Plugin disabled or draining state. |
| `script_disabled` | `ADMISSION` | Script disabled or draining due to operator action. |
| `sandbox_error` | `DSL_EVAL` | Runtime or guard failure; reason required. |
| `validation_error` | `DSL_EVAL` | Static/semantic validation failure before effect persistence. |
| `canceled` | `ADMISSION`, `DSL_EVAL`, `WORK_ITEM_PERSIST`, or `TICK_HANDOFF` | A scheduler candidate fenced before admission, an `onLoad`/evaluation execution fenced before readiness or descriptor commit, or an already admitted execution intentionally fenced before producing live work or before handoff completed. Use bounded `finalReason` values such as `runtime_scope_changed`, `playable_state_scope_changed`, `rollback_epoch_advanced`, `stale_execution_fenced`, `superseded_by_newer_patch`, `operator_canceled`, or `operator_purged`. |
| `infrastructure_error` | Any non-success stage | Transport/storage/runtime infrastructure failure. |
| `disabled_due_to_errors` | `ADMISSION` | Script disabled by failure-rate policy. |

Deprecated aliases:

- `skipped_version_unavailable` is deprecated. Use `finalOutcome=version_unavailable` with `finalReason` for specificity.

### Required Cleanup Rule for Version Fencing

If Game Session rejects a queued command because its embedded `scriptPatchVersion` does not match the currently pinned patch (or a plugin-produced command does not match the currently active `pluginVersionId` for its `pluginId`), it must:

- Record the drop on the affected command-handoff record with identifiers sufficient for diagnosis (including `automationDispatchId`, `outboxWorkItemId`, `scriptEventId`, `scriptId`, `scriptPatchVersion`, `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `entityId`, plus `pluginId`, `pluginVersionId`, and `bindingId` for a plugin handler).
- Remove the rejected queue entry (or move it to a bounded dead-letter store) so mismatched entries cannot accumulate unboundedly after a rollback.
- Emit an operator-visible metric for version-fence drops:
  - `automation_tick_version_fence_dropped_total{scope, script_category, reason}` for script patch mismatches (for example `reason="script_patch_mismatch"`).
  - `automation_tick_plugin_version_fence_dropped_total{scope, plugin_family, plugin_version_family, reason}` for plugin version mismatches (for example `reason="plugin_version_mismatch"`).
- Dead-letter retention for rejected queue entries must be bounded and explicit:
  - `maxAge` and `maxRows` must be documented per environment.
  - Cleanup cadence must be documented and alert-backed.
  - Breaching thresholds must emit operator-visible alerts.

## Table 3: Timer Semantics Matrix

Timer-driven handlers (`onInterval`, `onTimerExpire`) are best-effort, at-most-once per Trigger Identity.

The matrix below defines what the scheduler does when a firing becomes due under different conditions:

| Condition | Behavior | Audit requirements |
| --- | --- | --- |
| Normal operation | Evaluate once per due candidate under budgets and quotas; only an admitted candidate becomes a firing. | An admitted firing receives one `script_event_audit` row per handler-scoped Trigger Identity with stage-aware outcomes. |
| Quota/budget denied | Skip the candidate; do not create a firing claim or replay it later. | Record an event-scope candidate audit keyed by deterministic `scheduleCandidateId`, with `finalStage=ADMISSION` and an explicit deny outcome/reason. |
| `reloadState=RELOADING` | Do not admit new timer firings; do not backfill by default. | Record the non-admitted due candidate under deterministic `scheduleCandidateId` with `finalStage=ADMISSION` and `finalOutcome=skipped_reloading`; do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| `PAUSED_FOR_ROLLBACK` | Do not admit new timer firings while rollback cleanup and repin complete. | Record the non-admitted due candidate under deterministic `scheduleCandidateId` with `finalStage=ADMISSION` and `finalOutcome=rollback_paused`; do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Leader failover / short downtime | May perform bounded catch-up for missed cadence boundaries: at most one synthetic firing per cadence boundary crossed, and never more than `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` for a resume window. Excess candidates are coalesced/dropped and never enqueued as triggers. | Catch-up firings must use `triggerMode=CATCH_UP` and deterministic `scriptEventId` derived from the due point plus all applicable Trigger Identity fields. Truncated catch-up must emit an operator-visible metric and bounded reason code. |
| Runtime scope / epoch change before due-point admission | Do not remint stale due points under the newer `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)` timeline. A `playableStateScope` change is a schedule/runtime migration fence even when the other runtime fields are unchanged: create or confirm the new scope-owned schedule entry before retiring the old entry, as one atomic durable result or a resumable idempotent operation, then advance to the new scope's next valid due point without reusing the stale one. | Record the dropped candidate at event scope under deterministic `scheduleCandidateId` with `finalStage=ADMISSION`, `finalOutcome=canceled`, and `finalReason=runtime_scope_changed` (or `playable_state_scope_changed` for that specific fence), and emit the runtime-fence metric. Because admission did not occur, do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Preserved timer across reload/rollback | Recalculate the next due point from the canonical resume formula using `resumeTickId`, `previousDueTickId`, and cadence; do not replay the paused window. If the cadence boundary is exactly `resumeTickId`, the firing is due immediately and must not be advanced by one interval. | The preserved firing cadence must remain derivable from durable schedule metadata and the documented resume rule. |
| Long downtime or sustained overload | No guarantee of eventual execution for every firing; the system converges by running future firings once capacity returns. | Missed firings must be visible as skips/drops in metrics and audit. |
| Infrastructure error after admission | Do not re-run the DSL body for the same full applicable Trigger Identity. Only idempotent downstream ops may retry; `scriptEventId` alone is not an execution identity. | `finalStage` must reflect where it failed; do not record `success`. |

### Canonical Preserved-Timer Resume Formula (Normative)

For `intervalTicks > 0`, let `remainder = (resumeTickId - previousDueTickId) % intervalTicks`:

- If `previousDueTickId > resumeTickId`, keep `nextTick = previousDueTickId`.
- Otherwise, if `remainder = 0`, set `nextTick = resumeTickId` so an exact cadence boundary can fire immediately.
- Otherwise, set `nextTick = resumeTickId + intervalTicks - remainder`.

This formula advances to the first valid cadence boundary at or after resume without replaying the paused window. A modulo-zero result is the boundary case, not a reason to skip to the following interval.

### Version-Owned Durable Schedule Migration (Normative)

- `scheduleDefinitionId` is the stable logical identity used to decide whether old and new definitions represent the same schedule. It is not the durable row or trigger-claim identity.
- A change to `scriptPatchVersion`, `playableStateScope`, target scope, or, for a plugin-owned schedule, `pluginId`/`pluginVersionId`/`bindingId` is a schedule/runtime migration fence. Reconciliation must create or confirm the new owner/scope entry before retiring the old entry, as one atomic durable result or a resumable idempotent operation. The new entry may carry forward due state only when the complete immutable reconciliation identity matches the requested mapping: `<tenantId, gameInstanceId, playableStateScope, targetScopeType, targetScopeId, scriptId, eventType, eventSchemaVersion, isDryRun, scheduleDefinitionId, scheduleSemanticsHash, pluginId?, displacedScriptPatchVersion, replacementScriptPatchVersion?, displacedPluginVersionId?, replacementPluginVersionId?, bindingId?>`. Both displaced and replacement owner identities must be retained and matched explicitly; any mismatch receives fresh due state. The old entry's owner fields and claim history are immutable.
- The new version/scope-owned entry must derive a new scheduler trigger claim and `scriptEventId`; a matching `scheduleDefinitionId`, due point, handler, or old `playableStateScope` must never be used to reuse a Trigger Identity across the migration.

## Table 4: Metrics Label Matrix

This table defines the authoritative metric-family catalog and label sets for scripting metrics. Metric names and label rules must also follow `design/architecture/system-architecture-scripting-observability-contract.md`.

General rules:

- `scriptEventId` is forbidden as a metric label.
- Raw `tenantId`, `scriptId`, `pluginId`, `pluginVersionId`, and `bindingId` are not approved ordinary Prometheus labels in the canonical repo-wide metrics policy. When this table names those logical dimensions, producers must map them to bounded operator-facing scope/category labels unless a later design update records an explicit exception.

Bounded semantic labels use the existing scripting vocabulary:

- Before handler resolution, pre-handler metric families use the single bounded value `script_category="UNRESOLVED"`; after resolution, handler-scoped metrics use the existing handler types `SCRIPT` and `PLUGIN`.
- `priorityTag` uses `high`, `normal`, or `background`.
- Event-scope `outcome`/`reason` values are bounded `admissionOutcome`/`admissionReason` codes; handler-scoped values are bounded `finalOutcome`/`finalReason` codes. An event-scope metric must not copy handler outcome or reason values.
- `plugin_family` and `plugin_version_family` are optional registry-defined classifications, not raw plugin identifiers. Omit an optional plugin label when the event is not plugin-owned or the classification is unavailable at the event-scope boundary; do not invent `unknown` or `not_applicable` values for this contract.
- Pre-handler metrics omit `plugin_family` and `plugin_version_family` because plugin identity is unavailable before handler resolution. Resolved plugin-handler metrics may include those bounded classifications when available.
- `eventType`, `outcome`, and `scope` likewise use their owning registry, outcome taxonomy, and approved scope vocabularies. A producer must not emit an unbounded raw value.

| Metric family | Required labels | Forbidden labels | Notes |
| --- | --- | --- | --- |
| `automation_script_triggers_total` | `scope`, `script_category`, `eventType`, `outcome`, optional `plugin_family`, `plugin_version_family`, `priorityTag` | `scriptEventId` | Increment exactly once for each rejected pre-handler ingress event using its event-scope admission outcome. After resolution, increment exactly once for each resolved handler using its applicable `finalOutcome`, with no additional admitted-event increment. If an admitted event resolves zero handlers, increment once at event scope with `outcome="admitted_no_handlers"`. |
| `automation_script_skips_total` | `scope`, `script_category`, `reason`, optional `plugin_family`, `priorityTag` | `scriptEventId` | “Skip” is pre-eval. |
| `automation_script_triggers_dropped_total` | `scope`, `script_category`, `reason`, optional `plugin_family`, `priorityTag` | `scriptEventId` | Counts only trigger requests rejected before handler resolution. It does not count handler-level outcomes such as `quota_denied` or post-handoff command drops. Use bounded event-scope `admissionReason` values such as `signer_policy_unavailable`; handler-level `quota_denied` belongs in the handler outcome path (`automation_script_triggers_total{outcome="quota_denied"}` and its handler audit row), without joining or double-counting it here. |
| `script_quota_allowed_total` | `scope`, `script_category` | `scriptEventId` | Quota decisions are pre-eval. |
| `script_quota_denied_total` | `scope`, `script_category`, `reason` | `scriptEventId` | Counts per-script quota decisions only; handler-scoped dry-run capacity denials do not increment this family. |
| `automation_tick_events_enqueued_total` | `scope` | `scriptEventId` | Counts successful tick handoffs, not DSL evaluations. |
| `automation_tick_version_fence_dropped_total` | `scope`, `script_category`, `reason` | `scriptEventId` | Counts commands dropped at execution-time due to script patch version fence mismatches. |
| `automation_tick_plugin_version_fence_dropped_total` | `scope`, `plugin_family`, `plugin_version_family`, `reason` | `scriptEventId` | Counts commands dropped at execution-time due to plugin version fence mismatches. |
| `automation_script_runtime_seconds` | `scope`, `script_category`, `eventType`, optional `plugin_family` | `scriptEventId` | Runtime is sandbox eval time (not tick execution time). |
| `automation_script_sandbox_failures_total` | `scope`, `script_category`, `reason`, optional `plugin_family` | `scriptEventId` | N/A |
| `automation_script_test_runs_total` | `scope`, `script_category`, `eventType`, `result`, optional `plugin_family` | `scriptEventId` | Increment exactly once per materialized handler-scoped dry-run/test execution attempt, including a post-materialization capacity denial or execution failure. Pre-resolution denials use the ingress/drop metrics and do not increment this family; completion must not increment it again. Keep it separate from live-traffic counters. |
| `automation_script_test_runtime_seconds` | `scope`, `script_category`, `eventType`, optional `plugin_family` | `scriptEventId` | Dry-run/test runtime latency; must remain separate from live runtime histograms. |
| `automation_script_test_sandbox_failures_total` | `scope`, `script_category`, `eventType`, `reason`, optional `plugin_family` | `scriptEventId` | Dry-run/test-only sandbox failures; must not increment live sandbox failure counters. |
| `automation_script_test_capacity_denied_total` | `scope` | `scriptEventId` | Counts handler-scoped dry-run capacity denials. `scope` is bounded to `tenant` or `cluster`; this is not a per-script quota decision. |
| `automation_script_timer_catchup_truncated_total` | `scope`, `script_category`, `eventType`, `reason` | `scriptEventId` | Counts catch-up firings that were intentionally truncated/dropped by resume-window limits. |
| `automation_script_timer_runtime_fence_dropped_total` | `scope`, `script_category`, `eventType`, `reason` | `scriptEventId` | Counts due points intentionally dropped because runtime scope or epoch changed before the scheduler could admit them. |
| `automation_rollback_convergence_timeout_total` | `scope`, `operation`, `reason` | `scriptEventId` | Incremented when rollback orchestration reaches timeout terminal state before convergence acknowledgment. |

## Documentation Drift Guardrails

To keep contracts consistent across docs:

- `design/` markdown validation in CI must include a scripting-contract lint pass (for example `./gradlew lintMarkdown` invoking `dev-tools/docs/lint-scripting-contracts.sh`) that fails on:
  - Deprecated aliases in normative fields (for example `skipped_disabled`, `skipped_version_unavailable`).
  - Conflicting ownership language for ingress identity fields (for example `scriptEventId`).
  - Multiple documents claiming incompatible “authoritative/source-of-truth” ownership for the same contract surface.
- The scripting platform maintainers own these lint rules under CODEOWNERS and must update them when canonical contracts change.
- Docs that define non-normative examples must link back to this table for outcome and label names instead of redefining names locally.
