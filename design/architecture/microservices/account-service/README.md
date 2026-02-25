# Account Service

## Overview

Manages user accounts and authentication for the platform. It stores profile data and is the sole service that creates and signs JWTs. These tokens authorize access to meta/control services. The Game Session Service relies on Redis session context for gameplay and may request an updated token when a player's roles change. REST endpoints are documented in `openapi.yaml` within the service resources directory.
Public login APIs exist for administrators and account portals, but gameplay clients reach them indirectly through the Game Session Service rather than calling the Gateway directly.

### Responsibilities

- Registration and login flows, including password resets
- Issuing short-lived JWT tokens for internal meta/control APIs, including:
  - Browser JWTs for first-party admin/creator web UIs via `/auth/login`, and
  - Service JWTs for backend gRPC callers via internal authentication flows
- Tracking profiles, OAuth2 social logins, external account links, and achievements.
- Managing subscription status and ban enforcement.
- Self-service account recovery for compromised or lost credentials.
- Optional two-factor authentication for admin and moderator roles.

## Architecture / Design Notes

- Stateless authentication uses short-lived JWT tokens for internal meta/control APIs. Two token profiles are issued:
  - **Browser JWTs** for first-party admin/creator web UIs via `/auth/login`, with a frontend-oriented audience and short lifetime, and
  - **Service JWTs** for backend services via internal authentication flows (for example, the `Authenticate` gRPC method), with an internal audience.
  Gameplay protocol clients (Telnet and WebSocket) never see or transmit these tokens.
- The service hashes raw passwords with a strong algorithm such as Argon2 and unique salts before storing them in PostgreSQL.
- Auth token allowlist entries are stored in Redis as described in [Authentication & Authorization](../../system-architecture-authentication.md); gameplay session bindings are owned by the Game Session Service and are not managed directly here.
- Creation events are logged to the Logging & Admin Service via a saga step.
- Ban and recovery events are logged to the Logging & Admin Service for auditability.
- Account-to-character relationships allow players to own characters across multiple games.
- The core `account` record represents a **global platform account** identified by `accountId`. Tables that represent per-game state or billing attach to tenants via a `tenantId` column so the same platform account can join multiple games without data leakage. Every tenant-scoped query enforces this filter as described in the [Multi-Tenancy](../../system-architecture-multi-tenancy.md) design.
- Provides a JWKS endpoint for other services to validate tokens. JWT signing keys and JWKS documents are rotated by dedicated Kubernetes Jobs (cert-manager rotates TLS certificates), as described in the [Security Architecture](../../system-architecture-security.md).
- All service-to-service communication is protected by mutual TLS.
- Gameplay client authentication is initiated via the `LOGIN` command flow described in
  [Authentication & Authorization](../../system-architecture-authentication.md); gameplay clients never see JWTs. The Game Session Service calls the internal `Authenticate` gRPC method (mTLS-protected) to verify credentials and obtain Service JWTs, while `/auth/login` remains a browser/control-plane endpoint.
- Admin and creator UI authentication is initiated via the `/auth/login` HTTP endpoint, which issues Browser JWTs used for control-plane APIs as described in the Authentication & Authorization and Frontend architecture designs.
- Owns brute-force defense and login abuse handling for the platform. The service monitors login attempts per account and per IP, applies throttling and temporary blacklisting policies, and emits structured signals (for example, `AUTH_ACCOUNT_LOCKED`) that the Game Session Service and other consumers honor when binding gameplay sessions. Suspicious activity triggers notification emails and audit events as described in
  [Security Architecture](../../system-architecture-security.md#brute-force-defense-and-abuse-handling).
- Non-gameplay workflows such as account creation or billing updates are
  orchestrated using the Saga pattern outlined in
  [Transaction Strategies](../../system-architecture-transactions.md).
- Leverages the [Shared Libraries](../../system-architecture-shared-libraries.md) for common DTOs, logging interceptors, and Micrometer metrics.

### Redis Role and Prefixes

- **Coordination Redis**
  - The Account Service does **not** participate in tick or gameplay coordination and never touches `tick:*`, `timer:*`, `retry:*`, or other tick-related prefixes on Coordination Redis; those responsibilities remain with the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md).
  - It does, however, use auth/session keys as documented in [Authentication](../../system-architecture-authentication.md):
    - `session:auth:account:<accountId>:<tokenHash>` – baseline allowlist entry for a JWT, consulted for control-plane session validity and immediate revocation.
    - `session:auth:tenant:<tenantId>:<tokenHash>` – tenant-scoped Account/JWT bindings for internal auth, consulted when authorizing tenant-specific operations.
    - `session:auth:global:<accountId>:<tokenHash>` – cross-tenant Account/JWT bindings for internal auth, consulted when authorizing cross-tenant or platform-wide operations based on `globalRoles`.
  - These keys live on Coordination Redis so that auth/session bindings share the same AOF and reset semantics as gameplay sessions. They are short-lived but **reset-sensitive**: coordination resets that drop `session:auth:*` force re-authentication and token re-issuance for affected account, tenant, and global scopes.
- **Cache/Rate-Limit Redis**
  - The Account Service does not maintain its own Cache/Rate-Limit Redis prefixes today; any future caches for account or profile lookups must use Cache/Rate-Limit Redis and the key naming/TTL/versioning rules in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md), not Coordination Redis.
  - When introducing new Redis usage here, follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so auth/session keys, roles, and observability remain consistent with the global design.

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Profiles store a display name, bio, game history, and achievements.
- Password reset and verification flows.
- Subscription tracking with ban management.
- External account linking (Google, Discord, Steam) allows unified logins.
- Handles payment processing via **Stripe** for one-time purchases and recurring subscriptions.
- Link accounts to player characters for ownership and permissions.
- gRPC APIs expose account management, external account linking, and payment operations.

### Data Model

- `account` table stores username, password hash, email, and status flags for the global platform account.
- `profile` table captures optional user details and preferences.
- An `achievement` table records earned achievements keyed by account and game.
- `external_account` table links third-party OAuth IDs to platform accounts.
- Tenant- and billing-related tables such as `subscription` and `payment_transaction` associate `accountId` with `tenantId` for hosted games and hosting plans.

External accounts allow players to log in via Google, Discord, or Steam. Each
link stores the provider name and external ID so the platform account can be
resolved during authentication.

### gRPC APIs

- `CreateAccount` – registers a new user and returns its `accountId` so internal services can establish their own sessions using the authentication flows described below.
- `Authenticate` – verifies credentials and issues a Service JWT (internal token profile) backed by `session:auth:*` allowlist entries for meta/control APIs.
- `GetProfile` – retrieves profile information for the current account.
- `UpdateProfile` – modifies profile fields and triggers notification emails.
- `ExportAccount` – exports all account and profile data.
- `DeleteAccount` – permanently removes an account.
- `RequestPasswordReset` – initiate a password reset email.
- `CompletePasswordReset` – update the password using a token.
- `LinkExternalAccount` – attach a Google, Discord, or Steam ID.
- `GetTenantMembership` – return authoritative account-tenant membership and roles for billing-safe mutation checks.
- `RequestEmailVerification` – send a verification email.
- `VerifyEmail` – confirm an email verification token.
- `CreatePaymentIntent` – initiate a Stripe payment.
- `CreateSubscription` – start a recurring subscription.
- `CreateDonation` – process a donation payment.
- `RefundPayment` – issue a refund for a payment.
- `GetBalance` – retrieve a virtual currency balance.
- `AddCurrency` – increase virtual currency for an account.
- `SpendCurrency` – deduct virtual currency from an account.

### REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Simple health check |
| `POST` | `/auth/login` | Authenticate and establish a control-plane session for first-party UIs by returning a Browser JWT; the token is allowlisted server-side via `session:auth:*` entries for revocation |
| `POST` | `/auth/logout` | Revoke the currently presented control-plane token (`session:auth:*:<tokenHash>` delete for that token) |
| `POST` | `/auth/logout-all` | Revoke all active control-plane tokens for the authenticated account by advancing `session:auth:revoked_after:account:<accountId>` |
| `POST` | `/auth/request-password-reset` | Request password reset |
| `POST` | `/auth/complete-password-reset` | Complete password reset |
| `POST` | `/auth/request-email-verification` | Send verification email |
| `POST` | `/auth/verify-email` | Verify email token |
| `POST` | `/auth/recover-username` | Send username reminder |
| `POST` | `/accounts` | Create a new user account |
| `GET` | `/accounts/{accountId}/export` | Export account data |
| `DELETE` | `/accounts/{accountId}` | Delete an account |
| `POST` | `/accounts/{accountId}/external` | Link external account |
| `GET` | `/profiles/{accountId}` | Retrieve profile information |
| `PUT` | `/profiles/{accountId}` | Update profile information |
| `GET` | `/tenants/{tenantId}/memberships/{accountId}` | Authoritative live membership and roles for billing-safe mutation guards |
| `GET` | `/.well-known/jwks.json` | JWKS for verifying issued JWTs |

## Dependencies

- **Internal:**
  - Logging & Admin Service for audit logging.
  - Game Session Service consumes tokens to create gameplay sessions.
- **External:** PostgreSQL for account data, Redis for transient session data.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service follows the standard scheme described in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).
JWT signing key material is configured with `FIREMUD_AUTH_JWT_SECRET` or `FIREMUD_AUTH_JWT_SECRET_PATH`; player-facing environments must use `FIREMUD_AUTH_JWT_SECRET_PATH` mounted from Kubernetes Secrets. Service verification must follow the asymmetric JWKS model from [Authentication & Authorization](../../system-architecture-authentication.md#jwt-verification-model-normative). Server-side session TTL is derived from JWT lifetime using `FIREMUD_AUTH_JWT_EXPIRATION_MS` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`.

Additional variables configure outbound email delivery:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SMTP_HOST` | SMTP server hostname | `localhost` |
| `SMTP_PORT` | SMTP server port | `1025` |
| `SMTP_USERNAME` | Username for SMTP auth | *(empty)* |
| `SMTP_PASSWORD` | Password for SMTP auth | *(empty)* |
| `SMTP_FROM` | From address for transactional emails | `no-reply@firemud.local` |
| `FIREMUD_MAIL_VERIFICATION_URL` | Public URL for email verification links | `http://localhost:8080/auth/verify-email?token=%s` |
| `FIREMUD_MAIL_RESET_URL` | Public URL for password reset links | `http://localhost:8080/reset-password?token=%s` |
| `FIREMUD_PAYMENT_STRIPE_API_KEY` | Stripe API key used for payments | *(none)* |
| `FIREMUD_PAYMENT_PLATFORM_FEE_PERCENT` | Platform fee percentage applied to transactions | `0` |
| `FIREMUD_AUTH_JWT_SECRET` | Inline JWT signing key material for local/dev or explicitly ephemeral stacks only (legacy compatibility; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Path to a file containing JWT signing key material (required for player-facing environments; mounted from `jwt-signing-keys`) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Extra time added to the JWT lifetime when deriving server-side session TTL | `300000` |
| `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` | Controls whether plaintext Telnet logins are restricted to 2FA-enabled, explicitly opted-in accounts (see Environment & Secrets – Authentication) | `true` |

## Proto Files

The gRPC schemas for this service live in
[../../../../protos/account/v1](../../../../protos/account/v1). Use
`./gradlew generateProto` to regenerate Java stubs when the definitions change.

## Related Documentation

- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Sign Up](../../user-journeys-players.md#1-sign-up)
- [User Journeys – Purchases and Subscriptions](../../user-journeys-players.md#5-purchases-and-subscriptions)
- [User Journeys – Account Data Export & Deletion](../../user-journeys-players.md#8-account-data-export--deletion)
- [Redis Architecture](../../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

## Additional Details

### Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through **Stripe** as outlined in the [Core Requirements](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization). Entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers expose operations for creating payment intents and managing subscriptions. The proto definitions live in [`payment_service.proto`](../../../../protos/account/v1/payment_service.proto).
Donations are stored as one-time `payment_transaction` records with the `donation` flag set to `true`. A dedicated `CreateDonation` gRPC method issues a Stripe payment intent for these cases. Refunds call Stripe's API and update the `payment_transaction` `status` to `refunded`, enabling chargeback handling workflows. More detailed designs for payments and recurring subscriptions will live in the dedicated [Stripe Integration Design](./stripe-integration.md) and [Subscription Management Design](./subscription-management.md) documents as the implementation matures.

### Virtual Currency & Revenue Sharing

Each tenant may define game-specific currencies. Balances are stored in the `currency_balance` table keyed by account. The `VirtualCurrencyService` gRPC API allows services to add or spend currency for an account. Platform fees are deducted from each purchase and the remaining `creator_share_cents` is recorded on the `payment_transaction` row for revenue-sharing calculations.

### Premium Hosting

Premium hosting tiers are modeled as subscription plans with higher resource limits. Game creators can upgrade via the existing payment flows.

### Email & Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables configure `spring.mail.*` along with `firemud.mail.from`, `firemud.mail.verification-url`, and `firemud.mail.reset-url`. The gRPC API is defined in [`notification_service.proto`](../../../../protos/account/v1/notification_service.proto).

### Session Management

Authentication generates a JWT and records server-side allowlist entries in Redis for immediate revocation, following the scope model defined in [Authentication & Authorization](../../system-architecture-authentication.md):

- Account-scoped entries: `session:auth:account:<accountId>:<tokenHash>` for every issued JWT; this is the baseline “token is currently allowed for this account” allowlist entry.
- Tenant-scoped entries: `session:auth:tenant:<tenantId>:<tokenHash>` when the token’s effective privileges are limited to a specific tenant and derived from `scopedRoles[tenantId]`.
- Global/admin entries: `session:auth:global:<accountId>:<tokenHash>` when the token carries cross-tenant `globalRoles` such as `platformAdmin`, `billingAdmin`, or `support`.

The Account Service always creates exactly one account-scoped entry per issued token, may create one tenant-scoped entry per tenant in `scopedRoles`, and creates a single global entry when `globalRoles` are present. In all cases, `tokenHash` is a fixed-length digest (for example, a hex-encoded SHA-256 of the JWT), and TTL is derived from the JWT lifetime:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

See [Environment & Secrets](../../infrastructure/environment-and-secrets.md#authentication) for configuration details.

Browser JWTs and Service JWTs share the same claim schema (`iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, optional `globalRoles`, optional `scopedRoles`) but differ in their intended audiences and issuance flows as described in the Authentication & Authorization design. New endpoints must document which profile they issue or accept and validate the expected audience before trusting a token.

The Account Service is the sole writer for auth revocation watermark keys (`session:auth:revoked_after:account:*`, `session:auth:revoked_after:tenant:*`). Other services request revocation via events or APIs and must not write watermark keys directly.

Billing-safe mutation authority contract:

- The Account Service provides an authoritative membership API for live billing-safe checks: `GetTenantMembership(accountId, tenantId)` (REST equivalent `GET /tenants/{tenantId}/memberships/{accountId}`).
- Responses include `evaluatedAt` and `membershipVersion` so callers can verify freshness and detect stale reads.
- If authoritative membership data is unavailable, callers must fail closed for billing-safe mutations rather than relying on JWT claims alone.

Entitlement producer contract:

- `GetTenantEntitlements(tenantId)` is the authoritative producer for admission-critical entitlement snapshots.
- Responses must include:
  - `evaluatedAt` (UTC RFC3339 timestamp),
  - `entitlementVersion` (monotonic per-tenant version string/integer),
  - `tenantBillingSequence` (monotonic `uint64` scoped to `tenantId`).
- `tenantBillingSequence` must be monotonic for each tenant and must advance whenever billing-state transitions can affect availability/quotas.

### Two-Factor Authentication

Two-factor authentication is optional and applies only when a `two_factor_secret` is configured on an account. This is typically enabled for administrator or moderator accounts. When present, the `/auth/login` endpoint requires an `otp` field. Codes are validated using the Base32 secret as outlined in the [Security Architecture](../../system-architecture-security.md).

When `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins over **plaintext Telnet** are additionally constrained:

- Only accounts that have a `two_factor_secret` configured and
- Have explicitly opted in to **allow plaintext Telnet login** in their account settings

may authenticate via the raw TCP Telnet port. The account model includes a boolean flag (for example `allowPlaintextTelnetLogin`) that is exposed both:

- As a checkbox in the web portal account settings (default: unchecked, with a clear explanation of the risks of plaintext Telnet), and
- As an option in the Telnet account setup flow (default: off, with matching wording).

Accounts that do not meet these conditions must use the TLS Telnet port or the web client instead; the `Authenticate` gRPC response returns a dedicated error code so the Game Session Service can present a clear message to the player. `/auth/login` remains a browser/control-plane endpoint.

### Login error codes

Both the `/auth/login` REST endpoint and the gRPC `Authenticate` method return structured `shared.v1.ErrorDetail` responses when authentication fails. Responses use the canonical codes defined in `AuthenticationErrorCodes` so downstream services can rely on stable semantics:

- `AUTH_INVALID_CREDENTIALS` - wrong username or password
- `AUTH_OTP_REQUIRED` - invalid or missing OTP for a two-factor-protected account
- `AUTH_ACCOUNT_LOCKED` - account suspended or locked by policy (reserved for future enforcement)
- `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP` - account attempted to log in over plaintext Telnet but does not yet have two-factor authentication enabled while `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is true
- `AUTH_PLAINTEXT_TCP_NOT_PERMITTED` - account attempted to log in over plaintext Telnet without having opted in to allow this transport (for example `allowPlaintextTelnetLogin=false`)
- `AUTH_UPSTREAM_FAILURE` - infrastructure/grpc failures before authentication could complete

The Game Session Service translates these codes into the text-protocol `ERROR <CODE>` responses (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, etc.) so Telnet and WebSocket clients always see consistent login error semantics even when human-facing messages evolve.

Canonical non-login authorization/entitlement errors:

- `MEMBERSHIP_AUTH_UNAVAILABLE` - authoritative membership/role lookup is unavailable for a billing-safe mutation; callers must fail closed.
- `ENTITLEMENT_UNAVAILABLE` - authoritative entitlement snapshot could not be produced at required freshness/sequence guarantees.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /accounts` – create a new account and profile.
- `GET /accounts/{accountId}/export` – export all account data.
- `DELETE /accounts/{accountId}` – remove an account permanently.
- `POST /auth/login` – authenticate and establish a control-plane session for first-party admin/creator UIs by returning a Browser JWT and associated metadata. The frontend stores this token in memory and sends it on meta/control API calls; gameplay clients do not call this endpoint directly and authenticate exclusively via the text `LOGIN`/`LOGON` flow fronted by the Game Session Service. The response body follows the shared envelope pattern:

  ```json
  {
    "status": "SUCCESS",
    "data": {
      "token": "jwt-token-here",
      "expiresAt": "2025-01-01T12:00:00Z",
      "account": {
        "accountId": "user-123",
        "email": "demo@example.com"
      },
      "scopedRoles": {
        "tenant-abc": ["tenantAdmin", "designer"],
        "tenant-def": ["player"]
      },
      "globalRoles": ["platformAdmin"]
    }
  }
  ```

  Error responses use the standard `shared.v1.ErrorDetail` structure and `AuthenticationErrorCodes` as described below.
- `POST /auth/connect-token` – issue a short-lived gameplay connect token for one `{tenantId, gameInstanceId}` target for first-party WebSocket handshake policy on `/ws/game/**`.
- `POST /auth/logout` – revoke only the currently presented token. The service computes `tokenHash` from `Authorization: Bearer <token>`, deletes associated `session:auth:*:<tokenHash>` allowlist entries, and emits an audit event.
- `POST /auth/logout-all` – revoke all active tokens for the authenticated account by setting `session:auth:revoked_after:account:<accountId>` to now and emitting an audit event. This operation is idempotent.
  - The account watermark is the immediate revocation authority. Existing `session:auth:tenant:*` and `session:auth:global:*` keys for the account may be removed asynchronously by bounded background cleanup and are not required for immediate correctness.
- `GET /.well-known/jwks.json` – JWKS for verifying issued JWT tokens.
- `GET /tenants/{tenantId}/memberships/{accountId}` – authoritative live membership/role lookup used by billing-safe mutation guards.
- `GET /tenants/{tenantId}/entitlements` – authoritative runtime entitlement snapshot including `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
- `POST /auth/request-email-verification` – send a verification email for the account.
- `POST /auth/verify-email` – confirm the verification token.

Example account creation request:

```bash
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"secret"}'
```

Example login request:

`otp` is only required when two-factor authentication is enabled for the account.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"secret","otp":"123456"}'
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`account_service.proto`](../../../../protos/account/v1/account_service.proto).
- `CreateAccount(CreateAccountRequest) returns (CreateAccountResponse)` – registers a new user.
- `SendNotification(SendNotificationRequest) returns (SendNotificationResponse)` – deliver account notifications asynchronously.
- `ExportAccount(ExportAccountRequest) returns (ExportAccountResponse)` – export account data.
- `DeleteAccount(DeleteAccountRequest) returns (DeleteAccountResponse)` – permanently remove an account.
- `RequestPasswordReset(RequestPasswordResetRequest) returns (RequestPasswordResetResponse)` – send a reset token.
- `CompletePasswordReset(CompletePasswordResetRequest) returns (CompletePasswordResetResponse)` – reset the password using a token.
- `LinkExternalAccount(LinkExternalAccountRequest) returns (LinkExternalAccountResponse)` – attach a third-party account.
- `IssueConnectToken(IssueConnectTokenRequest) returns (IssueConnectTokenResponse)` – issue short-lived gameplay connect token for `/ws/game/**` handshake policy.
- `GetTenantMembership(GetTenantMembershipRequest) returns (GetTenantMembershipResponse)` – authoritative account-tenant membership/role lookup for billing-safe mutation guards.
- `GetTenantEntitlements(GetTenantEntitlementsRequest) returns (GetTenantEntitlementsResponse)` – authoritative runtime entitlement snapshot including freshness/sequence fields.
- `RequestEmailVerification(RequestEmailVerificationRequest) returns (RequestEmailVerificationResponse)` – send a verification email for the account.
- `VerifyEmail(VerifyEmailRequest) returns (VerifyEmailResponse)` – confirm the email token.
- `CreatePaymentIntent(CreatePaymentIntentRequest) returns (CreatePaymentIntentResponse)` – initiate a payment.
- `CreateSubscription(CreateSubscriptionRequest) returns (CreateSubscriptionResponse)` – start a recurring subscription.
- `CreateDonation(CreateDonationRequest) returns (CreateDonationResponse)` – create a donation payment.
- `RefundPayment(RefundPaymentRequest) returns (RefundPaymentResponse)` – refund a payment.
- `GetBalance(GetBalanceRequest) returns (GetBalanceResponse)` – retrieve a virtual currency balance.
- `AddCurrency(AddCurrencyRequest) returns (AddCurrencyResponse)` – add virtual currency for an account.
- `SpendCurrency(SpendCurrencyRequest) returns (SpendCurrencyResponse)` – spend virtual currency for an account.

Call the gRPC method with:

```bash
grpcurl -plaintext localhost:6565 account.v1.AccountService/Ping
```

### Saga Participation

Account creation uses the shared `SagaBuilder` from `firemud-common` to persist
the account record, create the profile, and log creation in the Logging & Admin
Service. If any step fails, compensation actions roll back the database writes
so the workflow remains consistent across services. See the
[Transaction Strategies](../../system-architecture-transactions.md) document for
details on the saga pattern.

Purchase workflows (one-time payments or donations) reuse this same runner. The
`PurchaseWorkflowService` creates the payment intent and then records the
transaction with the Logging & Admin Service. Should the log step fail, the
saga automatically refunds the Stripe payment via a compensation action.

### Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service alongside
the Logging & Admin Service using **Testcontainers**. Execute it once dependent
images are available:

```bash
./gradlew :account-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more details.
