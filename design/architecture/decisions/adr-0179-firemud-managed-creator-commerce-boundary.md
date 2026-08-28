# ADR 0179: FireMUD-Managed Creator Commerce Boundary

## Status

Accepted

## Implementation Status

This decision records future direction only and does not enable marketplace commerce. FireMUD V1 remains limited to the hosting-plan and platform-subscription billing boundary in [ADR 0143](./adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md). Creator-player managed transactions, entitlement fulfillment, platform-fee deduction, and creator remittance remain deferred and unsupported until the launch gates in this record are separately satisfied. Creator-party marketplace opt-in and identified operator/scope evidence, creator-balance attribution, and payout-destination binding are also deferred and unimplemented; no marketplace route or payout workflow is implied.

## Decision Record

- Human review status: Completed
- Human review date: 2026-08-25
- Human review disposition: Accepted
- Review source: `COMMERCE-02`
- Decision date: 2026-08-23
- Decision key: `COMMERCE-02`
- Primary capability: `AA-1.4`
- Affected capabilities: `AA-1.5`, `PO-1.3`, `EA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: explicit human product-owner approval on 2026-08-23 and direct human refinement on 2026-08-25

## Context

[ADR 0143](./adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md) remains authoritative for official hosted V1: FireMUD supports its own hosting-plan and platform-subscription billing, while creator-player monetization and settlement are deferred. A future marketplace would need a narrower authority boundary than the existing payment substrate. The tenant's Account-owned Creator Party defined by [ADR 0180](./adr-0180-account-owned-hosted-terms-acceptance-gate.md) is the creator-side contracting party and the party to which creator balances are attributable; FireMUD must not invent a second marketplace creator identity. Creator-party identity, marketplace consent, payment processing, financial evidence, platform entitlements, and creator settlement are distinct concerns, and a payment-success signal alone cannot safely grant or reverse gameplay value.

Existing PaymentIntent, donation, creator-share, currency, refund, and purchase code is unsupported substrate. In particular, existing 5% or creator-share arithmetic is not an accepted fee policy. This ADR records the future direction that may guide a later launch decision without treating that substrate as a supported product path.

## Decision

### Creator Party and No Default Marketplace Rights

Hosting creator content does not transfer creator ownership and does not grant FireMUD default marketplace rights. The tenant's Account-owned Creator Party defined by ADR 0180 is the creator-side contracting party and the party to which creator balances and marketplace attribution are bound; no second marketplace creator identity may be created for the same tenant. Marketplace participation requires a separate explicit opt-in by that exact Creator Party for the identified operator and scope. Acceptance of hosted-content terms never enrolls the party in marketplace commerce. Those marketplace terms must authorize FireMUD to process managed transactions and define the creator's applicable content, commerce, and operational permissions without being inferred from hosting alone.

For an individual, the party acts in its own capacity. For an organization, the opt-in requires a current authorized signer under [ADR 0180](./adr-0180-account-owned-hosted-terms-acceptance-gate.md). A tenant role, organization administration, collaborator access, hosting billing ownership, payer status, or payment activity does not establish marketplace authority and cannot redirect creator earnings by itself. Marketplace eligibility may be narrower than hosting eligibility: individual official hosting may launch without marketplace commerce or organization commerce, while unsupported party forms and organization commerce remain disabled until their applicable implementation, legal, and compliance gates are complete.

### Managed Checkout and Entitlement Authority

FireMUD-managed checkout is the only payment path that may create FireMUD-managed entitlements or gameplay value. When a creator has opted in under the separate marketplace terms, Account must be the financial/provider reconciliation boundary and the authoritative source for the resulting FireMUD entitlement or gameplay-value outcome. Fulfillment and reconciliation must be based on verified supported-provider completion and durable idempotent state, not on a client redirect or an unintegrated provider claim.

External payments, receipts, creator assertions, or claims from an unintegrated provider cannot create FireMUD-managed entitlements or gameplay value. Payment records remain financial/provider evidence; consumers use the separate entitlement authority rather than treating a charge, receipt, or payment row as access authority.

The refund and value classes accepted by [ADR 0143](./adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md) remain unchanged: revocable access may be disabled, unconsumed conserved value may be removed when it can be identified safely, and consumed or transferred value must be handled as an honest financial/product-state divergence under the later settlement policy rather than represented as though it never existed.

### Platform Fee and Creator Balance

If a future creator enables paid FireMUD transactions, the accepted marketplace terms must disclose the platform fee that FireMUD deducts from every FireMUD-managed paid creator-player transaction before remitting the creator balance. Creator balance attribution remains bound to the Creator Party and is distinct from custody of a payout instrument. A payout destination or payment account must be separately verified and bound to that Creator Party; it is never inferred from the hosting subscription's billing owner or payment instrument. The exact fee percentage is unresolved and is not set by this ADR. No existing 5% or creator-share arithmetic is an accepted policy. The later marketplace design must define the fee basis, calculation, disclosure, accounting, and reconciliation before any transaction is enabled.

### Creator-Party Transfer Does Not Transfer Marketplace State

Creator-party transfer under [ADR 0180](./adr-0180-account-owned-hosted-terms-acceptance-gate.md) does not automatically transfer marketplace opt-in, creator balance, payout destination, or a separate marketplace agreement. The target party must pass the applicable marketplace onboarding and acceptance requirements for the identified operator and scope. The later settlement design must define guarded treatment of any balance during and after a transfer; no balance movement or payout authorization is implied by the hosting-party transfer alone.

### Unresolved Launch Gates

This future direction does not enable marketplace commerce. Before launch, a separate accepted marketplace and settlement design must resolve, at minimum:

- merchant-of-record allocation, contractual responsibility, and any actual operator or future-company cutover;
- supported jurisdictions;
- creator onboarding, provider choice, KYC, sanctions, tax, reporting, and related compliance obligations;
- provider and platform fee mechanics, the exact fee percentage and fee basis, creator-balance accounting, payout timing, and currencies;
- payout destinations, reserves, holds, failed payouts, refunds, disputes, chargebacks, fraud, negative balances, and account closure;
- fulfillment, revocation, value reversal, reconciliation, support, audit, and privacy behavior;
- exact marketplace routes, schemas, and ledger design; and
- final legal terms and detailed creator-content, marketplace, and separate-term requirements.

Existing 5% arithmetic and creator-share code remain non-policy substrate. These gates remain explicitly deferred and supplement, but do not supersede or satisfy, the deferred creator-monetization gate in ADR 0143.

## Consequences

- Creator ownership remains independent from FireMUD hosting and any future marketplace opt-in.
- The Account-owned Creator Party defined by ADR 0180 is the creator-side marketplace contracting party and balance-attribution party for the tenant; marketplace eligibility may be narrower than hosting eligibility.
- Hosted-content acceptance, tenant roles, organization administration, collaborators, billing ownership, payer status, and payment activity do not create marketplace opt-in or redirect creator earnings.
- A future marketplace has one clear entitlement-producing payment boundary and cannot rely on arbitrary external receipts or creator assertions.
- Every future FireMUD-managed paid creator-player transaction applies a disclosed platform fee before creator remittance, without implying that a fee rate, payout model, or legal allocation has already been selected.
- Creator balance attribution and separately verified payout-destination custody remain distinct, and creator-party transfer does not automatically transfer either marketplace state.
- Existing payment and creator-share substrate remains unsupported until the launch gates and focused proof are complete.
- Future marketplace work must coordinate financial/provider evidence, Account entitlement authority, creator terms, and gameplay-value conservation without widening V1.

## Alternatives Considered

### Grant Marketplace Rights Through Hosting

Rejected because hosting content alone does not express creator consent to managed commerce, fee deduction, entitlement fulfillment, or settlement responsibilities, and it would blur ownership and contract boundaries.

### Accept External Payments as Entitlement Evidence

Rejected because FireMUD cannot authoritatively verify lifecycle, reversal, identity, replay, or settlement for arbitrary external payments. External evidence therefore cannot create FireMUD-managed value.

### Treat Existing Creator-Share Arithmetic as the Fee Policy

Rejected because implementation substrate is not an approved product or financial policy. The fee percentage and its accounting basis remain unresolved until the later marketplace design.

### Enable the Existing Purchase Surface Before Settlement Policy

Rejected because it would expose unsupported merchant-of-record, onboarding, tax, payout, fraud, dispute, refund, value-reversal, and reconciliation promises while ADR 0143 still defers creator monetization.

## Implementation and Proof Obligations

No implementation may enable this future direction from this ADR alone. Before individual creator commerce launches, the owning design documents must define the exact individual Creator Party and operator/scope binding, separate opted-in creator terms, managed-checkout transaction identity, verified provider completion, durable idempotent fulfillment/reconciliation, Account entitlement authority, external-evidence rejection, disclosed fee and creator-balance accounting, separately verified payout-destination binding, and support/audit evidence. Proof must cover duplicate, delayed, reordered, missing, reversed, refunded, disputed, and conflicting provider events; unconsumed versus consumed or transferred value; the creator opt-in lifecycle; external payment attempts; fee calculation and disclosure; balance remittance; payout-destination changes; and entitlement revocation or value reversal. Organization signer-change and organization party-transfer proof is additionally required only before organization commerce is advertised or enabled. The exact fee, merchant-of-record model, operator cutover, jurisdiction, payout model, legal terms, ledger/routes/schemas, and operational controls require separate accepted decisions before implementation claims are made.

## Reversibility and Revisit Triggers

This future direction may be narrowed, postponed, or replaced without changing official hosted V1. Revisit it when a concrete marketplace proposal has accountable product, architecture, legal, tax, fraud, support, and entitlement owners, or when a jurisdiction, provider, creator model, or gameplay-value requirement changes the unresolved launch gates. Any selected fee rate, merchant-of-record model, payout design, or detailed terms require a new accepted decision or explicitly amended marketplace record.

## Required Documentation Alignment

- [Product requirements](../../product/requirements.md)
- [Architecture authority map](../README.md#contract-authority-map)
- [Stripe integration](../microservices/account-service/stripe-integration.md)
- [Account runtime and data](../microservices/account-service/runtime-and-data.md)
- [Subscription management](../microservices/account-service/subscription-management.md)
- [Multi-tenancy](../system-architecture-multi-tenancy.md)
- [Player journey](../../product/user-journeys/players.md)
- [Creator journey](../../product/user-journeys/creators.md)
- [Licensing guide](../../../LICENSING.md)
- [Player access and session tracker](../../project-management/implementation-tracking/player-access-and-session.md)
