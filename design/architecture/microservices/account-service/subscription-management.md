# Subscription Management Design (Stub)

This document will capture the detailed design for how the Account Service models and manages subscriptions for FireMUD game creators and players.

The goals of the subscription system include:

- Support recurring billing for hosting plans and optional in-game features.
- Handle upgrades, downgrades, and cancellations without data loss.
- Respect tenant isolation while still enabling platform-wide reporting.
- Coordinate with Stripe (or other gateways) while keeping billing state consistent in FireMUD’s own database.

## Planned Topics

The following sections will be fleshed out as the implementation evolves:

- **Plan & Entitlement Model** – How plans, tiers, and limits map to internal tables and Stripe products/prices.
- **Lifecycle Flows** – Creation, trial periods, renewals, grace periods, cancellations, and reactivation.
- **Edge Cases** – Payment failures, partial periods, proration behavior, and how to handle long outages or webhook delays.
- **Multi-Tenancy** – How subscriptions attach to tenants and how cross-tenant creators with multiple games are represented.
- **APIs & Events** – gRPC/REST endpoints, domain events, and sagas that react to subscription changes.

For related context, see:

- [Core Requirements – Monetization](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization)
- [Stripe Integration Design](./stripe-integration.md)
- [Account Service README](./README.md)
