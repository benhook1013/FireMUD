# Account Service

## Overview

Manages user accounts and authentication for the platform. Stores profile data and issues tokens so the Game Session Service can validate connections.

### Responsibilities

- Registration and login flows, including password resets
- Issuing JWT tokens consumed by other microservices
- Tracking profiles, achievements, and external account links
- Managing subscription status and bans

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
- Leverages the [Shared Libraries](../system-architecture-shared-libraries.md) for common DTOs, logging interceptors, and Micrometer metrics.

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

### REST Endpoints

| Method | Path       | Description               |
| ------ | ---------- | ------------------------- |
| `POST` | `/accounts` | Create a new user account |
| `GET`  | `/ping`    | Simple health check       |

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

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## 📚 Related Documentation

- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Sign Up and Game Creation](../user-journeys.md#1-sign-up-and-game-creation)
- [User Journeys](../user-journeys.md#9-purchases-and-subscriptions) – payment and subscription workflow.
- [Redis Architecture](../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Additional Details

### Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through **Stripe** as outlined in the [Core Requirements](../../../design/project-management/core-requirements.md#2.8-moderation-administration--monetization). Planned entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers expose operations for creating payment intents and managing subscriptions. The proto definitions live in [`payment_service.proto`](../../../protos/account/v1/payment_service.proto).

### Email & Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables specify SMTP credentials. The gRPC API is defined in [`notification_service.proto`](../../../protos/account/v1/notification_service.proto).

### Session Management

Authentication returns a JWT token which is stored in Redis for quick reconnects. Keys follow `session:{tenantId}:{token}` and expire after the duration configured by `session-expiration-ms` in `AuthProperties`.

### Two-Factor Authentication

Admin and moderator accounts can enable a TOTP secret for additional protection. If a `two_factor_secret` is present on the account row, the `/auth/login` endpoint expects an `otp` field. Codes are validated using the Base32 secret as outlined in the [Security Architecture](../../../design/architecture/system-architecture-security.md).

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /accounts` – create a new account and profile.
- `POST /auth/login` – authenticate and return a JWT token.
- `GET /.well-known/jwks.json` – JWKS for verifying issued JWT tokens.

Example account creation request:

```bash
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"secret"}'
```

Example response:

```json
{
  "id": 123,
  "tenantId": 1,
  "username": "demo",
  "email": "demo@example.com"
}
```

Example login request:

`otp` is only required when two-factor authentication is enabled for the account.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"username":"demo","password":"secret","otp":"123456"}'
```

Example login response:

```json
{
  "status": "SUCCESS",
  "data": {
    "authToken": "<token>"
  }
}
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`account_service.proto`](../../../protos/account/v1/account_service.proto).
- `CreateAccount(CreateAccountRequest) returns (CreateAccountResponse)` – registers a new user.
- `SendNotification(SendNotificationRequest) returns (SendNotificationResponse)` – deliver account notifications asynchronously.

Call the gRPC method with:

```bash
grpcurl -plaintext localhost:6565 account.v1.AccountService/Ping
```

Create an account via gRPC:

```bash
grpcurl -plaintext -d '{"tenant_id":1,"username":"demo","email":"demo@example.com","password":"secret"}' \
  localhost:6565 account.v1.AccountService/CreateAccount
```

Expected response:

```json
{
  "accountId": "123"
}
```

### Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

## Future Enhancements

- OAuth2 support for social logins.
- Self-service account recovery tools.
- Two-factor authentication is now available for admins and moderators using TOTPs.
