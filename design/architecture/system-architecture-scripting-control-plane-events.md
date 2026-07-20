# FireMUD Scripting & Automation: Control Plane Notifications

This document defines the control-plane notification catalogue for scripting and automation. It complements [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md), which defines the authoritative mutating and read contracts used by operators and services.

The producing service's durable state and, where chronology matters, append-only history with a stable producer-owned cursor are authoritative. The notification families below are advisory unless a family explicitly names a durable asynchronous consumer and delivery objective.

## Notification and Recovery Contract

Under [ADR 0125](decisions/adr-0125-owner-read-first-control-plane-notifications.md), consumers obtain correctness through direct owner reads. They may use bounded-staleness caches and controlled polling with rate limits, jitter, and herd control. Redis or gRPC may carry disposable wake-ups that tell a consumer to reread the owner; notifications may be lost, duplicated, delayed, or reordered.

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

Adopting durable delivery for one family does not upgrade the rest of this catalogue. `SignerRevocationApplied` and `ScriptRollbackConvergenceTimedOut` are plausible future targeted durable flows for a named compliance or alerting subscriber; they remain advisory until such a contract and service level are adopted.

## `ScriptPatchPinChanged` (Game Session -> Advisory Notification)

Published after the authoritative Game Session pin and append-only history record commit. It is an optional cache-refresh wake-up whenever the pinned patch changes.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `previousScriptPatchVersion`
- `previousScriptPinEpoch`
- `pinnedScriptPatchVersion`
- `scriptPinEpoch`
- `changeType` (`SET` | `ROLLBACK` | `REPIN`)
- `controlPlaneRequestId`
- `actor` and `reason`

Consumers read current state and history through Game Session APIs. Notification delivery, retention, or a consumer projection does not become rollout-history authority.

## `ScriptPatchRollbackRequested` (Game Session -> Advisory Notification)

Optional dedicated wake-up. If it is omitted, consumers observe the committed rollback through `ScriptPatchPinChanged(changeType=ROLLBACK)` or authoritative Game Session reads. Neither notification is the rollback completion barrier.

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

## `PluginVersionActivated` / `PluginVersionDisabled` (Automation & Scripting -> Advisory Notification)

Published after an instance-scoped plugin lifecycle transition and required Game Session fence acknowledgement under ADR 0124. It is not the activation or containment barrier.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `pluginId`
- `previousPluginVersionId` / `newPluginVersionId` (when applicable)
- `pluginActivationEpoch`
- `newState` (`ENABLED` | `DISABLED` | `DRAINING`)
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

## `ScriptRollbackConvergenceTimedOut` (Game Session -> Advisory Notification)

Published after rollback orchestration commits terminal state `ROLLBACK_CONVERGENCE_TIMEOUT` because both convergence APIs did not acknowledge the expected `controlPlaneRequestId`. Logging & Admin may initiate orchestration, but Game Session owns the terminal state and history.

Fields in addition to the common envelope:

- `tenantId`
- `gameInstanceId`
- `targetScriptPatchVersion`
- `scriptPinEpoch`
- `controlPlaneRequestId`
- `timeoutMs`
- `reason` (bounded enum/code)

Operational consumers reread Game Session's rollout state and history. A named paging or incident subscriber with a guaranteed-delivery objective may justify a targeted ADR 0083 outbox flow later; the current advisory notification is not itself the durable alert record.
