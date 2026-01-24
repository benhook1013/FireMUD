# Subscription Management Design

This document describes how the Account Service models and manages subscriptions for FireMUD game creators and players, and how subscription state drives tenant availability and resource quotas.

The goals of the subscription system include:

- Support recurring billing for hosting plans and optional in-game features.
- Handle upgrades, downgrades, and cancellations without data loss.
- Respect tenant isolation while still enabling platform-wide reporting.
- Coordinate with Stripe while keeping billing state consistent in FireMUD’s own database.

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

## Lifecycle Flows

The subscription lifecycle is modeled as a finite state machine:

- `trialing` – The tenant is in a time-limited trial; full hosting features are enabled but may be subject to conservative quotas.  
- `active` – The subscription is paid and current; quotas and entitlements from the selected plan apply.  
- `past_due` – Recent billing attempts have failed; the platform has not yet enforced restrictions, but alerts and UI warnings appear.  
- `grace` – A configured grace period after `past_due` where some or all entitlements may be restricted (for example, blocking new game instances while allowing existing ones to run).  
- `suspended` – Hosting entitlements are temporarily disabled due to non-payment or policy; new sessions and instance starts are blocked for the tenant.  
- `canceled` – The subscription has been terminated; long-term data retention and clean-up policies apply.

State transitions are driven by:

- Admin or creator actions (for example, creating or canceling a subscription, changing plans).  
- Stripe events (for example, successful invoice, payment failure, subscription updated/deleted).  
- Internal timers (for example, moving from `past_due` to `grace` to `suspended` once configured durations elapse).

Each transition triggers domain events that downstream services can consume to adjust quotas or availability.

## Tenant Availability and Quota Enforcement

Subscription status feeds directly into tenant availability and resource enforcement:

- When a subscription is `trialing` or `active`:
  - The tenant is **available**; the Game Session Service may start and run game instances for that tenant.  
  - Quotas derived from the plan (for example, maximum `active_sessions`, allowed world size) are applied when starting instances or admitting new player sessions.

- When a subscription is `past_due`:
  - The tenant remains available, but operator dashboards and creator/admin UIs surface prominent warnings.  
  - Optional soft restrictions may apply (for example, preventing further plan upgrades).

- When a subscription enters `grace`:
  - The tenant continues to run existing game instances and player sessions.  
  - New instances or large-scale operations (for example, starting additional shards) may be blocked until billing is brought current.

- When a subscription is `suspended` or `canceled`:
  - Tenant-level hosting is disabled:
    - The Game Session Service and world-management flows must reject new game instance creations and startup requests for the tenant.  
    - New player logins for that tenant are rejected with a dedicated error code and user-facing message indicating that the game is currently unavailable due to billing.
  - Admin and creator tools remain accessible so owners can resolve billing issues or export data.

These behaviors tie directly into the session revocation rules described in [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management): when a tenant becomes `suspended` or `canceled`, relevant gameplay sessions are revoked and new sessions are blocked.

## Edge Cases and Failure Handling

Edge cases around billing and subscription management include:

- **Payment failures and retries** – Repeated failed charges move a subscription from `active` → `past_due` → `grace` → `suspended` based on configured retry and grace-period policies. Webhooks annotate `subscription` records with Stripe’s failure reasons so operators can investigate.  
- **Partial periods and proration** – Upgrades and downgrades use Stripe’s proration settings; internal subscription records track the current plan and effective period, but proration details are left to Stripe.  
- **Webhook delays or outages** – If webhooks are delayed, internal subscription state may temporarily lag Stripe; regular reconciliation jobs query Stripe to detect mismatches and correct local state. During extended outages, the platform errs on the side of keeping existing tenants available until configured maximum grace windows expire.

## APIs and Events

The Account Service exposes subscription APIs and emits events so other services can react to billing changes:

- `CreateSubscription` – Create or update a hosting subscription for a `tenantId` and `plan_code`.  
- `GetSubscription` / `ListSubscriptions` – Query subscription state for a tenant, scoped by the caller’s authorization.  
- `CancelSubscription` – Cancel a subscription at period end or immediately, moving it to `canceled` and emitting events.  
- Domain events such as `SubscriptionStatusChanged` and `TenantBillingStateChanged` – Consumed by Game Session, world-management, and admin/logging services to adjust availability, quotas, and observability.

All APIs are secured using JWT-based auth and the Tenant Authorization Contract. Callers must be authorized for the `tenantId` they are querying or modifying, and only platform-level roles (for example, `platformAdmin`) may access cross-tenant subscription data.

For related context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Stripe Integration Design](./stripe-integration.md)
- [Account Service README](./README.md)
