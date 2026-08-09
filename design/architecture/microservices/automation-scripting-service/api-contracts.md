# Automation & Scripting Service API Contracts

This document defines the Automation & Scripting Service REST and gRPC surfaces, event-ingress contract, patch and plugin control-plane visibility APIs, and publish-gating digest contract.

## Implementation Status

This section records the current implementation boundary only. The API sections below remain normative target contracts; a current implementation note does not relax those contracts, and a target-only surface must not be inferred as shipped.

- `UpdateScript` is limited to bootstrap/dev tooling and is not the production runtime publish path; production rollout uses `PublishScriptPatchVersion` and `NotifyScriptVersionUpdate`.
- `GetScriptStatus` is backed by the shared durable `script_work_items` pre-DSL states: runtime workers claim `PENDING_EVALUATION` rows by transitioning them to `EVALUATING` before DSL execution. These are the current work-item statuses and are not evaluated-command queue entries. The separate evaluated-descriptor flow uses `PENDING` and `INDEXED` as target-only statuses; it is not currently persisted as that separate status layer, while a separate pre-DSL projection may route evaluator work.
- `NotifyScriptVersionUpdate` currently validates and stages patches through `PENDING_VALIDATION -> ONLOAD_RUNNING -> READY/FAILED/SUPERSEDED`; running instances reload only after a later pin change to that tenant-`READY` patch.
- The current `ListScriptHandoffEvents` proto/client exposes no `bindingId` request filter. The target response metadata remains target-state follow-through rather than a claimed live field.
- `GetAutomationPinConvergence` is currently served from a durable Automation-owned `script_patch_pin_projections` view keyed by `(tenantId, gameInstanceId)` and returns freshness flags (`projectionAsOfMs`, `projectionLagMs`, `isProjectionStale`) so temporary Game Session read failures do not collapse operator visibility into raw pass-through coupling.
- Signer/component-policy enforcement is live on the Automation runtime side. The current control-plane surface exposes `GetPluginStatus`, `ListPluginRuntimeEvents`, and `GetPluginPolicyConvergence`, and scheduled reconciliation disables enabled plugins when current publication metadata becomes fail-closed. The separate propagation-event families `SignerPolicyVersionObserved` and `SignerRevocationApplied` remain target-state follow-through rather than a shipped API family.
- Script-patch digesting already attests the patch-scoped script graph for `scriptPatchVersion`. The current full-version digest attests the tenant's draft script graph using the existing schema and returns synthetic `appliedCommitId = "version:<versionId>"` until script definitions are modeled as fully version-scoped draft data.
- The current single-command evaluator boundary and its rejection semantics are defined in [Scripting & Automation: Cross-Service Contracts](../../system-architecture-scripting-contracts.md#implementation-status); the multi-command identity using `(automationDispatchId, commandOrdinal)` remains target-only.

## REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `automation_scripting_service.proto`.
- `UpdateScript` – bootstrap/dev-only script upload path that replaces the script definition and its event bindings for that `<tenantId, scriptPatchVersion, scriptId>` tuple in one operation.
- `GetScriptStatus` – queries durable queued work (`PENDING_EVALUATION`) and active evaluation work (`EVALUATING`) in the script work-item outbox.
- `NotifyScriptVersionUpdate` – production rollout notification to Automation & Scripting that a Game Design-published `script_patch_version` is available for tenant-readiness ingestion.
- Event-ingress RPCs such as `TriggerScriptEvent` or a batch equivalent deliver script events from domain services and must carry runtime scope, idempotency, and patch-selection fields as described below.
- `GetDraftDesignDigest` – returns publish-gating digest for full publishes and script-patch publishes using a typed scope selector `oneof { versionId, scriptPatchVersion }`.

```bash
grpcurl -plaintext localhost:6565 automation_scripting.v1.AutomationScriptingService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

## Event Ingress Contract

Domain services such as Game Session and Game Logic deliver automation events through event-ingress RPCs (for example `TriggerScriptEvent`). The required identity fields, endpoint ownership, and audit behavior are defined in the [normative scripting contract tables](../../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields); the following list describes the service-local wire envelope:

- `tenantId`, `gameInstanceId`, `regionId`, and `entityId` for the target runtime context.
- resolved `playableStateScope` for gameplay-originated events so shared versus isolated realm state stays explicit through durable trigger identity, timer follow-up work, and operator read models.
- `regionEpoch` for gameplay/runtime triggers and scheduler triggers so Trigger Identity is fenced across scoped coordination resets.
- `scriptEventId` in the normalized applicable Trigger Identity. Its wire requirement follows the [ingress ownership matrix](../../system-architecture-scripting-normative-contract-tables.md#table-1a-event-ingress-scripteventid-ownership-matrix), and it is not a complete idempotency key by itself.
- `isDryRun` so live and dry-run/test traffic are always in separate idempotency namespaces.
- `eventType` and versioning metadata such as `scriptPatchVersion`.
- `eventSchemaVersion` for custom or service-specific events governed by the event registry.
- `readSnapshotToken` when the canonical event-registry entry for that `eventType` requires an authoritative gameplay snapshot selector; it must be absent for events whose registry entry explicitly marks snapshot authority as `NONE`.
- An envelope for the event payload, including any domain-specific fields.

Event ingress uses two-stage deduplication. Before handler resolution, incoming request dedupe uses event-scope identity; `scriptId` and `bindingId` are unavailable at ingress and must not be invented. After binding resolution, each resolved handler dedupes by the full applicable Trigger Identity and produces its own handler-scoped outcome. The identity, ownership, and audit uniqueness rules are defined in the [normative scripting contracts](../../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) and [cross-service deduplication contract](../../system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe).

Admission must also enforce pin consistency for `<tenantId, gameInstanceId>`:

These are event-scope ingress decisions: return the same `admissionOutcome` and `admissionReason` values in the ingress response and `script_event_ingress_audit`; they are not handler-scoped `finalOutcome` or `finalReason` values.

- If the request patch is not `READY` for the tenant, reject with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` and bounded `admissionReason=version_unavailable`.
- If local pin state is stale beyond max age and cannot be refreshed, reject with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE` and bounded `admissionReason=pin_state_unavailable`.
- If the request patch is `READY` but differs from the observed pinned patch for the instance, reject with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE` and bounded `admissionReason=pin_state_mismatch_requested_vs_observed` rather than silently substituting a version.
- Custom and service-specific events must additionally be validated against a canonical event registry so only authorized producer services can emit a given `eventType` and schema version.

Canonical event-registry contract for ingress:

- Each registry entry is keyed by `eventType` plus `eventSchemaVersion`.
- The registry entry must define the owning producer service, allowed producer principal classes, payload schema/version, payload schema reference, replay semantics, quota class, snapshot authority, consistency class, and whether `GLOBAL` bindings are legal.
- For authoritative gameplay-affecting events, the registry entry must state that `readSnapshotToken` is required and must define the required scope encoded by that token.
- For non-authoritative or synthetic events, the registry entry must explicitly mark `readSnapshotToken` forbidden so callers cannot imply stronger consistency than the event contract provides.
- Registry rejection is an event-scope ingress failure and must use `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED` with a bounded `admissionReason` such as `unknown_event_type`, `schema_version_unsupported`, `producer_not_authorized`, `snapshot_token_required`, or `snapshot_token_forbidden`.

### Production script-patch publication and rollout

`PublishScriptPatchVersion` is owned by the Game Design Service. Its canonical design-time publication contract is defined in the [Game Design Service API contracts](../game-design-service/api-contracts.md#grpc-apis) and the [Game Design gRPC proto](../../../../protos/game-design/v1/game_design_service.proto): it publishes an immutable script-only patch referencing a base version. The [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md#game-design-design-time-publication-visibility) is canonical for the boundary between that `PUBLISHED` design status and Automation & Scripting runtime readiness.

Production rollout therefore uses `PublishScriptPatchVersion` followed by Automation & Scripting's `NotifyScriptVersionUpdate`, which validates and stages the published patch for tenant readiness. Game Session remains the owner of the instance `scriptPatchVersion` pin; a notification does not repin a running instance by itself. `UpdateScript` remains limited to bootstrap/dev tooling and must not be used as either the production publication or rollout path.

Direct script upload and update APIs such as `UpdateScript` are limited to bootstrap/dev tooling and must not be used as a production runtime publish path.

## Idempotency and Retry Rules

The canonical Trigger Identity, endpoint-specific `scriptEventId` ownership, deduplication, and downstream command retry rules are defined in the [normative contract tables](../../system-architecture-scripting-normative-contract-tables.md#table-1a-event-ingress-scripteventid-ownership-matrix) and [cross-service scripting contracts](../../system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe).

At this API boundary:

- Unary event-ingress calls may be retried at the gRPC transport layer only when the caller follows that canonical identity and retry contract.
- Timer and scheduler internals may retry idempotent infrastructure operations such as Redis writes, but do not re-execute the DSL body for the same trigger.
- Handler-level outcomes remain represented by the service's durable `script_event_audit` rows and handoff/status APIs rather than by treating transport acknowledgement as execution success.

## Dry-Run and Test Execution Contract

The shared dry-run safety, namespace, authorization, budget, and capacity rules are defined in the [cross-service scripting contracts](../../system-architecture-scripting-contracts.md#6-dry-run--test-traffic-safety). At this API boundary, the service exposes a non-committing test path used by Game Design and Logging & Admin tools:

- Test runs use the production sandbox and loop-safety/resource limits, but return would-be commands to the caller instead of persisting/indexing work or handing off to tick queues.
- Test executions are recorded in `script_event_audit` with `isDryRun=true`; the normative audit table defines the `DRY_RUN_RESULT` success outcome and prohibits live handoff success.
- Pre-resolution dry-run budget denial is returned as the event-scope outcome `TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED` with `admissionReason=dry_run_budget_exceeded`, not as a transport error. After handler work is materialized, capacity denial is recorded with handler-scoped `finalOutcome=quota_denied` and a bounded reason. Authorization failures remain deterministic application-level errors such as `DRY_RUN_UNAUTHORIZED`.

## Reload Backpressure Contract

Reload admission and retry semantics follow the canonical [Scripting & Automation Cross-Service Contracts](../../system-architecture-scripting-contracts.md#7-reload-backpressure-contract). At this API boundary, during `reloadState=RELOADING`, event ingress must return explicit backpressure signals so callers can decide whether to retry:

- `TriggerScriptEventResponse.admitted=false`
- `TriggerScriptEventResponse.admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`
- `TriggerScriptEventResponse.admissionReason="reloading"` or equivalent
- `TriggerScriptEventResponse.retryAfterMs` must be populated so callers can avoid thundering-herd retries during reload

The service must also record the event-scope ingress decision in the ingress audit/logging surface with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING` and bounded reason `reloading`. A handler-scoped `script_event_audit` row is written only if handler resolution has already produced a concrete Trigger Identity.

During operator rollback pause (`PAUSED_FOR_ROLLBACK`), ingress must return an explicit rollback backpressure outcome and ingress audit record:

- `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK`
- `admissionReason=rollback_pause`
- event-scope identity fields from the request, without inventing synthetic `scriptId` or `bindingId` fields

While terminal `ROLLBACK_CONVERGENCE_TIMEOUT` is active, pre-handler ingress remains rejected using the existing rollback backpressure response enum:

- `TriggerScriptEventResponse.admitted=false`
- `TriggerScriptEventResponse.admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK`
- `TriggerScriptEventResponse.admissionReason=rollback_convergence_timeout`
- `script_event_ingress_audit` records the same event-scope admission outcome and bounded `admissionReason=rollback_convergence_timeout`
- Each rejected ingress increments the existing `automation_script_triggers_dropped_total{scope, script_category, reason="rollback_convergence_timeout"}` family. `automation_rollback_convergence_timeout_total{scope, operation, reason}` increments only when rollback enters the terminal state, not for each rejected ingress.

This is an event-scope decision. It must not create or relabel a handler-scoped `script_event_audit` row with `finalOutcome=rollback_convergence_timeout`.

These ingress response fields are event-scope only. A successful ingress admission means the request was accepted for handler resolution; it does not mean every resolved script or plugin handler later succeeded. `resolvedHandlerCount` reports only how many enabled bindings matched the admitted event scope and were materialized into durable `script_work_items` for later evaluation. Per-handler outcomes remain authoritative in `script_event_audit`.

Event-scope admission outcomes are intentionally limited to ingress-time fences such as reload/rollback backpressure, version visibility, pin visibility, signer-policy visibility, and event-registry validation. These pre-resolution outcomes are recorded in `script_event_ingress_audit`; handler-scoped outcomes such as `quota_denied`, `script_disabled`, `plugin_disabled`, and `plugin_component_blocked` are recorded only after binding resolution in `script_event_audit`.

Retry identity and timer catch-up behavior follow the linked scripting contract. The event-ingress response fields (`admitted`, `admissionOutcome`, `admissionReason`, `retryAfterMs`) and enum values are normative API contract and must align with [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md).

## Script Patch and Plugin Visibility APIs

The service exposes control-plane read and lifecycle surfaces for script patch visibility and plugin runtime lifecycle management:

- `GetScriptPatchStatus(tenantId, scriptPatchVersion)` – returns the current runtime-readiness lifecycle state plus the published script patch `baseVersionId`, the current Automation participant `abilitySchemaDigest` for that base version, timestamps, and any last-error details.
- `ListScriptPatchStatuses(tenantId, status?, changedAfter?, changedBefore?)` – lists known tenant patch statuses with the same publication metadata fields used by the single-patch read.
- `ScriptPatchTenantStatusChanged` – emitted whenever `<tenantId, scriptPatchVersion>` transitions between tenant readiness lifecycle states.
- `ScriptPatchInstanceRolloutChanged` – consumed as the authoritative instance rollout history stream produced by Game Session (`PINNED`, `ROLLED_BACK`, `REPINNED`) and projected into read APIs.
- `GetScriptPatchInstanceRolloutStatus(tenantId, gameInstanceId, scriptPatchVersion)` and `ListScriptPatchInstanceRollouts(...)` – read APIs for instance-scoped rollout history and correlation.
- `ListScriptHandoffEvents(tenantId, gameInstanceId?, scriptPatchVersion?, workItemId?, handoffOutcome?, changedAfterMs?, changedBeforeMs?, limit?, targetGameInstanceId?, targetRegionId?, targetRegionEpoch?, remoteCoordinatorId?, remoteFollowupId?, scriptId?, pluginId?, automationDispatchId?, gameSessionCommandId?, targetEntityId?, playableStateScope?, worldSlug?, realmSlug?, pointerVersion?, sourceKind?, sourceState?)` – returns durable per-command handoff history keyed by `(automationDispatchId, commandOrdinal)`, including target entity, resolved `playableStateScope`, plugin version when applicable, rendered emitted command text, Game Session command id, and handoff outcome/reason for one emitted gameplay command attempt. The target response also returns `bindingId` when the parent handler is plugin-backed. The actual proto/client filters map as follows: remote scope is `targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`, `playableStateScope`, `worldSlug`, `realmSlug`, and `pointerVersion`; remote IDs are `remoteCoordinatorId` and `remoteFollowupId`; origin identity is `gameInstanceId`, `scriptPatchVersion`, `workItemId`, `scriptId`, `pluginId`, `automationDispatchId`, and `gameSessionCommandId`; target identity is `targetEntityId`; source metadata is `sourceKind` and `sourceState`. `pluginVersionId` and applicable `bindingId` are response metadata, not request filters.
- `GetScriptEventDefinition(eventType, eventSchemaVersion)` and `ListScriptEventDefinitions(ownerService?)` – expose the canonical event registry used by ingress admission. These reads include allowed producers, required Trigger Identity fields, snapshot authority, consistency class, quota class, replay semantics, allowed binding scopes, dry-run support, and deprecation status.
- `CancelPendingWorkItemsForPatch(tenantId, scriptPatchVersion, gameInstanceId?, regionId?, controlPlaneRequestId, actorPrincipal, reason)` – cancels pending durable `script_work_items` for a script patch so rollback/drain workflows can prove displaced work will not be evaluated later. Target state transitions `PENDING_EVALUATION` to terminal `CANCELED` without running the DSL and records `finalStage=ADMISSION`, `finalOutcome=canceled`, and a bounded reason such as `operator_canceled` or `rollback_epoch_advanced`. For `EVALUATING`, the cancellation owner first fences the attempt and reads the descriptor-commit marker: a committed descriptor resumes the durable descriptor flow without DSL re-entry and is handled by descriptor cancellation/version fencing; without a committed descriptor, a fenced explicit cancellation transitions the trigger to terminal `CANCELED` with `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and the same bounded cancellation reason. Stale lease recovery without a committed descriptor uses terminal `DEAD_LETTERED` with `finalReason=stale_execution_fenced` instead. `cancelReason` is durable on the work item.
- `ListScriptDeadLetters(tenantId, gameInstanceId?, scriptPatchVersion?, limit?)` – returns bounded, newest-first `DEAD_LETTERED` work-item rows with trigger identity, resolved `playableStateScope`, script patch, event identity, reason, and timestamps so operators can inspect failed scripting work without raw table access.
- `ReplayDeadLetteredWorkItems(tenantId, gameInstanceId?, regionId?, workItemIds?, scriptPatchVersion?, createdAfterMs?, createdBeforeMs?, limit?, controlPlaneRequestId, actorPrincipal, reason)` – creates a durable replay record with a new execution identity for each eligible `DEAD_LETTERED` item after validating that the current instance still pins the same `scriptPatchVersion` and, for plugin-backed work, that the immutable `(pluginId, pluginVersionId, bindingId)` tuple stored on the dead-lettered work item still matches the active binding for the same scoped `<tenantId, gameInstanceId, pluginId>`. The original remains terminal and is retained as causation evidence; its ingress audit may supplement provenance but does not determine plugin/binding eligibility. Same-request retries reuse the replay identity under the canonical [operator replay contract](../../system-architecture-scripting-runtime-execution.md#operator-replay-of-dead_lettered-work).
- `SetAutomationAdmissionMode(tenantId, gameInstanceId, regionId?, mode, controlPlaneRequestId, actorPrincipal, reason)` – mutates the durable Automation-owned rollback admission barrier for one scope and advances `admissionEpoch` when entering `PAUSED_FOR_ROLLBACK`.
- `GetAutomationDrainStatus(tenantId, gameInstanceId, regionId?)` – reports current drain truth for one scope from durable `automation_admission_states` plus durable `script_work_items`: `admissionMode`, `admissionEpoch`, `activeExecutionCount`, `oldestActiveExecutionStartedAt`, `pendingCancelableWorkItemCount`, and `observedAt`. **Target-state mapping:** `activeExecutionCount` counts current-epoch `EVALUATING` pre-DSL triggers and `HANDOFF_IN_FLIGHT` evaluated descriptors; `pendingCancelableWorkItemCount` counts current-epoch `PENDING_EVALUATION` pre-DSL triggers and `PENDING`/`INDEXED` evaluated descriptors. **Current fail-closed mapping:** the live projection counts `EVALUATING` including unresolved stale rows plus `HANDOFF_IN_FLIGHT` as active, and counts every current handoff-capable `PENDING_EVALUATION` row as pending; it does not infer terminalization, reclaim, or safe resumption from lease age. Both counts must be zero, and the response must be fresh for the current `admissionEpoch`, before drain can resolve.
- `GetAutomationPinConvergence(tenantId, gameInstanceId)` – read-only operator surface for the latest pinned patch observation (`observedPinnedScriptPatchVersion`, `lastObservedControlPlaneRequestId`, `observedAt`) used as input by admission and scheduler logic, plus freshness flags (`projectionAsOfMs`, `projectionLagMs`, `isProjectionStale`). It does not return an admission decision or handler outcome.
- `ListScriptScheduleInstances(tenantId, gameInstanceId, ...)` now also returns the resolved `playableStateScope` used when the schedule row was materialized, so timer-driven work can be audited against the same shared-versus-isolated gameplay namespace that produced the schedule.
- `GetPluginStatus(tenantId, gameInstanceId, pluginId)` – returns plugin runtime state (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`), active and pending version IDs, the last control-plane request id, the last recorded actor principal for the runtime row, and current policy-check freshness (`lastPolicyCheckedAtMs`, `policyCheckStale`).
- `ListPluginRuntimeEvents(tenantId, gameInstanceId?, pluginId?, pluginState?, activePluginVersionId?, changedAfterMs?, changedBeforeMs?, limit?)` – returns append-only instance-scoped plugin lifecycle history for activation, drain, disable, and policy-reconcile fail-closed transitions so operator tooling does not infer chronology from the latest runtime row.
- `GetPluginPolicyConvergence(tenantId, gameInstanceId?, maxResults?)` – reports enabled plugin runtime states whose current Game Design publication, signer, or component-policy metadata would now fail closed.
- `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin` – idempotent plugin lifecycle operations used by Logging & Admin to promote, disable, or drain plugin versions per runtime scope.

Consumption rules:

- Use `ScriptPatchTenantStatusChanged` for tenant readiness gates and publish-validation UX.
- Use `ScriptPatchInstanceRolloutChanged` for instance rollout progression and rollback history.
- Read-model ownership for rollout status is Game Session pin mutations projected into query APIs via idempotent, replayable events keyed by `controlPlaneRequestId`.

Game Session and Logging & Admin use script patch visibility APIs and events to decide which `scriptPatchVersion` values may be passed to runtime. Mutating operations that change the pinned patch for a running game instance are defined on the Game Session control-plane surface and must follow the contracts in [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md). The Automation & Scripting Service uses the shared Game Session runtime-state read as the authoritative refresh source for its local pin projection and for admission alignment, but it is not the source of truth for the pin. Pinning must also satisfy base-version cohesion (`patch.baseVersionId == runtimeVersionId` for the instance).

## Pinned Version Visibility Consistency

Admission and scheduler decisions use the bounded-staleness and fail-closed rules in the [cross-service version-fencing contract](../../system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety). This service-local projection exposes the latest observed pin and freshness flags only; it does not return an admission `finalStage`, `finalOutcome`, or `finalReason`. Event-scope admission outcomes and reasons are produced on the [normative event-ingress decision path](../../system-architecture-scripting-control-plane-api.md#automation--scripting-event-ingress-admission-contract-normative), while handler-scoped final outcomes remain in `script_event_audit` after binding resolution.

Plugin signer-policy admission follows the same fail-closed principle. If signer policy for a scope is stale beyond max age and cannot be refreshed, event ingress is rejected before handler work is materialized; no handler-scoped `finalOutcome` or `script_event_audit` row is produced. After binding resolution, handler-scoped outcomes remain in `script_event_audit`. The canonical admission fields, ingress-audit mapping, and metric are owned by the [normative scripting contract tables](../../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) and [observability contract](../../system-architecture-scripting-observability-contract.md#ingress-audit-vs-handler-audit).

## Digest Contract

This service is a required digest participant for full publishes and script-patch publishes. It must expose `GetDraftDesignDigest` with a typed scope selector `oneof { versionId, scriptPatchVersion }` and maintain a service-local digest input manifest with:

- Included objects such as version- or patch-scoped script graphs, bindings, and publish-critical metadata that affect runtime execution for the scoped publish type.
- Excluded objects such as runtime queues, audit and event logs, quota counters, and other non-launchability operational state.
- Canonicalization rules covering stable ordering, normalized serialization, and deterministic default and null handling before hashing.
- `digestSchemaVersion` bump criteria: any include, exclude, or canonicalization change requires an explicit schema bump and digest migration or re-record workflow.

Publish gating must fail closed when the service cannot attest a digest under its documented manifest for the reported `digestSchemaVersion`.
