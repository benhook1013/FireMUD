# FireMUD Scripting & Automation: Control Plane Events

This document defines the durable event contracts emitted by the scripting control plane. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the API surface and authoritative mutating/read contracts used by operators and services.

All events must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry stable identity fields; include `controlPlaneRequestId` when operator-caused).
- Emitted only after the producing service commits its state change.

## Event Transport Contract (Required)

To keep control-plane behavior predictable, transport and ordering guarantees must be explicit:

- **Partition key (instance-scoped events)**: events scoped to a running instance (for example `ScriptPatchPinChanged`, `ScriptPatchInstanceRolloutChanged`, and plugin lifecycle events) must use `tenantId` + `gameInstanceId` so ordering is stable for that instance.
- **Partition key (tenant-scoped patch lifecycle events)**: tenant patch readiness events (`ScriptPatchTenantStatusChanged`) must use `tenantId` only.
- **Ordering**: consumers may assume per-partition order within each event family and scope, but must not assume global order across tenants or instances.
- **Monotonic sequencing (required)**:
  - All instance-scoped event families must carry `instanceSequence` (monotonic per `(tenantId, gameInstanceId)`).
  - Tenant-scoped patch readiness events must carry `tenantSequence` (monotonic per `tenantId`).
  - Read models must apply events by sequence (not arrival time) and ignore stale or duplicate sequence numbers.
- **Replay**: new consumers must be able to replay at least N days of control-plane events (or reconstruct state from durable service APIs) so operator UIs can be rebuilt without data loss.
- **Idempotency**: consumers must treat `controlPlaneRequestId` as the primary idempotency key for operator-driven events and must be safe under at-least-once delivery.

## `ScriptPatchPinChanged` (Game Session -> Event Bus)

Emitted whenever the pinned patch changes.

Fields:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion`
- `changeType` (`SET` | `ROLLBACK`)
- `instanceSequence`
- `controlPlaneRequestId`
- `actor` and `reason`
- `occurredAt`

## `ScriptPatchRollbackRequested` (Game Session -> Event Bus)

Optional dedicated event. If not used, `ScriptPatchPinChanged(changeType=ROLLBACK)` is required.

## `ScriptPatchTenantStatusChanged` (Automation & Scripting -> Event Bus)

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

## `ScriptPatchInstanceRolloutChanged` (Game Session -> Event Bus)

Emitted whenever instance rollout history changes for a patch.

Fields:

- `tenantId`
- `gameInstanceId`
- `scriptPatchVersion`
- `previousRolloutStatus`
- `newRolloutStatus` (`PINNED` | `ROLLED_BACK` | `REPINNED`)
- `causedBy` (`OPERATOR` | `SYSTEM`)
- `instanceSequence`
- `controlPlaneRequestId` (required when `causedBy=OPERATOR`)
- `statusReason` (optional)
- `occurredAt`

Operator consumption rule:

- Use this event family for instance rollout history, rollback audit trails, and per-instance pin progression.

## `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting -> Event Bus)

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

## `SignerPolicyVersionObserved` (Automation & Scripting -> Event Bus)

Emitted when Automation & Scripting observes or refreshes plugin signer policy for a scope.

Fields:

- `tenantId` (nullable for global policy snapshots)
- `serviceInstanceId`
- `observedSignerPolicyVersion`
- `observedAt`
- `policySource` (for example `signed_config_artifact`)

## `SignerRevocationApplied` (Automation & Scripting -> Event Bus)

Emitted when signer revocation enforcement transitions one or more plugins to disabled state.

Fields:

- `tenantId`
- `gameInstanceId`
- `signerKeyId`
- `affectedPluginCount`
- `instanceSequence`
- `controlPlaneRequestId` (optional when operator-driven rollout change is correlated)
- `occurredAt`

## `ScriptRollbackConvergenceTimedOut` (Game Session -> Event Bus)

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
