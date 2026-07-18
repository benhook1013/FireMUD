# ADR 0028: Differentiated Entitlement Freshness

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.5` Entitlements, quotas, and hosting eligibility
- Affected capabilities: `AA-3.2`, `AA-2.1`, `SF-1.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `ADMIT-01`

## Context

Account Service owns tenant subscription, quota, and hosting-entitlement truth. The previous target required every `PLAY`, reconnect, instance start/restart, and rollback to obtain a snapshot no older than 15 seconds and fail closed whenever Account was uncertain. That minimizes a short period of post-suspension usage, but it also makes Account availability part of reconnect and recovery for already-paid capacity. A brief Account outage following transport or runtime loss could therefore keep paid players out of an otherwise healthy game while uninterrupted players remained connected.

The implementation does not yet enforce the previous freshness contract. Account stamps responses with the current time while deriving both version fields from subscription row IDs; Game Session checks only `gameplayAvailable`. No runtime cache, billing-event sequence tracking, source-freshness validation, instance-start enforcement, or hard-suspension consumer exists. Current implementation also blocks `past_due` even though the canonical lifecycle keeps it playable.

## Decision

### Account Authority And Snapshot Contract

- Account remains the sole entitlement writer and authoritative refresh source.
- Runtime snapshots carry committed `subscriptionStatus`, `gameplayAvailable`, `allowNewInstanceStarts`, applicable quotas, `evaluatedAt`, monotonic `entitlementVersion`, and monotonic per-tenant `tenantBillingSequence`.
- `evaluatedAt` represents when Account evaluated authoritative committed inputs. A caller must not make stale underlying data appear fresh by restamping it at read time.
- Absence of subscription/entitlement state is not implicit permission. Free, trial, or otherwise non-paid hosting is represented by an explicit entitlement state.
- `trialing`, `active`, `past_due`, and policy-permitted `grace` remain gameplay-available as defined by the subscription lifecycle. `suspended` and `canceled` are hard denials.

### Fresh And Last-Known-Good Windows

- A runtime snapshot is fresh for 15 seconds from `evaluatedAt`.
- Runtime services keep a bounded per-tenant cache, use single-flight refresh, immediately invalidate or advance it from sequenced billing events, and periodically reconcile with Account.
- A previously observed positive snapshot may be used as last-known-good for continuity for no more than five minutes from `evaluatedAt`. Five minutes is a platform hard maximum; operators may shorten or disable it but cannot widen it without revisiting this decision.
- Last-known-good is forbidden after an observed `suspended`/`canceled` state, tenant/account revocation, a newer locally observed billing sequence, a sequence gap, an explicit denial, or when no prior authoritative positive snapshot exists.

### Strict New Commitments

The following require a fresh snapshot and fail closed with `ENTITLEMENT_UNAVAILABLE` when refresh cannot establish it:

- explicit public-game join and first/new gameplay-session admission;
- new instance creation, scale-out, or quota-increasing changes;
- paid-feature activation; and
- replacement launch/cutover or another operation that creates additional capacity, even temporarily.

Known denial returns `TENANT_BILLING_BLOCKED`, not `ENTITLEMENT_UNAVAILABLE`.

### Bounded Continuity And Recovery

- Reconnecting the same still-resumable gameplay session may use eligible last-known-good state when Account refresh is unavailable.
- Restart, rollback, or recovery of already-entitled capacity may use eligible last-known-good state only when it does not increase the tenant's admitted capacity or quota consumption.
- A reconnect that resolves to a different realm target or creates a fresh gameplay binding is new admission and remains strict.
- Existing uninterrupted sessions do not check entitlement authority per action. An observed hard suspension/cancellation still revokes them through the billing event and tenant-revocation-watermark path.
- Use of last-known-good is logged and metered by operation class and snapshot age without unbounded tenant labels in public metrics.

### Discovery

`WORLDS` and equivalent lobby discovery may show a last-known visible game with an explicit availability-unknown state while entitlement refresh is unavailable. Discovery grants no membership, token, gameplay binding, or instance authority; strict admission checks still apply before those operations.

## Consequences

- Account failure no longer prevents a recently admitted player from resuming the same session or blocks non-expanding recovery of recently entitled capacity.
- A tenant whose hard cutoff has not reached runtime services may receive at most five additional minutes of bounded continuity on already-observed positive state. New commitments remain unavailable.
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

- Implement the complete runtime response and explicit free/trial state; correct `past_due` handling and remove row-ID-derived versions.
- Implement per-tenant cache, single-flight refresh, sequenced event invalidation, gap detection, periodic reconciliation, and the five-minute hard ceiling.
- Prove strict new commitment denial, eligible reconnect/recovery continuity, expired/unsafe last-known-good denial, and immediate known hard-cutoff behavior.
- Prove no entitlement lookup occurs on routine actions for an uninterrupted session.
- Add instance lifecycle, cutover, rollback, scale, public-join, new-`PLAY`, reconnect, and discovery tests for their distinct operation classes.
- Prove `ENTITLEMENT_UNAVAILABLE` remains retriable and distinct from known `TENANT_BILLING_BLOCKED`.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/project-management/implementation-tracking/player-access-and-session.md`

## Reversibility and Revisit Triggers

The grace maximum and operation classification are centralized policy and can be tightened without changing client identity or storage authority. Revisit if measured billing abuse exceeds the accepted five-minute exposure, Account availability makes strict new admission materially unreliable, or a future deployment requires a durable replicated entitlement projection.
