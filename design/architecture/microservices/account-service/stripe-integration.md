# Stripe Integration Design

This document describes how the Account Service integrates with **Stripe** to handle payments, donations, and subscriptions for FireMUD in a multi-tenant, auditable way.

At a high level, the goals of the integration are:

- Provide a consistent payment abstraction for tenants while using Stripe as the underlying gateway.
- Support one-time purchases, recurring subscriptions, and optional donations.
- Keep sensitive Stripe data and API keys confined to the Account Service boundary.
- Ensure idempotent, auditable payment flows that cooperate with existing saga and multi-tenancy patterns.

## Implementation Notes

The `purchase_entitlement` and account-deletion billing-owner precondition contracts below are canonical target-state behavior. Current payment code records payment transactions, donations, refunds, platform fees, and creator shares, but still needs durable purchased-entitlement fulfillment/revocation and active-subscription deletion guards.

## Domain Model

The Account Service owns billing records and maps them to Stripe resources while keeping `accountId` and `tenantId` as the primary internal keys:

- `payment_transaction`  
  - Represents a single payment attempt or completed charge, including one-time purchases, hosting fees, and donations.  
  - Key fields: internal ID, `accountId`, optional `tenantId`, `amount_cents`, `platform_fee_cents`, `creator_share_cents`, `status` (`pending`, `succeeded`, `refunded`, `failed`), `provider` (`stripe`), and `provider_id` (Stripe `payment_intent` ID).  
  - Records whether the transaction is a `donation` and which internal entity it relates to (for example, hosting subscription, in-game item purchase).

- `purchase_entitlement`  
  - Represents the durable product grant created by a successful one-time purchase when the product has ongoing account, tenant, character, virtual-currency, or gameplay value.  
  - Key fields: internal ID, `accountId`, `tenantId`, optional `characterId`, `product_code`, `grant_status` (`pending`, `active`, `revoked`, `consumed_nonrevocable`), `payment_transaction_id`, `provider_event_id`, `granted_at`, and optional `revoked_at` / `revocation_reason`.  
  - This table is the entitlement authority for one-time purchases. `payment_transaction` remains the financial/provider audit record and must not be treated as proof that gameplay value is currently active.

- `subscription`  
  - Represents a recurring billing agreement between a creator (platform account) and the platform for a specific tenant’s hosting plan.  
  - Key fields: internal ID, `accountId`, `tenantId`, `plan_code`, `status` (`trialing`, `active`, `past_due`, `grace`, `suspended`, `canceled`), current period start/end, `provider_subscription_id` (Stripe `subscription` ID), `provider_customer_id` (Stripe `customer` ID), and the explicit account-owned payment-instrument reference selected for that subscription.
  - Plan metadata defines quota-related attributes (for example, maximum active sessions, world size tiers) that the platform uses to drive per-tenant resource limits as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md#tenant-configuration--scaling).

- `billing_customer`
  - Maps one global `accountId` to its Stripe customer ID so the account has one consistent provider identity across tenants.

- `payment_instrument`
  - Represents an account-owned Stripe PaymentMethod reference plus provider-approved display metadata. FireMUD never stores raw card numbers, security codes, or equivalent payment credentials.
  - A saved instrument may be selected by several subscriptions owned by the same account, but each subscription records that selection explicitly. Established subscriptions must not fall back to a mutable customer-wide default.

### Billing Identity and Instrument Ownership

- `actorAccountId` is the authenticated caller or operator. It identifies who requested the action and is not, by itself, the account that owns a subscription or payment instrument.
- `subscription.accountId` is the subscription account and current billing owner recorded for the tenant subscription. A delegated or cross-tenant actor may act for that account only through the explicit audited billing-owner authorization or handoff contract.
- `payment_instrument.accountId` is the instrument owner. Account may select an instrument for a subscription only when it matches the authorized billing-owner account for that operation and its Stripe customer matches that account; `tenantId`, actor identity, or subscription ownership alone must not substitute for this ownership check.

## Payment Flows

All payment flows follow the same high-level pattern: create or reuse a Stripe customer, create a Payment Intent or Subscription in Stripe, persist internal records, and rely on Stripe webhooks to finalize state transitions.

New real-money charges, saved-instrument changes, subscriptions, refunds, billing-owner transfers, and payouts complete through the HTTPS account/control plane and provider-hosted flows. A Telnet or other gameplay client may explicitly initiate an eligible purchase and receive a short-lived, single-use opaque HTTPS checkout URL bound server-side to the authenticated account, gameplay session, tenant, action, product, immutable amount and currency, and request ID. The URL carries no payment credential and cannot change its bound purchase. Gameplay receives completion only after Account verifies the provider result, applies the idempotent transaction/entitlement workflow, and publishes the outcome.

### One-Time Purchases and Donations

1. A service calls `CreatePaymentIntent` on the Account Service with `accountId`, optional `tenantId`, `amount_cents`, and purchase context (for example, donation vs one-time purchase).  
2. The Account Service looks up or creates a Stripe customer for the account and calls Stripe to create a `PaymentIntent`.  
3. A `payment_transaction` row is created in `pending` status with the returned `payment_intent` ID recorded as `provider_id`.  
4. The client completes payment using Stripe’s client-side flow (for example, via Stripe.js); Stripe later calls a configured webhook when the intent succeeds or fails.  
5. The webhook handler in the Account Service:
   - Verifies the webhook signature.  
   - Locates the `payment_transaction` row by `provider_id`.  
   - Sets `status` to `succeeded` or `failed` and records Stripe failure codes where applicable.  
   - For product purchases that grant ongoing value, idempotently creates or activates the corresponding `purchase_entitlement` row using the Stripe event ID and product grant key as fulfillment idempotency inputs.  
   - Emits domain events or saga steps so other services (for example, Logging & Admin, in-game unlocks) can react.

Refunds call Stripe’s `Refund` API and update the `payment_transaction` `status` to `refunded`, enabling chargeback handling workflows. If the refunded payment created a `purchase_entitlement`, the refund workflow must revoke that entitlement unless it has already been consumed under a product contract that is explicitly non-revocable. Non-revocable consumption must be recorded as `consumed_nonrevocable` with a reason so support, audit, and revenue-sharing reports can explain why financial refund and product state diverged.

### Subscriptions and Hosting Plans

Subscription creation, lifecycle, and entitlements are covered in more detail in the [Subscription Management Design](./subscription-management.md). At a high level:

1. A creator chooses a hosting plan for a tenant in the admin UI; the caller-bound tenant variant of `CreateSubscription` derives actor identity from auth context and accepts `tenantId` plus `plan_code` only. Cross-tenant billing/admin workflows use separate admin variants when acting on another account or tenant.  
2. Before a saved instrument is selected, the Account Service resolves the actor from auth context, loads the subscription account from `subscription.accountId`, and verifies that the actor is authorized to act for the current billing owner or has an explicit billing-owner handoff for that subscription. A cross-tenant `billingAdmin` or `platformAdmin` action must use the dedicated audited billing route. Only after that authorization does Account ensure the authorized billing-owner account's Stripe customer exists, select a `payment_instrument` owned by that same account, and set that instrument explicitly on the Stripe subscription.
3. An internal `subscription` row is created or updated with `status` based on the Stripe subscription’s state and linked to the Stripe `subscription` ID.  
4. Stripe webhooks (`invoice.payment_succeeded`, `invoice.payment_failed`, `customer.subscription.updated`, `customer.subscription.deleted`) drive subsequent state transitions and keep the internal `subscription` table in sync.  
5. Changes to `subscription.status` are propagated to tenant-management and quota-enforcement components so that tenant availability and resource limits reflect the current billing state. For hard cutoff transitions such as `suspended` or `canceled`, Account transactionally advances the durable tenant authority generation with the committed billing state and emits `SubscriptionStatusChanged` plus `TenantBillingStateChanged`. Downstream services consume those events to invalidate projections and terminate affected gameplay sessions; they never advance Account authority themselves. See [Subscription Management](./subscription-management.md#tenant-availability-and-quota-enforcement) and [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management).

## Multi-Tenancy and Security

Stripe integration must preserve tenant isolation while allowing platform-level reporting:

- Each hosted game (`tenantId`) that requires billing has exactly one primary subscription record linking `accountId` and `tenantId`.  
- Account deletion is blocked while the account owns any nonterminal tenant subscription (`trialing`, `active`, `past_due`, `grace`, or `suspended`). The creator must first cancel terminally or transfer billing ownership for every affected tenant; the platform must not delete the account and leave Stripe subscriptions, shared payment instruments, or tenant billing ownership orphaned.  
- Stripe customer IDs and saved instruments are per-account, not per-tenant, to reduce duplication. Every tenant subscription nevertheless binds one selected instrument explicitly, so changing tenant A’s subscription does not change what tenant B will be charged.
- Instrument listing, attachment, and detachment are account-scoped operations available to the authenticated billing-owner subject. A `tenantAdmin` role alone does not reveal or mutate another account’s instruments. Global `billingAdmin` or `platformAdmin` intervention uses an explicit cross-tenant billing route with actor, target account, affected subscriptions, reason, and outcome audit.
- Detaching an instrument is rejected while any subscription references it unless the same idempotent workflow supplies and successfully installs a replacement for every affected subscription. The owner sees the safely displayable affected tenant/subscription set; it is not exposed to unrelated tenant operators.
- Billing-owner transfer rebinds the subscription to the new owner’s Stripe customer and explicitly selected instrument. Saved instruments never transfer between accounts.
- Customer-wide provider defaults may be used during initial setup convenience but are not authority for an established FireMUD subscription. Provider webhook processing and reconciliation verify the recorded per-subscription binding.
- If a future product requirement needs tenant-isolated payment instruments, the platform must move to tenant-scoped billing customers rather than treating the current account-owned wallet as implicitly tenant-safe.
- Internal queries always filter billing records by both `accountId` and `tenantId` when operating on tenant-specific subscriptions or transactions. Cross-tenant reports are restricted to roles with appropriate `globalRoles` as defined in the shared role model:
  - `platformAdmin` for full cross-tenant reporting, and
  - `billingAdmin` for billing-focused reporting surfaces.
  Cross-tenant support troubleshooting is exposed only through explicitly support-safe variants (`cross_tenant_support_safe`) with high-level redacted fields. These rules are enforced by the Tenant Authorization Contract.  
- Stripe API keys, webhook secrets, and any PCI-relevant configuration remain confined to the Account Service. Other services never communicate with Stripe directly.

## Service APIs

The Account Service exposes gRPC and REST endpoints for initiating and inspecting billing flows:

- `CreatePaymentIntent` – Initiate a one-time payment or donation and return the client-facing Stripe Payment Intent details.  
- `RefundPayment` – Issue a refund for an existing `payment_transaction` and update its status.  
- `CreateSubscription` – Start or update a recurring hosting subscription for a specific `tenantId` and `plan_code`.  
- Subscription and transaction query APIs – Must be split into explicit tenant-scoped billing-safe, cross-tenant support-safe, and cross-tenant billing-safe variants (no mixed-mode endpoint behavior). Per-tenant caller-bound billing history is visible to `tenantAdmin`; global `platformAdmin`/`billingAdmin` access uses cross-tenant billing-safe variants only.

All endpoints are protected by JWT-based auth, and tenant-scoped operations must validate that the caller is allowed to act on the specified `tenantId` using the Tenant Authorization Contract from [Authentication & Authorization](../../system-architecture-authentication.md#tenant-authorization-contract).

## Operational Concerns

Operational behavior around Stripe integration focuses on observability, idempotency, and resilience:

- Webhook handlers are idempotent and keyed by Stripe event IDs; repeated deliveries do not change internal state after the first successful application.  
- Metrics track payment and subscription statuses (for example, counts of `payment_transaction` by `status`, and subscriptions in `past_due` or `grace` states).  
- Alerts fire when webhook processing fails repeatedly, when Stripe API calls start failing at elevated rates, or when the number of tenants in `grace` or `suspended` billing states exceeds thresholds.  
- During Stripe outages, new purchases and subscription changes fail closed, but existing tenants remain in their last-known-good state until internal policies (for example, maximum grace period) dictate otherwise. Webhook reconciliation may repair Account's committed billing state and outbox, but Account remains the sole producer of `SubscriptionStatusChanged` and `TenantBillingStateChanged`; downstream consumers process those Account-owned events. Failures in either stage remain independently observable so potential entitlement-enforcement drift can be correlated without introducing a second event source.

For current requirements and additional context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Subscription Management Design](./subscription-management.md)
- [Account Service README](./README.md)
