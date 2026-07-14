# Automation and Scheduler Runtime

## Current Status

Lossless domain transposition is complete. The implementation claims, open gaps, and discussion items below remain source-backed until the required Spark coverage audit verifies each migrated range.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Publish and Script Patch Temporal Migration Vertical Slice](../vertical-slices/02.20.3-task-list-publish-and-script-patch-temporal-migration-vertical-slice.md) - Script-patch readiness and rollout workflow ownership | implemented at the current boundary | 20-36 | [source evidence](#source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-20-36) |
| [Scripting, Automation, and Runtime Orchestration Vertical Slice](../vertical-slices/10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice.md) - Scripting and runtime orchestration taxonomy | complete as the parent slice-family framing; child slices own ongoing implementation follow-through | 1-55 | [source evidence](#source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55) |
| [Script Event Ingress and Handler Resolution Vertical Slice](../vertical-slices/10.1-task-list-script-event-ingress-and-handler-resolution-vertical-slice.md) - Script event ingress and handler resolution | complete at the current bounded boundary | 1-92 | [source evidence](#source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92) |
| [10.1.1 Task List: Built-In Event Payload Contract Parity Vertical Slice](../vertical-slices/10.1.1-task-list-built-in-event-payload-contract-parity-vertical-slice.md) - Built-in script event payload contract | complete | 1-89 | [source evidence](#source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89) |
| [10.1.2 Task List: Plugin Binding Resolution Runtime-Activation Follow-Through Vertical Slice](../vertical-slices/10.1.2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice.md) - Plugin binding runtime activation | complete | 1-93 | [source evidence](#source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93) |
| [10.1.3 Task List: Durable `onCommand` Trigger Authority Follow-Through Vertical Slice](../vertical-slices/10.1.3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice.md) - Durable onCommand trigger authority | complete | 1-83 | [source evidence](#source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83) |
| [Script Timer, Scheduler, and Runtime Ownership Vertical Slice](../vertical-slices/10.2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice.md) - Timer and scheduler runtime ownership | complete for the first ownership model | 1-44 | [source evidence](#source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44) |
| [Script Execution Budgets, Quotas, and Isolation Vertical Slice](../vertical-slices/10.3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice.md) - Script execution budgets and quotas | complete at the current bounded boundary | 1-80 | [source evidence](#source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80) |
| [10.3.1 Task List: Source-Aware Budget Charging Follow-Through Vertical Slice](../vertical-slices/10.3.1-task-list-source-aware-budget-charging-follow-through-vertical-slice.md) - Durable source-aware budget charging | complete at the current bounded boundary | 1-96 | [source evidence](#source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96) |
| [10.3.2 Task List: Script Execution Operator Metrics and Alerting Convergence Vertical Slice](../vertical-slices/10.3.2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice.md) - Automation execution observability | complete | 1-94 | [source evidence](#source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94) |
| [10.3.3 Task List: Durable Quota-Class Runtime Convergence Vertical Slice](../vertical-slices/10.3.3-task-list-durable-quota-class-runtime-convergence-vertical-slice.md) - Durable quota-class enforcement | complete at the current bounded boundary | 1-93 | [source evidence](#source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93) |
| [10.3.4 Task List: Publish-Readiness Capacity Convergence Vertical Slice](../vertical-slices/10.3.4-task-list-publish-readiness-capacity-convergence-vertical-slice.md) - Readiness execution capacity | complete at the current bounded boundary | 1-87 | [source evidence](#source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87) |
| [Automation Handoff and Tick Integration Vertical Slice](../vertical-slices/10.4-task-list-automation-handoff-and-tick-integration-vertical-slice.md) - Automation handoff and tick integration | complete for the current handoff seam | 1-65 | [source evidence](#source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65) |
| [10.4.1 Task List: Automation Handoff Fail-Closed Provenance Follow-Through Vertical Slice](../vertical-slices/10.4.1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice.md) - Fail-closed automation handoff provenance | complete | 1-101 | [source evidence](#source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101) |
| [10.4.1.1 Task List: Remote Trigger-Event Authority and Read-Model Parity Vertical Slice](../vertical-slices/10.4.1.1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice.md) - Remote trigger-event execution authority | complete | 1-101 | [source evidence](#source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101) |
| [Scripting Operator Visibility and Runtime Convergence Vertical Slice](../vertical-slices/10.5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice.md) - Scripting runtime visibility and convergence | complete | 1-77 | [source evidence](#source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77) |
| [10.5.1 Task List: Publication-Activation Event-Handling Separation Vertical Slice](../vertical-slices/10.5.1-task-list-publication-activation-event-handling-separation-vertical-slice.md) - Runtime activation and event chronology | complete | 1-83 | [source evidence](#source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83) |
| [10.5.2 Task List: Readiness Cancellation Taxonomy and Read-Model Separation Vertical Slice](../vertical-slices/10.5.2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice.md) - Readiness cancellation and runtime outcomes | complete | 1-83 | [source evidence](#source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83) |
| [10.5.3 Task List: Game Session Pin Readback Operator-Surface Convergence Vertical Slice](../vertical-slices/10.5.3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice.md) - Runtime pin-convergence readback | complete at the current bounded boundary | 1-94 | [source evidence](#source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94) |

## Canonical Design Sources

Canonical target-state design remains under [design/architecture](../../architecture/README.md). The migrated evidence links to the exact source records that previously carried implementation-tracking detail.

## Verified Live Implementation

The source-backed claims are indexed above. Spark coverage review is pending before they are promoted from migrated evidence to independently verified live status.

## Active Gaps

Source-declared active gaps remain in the detailed evidence below. The post-transposition review will extract any live gaps into this section without losing their original context.

## To Discuss

Source-declared unresolved design or implementation questions remain in the detailed evidence below until they are consolidated into this domain tracker.

## Service and Contract Map

The detailed evidence identifies the public contracts, owning services, and focused proof for each capability. The Spark review produces the service-level audit queue for this tracker.

## Source Evidence

The following records are a line-preserving transposition. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-20-36

#### Publish and Script Patch Temporal Migration Vertical Slice - Script-patch readiness and rollout workflow ownership (source lines 20-36)

##### Preserved Source Text: source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-20-36

<!-- migration-source path="design/project-management/vertical-slices/02.20.3-task-list-publish-and-script-patch-temporal-migration-vertical-slice.md" lines="20-36" sha256="42bec76402a48bfbc1f8bcfb7006a4e6b23878f2fa68597661a067d92fbb60ae" heading-offset="3" -->
- script patch readiness now has a real Temporal-backed `script-patch-readiness` workflow family in Automation Scripting;
- `NotifyScriptVersionUpdate` still preserves the synchronous validation/seed path, but it now also starts or reuses the durable readiness workflow so `onLoad` progression to `READY` / `FAILED` / `ROLLED_BACK` / `SUPERSEDED` no longer depends on one process lifetime;
- the canonical patch-status control-plane read surfaces now expose Temporal workflow identity and execution status for operators without inventing a second read API.

Still remaining in this slice:

- any additional publish-adjacent or script-patch rollout-specific Temporalization that only becomes justified when new definite durable workflow needs exist in code;
- final boundary cleanup once the surviving short synchronous saga seams are explicitly re-scoped in `02.20.4`.

##### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-20-36: Checklist

- [x] Map publish workflow stages to Temporal workflow and activity boundaries.
- [x] Map script patch readiness / rollout lifecycle stages to Temporal workflow and activity boundaries.
- [x] Preserve canonical publish/request/rollout identifiers and business-step idempotency semantics for the publish and script patch readiness workflow families.
- [x] Expose operator-visible patch workflow state through canonical read surfaces.
- [x] Expose operator-visible publish workflow state through canonical read surfaces.
- [x] Re-prove publish and full script patch lifecycle behavior on the new durable workflow path.
<!-- /migration-source -->

### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55

#### Scripting, Automation, and Runtime Orchestration Vertical Slice - Scripting and runtime orchestration taxonomy (source lines 1-55)

##### Preserved Source Text: source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55

<!-- migration-source path="design/project-management/vertical-slices/10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice.md" lines="1-55" sha256="b95125c278751a4384e2b988bc0eab40883f14604b32219e69990973cceb620b" heading-offset="3" -->
#### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Scripting, Automation, and Runtime Orchestration Vertical Slice

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Goal and Status

Goal: give the scripting and automation domain one coherent slice family covering runtime ingress, scheduling, execution budgets, tick handoff, and operator visibility so future work stops being scattered across gameplay hardening, publish-control-plane, and service-local docs. Status: complete as the parent slice-family framing; child slices own ongoing implementation follow-through.

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Why This Slice Exists

FireMUD already has substantial scripting architecture and real implementation substrate, but planning coverage is still fragmented. The domain is too large and too central to keep treating timers, quotas, runtime execution, and rollout visibility as indirect side effects of other slice families.

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Implementation Notes

The parent family is no longer discussion-gated. The scripting/runtime direction is now locked and the family is actively implemented through child slices:

- `10.1` event ingress and handler resolution
- `10.2` scheduler/timer ownership
- `10.3` execution budgets, quotas, and isolation
- `10.4` automation handoff and tick integration
- `10.5` operator visibility and runtime convergence

That means this parent doc is up to date as a taxonomy and direction lock, but it is not a statement that the scripting domain is fully finished or ready for design-only verification without looking at the child slices and, in several cases, the code.

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Scope

- runtime script/event ingress and handler-resolution contracts
- scheduler/timer ownership and catch-up rules
- execution budgets, quotas, fairness, and isolation
- automation outbox/queue/tick handoff into Game Session
- operator observability and control-plane/runtime convergence visibility

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Out of Scope

- design-time publish/version/asset attestation work already covered by `08`
- script patch and plugin publication-versus-activation boundaries already covered by `08.4`
- general gameplay command/durability slices outside scripting-owned execution paths

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Locked Direction

- scripting needs its own slice family; it should not stay an indirect collection of adjacent runtime slices.
- design-time publication remains separate from runtime readiness and execution behavior.
- runtime scripting work must land on the canonical control-plane, scheduler, and handoff seams rather than inventing service-local shortcuts.
- operator visibility for scripting must reflect both design-time and runtime truth without collapsing them into one status model.

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Current Remaining Work

- [ ] Continue implementation and closure work in the child slices rather than reopening the parent family definition.
- [x] Keep `08.4` as the publication-boundary companion rather than duplicating it here.
- [ ] Add future scripting/runtime work under the `10.x` family instead of burying it under generic hardening or gameplay slices.
- [x] Do not treat the parent `10` doc as a frozen review artifact for service behavior; use it to navigate the child slices, with `10.5` currently the closest thing to a broad read-model/operator deep-dive candidate.

##### source-10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice-1-55: Checklist

- [x] Define target-state behavior and scope.
- [x] Establish the parent slice-family framing and child-slice ownership.
- [x] Verify and close the parent-family definition follow-up.
<!-- /migration-source -->

### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92

#### Script Event Ingress and Handler Resolution Vertical Slice - Script event ingress and handler resolution (source lines 1-92)

##### Preserved Source Text: source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92

<!-- migration-source path="design/project-management/vertical-slices/10.1-task-list-script-event-ingress-and-handler-resolution-vertical-slice.md" lines="1-92" sha256="dbdb3f9aa2923c8b59d77fdfee102c6d72b45dfe43f29f459c20d413bb8d6cac" heading-offset="3" -->
#### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Script Event Ingress and Handler Resolution Vertical Slice

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Goal and Status

Goal: make script/event ingress one canonical runtime contract with explicit event-scope admission outcomes, handler-resolution behavior, and trigger-identity ownership so callers, audit logs, and retries no longer depend on transport quirks or service-local inference. Status: complete at the current bounded boundary.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Why This Slice Exists

The scripting docs already define event ingress and handler-level outcomes, but implementation planning does not yet isolate that work as a bounded slice. This cut gives the Automation & Scripting runtime one concrete ingress seam before deeper scheduler and execution work expands.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Implementation Notes

The discussion gate is complete. Implementation is now underway in Automation & Scripting with event-scope ingress admission, ingress audit, event-registry enforcement, snapshot-token validation, plugin runtime-state fencing for plugin-trigger ingress, durable script event bindings, first work-item outbox materialization, handler-scoped audit rows, pending-work cancellation for rollback/drain workflows, live Game Session producer paths for `onCommand`, fresh-PLAY `onSpawn`, movement-backed `onEnterRegion` and `onLeaveRegion`, deliberate-logout plus forced-exit `onLeaveRegion` producer coverage when a player leaves the active room without a destination region, live scheduler-owned `onInterval` and wall-clock `onTimerExpire` producer paths, tenant-readiness `onLoad` work materialized from `NotifyScriptVersionUpdate`, status-safe pending-work claiming for evaluation, and a first durable executor that loads persisted script definitions and emits gameplay commands through the canonical Game Session handoff seam. Gameplay-originated event ingress now also keeps `worldSlug`, `realmSlug`, `pointerVersion`, and `playableStateScope` together as first-class trigger identity on the ingress request, durable audit/work-item records, and operator dead-letter/handoff reads, and Game Session-sourced gameplay events now fail closed if that admitted routing bundle is missing. The same ingress path now also fences against stale runtime region scope by comparing gameplay event `regionId` / `regionEpoch` to current Game Session runtime-state truth and rejecting `runtime_region_scope_advanced` before durable work is materialized on the wrong owned timeline. The current timer/scheduler-owned follow-up path now also preserves that same routing bundle through Game Session runtime-state projection, Automation pin convergence, durable `script_schedule_instances`, timer-owned work-item materialization, and skipped-audit rows instead of collapsing scheduler-originated follow-up work back to blank routing identity. Automation -> Game Session gameplay-command handoff now carries the same bundle on the enqueue request, on staged `GameplayCommand` ledger rows, and back out through `GetGameplayCommandStatus`, and emitted commands may now also carry explicit target runtime scope so cross-scope handoff schedules a durable remote follow-up instead of flattening target-region gameplay work to the local owned scope. Durable handoff history preserves that same target runtime scope plus remote follow-up ids, so scripting operator reads stay truthful when a later gameplay command leg is remote-owned. Tenant-readiness `onLoad` now uses the same canonical ingress/audit/work-item substrate with an explicit readiness source kind, exact-script resolution, `readiness_success` / `onload_commands_not_allowed` execution outcomes, and a separate durable readiness projection so late superseded completions cannot silently advance an older patch back to `READY`. Game Session disconnect teardown now also centralizes canonical `onLeaveRegion` publication for transport loss, takeover, and logout through the gameplay presence lifecycle service, preserving the established `logout:<sessionId>:<gameInstanceId>:<characterId>` trigger id for deliberate logout while emitting deterministic `disconnect:<reason>:<sessionId>:<gameInstanceId>:<characterId>` ids for forced exits and carrying `exitReason` as optional producer-owned lifecycle metadata on the payload.
The current bounded `onCommand` producer path now also covers non-session-backed gameplay execution truth instead of only the live session enqueue seam: session-driven durable commands still publish at enqueue time, direct communication commands (`SAY` / `WHISPER` / `TELL`) now publish through their own live handler path even though they bypass durable command staging, successful direct gameplay read handlers now do the same for `INVENTORY` / `EQUIPMENT` / `CONTAINER`, `WHO`, `FRIENDS`, gameplay-scoped `HELP`, gameplay-scoped world-browse commands (`WORLDS` / `REALMS` / `CHARS`), and gameplay-scoped authored actions, successful direct session commands now also do the same for `PLAY` and `LOGOUT`, and Automation-handoff plus remote target-side gameplay commands now publish `onCommand` when durable execution resolves gameplay identity, so the already-defined event family no longer silently excludes those later command sources. That same `onCommand` path now also supports real `COMMAND_ALIAS`, `ACTION_CATEGORY`, and `ACTION_TAG` handler binding scopes: Game Session preserves the normalized built-in alias as optional payload truth when available, the event manifest explicitly allows alias/category/tag binding scopes for `onCommand`, and ingress resolution can now match handlers from canonical command-classification truth instead of leaving those fields as producer-only metadata. Plugin-owned handlers on that same ingress path now also resolve from canonical runtime activation truth for the current runtime scope rather than from optional request plugin identity alone, and the resolved handler’s plugin ownership now persists on durable work-item and handler-audit rows even when the gameplay producer was not itself plugin-scoped.
The queued player-command seam now also preserves durable `onCommand` trigger authority across enqueue and later live execution: staged `GameplayCommand` rows carry current runtime region scope plus target entity identity, `publishCommandEvent(...)` prefers that persisted command authority when present, and session-backed durable execution now republishes `onCommand` only on live execution paths after replay gates. That closes the old “best-effort enqueue publish only” gap without waking replay/no-op runs or recalculating a newer trigger identity from drifted current runtime state.

The canonical event-registry ownership and entry model now lives in `design/architecture/system-architecture-scripting-event-registry.md`, and the live registry read APIs now expose `payloadSchemaRef` alongside producer/identity rules from a checked-in built-in event manifest rather than only hardcoded Java entries. Script-definition updates now also validate built-in event bindings against that same manifest, so unknown event types or illegal scope combinations fail on the write path instead of surviving into runtime ingress. This slice should treat registry semantics as settled design and focus on runtime ingress enforcement rather than reopening ownership shape.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Scope

- `TriggerScriptEvent` and equivalent ingress RPC/command surfaces
- event-scope admission outcomes and retry hints
- handler-resolution semantics after event admission
- trigger-identity ownership and audit expectations at ingress

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Out of Scope

- timer scheduling ownership beyond resolved ingress semantics
- sandbox runtime internals and per-run execution budgets
- design-time publication/readiness contracts

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Locked Direction

- ingress decisions are application outcomes, not transport-error side effects.
- event-scope admission and handler-scope outcomes are separate truths and must stay separate.
- one admitted event may still yield mixed handler-level outcomes; callers do not infer that from the ingress response.
- trigger identity and ingress audit records are first-class runtime contracts, not optional observability detail.
- producer coverage should not outpace durable execution truth; when a new producer depends on timer, follow-up, or ownership semantics that are not yet canonical, the deeper runtime slice lands first rather than teaching ingress one-off semantics.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Current Remaining Work

The current ingress boundary is complete. Any later work now belongs to narrower owning follow-up slices instead of staying open in this parent:

- future gameplay lifecycle producers should extend the same ingress contract only when those event families become canonical later;
- future runtime-owned follow-up families should carry the same admitted routing bundle directly anywhere reconnect- or cutover-sensitive scripting surfaces appear;
- future custom event families should grow the manifest-backed registry source without reopening the built-in ingress contract;
- future plugin-owned follow-up readers should keep consuming canonical runtime activation truth instead of reviving request- or publication-shaped assumptions; and
- richer graph/runtime evaluation should land as its own executor/runtime slice once the sandbox or compiled-artifact seam is ready.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
  - [x] Event-scope `TriggerScriptEvent` admission returns application outcomes instead of `NOT_IMPLEMENTED`.
  - [x] Event-scope ingress audit is separate from handler-scoped `script_event_audit`.
  - [x] Built-in event-registry enforcement validates producer, identity, and `readSnapshotToken` requirements.
  - [x] Plugin-trigger ingress rejects missing, disabled, or displaced plugin versions before durable handler work is materialized.
  - [x] Canonical event-registry read APIs expose the same definitions used by ingress admission.
  - [x] Script-definition updates now reject unknown built-in event bindings and binding scopes that the canonical event manifest does not allow.
  - [x] Script event bindings are durable and handler resolution reports matched handler count.
  - [x] Matched handlers are materialized as durable `script_work_items`.
  - [x] Handler-scoped `script_event_audit` rows are written for resolved handlers and updated when pending work is canceled.
  - [x] `CancelPendingWorkItemsForPatch` performs a real durable outbox cancellation transition.
  - [x] Game Session emits a real `onCommand` event-ingress call after durable command staging when runtime ownership and a pinned script patch are available.
  - [x] `onCommand` handler resolution now supports real `COMMAND_ALIAS`, `ACTION_CATEGORY`, and `ACTION_TAG` scopes using canonical payload truth instead of leaving alias/classification scope as producer- or preflight-only metadata.
  - [x] Plugin-owned handler resolution now consumes canonical runtime activation truth for the current runtime scope, persists resolved plugin ownership on durable work-item and handler-audit rows, and keeps dead-letter replay aligned with that per-handler ownership instead of falling back to request-shaped ingress metadata for newly admitted rows.
  - [x] Direct gameplay read handlers now also emit the same `onCommand` event family for `INVENTORY` / `EQUIPMENT` / `CONTAINER`, `WHO`, and `FRIENDS` instead of leaving those in-world command paths invisible beside queued gameplay, communication commands, and direct session commands.
  - [x] Gameplay-scoped `HELP` plus gameplay-scoped world-browse commands (`WORLDS` / `REALMS` / `CHARS`) now also emit the same `onCommand` event family while still fail-closing naturally when those same commands are used without gameplay scope.
  - [x] Gameplay-scoped authored actions now also emit the same `onCommand` event family while still failing closed naturally when the same authored command is used outside gameplay scope.
  - [x] Successful direct session commands now also emit the same `onCommand` event family for `PLAY` and `LOGOUT` instead of leaving those command paths invisible beside durable gameplay and communication commands.
  - [x] Fresh PLAY admission now emits a real `onSpawn` event-ingress call with admitted gameplay routing identity and deterministic trigger ids.
  - [x] Durable movement execution now emits real `onLeaveRegion` / `onEnterRegion` event-ingress calls with deterministic effect-derived trigger ids.
  - [x] Gameplay disconnect teardown now emits the same `onLeaveRegion` event family for deliberate logout, transport loss, and takeover, with deterministic lifecycle-derived trigger ids and optional `exitReason` payload metadata.
  - [x] Runtime workers can claim pending durable work items by moving them from `PENDING_EVALUATION` to `EVALUATING`.
  - [x] Claimed durable `script_work_items` now execute through a first real evaluator and downstream tick handoff path.
- [x] Verify and close follow-ups.
  - [x] Child follow-through `10.1.1` closes built-in payload-contract parity for already-live producers and ingress/runtime readers.
  - [x] Child follow-through `10.1.2` closes plugin binding resolution on canonical runtime activation truth.
  - [x] Child follow-through `10.1.3` closes durable queued-player `onCommand` trigger-authority reuse.

##### source-10-1-task-list-script-event-ingress-and-handler-resolution-vertical-slice-1-92: Completion Evidence

- Parent implementation surfaces:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImpl.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AutomationScriptEventPublisher.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionService.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionService.java`
- Child completion docs:
  - `design/project-management/vertical-slices/10.1.1-task-list-built-in-event-payload-contract-parity-vertical-slice.md`
  - `design/project-management/vertical-slices/10.1.2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice.md`
  - `design/project-management/vertical-slices/10.1.3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice.md`
<!-- /migration-source -->

### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89

#### 10.1.1 Task List: Built-In Event Payload Contract Parity Vertical Slice - Built-in script event payload contract (source lines 1-89)

##### Preserved Source Text: source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89

<!-- migration-source path="design/project-management/vertical-slices/10.1.1-task-list-built-in-event-payload-contract-parity-vertical-slice.md" lines="1-89" sha256="b13ecdb414aebb0ce113675e6292e465d7fffff83afbba73471c4f1688b060d1" heading-offset="3" -->
#### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: 10.1.1 Task List: Built-In Event Payload Contract Parity Vertical Slice

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Goal and Status

Goal: make the live built-in script event producers and ingress/runtime readers converge on one explicit payload contract so registry-declared payload identity, required fields, and optional metadata are enforced consistently instead of drifting by producer family. Status: complete.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Current Snapshot (2026-06-29)

- This slice is currently `complete`.
- Built-in ingress now fail-closes malformed payloads for the already-live built-in event families covered by the registry contract, and gameplay producer proof asserts the required emitted payload fields for the live Game Session family.
- Accuracy note (2026-06-29): bounded contract convergence is complete for the already-live built-in families touched here (`onCommand`, `onSpawn`, `onEnterRegion`, `onLeaveRegion`, `onTimerExpire`, `onInterval`) without widening into new event-family design.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Why This Slice Exists

`10.1` already landed the core ingress, registry, audit, work-item, and first executor substrate. That leaves one bounded convergence seam that is well-suited to a smaller-context implementation batch:

- several built-in producers now emit canonical events (`onCommand`, `onSpawn`, `onEnterRegion`, `onLeaveRegion`, timer/event families), but payload shape parity can still drift between producer code, ingress assumptions, and registry declarations;
- one producer may include first-class payload fields while another still relies on optional blobs or omits a registry-declared required field;
- this is a classic “enumerate current producers, converge them onto one contract, add focused tests” task rather than a broad scripting redesign.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Scope

- built-in event manifest/registry entries for already-live event families;
- Game Session and Automation producer paths for already-live built-in events;
- ingress/runtime validation where those built-in payload fields are already intended to be first-class contract truth;
- focused tests for missing, contradictory, or malformed event payload families.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Out of Scope

- new event family design;
- broad plugin/custom event work;
- deeper scheduler/runtime execution semantics owned by `10.2` or `10.3`.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Locked Direction

- registry-declared built-in event payload contracts must be the source of truth for already-live producers;
- live producers should either provide the required contract shape or fail closed, not rely on downstream guesswork;
- ingress/runtime readers should prefer first-class contract fields over ad hoc payload interpretation when those fields already exist.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Planned Work

###### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: 1. Producer and Registry Audit

- [x] Enumerate already-live built-in event families and their current producer paths.
- [x] Record the registry-declared payload contract and any producer/reader drift for each touched family.
- [x] Keep the batch limited to already-live built-in events; do not invent new families.

###### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: 2. Contract Convergence

- [x] Update touched producers and ingress/runtime readers so required fields, optional fields, and first-class payload identity align with the registry contract.
- [x] Reject missing or contradictory required built-in payload fields where the contract already says they are mandatory.
- [x] Keep changes bounded to the event families covered by the audit.

###### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: 3. Focused Proof

- [x] Add or refresh focused tests for missing required payload fields, malformed built-in payload shape, and parity between registry declarations and live producer output.
- [x] Prove the touched event families remain durable/audit-compatible after convergence.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Completion Notes

- 2026-06-29: Completed. `ScriptEventIngressServiceImpl` now enforces minimum built-in payload contracts at admission time for the already-live built-in event families instead of trusting payload JSON shape implicitly after trigger-identity validation.
- 2026-06-29: Completed. Focused gameplay producer proof in `AutomationScriptEventPublisherTest` now asserts required `onCommand` payload identity fields directly, while existing spawn/region/timer proof remains aligned with the registry minimum fields.
- 2026-06-29: Focused proof added in `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java` and `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AutomationScriptEventPublisherTest.java`.
- 2026-06-29: The live `onCommand` producer payload now also carries additive action-classification enrichment (`actionCategory`, `actionTags[]`) resolved from the canonical command registry, while ingress continues to require only the documented minimum identity fields.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Acceptance Shape

- touched built-in event families have one consistent payload contract across registry, producer, ingress, and runtime use;
- missing or contradictory required built-in payload fields fail closed;
- focused proof covers the touched producer families end to end.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Spark Delegation Notes

- Start with one table of live built-in event families, producer files, and expected payload fields.
- Keep the batch on contract parity for already-live events only.
- Return exact changed files, exact touched event families, and exact validation commands run.

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Suggested Starting Surfaces

- `services/automation-scripting-service`
- `services/game-session-service`
- built-in event manifest/registry docs or resources already used by `10.1`

##### source-10-1-1-task-list-built-in-event-payload-contract-parity-vertical-slice-1-89: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93

#### 10.1.2 Task List: Plugin Binding Resolution Runtime-Activation Follow-Through Vertical Slice - Plugin binding runtime activation (source lines 1-93)

##### Preserved Source Text: source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93

<!-- migration-source path="design/project-management/vertical-slices/10.1.2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice.md" lines="1-93" sha256="bc54e404c3cb9378d471d9a9aca31218aeb63f13e102c06add3089f48726f488" heading-offset="3" -->
#### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: 10.1.2 Task List: Plugin Binding Resolution Runtime-Activation Follow-Through Vertical Slice

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Goal and Status

Goal: carry plugin event-binding resolution onto the now-live runtime activation truth so ingress no longer depends on weaker or older plugin/version assumptions once activation state is already canonical elsewhere. Status: complete.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Why This Slice Exists

`10.1` already landed plugin-trigger ingress fencing for missing, disabled, or displaced plugin versions, while `10.5` now owns real runtime activation state and append-only plugin runtime events. One bounded gap remains:

- plugin binding resolution can still lag behind the stronger runtime activation substrate;
- ingress should resolve plugin-owned bindings from the same activation truth operators and schedulers already use;
- later plugin event handling should not revive older publication- or request-shaped assumptions once runtime state is authoritative.

This slice is for converging plugin binding resolution onto runtime activation truth, not for redesigning plugin publication or runtime policy.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Scope

- plugin-owned event-binding resolution at Automation ingress and adjacent runtime readers;
- convergence between plugin trigger admission and the canonical runtime activation state;
- focused proof for displaced, stale, or mismatched plugin-binding resolution behavior.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Out of Scope

- publication-time plugin compatibility checks already owned by `10.5`;
- broader sandbox or script-execution policy work owned by `10.3`;
- custom event-family design beyond the touched plugin binding paths.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Locked Direction

- once plugin runtime activation state exists, ingress binding resolution should prefer that runtime truth directly;
- plugin-trigger ingress should fail closed when runtime activation and candidate binding identity disagree;
- publication truth and activation truth stay separate, but ingress should consume the right one for runtime handler resolution.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Planned Work

###### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: 1. Binding-Resolution Audit

- [x] Enumerate the current plugin-owned binding resolution paths that still rely on weaker publication/request assumptions after runtime activation is already known.
- [x] Record where runtime activation truth already exists but is not yet the owning read surface.
- [x] Keep the batch limited to plugin binding resolution and adjacent ingress/runtime readers.

###### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: 2. Runtime-Activation Convergence

- [x] Move touched plugin binding resolution onto the canonical runtime activation truth.
- [x] Remove touched displaced/stale fallback behavior that can admit plugin-owned bindings after activation truth has already moved on.
- [x] Keep operator/runtime semantics aligned with the existing activation substrate.

###### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: 3. Focused Proof

- [x] Add or refresh focused proof for displaced plugin versions, stale activation reads, and mismatched plugin-binding resolution.
- [x] Prove touched plugin-trigger ingress paths fail closed using the runtime activation contract.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Acceptance Shape

- touched plugin binding resolution paths consume canonical runtime activation truth;
- displaced or mismatched plugin binding state fails closed at ingress;
- focused proof is green for the touched activation-versus-binding seams.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Current Batch Notes

- Automation ingress now resolves plugin-owned handlers against the enabled plugin-version map for the current runtime scope instead of only trusting optional request `pluginId` / `pluginVersionId`.
- Resolved handler ownership now persists onto durable work-item and handler-audit rows, so later replay and handoff readers continue from handler truth rather than from producer request shape.
- Dead-letter replay now prefers the work item’s persisted plugin ownership and only falls back to ingress-audit plugin metadata for older rows that predate this convergence.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Completion Evidence

- Implementation points:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImpl.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImpl.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java`
- Focused tests:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImplTest.java`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImplTest.java`

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Spark Delegation Notes

- Start from one inventory of plugin binding readers, runtime activation readers, and where they currently diverge.
- Keep the work narrow: runtime-activation follow-through for plugin binding resolution only.
- Return exact changed files, exact touched plugin ingress paths, and exact validation commands run.

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Suggested Starting Surfaces

- `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/`
- `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/`
- `design/project-management/vertical-slices/10.1-task-list-script-event-ingress-and-handler-resolution-vertical-slice.md`

##### source-10-1-2-task-list-plugin-binding-resolution-runtime-activation-follow-through-vertical-slice-1-93: Validation

- `./gradlew spotlessApply :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.PluginRuntimeStateServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptEventIngressServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptWorkItemServiceImplTest'`
- `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83

#### 10.1.3 Task List: Durable `onCommand` Trigger Authority Follow-Through Vertical Slice - Durable onCommand trigger authority (source lines 1-83)

##### Preserved Source Text: source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/10.1.3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice.md" lines="1-83" sha256="abe6411691e8e8152932996347f6bb89ac1c1bc4e02cbc8766c90af4eaa1510a" heading-offset="3" -->
#### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: 10.1.3 Task List: Durable `onCommand` Trigger Authority Follow-Through Vertical Slice

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Goal and Status

Goal: make durable Game Session `onCommand` fallback reuse the same persisted trigger authority as live enqueue so session-backed command execution no longer depends on best-effort enqueue-time publish succeeding. Status: complete.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Why This Slice Exists

`10.1` already landed broad `onCommand` producer coverage, including queued gameplay commands at live enqueue and later sessionless durable execution paths. One bounded gap remained:

- session-backed durable gameplay commands still depended on the enqueue-time best-effort publish succeeding;
- if that best-effort publish missed, later durable execution had no safe fallback because the staged command row did not fully carry the original runtime trigger authority into the publisher seam;
- replay/no-op execution also needed to stay quiet so the fallback would not create new ingress truth for already-applied effects.

This slice closes that one authority-preservation seam without widening into broader producer-family design.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Scope

- Game Session staged `GameplayCommand` authority persistence for live player command admission;
- Game Session `onCommand` publish authority selection;
- durable gameplay execution fallback for live execution only;
- focused proof for persisted runtime scope and replay-safe fallback behavior.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Out of Scope

- new event-family design or broader Automation ingress changes;
- scheduler/timer-owned producer families already tracked elsewhere under `10.1` / `10.2`;
- publish-marker schema or cross-service dedupe redesign.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Locked Direction

- durable fallback should reuse persisted command authority rather than recalculate a newer runtime identity at execution time;
- live execution may republish `onCommand` from durable execution when the command identity is stable and Automation ingress already deduplicates that trigger identity;
- replay/no-op durable execution must not emit a fresh `onCommand` event.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Planned Work

###### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: 1. Persist Staged Command Authority

- [x] Carry the current runtime region scope onto live player-staged `GameplayCommand` rows.
- [x] Keep target entity and routing-bundle authority on the same staged row so later publish paths can reuse one source of truth.

###### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: 2. Durable Fallback Convergence

- [x] Make `AutomationScriptEventPublisher.publishCommandEvent(...)` prefer persisted command authority when present.
- [x] Republish `onCommand` from durable gameplay execution for live execution paths, not replay/no-op paths.
- [x] Keep lifecycle producers (`onSpawn`, `onEnterRegion`, `onLeaveRegion`) on their existing current-runtime authority path.

###### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: 3. Focused Proof

- [x] Prove staged player commands persist runtime scope needed for stable fallback.
- [x] Prove `publishCommandEvent(...)` prefers staged authority over drifted current context when that staged authority is present.
- [x] Prove live durable execution publishes `onCommand` while replay/no-op durable execution does not.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Acceptance Shape

- live player-staged gameplay commands persist the runtime trigger scope needed for later `onCommand` fallback;
- durable gameplay execution republishes `onCommand` for live execution even when the enqueue-time best-effort publish missed;
- replay/no-op execution stays quiet;
- focused proof is green on the touched Game Session seams.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Completion Notes

- `CommandServiceImpl` now persists `targetEntityId`, `regionId`, and `regionEpoch` on staged player command rows when current runtime ownership is known, alongside the already-carried routing bundle.
- `AutomationScriptEventPublisher.publishCommandEvent(...)` now prefers persisted `GameplayCommand` runtime authority when present and only falls back to current runtime ownership/context when the command row does not already carry that authority.
- `DefaultDurableGameplayCommandExecutionService` now republishes `onCommand` only on live durable execution paths, after replay gates but before handler application, so durable fallback is available without waking replay/no-op runs.

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Completion Evidence

- Implementation points:
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/CommandServiceImpl.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AutomationScriptEventPublisher.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionService.java`
- Focused tests:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/CommandServiceImplTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/AutomationScriptEventPublisherTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest.java`

##### source-10-1-3-task-list-durable-oncommand-trigger-authority-follow-through-vertical-slice-1-83: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest' --tests 'net.firedevops.firemud.gamesession.service.impl.DefaultDurableGameplayCommandExecutionServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.AutomationScriptEventPublisherTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44

#### Script Timer, Scheduler, and Runtime Ownership Vertical Slice - Timer and scheduler runtime ownership (source lines 1-44)

##### Preserved Source Text: source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44

<!-- migration-source path="design/project-management/vertical-slices/10.2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice.md" lines="1-44" sha256="2587d5b86c449b0801eab424740ead45901e89ee0b04c6080bdfd74cd79e05cb" heading-offset="3" -->
#### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Script Timer, Scheduler, and Runtime Ownership Vertical Slice

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Goal and Status

Goal: lock the first canonical scheduler/timer ownership model for scripting so interval and timer triggers, leader catch-up, region fencing, and plugin-owned schedules all follow one explicit runtime contract. Status: complete for the first ownership model.

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Why This Slice Exists

Timers and scheduler coordination are among the highest-risk scripting seams, but they are still easy to treat as internal service detail. This slice isolates the runtime ownership model before more scripting behavior accumulates on top of it.

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Implementation Notes

The human direction for this slice is no longer the blocker: scheduler ownership is now instance-aware, fenced, and backed by durable timer identity. Automation persists a patch-scoped `script_schedule_definitions` catalog from compiled script payloads during `NotifyScriptVersionUpdate`, keyed by `scheduleDefinitionId` with cadence/unit metadata and duplicate detection per patch scope. It materializes observed pinned patches into durable `script_schedule_instances` rows keyed by `(tenantId, gameInstanceId, plugin owner metadata, binding target identity, scheduleDefinitionId)`, and plugin-owned schedules are filtered through the live `(tenantId, gameInstanceId, pluginId)` runtime registry so only the currently enabled `pluginVersionId` keeps schedule ownership. Game Session advances durable `lastCommittedTickId` on its runtime ownership row after successful tick cycles, exposes that progress through runtime ownership status, and publishes `ObserveRuntimeTickProgress` to Automation with `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`. Game Session runtime-state reads now also expose the currently admitted `worldSlug`, `realmSlug`, `pointerVersion`, and `playableStateScope`, and Automation persists that routing bundle on pin projections and `script_schedule_instances` so scheduler-owned follow-up work reuses the same admitted routing truth as gameplay-originated ingress. Automation records those runtime progress fields on tick-aligned schedule rows, derives the next future `nextDueTickId`, emits deterministic timer-derived work items into the same outbox/audit/queue path used by other script events when due ticks are reached, fires wall-clock `onTimerExpire` schedules through the same fenced identity model, and orders mixed due candidates deterministically. Catch-up candidates are selected in round-robin passes across schedule identities, capped by `script.scheduler.max-catch-up-firings-per-observation`, surfaced in the progress response, and written to `script_event_audit` with bounded `catch_up_truncated` reasons when the cap drops them. Runtime-scope changes fence both tick and wall-clock due points before new work is minted: stale due points are dropped with `runtime_scope_changed`, `automation_script_timer_runtime_fence_dropped_total`, and old-scope audit identity rather than being silently re-emitted under the new region epoch, while both emitted work items and skipped-audit rows preserve the current admitted routing bundle instead of blank scheduler-local placeholders. Control-plane timer audit reads now expose those scheduler-owned audit rows directly, including plugin provenance, due-point identity, routing bundle, and skipped-versus-persisted outcomes, so truncation/fence behavior is no longer write-only.

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Scope

- script timers versus tick timers
- scheduler leadership and catch-up rules
- durable timer identity and ownership across `tenantId`, `gameInstanceId`, and `regionEpoch`
- plugin-owned timer reconciliation during activation/rollback/disablement

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Out of Scope

- general runtime durability hardening outside scripting-owned scheduler truth
- event ingress API shape beyond what timer-derived triggers require
- design-time plugin publication metadata

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Locked Direction

- scheduler ownership is instance-scoped and fenced; stale executors must not mint new timer-derived triggers.
- timer identities must be explicit enough for rollback, catch-up, and plugin-version changes to converge safely.
- plugin-owned schedules are runtime state and must be reconciled explicitly during activation changes.
- best-effort timer semantics remain explicit; the system must not imply stronger guarantees than documented.

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Current Remaining Work

- [x] No known first-ownership-model work remains in this slice.
- [x] Keep richer scheduler dashboards and any later explicit leader-checkpoint substrate in later observability or coordination follow-through rather than reopening this ownership-model cut.

##### source-10-2-task-list-script-timer-scheduler-and-runtime-ownership-vertical-slice-1-44: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the remaining catch-up firing and reconciliation work end to end on top of the live instance materialization substrate.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80

#### Script Execution Budgets, Quotas, and Isolation Vertical Slice - Script execution budgets and quotas (source lines 1-80)

##### Preserved Source Text: source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80

<!-- migration-source path="design/project-management/vertical-slices/10.3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice.md" lines="1-80" sha256="b82bc36f83c4eb9e990c772e9c857a5c198f32d05aa5009d126322cd4804afbb" heading-offset="3" -->
#### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Script Execution Budgets, Quotas, and Isolation Vertical Slice

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Goal and Status

Goal: make per-script quotas, tenant automation budgets, sandbox isolation, and admission-time fairness one bounded execution slice so runtime behavior under load is governed by one canonical policy model instead of scattered knobs. Status: complete at the current bounded boundary.

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Why This Slice Exists

The repo already has real quota and runtime substrate, but planning coverage for execution fairness is still indirect. This slice turns the existing architecture into one bounded implementation target shared by Automation runtime, operators, and downstream gameplay callers.

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Implementation Notes

Implementation has landed at the current bounded boundary. The concrete cuts wire the documented terminal outbox retention knobs into Automation & Scripting configuration and a scheduled cleanup path for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` `script_work_items`, enforce the serialized work-item payload-size ceiling at event ingress before persistence, enforce command-count output ceilings when claimed work items are evaluated, charge live per-script quota at handler admission before durable work-item materialization, persist binding `priorityTag` and canonical registry `quotaClass` onto durable work items, reserve live tenant budget against that persisted policy surface before durable work-item execution, reserve dedicated tenant and cluster execution capacity for live `PUBLISH_READINESS` `onLoad` work with deterministic `onload_budget_exceeded` cancellation, enforce dedicated dry-run per-minute tenant/principal quotas before handler resolution, reserve isolated tenant-local and cluster-wide dry-run execution capacity before dry-run evaluation, skip live `ScriptQuotaService` and tenant-budget charging for dry-run executions, make Automation pin/rollout projection freshness use the supported `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` runtime knob instead of a hardcoded service threshold, make plugin-trigger ingress fail closed when signer/component-policy evidence is stale, and emit a durable work-item outcome counter using the same stage/outcome vocabulary as audit rows plus first-class `sourceKind`, `sourceService`, `eventType`, `priorityTag`, and `dryRun` breakdown tags. Claimed work-item evaluation now enforces both total command count and per-target command count against emitted command target identities. Broader sandbox execution policy paths are now landed in this bounded target; broader sandbox architecture extension belongs to future slices.

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Scope

- event admission versus execution-time budget consumption
- per-script quotas and per-tenant automation budgets
- sandbox resource isolation and capacity partitioning
- canonical outcome mapping for quota/backpressure/policy denials

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Out of Scope

- design-time validation/editor limits except where they enforce runtime invariants
- timer ownership mechanics beyond their budget interaction
- general cluster-level quota systems outside scripting-owned runtime execution

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Locked Direction

- quota denials, backpressure, and policy blocks are different runtime outcomes and must remain distinguishable.
- live traffic and privileged dry-run/test capacity must remain isolated enough that tooling cannot starve gameplay automation.
- budget charging points must be explicit; the system must not imply refunds or cost semantics it does not honor.
- operator-facing metrics and runtime outcomes must map directly to the same execution-policy model.

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Completion Evidence

- Live budget charging and quota enforcement in the first bounded implementation surface:
  - [ScriptEventIngressServiceImpl.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImpl.java)
  - [ScriptOutputProperties.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/config/ScriptOutputProperties.java)
  - [ScriptWorkItemRepository.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/repository/ScriptWorkItemRepository.java)
  - [ScriptWorkItemExecutionServiceImpl.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImpl.java)
- Targeted proof for budget outcomes and policy gates:
  - [ScriptWorkItemExecutionServiceImplTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImplTest.java)
  - [ScriptEventIngressServiceImplTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java)
  - [V1__baseline.sql](../../../services/automation-scripting-service/src/main/resources/db/migration/V1__baseline.sql)
  - [V2__add_script_work_item_quota_class.sql](../../../services/automation-scripting-service/src/main/resources/db/migration/V2__add_script_work_item_quota_class.sql)
  - [V3__add_script_event_ingress_audit_quota_class.sql](../../../services/automation-scripting-service/src/main/resources/db/migration/V3__add_script_event_ingress_audit_quota_class.sql)

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Deferred Follow-ups

- [x] Extend the now-live per-script and priority-tagged tenant-budget charging into the current live durable work-item producer families. The bounded follow-up [`10.3.1`](../vertical-slices/10.3.1-task-list-source-aware-budget-charging-follow-through-vertical-slice.md) is now complete for gameplay-trigger ingress and scheduler timer emission; future new producer families should extend the same durable source-truth contract instead of reopening request-local policy.
- [x] Carry registry `quotaClass` onto durable work-item policy so readiness-vs-runtime charging no longer re-infers from `eventType`. The bounded follow-up [`10.3.3`](../vertical-slices/10.3.3-task-list-durable-quota-class-runtime-convergence-vertical-slice.md) is now complete.
- [x] Give live `PUBLISH_READINESS` work explicit bounded execution capacity instead of an unbounded live-budget bypass. The bounded follow-up [`10.3.4`](../vertical-slices/10.3.4-task-list-publish-readiness-capacity-convergence-vertical-slice.md) is now complete.
- [x] Keep dry-run/test isolation on the same canonical budget model rather than a sidecar shortcut; live quota/budget bypass, first per-minute dry-run rate limits, tenant-local dry-run capacity reservation, and cluster-wide dry-run capacity reservation are now enforced.
- [x] Extend operator metrics beyond the current durable work-item outcome counter, which now already breaks outcomes down by `sourceKind`, `sourceService`, `eventType`, `priorityTag`, and `dryRun`, into broader dashboards and alerting as more producers land. The bounded follow-up [`10.3.2`](../vertical-slices/10.3.2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice.md) is now complete.
- [x] Keep later producer families and broader sandbox execution policy paths on the same bounded execution-policy contract instead of reopening request-local quota, budget, freshness, or isolation policy; outbox-retention cleanup, serialized work-item payload-size rejection, target-aware command output ceilings, pin-projection freshness, and plugin-policy freshness are now live.

##### source-10-3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice-1-80: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
  - [x] Terminal outbox cleanup honors documented retention knobs for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` work items.
  - [x] Event ingress rejects payloads larger than `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` before durable work-item persistence.
  - [x] Live per-script quota is charged at handler admission; quota-denied handlers write handler audit rows without materializing work items.
  - [x] Registry `quotaClass` now persists onto durable work items and is reused at execution-time budget policy so readiness-vs-runtime charging does not fall back to `eventType` inference.
  - [x] Live `PUBLISH_READINESS` work now reserves dedicated readiness capacity and fails with `onload_budget_exceeded` when that bounded substrate is full.
  - [x] Claimed work-item evaluation rejects command fan-out that exceeds documented per-run and per-target command ceilings.
  - [x] Live work-item execution reserves priority-tagged tenant runtime budget before script definition evaluation and cancels budget-denied work with `tenant_budget_exceeded` audit outcomes.
  - [x] Dry-run execution skips live per-script quota and tenant-budget acquisition and never hands commands to Game Session.
  - [x] Dry-run ingress enforces `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` and `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL` before handler resolution.
  - [x] Dry-run execution reserves isolated tenant-local capacity via `SCRIPT_TEST_MAX_CONCURRENCY` and cancels capacity-denied work with `dry_run_capacity_exhausted`.
  - [x] Dry-run execution also reserves isolated cluster-wide capacity via `SCRIPT_TEST_MAX_CLUSTER_CONCURRENCY` so test traffic sheds before it can saturate shared Automation execution.
  - [x] Automation pin/rollout convergence reads use `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` for projection stale detection.
  - [x] Plugin-trigger ingress fails closed when enabled-plugin signer/component-policy evidence exceeds `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS`.
  - [x] Durable work-item execution emits `automation_script_work_item_outcomes_total` with the same stage/outcome vocabulary written to `script_event_audit`.
  - [x] Durable work-item outcome metrics now also tag `sourceKind`, `sourceService`, `eventType`, `priorityTag`, and `dryRun` directly so operator breakdowns do not need to infer source families from unrelated logs or row inspection.
  - [x] Current live durable work-item producers now prove the same persisted `priorityTag` / source metadata budget contract for both gameplay-trigger and scheduler timer work items via `10.3.1`, including explicit timer-side `quotaClass=STANDARD_RUNTIME` persistence.
  - [x] Current live readiness-vs-runtime charging now also proves one persisted registry `quotaClass` contract via `10.3.3`.
  - [x] Current live `PUBLISH_READINESS` execution now also proves dedicated bounded readiness capacity via `10.3.4`.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96

#### 10.3.1 Task List: Source-Aware Budget Charging Follow-Through Vertical Slice - Durable source-aware budget charging (source lines 1-96)

##### Preserved Source Text: source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96

<!-- migration-source path="design/project-management/vertical-slices/10.3.1-task-list-source-aware-budget-charging-follow-through-vertical-slice.md" lines="1-96" sha256="1c28de52672f48b5fa012c78c7cf8a13e4994e9dcf6e14912150582a1c17e5b9" heading-offset="3" -->
#### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: 10.3.1 Task List: Source-Aware Budget Charging Follow-Through Vertical Slice

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Goal and Status

Goal: carry the now-live per-script and priority-tagged tenant-budget charging onto every current durable `script_work_items` producer family so budget policy is derived from one persisted source-truth contract instead of drifting by ingress path. Status: complete at the current bounded boundary.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Why This Slice Exists

`10.3` already made live execution reserve tenant budget against persisted `priorityTag` and emit outcome metrics with `sourceKind`, `sourceService`, and `eventType`. One bounded policy gap remains:

- not every current durable work-item producer family is yet explicitly audited against that charging contract;
- some source families may still depend on inherited defaults or weaker request-shaped source metadata instead of one durable row authority;
- operator policy becomes harder to reason about if two producers can mint equivalent work under different budget semantics.

This slice keeps the work narrow: it is about charging-policy convergence across current scripting work-item producers, not a broader quota redesign.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Scope

- current durable `script_work_items` producer families in Automation & Scripting;
- persisted source identity, `quotaClass`, and `priorityTag` truth used by budget charging;
- focused proof that charging and denial outcomes stay consistent across the touched producer families.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Out of Scope

- broader sandbox isolation work beyond the budget contract itself;
- dashboards/alerting follow-through, which belongs in `10.3.2`;
- future producer families that do not exist yet.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Locked Direction

- tenant-budget charging must derive from durable work-item source truth, not from caller-local inference;
- equivalent producer families should not bypass or weaken the same budget policy just because they enter through a different ingress path;
- source-aware charging belongs on the persisted work-item boundary so later replay, retry, and operator reads see the same truth.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Implementation Notes

- Current live budget-charged `script_work_items` producer families are now explicitly audited as gameplay-trigger ingress in `ScriptEventIngressServiceImpl` and scheduler timer emission in `ScriptScheduleInstanceServiceImpl`.
- Both producer families persist the charging inputs that execution consumes later from the durable row itself: `sourceKind`, `sourceService`, `eventType`, `quotaClass`, and `priorityTag`.
- Timer work-item persistence now explicitly stamps `quotaClass` as `STANDARD_RUNTIME`.
- Focused execution proof now confirms tenant-budget denial uses those persisted row fields for both producer families instead of request-local inference or one producer-specific default path.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Planned Work

###### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: 1. Producer Audit

- [x] Enumerate every current durable `script_work_items` producer family that can reach live budget charging.
- [x] Record which source fields each producer persists today (`sourceKind`, `sourceService`, `eventType`, `priorityTag`, and any source-specific policy inputs).
- [x] Skip producer families already fully converged or not yet live.

###### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: 2. Charging-Contract Convergence

- [x] Repair any touched producer path that still relies on weaker request-local or fallback source semantics before budget reservation.
- [x] Keep budget reservation and denial outcomes derived from durable row authority for each touched source family.
- [x] Preserve canonical denial vocabulary instead of creating producer-specific budget failure semantics.

###### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: 3. Focused Proof and Docs

- [x] Add or refresh focused tests proving source-aware budget charging across the touched producer families.
- [x] Update `10.3` docs/status notes so the surviving open work is explicit after this cut.
- [x] Re-run touched-service validation and Markdown/link proof.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Completion Evidence

- Gameplay-trigger ingress persists durable charging-source truth in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`
- Scheduler timer emission persists the same durable charging-source truth in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptScheduleInstanceServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptScheduleInstanceServiceImplTest.java`
- Tenant-budget denial consumes the durable row fields for both producer families in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImplTest.java`

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Acceptance Shape

- every current live durable work-item producer family reaches tenant-budget charging through one persisted source-truth contract;
- touched producers no longer depend on weaker request-local or implicit source-policy defaults;
- focused proof covers both accepted and budget-denied outcomes for the touched source families.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Spark Delegation Notes

- Keep the batch bounded to current live durable work-item producers only.
- Audit producers first, then repair all still-valid charging gaps in one pass.
- Return exact producer families covered, exact changed files, and exact validation commands run.

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Suggested Starting Surfaces

- `services/automation-scripting-service`
- `design/project-management/vertical-slices/10.3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice.md`

##### source-10-3-1-task-list-source-aware-budget-charging-follow-through-vertical-slice-1-96: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:test --tests 'unit.net.firedevops.firemud.automationscripting.service.impl.ScriptEventIngressServiceImplTest' --tests 'unit.net.firedevops.firemud.automationscripting.service.impl.ScriptScheduleInstanceServiceImplTest' --tests 'unit.net.firedevops.firemud.automationscripting.service.impl.ScriptWorkItemExecutionServiceImplTest'`
- `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94

#### 10.3.2 Task List: Script Execution Operator Metrics and Alerting Convergence Vertical Slice - Automation execution observability (source lines 1-94)

##### Preserved Source Text: source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94

<!-- migration-source path="design/project-management/vertical-slices/10.3.2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice.md" lines="1-94" sha256="bc9aa11c973884539a951ac6e79681fd893dc6c0b6d40420b1db97a9c260575a" heading-offset="3" -->
#### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: 10.3.2 Task List: Script Execution Operator Metrics and Alerting Convergence Vertical Slice

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Goal and Status

Goal: extend the now-live durable work-item outcome metrics into one bounded operator-facing execution-policy observability contract so quota denials, tenant-budget pressure, and dry-run isolation failures are directly visible without log spelunking. Status: complete.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Why This Slice Exists

`10.3` already emits `automation_script_work_item_outcomes_total` with first-class source and policy tags. That is a strong base, but one bounded operator gap remains:

- the current outcome counter is not yet the whole execution-policy observability story;
- later operators should not need to infer budget starvation, quota pressure, or dry-run isolation failures from mixed logs and row inspection;
- repo-owned monitoring assets should teach one canonical breakdown for scripting execution policy before more producer families land.

This slice is about bounded execution-policy observability, not a general monitoring platform redesign.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Scope

- scripting execution-policy metrics beyond the current outcome counter where they are needed for operator truth;
- repo-owned alerting, rule, or documentation updates tied directly to the touched metrics;
- focused proof for the touched observability contract.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Out of Scope

- broader Grafana or hosted-observability product work outside the scripting execution-policy seam;
- changing budget or quota semantics themselves;
- unrelated metrics cleanup outside touched scripting policy surfaces.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Locked Direction

- operator observability should map directly to the same execution-policy model the runtime enforces;
- quota denial, tenant-budget exhaustion, and dry-run capacity exhaustion remain distinguishable truths;
- repo-owned monitoring assets should prefer explicit bounded metrics over log-derived heuristics.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Planned Work

###### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: 1. Observability Gap Audit

- [x] Audit the current scripting execution-policy metrics and identify the smallest still-valid gaps after the live outcome counter.
- [x] Enumerate which operator questions still require log or row inspection instead of canonical metrics.
- [x] Keep the batch bounded to real execution-policy gaps, not every possible dashboard idea.

###### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: 2. Metric and Monitoring Follow-Through

- [x] Confirm the minimal additional metric surface for this bounded pass is alert-level coverage over the existing quota/budget/dry-run counters; no new live counters were required.
- [x] Update repo-owned alerting or monitoring docs/assets for the touched execution-policy contract.
- [x] Keep names, tags, and meanings aligned with the canonical runtime outcome vocabulary in the new alerting bundle.

###### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: 3. Proof and Status Refresh

- [x] Add focused proof for the touched metrics or monitoring assets.
- [x] Update `10.3` slice notes so the remaining observability tail is explicit after this cut.
- [x] Re-run touched validation and Markdown/link proof.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Completion Evidence

- Operator-facing observability assets were added and split into a dedicated snippet file at:
  - `design/observability/grafana/scripting-execution-policy-alerts-snippets.md`
- The core observability index now links that new snippet from:
  - `design/observability/grafana/core-alerts-snippets.md`
- No new budget/capacity metrics were added in this slice because the required tagged counters already exist in the live runtime path; this pass adds alerting and proof wiring around them.
- Runtime counter inputs already in the target seam and used by the new alerting bundle are:
  - `automation_script_work_item_outcomes_total`
  - `automation_script_tenant_budget_allowed_total`
  - `automation_script_tenant_budget_denied_total`
  - `automation_script_test_capacity_denied_total`
  - `script_quota_allowed_total`
  - `script_quota_denied_total`

Prometheus expressions in the new alerting snippet include explicit checks for tenant-budget denial spikes, quota-denial ratio, dry-run capacity saturation, and elevated work-item outcomes.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Acceptance Shape

- the touched scripting execution-policy questions can be answered from canonical metrics and repo-owned monitoring assets rather than mixed logs;
- quota, tenant-budget, and dry-run isolation outcomes remain explicitly distinguishable in operator proof;
- focused proof covers the touched observability contract.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Spark Delegation Notes

- Keep the batch on bounded scripting execution-policy observability only.
- Audit first; do not invent broad dashboard work that is not justified by a current operator gap.
- Return exact metrics/assets added or changed, exact operator questions covered, and exact validation commands run.

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Suggested Starting Surfaces

- `services/automation-scripting-service`
- `observability/`
- `design/project-management/vertical-slices/10.3-task-list-script-execution-budgets-quotas-and-isolation-vertical-slice.md`

##### source-10-3-2-task-list-script-execution-operator-metrics-and-alerting-convergence-vertical-slice-1-94: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93

#### 10.3.3 Task List: Durable Quota-Class Runtime Convergence Vertical Slice - Durable quota-class enforcement (source lines 1-93)

##### Preserved Source Text: source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93

<!-- migration-source path="design/project-management/vertical-slices/10.3.3-task-list-durable-quota-class-runtime-convergence-vertical-slice.md" lines="1-93" sha256="46ebfbdebd15752f08f7416d40475dfc98c3c2202a727af28e836ce3e7ca146f" heading-offset="3" -->
#### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: 10.3.3 Task List: Durable Quota-Class Runtime Convergence Vertical Slice

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Goal and Status

Goal: carry canonical event-registry `quotaClass` truth onto durable `script_work_items` so both handler-admission quota and execution-time tenant-budget policy read the same persisted classification instead of re-inferring `onLoad` behavior from `eventType`. Status: complete at the current bounded boundary.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Why This Slice Exists

`10.3` already established the current live budget model, but one policy seam remained open:

- the event registry exposed `quotaClass`, including `onLoad` readiness semantics, yet the built-in registry still mislabeled `onLoad` as `STANDARD_RUNTIME`;
- ingress and execution skipped live quota and tenant-budget charging for `onLoad` through event-name inference instead of one canonical persisted policy surface;
- operator reads and later execution paths could therefore drift from registry truth because durable work rows did not preserve the classification that admission had already decided.

This slice keeps the cut narrow: it is about durable quota-class convergence for current live scripting execution, not broader readiness-capacity implementation.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Scope

- built-in scripting event-registry `quotaClass` truth for `onLoad`;
- durable `script_work_items` persistence of `quotaClass`;
- current live per-script quota and tenant-budget charge points that must consume the same classification;
- focused proof and architecture notes for the converged contract.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Out of Scope

- new publish-time concurrency/capacity limiters for `PUBLISH_READINESS`;
- broader sandbox or scheduler policy redesign outside current charge-point convergence;
- unrelated `onLoad` lifecycle/read-model work already owned by `10.5`.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Locked Direction

- budget policy must come from canonical registry truth and survive onto the durable work row;
- execution-time policy must not guess readiness-vs-runtime charging from `eventType` when the registry already classifies it;
- `PUBLISH_READINESS` work must stay outside ordinary live per-script quota and tenant runtime budget charging.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Implementation Notes

- Built-in event-registry truth now classifies `onLoad` as `PUBLISH_READINESS` instead of `STANDARD_RUNTIME`.
- Automation now persists registry `quotaClass` onto each durable `script_work_item` through ingress.
- Handler-admission quota still charges only `STANDARD_RUNTIME` work.
- Execution-time tenant-budget reservation now reads durable `quotaClass` from the row itself instead of inferring readiness behavior from `eventType`.
- Current readiness semantics that are genuinely about the event kind, such as `onLoad` patch-readiness success handling and command prohibition, still remain event-type-based and are intentionally not folded into the budget class.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Planned Work

###### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: 1. Durable Contract Audit

- [x] Confirm whether `script_work_items` already persist quota-class truth.
- [x] Confirm whether current live budget checks still depend on `onLoad` event-name inference.
- [x] Keep the change bounded to the existing `10.3` charge-point contract.

###### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: 2. Runtime Convergence

- [x] Correct the built-in registry entry so `onLoad` advertises the readiness budget class.
- [x] Persist `quotaClass` onto durable `script_work_items`.
- [x] Make handler-admission quota and execution-time tenant-budget policy read the same classification.

###### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: 3. Proof and Docs

- [x] Refresh focused proof for registry visibility, ingress persistence, and execution-time bypass behavior.
- [x] Update the `10.3` parent slice and nearby architecture docs to describe the canonical contract directly.
- [x] Re-run touched-service and Markdown/link validation.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Completion Evidence

- Shared quota-class constants and current charge-point helpers live in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/ScriptQuotaClasses.java`
- Built-in registry `onLoad` truth now exposes `PUBLISH_READINESS` in:
  - `services/automation-scripting-service/src/main/resources/script-event-registry/built-in-events.json`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationEventControlPlaneServiceTest.java`
- Durable work-item persistence now carries `quotaClass` in:
  - `services/automation-scripting-service/src/main/resources/db/migration/V1__baseline.sql`
  - `services/automation-scripting-service/src/main/resources/db/migration/V2__add_script_work_item_quota_class.sql`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/entity/ScriptWorkItem.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/repository/ScriptWorkItemRepository.java`
- Ingress now stamps registry `quotaClass` onto work items and uses that same class for live per-script quota behavior in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptEventIngressServiceImplTest.java`
- Execution-time tenant-budget reservation now reads durable `quotaClass` instead of `onLoad` event-name inference in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImplTest.java`

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Acceptance Shape

- registry `quotaClass` truth is durable and queryable on every current live `script_work_item`;
- readiness-vs-runtime budget behavior no longer depends on ad hoc execution-time `eventType` inference;
- focused proof shows `onLoad` still bypasses ordinary live quota/budget through the persisted readiness class while ordinary runtime work remains chargeable.

##### source-10-3-3-task-list-durable-quota-class-runtime-convergence-vertical-slice-1-93: Validation

- `./gradlew spotlessApply :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptEventIngressServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptWorkItemExecutionServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.AutomationEventControlPlaneServiceTest'`
- `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87

#### 10.3.4 Task List: Publish-Readiness Capacity Convergence Vertical Slice - Readiness execution capacity (source lines 1-87)

##### Preserved Source Text: source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87

<!-- migration-source path="design/project-management/vertical-slices/10.3.4-task-list-publish-readiness-capacity-convergence-vertical-slice.md" lines="1-87" sha256="41faf16a4fa4d6771dfbce14c380bedfb661028b2b2275264c4ea5ec2900efc7" heading-offset="3" -->
#### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: 10.3.4 Task List: Publish-Readiness Capacity Convergence Vertical Slice

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Goal and Status

Goal: give live `PUBLISH_READINESS` `onLoad` work its own bounded execution-capacity substrate so readiness no longer bypasses live runtime budgets without any explicit replacement limit, and so exhausted readiness capacity fails with one deterministic `onload_budget_exceeded` outcome. Status: complete at the current bounded boundary.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Why This Slice Exists

`10.3.3` converged readiness-vs-runtime charging onto persisted `quotaClass`, but it intentionally did not add the missing readiness-capacity substrate itself. That left one real policy gap:

- live `PUBLISH_READINESS` work no longer consumed ordinary tenant runtime budget, but it still had no explicit tenant or cluster concurrency ceiling of its own;
- the architecture already required dedicated publish/readiness capacity and named `onload_budget_exceeded` as the bounded failure shape;
- patch readiness projections already preserved `onLoad` cancel/dead-letter reasons, so the missing piece was the execution-capacity reservation itself rather than another read-model redesign.

This slice stays narrow: it adds bounded readiness-capacity reservation for current live `onLoad` execution, not broader publish-time timeout or retry redesign.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Scope

- dedicated tenant and cluster concurrency knobs for live `PUBLISH_READINESS` execution;
- Redis-backed readiness-capacity reservation and release;
- durable work-item execution cancellation when readiness capacity is exhausted;
- focused proof and documentation for the new readiness-capacity contract.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Out of Scope

- publish-time timeout or memory ceilings beyond current executor behavior;
- broader readiness retry-orchestration changes;
- dry-run/test capacity, which already belongs to the separate `script.test` substrate.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Locked Direction

- live readiness work must not borrow ordinary tenant runtime budget and also must not run without any explicit replacement bound;
- capacity policy must stay on the execution boundary where durable work is claimed;
- readiness-capacity exhaustion must surface as one bounded reason, `onload_budget_exceeded`, so projections and operators can distinguish it from ordinary live quota or tenant-budget denials.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Implementation Notes

- Automation now exposes dedicated readiness-capacity knobs under `script.readiness.*`, with tenant and cluster concurrency ceilings.
- Redis-backed readiness-capacity reservation mirrors the existing dry-run capacity substrate but uses a separate key family and metric family.
- Live `PUBLISH_READINESS` work now reserves readiness capacity before DSL evaluation and releases it afterward.
- If readiness capacity cannot be reserved, the durable work item is canceled with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=onload_budget_exceeded`.
- Existing readiness projection refresh already consumes that cancellation reason, so no separate projection contract change was needed.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Planned Work

###### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: 1. Capacity Substrate

- [x] Add dedicated readiness-capacity configuration and Redis key space.
- [x] Implement bounded tenant and cluster readiness-capacity reservation.
- [x] Keep the substrate separate from ordinary live tenant-budget and dry-run/test capacity.

###### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: 2. Execution Convergence

- [x] Reserve readiness capacity for live `PUBLISH_READINESS` work before DSL evaluation.
- [x] Cancel exhausted readiness work with deterministic `onload_budget_exceeded`.
- [x] Preserve current event-type-based readiness semantics that are not really capacity policy.

###### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: 3. Proof and Docs

- [x] Add focused proof for readiness-capacity reservation and exhaustion.
- [x] Update `10.3` parent and nearby architecture/config docs.
- [x] Re-run touched-service and Markdown/link validation.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Completion Evidence

- Dedicated readiness-capacity properties and Redis key builders now live in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/config/ScriptReadinessCapacityProperties.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/redis/AutomationRedisKeys.java`
- The readiness-capacity reservation substrate now lives in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/quota/ScriptReadinessCapacityService.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/quota/ScriptReadinessCapacityServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/quota/ScriptReadinessCapacityServiceImplTest.java`
- Durable execution now reserves readiness capacity and emits `onload_budget_exceeded` on exhaustion in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImpl.java`
  - focused proof: `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemExecutionServiceImplTest.java`

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Acceptance Shape

- live `PUBLISH_READINESS` work has explicit bounded tenant and cluster capacity instead of an unbounded live-budget bypass;
- readiness-capacity exhaustion produces one deterministic bounded reason, `onload_budget_exceeded`;
- focused proof shows readiness success releases reservations and capacity exhaustion cancels before DSL evaluation.

##### source-10-3-4-task-list-publish-readiness-capacity-convergence-vertical-slice-1-87: Validation

- `./gradlew spotlessApply :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptWorkItemExecutionServiceImplTest' --tests 'net.firedevops.firemud.automationscripting.service.quota.ScriptReadinessCapacityServiceImplTest'`
- `dev-tools/validation/run-locked-gradle.sh :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65

#### Automation Handoff and Tick Integration Vertical Slice - Automation handoff and tick integration (source lines 1-65)

##### Preserved Source Text: source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65

<!-- migration-source path="design/project-management/vertical-slices/10.4-task-list-automation-handoff-and-tick-integration-vertical-slice.md" lines="1-65" sha256="6f811d875d0ce88c02a9fec4636292b19eab958e76facf48a1f03c5757654223" heading-offset="3" -->
#### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Automation Handoff and Tick Integration Vertical Slice

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Goal and Status

Goal: keep the Automation & Scripting to Game Session handoff on one canonical outbox/queue/tick seam so scripting-generated commands enter gameplay execution through the same tick safety model as player commands. Status: complete for the current handoff seam.

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Why This Slice Exists

This seam is already central to the runtime, but it is not yet represented as a dedicated slice. Without one, future scripting work can drift into direct tick writes, partial queue shortcuts, or unclear ownership between Automation and Game Session.

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Implementation Notes

Automation-owned Redis queue and quota helpers now use the canonical instance-aware `automation:queue:{tenantInstanceTag}:*` and `automation:quota:<tenantId>:<scriptId>` key families, while the older `automation:tick:*` staging path has been retired from the live/runtime design because durable outbox execution owns the real handoff seam. `automation:queue:*` entries are the documented durable outbox-pointer envelope (`schemaVersion`, `outboxWorkItemId`, and bounded identity metadata) rather than raw event payloads. Game Session exposes the idempotent `EnqueueAutomationCommandIfAbsent` handoff surface and persists the automation dispatch/work-item correlation on the gameplay command ledger before staging the command into the tick queue. That handoff rejects new automation commands when durable runtime ownership is missing, paused, or on a different `regionEpoch`, while still returning duplicate/no-op for an already-recorded dispatch. Numeric gameplay `targetEntityId` values are now also captured as `characterId` on staged automation command rows, so the durable gameplay executor can resolve an active session by gameplay identity when the handoff correctly has no caller-owned `sessionId`. Automation has the matching handoff service boundary: given an emitted command from a durable work item, it derives the per-command `automationDispatchId`, keeps explicit target runtime scope on the emitted command contract, and either calls Game Session locally or schedules a durable remote follow-up when the command targets a different owned gameplay scope. The handoff seam persists durable per-command `script_handoff_events` and exposes them through `ListScriptHandoffEvents` so richer multi-command output remains operator-visible instead of collapsing into one work-item terminal row; those handoff reads now preserve target runtime scope plus optional remote coordinator/follow-up ids, expose the current owned target runtime `gameInstanceId` / `regionId` / `regionEpoch`, flag when the persisted target runtime scope has gone stale against that current owned scope, and now also carry the later Game Session gameplay-command execution/gameplay outcome plus remote-state tail when the admitted handoff continued into durable gameplay execution instead of stopping at admission-time truth alone. They support direct filtering by remote scope, remote ids, origin script/plugin/dispatch identity, Game Session command id, target entity, carried gameplay routing bundle, and source-kind/state truth instead of flattening cross-region handoff back to a local-only event history. The queue/index side is explicitly reset-tolerant instead of ingress-only projection state: Redis drains dedupe by `outboxWorkItemId`, a bounded scheduled rebuild republishes missing queue pointers from durable `PENDING_EVALUATION` / `EVALUATING` work items, a bounded inspector loop publishes orphan/oldest-age queue health metrics from the queue projection plus durable outbox state, and the production executor drains queue pointers for discovery before claiming durable work-item rows. The current evaluator supports command-specific `targetEntityId` templates, explicit target runtime scope templates, a structured emitted-command shape (`commandAlias` + templated `arguments`), bounded `when` / `unless` conditional emission, and multi-target fan-out from one emitted command node via templated `targetEntityIds[]`, while `ListScriptHandoffEvents` preserves the rendered command text beside dispatch identity.

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Scope

- durable scripting work-item persistence and derived coordination indexes
- `automation:queue:*` / `automation:tick:*` staging semantics
- Game Session handoff APIs and tick-fence expectations
- canonical ownership split between Automation runtime and Game Session tick execution

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Out of Scope

- broader non-scripting command durability hardening
- sandbox execution semantics before work items are produced
- frontend/operator UX beyond the observability needed to prove handoff

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Locked Direction

- Automation & Scripting never writes Game Session tick keys directly.
- durable work truth and derived coordination indexes remain separate concerns.
- scripting-generated commands must enter the same tick safety/fairness path as gameplay commands.
- queue/index loss may be tolerable only when the documented rebuild and observability contracts hold.

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Current Remaining Work

- [x] No known handoff-seam work remains in this slice.
- [x] Keep richer graph/runtime semantics above the current bounded command-emission format in `10.1`, `10.3`, and `10.5` follow-through rather than reopening this handoff integration slice.

##### source-10-4-task-list-automation-handoff-and-tick-integration-vertical-slice-1-65: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
  - [x] Retired `automation_queue:*` helper keys in favor of the canonical `automation:queue:{tenantInstanceTag}:*` key family.
  - [x] Moved quota counters to the canonical `automation:quota:<tenantId>:<scriptId>` keys and retired the stale standalone `automation:tick:*` staging path from the live/runtime design.
  - [x] Replaced raw `automation:queue:*` payloads with the documented durable outbox-pointer envelope.
  - [x] Added Game Session's idempotent automation-command handoff API and persisted dispatch identity on the gameplay command ledger.
  - [x] Fenced new automation handoffs against Game Session's durable runtime ownership row so stale-epoch, paused, or missing ownership requests fail before entering the tick queue.
  - [x] Added Automation's handoff service boundary that maps emitted work-item commands to Game Session outcomes and updates work-item/audit state.
  - [x] Added durable per-command handoff history plus `ListScriptHandoffEvents` so emitted-command dispatch identity, rendered command text, target entity, and Game Session admission outcome remain queryable after handoff.
  - [x] Extended Game Session command-status lookup to resolve by automation handoff identity as well as `commandId`, so Automation handoff history and Game Session command status share the same correlation tuple.
  - [x] Added bounded queue rebuild plus drain dedupe so `automation:queue:*` remains a derived durable-work index instead of the sole work truth.
  - [x] Added bounded queue-health inspection so `automation_queue_orphaned_entries_total` and `automation_queue_oldest_entry_age_seconds` are backed by real queue-projection versus durable-work inspection rather than prose only.
  - [x] Made the scheduled executor queue-pointer-aware: it drains bounded `automation:queue:*` pointers for discovery, then still claims PostgreSQL work-item rows before evaluation, ignoring stale pointers and falling back to durable scanning if Redis projection discovery fails.
  - [x] Added the first production executor that claims durable work items, evaluates them, and hands emitted commands to Game Session through the canonical dispatch-idempotent seam.
  - [x] Added command-specific `targetEntityId` evaluation so one work item can emit target-aware gameplay commands without losing per-command dispatch identity.
  - [x] Added a first structured emitted-command form (`commandAlias` plus templated `arguments`) so the executor is not limited to raw `commandText` literals/templates.
  - [x] Added first multi-target command fan-out so one emitted command node may expand into multiple per-target gameplay commands via templated `targetEntityIds[]` while preserving stable per-command ordinals.
  - [x] Added first bounded conditional command emission with `when` / `unless` maps over existing work-item and flat payload variables so simple graph-like branches can skip command nodes without creating ordinal gaps.
  - [x] Added explicit target runtime scope on emitted commands plus durable local-vs-remote handoff routing, so Automation can schedule cross-region follow-ups through Game Session instead of flattening every emitted command to the current local scope.
  - [x] Extended durable handoff history and `ListScriptHandoffEvents` to preserve target runtime scope and optional remote follow-up ids, so operator reads can distinguish local admission from durable remote scheduling without payload inference.
  - [x] Extended `ListScriptHandoffEvents` to expose current owned target runtime scope plus the current owned routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) and stale-scope/routing signaling from Game Session runtime-state truth, so remote handoff diagnostics no longer require a second manual ownership lookup.
  - [x] Extended `ListScriptHandoffEvents` to expose later Game Session gameplay-command execution/gameplay outcome plus remote-state tail, so operator reads do not stop at admission-time handoff truth once the emitted command has progressed into durable gameplay execution.
  - [x] Extended `ListScriptHandoffEvents` filtering to target runtime scope, remote coordinator/follow-up ids, and origin script/plugin/dispatch identity so remote handoff history is directly queryable instead of only returned as one mixed history stream.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101

#### 10.4.1 Task List: Automation Handoff Fail-Closed Provenance Follow-Through Vertical Slice - Fail-closed automation handoff provenance (source lines 1-101)

##### Preserved Source Text: source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101

<!-- migration-source path="design/project-management/vertical-slices/10.4.1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice.md" lines="1-101" sha256="2b8511de1ba762d1ef411e36cb990655c2b9db65cdebc45e1ea8b811d77b56cb" heading-offset="3" -->
#### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: 10.4.1 Task List: Automation Handoff Fail-Closed Provenance Follow-Through Vertical Slice

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Goal and Status

Goal: keep Automation-to-Game-Session handoff and remote scheduling self-describing and fail-closed when explicit provenance, routing, target-scope, or source metadata is malformed, incomplete, or contradictory instead of letting downstream queue, payload, or status code paths guess from partial truth. Status: complete.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Completion Notes

- 2026-06-29: Completed. Validation is centralized for required local/remote target scope fields, contradictory explicit/provided payload metadata is rejected before remote scheduling, and durable row authority is preferred in handoff/follow-up execution and reads.
- 2026-06-29: Evidence added in
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptGameplayCommandHandoffServiceImpl.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/AutomationGameplayCommandAdmissionSupport.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionService.java`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptGameplayCommandHandoffServiceImplTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionServiceTest.java`

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Why This Slice Exists

`10.4` landed the canonical durable handoff seam: emitted commands, local admission, remote follow-up scheduling, and operator-facing handoff history now all exist as real durable surfaces. That makes the remaining risk narrower but more mechanical:

- one handoff path may already trust first-class durable fields while a sibling path still reparses payload JSON first or tolerates missing explicit identity that should now be mandatory;
- local and remote admission can diverge on what counts as a complete target runtime scope or complete command provenance tuple;
- operator reads may expose richer durable row truth on one surface while another still hides behind payload summaries or partial fallback identity;
- malformed or contradictory explicit-versus-payload metadata can survive because the code still assumes current callers always send coherent data.

This slice is for bounded fail-closed follow-through on the already-chosen handoff architecture. It is not for reopening the handoff/tick boundary itself.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Implementation Notes

- 2026-06-25: `DefaultDurableRemoteFollowupExecutionService` now rejects contradictory first-class durable follow-up metadata versus payload JSON on the target-side execution seam instead of silently ignoring payload drift when durable row authority already exists.
- 2026-06-25: focused proof now covers the new fail-closed behavior for conflicting remote gameplay-command payloads and conflicting trigger-script-event payloads while preserving the existing durable-row fallback path for malformed legacy payload JSON.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Scope

- Automation emitted-command handoff into Game Session, including local admission and remote follow-up scheduling;
- explicit provenance, routing, source, and target-runtime metadata on handoff and remote-scheduling contracts;
- operator and status reads that should prefer durable row authority over payload reparsing or local fallback inference;
- focused tests for malformed, incomplete, or contradictory handoff metadata.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Out of Scope

- new scripting graph semantics or richer authored automation features;
- redesign of the durable outbox or tick integration model;
- broad gameplay command feature changes unrelated to handoff provenance and fail-closed admission.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Locked Direction

- once a handoff or remote-followup row persists first-class provenance/routing/source fields, downstream execution and reads should prefer those durable fields over payload inference;
- malformed or contradictory explicit handoff metadata must fail closed before queue mutation or durable remote scheduling;
- local and remote handoff paths should enforce the same completeness rules for target runtime scope, provenance, and source tuple when the same invariant applies;
- operator reads should expose durable truth directly rather than forcing payload parsing or multi-surface guesswork.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Planned Work

###### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: 1. Explicit Contract Audit

- [x] Audit the current Automation emitted-command and Game Session remote-scheduling request contracts for fields that are now supposed to be first-class authority: payload kind, requested command text, solo-tick mode, target runtime scope, routing bundle, script/plugin provenance, dispatch/work-item identity, and source tuple.
- [x] Identify any path that still accepts missing or contradictory explicit fields because equivalent information might exist in payload JSON or a later lookup.

###### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: 2. Fail-Closed Admission Follow-Through

- [x] Reject malformed, blank, incomplete, or contradictory explicit handoff metadata before local queue mutation or durable remote scheduling.
- [x] Keep the validation on the owning contract/parser/helper seam rather than scattering the same checks deeper into execution code.
- [x] Align local and remote admission semantics where they are supposed to share the same invariant.

###### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: 3. Durable Row Authority Convergence

- [x] Audit execution and status/read surfaces that still prefer payload JSON, fallback lookup, or batch-local inference when durable handoff/followup rows already persist the same facts directly.
- [x] Move touched readers and execution helpers onto durable row authority for provenance, routing, source tuple, and requested command identity.
- [x] Add focused proof that older rows can still be read compatibly where fallback is intentionally required, but new rows use first-class durable truth.

###### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: 4. Focused Operator and Regression Proof

- [x] Add or refresh tests for malformed payload-kind contracts, missing target runtime scope, incomplete provenance tuples, contradictory explicit-versus-payload identity, and reader preference for durable first-class fields.
- [x] Prove operator reads such as handoff history or remote follow-up status expose the same durable truth that execution now uses.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Acceptance Shape

- local and remote Automation handoff paths reject incomplete or contradictory explicit metadata before durable work mutation;
- new durable handoff/followup rows act as the canonical authority for provenance, routing, source tuple, and requested command identity;
- touched operator/status reads expose that same durable truth directly;
- focused handoff and remote-followup proof is green for the touched seams.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Spark Delegation Notes

- Pick one concrete invariant first, such as "explicit target runtime scope must be complete and non-contradictory before remote scheduling" or "new remote follow-up rows must be execution authority for requested command identity."
- Enumerate every writer, reader, and test for that invariant before editing.
- Keep the batch bounded to one contract family at a time and return exact changed files plus exact validation run.

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Suggested Starting Surfaces

- `services/automation-scripting-service`
- `services/game-session-service`

##### source-10-4-1-task-list-automation-handoff-fail-closed-provenance-follow-through-vertical-slice-1-101: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101

#### 10.4.1.1 Task List: Remote Trigger-Event Authority and Read-Model Parity Vertical Slice - Remote trigger-event execution authority (source lines 1-101)

##### Preserved Source Text: source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101

<!-- migration-source path="design/project-management/vertical-slices/10.4.1.1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice.md" lines="1-101" sha256="54588fda6c0c77a2cf9446d9c1891b38ec1e5098d33f2ae0179aad152b63cce9" heading-offset="3" -->
#### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: 10.4.1.1 Task List: Remote Trigger-Event Authority and Read-Model Parity Vertical Slice

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Goal and Status

Goal: finish the first-class `trigger_script_event` remote follow-up contract so persisted remote-row event identity is execution authority and every touched operator/read-model surface exposes and filters the same durable truth directly. Status: complete.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Current Snapshot (2026-06-29)

- This slice is currently `complete`.
- The `trigger_script_event` writer, executor, command-status surface, remote followup list/read surface, and remote followup result list/read surface now all prefer the first-class durable-row event identity contract instead of reparsing payload JSON as the primary authority.
- Direct control-plane filtering for `eventType` and `scriptEventId` is already wired through repository-level first-class column filters, and the touched operator reads project the same durable fields directly.
- Accuracy note (2026-06-29): bounded completion is now present for the `trigger_script_event` remote family in this branch; future work should reopen only if a new sibling remote surface drifts back to payload-first authority.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Why This Slice Exists

`10.4.1` completed the first broad fail-closed provenance pass, but one narrower remote-family follow-through is still a strong candidate for delegated work:

- `trigger_script_event` now has first-class schedule-time fields, but some touched read or filter surfaces may still lag behind the already-persisted event identity contract;
- one path may already prefer durable row authority while a sibling status/read model still depends on payload parsing or omits those fields entirely;
- this is bounded to one remote payload family and one durable identity contract, which makes it appropriate for a smaller-context worker.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Scope

- `trigger_script_event` remote scheduling, execution, and durable row authority where event identity is already first-class;
- touched coordinator/followup/result/status/read-model surfaces that should expose or filter that same event identity directly;
- focused tests for contradictory payload-versus-row event identity and for read-model parity.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Out of Scope

- unrelated remote payload families;
- broader tick/ownership redesign;
- generic Automation or Game Session cleanup outside the `trigger_script_event` remote contract.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Locked Direction

- once `trigger_script_event` identity fields are persisted first-class on remote rows, execution and reads should prefer those durable fields over payload parsing;
- touched operator/status surfaces should expose and filter the same durable event identity directly;
- contradictory explicit-versus-payload event identity should fail closed.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Completion Notes

- 2026-06-29: Completed. `RemoteFollowupRuntimeServiceImpl` persists first-class `trigger_script_event` identity fields on the durable followup row, including `eventType`, `eventSchemaVersion`, `scriptEventId`, `readSnapshotToken`, and `eventPayloadJson`, with focused proof for accepted writes and missing-snapshot rejection.
- 2026-06-29: Completed. `DefaultDurableRemoteFollowupExecutionService` now treats the durable remote followup row as execution authority for `trigger_script_event` metadata and rejects contradictory payload-versus-row identity before scripting dispatch.
- 2026-06-29: Completed. `GameSessionCommandControlPlaneService`, `GameSessionRemoteControlPlaneService`, `RemoteCommandCoordinatorRepository`, `RemoteFollowupRepository`, and `RemoteFollowupResultRepository` now expose and filter the same first-class event identity directly on command-status, followup, coordinator, and result reads.
- 2026-06-29: Focused proof covers durable write parity, contradiction rejection, and direct operator-read projection/filter parity in `RemoteFollowupRuntimeServiceImplTest`, `DefaultDurableRemoteFollowupExecutionServiceTest`, and `GameSessionControlPlaneGrpcServiceTest`.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Planned Work

###### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: 1. Current Contract Audit

- [x] Enumerate the current `trigger_script_event` first-class fields already persisted on coordinator/followup/result or linked status rows.
- [x] Identify touched execution or read-model paths that still reparse payload JSON first or omit those first-class fields from direct projection/filtering.
- [x] Keep the batch bounded to the `trigger_script_event` remote family only.

###### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: 2. Durable Authority and Read-Model Convergence

- [x] Move touched execution or status/read paths onto durable row authority for `eventType`, `eventSchemaVersion`, `scriptEventId`, `triggerMode`, `readSnapshotToken`, and any already-first-class payload identity fields.
- [x] Add or repair direct projection/filtering where operator/runtime reads should expose the same event identity surface.
- [x] Reject contradictory explicit-versus-payload event identity where touched seams still tolerate it.

###### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: 3. Focused Proof

- [x] Add or refresh focused tests for contradictory remote trigger-event metadata, durable-row authority preference, and direct read/filter parity on touched surfaces.
- [x] Keep proof bounded to the `trigger_script_event` remote family.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Completion Evidence

- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImpl.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionCommandControlPlaneService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionRemoteControlPlaneService.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/repository/RemoteCommandCoordinatorRepository.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/repository/RemoteFollowupRepository.java`
- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/repository/RemoteFollowupResultRepository.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RemoteFollowupRuntimeServiceImplTest.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableRemoteFollowupExecutionServiceTest.java`
- `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Acceptance Shape

- touched remote `trigger_script_event` execution and reads prefer durable row authority over payload parsing;
- touched operator/status surfaces expose and filter the same first-class trigger-event identity directly;
- focused proof covers contradictory metadata rejection and read-model parity for the touched seams.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Spark Delegation Notes

- Keep the work on one remote payload family only: `trigger_script_event`.
- Enumerate writer, executor, status projection, and list-read surfaces before editing.
- Return exact changed files, exact touched read surfaces, and exact validation commands run.

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Suggested Starting Surfaces

- `services/automation-scripting-service`
- `services/game-session-service`

##### source-10-4-1-1-task-list-remote-trigger-event-authority-and-read-model-parity-vertical-slice-1-101: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77

#### Scripting Operator Visibility and Runtime Convergence Vertical Slice - Scripting runtime visibility and convergence (source lines 1-77)

##### Preserved Source Text: source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77

<!-- migration-source path="design/project-management/vertical-slices/10.5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice.md" lines="1-77" sha256="6b5f3d424dc969c082175c4da8ed853d00c56e28eaa57108427a9e3590267dd8" heading-offset="3" -->
#### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Scripting Operator Visibility and Runtime Convergence Vertical Slice

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Goal and Status

Goal: give operators one coherent convergence and visibility model across script-patch readiness, plugin publication versus activation, rollout state, drain status, and scripting runtime failures so tooling stops inferring truth from partial read models. Status: complete.

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Why This Slice Exists

The repo already has substantial control-plane and observability design for scripting, but the operator-facing picture was spread across multiple docs and event families. This slice now gives that visibility work a bounded home.

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Implementation Notes

Implementation has landed through the runtime convergence substrate already landed in `10.1`, `10.3`, and `10.4`: durable work items, handler-scoped audit rows, real pending-work cancellation by script patch and plugin version, status-safe claim transitions, current script-patch status reads now backed by a durable Automation-owned `script_patch_readiness_projections` model instead of only inferring readiness from raw work-item scans and now cross-linked back to Game Design script-patch publication metadata instead of only `baseVersionId` plus derived digest fields, tenant-readiness `onLoad` work materialized from `NotifyScriptVersionUpdate` onto the same ingress/audit/work-item substrate with explicit readiness outcomes, deterministic supersession of older non-terminal patch readiness plus cancellation of not-yet-started superseded `onLoad` work, readiness-scoped dead-letter replay now gated by the canonical readiness projection instead of instance pin state so failed `onLoad` work can be safely requeued while superseded readiness stays fail-closed, operator cancellation of pending `onLoad` work now also advances the same readiness projection to `ROLLED_BACK` instead of leaving canceled readiness looking `READY` or still running, and patch-status listing now preserves non-projected legacy/runtime-only patch rows alongside readiness-projected rows instead of hiding them once readiness projections exist; those legacy/runtime-only rows now also preserve concrete terminal cancel/failure reasons like `rollback_epoch_advanced` or `runtime_region_scope_advanced` instead of flattening every rollback/failure to one generic fallback label, and the canonical readiness projection now follows the same pattern for terminal `onLoad` outcomes by preserving the newest concrete dead-letter/cancel reason like `onload_commands_not_allowed` or `rollback_epoch_advanced` instead of collapsing them back to generic `onload_failed` or `tenant_readiness_canceled`, a durable Automation-owned rollback admission-state substrate with live `SetAutomationAdmissionMode`, `admissionEpoch`-stamped work items, epoch-fenced late handoff cancellation, and `GetAutomationDrainStatus` over durable admission/work-item scopes with explicit stale signaling, a durable Automation-owned `script_patch_pin_projections` read model for `GetAutomationPinConvergence` with freshness flags plus ingress-driven observation updates, current owned runtime `regionId` / `regionEpoch`, region-aware stale refresh through the last observed `runtimeRegionId`, and script-patch publication cross-links, `ListScriptScheduleInstances` pin/runtime-progress stale flags over durable scheduler materialization rows now also cross-linked to script-patch publication metadata and plugin publication metadata when a plugin version owns the schedule row and now carrying the current owned runtime `gameInstanceId` / `regionId` / `regionEpoch` plus the current owned routing bundle and stale-scope/routing signaling beside the persisted scheduler row scope, and scheduler materialization plus plugin activation preflight now both fail closed against the currently observed runtime scope instead of letting stale same-instance plugin rows influence active-schedule materialization or binding-conflict checks after runtime scope advances; that filter is now exact on both `runtimeRegionId` and `runtimeRegionEpoch`, and `GetPluginPolicyConvergence` now follows that same region-and-epoch rule when scoped to one gameplay instance, so operator fail-closed policy reads no longer report plugin rows from a stale observed runtime timeline as if they still belonged to the current owned boundary, scheduler timer audit reads now also cross-linked to script-patch publication metadata and plugin publication metadata when present and now carrying current owned runtime scope plus current owned routing bundle and stale-scope/routing signaling, Game Session pin reads (`GetPinnedScriptPatchVersion`, `GetGameSessionPinConvergence`, and `GetGameInstanceRuntimeState`) now carrying persisted pin provenance, current owned runtime `regionId` / `regionEpoch`, plus linked Game Design script-patch publication metadata in the canonical runtime-state record, and Logging & Admin now exposes the bounded Game Session pin and convergence reads directly under `/game-session-pins/{tenantId}/{gameInstanceId}` instead of leaving those operator-facing reads gRPC-only, while `GetGameplayCommandStatus` now also cross-linking command-scoped `scriptPatchVersion` back to canonical Game Design script-patch publication metadata and command-scoped `pluginVersionId` back to canonical plugin publication metadata instead of leaving gameplay command status as a runtime-only row, and the remote-runtime operator reads (`GetRemoteCommandCoordinator`, `ListRemoteFollowups`, and `ListRemoteFollowupResults`) now follow the same pattern for plugin publication truth instead of exposing only runtime-scoped plugin ids beside script-patch publication links, dead-letter listing plus controlled replay and now script-patch publication plus plugin publication cross-links on each dead-letter row, dead-letter plugin provenance plus gameplay-versus-scheduler source metadata, and now current owned runtime scope plus current owned routing bundle and stale-scope/routing signaling on each dead-letter row, handoff plugin/source provenance plus script-patch publication and plugin publication cross-links on each handoff event, current owned target runtime scope plus current owned routing bundle and stale-scope/routing signaling, runtime-scope-advanced handoff cancellation before cross-service enqueue, script/plugin provenance carried from Automation work items into Game Session command ledgers, live Game Session purge controls for not-yet-drained script-patch and plugin-version queue entries, append-only rollout transition history behind `ListScriptPatchInstanceRolloutEvents` plus explicit script-patch publication cross-links on rollout status, rollout listing, and rollout-event reads, a durable plugin runtime registry with activation/disable/drain state transitions plus last-request/actor visibility, append-only plugin runtime transition history behind `ListPluginRuntimeEvents` with explicit previous/active Game Design publication cross-links plus persisted observed runtime `regionId` / `regionEpoch`, policy-check freshness timestamps and stale flags on `GetPluginStatus`, explicit `GetPluginStatus` cross-links back to active/pending Game Design publication metadata and lookup failures while keeping Automation activation state canonical, the same plugin runtime status surface now also preserving the last observed Game Session runtime scope and reusing that stored runtime scope for later region-aware runtime-state reads, `GetPluginPolicyConvergence` now also preserving that same observed runtime scope on each violation row instead of collapsing fail-closed policy truth back to `gameInstanceId` only, Game Design-backed plugin publication reads including tenant-scoped `ListPluginVersionStatuses`, durable `SIGNATURE_VERIFIED` and `VALIDATION_FAILED_DESIGN` publication states plus `statusReason`, publication-time base-version attestation plus `abilitySchemaDigest` enforcement, signer/component-policy publication gating, plugin distribution-manifest export, immutable runtime `abilitySchemaDigest` and `baseVersionId` activation checks, signer-revocation and component-policy activation gates plus scheduled policy reconciliation for already-enabled plugin runtime states, operator-visible `GetPluginPolicyConvergence`, a stronger shared Game Session runtime-state read for instance metadata and pin provenance, scheduled terminal outbox cleanup, and a durable `script_patch_instance_rollout_projections` substrate for per-instance script-patch rollout reads with explicit freshness flags plus first `REPINNED` preservation after rollback recovery.

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Scope

- operator-facing read models and freshness expectations
- script-patch readiness versus instance rollout visibility
- plugin publication versus runtime activation visibility
- drain/convergence/rollback status surfaces for scripting operations

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Out of Scope

- raw sandbox/runtime implementation details except where they surface in operator truth
- creator-facing editor UX
- broader security/compliance/operator-control-plane domains outside scripting

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Locked Direction

- design-time publication, tenant readiness, and instance activation are distinct truths and must remain so in operator tooling.
- Game Design publication state, Automation runtime activation state, and per-instance convergence/drain state remain separate canonical reads even when operator tooling presents them side by side.
- operator-facing APIs may cross-link those truths by stable identifiers, but they do not collapse them into one synthetic status row or one shared lifecycle.
- read-model freshness and projection lag are explicit contracts, not incidental details.
- operator workflows consume canonical reads/events rather than reconstructing state from raw tables or logs.
- scripting convergence must remain scoped enough that operators can distinguish stale projections from failed operations.

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Current Remaining Work

- None. All bounded follow-through sub-slices are complete; later surface extensions remain scoped to future operator UX or execution-policy slices.

##### source-10-5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice-1-77: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
  - [x] Terminal outbox retention cleanup is implemented as a scheduled service path rather than an ad hoc/manual table cleanup.
  - [x] `GetScriptPatchStatus` and `ListScriptPatchStatuses` return current runtime status summaries from durable `script_work_items` and enrich them with Game Design script-patch publication metadata, `baseVersionId`, and Automation `abilitySchemaDigest`.
  - [x] `ListScriptDeadLetters` exposes bounded operator-visible dead-letter rows from durable work items and now cross-links each row back to Game Design script-patch publication metadata.
  - [x] `ListScriptDeadLetters`, `ListScriptHandoffEvents`, `ListScriptScheduleInstances`, and `ListScriptTimerAuditEvents` now also cross-link plugin-owned rows back to current Game Design plugin publication metadata when `pluginVersionId` is present, instead of leaving those operator reads runtime-only on the plugin side.
  - [x] `ReplayDeadLetteredWorkItems` requeues only still-compatible dead-letter rows after current patch/plugin compatibility checks, with tenant-readiness `onLoad` replay gated by the canonical readiness projection rather than instance pin state.
  - [x] operator cancellation of pending tenant-readiness `onLoad` work now rolls the canonical readiness projection to `ROLLED_BACK` instead of leaving canceled readiness looking successful.
  - [x] `CancelPendingWorkItemsForPluginVersion` now cancels not-yet-evaluating plugin-version work before plugin disable/rollback can hand off stale commands.
  - [x] `GetPluginStatus`, `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin` persist and return Automation-owned runtime registry state.
  - [x] `ListPluginRuntimeEvents` now exposes append-only Automation-owned plugin activation/drain/disable/policy-reconcile history instead of forcing operators to infer chronology from the latest runtime row, and it cross-links previous/active plugin versions back to Game Design publication metadata.
  - [x] `SetPluginActiveVersion` now consults Game Design publication reads and Game Session runtime-instance metadata before mutating plugin runtime state.
  - [x] `SetPluginActiveVersion` now fails closed for revoked signer metadata, blocked or missing component-policy decisions, unsupported built-in command-alias bindings, and instance-scoped binding conflicts before mutating plugin runtime state, while report-only policy decisions remain activatable.
  - [x] Enabled plugin runtime states are now rechecked by a scheduled plugin-policy reconciliation pass and disabled if publication, signer, or component-policy metadata becomes fail-closed after activation.
  - [x] `GetPluginStatus` now exposes `lastPolicyCheckedAtMs` plus a stale flag so operator reads can see when plugin-policy evidence is too old for runtime admission.
  - [x] `GetPluginStatus` now cross-links active and pending plugin versions back to current Game Design publication metadata, including lookup failure metadata, without collapsing publication and activation into one lifecycle row.
  - [x] `GetPluginStatus` and `ListPluginRuntimeEvents` now also preserve the last observed Game Session runtime scope (`runtimeRegionId`, `runtimeRegionEpoch`), and the plugin runtime service reuses that stored `runtimeRegionId` as the preferred selector on later `GetGameInstanceRuntimeState` fetches instead of flattening back to `{tenantId, gameInstanceId}` only.
  - [x] `GetPluginPolicyConvergence` now reports enabled plugin runtime states whose current publication, signer, or component-policy metadata would fail closed, plus an explicit stale flag, violation-level publication cross-links, and the last observed runtime `regionId` / `regionEpoch` for the underlying convergence evaluation.
  - [x] Plugin activation preflight and scheduler materialization now both ignore enabled plugin runtime rows whose persisted observed runtime scope disagrees with the current Game Session runtime scope, and that filter is now exact on both `runtimeRegionId` and `runtimeRegionEpoch`, so stale same-instance plugin state cannot keep winning conflict/materialization decisions after runtime scope advances.
  - [x] `GetPluginPolicyConvergence` now applies that same current-runtime-scope filter when scoped to one gameplay instance, so fail-closed policy reads do not report stale observed-region or stale-epoch plugin rows as if they still belonged to the live owned timeline.
  - [x] `GetAutomationDrainStatus` now exposes the first scope-local drain read from durable `script_work_items`, including active execution count, oldest active start time, pending cancelable count, read timestamp, and an explicit stale flag.
  - [x] `ListScriptScheduleInstances` now exposes `isPinStale` plus `isRuntimeProgressStale` so operators can distinguish stale scheduler materialization from empty/non-due schedule state, and it now cross-links each schedule row back to Game Design script-patch publication metadata.
  - [x] `ListScriptScheduleInstances`, `ListScriptTimerAuditEvents`, `ListScriptDeadLetters`, and `ListScriptHandoffEvents` now also expose the current owned runtime `gameInstanceId` / `regionId` / `regionEpoch` plus the current owned routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) and stale-scope/routing signaling from Game Session runtime-state truth so operators can compare persisted Automation runtime identity against the current owned boundary without a second manual runtime-state lookup.
  - [x] `SetAutomationAdmissionMode` now persists a durable Automation-owned rollback admission barrier, advances `admissionEpoch` when entering rollback pause, rejects new runtime-scoped admissions while paused, and fences late handoff from older admitted work.
  - [x] `GetAutomationPinConvergence` now reads from a durable Automation-owned `script_patch_pin_projections` view, including observed pinned patch, linked Game Design script-patch publication metadata, persisted pin `controlPlaneRequestId`, observation time, and freshness flags.
  - [x] `ListScriptTimerAuditEvents` now cross-links each scheduler timer audit row back to Game Design script-patch publication metadata.
  - [x] `GetGameSessionPinConvergence` now exposes the Game Session-side convergence read directly from persisted game-instance pin metadata, including linked Game Design script-patch publication metadata plus an explicit stale flag for that persisted observation.
  - [x] `GetPinnedScriptPatchVersion` and `GetGameInstanceRuntimeState` now also carry linked Game Design script-patch publication metadata instead of exposing only the pinned patch version string beside runtime pin fields.
  - [x] `GetGameplayCommandStatus` now also cross-links command-scoped `scriptPatchVersion` back to canonical Game Design script-patch publication metadata instead of exposing only runtime/staging truth for script-driven gameplay commands.
  - [x] `GetScriptPatchInstanceRolloutStatus` and `ListScriptPatchInstanceRollouts` now expose a durable per-instance rollout projection with `projectionAsOfMs`, `projectionLagMs`, `isProjectionStale`, first `REPINNED` preservation after rollback recovery, and linked Game Design script-patch publication metadata.
  - [x] `ListScriptPatchInstanceRolloutEvents` now exposes append-only rollout transition events plus linked Game Design script-patch publication metadata so operators can distinguish first pin, rollback, and repin history instead of only seeing the latest projection row.
  - [x] Automation now persists plugin provenance on durable work items and forwards it into Game Session automation-command admission.
  - [x] `ListScriptHandoffEvents` now cross-links each handoff row back to Game Design script-patch publication metadata instead of leaving operators to hand-join patch truth.
  - [x] Game Session now implements `PurgeQueuedTickCommandsForScriptPatch` and `PurgeQueuedTickCommandsForPluginVersion` for not-yet-drained automation queue entries.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83

#### 10.5.1 Task List: Publication-Activation Event-Handling Separation Vertical Slice - Runtime activation and event chronology (source lines 1-83)

##### Preserved Source Text: source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/10.5.1-task-list-publication-activation-event-handling-separation-vertical-slice.md" lines="1-83" sha256="63264abbcfe33670924fd27bd86734773a2f417cab8a17205b2cf18ce54788e5" heading-offset="3" -->
#### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: 10.5.1 Task List: Publication-Activation Event-Handling Separation Vertical Slice

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Goal and Status

Goal: finish the remaining operator event-handling split so publication truth, activation truth, and runtime event chronology are exposed as separate first-class surfaces instead of being recoverable only through client-side inference across mixed reads. Status: complete.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Why This Slice Exists

`10.5` already landed substantial scripting operator visibility work: readiness projections, rollout projections, plugin runtime state, append-only runtime events, policy convergence, and publication cross-links. The remaining gap is narrower:

- current operator surfaces still have some event-handling and presentation tails where publication and activation can be understood only by combining multiple reads client-side;
- runtime chronology should stay append-only and explicit rather than being reconstructed from current-row state plus publication metadata;
- this is a bounded read-model/event-handling follow-through, not a new publication or activation architecture.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Scope

- operator-facing scripting event/read-model surfaces where publication truth, activation truth, and runtime chronology still blur together;
- append-only runtime-event handling and projection on touched scripting control-plane reads;
- focused proof for separated publication-versus-activation visibility on the touched surfaces.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Out of Scope

- new publication workflows already owned by `08.4`;
- plugin runtime policy admission logic already owned by the parent `10.5` substrate;
- readiness cancellation taxonomy owned by the sibling `10.5.2` follow-through.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Locked Direction

- publication truth, activation truth, and runtime chronology are distinct and should remain separately readable;
- append-only runtime events should teach chronology directly instead of requiring reconstruction from current-row diffs;
- operator surfaces should expose the owning truth instead of flattening multiple lifecycle dimensions into one summary label.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Planned Work

###### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: 1. Event-Handling Audit

- [x] Enumerate the operator-facing scripting surfaces that still require client-side inference between publication, activation, and runtime chronology.
- [x] Record which truths already exist durably and which projections still blur them together.
- [x] Keep the batch bounded to event-handling and read-model separation on the current scripting operator surfaces.

###### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: 2. Read-Model and Event Separation

- [x] Update touched operator reads so publication truth, activation truth, and append-only runtime chronology are exposed separately and directly.
- [x] Remove touched projection shortcuts that collapse those dimensions into one mixed lifecycle summary.
- [x] Keep the existing append-only runtime-event model as the chronology authority where applicable.

###### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: 3. Focused Proof

- [x] Add or refresh focused proof for touched operator reads and event-history surfaces.
- [x] Prove the touched views remain directly readable without client-side lifecycle inference.

###### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Completion Evidence

- Completion points:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImpl.java`
- Focused tests:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImplTest.java`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/PluginRuntimeStateServiceImplTest.java`

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Acceptance Shape

- touched operator surfaces expose publication truth, activation truth, and runtime chronology as separate readable facts;
- append-only runtime events remain the chronology authority for the touched flows;
- focused proof is green for the touched publication-versus-activation read-model surfaces.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Spark Delegation Notes

- Start from one matrix of operator reads, their current mixed lifecycle fields, and the durable source of truth for each dimension.
- Keep the work on event-handling/read-model separation only.
- Return exact changed files, exact touched operator reads, and exact validation commands run.

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Suggested Starting Surfaces

- `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/`
- `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/`
- `design/project-management/vertical-slices/10.5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice.md`

##### source-10-5-1-task-list-publication-activation-event-handling-separation-vertical-slice-1-83: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83

#### 10.5.2 Task List: Readiness Cancellation Taxonomy and Read-Model Separation Vertical Slice - Readiness cancellation and runtime outcomes (source lines 1-83)

##### Preserved Source Text: source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/10.5.2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice.md" lines="1-83" sha256="5aa7ec40e56c389e874d4139c9a9cf793343db3bca4ead615eff27bebc764c9f" heading-offset="3" -->
#### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: 10.5.2 Task List: Readiness Cancellation Taxonomy and Read-Model Separation Vertical Slice

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Goal and Status

Goal: close the remaining `onLoad` readiness and cancellation taxonomy gaps so operator reads preserve concrete terminal reasons and readiness lifecycle truth instead of collapsing back to generic failure labels or mixed-model summaries. Status: complete.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Why This Slice Exists

`10.5` already landed real readiness projections, supersession semantics, dead-letter replay gating, operator cancellation, and concrete terminal-reason preservation on major paths. One bounded tail remains:

- readiness and cancellation surfaces can still drift between concrete terminal reason truth and generic operator summaries;
- later operator views should not need to infer whether a row was canceled, superseded, rolled back, or concretely failed from mixed fields;
- this is a read-model/taxonomy completion slice, not a new readiness architecture.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Scope

- `onLoad` readiness and cancellation taxonomy on the current scripting operator/read-model surfaces;
- concrete terminal reason preservation and projection for the touched readiness paths;
- focused proof for readiness/cancellation read-model separation.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Out of Scope

- broader publication-versus-activation separation owned by sibling `10.5.1`;
- new readiness producers or non-`onLoad` lifecycle design;
- unrelated execution-budget or ingress work owned by `10.3` and `10.1`.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Locked Direction

- concrete readiness terminal reasons should remain first-class operator truth, not derived hints;
- cancellation, rollback, supersession, and execution failure should stay distinguishable in touched read models;
- operator summaries may simplify presentation, but they should not erase the underlying canonical reason taxonomy.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Planned Work

###### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: 1. Taxonomy Audit

- [x] Enumerate the current readiness/cancellation reads that still flatten concrete terminal reasons or mix incompatible lifecycle states together.
- [x] Record where canonical readiness reason truth already exists durably but is not preserved by projection.
- [x] Keep the batch bounded to `onLoad` readiness and cancellation read-model follow-through.

###### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: 2. Read-Model Separation

- [x] Preserve and expose concrete readiness cancellation and terminal-reason truth on the touched surfaces.
- [x] Remove touched projection shortcuts that flatten canceled, rolled-back, superseded, or concretely failed readiness into generic failure labels.
- [x] Keep projection vocabulary aligned with the already-live readiness substrate.

###### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: 3. Focused Proof

- [x] Add or refresh focused proof for readiness cancellation, rollback, supersession, and concrete terminal-reason projection on the touched reads.
- [x] Prove operator surfaces remain directly readable without reason reconstruction from mixed fields.

###### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Completion Evidence

- Projection and status behavior is implemented in:
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptPatchReadinessProjectionServiceImpl.java`
  - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java`
- Focused tests:
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptPatchReadinessProjectionServiceImplTest.java`
  - `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImplTest.java`

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Acceptance Shape

- touched readiness/cancellation surfaces preserve concrete canonical reason truth;
- canceled, rolled-back, superseded, and concretely failed readiness stays distinguishable on operator reads;
- focused proof is green for the touched readiness read-model projections.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Spark Delegation Notes

- Start from one table of readiness terminal states, current operator labels, and the durable source field for each reason.
- Keep the work narrow: readiness/cancellation taxonomy and read-model separation only.
- Return exact changed files, exact touched operator reads, and exact validation commands run.

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Suggested Starting Surfaces

- `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/`
- `services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/`
- `design/project-management/vertical-slices/10.5-task-list-scripting-operator-visibility-and-runtime-convergence-vertical-slice.md`

##### source-10-5-2-task-list-readiness-cancellation-taxonomy-and-read-model-separation-vertical-slice-1-83: Validation

- `./gradlew spotlessApply`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94

#### 10.5.3 Task List: Game Session Pin Readback Operator-Surface Convergence Vertical Slice - Runtime pin-convergence readback (source lines 1-94)

##### Preserved Source Text: source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94

<!-- migration-source path="design/project-management/vertical-slices/10.5.3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice.md" lines="1-94" sha256="87ed47d3fd0dcaef421232ff1d1d323e15976b7b241dc357ab6bb47085c792e2" heading-offset="3" -->
#### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: 10.5.3 Task List: Game Session Pin Readback Operator-Surface Convergence Vertical Slice

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Goal and Status

Goal: expose the canonical Game Session pin read surfaces through Logging & Admin so operators can inspect pinned script-patch truth and persisted pin convergence without dropping to gRPC. Status: complete at the current bounded boundary.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Why This Slice Exists

`10.5` already settled the operator-facing truth model for scripting convergence, and Game Session already exposed the relevant pin reads:

- `GetPinnedScriptPatchVersion` for current pinned patch provenance;
- `GetGameSessionPinConvergence` for the persisted Game Session-side convergence observation plus stale signaling.

But those reads were still one seam short of the operator HTTP surface:

- Logging & Admin had no route for either Game Session pin read;
- operators could mutate or inspect adjacent runtime/operator state through HTTP, but pin-read truth still required direct gRPC access;
- that left one avoidable gap between the canonical pin/convergence contract and the operator ingress that is supposed to consume it.

This slice closes that readback gap without widening into pin-mutation or broader scripting dashboards.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Scope

- Logging & Admin client, service, controller, DTO, and OpenAPI support for Game Session pinned-patch and pin-convergence reads;
- tenant-guarded operator REST ingress by `{tenantId, gameInstanceId}`;
- focused proof for successful readback and fail-closed convergence identity validation.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Out of Scope

- pin mutation, rollback, or queue-purge operator writes;
- broader scripting operator families already tracked elsewhere in `10.5`;
- UI composition across Game Design, Automation, and Game Session pin surfaces.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Locked Direction

- Logging & Admin must consume the canonical Game Session pin/convergence reads rather than rebuild local pin truth;
- Game Session pin provenance and convergence remain distinct operator reads even when surfaced under one Logging & Admin route family;
- convergence identity mismatches must fail closed instead of being silently trusted.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Planned Work

###### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: 1. Operator Read Surface

- [x] Add Logging & Admin client methods for `GetPinnedScriptPatchVersion` and `GetGameSessionPinConvergence`.
- [x] Add one bounded Logging & Admin route family for those Game Session pin reads.
- [x] Map publication-linked pin metadata onto bounded Logging & Admin DTOs.

###### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: 2. Proof and Docs

- [x] Add focused controller/service proof for both pin reads and convergence identity guards.
- [x] Update the `10.5` parent/index docs so this operator surface is tracked explicitly.
- [x] Update Logging & Admin `openapi.yaml` to publish the new operator routes and DTOs.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Acceptance Shape

- Logging & Admin exposes `GET /game-session-pins/{tenantId}/{gameInstanceId}` and `GET /game-session-pins/{tenantId}/{gameInstanceId}/convergence`;
- the pinned-patch read returns canonical pinned script-patch provenance and publication metadata;
- the convergence read returns canonical persisted observation metadata, explicit stale signaling, and publication metadata;
- unauthorized callers and mismatched convergence identity fail closed.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Completion Notes

- `GameSessionPinController` now exposes read-only Logging & Admin routes for pinned-patch and convergence reads under `/game-session-pins`.
- `GameSessionPinServiceImpl` now consumes the canonical Game Session gRPC reads, reuses the shared app-error-to-HTTP mapping pattern, and validates `tenantId` plus `gameInstanceId` on the convergence response.
- Logging & Admin `openapi.yaml` now documents the new Game Session pin routes and DTO shapes.

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/GameSessionPinController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/GameSessionPinService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/GameSessionPinServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/PinnedScriptPatchVersionDto.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/GameSessionPinConvergenceDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/GameSessionPinControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/GameSessionPinServiceImplTest.java`
- Existing Game Session contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.GameSessionPinControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.GameSessionPinServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-10-5-3-task-list-game-session-pin-readback-operator-surface-convergence-vertical-slice-1-94: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->
