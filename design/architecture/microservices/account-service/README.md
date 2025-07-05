# Account Service

## Overview

Manages user accounts and authentication for the platform. Stores profile data and controls session creation and validation.

## Architecture / Design Notes

- Stateless authentication using JWT tokens.
- Passwords are hashed with strong salts and stored only in PostgreSQL.
- Session information is stored in Redis as transient data for quick reconnections.
- Emits account lifecycle events (creation, ban, recovery) for auditing by the Logging & Admin Service.
- Maintains account-to-character relationships so players can own characters across multiple games.
- All tables include a `tenantId` column so the same platform account can join
  multiple games without data leakage. Every query enforces this tenant filter as
  described in the [Multi-Tenancy](../system-architecture-multi-tenancy.md)
  design.
- Provides a JWKS endpoint for other services to validate tokens. Keys are rotated
  via cert-manager as described in the [Security Architecture](../system-architecture-security.md).
- All service-to-service communication is protected by mutual TLS.
- Client authentication is initiated via the `LOGIN` command flow described in
  [Authentication & Authorization](../system-architecture-authentication.md).
  Session tokens stored in Redis allow seamless reconnection by the Game Session
  Service without re-entering credentials.
- Sends notification emails when the Game Session Service reports suspicious
  login activity. See
  [Security Architecture](../system-architecture-security.md#brute-force-defense-and-abuse-handling).
- Non-gameplay workflows such as account creation or billing updates are
  orchestrated using the Saga pattern outlined in
  [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Profiles track optional game history and achievements for each player.
- Password reset and verification flows.
- Banning and subscription tracking.
- External account linking (Google, Discord, Steam) allows unified logins.
- Handles payment processing via **Stripe** for one-time purchases and recurring subscriptions.
- Links accounts to player characters for ownership and permissions.
- gRPC APIs for account creation, authentication, and profile queries.

### Data Model

- `account` table stores username, password hash, email, and status flags.
- `profile` table captures optional user details and preferences.
- `achievement` table records earned achievements keyed by account and game.
- `external_account` table links third-party OAuth IDs to platform accounts.
- `session` keys in Redis map temporary session tokens to account IDs for quick
  reconnects.

### gRPC APIs

- `CreateAccount` – registers a new user and returns an auth token on success.
- `Authenticate` – verifies credentials and issues a session token.
- `GetProfile` – retrieves profile information for the current account.
- `UpdateProfile` – modifies profile fields and triggers notification emails.

## Dependencies

- **Internal:**
  - Logging & Admin Service for audit logging.
  - Game Session Service consumes tokens to create gameplay sessions.
- **External:** PostgreSQL for account data, Redis for transient session data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Deployed via Kubernetes as a horizontally scalable Deployment. Local
  development uses Docker Compose with the same Spring profiles.
- Exposes `/actuator/health` for readiness and liveness probes consumed by the
  cluster.
- Metrics are scraped by Prometheus and logs shipped through Fluent Bit to
  Elasticsearch, with traces captured via OpenTelemetry.
- Configuration differences between environments are described in
  [Deployment Environments](../../infrastructure/deployment-environments.md).

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
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Future Enhancements

- OAuth2 support for social logins.
- Self-service account recovery tools.
- Optional 2FA for elevated roles, as planned in the
  [Security Architecture](../system-architecture-security.md#%F0%9F%94%A1-summary).
