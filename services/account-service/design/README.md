# 🔗 Design Document for Account Service

The design for this service is located here:

[📄 Central Architecture: Account Service Design](../../../design/architecture/microservices/account-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through **Stripe** as outlined in the [Core Requirements](../../../design/project-management/core-requirements.md#2.8-moderation-administration--monetization). Planned entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers will expose operations for creating payment intents and managing subscriptions.
