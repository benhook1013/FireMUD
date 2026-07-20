# Stripe Integration Design

This document defines the Account Service's Stripe integration for FireMUD's own hosting-plan and platform-subscription billing. [ADR 0143](../../decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md) makes Stripe the sole supported v1 processor and defers creator monetization behind a separate marketplace and settlement decision.

## Product Boundary

V1 supports the billing relationship between FireMUD and the account that pays to host a tenant. The integration must:

- keep Stripe credentials, customer references, payment instruments, provider events, and reconciliation inside the Account Service boundary;
- use provider-hosted or first-party HTTPS billing flows;
- maintain authoritative hosting-subscription state and propagate billing availability and quota changes;
- process provider events idempotently and reconcile internal state with Stripe;
- preserve billing-safe management when tenant gameplay is denied; and
- retain auditable correlation between FireMUD requests, provider resources, provider events, state transitions, and operator recovery.

FireMUD does not build a speculative provider-neutral payment framework. A future provider requires a reviewed integration that defines its provider-specific lifecycle, security, idempotency, reconciliation, migration, and operational behavior.

Player purchases, paid game subscriptions, creator donations, revenue sharing, platform fees on creator transactions, and creator payouts are not v1 product capabilities. Existing generic payment or donation APIs do not widen this boundary.

## Implementation Notes

Current implementation is partial and conflicts with the accepted scope in both directions. Account can create live Stripe PaymentIntents, invoke Stripe refunds, store `payment_transaction` rows, flag a transaction as a donation, compute the configured 5% application fee and remaining creator share, and create local subscription rows. Focused unit tests cover parts of the fee arithmetic and service calls.

The implementation does not demonstrate verified Stripe webhook processing, durable idempotent fulfillment, provider reconciliation, purchase entitlements, creator onboarding, payouts, settlement, reserves, tax handling, chargebacks, negative balances, or complete hosting-subscription enforcement. The local subscription creation path does not itself prove a Stripe subscription lifecycle. Generic PaymentIntent, donation, creator-share, and refund surfaces are partial implementation substrate, not supported marketplace behavior.

## V1 Domain Model

The Account Service owns billing records and maps them to Stripe resources while retaining FireMUD's `accountId` and `tenantId` as internal authority keys:

- `subscription` represents the recurring hosting agreement between FireMUD and the billing-owner account for one tenant. It records the plan, authoritative lifecycle state, provider subscription and customer references, billing periods, selected account-owned payment-instrument reference, and monotonic billing-state version or sequence.
- `billing_customer` maps one global account to its Stripe customer reference. It does not make customer-wide defaults authoritative for an established tenant subscription.
- `payment_instrument` stores only Stripe references and provider-approved display metadata. FireMUD never stores raw card numbers, security codes, or equivalent payment credentials. Each hosting subscription explicitly records its selected instrument.
- `payment_transaction` records charge, refund, and provider evidence needed for hosting billing, audit, and reconciliation. A transaction row is not gameplay access or purchased-product entitlement authority.

Absence of a subscription row is not implicit free hosting. Free or trial hosting is an explicit plan and entitlement state.

## Hosting Subscription Flow

1. An authenticated billing owner selects a hosting plan and payment instrument through an HTTPS account/control-plane flow. Caller-bound routes derive account identity from authentication and accept only the tenant and plan selection they need.
2. Account verifies current billing ownership and tenant membership, creates or reuses the account's Stripe customer, and explicitly binds the selected account-owned instrument to the tenant subscription.
3. Account creates or updates the corresponding Stripe subscription with an idempotency key and persists the pending internal workflow state and provider correlation.
4. Verified Stripe webhook events drive committed subscription transitions. Relevant event families include successful or failed invoices and subscription updates or deletion.
5. An idempotent handler records each provider event, rejects invalid signatures, applies only valid state transitions, advances the tenant's billing version or sequence, and emits the outbox events used by tenant availability and quota enforcement.
6. Reconciliation compares FireMUD's records with Stripe and repairs or escalates missing, delayed, reordered, or conflicting event delivery without silently inventing success.

Duplicate provider delivery must have no additional effect. Out-of-order delivery cannot move the subscription backward to an invalid lifecycle state. A client redirect, PaymentIntent client secret, or provider object observed by another service never substitutes for the verified webhook and reconciliation boundary.

## HTTPS Billing and Gameplay Denial

New charges, saved-instrument changes, subscription changes, refunds, cancellation, and billing-owner transfers complete through HTTPS account/control-plane or provider-hosted flows. Telnet and other gameplay protocols never collect payment credentials.

If a future approved product permits an in-game action to initiate checkout, gameplay may receive only a short-lived, single-use opaque HTTPS URL bound server-side to the authenticated account, gameplay session, tenant, exact action, immutable amount and currency, and request ID. The URL carries no payment credential and cannot change its bound action. Gameplay learns completion only from Account after the supported provider workflow reaches its authoritative state.

A tenant's billing state may deny new instance starts, gameplay admission, or active gameplay according to the subscription policy. That denial must not remove the authorized billing owner's separate billing-safe ability to inspect status, update payment details, resolve failed payment, cancel or transfer billing responsibility, or obtain the allowed tenant billing-safe export. These routes remain tenant scoped, authenticated, audited, and unavailable to ordinary players.

## Billing Ownership and Tenant Isolation

- Each billed tenant has one primary hosting-subscription record and one explicit billing-owner account.
- Account deletion is blocked while that account owns any nonterminal tenant subscription. The owner must cancel terminally or complete an audited ownership transfer first.
- Saved instruments belong to an account, not to a tenant or tenant role. A `tenantAdmin` role alone cannot inspect or mutate another account's instruments.
- Established subscriptions explicitly bind an instrument. Changing a mutable customer-wide default does not silently change another tenant's funding source.
- Detaching an instrument is rejected while a subscription references it unless the same idempotent workflow successfully installs replacements for every affected subscription.
- Billing-owner transfer rebinds the subscription to the new owner's Stripe customer and explicitly selected instrument. Payment instruments never transfer between accounts.
- Tenant-scoped queries filter by the authoritative account and tenant relationship. Cross-tenant billing and support operations use separate restricted APIs with actor, target, reason, and outcome audit.
- Stripe API keys and webhook secrets remain confined to Account. Other services consume Account-owned billing state and events rather than communicating with Stripe directly.

## Refunds, Audit, and Reconciliation

Hosting refunds use the Stripe refund API and an idempotent internal workflow. A successful API response alone is not permission to rewrite every related state optimistically. Account records the provider correlation, follows the authoritative provider lifecycle, updates financial records, applies the hosting policy, and emits auditable state changes. Refunds, disputes, reconciliation repairs, and operator intervention retain request, actor, tenant, provider-resource, provider-event, reason, and outcome correlation.

Operational metrics and alerts cover at minimum:

- Stripe API and webhook verification failures;
- event-processing retries, dead letters, duplicates, age, and ordering conflicts;
- reconciliation lag and unresolved provider/internal drift;
- subscriptions in `past_due`, grace, suspended, or inconsistent states;
- billing-state propagation lag to tenant admission and quota enforcement; and
- failed refunds, cancellation, or billing-owner transfers.

During a Stripe outage, new charges and billing mutations fail closed. Existing tenants remain in their last authoritative state until the explicit grace and cutoff policy changes it; an outage is not treated as either confirmed payment or confirmed cancellation.

## Deferred Marketplace and Entitlement Boundary

Creator monetization requires a later marketplace and settlement decision before implementation is enabled. That decision must define merchant of record, creator identity verification and KYC, sanctions, tax and reporting, supported regions and currencies, provider and platform fees, payout timing, reserves and holds, failed payouts, refunds, disputes, chargebacks, fraud, negative balances, account closure, support ownership, and ledger reconciliation.

Off-platform receipts, creator assertions, payment links, or callbacks from an unintegrated provider cannot create FireMUD-managed entitlements. If marketplace commerce is later accepted:

- a verified supported-provider webhook or reconciliation result must pass through an idempotent fulfillment workflow before an entitlement becomes active;
- raw financial records remain separate from the durable entitlement authority;
- consumers check that entitlement authority rather than a transaction row or client-reported success;
- duplicate fulfillment, missed events, reversal races, and reconciliation repair must be proved; and
- refund handling distinguishes revocable access, unconsumed conserved value, and consumed or transferred value.

Revocable access may be disabled after the refund or dispute reaches the defined authoritative state. Identifiable unconsumed value may be removed without violating conservation. Consumed or transferred value cannot be represented as deleted; the system records the financial/product-state divergence and applies the separately approved debt, restriction, reserve, negative-balance, or support policy.

## Service Surface

The accepted v1 target exposes authenticated hosting-subscription, billing-customer, payment-instrument, billing-history, refund, cancellation, owner-transfer, and billing-safe recovery operations. Tenant caller-bound, cross-tenant support-safe, and cross-tenant billing-safe variants remain separate rather than changing behavior according to optional request fields.

Current generic `CreatePaymentIntent`, `CreateDonation`, and related refund methods are implementation reality, not the canonical public product contract for creator monetization. They must not be exposed or documented as supported player-commerce capabilities unless the later marketplace and settlement decision accepts their semantics and the required proof is complete.

For related requirements and contracts, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Subscription Management Design](./subscription-management.md)
- [Account Service Runtime and Data](./runtime-and-data.md)
- [Account Service README](./README.md)
