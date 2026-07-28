# Stripe Integration Design

This document describes how the Account Service integrates with **Stripe** to handle payments, donations, and subscriptions for FireMUD in a multi-tenant, auditable way.

At a high level, the goals of the integration are:

- Provide a consistent payment abstraction for tenants while using Stripe as the underlying gateway.
- Support one-time purchases, recurring subscriptions, and optional donations.
- Keep sensitive Stripe data and API keys confined to the Account Service boundary.
- Ensure idempotent, auditable payment flows that cooperate with existing saga and multi-tenancy patterns.

## Implementation Status

The `purchase_entitlement` and account-deletion billing-owner precondition contracts below are canonical target-state behavior. Current payment code records payment transactions, donations, refunds, platform fees, and creator shares, but still needs durable purchased-entitlement fulfillment/revocation and active-subscription deletion guards.

The account-owned wallet contracts are also target state. Per-subscription instrument binding, replacement-before-detach, and the audited `cross_tenant_billing_safe` ownership-transfer route remain absent or partial until the corresponding routes and enforcement are implemented; the [Authorization Route Matrix](../../system-architecture-authz-route-matrix.md) records that coverage drift.

## Domain Model

The Account Service owns billing records and maps them to Stripe resources. Records use immutable internal IDs for stable identity; for `subscription`, the internal `subscriptionId` is the sole stable identity. `accountId`, `tenantId`, and `plan_code` are mutable lookup, authorization, and constraint fields, not key material.

- `payment_transaction`  
  - Represents a single payment attempt or completed charge, including one-time purchases, hosting fees, and donations.  
  - Key fields: internal ID, `accountId`, optional `tenantId`, `amount_cents`, `platform_fee_cents`, `creator_share_cents`, `status` (`pending`, `succeeded`, `refunded`, `failed`), `provider` (`stripe`), and `provider_id` (Stripe `payment_intent` ID).  
  - Records whether the transaction is a `donation` and which internal entity it relates to (for example, hosting subscription, in-game item purchase).

- `purchase_operation`
  - Is the durable operation and webhook-correlation record for a one-time purchase or donation. It is committed before any external Stripe customer or `PaymentIntent` call and binds the authenticated `accountId`, optional `tenantId`, operation type, caller request ID, immutable purchase digest, separate customer-provisioning operation reference, and stable PaymentIntent idempotency identity.
  - Supplies the immutable, non-secret provider-metadata correlation identity used by webhook and reconciliation processing. Provider IDs and the linked `payment_transaction` are added when known; a lost response or early webhook leaves the operation nonterminal until those links and the resulting financial/product state are reconciled.

- `stripe_event_inbox`
  - Idempotently retains each verified Stripe event by provider event ID together with its extracted purchase-operation correlation identity and processing state.
  - An event that arrives before `provider_id` or transaction linkage is persisted remains pending against the durable purchase operation and is retried after linkage repair. It is never discarded or treated as terminal merely because the local provider ID is not yet available.

- `purchase_entitlement`  
  - Represents the durable product grant created by a successful one-time purchase when the product has ongoing account, tenant, character, virtual-currency, or gameplay value.  
  - Key fields: internal ID, `accountId`, `tenantId`, optional `characterId`, `product_code`, `grant_status` (`pending`, `active`, `revoked`, `consumed_nonrevocable`), `payment_transaction_id`, `provider_event_id`, `granted_at`, and optional `revoked_at` / `revocation_reason`.  
  - This table is the entitlement authority for one-time purchases. `payment_transaction` remains the financial/provider audit record and must not be treated as proof that gameplay value is currently active.

- `subscription`  
  - Represents a recurring billing agreement between a creator (platform account) and the platform for a specific tenant’s hosting plan.  
  - Key fields: immutable internal `subscriptionId`, `accountId`, `tenantId`, `plan_code`, `status` (`pending`, `provisioning`, `trialing`, `active`, `past_due`, `grace`, `suspended`, `canceled`), current period start/end, `provider_subscription_id` (Stripe `subscription` ID), `provider_customer_id` (Stripe `customer` ID), an immutable `subscription_provisioning_request_id`/subscription-operation idempotency identity, the reserved payment-instrument version, a reference to the separately immutable customer-provisioning request identity, and a non-null persisted subscription-specific binding to the selected account-owned payment instrument. Only `subscriptionId` is stable identity; `accountId`, `tenantId`, and `plan_code` remain mutable lookup/constraint fields.
  - `pending` and `provisioning` are durable pre-activation states. `pending` means the local subscription intent and provider work item have committed but provider creation has not been claimed; `provisioning` means the provider call is in progress or awaiting recovery. Neither state grants hosting entitlements or permits billing.
  - Plan metadata defines quota-related attributes (for example, maximum active sessions, world size tiers) that the platform uses to drive per-tenant resource limits as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md#tenant-configuration--scaling).

- `billing_customer`
  - Maps one global `accountId` to its Stripe customer ID so the account has one consistent provider identity across tenants.
  - Also records the durable account-keyed customer-provisioning intent, its own immutable `customer_provisioning_request_id`/customer-provider idempotency identity, provider customer ID when known, and reconciliation state. A timeout, lost response, or failed local commit must reconcile this same intent and provider metadata before another customer-create attempt; ambiguous state remains nonterminal rather than creating a duplicate customer. This identity is never reused as the idempotency identity for a subscription create or update.

- `payment_instrument`
  - Represents an account-owned Stripe PaymentMethod reference plus provider-approved display metadata. FireMUD never stores raw card numbers, security codes, or equivalent payment credentials.
  - A saved instrument may be selected by several subscriptions owned by the same account, but each subscription records that selection explicitly before activation or charge. Established subscriptions must not fall back to a mutable customer-wide default.

### Billing Identity and Instrument Ownership

- `actorAccountId` is the authenticated caller or operator. It identifies who requested the action and is not, by itself, the account that owns a subscription or payment instrument.
- `subscription.accountId` is the subscription account and current billing owner recorded for the tenant subscription. A delegated or cross-tenant actor may act for that account only through the explicit audited billing-owner authorization or handoff contract.
- `payment_instrument.accountId` is the instrument owner. Account may select an instrument for a subscription only when it matches the authorized billing-owner account for that operation and its Stripe customer matches that account; `tenantId`, actor identity, or subscription ownership alone must not substitute for this ownership check.

## Payment Flows

All payment flows follow the same high-level pattern: persist the durable operation and webhook-correlation identity before any external Stripe call, create or reuse a Stripe customer, persist or confirm the internal authorization and billing record, create the provider resource, and rely on Stripe webhooks to finalize state transitions. Customer provisioning and subscription provisioning are separate durable operations/work items. Each has its own stable idempotency identity, retry/reconciliation state, and recovery boundary; a subscription operation may depend on a confirmed customer identity but must not conflate customer creation with subscription creation. For subscriptions, a persisted subscription-specific instrument binding is a prerequisite for activation and every charge; provider defaults are not a substitute.

New real-money charges, saved-instrument changes, subscriptions, refunds, billing-owner transfers, and payouts complete through the HTTPS account/control plane and provider-hosted flows. A Telnet or other gameplay client may explicitly initiate an eligible purchase and receive a short-lived, single-use opaque HTTPS checkout URL bound server-side to the authenticated account, gameplay session, tenant, action, product, immutable amount and currency, and request ID. The URL carries no payment credential and cannot change its bound purchase. Gameplay receives completion only after Account verifies the provider result, applies the idempotent transaction/entitlement workflow, and publishes the outcome.

### One-Time Purchases and Donations

1. A service calls `CreatePaymentIntent` on the Account Service with `accountId`, optional `tenantId`, `amount_cents`, and purchase context (for example, donation vs one-time purchase). The Account Service derives the authoritative account from authenticated context and, before any external Stripe customer or `PaymentIntent` call, commits one durable `purchase_operation` and its webhook-correlation identity bound to the operation type, caller request ID, immutable purchase digest, customer-operation reference, and separate stable PaymentIntent idempotency identity. The webhook must resolve through this durable operation identity rather than a provider ID that may not yet have been persisted.
2. The Account Service provisions or reuses the Stripe customer through the same durable intent contract as `billing_customer`: persist the account-keyed provisioning intent and stable customer-provisioning idempotency identity, reconcile provider metadata before retrying after a timeout, lost response, or failed local commit, and leave ambiguous state nonterminal. A generic lookup-or-create flow after an ambiguous attempt is prohibited. Once the customer is confirmed, Account calls Stripe to create the `PaymentIntent` using the purchase operation's distinct stable payment-intent idempotency identity and non-secret provider metadata carrying the durable purchase-operation correlation identity.
3. A `payment_transaction` row is created in `pending` status and linked to the purchase operation, with the returned `payment_intent` ID recorded as `provider_id`. If the provider call may have succeeded but the response or local linkage commit is lost, the operation remains nonterminal and reconciliation uses the same idempotency and provider-metadata identities; it never creates a new logical purchase attempt.
4. The client completes payment using Stripe’s client-side flow (for example, via Stripe.js); Stripe later calls a configured webhook when the intent succeeds or fails.  
5. The webhook handler in the Account Service:
   - Verifies the webhook signature and idempotently records the event in `stripe_event_inbox` by Stripe event ID before applying business state.
   - Resolves the durable purchase operation from its provider metadata correlation identity and then locates or repairs the linked `payment_transaction` by operation identity and `provider_id`. An event that arrives before local provider-ID/linkage persistence remains pending in the inbox and is reconciled after linkage becomes available.
   - Sets `status` to `succeeded` or `failed` and records Stripe failure codes where applicable.  
   - For product purchases that grant ongoing value, idempotently creates or activates the corresponding `purchase_entitlement` row using the Stripe event ID and product grant key as fulfillment idempotency inputs.  
   - Emits domain events or saga steps so other services (for example, Logging & Admin, in-game unlocks) can react.
   - Marks the purchase operation and inbox event terminal only after provider linkage, transaction state, and any required entitlement or failure outcome have converged durably.

Refunds call Stripe’s `Refund` API and update the `payment_transaction` `status` to `refunded`, enabling chargeback handling workflows. If the refunded payment created a `purchase_entitlement`, the refund workflow must revoke that entitlement unless it has already been consumed under a product contract that is explicitly non-revocable. Non-revocable consumption must be recorded as `consumed_nonrevocable` with a reason so support, audit, and revenue-sharing reports can explain why financial refund and product state diverged.

### Subscriptions and Hosting Plans

Subscription creation, lifecycle, and entitlements are covered in more detail in the [Subscription Management Design](./subscription-management.md). At a high level:

1. A creator chooses a hosting plan for a tenant in the admin UI; the caller-bound tenant variant of `CreateSubscription` derives actor identity from auth context and requires `tenantId`, `plan_code`, and either an explicit saved `paymentInstrumentId` or deterministic instrument-selection input that resolves to exactly one saved instrument. Cross-tenant billing/admin workflows use separate admin variants when acting on another account or tenant.
2. For a new subscription, before any Stripe customer or subscription provisioning, the Account Service resolves the authoritative billing owner for the tenant and verifies that the actor is authorized to act for that owner or has an explicit billing-owner handoff. It allocates the immutable internal `subscriptionId`, then commits a durable subscription operation/correlation record and outbox work item bound to the operation, authenticated caller, account owner, tenant, and caller request identity before provisioning begins. For an existing subscription update, it reads the current owner from the existing row. A cross-tenant `billingAdmin` or `platformAdmin` action must use the dedicated audited billing route. Account then links the subscription operation to that owner's separate durable account-keyed customer-provisioning operation, which must complete or reconcile to a confirmed Stripe customer identity before the subscription work item can transition to provider creation. The customer operation has its own immutable `customer_provisioning_request_id`; the subscription operation has a different immutable `subscription_provisioning_request_id`. Every retry recovers the existing operation of the relevant type and reuses only that operation's provider-resource identity. Customer recovery must not create a subscription, and subscription recovery must not create a second customer. The requested or deterministically selected `payment_instrument` must be owned by that same account and belong to that customer.
3. Account atomically persists the subscription’s immutable internal `subscriptionId`, owner, non-null subscription-specific instrument binding and reserved instrument version, durable `pending` status, the separate customer-operation reference, the immutable `subscription_provisioning_request_id`, and the outbox work item in one database transaction. The provider customer reference may remain null only while the subscription stays `pending`; a confirmed non-null customer reference is mandatory before the work item becomes eligible for `provisioning` or any provider-subscription call. The subscription-operation idempotency identity is reused for every retry or recovery of that subscription operation; it is never reused for customer provisioning or a later update to an existing subscription. Customer retries and reconciliation reuse only `customer_provisioning_request_id` and its provider customer identity. Every existing-subscription plan, instrument, cancellation, or other provider update has its own immutable operation-specific request ID and expected subscription row version/CAS; Account rejects a stale version, and retries reuse that same update identity and guard. The provider call must use the persisted customer, payment-method, and operation-specific idempotency identifiers; Stripe customer defaults and other provider defaults are prohibited. A missing, stale, or mismatched binding fails closed and cannot be repaired by a provider default.
4. A durable worker claims the work item only after an atomic transaction revalidates the persisted instrument owner, exact reserved instrument version, matching Stripe customer, and attachable state, then moves the row to `provisioning`. A pending or provisioning reservation blocks detach; if detach wins before claim, its version advance makes the claim fail before any Stripe call. The worker then creates or recovers the provider subscription using the same `subscription_provisioning_request_id`. A crash, timeout, or lost response is recovered by retrying that same operation identity and reconciling Stripe before any new subscription create is attempted. Customer reconciliation remains a separate operation using only its customer identity. If Stripe created the subscription but the local provider ID was not recorded, reconciliation locates the resource by the subscription operation's idempotency identity/provider metadata and repairs `provider_subscription_id` without creating a second subscription.
5. Account records the confirmed Stripe `subscription` ID before moving the local subscription into `trialing` or `active`; activation and invoice charging are forbidden while the row is `pending`, `provisioning`, or missing a confirmed provider ID. Stripe webhook and reconciliation processing rechecks the persisted binding before accepting activation or any invoice charge, including renewals.
6. Stripe webhooks (`invoice.payment_succeeded`, `invoice.payment_failed`, `customer.subscription.updated`, `customer.subscription.deleted`) resolve through the durable subscription operation/correlation identity, then drive subsequent state transitions and keep the internal `subscription` table in sync.
7. Changes to `subscription.status` are propagated to tenant-management and quota-enforcement components so that tenant availability and resource limits reflect the current billing state. For hard cutoff transitions such as `suspended` or `canceled`, Account transactionally advances the durable tenant authority generation with the committed billing state and emits `SubscriptionStatusChanged` plus `TenantBillingStateChanged`. Downstream services consume those events to invalidate projections and terminate affected gameplay sessions; they never advance Account authority themselves. See [Subscription Management](./subscription-management.md#tenant-availability-and-quota-enforcement) and [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management).

## Multi-Tenancy and Security

Stripe integration must preserve tenant isolation while allowing platform-level reporting:

- Each hosted game (`tenantId`) that requires billing has exactly one primary subscription record linking `accountId` and `tenantId`.  
- Account deletion is blocked while the account owns any nonterminal tenant subscription (`pending`, `provisioning`, `trialing`, `active`, `past_due`, `grace`, or `suspended`) or unresolved customer/subscription provisioning intent. The creator must first cancel or drain committed provider/outbox work, reconcile any ambiguous provider outcome, and then cancel terminally or transfer billing ownership for every affected tenant; the platform must not delete the account and leave Stripe resources, shared payment instruments, or tenant billing ownership orphaned.
- Stripe customer IDs and saved instruments are per-account, not per-tenant, to reduce duplication. Every tenant subscription nevertheless persists one selected instrument binding before activation or charge, so changing tenant A’s subscription does not change what tenant B will be charged.
- Instrument listing, attachment, and detachment are account-scoped operations available to the authenticated billing-owner subject. A `tenantAdmin` role alone does not reveal or mutate another account’s instruments. Global `billingAdmin` or `platformAdmin` intervention is a bounded recovery or administrative action, not ordinary owner-wallet access: it uses an explicit cross-tenant billing route requiring recent reauthentication with `privileged_control` assurance and a durable audit record containing actor, target account, affected subscriptions, reason, scope, and outcome. It grants no standing wallet access and does not replace the billing-owner restriction for ordinary operations.
- Detaching an instrument is rejected while any subscription references it unless the same idempotent workflow supplies and successfully installs a replacement for every affected subscription. The owner sees the safely displayable affected tenant/subscription set; it is not exposed to unrelated tenant operators.
- Billing-owner transfer is an explicit, audited handoff through the dedicated `cross_tenant_billing_safe` route, recording the actor, current owner, new owner, affected subscription, reason, paid-through effective boundary, and outcome. Because Stripe cannot reassign an existing subscription to another Customer, Account durably coordinates a replacement: it creates or reconciles one new-owner subscription scheduled to begin billing at the old paid-through boundary and schedules the old subscription to end at that same boundary. The old binding remains authoritative until provider state and the guarded local cutover are verified; exactly one subscription may authorize hosting entitlement. Ambiguous or partial outcomes remain nonterminal and reconcile by stable creation/cancellation identities. Immediate mid-period transfer, cross-customer credit movement, and proration are unsupported; saved instruments never transfer between accounts.
- `CreateSubscription`, activation, and every subscription charge must supply the persisted customer and payment-method identifiers; Stripe customer defaults and other provider defaults are prohibited. Provider webhook processing and reconciliation verify the persisted per-subscription binding for activation and every charge, including renewals; a missing or mismatched binding fails closed.
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
- Subscription provisioning is an outbox-backed, restart-safe workflow. Reconciliation must inspect `pending`/`provisioning` rows, reuse the subscription operation's stable provider idempotency identity, repair missing provider IDs, and surface ambiguous provider state for operator resolution rather than issuing an unkeyed create. Customer provisioning is reconciled separately with its own stable identity; neither operation may reuse the other's provider-resource identity.
- Metrics track payment and subscription statuses (for example, counts of `payment_transaction` by `status`, and subscriptions in `past_due` or `grace` states).  
- Alerts fire when webhook processing fails repeatedly, when Stripe API calls start failing at elevated rates, or when the number of tenants in `grace` or `suspended` billing states exceeds thresholds.  
- During Stripe outages, new purchases and subscription changes fail closed, but existing tenants remain in their last-known-good state until internal policies (for example, maximum grace period) dictate otherwise. Webhook reconciliation may repair Account's committed billing state and outbox, but Account remains the sole producer of `SubscriptionStatusChanged` and `TenantBillingStateChanged`; downstream consumers process those Account-owned events. Failures in either stage remain independently observable so potential entitlement-enforcement drift can be correlated without introducing a second event source.

For current requirements and additional context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Subscription Management Design](./subscription-management.md)
- [Account Service README](./README.md)
