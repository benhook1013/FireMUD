# FireMUD Scripting & Automation: Control Plane Events

This document defines the durable event contracts emitted by the scripting control plane. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the API surface and authoritative mutating/read contracts used by operators and services.

All events must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry stable identity fields; include `controlPlaneRequestId` when operator-caused).
- Emitted only after the producing service commits its state change.

## Durable Transport Authority

These event families are logical durable asynchronous contracts. FireMUD does not adopt a general broker at this stage. A producing service captures each durable event in a PostgreSQL transactional outbox together with its authoritative state change, and each consumer advances its own durable delivery state through an idempotent worker.

Redis may provide a disposable wake signal, durable-row pointer, or observability/coordination hint. Losing or duplicating that Redis state may delay discovery or require reconstruction, but must not erase the outbox event, consumer progress, or authoritative source state. Each flow must document its ordering scope, retention, replay window or reconstruction API, and producer and consumer backpressure behavior. A flow must not claim global ordering, indefinite replay, independent consumer progress, or durable buffering unless its concrete PostgreSQL, worker, and API implementation provides and proves that property.

This document owns the no-general-broker boundary, transactional-outbox and per-consumer delivery obligations, measured adoption gates, concrete event-family fields, and local ordering rules. [ADR 0083](./decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md) explains why that contract was accepted; it does not replace this current target-state authority.

## Event Transport Contract (Required)

To keep control-plane behavior predictable, transport and ordering guarantees must be explicit:

- **Partition key (instance-scoped events)**: events scoped to a running instance (for example `ScriptPatchPinChanged`, `ScriptPatchInstanceRolloutChanged`, and plugin lifecycle events) must use `tenantId` + `gameInstanceId` so ordering is stable for that instance.
- **Partition key (tenant-scoped patch lifecycle events)**: tenant patch readiness events (`ScriptPatchTenantStatusChanged`) must use `tenantId` only.
- **Partition key (tenant-scoped design publication events)**: Game Design publication events for script patches and plugin versions must use `tenantId` only.
- **Ordering**: consumers may assume per-partition order within each event family and scope, but must not assume global order across tenants or instances.
- **Monotonic sequencing (required)**:
  - All instance-scoped event families must carry `instanceSequence` (monotonic per `(tenantId, gameInstanceId)`).
  - Tenant-scoped patch readiness events must carry `tenantSequence` (monotonic per `tenantId`).
  - Read models must apply events by sequence (not arrival time) and ignore stale or duplicate sequence numbers.
- **Replay**: each event family must name a concrete replay retention or authoritative reconstruction path; the placeholder “N days” is not a durable guarantee by itself.
- **Idempotency**: consumers must use the event's stable identity (including `controlPlaneRequestId` when operator-caused), persist independent delivery progress, and remain safe under at-least-once delivery.

## `ScriptPatchPinChanged` (Game Session -> Durable Event Flow)

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

## `ScriptPatchRollbackRequested` (Game Session -> Durable Event Flow)

Optional dedicated event. If not used, `ScriptPatchPinChanged(changeType=ROLLBACK)` is required.

## `ScriptPatchTenantStatusChanged` (Automation & Scripting -> Durable Event Flow)

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

## `PluginVersionStatusChanged` (Game Design -> Durable Event Flow)

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

## `ScriptPatchInstanceRolloutChanged` (Game Session -> Durable Event Flow)

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

## `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting -> Durable Event Flow)

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

## `SignerPolicyVersionObserved` (Automation & Scripting -> Durable Event Flow)

Emitted when Automation & Scripting observes or refreshes plugin signer policy for a scope.

Fields:

- `tenantId` (nullable for global policy snapshots)
- `serviceInstanceId`
- `observedSignerPolicyVersion`
- `observedAt`
- `policySource` (for example `signed_config_artifact`)

## `SignerRevocationApplied` (Automation & Scripting -> Durable Event Flow)

Emitted when signer revocation enforcement transitions one or more plugins to disabled state.

Fields:

- `tenantId`
- `gameInstanceId`
- `signerKeyId`
- `affectedPluginCount`
- `instanceSequence`
- `controlPlaneRequestId` (optional when operator-driven rollout change is correlated)
- `occurredAt`

## `ScriptRollbackConvergenceTimedOut` (Game Session -> Durable Event Flow)

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
