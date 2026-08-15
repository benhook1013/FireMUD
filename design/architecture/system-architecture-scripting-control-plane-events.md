# FireMUD Scripting & Automation: Control Plane Events

This document defines the durable event contracts emitted by the scripting control plane. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the API surface and authoritative mutating/read contracts used by operators and services.

Pin selection and rollout-history authority remains in Game Session. The event catalogue carries committed notifications and must not create a second rollout lifecycle; see [Scripting & Automation: Cross-Service Contracts](./system-architecture-scripting-contracts.md#10-instance-rollout-read-model-ownership).

## Implementation Status

The durable transport boundary is accepted, but the current catalogue does not yet specify or prove each family's concrete retention owner/window, reconstruction procedure, consumer-progress store, and backpressure behavior. `ScriptPatchRollbackRequested` and `SignerPolicyVersionObserved` likewise have no complete standalone delivery contract. Those family-level choices belong to the still-pending `CP-01` decision import; until that lands, no family may claim an implemented durable flow merely from the payload shape below, rollback consumers use the fully defined `ScriptPatchPinChanged(changeType=ROLLBACK)` form, and signer-policy observation remains authoritative through the current read contract.

Every admitted event family must be:

- Durable (delivered at-least-once).
- Idempotent for consumers (carry stable identity fields; include `controlPlaneRequestId` when operator-caused).
- Emitted only after the producing service commits its state change.

## Durable Transport Authority

These event families are logical durable asynchronous contracts. FireMUD does not adopt a general broker at this stage. A producing service captures each durable event in a PostgreSQL transactional outbox together with its authoritative state change, and each consumer advances its own durable delivery state through an idempotent worker.

Redis may provide a disposable wake signal, durable-row pointer, or observability/coordination hint. Losing or duplicating that Redis state may delay discovery or require reconstruction, but must not erase the outbox event, consumer progress, or authoritative source state. Each flow must document its ordering scope, retention, replay window or reconstruction API, and producer and consumer backpressure behavior. A flow must not claim global ordering, indefinite replay, independent consumer progress, or durable buffering unless its concrete PostgreSQL, worker, and API implementation provides and proves that property.

This document owns the no-general-broker boundary, transactional-outbox and per-consumer delivery obligations, measured adoption gates, concrete event-family fields, and local ordering rules. [ADR 0083](./decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md) explains why that contract was accepted; it does not replace this current target-state authority.

## Event Transport Contract (Required)

To keep control-plane behavior predictable, transport and ordering guarantees must be explicit:

- **Partition key (instance-scoped events)**: events scoped to a running instance (for example `ScriptPatchPinChanged` and plugin lifecycle events) must use `tenantId` + `gameInstanceId` so ordering is stable for that instance. The reserved `ScriptPatchInstanceRolloutChanged` name is not published.
- **Partition key (tenant-scoped patch lifecycle events)**: tenant patch readiness events (`ScriptPatchTenantStatusChanged`) must use `tenantId` only.
- **Partition key (tenant-scoped design publication events)**: Game Design publication events for script patches and plugin versions must use `tenantId` only.
- **Ordering**: consumers may assume per-partition order within each event family and scope, but must not assume global order across tenants or instances.
- **Monotonic sequencing (required)**:
  - All instance-scoped event families must carry `instanceSequence` (monotonic per `(tenantId, gameInstanceId)`).
  - Tenant-scoped patch readiness events must carry `tenantSequence` (monotonic per `tenantId`).
  - Read models must apply events by sequence (not arrival time) and ignore stale or duplicate sequence numbers.
- **Replay**: each event family must name a concrete replay retention or authoritative reconstruction path; the placeholder “N days” is not a durable guarantee by itself.
- **Idempotency**: consumers must use the event's stable identity (including `controlPlaneRequestId` when operator-caused), persist independent delivery progress, and remain safe under at-least-once delivery.
- **Family admission gate**: a payload shape below is not by itself authorization to publish a durable event. Before a family is adopted, its contract must name the retention owner and concrete window, replay API or authoritative reconstruction procedure, durable independent-consumer progress store, bounded retry/backpressure behavior, and stable event identity. Until that profile exists, the producer's committed state and read API remain authoritative and no consumer may depend on delivery of that family.

## `ScriptPatchPinChanged` (Game Session -> Durable Event Flow)

Emitted whenever the exact `(pinnedScriptPatchVersion, scriptPinEpoch)` tuple changes, including an epoch-only repin to the same patch version.

Fields:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `pinnedScriptPatchVersion`
- `previousScriptPinEpoch`
- `scriptPinEpoch`
- `changeType` (`SET` | `ROLLBACK` | `REPIN`; `REPIN` classifies an epoch-only repin to the same patch version)
- `instanceSequence`
- `controlPlaneRequestId`
- `actor` and `reason`
- `occurredAt`

## `ScriptPatchRollbackRequested` (Game Session -> Durable Event Flow)

Reserved family name only; it has no adopted payload or durable-delivery profile and must not be published or consumed. Current rollback flows use `ScriptPatchPinChanged(changeType=ROLLBACK)`. A future dedicated family requires the complete family admission profile above and the pending `CP-01` decision import rather than inferring its contract from this heading.

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

## `ScriptPatchInstanceRolloutChanged` (Reserved; do not publish)

This duplicate rollout event family is deliberately not part of the canonical contract. Game Session's committed `ScriptPatchPinChanged` record carries the exact previous/resulting pin epochs and supplies the authoritative append-only history read. Consumers must not reconstruct rollout history from Automation work-item transitions or this reserved family.

No payload is adopted for this reserved family. Do not publish or consume it.

Operator consumption rule:

- Use `ScriptPatchPinChanged` and the Game Session bounded rollout-history read for instance rollout history, rollback audit trails, and per-instance pin progression. A future-derived notification requires a separate accepted contract and may not become a second authority.

## `PluginVersionRuntimeStateChanged` (Automation & Scripting -> Durable Event Flow)

Emitted when operator actions or scheduled policy reconciliation materially change plugin active versions or runtime state. This family remains subject to the existing family admission gate and `CP-01` profile; this producer rule does not authorize publication beyond that gate.

Fields:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `newState` (`ENABLED` | `DISABLED` | `DRAINING`)
- `statusReason` (optional generally; required for policy/security-driven changes, including fail-closed `DISABLED` transitions)
- `instanceSequence`
- `controlPlaneRequestId` (operator-driven only)
- `actor` and `reason` (operator-driven only)
- `occurredAt`

Operator consumption rule:

- Use this event family for runtime activation state only, including material changes from operator actions and scheduled policy reconciliation.
- `controlPlaneRequestId`, `actor`, and `reason` are omitted for scheduled policy reconciliation; `statusReason` records the policy/security cause.
- Tooling that needs the full picture must join `PluginVersionStatusChanged` with instance-scoped runtime events/read APIs rather than overloading runtime events to explain design-time publication history.
- Automation's operator read model must persist an append-only instance-scoped history for this family so `ListPluginRuntimeEvents` can expose real transition chronology without inferring it from the latest registry row.

## `SignerPolicyVersionObserved` (Automation & Scripting -> Durable Event Flow)

Reserved target-state family name and candidate payload only; it must not be published or consumed until its complete family admission profile defines stable identity, global and tenant partition scopes, monotonic sequencing, retention, reconstruction, independent consumer progress, and backpressure behavior. Until then, Automation & Scripting's committed signer-policy observation state and read API are authoritative.

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
