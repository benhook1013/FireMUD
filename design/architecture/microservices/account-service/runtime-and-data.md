# Account Service Runtime and Data

This document defines the Account Service runtime model, persistent data ownership, Redis role, token/session responsibilities, and monetization-related domain notes.

## Architecture and Runtime Notes

- Stateless authentication uses short-lived JWT tokens for internal meta/control APIs. Two token profiles are issued:
  - Browser JWTs for first-party admin/creator web UIs via `/auth/login`, with a frontend-oriented audience and short lifetime.
  - Service JWTs for backend services via internal authentication flows (for example, the `Authenticate` gRPC method), with an internal audience.
  Gameplay protocol clients (Telnet and WebSocket) never see or transmit these tokens.
- The service hashes raw passwords with a strong algorithm such as Argon2 and unique salts before storing them in PostgreSQL.
- Auth token allowlist entries are stored in Redis as described in [Authentication & Authorization](../../system-architecture-authentication.md); gameplay session bindings are owned by the Game Session Service and are not managed directly here.
- Creation events are logged to the Logging & Admin Service via a saga step.
- Ban and recovery events are logged to the Logging & Admin Service for auditability.
- Account-to-character relationships allow players to own characters across multiple games.
- The core `account` record represents a global platform account identified by `accountId`. Tables that represent per-game state or billing attach to tenants via a `tenantId` column so the same platform account can join multiple games without data leakage. Every tenant-scoped query enforces this filter as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Provides a JWKS endpoint for other services to validate tokens. JWT signing keys and JWKS documents are rotated by dedicated Kubernetes Jobs (cert-manager rotates TLS certificates), as described in the [Security Architecture](../../system-architecture-security.md).
- All service-to-service communication is protected by mutual TLS.
- Gameplay client authentication is initiated via the `LOGIN` command flow described in [Authentication & Authorization](../../system-architecture-authentication.md); gameplay clients never see JWTs. The Game Session Service calls the internal `Authenticate` gRPC method (mTLS-protected) to verify credentials and obtain Service JWTs, while `/auth/login` remains a browser/control-plane endpoint.
- Admin and creator UI authentication is initiated via the `/auth/login` HTTP endpoint, which issues Browser JWTs used for control-plane APIs as described in the authentication and frontend architecture designs.
- Owns brute-force defense and login abuse handling for the platform. The service monitors login attempts per account and per IP, applies throttling and temporary blacklisting policies, and emits structured signals (for example, `AUTH_ACCOUNT_LOCKED`) that the Game Session Service and other consumers honor when binding gameplay sessions. Suspicious activity triggers notification emails and audit events as described in [Security Architecture](../../system-architecture-security.md#brute-force-defense-and-abuse-handling).
- Non-gameplay workflows such as account creation or billing updates are orchestrated using the Saga pattern outlined in [Transaction Strategies](../../system-architecture-transactions.md).
- Leverages the [Shared Libraries](../../system-architecture-shared-libraries.md) for common DTOs, logging interceptors, and Micrometer metrics.

## Data Model

- `account` table stores username, password hash, email, and status flags for the global platform account.
- `profile` table captures optional user details and preferences.
- `achievement` table records earned achievements keyed by account and game.
- `external_account` table links third-party OAuth IDs to platform accounts.
- Tenant- and billing-related tables such as `subscription` and `payment_transaction` associate `accountId` with `tenantId` for hosted games and hosting plans.

External accounts allow players to log in via Google, Discord, or Steam. Each link stores the provider name and external ID so the platform account can be resolved during authentication.

## Redis Role and Prefixes

- **Coordination Redis**
  - The Account Service does not participate in tick or gameplay coordination and never touches `tick:*`, `timer:*`, `retry:*`, or other tick-related prefixes on Coordination Redis; those responsibilities remain with the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md).
  - It does, however, use auth/session keys as documented in [Authentication & Authorization](../../system-architecture-authentication.md):
    - `session:auth:account:<accountId>:<tokenHash>` – baseline allowlist entry for a JWT, consulted for control-plane session validity and immediate revocation.
    - `session:auth:tenant:<tenantId>:<tokenHash>` – tenant-scoped Account/JWT bindings for internal auth, consulted when authorizing tenant-specific operations.
    - `session:auth:global:<accountId>:<tokenHash>` – cross-tenant Account/JWT bindings for internal auth, consulted when authorizing cross-tenant or platform-wide operations based on `globalRoles`.
  - These keys live on Coordination Redis so that auth/session bindings share the same AOF and reset semantics as gameplay sessions. They are short-lived but reset-sensitive: coordination resets that drop `session:auth:*` force re-authentication and token re-issuance for affected account, tenant, and global scopes.
- **Cache/Rate-Limit Redis**
  - The Account Service does not maintain its own Cache/Rate-Limit Redis prefixes today; any future caches for account or profile lookups must use Cache/Rate-Limit Redis and the key naming/TTL/versioning rules in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md), not Coordination Redis.
  - When introducing new Redis usage here, follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so auth/session keys, roles, and observability remain consistent with the global design.

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Session and Token Model

Authentication generates a JWT and records server-side allowlist entries in Redis for immediate revocation, following the scope model defined in [Authentication & Authorization](../../system-architecture-authentication.md):

- Account-scoped entries: `session:auth:account:<accountId>:<tokenHash>` for every issued JWT; this is the baseline allowlist entry.
- Tenant-scoped entries: `session:auth:tenant:<tenantId>:<tokenHash>` when the token’s effective privileges are limited to a specific tenant and derived from `scopedRoles[tenantId]`.
- Global/admin entries: `session:auth:global:<accountId>:<tokenHash>` when the token carries cross-tenant `globalRoles` such as `platformAdmin`, `billingAdmin`, or `support`.

The Account Service always creates exactly one account-scoped entry per issued token, may create one tenant-scoped entry per tenant in `scopedRoles`, and creates a single global entry when `globalRoles` are present. In all cases, `tokenHash` is a fixed-length digest (for example, a hex-encoded SHA-256 of the JWT), and TTL is derived from the JWT lifetime:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

See [Environment & Secrets](../../infrastructure/environment-and-secrets.md#authentication) for configuration details.

Browser JWTs and Service JWTs share the same claim schema (`iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, optional `globalRoles`, optional `scopedRoles`) but differ in their intended audiences and issuance flows as described in the authentication design. New endpoints must document which profile they issue or accept and validate the expected audience before trusting a token.

The `player-bootstrap` token profile is distinct from both Browser JWTs and Service JWTs:

- Audience is `player-bootstrap`.
- It is issued only by `POST /auth/player-bootstrap` / `IssuePlayerBootstrapToken`.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It authenticates player credentials directly under the same password, OTP, brute-force defense, and account-lock policy used for gameplay login rather than deriving bootstrap from a pre-existing admin/creator session.
- It establishes account identity for first-party gameplay bootstrap only; tenant membership/public-admission and runtime entitlement checks occur later during `POST /auth/connect-token` for the discovery-selected realm target.
- It is stored in memory only by first-party gameplay UIs and is accepted only on gameplay-bootstrap surfaces such as bootstrap discovery and `POST /auth/connect-token`.
- For first-party `/ws/game/**`, subsequent gameplay `LOGIN` must complete from the bootstrap/connect context already established for that socket; browser clients must not be required to replay account credentials after bootstrap.
- If the token is lost because the page/app process is restarted, the client must restart bootstrap from `POST /auth/player-bootstrap`; the current architecture does not define a hidden silent-bootstrap refresh mechanism.
- It is backed by `session:auth:account:<accountId>:<tokenHash>` so account-level logout/revocation semantics remain consistent.
- `POST /auth/logout` and `POST /auth/logout-all` must accept this profile so first-party gameplay UIs can explicitly revoke bootstrap capability on sign-out rather than waiting for expiry.

Gameplay clients never hold control-plane Browser JWTs or internal Service JWTs. The only JWT profile first-party gameplay clients may hold directly is this short-lived `player-bootstrap` token.

The Account Service is the sole writer for auth revocation watermark keys (`session:auth:revoked_after:account:*`, `session:auth:revoked_after:tenant:*`, `session:auth:revoked_after:membership:<accountId>:<tenantId>`). Other services request revocation via events or APIs and must not write watermark keys directly.

## Membership and Entitlement Authority

Billing-safe mutation authority contract:

- The Account Service provides an authoritative membership API for live billing-safe checks: `GetCallerTenantMembership(tenantId)` (REST equivalent `GET /tenants/{tenantId}/memberships/me`), where subject account is bound from caller auth context.
- Cross-tenant membership reads use a separate API: `GetTenantMembershipForAccount(tenantId, accountId)` (REST equivalent `GET /tenants/{tenantId}/memberships/{accountId}`), restricted to `billingAdmin`/`platformAdmin`.
- The Account Service provides a separate internal membership API for gameplay/runtime callers: `GetTenantMembershipForRuntime(accountId, tenantId)` (or protocol-equivalent). Game Session must use this API for `PLAY`, reconnect/resume validation, and membership-gap reconciliation rather than reusing billing-safe caller-bound routes.
- Responses include `evaluatedAt` and `membershipVersion` so callers can verify freshness and detect stale reads.
- If authoritative membership data is unavailable, callers must fail closed for billing-safe mutations rather than relying on JWT claims alone.

Runtime caller contract:

- `GetTenantMembershipForRuntime(accountId, tenantId)` is the authoritative internal membership surface for gameplay/runtime flows.
  - Minimum request fields: `accountId`, `tenantId`, `requestId`.
  - Minimum response fields: `accountId`, `tenantId`, `roles[]`, `gameplayAdmissionAllowed`, `membershipVersion`, `evaluatedAt`.
- `GetTenantEntitlementsForRuntime(tenantId)` is the authoritative internal entitlement surface for gameplay/runtime flows.
  - Minimum request fields: `tenantId`, `requestId`.
  - Minimum response fields: `tenantId`, `subscriptionStatus`, `gameplayAvailable`, `quotas { ... }`, `evaluatedAt`, `entitlementVersion`, `tenantBillingSequence`.
- `GetAdmissionPointer(tenantId, realmSlug)` is the authoritative gameplay-admissible-instance lookup owned by Game Session.
  - Minimum request fields: `tenantId`, `realmSlug`, `requestId`.
  - Minimum response fields: `tenantId`, `realmSlug`, `admissibleGameInstanceId`, `pointerVersion`, `updatedAt`.
- `IssueConnectToken` / `POST /auth/connect-token` is the authoritative gameplay bootstrap token-issuance surface.
  - Minimum request fields: `connectScopeId`, `requestId`.
  - Minimum response fields: `connectToken`, `expiresAt`, `accountId`, `tenantId`, `realmSlug`, `gameInstanceId`, `jti`, `issuedAt`.
- Account Service must expose bootstrap-discovery endpoints that accept only the `player-bootstrap` token profile and return the canonical caller-visible worlds, realms, characters, and a canonical `connectScopeId` selector for each admissible realm target.
- Account Service must use the authoritative realm-routing contract when issuing `/auth/connect-token` so connect-token scope is pinned to the tenant's current admissible instance for the selected realm instead of a caller-supplied guess.
- `EnsurePublicProductionPlayerMembership(accountId, tenantId, realmSlug, requestId)` is the authoritative membership-creation surface for first admission through a tenant's default production realm.
  - It is valid only for the default public production realm and only when the caller satisfies the public-production admission policy.
  - It must be idempotent for `{accountId, tenantId, realmSlug}` and return the resulting `membershipVersion`.
  - The resulting membership must be immediately visible to `GetTenantMembershipForRuntime`.
- Runtime callers must treat missing required fields as contract failure and fail closed rather than inferring defaults.

Membership-change producer contract:

- The Account Service emits membership-change events with `eventId`, `accountId`, `tenantId`, `membershipVersion`, changed roles, and a flag indicating whether gameplay admission remains allowed.
- `membershipVersion` is monotonic per `{accountId, tenantId}` and must advance on any membership or role change that can affect gameplay admission or caller-bound tenant authority.
- When a membership or tenant-role change invalidates existing caller-bound tenant authorization, the Account Service must also advance `session:auth:revoked_after:membership:<accountId>:<tenantId>` so previously issued control-plane tokens lose tenant authority immediately rather than waiting for expiry.
- Consumers treat duplicate/older versions as no-ops and reconcile gaps with authoritative membership reads.

Entitlement producer contract:

- `GetTenantEntitlementsForRuntime(tenantId)` is the authoritative producer for admission-critical entitlement snapshots.
- Responses must include:
  - `evaluatedAt` (UTC RFC3339 timestamp),
  - `entitlementVersion` (monotonic per-tenant version string/integer),
  - `tenantBillingSequence` (monotonic `uint64` scoped to `tenantId`).
- `tenantBillingSequence` must be monotonic for each tenant and must advance whenever billing-state transitions can affect availability/quotas.

## Monetization and Notification Domain Notes

### Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through Stripe as outlined in the [Core Requirements](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization). Entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers expose operations for creating payment intents and managing subscriptions. The proto definitions live in [`payment_service.proto`](../../../../protos/account/v1/payment_service.proto).

Donations are stored as one-time `payment_transaction` records with the `donation` flag set to `true`. A dedicated `CreateDonation` gRPC method issues a Stripe payment intent for these cases. Refunds call Stripe's API and update the `payment_transaction` `status` to `refunded`, enabling chargeback handling workflows. More detailed designs for payments and recurring subscriptions live in the dedicated [Stripe Integration Design](./stripe-integration.md) and [Subscription Management Design](./subscription-management.md) documents.

### Virtual Currency and Revenue Sharing

Each tenant may define game-specific currencies. Balances are stored in the `currency_balance` table keyed by account. The `VirtualCurrencyService` gRPC API allows services to add or spend currency for an account. Platform fees are deducted from each purchase and the remaining `creator_share_cents` is recorded on the `payment_transaction` row for revenue-sharing calculations.

### Premium Hosting

Premium hosting tiers are modeled as subscription plans with higher resource limits. Game creators can upgrade via the existing payment flows.

### Email and Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables configure `spring.mail.*` along with `firemud.mail.from`, `firemud.mail.verification-url`, and `firemud.mail.reset-url`. The gRPC API is defined in [`notification_service.proto`](../../../../protos/account/v1/notification_service.proto).
