# FireMUD Scripting & Automation: Control Plane Notifications

This document defines the control-plane notification catalogue for scripting and automation. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the authoritative mutating and read contracts used by operators and services.

The producing service's durable state and, where chronology matters, append-only history with a stable producer-owned cursor are authoritative. The notification families below are advisory unless a family explicitly names a durable asynchronous consumer and delivery objective.

## Implementation Status

This catalogue is target-state. Game Session's current pin state is live, while append-only rollout history and the timeout workflow/event capture remain partial; Automation's observed-pin and convergence projections remain non-authoritative and exact epoch propagation is incomplete. See the [Game Session Runtime and Tick Coordination tracker](../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status), [Automation and Scheduler Runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status), and [Shared Runtime tracker](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md#capability-status).

## Notification and Recovery Contract

Under [ADR 0120](decisions/adr-0120-owner-read-first-control-plane-notifications.md), consumers obtain correctness through direct owner reads. They may use bounded-staleness caches and controlled polling with rate limits, jitter, and herd control. Redis or gRPC may carry disposable wake-ups that tell a consumer to reread the owner; notifications may be lost, duplicated, delayed, or reordered.

Notification loss may delay freshness until the next poll, but cannot lose accepted state, accepted history, or a required business consequence. A projection reconstructed from notifications never becomes authority. Consumers reread the owner after a gap, contradiction, cache expiry, restart, or suspected wake-up loss.

Safety-critical and completion-critical transitions use idempotent commands plus durable acknowledgement. Notifications are never the correctness, containment, activation, publication, rollback, or completion barrier.

Producers publish notifications only after the corresponding authoritative state and history commit. They do not publish predictive pre-commit notifications.

### Identity and freshness

Every notification carries:

- `eventId`, a stable identity for that occurrence;
- the family-specific producer-owned aggregate version, epoch, or history cursor when one exists; and
- `occurredAt`.

Operator-caused notifications may also carry `controlPlaneRequestId` for correlation. It is not the notification or delivery deduplication identity because one request may produce multiple legitimate events. A consumer of an advisory family uses aggregate epochs or versions to reject stale state and otherwise rereads the owner; it does not infer correctness from arrival order.

Generic cross-service `tenantSequence` and `instanceSequence` counters do not exist. Families use the producer-owned version that represents their actual aggregate, such as `scriptPinEpoch`, `pluginActivationEpoch`, `scriptPatchStatusVersion`, or `pluginVersionStatusVersion`. Owner history APIs may additionally expose an opaque stable cursor for chronological paging.

### Targeted durable delivery

A family becomes durable only when a separate contract names a consumer that requires a guaranteed asynchronous consequence or a defined delivery service level. That flow must follow [ADR 0083](decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md): transactional outbox capture with producer state, stable `eventId`, independent durable consumer progress, idempotent delivery and effects, explicit ordering and retention, bounded retry and backpressure, and authoritative reconstruction.

Adopting durable delivery for one family does not upgrade the rest of this catalogue. `SignerRevocationApplied` remains advisory unless a named durable consumer and delivery objective are adopted. `ScriptPinConvergenceTimedOut` is the existing named durable producer consequence of the Game Session workflow contract: its exactly-once event capture is committed with the timeout transition, while downstream notification delivery remains advisory and consumers recover by rereading the owner.

## `ScriptPatchPinChanged` (Game Session -> Advisory Notification)

Published after the authoritative Game Session pin and append-only history record commit. It is an optional cache-refresh wake-up if and only if the committed exact `(scriptPatchVersion, scriptPinEpoch)` tuple changes, including a same-version `REPIN` that advances the epoch.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `previousPin` (optional nested exact pin tuple containing required `scriptPatchVersion` and `scriptPinEpoch` members)
- `pinnedPin` (required nested exact pin tuple containing required `scriptPatchVersion` and `scriptPinEpoch` members)
- `changeType` (`SET` | `ROLLBACK` | `REPIN`)
- `controlPlaneRequestId` (optional for non-operator changes; required when operator-driven)
- `actor` and `reason`

At the wire level, `previousPin` is absent only on a first pin (semantic `UNPINNED`), and when present both tuple members are required. `pinnedPin` is always present with both members required; no flattened partial tuple or sentinel represents an unpinned instance. An unchanged or no-op request, failed mutation, or exact idempotent retry publishes no new occurrence. Consumers read current state and history through Game Session APIs. Notification delivery, retention, or a consumer projection does not become rollout-history authority.

## `ScriptPatchRollbackRequested` (Reserved; do not publish)

This settled reserved name has no adopted payload or delivery contract and must not be published or consumed. Current rollback flows use `ScriptPatchPinChanged(changeType=ROLLBACK)` or authoritative Game Session reads; neither is the rollback completion barrier.

## `ScriptPatchTenantStatusChanged` (Automation & Scripting -> Advisory Notification)

Published after tenant-scoped readiness state and history commit.

Fields in addition to the common envelope:

- `tenantId`
- `scriptPatchVersion`
- `previousStatus`
- `newStatus`
- `scriptPatchStatusVersion`, monotonic for the Automation-owned tenant-and-patch status aggregate
- `causedBy` (`RUNTIME_VALIDATION` | `SYSTEM` | `OPERATOR`)
- `controlPlaneRequestId` (optional; required when `causedBy=OPERATOR`)
- `statusReason` (optional)

Creator and operator tooling rereads Automation's readiness API for gates and publication validation UX (`READY`, `FAILED`, `SUPERSEDED`).

## `PluginVersionStatusChanged` (Game Design -> Advisory Notification)

Published after immutable design-time publication status and history commit for one plugin version.

Fields in addition to the common envelope:

- `tenantId`
- `pluginId`
- `pluginVersionId`
- `previousDesignStatus`
- `newDesignStatus` (`DRAFT` | `UPLOAD_REJECTED` | `SIGNATURE_VERIFIED` | `VALIDATION_FAILED_DESIGN` | `PUBLISHED` | `SUPERSEDED` | `REVOKED_DESIGN`)
- `pluginVersionStatusVersion`, monotonic for the Game Design-owned plugin-version status aggregate
- `statusReason` (optional)

Creator and operator tooling rereads Game Design for publication history and design-time eligibility. It does not infer runtime activation, drain, or disablement from this family; those remain instance-scoped Automation state.

There is no mandatory `ScriptPatchInstanceRolloutChanged` family. Game Session's append-only history is authoritative, while `ScriptPatchPinChanged` may accelerate refresh. A distinct family requires a concrete consumer need that the committed pin record and authoritative history API cannot meet.

## `PluginVersionRuntimeStateChanged` (Automation & Scripting -> Advisory Notification)

Published after an instance-scoped plugin lifecycle transition, the authoritative owner history commit, and required Game Session fence acknowledgement under [ADR 0119](decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md). It retains the settled runtime-state event identity and is not the activation or containment barrier.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `pluginActivationEpoch`
- `lifecycleRevision`
- `newState` (`ENABLED` | `RELOADING` | `FAILED` | `DISABLED` | `DRAINING`)
- `controlPlaneRequestId` (if operator-driven)
- `actor` and `reason` (if operator-driven)

Tooling reads Automation's current state and append-only instance-scoped plugin transition history. Tooling that needs the full picture joins Game Design publication reads with Automation runtime reads rather than overloading runtime notifications to explain design-time publication history.

## `SignerPolicyVersionObserved` (Automation & Scripting -> Advisory Notification)

Published after Automation records an observed signer-policy version for a scope.

Fields in addition to the common envelope:

- `tenantId` (nullable for global policy snapshots)
- `serviceInstanceId`
- `observedSignerPolicyVersion`
- `observedAt`
- `policySource` (for example `signed_config_artifact`)

Consumers reread Automation's observation state or the signer-policy authority. The notification is not proof that every plugin instance has completed reconciliation.

## `SignerRevocationApplied` (Automation & Scripting -> Advisory Notification)

Published after signer-revocation enforcement advances affected plugin activation epochs, installs required Game Session fences, and commits authoritative transition history. It is not the revocation containment barrier.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `signerKeyId`
- `observedSignerPolicyVersion`
- `affectedPluginCount`
- `controlPlaneRequestId` (optional when correlated with an operator-driven rollout change)

A named compliance or alerting subscriber with a guaranteed-delivery requirement may justify a targeted ADR 0083 outbox flow later. Until then, operational consumers poll or reread authoritative revocation and activation history.

## `ScriptPinConvergenceTimedOut` (Game Session -> Durable Event Flow)

Game Session captures exactly one event in the same transaction that commits terminal state `PIN_CONVERGENCE_TIMEOUT` for the current convergence attempt because the required owner acknowledgements did not arrive. Logging & Admin may initiate orchestration, but Game Session owns the terminal state, workflow history, timeout identity, and producer outbox/equivalent capture. Downstream delivery is an advisory wake-up; consumers must reread the authoritative Game Session workflow/status API before acting, and loss, duplication, delay, or reordering cannot lose the timeout state or create a second producer event.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `targetScriptPinEpoch`
- `operationKind` (`SET` | `ROLLBACK` | `REPIN`)
- `convergenceAttemptGeneration` (positive monotonic generation within the workflow)
- `controlPlaneRequestId`
- `timeoutMs`
- `reason` (bounded enum/code)

The exact `(tenantId, gameInstanceId, targetScriptPatchVersion, targetScriptPinEpoch, operationKind, convergenceAttemptGeneration, controlPlaneRequestId)` must match the persisted workflow attempt. Operational consumers reread Game Session's rollout state and history. A named paging or incident subscriber with a separate guaranteed-delivery objective may justify an additional ADR 0083 consumer flow later; that downstream objective is distinct from the existing exactly-once producer event.
