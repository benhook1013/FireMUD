# Account Service

## Overview

Manages user accounts and authentication for the platform. Stores profile data and controls session creation and validation.

## Architecture / Design Notes

- Stateless authentication using JWT tokens.
- Passwords are hashed with strong salts and stored only in PostgreSQL.
- Session information is stored in Redis as transient data for quick reconnections.
- Emits account lifecycle events (creation, ban, recovery) for auditing by the Logging & Admin Service.
- Maintains account-to-character relationships so players can own characters across multiple games.
- Provides a JWKS endpoint for other services to validate tokens. Keys are rotated
  via cert-manager as described in the [Security Architecture](../system-architecture-security.md).
- All service-to-service communication is protected by mutual TLS.
- Non-gameplay workflows such as account creation or billing updates are
  orchestrated using the Saga pattern outlined in
  [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Password reset and verification flows.
- Banning and subscription tracking.
- Handles payment processing via **Stripe** for one-time purchases and recurring subscriptions.
- Links accounts to player characters for ownership and permissions.
- gRPC APIs for account creation, authentication, and profile queries.

### Data Model

- `account` table stores username, password hash, email, and status flags.
- `profile` table captures optional user details and preferences.
- `session` keys in Redis map temporary session tokens to account IDs for quick
  reconnects.

### gRPC APIs

- `CreateAccount` – registers a new user and returns an auth token on success.
- `Authenticate` – verifies credentials and issues a session token.
- `GetProfile` – retrieves profile information for the current account.
- `UpdateProfile` – modifies profile fields and triggers notification emails.

## Dependencies

- **Internal:** Logging & Admin Service for audit logging.
- **External:** PostgreSQL for account data, Redis for transient session data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

The gRPC schemas for this service live in
[../../../../protos/account/v1](../../../../protos/account/v1). Use
`./gradlew generateProto` to regenerate Java stubs when the definitions change.

## 📚 Related Documentation

- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys](../user-journeys.md#9-purchases-and-subscriptions) – payment and subscription workflow.
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Future Enhancements

- OAuth2 support for social logins.
- Self-service account recovery tools.
