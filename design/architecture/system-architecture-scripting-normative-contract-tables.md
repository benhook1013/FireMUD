# FireMUD Scripting & Automation: Normative Contract Tables

This document centralizes the **normative tables** for scripting and automation contracts so other design docs do not drift.

If another document conflicts with these tables on exact identity fields, audit stage/outcome fields, or metric label sets, treat this document as the tie-breaker. The detailed DSL read-manifest contract is owned by the [DSL reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract); clock, recovery, due-point, and resume behavior is owned by the [scheduler and timers contract](./system-architecture-scripting-scheduler-and-timers.md#target-state-design). Exact pin/epoch, no-degraded-admission, rollback, dead-letter, rollout-history, schedule-continuity, and DSL-lifecycle decisions are recorded in [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](./decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0111](./decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md). This document retains only exact identity projections and the local audit consequences needed to make those owner contracts queryable.

## Implementation Status

These tables define the canonical target-state scripting contracts. Current implementation and proof remain partial; see the [Automation and Scheduler Runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status). Any explicitly labeled current live fallback in this document is implementation evidence and does not narrow or replace the target tables.

## Document Precedence (Normative)

When documents disagree, resolve conflicts in this order:

1. `system-architecture-scripting-normative-contract-tables.md` (this document)
2. `system-architecture-scripting-observability-contract.md` (metric/audit behavior not fully enumerated in tables)
3. `system-architecture-scripting-contracts.md` (cross-service invariants)
4. `system-architecture-scripting-control-plane-api.md`, `system-architecture-scripting-control-plane-events.md`, and `system-architecture-scripting-control-plane-operations.md` (operator API shapes, event families, and workflow sequencing)
5. DSL/service/hub docs (`system-architecture-scripting-dsl-reference-and-lifecycle.md`, service READMEs, and overview hubs)

## Table of Contents

- [Implementation Status](#implementation-status)
- [Table 1: Trigger Identity (Required Fields)](#table-1-trigger-identity-required-fields)
- [Table 1A: Event Ingress `scriptEventId` Ownership Matrix](#table-1a-event-ingress-scripteventid-ownership-matrix)
- [Table 2: `script_event_audit` Stages and Outcomes](#table-2-script_event_audit-stages-and-outcomes)
- [Table 3: Timer Semantics Matrix](#table-3-timer-semantics-matrix)
- [Table 4: Metrics Label Matrix](#table-4-metrics-label-matrix)

---

## Table 1: Trigger Identity (Required Fields)

Trigger Identity is the composite idempotency identity for “evaluate this resolved handler for this trigger at most once” and the natural primary key for handler-scoped `script_event_audit` records. `scriptEventId` is one required field, not a substitute for all other applicable identity fields. Incoming requests use the separate event-scope identity defined below until handler resolution completes. The handler-scoped input manifest defined by the [DSL lifecycle owner](./system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract) is durable evaluation input, not an additional Trigger Identity field; its owner versions and causal floor must be retained with the handler record. For gameplay/runtime work, `scriptPatchVersion` and the Game Session-owned `scriptPinEpoch` are an inseparable exact execution tuple; matching patch text under a different epoch is a fence mismatch.

Ordinary producer event-ingress RPCs into Automation & Scripting (for example `TriggerScriptEvent`) use two-stage deduplication:

- Before handler resolution, ordinary incoming-request dedupe uses the event-scope identity. `scriptId` and `bindingId` are unavailable on that path and must not be invented or used as synthetic identity fields. Tenant-readiness `onLoad` and scheduler/timer due candidates use the explicit owner-known exceptions below.
- After binding resolution, each resolved handler dedupes independently by its full applicable Trigger Identity. The event-scope `scriptEventId` is propagated unchanged into every resolved handler identity; retries of that handler must reuse the same full identity fields and that same ID.

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
| `scriptPinEpoch` | Yes (gameplay/runtime) | Exact Game Session selection epoch paired with `scriptPatchVersion`; advances on every pin change, including rollback or repin to the same version. Absent for tenant-readiness `onLoad`. |
| `scriptEventId` | Yes | One event-scope idempotency token propagated into each full applicable handler Trigger Identity. Live ingress uses caller-supplied IDs that must be stable across retries; scheduler/timer ingress derives one deterministic ID from the due-candidate identity. Dry-run/test ingress may use server-generated IDs by default. Must not be used as a Prometheus label. |
| `isDryRun` | Yes | Required identity dimension for dedupe/audit namespace separation. Dry-run/test and live traffic must never share the same idempotency namespace. |

Additional required fields for plugin triggers:

| Field | Required | Notes |
| --- | --- | --- |
| `pluginId` | Yes (plugin triggers) | Required to distinguish plugin-triggered runs from core scripts when the same `scriptId` model is reused. |
| `pluginVersionId` | Yes (plugin triggers) | Required for rollback safety, audit correlation, and version-fence drops. |
| `bindingId` | Yes (resolved plugin handlers and schedule-owned timer identity) | Stable signed-bundle binding identity. Required after ordinary handler resolution; a scheduler/timer also includes it before resolution when the durable schedule already owns that binding. Multiple handlers contributed by one plugin version cannot alias in dedupe, audit, quota, timer firing, or handoff state. |

Additional required fields for scheduler/timer triggers:

Before handler resolution, a scheduler/timer firing claim is keyed by the complete durable schedule-instance identity, exact `scriptPatchVersion` and `scriptPinEpoch`, applicable runtime scope, target selector, trigger mode, and tagged due point; when `triggerMode=CATCH_UP`, `resumeWindowId` is additionally required and participates in claim uniqueness and `scriptEventId` derivation, and it is absent otherwise. It therefore includes schedule-owned `scriptId` and any schedule-owned `pluginId`, `pluginVersionId`, and `bindingId`; it must not fabricate fields absent from the durable schedule. Candidate audits use that same complete due-candidate identity, including the exact patch and epoch, and likewise include `resumeWindowId` only for `CATCH_UP`. This owner-known firing identity is distinct from the ordinary producer event-ingress key below and derives the one event-scope `scriptEventId` propagated to resolved handlers. Durable schedule rows, due candidates, firing claims, candidate audits, and list/read projections persist and expose the exact epoch; `scriptPinEpoch` remains execution/fence metadata and is excluded from the stable continuity key.

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

Ordinary producer event-scope ingress identity before handler resolution:

| Field | Required | Notes |
| --- | --- | --- |
| `tenantId` | Yes | Tenant/project identity. |
| `gameInstanceId` | Yes (runtime ingress) | Required for runtime ingress fences such as reload, rollback pause, and pin visibility. |
| `playableStateScope` | Yes (gameplay/runtime ingress) | Resolved admitted gameplay-state namespace carried before handler fan-out. |
| `regionId`, `regionEpoch`, `entityId` | Yes when the incoming event is gameplay/runtime-scoped | These fields describe the source event before handler fan-out. |
| `eventType` | Yes | Logical event key being admitted for handler resolution. |
| `eventSchemaVersion` | Yes | Version of the event and payload contract being admitted. |
| `scriptPatchVersion` | Yes | Patch version supplied by the producer for pin/version checks. |
| `scriptPinEpoch` | Yes (gameplay/runtime ingress) | Exact Game Session pin epoch supplied with the patch; absent for tenant-readiness `onLoad`. |
| `scriptEventId` | Yes | Caller-supplied live ingress idempotency token, or service-generated dry-run/test token. |
| `isDryRun` | Yes | Separates live and dry-run/test ingress namespaces. |
| `sourceService` | Yes for custom/service-specific events | Canonical producing service identity derived from the authenticated producer/workload identity and authorized by the event registry. It is not an untrusted caller field. Include the same value in the event-scope key and persist it unchanged in ingress and handler audit records; omit it for built-in events that originate entirely within Automation & Scripting. |

### Remote Follow-Up Tuple Binding (Normative)

A remote follow-up whose source and target game instances differ carries two exact pin roles. The originating `sourceScriptPatchVersion`/`sourceScriptPinEpoch` pair is captured when the source event/follow-up is admitted and remains immutable source provenance and the source rollback fence. Before target-side Automation admission, Game Session revalidates that source pair against the authoritative source instance, reads and freezes the target instance's current exact `scriptPatchVersion`/`scriptPinEpoch` pair, and durably binds both pairs to the target delivery/claim and its immutable request/dedupe evidence. The target pair, not the source pair, populates the target event-scope and resolved-handler Trigger Identity and all target-side script admission and execution fences; the source pair remains separately named provenance/fence and is never substituted into that target identity. Retries reuse both frozen pairs. Source or target displacement, a conflicting retry, or a changed tuple fails closed; unavailable authority is retryable and creates no target admission, while semantic target `UNPINNED` creates no target script work. When source and target are the same game instance, one authoritative exact pair serves both roles and no distinct second tuple is fabricated.
For semantic target `UNPINNED`, the no-script outcome is durable and replayable: it creates no Automation event or target script work. This is distinct from unavailable authority, which remains retryable and non-admitted rather than being finalized as no-script delivery.

### Event-Scope Claim and Retry Semantics (Normative)

The ordinary producer event-scope uniqueness key is the ordered composite of every applicable field in the event-scope ingress identity table:
`<tenantId, gameInstanceId?, playableStateScope?, regionId?, regionEpoch?, entityId?, eventType, eventSchemaVersion, scriptPatchVersion, scriptPinEpoch?, scriptEventId, isDryRun, sourceService?>`.
An optional field is included exactly when its ingress scope requires it; absent optional fields remain absent and must not be replaced with sentinel values. `scriptId`, `pluginId`, `pluginVersionId`, and `bindingId` are not part of this ordinary producer key because they are unavailable before handler resolution. The scheduler/timer and tenant-readiness owner-known exceptions above use their separately defined pre-resolution identities; they do not authorize a producer to supply synthetic handler fields.

For custom or service-specific events, Automation derives `sourceService` from the authenticated producer/workload identity and the event-registry authorization result. The derived value, rather than a caller-supplied value, participates in the event-scope dedupe key and is persisted identically in `script_event_ingress_audit` and every resolved-handler `script_event_audit` record. A changed or unauthorized producer identity is an admission identity conflict, not a retry.

The durable event-scope claim has an `IN_PROGRESS` phase, a retry-eligible transient-denial phase, and a terminal finalized phase. The first claimant must atomically insert-or-claim this key and its single `script_event_ingress_audit` record before performing admission side effects. Only that claimant, or a fenced recovery owner for the same claim, may run handler resolution and fan-out. A claim that passes pre-handler admission remains `IN_PROGRESS` until resolution has completed and every matched handler's idempotent materialization attempt has completed; only then are the final response fields (`admitted`, `admissionOutcome`, `admissionReason`, `retryAfterMs` when present, and `resolvedHandlerCount`) atomically finalized on the claim and audit record. A pre-handler transient backpressure denial may enter the retry-eligible phase only when the owning policy explicitly permits retry: persist the denial/audit and a bounded retry eligibility window exposed by a bounded `retryAfterMs` hint, perform no handler resolution or fan-out, and keep the same event-scope key. After the indicated retry time, a same-key request may atomically reclaim that existing claim and transition it back to `IN_PROGRESS` for a new admission attempt; a lease/fence or compare-and-set must ensure that only one attempt owns the claim. If the bounded eligibility window expires without reclaim, the denial becomes terminal and is replayed exactly like any other permanent denial.

- A concurrent retry or duplicate delivery that finds `IN_PROGRESS` waits for the same claim to finalize, or returns a retryable transport/application signal without an admission result; it must not start resolution, return a partial handler count, or create another ingress-audit row. Recovery resumes the same claim under fencing rather than creating a competing outcome. The same fencing applies while a retry-eligible denial is being reclaimed: losing attempts return the stored denial/hint or wait for the owner, and cannot fan out.
- A retry or duplicate delivery with the same terminal finalized event-scope key returns the exact stored admission result, without re-evaluating admission or materializing duplicate handler work. Permanent denials and successful results are terminal finalized results; a retry-eligible transient denial is the explicit exception and may be reclaimed only through the bounded lifecycle above.
- `resolvedHandlerCount` is the count of enabled bindings that matched and were resolved for the admitted event scope, including dry-run/test claims. It is not a count of persisted work items, emitted commands, successful handlers, or handler audit rows, and it is immutable in the finalized claim result. A resolved handler may have no work item when its handler-scoped admission decision denies it.
- An admitted live claim with zero resolved handlers returns the normal admitted result with `resolvedHandlerCount=0`; `admitted_no_handlers` is emitted only as the Table 4 metric-only outcome. A dry-run/test claim with zero resolved handlers emits no live trigger metric. The finalized value is returned in the ingress response and stored on the claim and `script_event_ingress_audit`; it is not stored in handler-scoped `script_event_audit` rows.
- A request that reuses `scriptEventId` but changes any other applicable key field is an identity conflict, not a retry. Reject it deterministically, preserve the original claim and outcome, and do not materialize handlers.
- A rejected claim has no handler materialization and remains represented only by its one event-scope ingress-audit row.

Event-scope ingress outcomes are recorded in the existing `script_event_ingress_audit` surface using its event-scope identity, `scriptEventId` correlation, `createdAt` retention anchor, and `admissionOutcome`/`admissionReason` fields, not in handler-scoped `script_event_audit`. Once the event is accepted for handler resolution, each resolved handler produces its own full applicable Trigger Identity and `script_event_audit` row. Pre-resolution denials for event ingress, such as auth failure, stale pin state, reload backpressure, rollback pause, or version unavailability, must not invent a synthetic `scriptId`, `bindingId`, or command identity. Scheduler due-candidate skips use the candidate-audit rule in Table 3 instead of event-scope ingress audit.

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
| Scheduler/timer internal ingress (`onInterval`, `onTimerExpire`) | Automation scheduler | Scheduler must generate one deterministic event-scope `scriptEventId` from the complete event-scope due-candidate identity, including runtime scope, event/schema and patch version, schedule identity, exactly one tagged due point (`dueTickId` or `dueAt`), and `triggerMode` (plus `resumeWindowId` for catch-up). It propagates that ID unchanged into every resolved handler's full Trigger Identity and must not require or fabricate handler-only fields before resolution. |
| Tenant-readiness lifecycle ingress (`onLoad`) | Automation readiness worker | Worker must generate deterministic IDs from all non-generated applicable fields, including `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun=false>`. The DSL evaluation is at-most-once; bounded retries are permitted only for independently idempotent external infrastructure steps and reuse the same persisted ID. Superseded patches must not mint replacement IDs for the same readiness tuple. |
| Dry-run/test ingress (`RunScriptDryRun` or equivalent) | Service/test harness by default | Service generates `scriptEventId` by default. If caller-supplied IDs are accepted, service must validate dry-run namespace and reject collisions deterministically. |

## Table 2: `script_event_audit` Stages and Outcomes

`script_event_audit` is limited to a concrete resolved-handler lifecycle: handler-scoped admission and materialized-work decisions, DSL evaluation, persistence, handoff, and dry-run/test results. It must be stage-aware so operators can distinguish handler-scoped “rejected before evaluation” from “evaluated but not handed off” and from “accepted into tick queues”. Pre-handler event-scope decisions for event ingress, including dry-run rejection, signer-policy unavailability, and rollback backpressure, are represented by `script_event_ingress_audit` and its `admissionOutcome`/`admissionReason` fields instead. Scheduler due-candidate skips use the candidate-audit rule in Table 3. A live admitted event that resolves zero handlers is metric-only and is not an ingress- or handler-audit outcome.

### Required Stage Set

| `finalStage` | Meaning | Successful terminal outcome allowed? |
| --- | --- | --- |
| `ADMISSION` | Handler-scoped post-resolution pre-evaluation decisions: quotas, reload backpressure, disabled scripts, invalid version, policy enforcement. No DSL run occurs. For event ingress, pre-handler rollback pause is ingress-only; timer candidates use the Table 3 candidate-audit surface. | No |
| `DSL_EVAL` | DSL graph evaluation and sandbox enforcement (validation, loop safety, runtime budgets). | Only `readiness_success` or `completed_no_commands` in their declared cases |
| `WORK_ITEM_PERSIST` | Durable persistence of the resulting work item (outbox). | No |
| `TICK_HANDOFF` | Durable handoff of every required dispatch to Game Session. | Only `handoff_accepted` |
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
| `ADMISSION` | Must record explicit post-resolution handler backpressure outcomes during `reloadState=RELOADING` (`finalOutcome=skipped_reloading`) and `PAUSED_FOR_ROLLBACK` (`finalOutcome=rollback_paused`) rather than silent drops. For event ingress, a pre-handler rollback pause remains only in `script_event_ingress_audit`; timer candidates use the Table 3 candidate-audit surface. |
| `DSL_EVAL` | Sandbox failures use `finalOutcome=sandbox_error`. A valid live handler that intentionally emits no commands uses `finalOutcome=completed_no_commands`; it does not claim handoff. |
| `WORK_ITEM_PERSIST` | If durable persistence fails, the audit record must not show success. It must record a persistence failure outcome and must not claim that effects were enqueued. |
| `TICK_HANDOFF` | `finalOutcome=handoff_accepted` is permitted only when Game Session has durably accepted every required child dispatch. Evaluation or partial handoff is not handoff acceptance. |
| `DRY_RUN_RESULT` | `finalOutcome=dry_run_success` is permitted only for authorized `isDryRun=true` executions after DSL evaluation completes and the non-committing result has been returned or stored for inspection. It must not imply durable work-item persistence or tick handoff. |

Additional non-committing terminal outcome rules:

- Tenant-readiness `onLoad` completion uses `finalStage=DSL_EVAL`, `finalOutcome=readiness_success`; a live handler that intentionally emits no commands uses `finalOutcome=completed_no_commands`. Neither claims `handoff_accepted`.
- Control-plane or rollback fencing that intentionally prevents an already admitted execution from persisting or handing off must use `finalOutcome=canceled` with a bounded `finalReason` such as `rollback_epoch_advanced`, `superseded_by_newer_patch`, `operator_canceled`, or `operator_purged`.
- **Target-state cancellation mapping:** a cancelable `PENDING_EVALUATION` trigger transitions durably to terminal `CANCELED` without entering the DSL and records `finalStage=ADMISSION`, `finalOutcome=canceled`, and the applicable bounded cancellation reason. An `EXECUTING` trigger must be fenced and its descriptor-commit marker inspected before transition. If the descriptor commit is present, cancellation never resumes or re-dispatches a committed child: evaluated `PENDING` and `INDEXED` children compare-and-set to `CANCELED` with durable `cancelReason`, `finalStage=WORK_ITEM_PERSIST`, and `finalOutcome=canceled`; `HANDOFF_IN_FLIGHT` fences further retry and reconciles the durable downstream outcome (remaining active/unresolved when ambiguous); and `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` children retain their outcome and no-op. Explicit cancellation of an uncommitted trigger transitions to terminal `CANCELED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and the applicable bounded cancellation reason, and is never replay-eligible. Only the distinct expired-stale recovery-owner path for an uncommitted trigger transitions to terminal `DEAD_LETTERED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`; that path reuses the existing full Trigger Identity and propagated `scriptEventId` and creates no new `scriptEventId`, step identity, or command-child identity. A new `scriptEventId`/Trigger Identity is reserved for a new business attempt, not stale recovery; the cancellation API itself does not dead-letter stale displaced rows. Every transition is stage-aware and no path re-enters the DSL.

### Command-Handoff Identity (Target-State)

The command collection uses one complete command-handoff identity for each evaluated child dispatch:
`<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, targetGameInstanceId?, targetPlayableStateScope?, targetRegionId?, targetRegionEpoch?, automationDispatchId, commandOrdinal>`.
The source scope fields are required for gameplay/runtime commands; the target scope fields are included exactly when the emitted command is routed to a distinct target runtime scope, and `targetPlayableStateScope` is included whenever that distinct target scope has its own shared/isolated playable-state namespace. Absent optional fields remain absent. For command-capable gameplay/runtime evaluations, before DSL evaluation, the pre-DSL/evaluation claim durably reserves and persists one opaque `automationDispatchId` for the evaluation's eventual dispatch group; it is never reused within the complete source runtime scope, and evaluation-stage retries reuse that reservation. Tenant-readiness `onLoad` is non-emitting and does not reserve or expose a command dispatch identity. The reservation is not a child Command-Handoff Identity: before an evaluated descriptor exists, the pre-DSL trigger has no child identity or `commandOrdinal` and must not expose the reserved ID as a synthetic child/handoff reference. Once descriptors are emitted, `commandOrdinal` is deterministic from canonical emitted-command order: the first emitted command under a dispatch is `0`, followed by `1`, and so on. Before any child is emitted or committed, Automation enforces durable uniqueness of the parent/source-scope `automationDispatchId` reservation; when each evaluated descriptor is materialized, it enforces durable uniqueness on the complete Command-Handoff Identity above. The complete source/target runtime scope, dispatch ID, and ordinal together prevent unrelated evaluations from converging; `automationDispatchId` is not assumed to be globally unique. `outboxWorkItemId`, the parent Trigger Identity, and `scriptEventId` are correlation-only and excluded from command-child uniqueness. The complete identity above is the canonical identity for evaluated command-child handoff deduplication, execution-fence reporting, replay selection, and child correlation; it is not the downstream authoritative effect identity. Downstream effect idempotency instead uses the stable root `EffectId`, typed operation, and exact target aggregate, with the immutable request digest stored and compared; a conflicting operation, target, or digest fails closed. The `(automationDispatchId, commandOrdinal)` pair is only a suffix and is insufficient without the complete source/target scope; `script_event_audit` remains a separate one-row-per-handler surface.

A command-handoff reference exists only for an emitted evaluated descriptor. Pre-DSL trigger records have no child Command-Handoff Identity or `commandOrdinal` and must not be represented by their reserved `automationDispatchId`, parent `outboxWorkItemId`, parent Trigger Identity, or `scriptEventId` as a synthetic child/handoff reference. Read-only inspection may expose an emitted descriptor reference with the complete identity above, including `commandOrdinal`; mutation APIs currently accept bounded explicit `workItemIds[]` only. Descriptor references and filter selectors are listing/preview inputs, and descriptor-level replay or purge remains deferred until preview plus stable per-row proof.

Supplementary post-handoff correlation rule:

- **Target-state command-ordinal contract:** execution-time version/plugin fence drops that happen after tick handoff must not be left as metrics-only signals. They must be exposed on the affected per-command handoff disposition keyed by the complete Command-Handoff Identity, including `targetPlayableStateScope` when a distinct target runtime scope applies, with the parent Trigger Identity and its exact `scriptPatchVersion`/`scriptPinEpoch` pair retained for correlation, using bounded reasons such as `script_patch_mismatch` or `plugin_binding_mismatch`.
- **Current live fallback:** the Game Session handoff currently carries `automationDispatchId`, command id/text, selected provenance fields, and parent work-item correlation, but not `commandOrdinal` or the complete Trigger Identity. Current diagnostics use those fields and must be labeled as the narrower fallback rather than as proof of the target-state contract.

Per-command handoff correlation rule:

- `script_event_audit` remains one handler record per Trigger Identity, even when that handler emits multiple gameplay commands. It must not contain a single command dispatch field or a single post-handoff outcome for the whole Trigger Identity.
- **Target-state:** handoff and later execution dispositions are represented as a child/collection surface with one record per emitted command. One stable `automationDispatchId` identifies the persisted dispatch group, and `commandOrdinal` distinguishes each emitted command under that dispatch within the complete Command-Handoff Identity. Each record retains the parent `outboxWorkItemId` and complete Trigger Identity for correlation. `ListScriptHandoffEvents` is the canonical query surface for these records.
- A handler may therefore have zero, one, or many command-handoff records; a later version-fence drop on one command must not overwrite or summarize the handler audit row or the dispositions of sibling commands.

### Canonical `finalOutcome` Values (Normative)

Use a single canonical outcome taxonomy across docs, protos, metrics, and dashboards. Aliases are not allowed in new writes.

Taxonomy governance rule:

- Keep `finalOutcome` intentionally small and stable; add a new canonical value only when operator behavior, routing, or alert semantics materially change. Use `finalReason` for finer-grained diagnosis.
- Event-scope admission failures that occur before handler resolution are recorded in the ingress response and `script_event_ingress_audit`, not in this handler-scoped taxonomy. In particular, pre-resolution unavailable pin state uses `TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE` / `pin_state_unavailable`, a requested-versus-observed pin mismatch uses `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` / `pin_state_mismatch_requested_vs_observed`, a failed runtime reload uses `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` / `reload_failed` without `retryAfterMs`, signer-policy unavailability uses `TRIGGER_ADMISSION_OUTCOME_SIGNER_POLICY_UNAVAILABLE` / `signer_policy_unavailable`, and an active rollback convergence timeout uses `TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` / `rollback_convergence_timeout` with no `retryAfterMs`. This timeout is terminal fail-closed state, not retryable backpressure. After handler resolution, unavailable pin state and signer-policy unavailability remain handler-scoped with `finalStage=ADMISSION` and their corresponding `finalOutcome` values.

| Canonical value | Stage | Notes |
| --- | --- | --- |
| `handoff_accepted` | `TICK_HANDOFF` | Every required child dispatch was durably accepted by Game Session; this is not gameplay application. |
| `completed_no_commands` | `DSL_EVAL` | A valid live handler evaluated and intentionally emitted no commands. |
| `readiness_success` | `DSL_EVAL` | Tenant-readiness `onLoad` completed successfully and contributed to patch readiness. No work item or tick handoff was created. |
| `dry_run_success` | `DRY_RUN_RESULT` | Non-committing dry-run/test execution completed and returned would-be commands for inspection. |
| `skipped_reloading` | `ADMISSION` | Explicit reload backpressure. For a low-rate event denied before handler resolution, apply the [event-scope claim retry lifecycle](#event-scope-claim-and-retry-semantics-normative): only an explicitly retryable denial may be reclaimed after its bounded retry hint/window, using the same event-scope identity and `scriptEventId`; it must not fan out while denied. After handler resolution, a handler retry reuses that handler's full Trigger Identity and the same propagated `scriptEventId`. A timer/cadence candidate skip remains the Table 3 candidate-audit outcome and creates no event-scope firing claim or `scriptEventId`. |
| `rollback_paused` | `ADMISSION` | Post-resolution handler rollback backpressure while control-plane rollback pause is active; a handler retry reuses its full Trigger Identity and the same propagated `scriptEventId`, if policy allows. For low-rate event ingress denied before handler resolution, apply the [event-scope claim retry lifecycle](#event-scope-claim-and-retry-semantics-normative) to the ingress pair `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK` / `rollback_pause`: reclaim the same claim/identity only within its bounded retry eligibility, with no fan-out while denied. Timer candidates use the Table 3 candidate-audit outcome, not this handler or ingress audit path. |
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

If Game Session rejects a queued command because its embedded `(scriptPatchVersion, scriptPinEpoch)` does not match the currently pinned exact tuple (or a plugin-produced command's embedded `(pluginId, pluginVersionId, bindingId)` does not match fresh current activation, binding, lifecycle, and policy evidence for its scoped instance), it must:

- Record the drop on the affected command-handoff record keyed by the complete Command-Handoff Identity: required source scope (`tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`), the dispatch-group suffix (`automationDispatchId`, `commandOrdinal`), and the optional distinct target scope (`targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, `targetRegionEpoch`) when applicable. `automationDispatchId` is retained as dispatch-group correlation and is not a standalone child key; parent `outboxWorkItemId`, the parent Trigger Identity, and `scriptEventId` remain correlation-only. Retain `scriptId`, `scriptPatchVersion`, and `entityId` for diagnosis, plus `pluginId`, `pluginVersionId`, and `bindingId` for a plugin handler. For a plugin identity/evidence mismatch, this disposition uses bounded `reason=plugin_binding_mismatch`.
- Remove the rejected queue entry (or move it to a bounded dead-letter store) so mismatched entries cannot accumulate unboundedly after a rollback.
- Apply the corresponding Table 4 version-fence metric consequence for script-patch or plugin-version/binding mismatches; Table 4 owns the exact family, labels, and increment unit.
- Dead-letter retention for rejected queue entries must be bounded and explicit:
  - `maxAge` and `maxRows` must be documented per environment.
  - Cleanup cadence must be documented and alert-backed.
  - Breaching thresholds must emit operator-visible alerts.

### Terminal Descriptor Evidence Purge (Target-State)

Purge may delete only retention-eligible evaluated descriptor/outbox/evaluated-output evidence whose parent trigger is `EVALUATED_COMMITTED`, after every child under that parent is terminal `HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED` and every eligible evidence item is purged atomically. If any sibling is `PENDING`, `INDEXED`, `HANDOFF_IN_FLIGHT`, otherwise nonterminal, or not retention-eligible, the entire parent purge is rejected and no `descriptorEvidencePurgedAt` marker is written. The same successful purge transaction must persist an explicit durable `descriptorEvidencePurgedAt` marker on the retained parent. A marked parent is non-recoverable and non-replayable; consumers reject it deterministically rather than treating missing children as recoverable. It must preserve the `EVALUATED_COMMITTED` trigger marker, the corresponding `script_event_audit` record, and replay-causation claims or records; deleting terminal child evidence must never delete or reset the marker as a side effect.

Purge rejects `PENDING_EVALUATION` and `EXECUTING` triggers, any nonterminal trigger, and descriptor statuses `PENDING`, `INDEXED`, or `HANDOFF_IN_FLIGHT`, including when those rows are associated with an `EVALUATED_COMMITTED` parent; these rejections write no parent marker. Purge is evidence cleanup only: it must not cancel, reclaim, dead-letter, replay, or re-enter the DSL.

Separately, a terminal pre-DSL `DEAD_LETTERED` row with no committed descriptor may have only retention-eligible trigger/failure payload material purged after its recovery eligibility window has expired. No new schema marker is introduced for this path. The retained purge audit/idempotency outcome is the durable evidence of the purge and makes the row permanently non-recoverable; any later replay attempt is rejected deterministically with `rejectionReason=stage_evidence_unavailable`. Its immutable identity/digest, failure-stage, corresponding `script_event_audit`, recovery-causation evidence, and audit/idempotency outcomes remain retained under their owning retention contracts.

## Table 3: Timer Semantics Matrix

Recurring and advisory timer-driven handlers (`onInterval`, or advisory uses of `onTimerExpire`) are best-effort and produce at most one event-scope durable firing/claim and one deterministic event-scope `scriptEventId` per due candidate. That firing fans out to zero or more handler work items, each with its own full Trigger Identity containing that same propagated `scriptEventId`; handler uniqueness comes from the other applicable full-identity fields. Clock unit, recovery class, due-point calculation, resume-window fencing, and downtime policy are owned by the [scheduler and timers contract](./system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers); this table retains the exact Trigger Identity and candidate-audit consequences. Before `EVALUATED_COMMITTED`, a failed physical evaluation attempt for a handler work item may retry under that handler's full Trigger Identity and must converge on the same work item and admitted child work; after the boundary, recovery replays that handler's durable descriptors without re-entering the DSL. Correctness-bearing one-shot timers are not best-effort: durable intent must be recorded outside Redis before acknowledgement and must converge under the same logical identity to one execution or an explicit terminal outcome.

The matrix below defines what the scheduler does when a firing becomes due under different conditions:

| Condition | Behavior | Audit requirements |
| --- | --- | --- |
| Correctness-bearing one-shot | Persist intent outside Redis before acknowledgement; recover under the same logical identity to one execution or an explicit terminal outcome, never silently dropping or duplicating the effect. | Durable intent, execution, and terminal outcome must remain operator-visible; retries reuse the same identity. |
| Recurring/advisory normal operation | Apply cadence/reload/version/event-policy gates before binding; after those event gates pass, create at most one event-scope durable firing claim and one event-scope `scriptEventId` for the due candidate, resolve handlers, and let handler quota decisions apply independently. Fan out zero or more quota-allowed handlers into durable queued work, each with its own full Trigger Identity containing that same propagated `scriptEventId`; handler uniqueness comes from the other applicable full-identity fields. The later execution scheduler considers that queued work in canonical order for the artifact-estimate prefix reservation. Before `EVALUATED_COMMITTED`, a failed physical evaluation attempt may retry under the affected handler work item's full Trigger Identity and must converge on the same work item and child identities; after the boundary, recovery replays that handler's durable descriptors without re-entering the DSL. | An admitted firing receives one `script_event_audit` row per resolved handler-scoped Trigger Identity with stage-aware outcomes; handler quota denial records its handler audit but creates no work item, lease, or execution marker. |
| Recurring/advisory scheduler/cadence/reload/version/policy gate denied before binding | Skip the candidate; do not create a firing claim, handler Trigger Identity, or replay it later. | Record an event-scope candidate audit keyed by deterministic `scheduleCandidateId`, with `finalStage=ADMISSION` and the applicable bounded outcome/reason. |
| Recurring/advisory handler quota/budget denied after firing admission | Keep the one event-scope firing claim for the due candidate; deny only the affected resolved handler before DSL evaluation. Do not create that handler's work item, capacity lease, or execution marker, and do not replay the denied handler automatically. | Record the handler-scoped full Trigger Identity in `script_event_audit` with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and the applicable bounded reason. |
| Recurring/advisory `reloadState=RELOADING` | Do not admit new timer firings; do not backfill by default. | Record the non-admitted due candidate under deterministic `scheduleCandidateId` with `finalStage=ADMISSION` and `finalOutcome=skipped_reloading`; do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Recurring/advisory `reloadState=FAILED` | Fail closed until durable recovery reconciles the current pin, schedule, scope/epoch, and due evidence and returns the scope to `IDLE`; do not backfill from the prior patch. | Record the non-admitted due candidate under deterministic `scheduleCandidateId` with `finalStage=ADMISSION`, `finalOutcome=version_unavailable`, and `finalReason=reload_failed`; do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Recurring/advisory `PAUSED_FOR_ROLLBACK` | Do not admit new timer firings until the exact pin/artifact, schedule rows, due candidates, displaced/firing projections, and current list/read projections reconcile; only terminal old-execution evidence cleanup remains asynchronous/diagnostic and does not gate resumption. This Automation admission barrier does not pause ordinary gameplay ticks. | Record the non-admitted due candidate in the event-scope candidate-audit surface keyed by deterministic `scheduleCandidateId` with `finalStage=ADMISSION`, `finalOutcome=rollback_paused`, and `finalReason=rollback_pause`; this is not an ingress-audit record. Do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Recurring/durable-recurring leader failover or short downtime | Apply each complete stable schedule-instance identity's declared `SKIP_MISSED` or `COALESCE_ONE` policy. A coalescing schedule instance may admit at most one synthetic firing in the one Automation-owned durable timer-recovery resume window identified by `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, resumeGeneration>`; one deterministic fair `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` cap applies across independent schedule instances within that `resumeWindowId`, grouped by the complete identity defined in the scheduler lifecycle (including target scope and plugin-binding dimensions), not by `scheduleDefinitionId` alone. Excluded candidates are dropped and never deferred as backlog. | Catch-up firings use `triggerMode=CATCH_UP` and deterministic identity from the coalesced due point and `resumeWindowId`. The firing-claim uniqueness/comparison, candidate audit, and global-cap accounting all include `resumeWindowId`; same-window retries and takeovers reuse it. The window completes only after admitted and cap-excluded outcomes are durable, and only a later recovery episode allocates the next `resumeGeneration`. Skipped or cap-excluded candidates emit operator-visible audit, metric, and bounded reason evidence. |
| Recurring/advisory runtime scope / epoch change before due-point admission | Do not remint stale due points under the newer `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)` timeline. A `playableStateScope` change is a schedule/runtime migration fence even when the other runtime fields are unchanged: create or confirm the new scope-owned schedule entry before retiring the old entry, as one atomic durable result or a resumable idempotent operation, then advance to the new scope's next valid due point without reusing the stale one. | Record the dropped candidate at event scope under deterministic `scheduleCandidateId` with `finalStage=ADMISSION`, `finalOutcome=canceled`, and `finalReason=runtime_scope_changed` (or `playable_state_scope_changed` for that specific fence), and emit the runtime-fence metric. Because admission did not occur, do not create a firing claim, handler Trigger Identity, or `scriptEventId`. |
| Correctness-bearing one-shot runtime scope / epoch change | Keep the durable intent and any effect under the original identity and fence it from ordinary execution on the new timeline. Reconcile it under authority-fenced owner evidence; never rebind or remint it into the new scope/epoch. Only after the original intent/effect and source claim conclusively permit `ABANDONED` may an authorized recovery create a fresh current-scope identity with revalidation and durable lineage under [ADR 0067](./decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md). | Retain the original identity, old scope/epoch, reconciliation evidence, terminal outcome when proven, and any fresh lineage link. Inconclusive old work remains nonterminal and blocks affected-scope reopen rather than being reported as skipped or silently replaced. |
| Recurring/advisory timer preserved across reload/rollback | Apply the owner-defined resume rule only when its runtime-region, scope, and epoch preconditions match; otherwise fence the old due point and derive a new due point after the replacement schedule entry is durable. Do not reuse a stale due point or Trigger Identity. | Preserve the dropped old due-point evidence and the exact identity; see [Timer Resume Rule](./system-architecture-scripting-scheduler-and-timers.md#timer-resume-rule-normative). |
| Recurring/advisory long downtime or sustained overload | No guarantee of eventual execution for every firing; the system converges by running future firings once capacity returns. | Missed firings must be visible as skips/drops in metrics and audit. |
| Infrastructure error after admission | Before `EVALUATED_COMMITTED`, an admitted evaluation may retry only under the same full Trigger Identity and must converge on the same parent work-item and child identities. After `EVALUATED_COMMITTED`, recovery replays the durable descriptors and never re-enters the DSL. Correctness-bearing one-shot recovery must converge under its durable intent identity to one execution or an explicit terminal outcome. Downstream retries remain idempotent. | Preserve the `finalStage` reached by the failed or committed operation; do not record `handoff_accepted` without full durable acceptance of every required child. |

### Preserved-Timer Resume Rule

The exact resume formula, modulo-zero boundary behavior, and runtime scope/epoch preconditions are owned by [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md#timer-resume-rule-normative). This table records the resulting candidate-audit and identity requirements only: a preserved timer uses the original scope/epoch identity when those preconditions hold; a changed scope or epoch fences the old due point and does not reuse its Trigger Identity.

### Version-Owned Durable Schedule Migration (Normative)

- `scheduleDefinitionId` participates in the stable logical continuity key but is not the durable row or trigger-claim identity. The continuity key is `{stableOwnerKind, stableOwnerId, scheduleDefinitionId, targetScopeType, targetScopeId}`; `pluginId` is the stable plugin owner while `pluginVersionId`, `scriptPatchVersion`, and `scriptPinEpoch` remain provenance and exact execution-fence metadata.
- By default, a version transition tombstones the displaced interval and creates a target interval with fresh due state when a validated target definition exists. When the target definition is absent, reconciliation retires/fences the displaced interval and creates no replacement row, due state, firing claim, or `scriptEventId`; those may be created only after a validated target definition exists and reconciles. Carrying due state requires explicit compatible continuity declarations on both sides plus typed interval/cadence/target/playable-scope checks and the exact pin/epoch transition identity. A same-version repin is still a new epoch transition: the transition must atomically or resumably reconcile the stable row and retire or rewrite all displaced mutable schedule state, due candidates, and current list/read projections before admission resumes, while old-epoch firing claims and candidate audits retain their original exact `(scriptPatchVersion, scriptPinEpoch)` and `resumeWindowId` and may only be fenced or terminal-marked, never rewritten. `scheduleSemanticsHash` is diagnostic evidence only; equality cannot grant continuity. Replacement creation and retirement are one atomic durable result or a resumable, idempotent operation keyed by `controlPlaneRequestId`.
- The new version/scope-owned entry must derive a new scheduler trigger claim and `scriptEventId`; a matching `scheduleDefinitionId`, due point, handler, or old `playableStateScope` must never be used to reuse a Trigger Identity across the migration.

## Table 4: Metrics Label Matrix

This table is the sole exact owner of the scripting metric-family catalog, label sets, and increment units. The observability contract links here for behavior and local consequences; it must not redefine metric schemas.

General rules:

- `scriptEventId` is forbidden as a metric label.
- Raw `tenantId`, `scriptId`, `pluginId`, `pluginVersionId`, and `bindingId` are not approved ordinary Prometheus labels in the canonical repo-wide metrics policy. When this table names those logical dimensions, producers must map them to bounded operator-facing scope/category labels unless a later design update records an explicit exception.

Bounded semantic labels use the existing scripting vocabulary and are validated before a sample is emitted:

- `scope` is the controlled enum `region`, `game_instance`, `tenant`, or `cluster`; a metric family may restrict which of these values it accepts. Capacity metrics use only their documented subset, such as `tenant` or `cluster`.
- Before handler resolution, pre-handler metric families use the single bounded value `script_category="UNRESOLVED"`; after resolution, handler-scoped metrics use the existing handler types `SCRIPT` and `PLUGIN`.
- `priorityTag`/`tier` use the bounded runtime priority vocabulary `high`, `normal`, or `background`. For a rejected pre-handler increment, `priorityTag` must come from a bounded request/event-scope value; when absent, invalid, or untrusted, use `UNRESOLVED` rather than infer priority from a handler that was never materialized.
- `eventType` is valid only when `(eventType, eventSchemaVersion)` is present in the canonical event registry. For metric emission only, an invalid or missing source value maps to the bounded `eventType=UNRESOLVED` value (or the equivalent `event_type=UNRESOLVED` spelling at a metric adapter); the raw value remains in audit, log, and trace records. This bounded metric value is not a replacement Trigger Identity field, ingress response value, or audit `eventType`. Event-scope `outcome`/`reason` values use bounded `admissionOutcome`/`admissionReason` codes; handler-scoped values use bounded `finalOutcome`/`finalReason` codes; `stage`/`finalStage` use the Table 2 stage set. An event-scope metric must not copy handler outcome or reason values.
- `result` uses the bounded dry-run/test result taxonomy derived from Table 2 outcomes, including `dry_run_success` and the applicable classified non-success outcomes; it is never a free-form test result or raw exception.
- `reason` is the bounded admission, final-outcome, or command-handoff reason taxonomy owned by the relevant contract. `operation` uses the bounded operation vocabulary of its owning control-plane workflow. `component_class` uses the finite component-policy registry. None of these labels may carry a free-form request reason or raw identifier.
- `sourceService` uses the finite producer/owner service vocabulary registered for the event. The full canonical producer identity, including a custom-event source service, is preserved separately in event-scope and handler audit records; it is not widened into an unbounded metric label.
- `plugin_family` and `plugin_version_family` are registry-defined classifications, not raw plugin identifiers. Omit an optional plugin label when the event is not plugin-owned or the classification is unavailable at the event-scope boundary; do not invent `unknown` or `not_applicable` values. For `automation_tick_plugin_version_fence_dropped_total`, both plugin labels are required: validate them before emission and emit no sample when either is absent or invalid, while retaining the raw plugin identity in the command disposition, audit, or log record.
- Pre-handler metrics omit `plugin_family` and `plugin_version_family` because plugin identity is unavailable before handler resolution. Resolved plugin-handler metrics may include those bounded classifications when available.
- `admitted_no_handlers` is a bounded metric-only outcome owned by `automation_script_triggers_total`. It is emitted only when an admitted live event resolves zero handlers, with `script_category=UNRESOLVED` and no `plugin_family` or `plugin_version_family`; it is excluded from ingress responses and all Automation audit fields, including `script_event_ingress_audit` and `script_event_audit` admission and final outcome/reason fields.
- Every required label must be present and belong to its closed vocabulary. If an invalid or missing value is required for business-event admission, reject the event under the owning admission contract while emitting only the bounded metric value described above and retaining the full source value in audit/log/operational records; otherwise suppress the metric sample rather than emitting an unbounded value. Do not emit an unapproved sentinel or create a new admission outcome or validator family for metric hygiene.
- `isDryRun` is an execution-mode predicate, not a metric label. Live trigger, quota, work-item, sandbox, error, runtime, and tick-handoff families are emitted only for `isDryRun=false`. Dry-run/test handler executions use `automation_script_test_runs_total`, `automation_script_test_sandbox_failures_total`, and `automation_script_test_runtime_seconds` as applicable; `automation_script_test_capacity_denied_total` records isolated dry-run capacity denials. Pre-handler dry-run ingress denials use the single ingress-drop consequence defined below.

| Metric family | Required labels | Forbidden labels | Notes |
| --- | --- | --- | --- |
| `automation_script_triggers_total` | `scope`, `script_category`, `eventType`, `outcome`, optional `plugin_family`, `plugin_version_family`, `priorityTag` | `scriptEventId` | Requires `isDryRun=false`. After resolution, increment exactly once for each resolved live handler using its applicable `finalOutcome`, with no additional admitted-event increment. If an admitted live event resolves zero handlers, increment once at event scope with the metric-only `outcome="admitted_no_handlers"`; do not return or persist that value in ingress or audit fields. |
| `automation_script_skips_total` | `scope`, `script_category`, `reason`, optional `plugin_family`, `priorityTag` | `scriptEventId` | Requires `isDryRun=false`. “Skip” is pre-eval. |
| `automation_script_triggers_dropped_total` | `scope`, `script_category`, `reason`, optional `plugin_family`, `priorityTag` | `scriptEventId` | Counts trigger requests rejected before handler resolution, including dry-run/test ingress denials; this is the sole metric accounting for those dry-run ingress decisions. Increment once using the bounded event-scope `admissionReason`. It does not count handler-level outcomes such as `quota_denied` or post-handoff command drops. Handler-level `quota_denied` belongs in the handler outcome path (`automation_script_triggers_total{outcome="quota_denied"}` and its handler audit row), without joining or double-counting it here. |
| `automation_script_work_item_outcomes_total` | `stage`, `outcome`, `priorityTag`, `sourceService` | `scriptEventId` | Requires `isDryRun=false`. Increments exactly once for each terminal durable live work-item execution outcome using the `script_event_audit` stage/outcome vocabulary. Dry-run/test executions never increment this family; use `automation_script_test_runs_total` instead. |
| `automation_script_work_item_canceled_total` | `scope`, `operation`, `finalStage`, `reason` | `scriptEventId` | Increment once per durable work item or evaluated descriptor transitioned to `CANCELED` by an ordinary or control-plane cancellation, including rollback cancellation before execution. It does not count a purge that leaves status unchanged or an execution fence counted by `automation_rollback_drain_canceled_total`. |
| `automation_script_work_item_purged_total` | `scope`, `operation`, `reason` | `scriptEventId` | Increment once per terminal evaluated descriptor/outbox evidence item actually deleted by a successful purge. Retention/nonterminal rejection and ordinary cancellation do not increment this family; raw references remain in audit/log/trace records. |
| `script_quota_allowed_total` | `scope`, `script_category` | `scriptEventId` | Quota decisions are pre-eval. |
| `script_quota_denied_total` | `scope`, `script_category`, `reason` | `scriptEventId` | Counts per-script quota decisions only; handler-scoped dry-run capacity denials do not increment this family. |
| `automation_script_tenant_budget_allowed_total` | `scope`, `tier` | `scriptEventId` | Counts allowed live execution budget reservation decisions by bounded runtime budget tier. |
| `automation_script_tenant_budget_denied_total` | `scope`, `tier` | `scriptEventId` | Counts denied live execution budget reservation decisions by bounded runtime budget tier. |
| `automation_tick_events_enqueued_total` | `scope` | `scriptEventId` | Counts successful tick handoffs, not DSL evaluations. |
| `automation_tick_version_fence_dropped_total` | `scope`, `script_category`, `reason` | `scriptEventId` | Counts commands dropped at execution time because the embedded `(scriptPatchVersion, scriptPinEpoch)` does not match Game Session's exact current tuple. |
| `automation_tick_plugin_version_fence_dropped_total` | `scope`, `plugin_family`, `plugin_version_family`, `reason` | `scriptEventId` | Counts commands dropped at execution-time due to plugin version fence mismatches. All required labels must pass the registry/taxonomy validation above; if either plugin classification is unavailable or invalid, emit no sample and retain the raw plugin identity in the command disposition/audit/log record. |
| `automation_script_queue_delay_seconds` | `scope`, `script_category` | `scriptEventId` | Observes queue delay for sampled or processed automation work; raw queue, tenant, and script identifiers are not labels. |
| `automation_queue_orphaned_entries_total` | `scope` | `scriptEventId` | Counts queue entries detected beyond the bounded age window without corresponding durable-executor progress or tick-effect-ledger entries. |
| `automation_queue_oldest_entry_age_seconds` | `scope` | `scriptEventId` | Records the age of the oldest sampled queue entry per bounded scope; tenant/script diagnosis remains in operational records. |
| `automation_script_leadership_changes_total` | none | `scriptEventId` | Counts automation scheduler leadership changes. |
| `automation_script_runtime_seconds` | `scope`, `script_category`, `eventType`, optional `plugin_family` | `scriptEventId` | Requires `isDryRun=false`. Runtime is live sandbox eval time (not tick execution time); dry-run/test runtime uses `automation_script_test_runtime_seconds`. |
| `automation_script_sandbox_failures_total` | `scope`, `script_category`, `reason`, optional `plugin_family` | `scriptEventId` | Requires `isDryRun=false`. Counts live sandbox failures; dry-run/test sandbox failures use `automation_script_test_sandbox_failures_total`. |
| `automation_script_errors_total` | `scope`, `script_category`, `reason`, optional `plugin_family` | `scriptEventId` | Requires `isDryRun=false`. Counts higher-level classified live script errors, including downstream failures; dry-run/test execution results use `automation_script_test_runs_total` and never increment this family. |
| `automation_script_output_budget_exceeded_total` | `scope`, `script_category`, `reason`, optional `plugin_family` | `scriptEventId` | Counts runs rejected because emitted work exceeded a bounded output ceiling. |
| `automation_script_test_runs_total` | `scope`, `script_category`, `eventType`, `result`, optional `plugin_family` | `scriptEventId` | Dry-run/test counterpart for live work-item outcomes and classified execution results. Increment exactly once per materialized handler-scoped dry-run/test execution attempt, including a post-materialization capacity denial or execution failure. Pre-resolution denials use the single ingress-drop consequence above and do not increment this family; completion must not increment it again. Keep it separate from live-traffic counters. |
| `automation_script_test_runtime_seconds` | `scope`, `script_category`, `eventType`, optional `plugin_family` | `scriptEventId` | Dry-run/test runtime latency; must remain separate from live runtime histograms. |
| `automation_script_test_sandbox_failures_total` | `scope`, `script_category`, `eventType`, `reason`, optional `plugin_family` | `scriptEventId` | Dry-run/test-only sandbox failures; must not increment live sandbox failure counters. |
| `automation_script_test_capacity_denied_total` | `scope` | `scriptEventId` | Counts handler-scoped dry-run capacity denials. `scope` is bounded to `tenant` or `cluster`; this is not a per-script quota decision. |
| `automation_script_timer_catchup_truncated_total` | `scope`, `script_category`, `eventType`, `reason` | `scriptEventId` | Counts catch-up firings that were intentionally truncated/dropped by resume-window limits. |
| `automation_script_timer_runtime_fence_dropped_total` | `scope`, `script_category`, `eventType`, `reason` | `scriptEventId` | Counts due points intentionally dropped because runtime scope or epoch changed before the scheduler could admit them. |
| `automation_rollback_convergence_timeout_total` | `scope`, `operation`, `reason` | `scriptEventId` | Incremented when rollback orchestration reaches timeout terminal state before convergence acknowledgment. |
| `automation_plugin_policy_violations_total` | `scope`, optional `plugin_family`, optional `plugin_version_family`, `component_class`, `reason` | `scriptEventId` | Counts plugin component-policy violations in both report-only and enforcing modes. |
| `automation_rollback_drain_canceled_total` | `scope`, `operation`, `finalStage`, `reason` | `scriptEventId` | Increment once per admitted old-epoch execution intentionally fenced during rollback draining before live work can persist or hand off. Ordinary cancel requests, ordinary purge activity, and pre-admission skips do not increment this family; use the work-item cancellation/purge families above for those actions. |

## Documentation Drift Guardrails

To keep contracts consistent across docs:

- `design/` markdown validation in CI must include a scripting-contract lint pass (for example `./gradlew lintMarkdown` invoking `dev-tools/docs/lint-scripting-contracts.sh`) that fails on:
  - Deprecated aliases in normative fields (for example `skipped_disabled`, `skipped_version_unavailable`).
  - Conflicting ownership language for ingress identity fields (for example `scriptEventId`).
  - Multiple documents claiming incompatible “authoritative/source-of-truth” ownership for the same contract surface.
- The scripting platform maintainers own these lint rules under CODEOWNERS and must update them when canonical contracts change.
- Docs that define non-normative examples must link back to this table for outcome and label names instead of redefining names locally.
