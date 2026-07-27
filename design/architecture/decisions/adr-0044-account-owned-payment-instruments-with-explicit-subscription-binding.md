# ADR-0044: Account-Owned Payment Instruments With Explicit Subscription Binding

## Status

Accepted

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

`CreateSubscription` resolves the authoritative billing owner before its subscription row exists and requires either an explicit saved instrument ID or deterministic selection input that resolves to exactly one saved instrument owned by that owner. Account atomically persists the owner, Stripe customer, and instrument binding before provider creation; Stripe customer and provider defaults are prohibited.

Only the billing-owner subject may view or attach instruments in its account wallet. Possession of `tenantAdmin` alone does not permit a subject to inspect another account's instruments. A `billingAdmin` performing cross-tenant billing administration must use the dedicated audited route rather than inheriting wallet access through tenant authority.

An instrument cannot be detached while any subscription references it. The owner must first select replacement instruments for every referencing subscription.

A billing-owner transfer is an explicit, audited handoff through the dedicated `cross_tenant_billing_safe` route, recording the actor, current owner, new owner, affected subscription, reason, and outcome. It rebinds the subscription to the new owner’s Stripe customer and a payment instrument selected by that owner. Cards and other payment instruments are never transferred between account wallets.

Card management and every new real-money charge complete through HTTPS and the payment-provider flow. Telnet or gameplay may initiate the operation and return a short-lived, single-use checkout URL. The URL is bound to the initiating account and intended operation. FireMUD recognizes payment completion only from a verified provider webhook, then applies the resulting durable entitlement idempotently.

## Consequences

- Payment-instrument ownership and authorization remain global-account concerns, while subscription billing choices remain tenant-subscription concerns.
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
- wallet reads and attachment are limited to the billing-owner subject, `tenantAdmin` alone is denied, and cross-tenant `billingAdmin` operations use the audited route;
- `CreateSubscription` resolves the authoritative billing owner before creating its subscription row, requires an explicit or deterministic saved-instrument selection owned by that owner, persists the owner/customer/instrument binding before provider creation, and does not use provider or customer defaults;
- updating one subscription does not change any other subscription;
- detachment fails until every referencing subscription has a replacement binding;
- billing-owner transfer is an explicit audited handoff through the dedicated `cross_tenant_billing_safe` route, uses the new owner's Stripe customer, and never copies or transfers instruments;
- FireMUD persists provider identifiers and safe display metadata only, never raw card data;
- gameplay-issued checkout URLs expire, are single-use, and are bound to the initiating account and operation; and
- only verified, replay-safe provider webhooks can complete a charge and idempotently grant its entitlement.

## Reversibility and Revisit Triggers

The explicit subscription binding can be migrated to a different provider without changing its authority model. Revisit this decision if FireMUD introduces organizational payment wallets with distinct legal ownership, multiple payment providers whose customer models cannot support this boundary, cash-out or transferable stored value, delegated wallet management beyond the audited `billingAdmin` route, or a provider constraint that makes explicit per-subscription instrument binding impossible.
