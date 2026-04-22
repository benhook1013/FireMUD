# Automation & Scripting Service API Contracts

This document defines the Automation & Scripting Service REST and gRPC surfaces, event-ingress contract, patch and plugin control-plane visibility APIs, and publish-gating digest contract.

## REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `automation_scripting_service.proto`.
- `UpdateScript` – bootstrap/dev-only script upload path. Not part of the production runtime publish contract; production rollout uses `PublishScriptPatchVersion` plus `NotifyScriptVersionUpdate` lifecycle gates. When used, it replaces the script definition and its event bindings for that `<tenantId, scriptPatchVersion, scriptId>` tuple in one operation.
- `GetScriptStatus` – queries whether a script has durable queued work (`PENDING_EVALUATION`) or active evaluation work (`EVALUATING`) in the script work-item outbox. Runtime workers claim pending rows by transitioning them from `PENDING_EVALUATION` to `EVALUATING` before DSL execution or later handoff work proceeds, so queued/running status is backed by the same outbox state used for cancellation.
- `NotifyScriptVersionUpdate` – informs the service that a new `script_patch_version` is available for tenant-readiness ingestion; the service validates and stages the patch for `PENDING_VALIDATION -> ONLOAD_RUNNING -> READY/FAILED/SUPERSEDED`, while running instances reload only after a later pin change to that tenant-`READY` patch.
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

Domain services such as Game Session and Game Logic deliver automation events through event-ingress RPCs (for example `TriggerScriptEvent`). These RPCs carry:

- `tenantId`, `gameInstanceId`, `regionId`, and `entityId` for the target runtime context.
- `regionEpoch` for gameplay/runtime triggers and scheduler triggers so Trigger Identity is fenced across scoped coordination resets.
- `scriptEventId` as an idempotency identifier following endpoint ownership rules.
- `isDryRun` so live and dry-run/test traffic are always in separate idempotency namespaces.
- `eventType` and versioning metadata such as `scriptPatchVersion`.
- `eventSchemaVersion` for custom or service-specific events governed by the event registry.
- `readSnapshotToken` when the canonical event-registry entry for that `eventType` requires an authoritative gameplay snapshot selector; it must be absent for events whose registry entry explicitly marks snapshot authority as `NONE`.
- An envelope for the event payload, including any domain-specific fields.

Event ingress is idempotent with respect to Trigger Identity and the resolved script handler. Repeated calls with the same Trigger Identity must not cause the DSL body to run twice. The Automation & Scripting Service implements this in accordance with the `scriptEventId` lifecycle and deduplication rules in [Scripting DSL Reference & Event Lifecycle](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#scripteventid-lifecycle-and-deduplication).

Admission must also enforce pin consistency for `<tenantId, gameInstanceId>`:

- If the request patch is not `READY` for the tenant, reject with `finalOutcome=version_unavailable`.
- If local pin state is stale beyond max age and cannot be refreshed, reject with `finalOutcome=pin_state_unavailable`.
- If the request patch is `READY` but differs from the observed pinned patch for the instance, reject with `finalOutcome=version_unavailable` and a bounded mismatch reason rather than silently substituting a version.
- Custom and service-specific events must additionally be validated against a canonical event registry so only authorized producer services can emit a given `eventType` and schema version.

Canonical event-registry contract for ingress:

- Each registry entry is keyed by `eventType` plus `eventSchemaVersion`.
- The registry entry must define the owning producer service, allowed producer principal classes, payload schema/version, payload schema reference, replay semantics, quota class, snapshot authority, consistency class, and whether `GLOBAL` bindings are legal.
- For authoritative gameplay-affecting events, the registry entry must state that `readSnapshotToken` is required and must define the required scope encoded by that token.
- For non-authoritative or synthetic events, the registry entry must explicitly mark `readSnapshotToken` forbidden so callers cannot imply stronger consistency than the event contract provides.
- Registry rejection is an event-scope ingress failure and must use `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED` with a bounded `admissionReason` such as `unknown_event_type`, `schema_version_unsupported`, `producer_not_authorized`, `snapshot_token_required`, or `snapshot_token_forbidden`.

Direct script upload and update APIs such as `UpdateScript` are limited to bootstrap/dev tooling and must not be used as a production runtime publish path.

## Idempotency and Retry Rules

`scriptEventId` ownership is endpoint-specific:

- Live external ingress (`TriggerScriptEvent`): caller must supply `scriptEventId` and reuse it on retries.
- Scheduler and timer ingress (`onInterval`, `onTimerExpire`): scheduler generates deterministic `scriptEventId` from due-point identity, including `gameInstanceId`.
- Dry-run and test ingress: service generates by default; caller-supplied IDs are optional and must pass dry-run namespace collision validation.

These identifiers are the canonical idempotency keys for event ingress:

- Any RPC that accepts `scriptEventId` is idempotent with respect to Trigger Identity.
- For entity-scoped external events, the idempotency key is at least `<tenantId, gameInstanceId, regionId, regionEpoch, entityId, scriptId, eventType, scriptPatchVersion, scriptEventId, isDryRun>` for gameplay/runtime triggers.
- For scheduler events, the idempotency key also includes a due point (`dueTickId` / `dueAt`) in deterministic `scriptEventId` derivation.
- Re-sending the same request with the same idempotency key must not cause the DSL body to run twice.
- The service records at most one `script_event_audit` row per handler-scoped idempotency key, meaning one row per resolved Trigger Identity after fan-out to a specific `scriptId` or plugin handler.

Downstream calls made from DSL components must carry a stable idempotency token derived from Trigger Identity plus tick context when applicable so infrastructure-level retries do not duplicate side effects.

Transport-level retries:

- Unary event-ingress calls are safe to retry at the gRPC transport layer only if they reuse the same `scriptEventId`.
- Timer and scheduler internals may retry infrastructure operations such as Redis writes but never re-execute the DSL body for the same `scriptEventId`; they replay only idempotent downstream operations.

## Dry-Run and Test Execution Contract

In addition to live event handling, the service exposes a non-committing test path used by Game Design and Logging & Admin tools:

- Test runs execute handlers in the same sandbox and with the same loop-safety and resource limits as production runs.
- Instead of persisting and indexing work items or handing off to tick queues, test runs return the would-be commands to the caller for inspection.
- Test executions are recorded in `script_event_audit` with `isDryRun=true` and the normal `eventType` for the event being exercised. Successful test executions use the dry-run terminal outcome from the normative tables (`finalStage=DRY_RUN_RESULT`, `finalOutcome=dry_run_success`) and must not claim `TICK_HANDOFF` or live `success`.
- Dry-run and test requests must use an idempotency namespace separate from live traffic so test calls cannot dedupe, suppress, or overwrite live trigger records.
- Dry-run and test APIs should use server-generated `scriptEventId` values by default. If tooling passes a caller-supplied value, the service must enforce namespace validation and reject identity collisions deterministically.
- By default, dry runs do not consume `ScriptQuotaService` windows or tenant automation budgets and must not increment live-traffic error counters.
- By default, dry runs must not contribute to failure-rate circuit breakers that can disable live scripts (`runtimeStatus=DISABLED_DUE_TO_ERRORS`).
- Separate dry-run budgets cap how much test traffic a tenant or principal can generate.
- Dry-run and test work must execute on isolated capacity so privileged tooling cannot consume the last available live automation workers.
- Dry-run and test execution must require explicit authorization scope or role and must persist the calling principal in audit metadata.
- Dry-run and test authorization and budget failures must be returned as deterministic application-level outcomes such as `DRY_RUN_UNAUTHORIZED` or `DRY_RUN_RATE_LIMITED`, not transport errors.

## Reload Backpressure Contract

During `reloadState=RELOADING`, event ingress must return explicit backpressure signals so callers can decide whether to retry:

- `TriggerScriptEventResponse.admitted=false`
- `TriggerScriptEventResponse.admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`
- `TriggerScriptEventResponse.admissionReason="reloading"` or equivalent
- `retryAfterMs` should be populated so callers can avoid thundering-herd retries during reload

The service must also record the event-scope ingress decision in the ingress audit/logging surface with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING` and bounded reason `reloading`. A handler-scoped `script_event_audit` row is written only if handler resolution has already produced a concrete Trigger Identity.

During operator rollback pause (`PAUSED_FOR_ROLLBACK`), ingress must return an explicit rollback backpressure outcome and ingress audit record:

- `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK_PAUSE`
- `admissionReason=rollback_pause`
- event-scope identity fields from the request, without inventing a synthetic `scriptId`

These ingress response fields are event-scope only. A successful ingress admission means the request was accepted for handler resolution; it does not mean every resolved script or plugin handler later succeeded. `resolvedHandlerCount` reports only how many enabled bindings matched the admitted event scope and were materialized into durable `script_work_items` for later evaluation. Per-handler outcomes remain authoritative in `script_event_audit`.

Event-scope admission outcomes are intentionally limited to ingress-time fences such as reload/rollback backpressure, version visibility, pin visibility, signer-policy visibility, and event-registry validation. These pre-resolution outcomes are recorded in `script_event_ingress_audit`; handler-scoped outcomes such as `quota_denied`, `script_disabled`, `plugin_disabled`, and `plugin_component_blocked` are recorded only after binding resolution in `script_event_audit`.

For retry behavior:

- Low-rate external events may retry with the same `scriptEventId` using bounded exponential backoff and jitter with explicit `maxAttempts` and `maxElapsedMs`.
- Timer-derived scheduler events use best-effort timer semantics; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.
- Event-ingress response fields (`admitted`, `admissionOutcome`, `admissionReason`, `retryAfterMs`) and enum values are normative API contract and must align with [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md).

## Script Patch and Plugin Visibility APIs

The service exposes control-plane read and lifecycle surfaces for script patch visibility and plugin runtime lifecycle management:

- `GetScriptPatchStatus(tenantId, scriptPatchVersion)` – returns tenant-readiness lifecycle state (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, `SUPERSEDED`), `baseVersionId`, `abilitySchemaDigest`, timestamps, and any last-error details.
- `ListScriptPatchStatuses(tenantId, status?, changedAfter?, changedBefore?)` – lists known tenant patch statuses.
- `ScriptPatchTenantStatusChanged` – emitted whenever `<tenantId, scriptPatchVersion>` transitions between tenant readiness lifecycle states.
- `ScriptPatchInstanceRolloutChanged` – consumed as the authoritative instance rollout history stream produced by Game Session (`PINNED`, `ROLLED_BACK`, `REPINNED`) and projected into read APIs.
- `GetScriptPatchInstanceRolloutStatus(tenantId, gameInstanceId, scriptPatchVersion)` and `ListScriptPatchInstanceRollouts(...)` – read APIs for instance-scoped rollout history and correlation.
- `GetScriptEventDefinition(eventType, eventSchemaVersion)` and `ListScriptEventDefinitions(ownerService?)` – expose the canonical event registry used by ingress admission. These reads include allowed producers, required Trigger Identity fields, snapshot authority, consistency class, quota class, replay semantics, allowed binding scopes, dry-run support, and deprecation status.
- `CancelPendingWorkItemsForPatch(tenantId, scriptPatchVersion, gameInstanceId?, regionId?, controlPlaneRequestId, actorPrincipal, reason)` – cancels pending durable `script_work_items` for a script patch so rollback/drain workflows can prove displaced work will not be evaluated later. The operation transitions only pending work to `CANCELED`, records `cancelReason`, and updates the corresponding handler-scoped `script_event_audit` row with `finalStage=ADMISSION`, `finalOutcome=canceled`, and the bounded cancellation reason.
- `ListScriptDeadLetters(tenantId, gameInstanceId?, scriptPatchVersion?, limit?)` – returns bounded, newest-first `DEAD_LETTERED` work-item rows with trigger identity, script patch, event identity, reason, and timestamps so operators can inspect failed scripting work without raw table access.
- `ReplayDeadLetteredWorkItems(tenantId, gameInstanceId?, regionId?, workItemIds?, scriptPatchVersion?, createdAfterMs?, createdBeforeMs?, limit?, controlPlaneRequestId, actorPrincipal, reason)` – requeues eligible `DEAD_LETTERED` work items back to `PENDING_EVALUATION` after validating that the current instance still pins the same `scriptPatchVersion` and, when the original ingress was plugin-backed, that the currently active plugin version still matches the recorded ingress audit.
- `GetAutomationDrainStatus(tenantId, gameInstanceId, regionId?)` – reports current drain truth for one scope from durable `script_work_items`: `activeExecutionCount`, `oldestActiveExecutionStartedAt`, `pendingCancelableWorkItemCount`, and `observedAt`. The live implementation still returns `admissionEpoch=0` until scoped rollback-pause state exists.
- `GetAutomationPinConvergence(tenantId, gameInstanceId)` – reports the latest pinned patch observation (`observedPinnedScriptPatchVersion`, `lastObservedControlPlaneRequestId`, `observedAt`) used by admission and scheduler logic. The live implementation currently sources this from the shared Game Session runtime-state read, so it already returns the persisted pin `controlPlaneRequestId` but does not yet own an independent Automation projection.
- `GetSignerPolicyConvergence(...)` – reports observed signer-policy version, refresh lag, and enforcement mode.
- `GetPluginStatus(tenantId, gameInstanceId, pluginId)` – returns plugin runtime state (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`), active and pending version IDs, the last control-plane request id, and the last recorded actor principal for the runtime row.
- `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin` – idempotent plugin lifecycle operations used by Logging & Admin to promote, disable, or drain plugin versions per runtime scope.
- `SignerPolicyVersionObserved` and `SignerRevocationApplied` – signer-policy propagation and revocation-enforcement events for operator visibility.

Consumption rules:

- Use `ScriptPatchTenantStatusChanged` for tenant readiness gates and publish-validation UX.
- Use `ScriptPatchInstanceRolloutChanged` for instance rollout progression and rollback history.
- Read-model ownership for rollout status is Game Session pin mutations projected into query APIs via idempotent, replayable events keyed by `controlPlaneRequestId`.

Game Session and Logging & Admin use script patch visibility APIs and events to decide which `scriptPatchVersion` values may be passed to runtime. Mutating operations that change the pinned patch for a running game instance are defined on the Game Session control-plane surface and must follow the contracts in [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md). The Automation & Scripting Service uses pin-change events plus the shared Game Session runtime-state read for visibility and admission alignment, but it is not the source of truth for the pin. Pinning must also satisfy base-version cohesion (`patch.baseVersionId == runtimeVersionId` for the instance).

## Pinned Version Visibility Consistency

Admission and scheduler decisions must use a bounded-staleness view of pinned script patch and plugin versions:

- A local cache populated by control-plane events is allowed, but it must enforce a configured max age.
- If pin data for a scope is stale beyond max age, the service must refresh from authoritative control-plane APIs or events before admitting new work.
- If fresh authoritative pin data cannot be obtained, admission must fail closed with `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`, and a bounded `finalReason`.
- If fresh authoritative pin data is available and does not match the request’s `scriptPatchVersion` or plugin version, the request must still fail closed.
- Any override of this fail-closed behavior must be explicit, time-bounded, operator-audited, and auto-expire back to fail-closed mode.

Plugin signer-policy admission follows the same fail-closed principle. If signer policy for a scope is stale beyond max age and cannot be refreshed, plugin admission must fail closed with `finalOutcome=signer_policy_unavailable`.

## Digest Contract

This service is a required digest participant for full publishes and script-patch publishes. It must expose `GetDraftDesignDigest` with a typed scope selector `oneof { versionId, scriptPatchVersion }` and maintain a service-local digest input manifest with:

Implementation Notes:

- Script-patch digesting already attests the patch-scoped script graph for `scriptPatchVersion`.
- The current full-version digest attests the tenant’s draft script graph using the existing schema and returns synthetic `appliedCommitId = "version:<versionId>"` until script definitions are modeled as fully version-scoped draft data.

- Included objects such as version- or patch-scoped script graphs, bindings, and publish-critical metadata that affect runtime execution for the scoped publish type.
- Excluded objects such as runtime queues, audit and event logs, quota counters, and other non-launchability operational state.
- Canonicalization rules covering stable ordering, normalized serialization, and deterministic default and null handling before hashing.
- `digestSchemaVersion` bump criteria: any include, exclude, or canonicalization change requires an explicit schema bump and digest migration or re-record workflow.

Publish gating must fail closed when the service cannot attest a digest under its documented manifest for the reported `digestSchemaVersion`.
