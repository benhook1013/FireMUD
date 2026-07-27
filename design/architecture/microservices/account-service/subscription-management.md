# Subscription Management Design

This document describes how the Account Service models and manages subscriptions for FireMUD game creators and players, and how subscription state drives tenant availability and resource quotas.

The goals of the subscription system include:

- Support recurring billing for hosting plans and optional in-game features.
- Handle upgrades, downgrades, and cancellations without data loss.
- Respect tenant isolation while still enabling platform-wide reporting.
- Coordinate with Stripe while keeping billing state consistent in FireMUD’s own database.

## Implementation Notes

The plan-change timing, over-quota behavior, pending-plan metadata, and full quota-bearing runtime entitlement response described below are canonical target-state behavior. Current implementation has the first runtime availability surface, but quota fields, pending plan metadata, and downgrade/cancellation enforcement still need implementation follow-through.

## Plan and Entitlement Model

Subscriptions are modeled as **plans** that define resource limits and entitlements, and **subscription** records that attach those plans to specific tenants:

- **Plan**  
  - Identified internally by `plan_code` (for example, `basic-hosting`, `pro-hosting`).  
  - Maps to Stripe products/prices as described in the [Stripe Integration Design](./stripe-integration.md#domain-model).  
  - Encodes entitlements and quotas such as maximum active sessions, maximum concurrent game instances, and storage or world-size tiers. These quotas align with the per-tenant configuration and quota enforcement described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md#tenant-configuration--scaling).

- **Subscription**  
  - Keyed by internal ID plus `accountId`, `tenantId`, and `plan_code`.  
  - Represents the current hosting agreement and billing status for a tenant.  
  - Tracks Stripe `subscription` and `customer` identifiers alongside local fields like `status`, current period boundaries, and any trial or grace-period metadata.

Entitlements exposed to other services are derived from the active subscription’s plan and status and are always scoped to a single tenant (`tenantId`).

Plan entitlements are the source of truth for hosting/runtime quotas only. One-time account, character, or virtual-currency purchases use the purchase-entitlement model in [Account Service Runtime and Data](./runtime-and-data.md#monetization-design) and must not be folded into the tenant hosting subscription row.

### Authorization Roles for Billing and Subscriptions

To keep authorization consistent, subscription and billing operations map to the role model defined in the Authentication & Authorization design:

- **Per-tenant operations** (for example, create/update/cancel subscription for a given `tenantId`, view billing history for that tenant):
  - Allowed for:
    - `tenantAdmin` for that `tenantId` on caller-bound tenant variants (`billing_safe_tenant`).
  - Global roles (`platformAdmin`, `billingAdmin`) must use explicitly cross-tenant billing-safe route variants (`cross_tenant_billing_safe`) rather than caller-bound tenant variants.
- **Cross-tenant billing reports and analytics** (for example, billing-focused multi-tenant reports and revenue dashboards):
  - Allowed only for global roles:
    - `platformAdmin` for full reporting, and
    - `billingAdmin` for billing-focused reporting surfaces.

Implementations must not introduce ad-hoc “owner” or “admin” concepts; they should rely on `tenantAdmin`, `platformAdmin`, and `billingAdmin` from the shared role model and the Tenant Authorization Contract. Support roles (`support`) may read high-level subscription state and derived entitlements for troubleshooting purposes but must not be granted access to detailed billing artifacts (for example, invoices or payment methods) or to any subscription-mutating APIs. These troubleshooting endpoints must be explicitly classified as **support-safe** in the auth middleware contract (`design/architecture/system-architecture-authentication.md#auth-middleware-algorithm-normative`) so support access cannot accidentally expand to billing-safe or data-bearing surfaces.
For billing-safe tenant routes that intentionally remain reachable during `suspended`/`canceled` periods, services must perform a live membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing mutations; JWT role claims alone are insufficient for billing-safe mutations. Caller-bound tenant variants must derive actor identity from auth context rather than a client-supplied `accountId`.
Caller-bound membership responses must include `evaluatedAt` and `membershipVersion`; if membership authority is unavailable, billing-safe mutations fail closed.
When a billing-safe tenant route mutates an account-shared Stripe payment instrument, the route must require explicit caller acknowledgement of cross-tenant impact and must return the affected-tenant scope in its response/audit trail as specified in the Stripe integration contract. Missing acknowledgement must fail with canonical error `BILLING_SHARED_INSTRUMENT_ACK_REQUIRED`.

Current support-safe allowlist in this domain:

- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)`
- `GetSubscriptionCrossTenantSupportSafe(tenantId)` with high-level status/plan fields only
- `ListSubscriptionsCrossTenantSupportSafe` with high-level status/plan fields only

The following are explicitly not support-safe: invoice exports, payment method details, Stripe customer metadata, any subscription mutation (`CreateSubscription`, plan change, cancellation), and billing-report variants such as `ListSubscriptionsCrossTenantBillingSafeReports`.

All billing/support route classifications in this domain must be registered in [Authorization Route Matrix](../../system-architecture-authz-route-matrix.md).

## Lifecycle Flows

The subscription lifecycle is modeled as a finite state machine:

- `trialing` – The tenant is in a time-limited trial; full hosting features are enabled but may be subject to conservative quotas.  
- `active` – The subscription is paid and current; quotas and entitlements from the selected plan apply.  
- `past_due` – Recent billing attempts have failed; the platform has not yet enforced restrictions, but alerts and UI warnings appear.  
- `grace` – A configured recovery period after `past_due` where connected sessions and the exact same resumable bindings may continue, while public join, fresh gameplay bindings, new instances, scale-out, and quota growth are blocked.
- `suspended` – Hosting entitlements are temporarily disabled due to non-payment or policy; new sessions and instance starts are blocked for the tenant.  
- `canceled` – The subscription has been terminated; long-term data retention and clean-up policies apply.

This lifecycle table is the canonical subscription status contract for FireMUD. Other documents (including Stripe integration and auth/session gating) must reuse these exact status values and transition semantics.

State transitions are driven by:

- Admin or creator actions (for example, creating or canceling a subscription, changing plans).  
- Stripe events (for example, successful invoice, payment failure, subscription updated/deleted).  
- Internal timers (for example, moving from `past_due` to `grace` to `suspended` once configured durations elapse).

Each transition triggers domain events that downstream services can consume to adjust quotas or availability.

Account owns the lifecycle boundary. A transition becomes authoritative when Account commits the new status, its monotonic billing sequence/authority changes, and the durable event/outbox evidence. Provider confirmation and downstream revocation, projection, instance drain, socket closure, and cache convergence are separate execution stages. They may be partial or pending after the Account commit, so status and enforcement progress must be reported separately; a retry must resume the idempotent transition execution rather than create a second lifecycle transition or roll back committed Account authority.

### Plan Changes, Downgrades, and Cancellation Timing

Plan changes have one canonical entitlement timing model:

- Upgrades take effect immediately after Stripe confirms the subscription update and the Account Service commits the new plan snapshot. The next `SubscriptionStatusChanged` event advances `tenantBillingSequence`, and runtime services may admit new load against the higher quota only after observing or refreshing to that sequence.
- Downgrades default to taking effect at the next billing period boundary. The Account Service records `pending_plan_code`, `pending_effective_at`, and the current period end so creator UIs can show the future quota before it is enforced.
- Immediate downgrades are allowed only when the caller explicitly chooses immediate effect and acknowledges any over-quota impact. The Account Service must emit a `SubscriptionStatusChanged` event that advances `tenantBillingSequence` and marks the lower plan as current before runtime services enforce it.
- Cancellation defaults to period-end cancellation. During the paid current period the subscription remains `active` or `grace` according to payment state, with `cancel_at_period_end=true`; at the effective cancellation time it transitions to `canceled` and follows the hard cutoff behavior below.
- Immediate cancellation transitions to `canceled` immediately after provider confirmation and Account Service commit, then emits the same hard cutoff events as billing suspension.

When a tenant is above the newly effective quota after downgrade or cancellation-to-lower-plan, enforcement is deterministic: existing admitted gameplay sessions and already-running instances are not killed solely for quota excess while the tenant remains `trialing`, `active`, `past_due`, or `grace`; admission for new sessions, new instance starts, scale-out, and quota-increasing operations is denied until observed usage falls below the effective quota or the plan is upgraded. If the new state is `suspended` or `canceled`, the hard cutoff rules apply instead and existing gameplay sessions are revoked. Runtime entitlement responses must include enough quota fields for enforcement points to make this decision without relying on plan-code-specific local tables.

## Tenant Availability and Quota Enforcement

Subscription status feeds directly into tenant availability and resource enforcement. To keep behavior consistent across services, the following states and effects apply:

- When a subscription is `trialing` or `active`:
  - The tenant is **available for gameplay**; the Game Session Service may start and run game instances for that tenant.  
  - Quotas derived from the plan (for example, maximum `active_sessions`, allowed world size) are applied when starting instances or admitting new player sessions.

- When a subscription is `past_due`:
  - The tenant remains available for gameplay, but operator dashboards and creator/admin UIs surface prominent warnings.  
  - Existing and new gameplay continue under the tenant's ordinary plan quotas; `past_due` alone does not revoke sessions or close admission.

- When a subscription enters `grace`:
  - The tenant continues to run existing game instances and connected player sessions, and the same still-resumable session may reconnect.
  - First-time public join, a fresh gameplay binding, new instances, scale-out, and quota-increasing operations are blocked until billing is brought current.
  - Gameplay sessions and auth token sessions remain valid unless explicitly revoked for security reasons.

- When a subscription is `suspended` or `canceled`:
  - Tenant-level hosting is disabled for gameplay:
    - The Game Session Service and world-management flows must reject new game instance creations, restarts, and startup requests for the tenant when consulting `GetTenantEntitlementsForRuntime(tenantId)`.  
    - New player logins and tenant-selection attempts for that tenant are rejected with a dedicated error code and user-facing message indicating that the game is currently unavailable due to billing.
  - Existing running game instances for the tenant must be transitioned to shutdown:
    - The Account authority commit immediately makes new admission unauthorized. Downstream admission closes when the event is delivered or reconciliation observes the new generation, and no later than the `<=60-second` delayed/missed-event bound.
    - Connected gameplay authority is revoked immediately when Game Session receives the event; delayed or missed delivery is caught within the same `<=60-second` reconciliation bound. Game Session sends one bounded, non-sensitive availability notice and closes the sockets; the notice flush does not permit continued gameplay.
    - Instance processes enter a bounded drain window (target: 5 minutes maximum) for internal cleanup and then stop. During this window they are not gameplay-admissible.
  - A small, explicitly defined **billing-safe control-plane surface** remains accessible so owners can resolve billing issues or export tenant-scoped data. This surface includes actions such as updating payment methods, viewing invoices, and initiating tenant-bounded exports, but does not include full account export, starting game instances, or editing live gameplay configuration. Service-specific docs and shared authorization middleware must explicitly mark which routes participate in this billing-safe surface so they remain reachable while gameplay is blocked.
  - As part of the transition into `suspended` or `canceled`, the Account Service is the authority for the cutoff: it must commit the billing state, a monotonic durable `TenantBillingStateChanged` outbox event, and the idempotent Account-owned tenant authority-generation advance in one database transaction. This makes the authority cutoff and event enqueue immediate at the Account commit; it does not claim that a remote gameplay socket has already closed, and the cutoff workflow does not report enforcement complete until the authority projection succeeds.
  - Game Session and related services must close and revoke all affected gameplay sockets immediately when this event is delivered:
    - Revoke all gameplay sessions for the affected `tenantId` (kicking connected sockets and preventing reconnect), and
    - Reconcile entitlement caches and admission gates for the tenant.
    The socket-closure timing starts at consumer delivery, not at the Account Service commit, because durable events are at-least-once and may be delayed.
  - Downstream services must not write Account-owned authority generations directly. Implementations must not scan `session:auth:token:*` in hot paths; opportunistic cleanup of older token records is allowed only via purpose-built, bounded Account-owned indexes and background work.
  - Durable event consumption is the fast path. Game Session must also batch-reconcile active tenant authority independently of event delivery so a delayed or missed event is detected and reconciled within the `<=60-second` SLA. Failure to renew that authority-reconciliation lease closes admission and terminates bindings whose authority cannot be re-established at the bound; routine commands do not perform entitlement reads.

These behaviors tie directly into the session revocation rules described in [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management): `TenantBillingStateChanged` events for `suspended` or `canceled` must trigger revocation of gameplay sessions and regular tenant-scoped token authority, while softer billing states (`trialing`, `active`, `past_due`, `grace`) do not trigger automatic revocation and instead rely on quota and availability rules.

## Runtime Entitlement Contract

The Account Service is the source of truth for per-tenant billing state and entitlements. It exposes distinct entitlement surfaces instead of one mixed-use endpoint:

- `GetTenantEntitlementsForRuntime(tenantId)` for internal gameplay admission/runtime callers.
- `GetTenantEntitlementsTenant(tenantId)` for caller-bound tenant-admin billing-safe UX.
- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)` for support-safe cross-tenant troubleshooting with redacted fields only.

The internal runtime entitlement surface returns a snapshot of:

- The current subscription status for the tenant (for example, `trialing`, `active`, `past_due`, `grace`, `suspended`, `canceled`).  
- The effective plan and quotas (for example, maximum `active_sessions`, maximum concurrent instances, and storage/world-size tiers) derived from the active plan, plus pending plan-change metadata when a period-end downgrade or cancellation is scheduled.  
  - The current billing-state flags used for availability decisions, such as:
    - Whether the tenant is considered **available for gameplay** (for example, `trialing` or `active` vs `suspended`/`canceled`).
    - Whether first-time public join and first/new gameplay bindings are allowed under the current billing state.
    - Whether new game instances or scaling operations are allowed under the current plan and billing state.
- Freshness and sequencing metadata:
  - `evaluatedAt` (UTC timestamp when entitlements were evaluated),
  - `entitlementVersion` (monotonic entitlement snapshot/version identifier), and
  - `tenantBillingSequence` (latest applied billing-event sequence for the tenant), and
  - `tenantAuthorityGeneration` (opaque Account-owned tenant-authority fence).

Runtime services such as the Game Session Service and world-management components use this internal runtime contract as follows:

- On game instance start, restart, rollback that changes the active version, or significant scaling operations, they call `GetTenantEntitlementsForRuntime(tenantId)` and enforce both availability and quotas before admitting new load.  
- When admitting new player sessions for a tenant, they consult entitlements (either via a fresh call or a cached snapshot) to confirm that the tenant is still available for new bindings; general gameplay availability is not sufficient when `grace` has closed new admission.
- They cache entitlements per tenant, coalesce concurrent refreshes, and invalidate or advance cached state immediately when `SubscriptionStatusChanged` or `TenantBillingStateChanged` events arrive rather than checking entitlements on every tick or issuing one Account call per player.
- A strict commitment captures `tenantAuthorityGeneration`, `tenantBillingSequence`, and `entitlementVersion` from the fresh snapshot and conditionally commits only while that tuple remains current in Account authority. A billing or tenant-authority advance between evaluation and commit causes a retry or fail-closed rejection; cache invalidation alone is not a commitment fence.
- A snapshot is fresh for 15 seconds. Explicit join, first/new session admission, new instance/scale, quota increase, paid-feature activation, and capacity-creating cutover require fresh authority and fail closed with `ENTITLEMENT_UNAVAILABLE` when refresh cannot complete.
- Reconnecting the same resumable session and non-expanding restart/rollback/recovery may use a previously authoritative positive snapshot for at most five minutes from `evaluatedAt`. A different realm target or fresh binding is new admission. The five-minute maximum may be shortened or disabled but not widened by operator configuration.
- This outage fallback is valid only for the same existing binding, target, authority tuple, and still-valid resume episode with a positive authoritative snapshot. New joins, fresh bindings, expansion, target changes, and uncertain, missing, stale, negative, revoked, or gapped authority fail closed.
- Last-known-good is forbidden after observed `suspended`/`canceled`, tenant/account authority-generation revocation, a newer locally observed `tenantBillingSequence`, a sequence gap, or when no positive authoritative snapshot exists. An operation-specific negative flag invalidates authority for that requested operation only; it does not erase an otherwise eligible positive continuity snapshot for a different resumable operation. Sequence uncertainty reconciles immediately through `GetTenantEntitlementsForRuntime(tenantId)`.
- Existing uninterrupted sessions do not check entitlements per action. Hard billing events still revoke them immediately through the canonical event/authority-generation flow.
- Missing subscription state is not implicit gameplay availability. Free and trial hosting use explicit entitlement states.

## Edge Cases and Failure Handling

Edge cases around billing and subscription management include:

- **Payment failures and retries** – Repeated failed charges move a subscription from `active` → `past_due` → `grace` → `suspended` based on configured retry and grace-period policies. Webhooks annotate `subscription` records with Stripe’s failure reasons so operators can investigate.  
- **Partial periods and proration** – Upgrades and downgrades use Stripe’s proration settings; internal subscription records track the current plan and effective period, but proration details are left to Stripe.  
- **Webhook delays or outages** – If webhooks are delayed, internal subscription state may temporarily lag Stripe; regular reconciliation jobs query Stripe to detect mismatches and correct local state. During an outage, only an existing, still-resumable binding with a valid positive authoritative snapshot may use the bounded continuity fallback. New joins, fresh bindings, expansion, quota increases, target changes, and any operation with uncertain or missing authority fail closed; outage duration must not silently widen the fallback.

## APIs and Events

The Account Service exposes subscription APIs and emits events so other services can react to billing changes:

### Event Delivery Semantics (Required)

Downstream services depend on billing events for timely entitlement enforcement, but event transport is intentionally at-least-once and may be delayed. To keep behavior safe and deterministic, all billing-related events must follow these semantics:

- **At-least-once delivery** – consumers must assume duplicates and must apply events idempotently.
- **Per-tenant sequencing** – every event that affects a tenant’s availability or quotas (for example `SubscriptionStatusChanged` and `TenantBillingStateChanged`) must carry a monotonically increasing `tenantBillingSequence` scoped to `{tenantId}` so consumers can detect out-of-order or missing events.
- **Idempotency key** – every event includes a stable `eventId` (UUID) and the `(tenantId, tenantBillingSequence)` pair; consumers persist the latest applied sequence per tenant and treat older/duplicate events as no-ops.
- **Gap detection and reconciliation** – if a consumer detects a sequence gap (or has no prior authority-generation/version for a tenant), it must call `GetTenantEntitlementsForRuntime(tenantId)` to reconcile immediately and must emit an operator-visible structured `billing_event_gap` metric/log. The gap record must include `tenantId`, `expectedTenantBillingSequence`, `observedTenantBillingSequence`, `eventId`, `eventType`, `consumer` (service and subscription/handler), `eventOccurredAt` or `emittedAt`, `detectedAt`, `reconciledAt` when complete, and the event/request `correlationId` or `causationId`. These fields are mandatory; a free-text warning without them is not sufficient for detecting delayed cutoff enforcement.
- **Periodic refresh** – even when events are flowing, every healthy runtime consumer must refresh or reconcile cached entitlements at least once per 60 seconds so extended event outages meet the `<=60-second` delayed/missed-event SLA rather than causing unbounded drift.

- `CreateSubscription` – Create or update a hosting subscription for a `tenantId` and `plan_code`. Caller-bound tenant variants derive actor identity from auth context; cross-tenant admin variants are separate APIs.  
- `GetSubscriptionTenantHighLevel` / `ListSubscriptionsTenantHighLevel` – Query subscription state for one tenant, scoped by tenant authorization.  
- `ListSubscriptionsCrossTenantSupportSafe` – Cross-tenant support-safe high-level listing for troubleshooting only.  
- `ListSubscriptionsCrossTenantBillingSafeReports` – Cross-tenant billing-report listing for `billingAdmin`/`platformAdmin` only.  
- `CancelSubscription` – Cancel a subscription at period end or immediately, moving it to `canceled` and emitting events.  
- Domain events such as `SubscriptionStatusChanged` and `TenantBillingStateChanged` – Consumed by Game Session, world-management, and admin/logging services to adjust availability, quotas, and observability.

All APIs are secured using JWT-based auth and the Tenant Authorization Contract. Callers must be authorized for the `tenantId` they are querying or modifying. Cross-tenant subscription data access is split by explicit route class: support-safe views are restricted to `support`/`platformAdmin`, while billing-report views are restricted to `billingAdmin`/`platformAdmin`.

For related context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Stripe Integration Design](./stripe-integration.md)
- [Account Service README](./README.md)
