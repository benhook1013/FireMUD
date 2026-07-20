# FireMUD Scripting & Automation: Control Plane Events

This document defines the durable event contracts emitted by the scripting control plane. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the API surface and authoritative mutating/read contracts used by operators and services.

All events must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry stable identity fields; include `controlPlaneRequestId` when operator-caused).
- Emitted only after the producing service commits its state change.

## Event Transport Contract (Required)

FireMUD has no implicit general event bus. Under [ADR 0083](decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md), each producer captures these events in a PostgreSQL transactional outbox with its authoritative state change, retains durable per-consumer delivery progress, delivers through idempotent workers, and exposes an authoritative reconstruction API. Redis may carry only disposable wakes or durable-row pointers.

To keep control-plane behavior predictable, every event family records its concrete delivery transport, retention, ordering, reconstruction, and backpressure behavior:

- **Partition key (instance-scoped events)**: events scoped to a running instance (for example `ScriptPatchPinChanged` and plugin lifecycle events) must use `tenantId` + `gameInstanceId` so ordering is stable for that instance.
- **Partition key (tenant-scoped patch lifecycle events)**: tenant patch readiness events (`ScriptPatchTenantStatusChanged`) must use `tenantId` only.
- **Partition key (tenant-scoped design publication events)**: Game Design publication events for script patches and plugin versions must use `tenantId` only.
- **Ordering**: delivery may duplicate or reorder events. Consumers apply the monotonic sequence within each event family and scope and use the authoritative reconstruction API when they detect a gap; no global order exists across tenants or instances.
- **Monotonic sequencing (required)**:
  - All instance-scoped event families must carry `instanceSequence` (monotonic per `(tenantId, gameInstanceId)`).
  - Tenant-scoped patch readiness events must carry `tenantSequence` (monotonic per `tenantId`).
  - Read models must apply events by sequence (not arrival time) and ignore stale or duplicate sequence numbers.
- **Replay and reconstruction**: each family declares a concrete retained replay window. New or lagging consumers reconstruct from durable service APIs when that window is exhausted; no unspecified `N`-day broker retention is implied.
- **Idempotency**: consumers must treat `controlPlaneRequestId` as the primary idempotency key for operator-driven events and must be safe under at-least-once delivery.

## `ScriptPatchPinChanged` (Game Session -> Durable Event Delivery)

Emitted whenever the pinned patch changes.

Fields:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `previousScriptPinEpoch`
- `pinnedScriptPatchVersion`
- `scriptPinEpoch`
- `changeType` (`SET` | `ROLLBACK` | `REPIN`)
- `instanceSequence`
- `controlPlaneRequestId`
- `actor` and `reason`
- `occurredAt`

This is an optional refresh notification emitted from the same transaction as the authoritative Game Session pin and history record. Consumers reconstruct current state and history through Game Session APIs after loss or a sequence gap. Notification delivery, retention, or a consumer projection does not become rollout-history authority.

## `ScriptPatchRollbackRequested` (Game Session -> Durable Event Delivery)

Optional dedicated event. If not used, `ScriptPatchPinChanged(changeType=ROLLBACK)` is required.

## `ScriptPatchTenantStatusChanged` (Automation & Scripting -> Durable Event Delivery)

Emitted whenever tenant-scoped readiness lifecycle changes.

Fields:

- `tenantId`
- `scriptPatchVersion`
- `previousStatus`
- `newStatus`
- `causedBy` (`RUNTIME_VALIDATION` | `SYSTEM` | `OPERATOR`)
- `controlPlaneRequestId` (optional; required when `causedBy=OPERATOR`)
- `tenantSequence`
- `statusReason` (optional)
- `occurredAt`

Operator consumption rule:

- Use this event family for tenant patch readiness gates and publish validation UX (`READY`, `FAILED`, `SUPERSEDED`).

## `PluginVersionStatusChanged` (Game Design -> Durable Event Delivery)

Emitted whenever immutable design-time publication status changes for one plugin version.

Fields:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- `previousDesignStatus`
- `newDesignStatus` (`DRAFT` | `UPLOAD_REJECTED` | `SIGNATURE_VERIFIED` | `VALIDATION_FAILED_DESIGN` | `PUBLISHED` | `SUPERSEDED` | `REVOKED_DESIGN`)
- `tenantSequence`
- `statusReason` (optional)
- `occurredAt`

Operator consumption rule:

- Use this event family for creator/operator publication history and design-time eligibility changes only.
- Do not infer runtime activation, drain, or disablement from this event family; those remain instance-scoped runtime events.

There is no separate mandatory `ScriptPatchInstanceRolloutChanged` family. Game Session's append-only history is authoritative, while `ScriptPatchPinChanged` may accelerate current-state refresh. A distinct derived family requires a concrete consumer need that the committed pin record and authoritative history API cannot meet.

## `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting -> Durable Event Delivery)

Emitted when operator actions change plugin active versions or disablement state.

Fields:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `newState` (`ENABLED` | `DISABLED` | `DRAINING`)
- `instanceSequence`
- `controlPlaneRequestId` (if operator-driven)
- `actor` and `reason` (if operator-driven)
- `occurredAt`

Operator consumption rule:

- Use this event family for runtime activation state only.
- Tooling that needs the full picture must join `PluginVersionStatusChanged` with instance-scoped runtime events/read APIs rather than overloading runtime events to explain design-time publication history.
- Automation's operator read model must persist an append-only instance-scoped history for this family so `ListPluginRuntimeEvents` can expose real transition chronology without inferring it from the latest registry row.

## `SignerPolicyVersionObserved` (Automation & Scripting -> Durable Event Delivery)

Emitted when Automation & Scripting observes or refreshes plugin signer policy for a scope.

Fields:

- `tenantId` (nullable for global policy snapshots)
- `serviceInstanceId`
- `observedSignerPolicyVersion`
- `observedAt`
- `policySource` (for example `signed_config_artifact`)

## `SignerRevocationApplied` (Automation & Scripting -> Durable Event Delivery)

Emitted when signer revocation enforcement transitions one or more plugins to disabled state.

Fields:

- `tenantId`
- `gameInstanceId`
- `signerKeyId`
- `affectedPluginCount`
- `instanceSequence`
- `controlPlaneRequestId` (optional when operator-driven rollout change is correlated)
- `occurredAt`

## `ScriptRollbackConvergenceTimedOut` (Game Session -> Durable Event Delivery)

Emitted when rollback orchestration reaches terminal state `ROLLBACK_CONVERGENCE_TIMEOUT` before both convergence APIs acknowledge the expected `controlPlaneRequestId`. Logging & Admin may initiate orchestration, but Game Session is the mandatory producer-of-record for this event.

Fields:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `instanceSequence`
- `controlPlaneRequestId`
- `timeoutMs`
- `reason` (bounded enum/code)
- `occurredAt`
