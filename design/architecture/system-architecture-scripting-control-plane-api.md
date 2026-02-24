# FireMUD Scripting & Automation: Control Plane API and Events

This document specifies the **control plane** operations and event contracts required to operate scripting and automation safely across Game Session, Automation & Scripting, Game Design, and Logging & Admin.

It exists to remove ambiguity from “conceptual APIs” referenced in service READMEs: this is the target-state contract that must be implemented in protos/services over time.

## Table of Contents

- [Scope](#scope)
- [Principles](#principles)
- [Actors and Responsibilities](#actors-and-responsibilities)
- [Control Plane APIs (Normative)](#control-plane-apis-normative)
- [Control Plane Events (Normative)](#control-plane-events-normative)
- [Rollout and Rollback Protocols](#rollout-and-rollback-protocols)
- [Idempotency, AuthZ, and Audit](#idempotency-authz-and-audit)

---

## Scope

This document covers:

- Pinning and rolling back `scriptPatchVersion` for a running `gameInstanceId`.
- Patch lifecycle visibility (`READY` / `FAILED` / `ROLLED_BACK`) as an operator-facing contract.
- Operational interactions needed for safe rollback (pause/resume, drain/purge).
- Plugin lifecycle operations (enable/disable/rollback) scoped to a running `gameInstanceId`, as part of the same operational surface as scripts.

This document does not define the designer-facing DSL, sandbox internals, or per-trigger runtime semantics (see the scripting DSL reference and sandbox runtime docs).

## Principles

- **Game Session owns tick safety.** Game Session is the only writer for `tick:*` and enforces the version fence at execution time. Automation never writes `tick:*` directly.
- **Pinned versions are explicit.** Runtime must never “auto-upgrade” to a newer patch without an operator/designer action captured in the control plane.
- **Control plane is idempotent.** Every mutating operation must accept a caller-provided `controlPlaneRequestId` and be safely retryable.
- **Auditable and observable.** Every mutating action must emit an audit entry and a durable status event that downstream tooling can consume.

## Actors and Responsibilities

- **Game Design Service (designer control plane)**
  - Publishes script patches and plugin bundles.
  - Triggers runtime reload via publication notifications.
  - Does not repin running games by itself; repinning is an operator action.

- **Automation & Scripting Service (runtime + patch lifecycle)**
  - Evaluates triggers, persists script work items durably, and hands off to Game Session.
  - Tracks per-tenant patch lifecycle state (`READY` / `FAILED` / `ROLLED_BACK`) and enforces admission rules (“only `READY` is runnable”).
  - Emits `ScriptPatchStatusChanged` when lifecycle state changes.

- **Game Session Service (gameplay + tick control plane)**
  - Owns the pinned `scriptPatchVersion` for each `(tenantId, gameInstanceId)`.
  - Enforces the version fence on execution: commands produced under a non-pinned patch must not execute.
  - Exposes admin-only APIs to pause/resume ticks and to update the pin.
  - Emits a pin change event after a successful pin update.

- **Logging & Admin Service (operator control plane)**
  - Presents operator workflows for patch rollout/rollback and plugin management.
  - Drives changes by calling Game Session and Automation & Scripting APIs, never by writing Redis keys directly.
  - Consumes lifecycle and pin events to render status to operators.

## Control Plane APIs (Normative)

The API shapes below are described in gRPC-style terms but may be exposed via REST in operator-only deployments; the **fields and semantics are the contract**.

### Game Session: Patch Pinning

#### `GetPinnedScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`

Outputs:

- `tenantId`, `gameInstanceId`
- `pinnedScriptPatchVersion`
- `pinnedAt` (timestamp)
- `pinnedBy` (actor principal, optional)

#### `SetPinnedScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `controlPlaneRequestId` (idempotency key)
- `actor` (operator identity metadata, required for audit)
- `reason` (free-form, required)

Semantics:

- Idempotent: repeating the same request with the same `controlPlaneRequestId` must return the same result without reapplying.
- The operation must validate that `targetScriptPatchVersion` is allowed to be pinned (typically by consulting Automation & Scripting patch lifecycle state: `READY` only).
- On success, Game Session persists the new pin for `(tenantId, gameInstanceId)` and emits `ScriptPatchPinChanged`.

Outputs:

- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion` (the new value)
- `controlPlaneRequestId`

#### `RollbackScriptPatchVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion` (previous known-good patch)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Equivalent to `SetPinnedScriptPatchVersion` but semantically indicates rollback; tooling may treat it as higher urgency.
- On success, emits `ScriptPatchRollbackRequested` (or `ScriptPatchPinChanged` with `changeType=ROLLBACK`).

Outputs: same as `SetPinnedScriptPatchVersion`.

### Game Session: Tick Pause/Resume (Rollback Support)

Rollback protocols require a coordination barrier so gameplay does not execute mixed-version work during the transition.

#### `PauseTicks`

Inputs:

- `tenantId`
- Exactly one scope key:
  - `regionId` (preferred)
  - `gameInstanceId` (allowed for instance-scoped tooling)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Prevents new tick scheduling and new command intake for the scope.

#### `ResumeTicks`

Inputs: same scope model as `PauseTicks` + `controlPlaneRequestId` + `actor` + `reason`.

Semantics:

- Idempotent.
- Resumes normal scheduling after rollback/drain steps complete.

### Game Session: Purge Queued Tick Commands (Rollback Support)

Rollback safety relies on execution-time fences, but operators also need a deterministic cleanup hook so queues do not accumulate mismatched entries after a pin/disable event.

#### `PurgeQueuedTickCommandsForScriptPatch`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional scope: `regionId`
- `scriptPatchVersion` (the patch version to remove from queues)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Removes (or moves to a bounded dead-letter store) any queued tick commands whose embedded `scriptPatchVersion` matches the supplied value for the scope.
- Emits an operator-visible metric for purge activity and for version-fence drops (exact metric names and label sets follow the observability contract, including separate script and plugin version-fence metric families).

Outputs:

- `purgedCount` (best-effort count; may be approximate for large batches)

#### `PurgeQueuedTickCommandsForPluginVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- Optional scope: `regionId`
- `pluginId`
- `pluginVersionId` (the plugin version to remove from queues)
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics and outputs: same as `PurgeQueuedTickCommandsForScriptPatch`, scoped to plugin-produced commands.

### Automation & Scripting: Patch Lifecycle Visibility

#### `GetScriptPatchStatus`

Inputs:

- `tenantId`
- `scriptPatchVersion`

Outputs:

- `tenantId`, `scriptPatchVersion`
- `status` (for example `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, `ROLLED_BACK`)
- `statusReason` (optional)
- `lastChangedAt`

#### `ListScriptPatchStatuses`

Inputs:

- `tenantId`
- Optional filters: `status`, `changedAfter`, `changedBefore`

Outputs:

- A list of `GetScriptPatchStatus` records.

### Automation & Scripting: Plugin Lifecycle Management

Plugins are controlled by operators via Logging & Admin, but the runtime registry and enforcement live in Automation & Scripting. All mutating plugin operations must be idempotent and scoped to a running instance.

#### `GetPluginStatus`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`

Outputs:

- `tenantId`, `gameInstanceId`, `pluginId`
- `activePluginVersionId` (nullable)
- `pendingPluginVersionId` (nullable)
- `pluginState` (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`)
- `statusReason` (optional)
- `lastChangedAt`

#### `SetPluginActiveVersion`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `targetPluginVersionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Validates that the target bundle is allowed for the environment (signature verified, signer allowed, component policy satisfied).
- On success, updates the registry for `(tenantId, gameInstanceId, pluginId)` and emits `PluginVersionActivated` (or `PluginVersionDisabled` as appropriate if this operation also transitions state).

Outputs:

- `previousPluginVersionId` (nullable)
- `activePluginVersionId`
- `controlPlaneRequestId`

#### `DisablePlugin`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Transitions the plugin into a non-admitting state immediately.
- Triggers are rejected at admission with a dedicated outcome (for example `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`.
- Emits `PluginVersionDisabled(newState=DISABLED)`.

#### `DrainPlugin`

Inputs:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Transitions the plugin to `DRAINING` so no new triggers are admitted while previously admitted work is allowed to complete within bounded limits.
- Emits `PluginVersionDisabled(newState=DRAINING)` (or a dedicated draining event if introduced later).

### Automation & Scripting: Drain/Purge Hooks (Rollback Support)

Rollback safety requires draining or invalidating pending automation work items produced under the rolled-back patch.

#### `CancelPendingWorkItemsForPatch`

Inputs:

- `tenantId`
- `scriptPatchVersion`
- Optional scope: `gameInstanceId`, `regionId`
- `controlPlaneRequestId`
- `actor`
- `reason`

Semantics:

- Idempotent.
- Marks all pending outbox work items for the specified patch/scope as canceled so they are never handed off again.
- Emits an operator audit entry and increments a rollback-specific metric (exact metric names are defined by the observability contract).

Outputs:

- `canceledCount` (best-effort count; may be approximate for large batches)

### Logging & Admin: Operator Workflow APIs

Logging & Admin may expose a single high-level orchestration API (internally driving the lower-level calls above) for operators:

- `RequestScriptPatchRollback(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`
- `RequestScriptPatchPromotion(tenantId, gameInstanceId, targetScriptPatchVersion, controlPlaneRequestId, actor, reason)`

If implemented, these APIs must remain thin orchestration and must not become another source of truth for the pinned version.

## Control Plane Events (Normative)

All events must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry `controlPlaneRequestId` and stable identity fields).
- Emitted only after the producing service commits its state change.

### Event Transport Contract (Required)

To keep control-plane behavior predictable, transport and ordering guarantees must be explicit:

- **Partition key**: control-plane events must be partitioned by `tenantId` + `gameInstanceId` so ordering is stable for a single running instance.
- **Ordering**: consumers may assume per-partition order, but must not assume global order across tenants or instances.
- **Replay**: new consumers must be able to replay at least N days of control-plane events (or reconstruct state from durable service APIs) so operator UIs can be rebuilt without data loss.
- **Idempotency**: consumers must treat `controlPlaneRequestId` as the primary idempotency key for operator-driven events and must be safe under at-least-once delivery.

### `ScriptPatchPinChanged` (Game Session → Event Bus)

Emitted whenever the pinned patch changes.

Fields:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion`
- `changeType` (`SET` | `ROLLBACK`)
- `controlPlaneRequestId`
- `actor` and `reason`
- `occurredAt`

### `ScriptPatchRollbackRequested` (Game Session → Event Bus)

Optional dedicated event; if not used, `ScriptPatchPinChanged(changeType=ROLLBACK)` is required.

### `ScriptPatchStatusChanged` (Automation & Scripting → Event Bus)

Emitted whenever a tenant’s view of a patch’s lifecycle changes.

Fields:

- `tenantId`
- `scriptPatchVersion`
- `previousStatus`
- `newStatus`
- `statusReason` (optional)
- `occurredAt`

### `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting → Event Bus)

Emitted when operator actions change plugin active versions or disablement state.

Fields:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `newState` (`ENABLED` | `DISABLED` | `DRAINING`)
- `controlPlaneRequestId` (if operator-driven)
- `actor` and `reason` (if operator-driven)
- `occurredAt`

## Rollout and Rollback Protocols

### Patch Promotion (Operator-Driven)

1. Validate patch is `READY` in Automation & Scripting for the tenant (`GetScriptPatchStatus`).
2. Call `SetPinnedScriptPatchVersion` in Game Session.
3. Game Session emits `ScriptPatchPinChanged`.
4. Automation & Scripting observes the event for visibility (not for authority) and treats the pinned patch as the expected active one for tick handoffs.
5. Operators monitor `script_event_audit` and automation metrics; per-event correlation uses `scriptEventId` in audit/logs/traces, not metric labels.

### Patch Rollback (Operator-Driven, Required)

1. Call `PauseTicks` for the affected scope.
2. Call `RollbackScriptPatchVersion` (or `SetPinnedScriptPatchVersion`) to repin to the target known-good patch.
3. Call `CancelPendingWorkItemsForPatch` in Automation & Scripting for the rolled-back patch (and optionally purge volatile coordination indexes).
4. Call `PurgeQueuedTickCommandsForScriptPatch` (and, if applicable, `PurgeQueuedTickCommandsForPluginVersion`) so mismatched queued entries do not accumulate after repin.
5. Resume ticks with `ResumeTicks`.

Notes:

- Even without an explicit purge, Game Session’s version fence prevents execution of commands produced under the rolled-back patch, but rollback must still drain/purge automation staging to avoid unbounded queue growth and operator confusion.
- Rollback does not attempt compensating actions for already-executed tick effects. Operators rely on normal incident response patterns for remediation (restore, rollback data, or targeted admin operations).

## Idempotency, AuthZ, and Audit

- All mutating operations accept `controlPlaneRequestId` and must be safe to retry.
- All mutating operations require operator/admin authorization. Tenant-scoped operator actions must be auditable with actor identity and reason.
- Operator actions must be reflected in audit logs and in durable status events so UIs can reconstruct history.

For runtime trigger audit fields and metrics naming/label rules, see `design/architecture/system-architecture-scripting-observability-contract.md`.
