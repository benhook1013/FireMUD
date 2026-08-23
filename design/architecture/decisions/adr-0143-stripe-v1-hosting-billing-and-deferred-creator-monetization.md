# ADR 0143: Stripe V1 Hosting Billing and Deferred Creator Monetization

## Status

Accepted

## Implementation Status

This decision is not implemented. Stripe hosting-billing lifecycle, verified webhooks, reconciliation, entitlement authority, billing-safe availability, and focused provider proof remain gaps; creator monetization remains deferred.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `COMMERCE-01`
- Decision date: 2026-07-20
- Decision key: `COMMERCE-01`
- Primary capability: `AA-1.4`
- Affected capabilities: `AA-1.5`, `PO-1.3`, `EA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of payment-provider scope, hosting billing, creator monetization, entitlements, refunds, settlement, and billing-safe availability

## Context

FireMUD needs payment processing for its own hosted service. Stripe is already named throughout the design, and Account contains partial Stripe-facing payment, refund, and subscription foundations. Introducing a speculative provider-neutral layer before a second provider is required would add abstractions and testing obligations without proving that the providers share useful semantics.

Earlier product text also treated player purchases, paid game subscriptions, creator donations, a platform fee, revenue sharing, and entitlement reversal as one near-term capability. That is not just another use of the hosting-billing integration. It introduces marketplace and settlement responsibilities such as merchant-of-record choice, creator identity verification, tax handling, provider and platform fees, payouts, reserves, refunds, chargebacks, and negative balances. The product has not yet made those decisions.

Raw payment success is not sufficient entitlement authority. Provider callbacks can be duplicated, delayed, reordered, reversed, or absent, while consumed or transferred gameplay value cannot always be honestly erased after a refund. Off-platform receipts or creator assertions are even weaker evidence and cannot safely create FireMUD-managed access.

## Decision

### Stripe Is the Sole Supported V1 Provider

Stripe is the sole supported processor for FireMUD's own hosting-plan billing in v1. Account owns the integration, provider identifiers, billing state, audit correlation, and reconciliation boundary. FireMUD does not build a speculative multi-provider abstraction.

A future provider is possible, but it requires a reviewed integration that defines its lifecycle, idempotency, security, reconciliation, operational, and migration behavior. It is not enabled by swapping an implementation behind a prematurely generic interface.

### V1 Commerce Is FireMUD Hosting Billing

The accepted v1 product scope is the billing relationship between FireMUD and the account that pays to host a tenant. Hosting plans may govern tenant availability and resource entitlements. New charges, subscription changes, payment-instrument management, refunds, and billing-owner changes complete through HTTPS account/control-plane and provider-hosted flows.

Billing-safe management remains reachable when tenant gameplay is denied for billing reasons. Gameplay denial must not prevent an authorized billing owner from inspecting status, updating payment details, resolving a failed payment, exporting billing-safe tenant data, or canceling or transferring billing ownership. These routes remain account and tenant authorized, audited, and isolated from gameplay authority.

Hosting billing follows the generic Account handoff authorization-linearization contract in [ADR 0045](./adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) and the hosting-specific Stripe provider and reconciliation contract in [Stripe Integration Design](../microservices/account-service/stripe-integration.md); a provider result alone never authorizes an active hosting entitlement or billing outcome.

### Creator Monetization Is Deferred

The following are not v1 product commitments:

- player purchases that grant gameplay value;
- player-paid game subscriptions;
- creator “donations” or tips;
- revenue sharing and platform fees on creator transactions;
- creator payouts or settlement.

Existing code or APIs for generic PaymentIntents, donation flags, creator-share arithmetic, or refunds are partial implementation substrate, not evidence that these features are supported.

Before any creator-monetization path is enabled, a separate marketplace and settlement decision must define at minimum:

- merchant of record and contractual responsibility;
- creator onboarding, identity verification, KYC, sanctions, tax, and reporting obligations;
- provider charges and the exact platform-fee policy;
- payout scheduling, supported currencies, reserves, holds, and failed payouts;
- refunds, disputes, chargebacks, fraud, negative balances, and account closure;
- entitlement ownership, fulfillment, revocation, reconciliation, support, and audit behavior.

Creator-directed external payment links may exist outside FireMUD's managed commerce boundary only if later product policy permits them. Off-platform payment evidence, receipts, webhook claims from an unintegrated system, or creator assertions never create FireMUD entitlements.

### Future Entitlements Require Verified Provider Completion

If marketplace commerce is later accepted, a payment attempt or client redirect does not grant access. FireMUD may create or activate an entitlement only after Account has verified a supported provider webhook or reconciliation result and applied the provider event through an idempotent fulfillment workflow. Duplicate delivery must have no additional effect, and missed or conflicting events must be recoverable through provider reconciliation.

Payment records remain financial and provider evidence. Durable FireMUD entitlements remain separate state with explicit provenance and lifecycle. Consumers use the entitlement authority rather than treating a PaymentIntent, charge row, receipt, or gameplay callback as proof of access.

### Future Refunds Reflect What Can Actually Be Reversed

A future marketplace design must classify granted value rather than promise blanket deletion after every refund:

- **Revocable access** may be disabled or revoked once the refund or dispute reaches the defined authoritative state.
- **Unconsumed conserved value** may be removed when the exact remaining grant can be identified without creating a negative or duplicated balance.
- **Consumed or transferred value** cannot be represented as though it never existed. The workflow records the financial reversal and resulting product-state divergence, then applies the separately approved debt, restriction, reserve, negative-balance, or support policy.

The marketplace decision must define partial consumption, transfers to another player, chargebacks after payout, concurrent spending during refund processing, and idempotent retries before any such feature launches.

## Consequences

- V1 gains one concrete, testable hosting-billing integration instead of an unproven payment-provider framework.
- Supporting another provider later costs a deliberate integration and possibly migration work.
- FireMUD does not promise creator earnings, player purchases, tips, or platform-fee revenue in the current product scope.
- Existing generic payment and donation code may need removal, isolation, or later redesign; its presence does not widen the accepted product.
- Billing suspension can deny gameplay while preserving the creator's authorized recovery and account-management path.
- A future marketplace will require materially more policy, ledger, provider, reconciliation, fraud, tax, and support work than ordinary hosting subscriptions.
- Future refund behavior remains honest for irrevocably consumed or transferred value instead of fabricating a successful rollback.

## Alternatives Considered

### Build a Provider-Neutral Payment Layer Now

This could reduce some future call-site change, but provider customer, subscription, webhook, dispute, settlement, and migration semantics are not interchangeable. Without a selected second provider, the abstraction would hide rather than resolve those differences and multiply proof obligations.

### Launch Creator Monetization on the Existing PaymentIntent Surface

The current surface demonstrates isolated payment operations but does not prove merchant-of-record policy, verified webhook fulfillment, entitlements, payouts, reserves, chargebacks, negative balances, or reconciliation. Exposing it as a supported marketplace would make financial and gameplay promises the system cannot yet keep.

### Accept External Receipts as Entitlement Evidence

This would let creators use arbitrary payment systems, but FireMUD could not authoritatively verify payment lifecycle, reversal, identity, replay, or settlement. External evidence therefore cannot create platform-managed access.

### Revoke Every Purchased Effect After Every Refund

This is simple only for unused access. It becomes false or destructive once currency is spent, an item is consumed, or value is transferred. Explicit value classes and divergence handling are required instead.

## Implementation and Proof Obligations

Current implementation is materially partial and broader than the accepted v1 product scope. Account can create Stripe PaymentIntents, invoke Stripe refunds, store `payment_transaction` rows, mark donation intent, and compute the configured 5% application fee and remaining creator share. Tests cover parts of that arithmetic and service invocation. Subscription rows and APIs also exist. These generic provider-mutating paths are implementation drift: unsupported generic PaymentIntent, refund, donation, and creator-share methods must reject before any provider mutation; only typed hosting-billing flows may proceed.

There is no demonstrated verified Stripe webhook lifecycle, durable idempotent fulfillment, `purchase_entitlement` authority, provider reconciliation, creator onboarding, payout ledger, reserve, settlement, tax, chargeback, negative-balance, or end-to-end hosting-subscription enforcement proof. The current subscription creation path also records a locally active row rather than proving the provider lifecycle described by the target design. Generic PaymentIntent, donation, creator-share, and refund surfaces must therefore be treated as incomplete and unsupported outside the accepted hosting-billing path. Their current generic RPC substrate is not proven exact-method-authorized; until it is removed or converged, the Account-local containment boundary in [Stripe Integration Design](../microservices/account-service/stripe-integration.md) applies and no such surface may create an entitlement. Any legacy operation already in flight is reconciled or rejected under its existing durable/provider identity and must never issue a replacement provider mutation or create a replacement provider identity.

V1 implementation must prove Stripe-hosted HTTPS checkout or management, authenticated billing-owner scope, provider-customer and payment-instrument safety, idempotent webhook and reconciliation processing for hosting subscription state, tenant availability propagation, duplicate and out-of-order events, provider outage behavior, refunds, deletion and billing-owner preconditions, billing-safe access during gameplay denial, audit correlation, metrics, alerts, and recovery from reconciliation drift.

No creator-monetization capability may be called complete merely because a PaymentIntent or refund succeeds. Its later proof must include the separately accepted marketplace and settlement contract plus entitlement and value-conservation cases.

## Reversibility and Revisit Triggers

Stripe-specific implementation details may evolve without changing this decision. Revisit provider scope when a concrete second provider, jurisdiction, resilience requirement, or migration need justifies the integration cost. Revisit creator monetization only with a concrete product proposal and owners for marketplace, settlement, legal, tax, fraud, support, and entitlement behavior.

## Required Documentation Alignment

- [Product requirements](../../product/requirements.md)
- [Player user journeys](../../product/user-journeys/players.md)
- [Account Stripe integration](../microservices/account-service/stripe-integration.md)
- [Account runtime and data](../microservices/account-service/runtime-and-data.md)
