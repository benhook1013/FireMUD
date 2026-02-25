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

### Authorization Roles for Billing and Subscriptions

To keep authorization consistent, subscription and billing operations map to the role model defined in the Authentication & Authorization design:

- **Per-tenant operations** (for example, create/update/cancel subscription for a given `tenantId`, view billing history for that tenant):
  - Allowed for:
    - `tenantAdmin` for that `tenantId`, and
    - Global `platformAdmin` and `billingAdmin` roles.
- **Cross-tenant billing reports and analytics** (for example, billing-focused multi-tenant reports and revenue dashboards):
  - Allowed only for global roles:
    - `platformAdmin` for full reporting, and
    - `billingAdmin` for billing-focused reporting surfaces.

Implementations must not introduce ad-hoc “owner” or “admin” concepts; they should rely on `tenantAdmin`, `platformAdmin`, and `billingAdmin` from the shared role model and the Tenant Authorization Contract. Support roles (`support`) may read high-level subscription state and derived entitlements for troubleshooting purposes but must not be granted access to detailed billing artifacts (for example, invoices or payment methods) or to any subscription-mutating APIs. These troubleshooting endpoints must be explicitly classified as **support-safe** in the auth middleware contract (`design/architecture/system-architecture-authentication.md#auth-middleware-algorithm-normative`) so support access cannot accidentally expand to billing-safe or data-bearing surfaces.
For billing-safe tenant routes that intentionally remain reachable during `suspended`/`canceled` periods, services must perform a live membership/role check against authoritative account-tenant membership data (for example `GetTenantMembership(accountId, tenantId)`) before allowing mutations; JWT role claims alone are insufficient for billing-safe mutations.
`GetTenantMembership` (or protocol-equivalent) responses must include `evaluatedAt` and `membershipVersion`; if membership authority is unavailable, billing-safe mutations fail closed.

Current support-safe allowlist in this domain:

- `GetTenantEntitlements(tenantId)`
- `GetSubscription(tenantId)` with high-level status/plan fields only
- `ListSubscriptionsSupportSafe` with high-level status/plan fields only

The following are explicitly not support-safe: invoice exports, payment method details, Stripe customer metadata, any subscription mutation (`CreateSubscription`, plan change, cancellation), and billing-report variants such as `ListSubscriptionsBillingReports`.

All billing/support route classifications in this domain must be registered in [Authorization Route Matrix](../../system-architecture-authz-route-matrix.md).

## Lifecycle Flows

The subscription lifecycle is modeled as a finite state machine:

- `trialing` – The tenant is in a time-limited trial; full hosting features are enabled but may be subject to conservative quotas.  
- `active` – The subscription is paid and current; quotas and entitlements from the selected plan apply.  
- `past_due` – Recent billing attempts have failed; the platform has not yet enforced restrictions, but alerts and UI warnings appear.  
- `grace` – A configured grace period after `past_due` where some or all entitlements may be restricted (for example, blocking new game instances while allowing existing ones to run).  
- `suspended` – Hosting entitlements are temporarily disabled due to non-payment or policy; new sessions and instance starts are blocked for the tenant.  
- `canceled` – The subscription has been terminated; long-term data retention and clean-up policies apply.

This lifecycle table is the canonical subscription status contract for FireMUD. Other documents (including Stripe integration and auth/session gating) must reuse these exact status values and transition semantics.

State transitions are driven by:

- Admin or creator actions (for example, creating or canceling a subscription, changing plans).  
- Stripe events (for example, successful invoice, payment failure, subscription updated/deleted).  
- Internal timers (for example, moving from `past_due` to `grace` to `suspended` once configured durations elapse).

Each transition triggers domain events that downstream services can consume to adjust quotas or availability.

## Tenant Availability and Quota Enforcement

Subscription status feeds directly into tenant availability and resource enforcement. To keep behavior consistent across services, the following states and effects apply:

- When a subscription is `trialing` or `active`:
  - The tenant is **available for gameplay**; the Game Session Service may start and run game instances for that tenant.  
  - Quotas derived from the plan (for example, maximum `active_sessions`, allowed world size) are applied when starting instances or admitting new player sessions.

- When a subscription is `past_due`:
  - The tenant remains available for gameplay, but operator dashboards and creator/admin UIs surface prominent warnings.  
  - Optional soft restrictions may apply (for example, preventing further plan upgrades or new instance types) without revoking existing sessions.

- When a subscription enters `grace`:
  - The tenant continues to run existing game instances and player sessions.  
  - New instances or large-scale operations (for example, starting additional shards) may be blocked until billing is brought current, based on plan and operator policy.  
  - Gameplay sessions and auth token sessions remain valid unless explicitly revoked for security reasons.

- When a subscription is `suspended` or `canceled`:
  - Tenant-level hosting is disabled for gameplay:
    - The Game Session Service and world-management flows must reject new game instance creations, restarts, and startup requests for the tenant when consulting `GetTenantEntitlements(tenantId)`.  
    - New player logins and tenant-selection attempts for that tenant are rejected with a dedicated error code and user-facing message indicating that the game is currently unavailable due to billing.
  - Existing running game instances for the tenant must be transitioned to shutdown:
    - Admission is closed immediately (no new sessions).
    - Connected gameplay sessions are revoked immediately.
    - Instance processes enter a bounded drain window (target: 5 minutes maximum) for internal cleanup and then stop. During this window they are not gameplay-admissible.
  - A small, explicitly defined **billing-safe control-plane surface** remains accessible so owners can resolve billing issues or export data. This surface includes actions such as updating payment methods, viewing invoices, and initiating exports, but does not include starting game instances or editing live gameplay configuration. Service-specific docs and shared authorization middleware must explicitly mark which routes participate in this billing-safe surface so they remain reachable while gameplay is blocked.
  - As part of the transition into `suspended` or `canceled`, the Account Service must:
    - Write `session:auth:revoked_after:tenant:<tenantId>` with the current timestamp (authoritative writer), and
    - Emit a `TenantBillingStateChanged` event with `billing_state` set to `suspended` or `canceled`.
  - Game Session and related services consume this event and immediately:
    - Revoke all gameplay sessions for the affected `tenantId` (kicking connected sockets and preventing reconnect), and
    - Reconcile entitlement caches and admission gates for the tenant.
  - Downstream services must not write `session:auth:revoked_after:*` watermark keys directly. Implementations must not rely on wildcard deletes (`session:auth:tenant:<tenantId>:*`) in hot paths; opportunistic cleanup of tenant-scoped allowlist entries is allowed only via purpose-built, bounded indexes and background work.

These behaviors tie directly into the session revocation rules described in [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management): `TenantBillingStateChanged` events for `suspended` or `canceled` must trigger revocation of gameplay sessions and regular tenant-scoped auth entries, while softer billing states (`trialing`, `active`, `past_due`, `grace`) do not trigger automatic revocation and instead rely on quota and availability rules.

## Runtime Entitlement Contract

The Account Service is the source of truth for per-tenant billing state and entitlements. It exposes a `GetTenantEntitlements(tenantId)` API (gRPC and REST) that returns a snapshot of:

- The current subscription status for the tenant (for example, `trialing`, `active`, `past_due`, `grace`, `suspended`, `canceled`).  
- The effective plan and quotas (for example, maximum `active_sessions`, maximum concurrent instances, and storage/world-size tiers) derived from the active plan.  
- The current billing-state flags used for availability decisions, such as:
  - Whether the tenant is considered **available for gameplay** (for example, `trialing` or `active` vs `suspended`/`canceled`).  
  - Whether new game instances or scaling operations are allowed under the current plan and billing state.
- Freshness and sequencing metadata:
  - `evaluatedAt` (UTC timestamp when entitlements were evaluated),
  - `entitlementVersion` (monotonic entitlement snapshot/version identifier), and
  - `tenantBillingSequence` (latest applied billing-event sequence for the tenant).

Runtime services such as the Game Session Service and world-management components use this contract as follows:

- On game instance start, restart, rollback that changes the active version, or significant scaling operations, they call `GetTenantEntitlements(tenantId)` and enforce both availability and quotas before admitting new load.  
- When admitting new player sessions for a tenant, they consult entitlements (either via a fresh call or a cached snapshot) to confirm that the tenant is still available for new logins.  
- They cache entitlements for a bounded period (for example, at most 60 seconds) and **must** invalidate or refresh them immediately when `SubscriptionStatusChanged` or `TenantBillingStateChanged` events are received, rather than checking entitlements on every tick. Admission paths (new player logins and game instance start/restart flows) must always consult a fresh or recently refreshed entitlement snapshot and must not bypass revocation rules based solely on stale cache entries.
- Admission-critical paths (`PLAY`, new session admission, instance start/restart/rollback) must enforce a hard entitlement freshness bound of 15 seconds. If the local entitlement snapshot is older than this bound and a refresh cannot be completed immediately, the path fails closed with canonical error `ENTITLEMENT_UNAVAILABLE` (or protocol-mapped equivalent).
- Admission-critical paths must also fail closed when `tenantBillingSequence` is behind locally observed sequence or when a sequence gap is detected, then reconcile immediately via `GetTenantEntitlements(tenantId)`.

## Edge Cases and Failure Handling

Edge cases around billing and subscription management include:

- **Payment failures and retries** – Repeated failed charges move a subscription from `active` → `past_due` → `grace` → `suspended` based on configured retry and grace-period policies. Webhooks annotate `subscription` records with Stripe’s failure reasons so operators can investigate.  
- **Partial periods and proration** – Upgrades and downgrades use Stripe’s proration settings; internal subscription records track the current plan and effective period, but proration details are left to Stripe.  
- **Webhook delays or outages** – If webhooks are delayed, internal subscription state may temporarily lag Stripe; regular reconciliation jobs query Stripe to detect mismatches and correct local state. During extended outages, the platform errs on the side of keeping existing tenants available until configured maximum grace windows expire.

## APIs and Events

The Account Service exposes subscription APIs and emits events so other services can react to billing changes:

### Event Delivery Semantics (Required)

Downstream services depend on billing events for timely entitlement enforcement, but event transport is intentionally at-least-once and may be delayed. To keep behavior safe and deterministic, all billing-related events must follow these semantics:

- **At-least-once delivery** – consumers must assume duplicates and must apply events idempotently.
- **Per-tenant sequencing** – every event that affects a tenant’s availability or quotas (for example `SubscriptionStatusChanged` and `TenantBillingStateChanged`) must carry a monotonically increasing `tenantBillingSequence` scoped to `{tenantId}` so consumers can detect out-of-order or missing events.
- **Idempotency key** – every event includes a stable `eventId` (UUID) and the `(tenantId, tenantBillingSequence)` pair; consumers persist the latest applied sequence per tenant and treat older/duplicate events as no-ops.
- **Gap detection and reconciliation** – if a consumer detects a sequence gap (or has no prior watermark for a tenant), it must call `GetTenantEntitlements(tenantId)` to reconcile immediately and should emit an operator-visible warning metric/log indicating entitlement drift was possible.
- **Periodic refresh** – even when events are flowing, runtime services should refresh cached entitlements on a bounded interval (for example once per minute) so extended event outages do not cause unbounded drift.

- `CreateSubscription` – Create or update a hosting subscription for a `tenantId` and `plan_code`.  
- `GetSubscription` / `ListSubscriptionsTenantHighLevel` – Query subscription state for one tenant, scoped by tenant authorization.  
- `ListSubscriptionsSupportSafe` – Cross-tenant support-safe high-level listing for troubleshooting only.  
- `ListSubscriptionsBillingReports` – Cross-tenant billing-report listing for `billingAdmin`/`platformAdmin` only.  
- `CancelSubscription` – Cancel a subscription at period end or immediately, moving it to `canceled` and emitting events.  
- Domain events such as `SubscriptionStatusChanged` and `TenantBillingStateChanged` – Consumed by Game Session, world-management, and admin/logging services to adjust availability, quotas, and observability.

All APIs are secured using JWT-based auth and the Tenant Authorization Contract. Callers must be authorized for the `tenantId` they are querying or modifying. Cross-tenant subscription data access is restricted to the global roles defined above for cross-tenant billing reports (typically `platformAdmin` and `billingAdmin`).

For related context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Stripe Integration Design](./stripe-integration.md)
- [Account Service README](./README.md)
