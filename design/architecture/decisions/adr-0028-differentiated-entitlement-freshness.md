# ADR 0028: Differentiated Entitlement Freshness

## Status

Accepted

## Implementation Status

The accepted cache and freshness policy is not implemented. Account currently restamps responses while deriving version fields from subscription row IDs. In the current runtime `PLAY` path, `AccountClient` emits `ENTITLEMENT_UNAVAILABLE` when entitlement authority is unavailable, while `PlayCommandHandler` checks only `gameplayAvailable` after a successful authority response and emits `TENANT_BILLING_BLOCKED` when that flag is false. This current implementation status does not change the target contract: unavailable authority remains retryable as `ENTITLEMENT_UNAVAILABLE`, while known denial remains `TENANT_BILLING_BLOCKED`. No runtime cache, billing-event sequence consumer, source-freshness validation, instance-lifecycle enforcement, or hard-suspension consumer exists. Current behavior also blocks `past_due` contrary to the accepted lifecycle.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.5` Entitlements, quotas, and hosting eligibility
- Affected capabilities: `AA-3.2`, `AA-2.1`, `SF-1.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `ADMIT-01`

## Context

Account Service owns tenant subscription, quota, and hosting-entitlement truth. The previous target required every `PLAY`, reconnect, instance start/restart, and rollback to obtain a snapshot no older than 15 seconds and fail closed whenever Account was uncertain. That minimizes a short period of post-suspension usage, but it also makes Account availability part of reconnect and recovery for already-paid capacity. A brief Account outage following transport or runtime loss could therefore keep paid players out of an otherwise healthy game while uninterrupted players remained connected.

The runtime therefore needs operation-specific freshness rather than one uniform availability rule.

## Decision

### Account Authority And Snapshot Contract

- Account remains the sole entitlement writer and authoritative refresh source.
- Runtime snapshots carry committed `subscriptionStatus`, `gameplayAvailable`, `allowPublicJoin`, `allowNewGameplayBindings`, `allowNewInstanceStarts`, applicable quotas, `evaluatedAt`, monotonic `entitlementVersion`, monotonic per-tenant `tenantBillingSequence`, and the opaque Account-owned `tenantAuthorityGeneration` needed to fence tenant-authority changes at commitment.
- `evaluatedAt` represents when Account evaluated authoritative committed inputs, stamped from its deployment-synchronized UTC clock. Runtime receipt time, Redis time, and caller restamping never make an older snapshot fresh. Consumers compare it with their own deployment-synchronized UTC clock using a configured maximum skew: reject a future timestamp beyond that bound, define `conservativeAge = max(0, localNow - evaluatedAt) + maxClockSkew`, and accept a window only when `conservativeAge` is strictly below the applicable limit. Clamping the observed age before adding skew ensures that a future timestamp within the allowed skew cannot widen the window. Exact boundaries remain expired; no caller may widen a freshness window to compensate for clock uncertainty.
- Absence of subscription/entitlement state is not implicit permission. Free, trial, or otherwise non-paid hosting is represented by an explicit entitlement state.
- `trialing`, `active`, and `past_due` permit gameplay under ordinary quotas. `grace` remains available only for connected sessions and the same still-resumable binding; it denies public join, fresh gameplay bindings, new instances, scale-out, and quota growth. `suspended` and `canceled` are hard denials.

### Fresh And Last-Known-Good Windows

- Define `freshUntil = evaluatedAt + 15 seconds` and `continuityUntil = evaluatedAt + 5 minutes`. A snapshot is fresh only while `conservativeAge = max(0, localNow - evaluatedAt) + maxClockSkew` is strictly below 15 seconds; at the exact boundary it is stale. An eligible last-known-good snapshot may authorize continuity only while the same `conservativeAge` is strictly below five minutes; at the exact boundary it expires. A future `evaluatedAt` beyond `maxClockSkew` is invalid and fails closed; one within the bound is accepted only through this clamped conservative-age predicate and never widens either window.
- Runtime services keep a bounded per-tenant cache, use single-flight refresh, immediately invalidate or advance it from sequenced billing events, and periodically reconcile with Account.
- A previously observed positive snapshot may be used as last-known-good for continuity only within `continuityUntil`. Five minutes is a platform hard maximum; operators may shorten or disable it but cannot widen it without revisiting this decision.
- Last-known-good entitlement continuity supplies only the entitlement input to ADR 0030's Account-owned resume decision. Account must still be reachable to validate current lifecycle, security, membership, grant, and revocation authority and to commit the exact-binding `resumeActivationLease`; inability to establish that lease fails closed. Entitlement evaluation itself may remain unavailable for up to five minutes, while inability to re-establish revocation authority terminates the binding at the stricter 60-second bound.
- Last-known-good is forbidden after an observed `suspended`/`canceled` state, tenant/account revocation, a newer locally observed billing sequence, a sequence gap, an explicit denial, or when no prior authoritative positive snapshot exists.

### Strict New Commitments

The following require a fresh snapshot and fail closed with `ENTITLEMENT_UNAVAILABLE` when refresh cannot establish it:

- explicit public-game join and first/new gameplay-session admission;
- new instance creation, scale-out, or quota-increasing changes;
- paid-feature activation; and
- replacement launch/cutover or another operation that creates additional capacity, even temporarily.

Known denial returns `TENANT_BILLING_BLOCKED`, not `ENTITLEMENT_UNAVAILABLE`.

A fresh snapshot is necessary but not sufficient: its operation-specific flag must also allow the requested commitment. In particular, fresh `grace` state still denies the new commitments listed above.

Every strict commitment captures `tenantAuthorityGeneration`, `tenantBillingSequence`, and the entitlement version from the fresh snapshot at its admission gate. The owning Account transaction or authoritative commit surface must conditionally commit only while that authority tuple remains unchanged; a generation or sequence advance between evaluation and commit causes a retry or fail-closed rejection rather than relying on cache invalidation alone.

### Bounded Continuity And Recovery

- Reconnecting the same still-resumable gameplay session may use eligible last-known-good entitlement state when fresh entitlement evaluation is unavailable but Account can still validate the other current authorities and commit the ADR 0030 `resumeActivationLease`.
- Restart, rollback, or recovery of already-entitled capacity may use eligible last-known-good state only when it does not increase the tenant's admitted capacity or quota consumption.
- A reconnect that resolves to a different realm target or creates a fresh gameplay binding is new admission and remains strict.
- For the interaction with ADR 0019, this decision is authoritative on entitlement freshness: its last-known-good exception is limited to the exact same still-resumable binding and non-expanding recovery described here. It does not replace ADR 0019's current, fail-closed identity, membership, revocation, uniqueness, lease, or gameplay-scope checks.
- Existing uninterrupted sessions do not check entitlement authority per action. An observed hard suspension/cancellation still revokes them through the billing event and tenant authority-generation path, while periodic batched reconciliation bounds a missed event to 60 seconds under ADR 0030.
- Use of last-known-good is logged and metered by operation class and snapshot age without unbounded tenant labels in public metrics.

### Discovery

`WORLDS` and equivalent lobby discovery may show a last-known visible game with an explicit availability-unknown state while entitlement refresh is unavailable. Discovery grants no membership, token, gameplay binding, or instance authority; strict admission checks still apply before those operations.

## Consequences

- Continuity grace tolerates only unavailability of the entitlement snapshot/evaluation itself. Current lifecycle, revocation, membership, applicable grant, and ADR 0030 `resumeActivationLease` checks still require Account reachability and fail closed when unavailable; the grace window remains a platform maximum of five minutes and creates no new commitment.
- A tenant whose hard cutoff has not reached runtime services may receive at most five minutes of bounded continuity from the Account `evaluatedAt` of already-observed positive state. New commitments remain unavailable.
- Login/admission load uses one short-lived cache per tenant rather than one Account/database call per player, while billing events and sequence reconciliation bound staleness.
- The contract requires real entitlement versions, per-tenant billing sequences, event consumers, cache policy, operation classification, and proof for hard-denial propagation.

## Alternatives Considered

### Universal Fifteen-Second Fail-Closed

This minimizes post-cutoff activity but makes Account freshness part of reconnect and recovery availability. A simultaneous Account and transport/runtime incident can lock paid players out and prevent repair of already-paid capacity.

### Long-Lived Permissive Cache

Allowing all operations from a stale positive snapshot improves availability but permits new cost and sessions long after suspension and can override a known billing transition.

### Replicated Runtime Entitlement Authority

Durably replicating full entitlement projections into each runtime service could isolate Account outages but creates additional distributed authority, reconciliation, and migration complexity that is not justified for the current product.

## Implementation and Proof Obligations

- Implement the complete runtime response, operation-specific billing flags, and explicit free/trial state; correct `past_due` handling and remove row-ID-derived versions.
- Implement per-tenant cache, single-flight refresh, sequenced event invalidation, gap detection, periodic reconciliation, and the five-minute hard ceiling.
- Prove strict new commitment denial, eligible exact-same-binding/non-expanding reconnect and recovery continuity, fresh-entitlement enforcement for fresh admission or changed bindings, expired/unsafe last-known-good denial, exact 15-second and five-minute boundary behavior, and immediate known hard-cutoff behavior.
- Prove no entitlement lookup occurs on routine actions for an uninterrupted session.
- Add instance lifecycle, cutover, rollback, scale, public-join, new-`PLAY`, reconnect, and discovery tests for their distinct operation classes.
- Prove `ENTITLEMENT_UNAVAILABLE` remains retriable and distinct from known `TENANT_BILLING_BLOCKED`.

## Required Documentation Alignment

- [Authentication](../system-architecture-authentication.md)
- [Runtime versioning](../system-architecture-versioning-runtime.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [Account subscription management](../microservices/account-service/subscription-management.md)
- [Account runtime and data](../microservices/account-service/runtime-and-data.md)
- [Player access and session tracker](../../project-management/implementation-tracking/player-access-and-session.md)

## Reversibility and Revisit Triggers

The grace maximum and operation classification are centralized policy and can be tightened without changing client identity or storage authority. Revisit if measured billing abuse exceeds the accepted five-minute exposure, Account availability makes strict new admission materially unreliable, or a future deployment requires a durable replicated entitlement projection.
