# ADR-0044: Account-Owned Payment Instruments With Explicit Subscription Binding

## Status

Accepted

## Implementation Status

This decision is partially implemented. Account has payment and subscription persistence plus Stripe/payment-operation foundations, but the complete target path is not yet converged: explicit owner-authorized instrument binding, audited billing-owner handoff, provider creation without customer or provider defaults, replacement-before-detach enforcement, verified webhook entitlement completion, and their end-to-end proof remain incomplete. This status describes implementation coverage only and does not change the human-reviewed decision metadata below.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.4` Payments, subscriptions, and durable purchase entitlements
- Affected capabilities: `AA-1.1`, `AA-1.2`, `PO-1.3`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-PAYMENT-INSTRUMENT`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-AA-PAYMENT-INSTRUMENT`

## Context

FireMUD needs a canonical ownership boundary for payment instruments when one global account can pay for subscriptions in multiple tenants. Tenant roles must not implicitly grant access to another account's instruments, and changing billing for one tenant must not silently affect subscriptions elsewhere. Telnet gameplay also needs a safe bridge to real-money operations without becoming a card-entry or step-up-authentication surface.

## Decision

Each global account has one Stripe customer and one account-owned wallet of payment instruments. FireMUD does not store raw card data.

Every tenant subscription explicitly binds the payment instrument chosen for that subscription. An established subscription never falls back to an account-default instrument. Changing the binding for one subscription affects only that subscription.

Hosting and game entitlements derived from a subscription remain tenant-scoped. Explicit account-scoped purchases, grants, and donations are distinct account-level records: they do not inherit a tenant from the payer or payment instrument and must not receive a fabricated `tenantId`. If an account-scoped grant is applied to a tenant-scoped game feature, that use creates an explicit tenant binding or consumption record at the owning boundary.

Every subscription is identified solely by its immutable internal `subscriptionId` and is permanently scoped to one immutable `tenantId`. `accountId` and `plan_code` are mutable lookup, authorization, and constraint fields governed by explicit operations and row-version checks; they are not composite key or stable-identity material. Only `accountId` may move through the billing-owner handoff. Generic updates and billing-owner transfer must reject any attempted `tenantId` change; FireMUD defines no cross-tenant subscription relocation.

`CreateSubscription` follows one ordered contract:

1. Resolve the authoritative billing owner before the subscription row exists. Require either an explicit saved instrument ID or deterministic selection input that resolves to exactly one saved instrument owned by that owner.
2. In one local database transaction, commit a durable `pending` subscription intent containing the owner, a separate customer-provisioning operation reference, explicit instrument binding, the observed instrument version reservation, a stable creation-operation provider idempotency identity, a distinct cleanup/cancellation-operation provider idempotency identity, and an outbox work item. This transaction performs only local reservation/CAS work, ends before any Stripe call, and never spans remote validation or mutation.
3. Confirm or reconcile the separate customer-provisioning operation and attach the confirmed Stripe customer reference to the intent. A worker may then claim the intent as `provisioning` in a separate local CAS transaction only after revalidating the instrument owner, exact reserved version, persisted Stripe customer reference, and reservation state.
4. After the claim commits, separately ask Stripe to validate the persisted customer/payment-method relationship and attachable state before making the provider mutation. Neither remote validation nor provider mutation is held inside or treated as part of a database transaction. Creation retries, timeouts, and ambiguous results reconcile the same creation-operation identity rather than creating a replacement subscription.
5. Detach and provisioning share the instrument reservation/version boundary. A pending or provisioning reservation blocks detach. If detach commits before the reservation or claim, it advances the instrument version and the stale reservation or claim fails before any Stripe call. Once a reservation or provisioning claim owns the boundary, detach waits.
6. A permanent provider or local failure, intent expiry, or explicit abandonment enters one idempotent terminal cleanup for the immutable subscription operation. Before local cancellation or reservation release, cleanup uses the distinct cleanup/cancellation-operation identity for its idempotent cancellation/end attempt while retaining the creation-operation identity to locate and reconcile any provider subscription object attributable to the create. Cleanup retries reuse the same cleanup/cancellation identity; an ambiguous provider result remains nonterminal for retry.
7. Only after provider absence or terminal cancellation is durably recorded may cleanup lock the subscription and instrument, validate the exact owner, instrument binding, reserved instrument version, reservation identity, and row version, then atomically mark the subscription `canceled` with the durable failure, expiry, or abandonment outcome, advance the instrument version exactly once, release the reservation, and commit the outbox boundary. An exact retry returns that committed terminal result. A stale or competing detach or claim cannot clear another reservation and must fail closed or reconcile under the same lock. No terminal failure, expiry, or abandonment response, and no detach that relies on the released instrument, is returned before cleanup commits.
8. The subscription cannot become `trialing` or `active`, authorize hosting entitlement, or be billed until Account has recorded a confirmed provider subscription ID. Stripe customer defaults and provider defaults are prohibited.

Only the billing-owner subject may view or attach instruments in its account wallet. Possession of `tenantAdmin` alone does not permit a subject to inspect another account's instruments. A `billingAdmin` performing cross-tenant billing administration must use the dedicated audited route rather than inheriting wallet access through tenant authority.

For a billing-owner handoff, the new billing owner must independently authenticate, select, and authorize an instrument from that owner's wallet. The audited `billingAdmin` route may orchestrate the handoff using only the opaque instrument ID and the new owner's authorization result; `billingAdmin` never lists, views, selects, attaches, or authorizes instruments in either wallet.

An instrument cannot be detached while any active or potentially chargeable subscription references it. Terminal canceled-subscription cleanup retires that subscription's instrument binding as non-chargeable and releases its reservation; that retired binding no longer blocks detach. The owner must first select replacement instruments for every remaining chargeable reference.

A billing-owner transfer is an explicit, audited handoff through the dedicated `cross_tenant_billing_safe` route, recording the actor, current owner, new owner, affected subscription, reason, effective boundary, and outcome. The new owner selects and authorizes a payment instrument from that owner's wallet; the route moves future billing to the new owner’s Stripe customer without exposing that wallet to the orchestrating `billingAdmin`. Cards and other payment instruments are never transferred between account wallets.

The transfer is one durable, idempotent state machine keyed by an immutable transfer request ID. Account first validates both owners, the new owner's confirmed customer, the new owner's authorization of the opaque selected instrument ID, the selected instrument's ownership and customer binding, the current subscription row version, the existing paid-through boundary, and the absence of another nonterminal transfer. It then commits a `pending` transfer intent with the old binding, proposed binding, expected row version, effective boundary, distinct old-cancellation and replacement-creation provider identities, and outbox work before any provider mutation. Provider retries reuse those identities.

Stripe subscriptions cannot change Customer in place, so the Stripe adapter performs a coordinated replacement rather than pretending to rebind the existing provider object. It creates or reconciles one replacement subscription for the new Customer, scheduled to begin billing at the existing subscription's paid-through boundary, and schedules the old subscription to end at that same boundary. The old Account binding remains authoritative until verified provider state proves the replacement exists with the intended plan, customer, instrument, and boundary and the old subscription is scheduled to terminate there. At cutover, Account atomically switches the authoritative owner/customer/instrument/provider-subscription binding under the expected row version. Exactly one provider subscription may authorize hosting entitlement at a time.

Timeouts or ambiguous provider results remain nonterminal and are reconciled using the same two provider-operation identities; they never authorize another replacement. A definitive replacement failure leaves the old binding authoritative and cancels any uncommitted transfer intent. If replacement creation succeeds but old cancellation scheduling or the local cutover cannot be confirmed, the workflow remains blocked and operator-visible while reconciliation converges or performs an idempotent cleanup of the not-yet-authoritative replacement. It must not expose overlapping entitlement, silently strand two billable subscriptions, or claim transfer completion. Immediate mid-period cross-customer transfer, cross-customer credits, and proration are unsupported; an owner may separately choose terminal cancellation and a new subscription when an immediate boundary is required. Account deletion remains blocked until the scheduled transfer has cut over or the old subscription is terminally canceled.

Card management and every new real-money charge complete through HTTPS and the payment-provider flow. Adopting [ADR 0045](./adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md)'s gameplay-to-HTTPS handoff, Telnet or gameplay may initiate the operation and return a short-lived, single-use, opaque checkout URL whose server-side handoff state is bound to the exact initiating account, gameplay session, action, product, amount, currency, and `requestId`. A tenant-scoped purchase binds exactly one validated target `tenantId`; its handoff, authenticated checkout request, and completion proof must carry that same tenant. An account-scoped purchase, grant, or donation persists and transmits no `tenantId` at all; its handoff, checkout request, and completion proof must require tenant absence and reject any supplied tenant. The URL contains no authority or mutable commercial parameters; replay, expiry, or parameter substitution cannot change that tuple. FireMUD recognizes payment completion only from a verified provider webhook, then applies the resulting durable entitlement or account-scoped grant idempotently. Fulfillment uniqueness is the immutable purchase-operation ID plus product grant key; the Stripe event ID is audit and evidence only.

## Consequences

- Payment-instrument ownership and authorization remain global-account concerns, while subscription billing choices remain tenant-subscription concerns.
- Account-scoped purchases, grants, and donations remain global account records; tenant-scoped hosting/game use is established only through an explicit binding or consumption record.
- Shared account defaults cannot unexpectedly move established subscriptions to another card.
- Owners must perform explicit replacement work before detaching an instrument or accepting a billing-ownership transfer.
- A single card may fund several tenant subscriptions without making those tenants co-owners of the card.
- Telnet clients can begin sensitive purchases without collecting card details or authentication factors in the gameplay protocol.
- Checkout initiation and webhook completion require durable correlation, expiry, single-use enforcement, signature verification, replay protection, and idempotent entitlement application.

## Alternatives Considered

### Tenant-owned payment instruments

Rejected because tenant roles would become entangled with card ownership and the same payer would need duplicated wallets across tenants.

### Account-default fallback for subscriptions

Rejected because changing an account default could silently redirect charges for unrelated tenant subscriptions.

### Transfer payment instruments with billing ownership

Rejected because payment instruments belong to the original Stripe customer and must not be exposed or copied to another account.

### Collect payment details or step-up factors over Telnet

Rejected because the gameplay protocol is not the payment-provider or sensitive account-management surface. A bounded HTTPS handoff preserves normal Telnet usage while keeping those operations in the appropriate control plane.

## Implementation and Proof Obligations

Focused contract and integration proof must demonstrate that:

- subscriptions persist an explicit payment-instrument binding and established subscriptions cannot use an account-default fallback;
- hosting/game entitlements derived from subscriptions carry the correct tenant scope, while account-scoped purchases, grants, and donations remain account-scoped without a fabricated `tenantId`;
- wallet reads and attachment are limited to the billing-owner subject, the new billing owner must select and authorize an instrument from that owner's wallet, `tenantAdmin` alone is denied, and cross-tenant `billingAdmin` operations only orchestrate an opaque instrument ID through the audited route without wallet access;
- `CreateSubscription` resolves the authoritative billing owner before creating its subscription row, commits one durable `pending` intent and outbox item with an explicit instrument/version reservation, a stable creation-operation provider idempotency identity, and a distinct cleanup/cancellation-operation identity before provider creation, atomically revalidates ownership, version, and attachability at worker claim, defines the detach-vs-provision race, retains the creation identity for provider-object lookup, and uses the cleanup identity for idempotent cancellation/end retries before local terminal cancellation and reservation release. It performs idempotent terminal cleanup for permanent failure, expiry, or abandonment, blocks detach and terminal responses until that cleanup commits, reconciles ambiguous provider outcomes without duplicate creation, records a confirmed provider subscription ID before activation or billing, and does not use provider or customer defaults;
- create and update are separate APIs with mutually exclusive request fields and idempotency identities; `UpdateSubscription` requires the immutable target `subscriptionId`, an update-specific request ID, and expected row version and rejects creation-only fields or any `tenantId` change;
- updating one subscription does not change any other subscription;
- detachment fails until every active or potentially chargeable referencing subscription has a replacement binding; terminal canceled-subscription cleanup retires its non-chargeable binding before it ceases to block detach;
- billing-owner transfer is an explicit audited, row-version-guarded replacement state machine through the dedicated `cross_tenant_billing_safe` route, preserves the immutable tenant binding, schedules old termination and new-owner billing at one paid-through boundary, reconciles partial or ambiguous provider outcomes without duplicate billing or entitlement, and never copies or transfers instruments;
- FireMUD persists provider identifiers and safe display metadata only, never raw card data;
- gameplay-issued checkout URLs use the ADR 0045 HTTPS handoff, expire, are single-use, and prove the exact account, session, action, product, amount, currency, and `requestId`; tenant presence is exact and required only for tenant-scoped purchases, while account-scoped actions prove `tenantId` is absent in handoff state, checkout disclosure/request, and completion binding; and
- only verified, replay-safe provider webhooks can complete a charge and idempotently grant its entitlement using the immutable purchase-operation ID plus product grant key, with Stripe event ID retained only as audit/evidence.

## Reversibility and Revisit Triggers

The explicit subscription binding can be migrated to a different provider without changing its authority model. Revisit this decision if FireMUD introduces organizational payment wallets with distinct legal ownership, multiple payment providers whose customer models cannot support this boundary, cash-out or transferable stored value, delegated wallet management beyond the audited `billingAdmin` route, or a provider constraint that makes explicit per-subscription instrument binding impossible.
