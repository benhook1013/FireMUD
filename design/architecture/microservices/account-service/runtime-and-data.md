# Account Service Runtime and Data

This document defines the Account Service runtime model, persistent data ownership, Redis role, token/session responsibilities, and monetization-related domain notes.

## Implementation Notes

The account lifecycle state machine, global deletion preconditions, full-account versus tenant-scoped export split, and `purchase_entitlement` model are the canonical target design. The current service has live identity/authentication, profile, external-link persistence, recovery/verification, payment, subscription, notification, and virtual-currency foundations, but lifecycle state transitions, genuine provider authentication, cross-service export, retained-record handling, purchased-entitlement fulfillment, and complete billing/subscription follow-through remain partial. The current external-link route accepts caller-asserted provider identity and stores tenant scope; it is not a supported provider login and directly conflicts with [ADR 0049](../../decisions/adr-0049-optional-provider-specific-external-identity-linking.md). The current full export returns only Account and profile rows, the tenant export includes excessive global Account fields, and successful deletion immediately removes Account-owned rows including payment transactions. Those behaviors directly conflict with [ADR 0050](../../decisions/adr-0050-versioned-export-retention-and-erasure-policy.md) and the bounded erasure workflow below.

## Architecture and Runtime Notes

- Stateless authentication uses short-lived JWT tokens for internal meta/control APIs. Two token profiles are issued:
  - Browser JWTs for first-party admin/creator web UIs via `/auth/login`, with a frontend-oriented audience and short lifetime.
  - Service JWTs for backend services via internal authentication flows (for example, the `Authenticate` gRPC method), with an internal audience.
  Gameplay protocol clients (Telnet and WebSocket) never see or transmit these tokens.
- The live service hashes and verifies raw passwords with `argon2-jvm` Argon2 using `iterations=2`, `memory=65536 KiB`, and `parallelism=1`, with unique salts before PostgreSQL storage.
- Issued-token registry records are stored in Redis as described in [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md); gameplay session bindings are owned by the Game Session Service and are not managed directly here.
- Creation events are logged to the Logging & Admin Service via a saga step.
- Ban and recovery events are logged to the Logging & Admin Service for auditability.
- Account-to-character relationships allow players to own characters across multiple games.
- The core `account` record represents a global platform account identified by `accountId`. Tables that represent per-game state or billing attach to tenants via a `tenantId` column so the same platform account can join multiple games without data leakage. Every tenant-scoped query enforces this filter as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Provides a JWKS endpoint for other services to validate tokens. JWT signing keys and JWKS documents are rotated by dedicated Kubernetes Jobs (cert-manager rotates TLS certificates), as described in the [Security Architecture](../../system-architecture-security.md).
- All service-to-service communication is protected by mutual TLS.
- Gameplay client authentication is initiated via the `LOGIN` command flow described in [Authentication & Authorization](../../system-architecture-authentication.md); gameplay clients never see JWTs. The Game Session Service calls the internal `Authenticate` gRPC method (mTLS-protected) to verify credentials and obtain Service JWTs, while `/auth/login` remains a browser/control-plane endpoint.
- Admin and creator UI authentication is initiated via the `/auth/login` HTTP endpoint, which issues Browser JWTs used for control-plane APIs as described in the authentication and frontend architecture designs.
- Owns credential brute-force defense and login abuse handling for every password and verified-email-code path. It combines trusted canonical source context with normalized account-candidate and coarse pressure buckets, applies graduated temporary throttles, and emits stable retry outcomes. Ordinary failed attempts never transition the account to `security_locked`; that durable state requires verified or high-confidence compromise, explicit security policy, or audited operator action. Suspicious activity triggers notification emails and audit events as described in [Security Architecture](../../system-architecture-security.md#brute-force-defense-and-abuse-handling) and [ADR 0034](../../decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md).
- Non-gameplay workflows such as account creation or billing updates are orchestrated using the Saga pattern outlined in [Transaction Strategies](../../system-architecture-transactions.md).
- Leverages the [Shared Libraries](../../system-architecture-shared-libraries.md) for common DTOs, logging interceptors, and Micrometer metrics.

## Data Model

- `account` table stores username, password hash, email, and status flags for the global platform account.
- `profile` table captures optional user details and preferences.
- `achievement` table records earned achievements keyed by account and game.
- Target `external_account` records link a server-verified canonical `{provider, issuer, subject}` to one global platform account without tenant scope. The current tenant-scoped, caller-asserted row shape is implementation drift.
- Tenant- and billing-related tables such as `subscription` and `payment_transaction` associate `accountId` with `tenantId` for hosted games and hosting plans.
- Durable purchased-product grants are represented separately from raw payment attempts. One-time purchases that create ongoing player-visible value write `purchase_entitlement` rows keyed by `accountId`, `tenantId`, optional `characterId`, and `productCode`; payment rows remain the audit and provider-settlement record, not the entitlement authority.

Google, Discord, and Steam are optional provider-specific HTTPS integrations, not simultaneously promised launch capabilities. Initial delivery links a verified provider subject to an already authenticated global account; provider-first account creation is deferred. Provider email or display-name matches never merge accounts, every account retains Account-owned verified-email recovery and an ordinary login mode, and provider outage does not invalidate existing FireMUD sessions.

## Account Lifecycle State Model

The Account Service owns one canonical account lifecycle state machine for the global platform account. Tenant billing state can block gameplay for one tenant, but it does not change the global account state by itself.

| State | Login and bootstrap | Control-plane access | Billing/export access | Recovery | Notes |
| --- | --- | --- | --- | --- | --- |
| `active` | Allowed subject to credentials, 2FA, and abuse controls | Allowed by route authorization | Allowed by route authorization | Allowed | Normal state. |
| `security_locked` | Denied with `AUTH_ACCOUNT_LOCKED` after sufficient identity proof; arbitrary failed attempts retain non-enumerating behavior | Existing sessions are revoked; new control-plane login is denied except support-approved recovery flows | Account-scoped export may be support-mediated; billing mutations are denied until recovery clears the lock | Allowed only through the account-security recovery path | Used for verified or high-confidence compromise, explicit account-security policy, or audited operator action. A failed-password threshold alone cannot enter this state. It is not a tenant gameplay ban. |
| `deactivated_pending_delete` | Denied for gameplay and normal control-plane login | Only deletion-cancel, export, and billing-settlement surfaces remain available by explicit account-scoped or support-mediated routes | Full-account export and billing-settlement reads remain available; new purchases/subscriptions are denied | Allowed to cancel deletion before the retention window expires | Entered after a confirmed deletion request when asynchronous retention/settlement work remains. |
| `deleted` | Denied | Denied | Only retained audit, tax, fraud, and settlement records remain available to platform-admin/reporting flows | Denied | Terminal account state; usernames/emails may be tombstoned or anonymized according to the retention policy. |

Transitions are explicit: `active` may move to `security_locked`, `deactivated_pending_delete`, or remain `active`; `security_locked` may return to `active` after successful recovery or move to `deactivated_pending_delete` only through a support-reviewed path; `deactivated_pending_delete` may return to `active` before the published cancellation window expires or move to `deleted`; `deleted` has no recovery transition. Every transition must emit an audit event and advance `session:auth:generation:account:<accountId>` so existing control-plane and player-bootstrap tokens stop working immediately when the new state denies access.

Account deletion is global, not tenant-scoped. A self-service request requires recent authentication, explicit confirmation, and a clear full-account export option. `DeleteAccount` must fail with `ACCOUNT_DELETE_ACTIVE_BILLING_OWNER` while the account owns any subscription in `trialing`, `active`, `past_due`, `grace`, or `suspended` for any tenant, returning all safely disclosable blockers. The caller must first cancel terminally or transfer billing ownership for every affected tenant.

An eligible confirmed request enters `deactivated_pending_delete`, immediately revokes ordinary account and bootstrap authority, and hides normal account/profile surfaces. Only explicit deletion cancellation, export, and necessary billing-settlement routes remain during the published cancellation and retention window. A durable, idempotent cross-service workflow then deletes, anonymizes, tombstones, or schedules policy-allowed retention for each owning domain. Failed work leaves the account pending with retry and operator diagnostics; the account becomes terminal `deleted` only after every required step has completed or is durably recorded under an allowed retention schedule.

Deletion must not blindly hard-delete billing, payment, refund, Stripe customer, tax, fraud, moderation, security, or audit records that have an approved retained purpose. Under ADR 0050, every persistent subject-related category declares its owner, export behavior, terminal action, retention trigger, exact finite maximum, approved purpose, permitted readers, and backup treatment. Retained records keep only the minimum account correlation and access needed for that purpose; generic or indefinite “audit/compliance” retention is forbidden.

Full-account export and tenant-scoped export are separate contracts. `ExportAccount` orchestrates an asynchronous versioned manifest containing every required owning-service contribution of portable subject data across tenants that the caller is entitled to receive. Missing or omitted categories remain explicit. Tenant billing-safe export is a separate tenant-scoped route for `tenantAdmin` recovery while a tenant is billing-blocked; it returns only tenant-owned exportable records and minimum subject references and must not expose global email, credentials, external identities, security state, or unrelated account data from other tenants.

## Redis Role and Prefixes

- **Coordination Redis**
  - The Account Service does not participate in tick or gameplay coordination and never touches `tick:*`, `timer:*`, `retry:*`, or other tick-related prefixes on Coordination Redis; those responsibilities remain with the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md).
  - It does, however, use auth/session keys as documented in [Authentication & Authorization](../../system-architecture-authentication.md):
    - `session:auth:token:<tokenHash>` – the one versioned issued-token record for a revocable Browser, player-bootstrap, or private Service JWT, consulted for control-plane/admission validity and immediate per-token revocation.
  - These records live on Coordination Redis so auth sessions share the same AOF and reset semantics as gameplay sessions. They are short-lived but reset-sensitive: coordination resets that drop `session:auth:*` force re-authentication and token re-issuance.
- **Cache/Rate-Limit Redis**
  - The Account Service does not maintain its own Cache/Rate-Limit Redis prefixes today; any future caches for account or profile lookups must use Cache/Rate-Limit Redis and the key naming/TTL/versioning rules in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md), not Coordination Redis.
  - When introducing new Redis usage here, follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so auth/session keys, roles, and observability remain consistent with the global design.

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Session and Token Model

Authentication generates a Browser, player-bootstrap, or private Service JWT and establishes exactly one server-side `session:auth:token:<tokenHash>` registry record before returning it, following [ADR 0035](../../decisions/adr-0035-single-record-issued-token-registry.md). The record proves exact issuance and active state; signed claims plus Account-owned revocation/version state determine tenant and global authority without scope-duplicated token keys.

`tokenHash` is a fixed-length digest (a hex-encoded SHA-256 of the complete compact JWT), and the one registry record's TTL is derived from the JWT lifetime:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

See [Environment & Secrets](../../infrastructure/environment-and-secrets.md#authentication) for configuration details.

Browser JWTs and Service JWTs share the same claim schema (`iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, optional `globalRoles`, optional `scopedRoles`) but differ in their intended audiences and issuance flows as described in the authentication design. New endpoints must document which profile they issue or accept and validate the expected audience before trusting a token.

The `player-bootstrap` token profile is distinct from both Browser JWTs and Service JWTs:

- Audience is `player-bootstrap`.
- It is issued only by `POST /auth/player-bootstrap` / `IssuePlayerBootstrapToken`.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It authenticates player credentials directly under the same password, OTP, brute-force defense, and account-lock policy used for gameplay login rather than deriving bootstrap from a pre-existing admin/creator session.
- It establishes account identity for first-party gameplay bootstrap only. Public-game membership is created only by explicit `/auth/bootstrap/join`; character creation and `POST /auth/connect-token` later require that membership and recheck applicable grants, entitlements, and the discovery-selected runtime target.
- It is stored in memory only by first-party gameplay UIs and is accepted only on gameplay-bootstrap surfaces such as bootstrap discovery and `POST /auth/connect-token`.
- For first-party `/ws/game/**`, subsequent gameplay `LOGIN` must complete from the bootstrap/connect context already established for that socket; browser clients must not be required to replay account credentials after bootstrap.
- If the token is lost because the page/app process is restarted, the client must restart bootstrap from `POST /auth/player-bootstrap`; the current architecture does not define a hidden silent-bootstrap refresh mechanism.
- It is backed by one `session:auth:token:<tokenHash>` record so per-token logout and account-level revocation semantics remain consistent.
- `POST /auth/logout` and `POST /auth/logout-all` must accept this profile so first-party gameplay UIs can explicitly revoke bootstrap capability on sign-out rather than waiting for expiry.

Gameplay clients never hold control-plane Browser JWTs or internal Service JWTs. The only JWT profile first-party gameplay clients may hold directly is this short-lived `player-bootstrap` token.

The Account Service owns durable issuer/account/tenant/membership auth generations and is the sole writer for `session:auth:generation:*` projections. Other services request revocation via events or APIs and must not write generation keys directly.

Active gameplay Service JWT refresh is an Account-owned authorization operation, not generic minting for a trusted workload. `RefreshGameplayServiceToken` requires the current token identity and captured authority generations, the bound gameplay session/account/tenant identity, and an idempotent request ID. Account validates the current token record, account lifecycle state, current membership/version, and applicable issuer, account, tenant, and membership generations before issuing a replacement. A generation mismatch cannot be bypassed by issuing a replacement with a newer `iat`. Successful rotation creates the replacement token record before returning it; Game Session installs the replacement atomically and removes the previous record after the bounded in-flight RPC overlap.

Per-token logout deletes only the presented token's one registry record and is idempotent. Logout-all commits a distinct durable account-generation advance and account-wide logout/audit event, then idempotently projects the generation set-if-greater; it does not depend on scanning token keys. Raw token values are excluded from both audit forms. The account-wide event terminates active gameplay under ADR 0030, whereas per-token logout leaves other devices and unrelated gameplay bindings intact.

## Membership and Entitlement Authority

Billing-safe mutation authority contract:

- The Account Service provides an authoritative membership API for live billing-safe checks: `GetCallerTenantMembership(tenantId)` (REST equivalent `GET /tenants/{tenantId}/memberships/me`), where subject account is bound from caller auth context.
- Cross-tenant membership reads use a separate API: `GetTenantMembershipForAccount(tenantId, accountId)` (REST equivalent `GET /tenants/{tenantId}/memberships/{accountId}`), restricted to `billingAdmin`/`platformAdmin`.
- The Account Service provides a separate internal membership API for gameplay/runtime callers: `GetTenantMembershipForRuntime(accountId, tenantId)` (or protocol-equivalent). Game Session must use this API for `PLAY`, reconnect/resume validation, and membership-gap reconciliation rather than reusing billing-safe caller-bound routes.
- Responses include `evaluatedAt` and `membershipVersion` so callers can verify freshness and detect stale reads.
- If authoritative membership data is unavailable, callers must fail closed for billing-safe mutations rather than relying on JWT claims alone.

Runtime caller contract:

- Current implementation note:
  - `GetTenantMembershipForRuntime(accountId, tenantId)`, `GetTenantEntitlementsForRuntime(tenantId)`, `GetAdmissionPointer(tenantId, worldSlug, realmSlug)`, public-production membership creation, and the fuller bootstrap/discovery surfaces are now all live enough that runtime admission no longer depends on Redis session identity or caller-supplied target tuples.
  - The remaining follow-through is broader reconnect/cutover consumption of the same admission-pointer truth plus later operator-facing tooling around pointer mutation and audit.
- `GetTenantMembershipForRuntime(accountId, tenantId)` is the authoritative internal membership surface for gameplay/runtime flows.
  - Minimum request fields: `accountId`, `tenantId`, `requestId`.
  - Minimum response fields: `accountId`, `tenantId`, `roles[]`, `gameplayAdmissionAllowed`, `membershipVersion`, `evaluatedAt`.
- `GetTenantEntitlementsForRuntime(tenantId)` is the authoritative internal entitlement surface for gameplay/runtime flows. Runtime callers may cache its positive result only under the strict-new-commitment and bounded-continuity rules in [ADR 0028](../../decisions/adr-0028-differentiated-entitlement-freshness.md); the cache never becomes a second writer.
  - Minimum request fields: `tenantId`, `requestId`.
  - Minimum response fields: `tenantId`, `subscriptionStatus`, `gameplayAvailable`, `allowPublicJoin`, `allowNewGameplayBindings`, `allowNewInstanceStarts`, `quotas { ... }`, `evaluatedAt`, `entitlementVersion`, `tenantBillingSequence`.
- `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` is the authoritative gameplay-admissible-instance lookup owned by Game Session.
  - Minimum request fields: `tenantId`, `worldSlug`, `realmSlug`.
  - Minimum response fields: `tenantId`, `worldSlug`, `realmSlug`, `admissibleGameInstanceId`, `pointerVersion`, `updatedAt`.
  - `pointerVersion` is monotonic per `{tenantId, worldSlug, realmSlug}` and is the freshness token callers must use when proving they are still binding against the same realm target they previously resolved.
  - Account bootstrap discovery, in-band `PLAY`, connect-token issuance, and reconnect validation must all consume this same pointer contract rather than maintaining separate realm-to-instance routing rules.
- `IssueConnectToken` / `POST /auth/connect-token` is the authoritative gameplay bootstrap token-issuance surface.
  - Minimum request fields: `connectScopeId`, `requestId`.
  - Minimum response fields for non-browser clients: `connectToken`, `expiresAt`, `accountId`, `tenantId`, `realmSlug`, `gameInstanceId`, `jti`, `issuedAt`.
  - Browser clients receive the connect token as `Set-Cookie: Firemud-Connect-Token=...` with the gateway-required security attributes and receive only non-secret response metadata in the body.
- Bootstrap discovery surfaces must return `connectScopeId` together with `tenantId`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`.
- `connectScopeExpiresAt` bounds how long the discovery-issued selector may be reused as a convenience token. Once it expires, callers must rerun discovery instead of treating the selector as a durable realm handle.
- Account Service must expose bootstrap-discovery endpoints that accept only the `player-bootstrap` token profile and return the canonical caller-visible worlds, realms, characters, and a canonical `connectScopeId` selector for each admissible realm target.
- Account Service must use the authoritative realm-routing contract when issuing `/auth/connect-token` so connect-token scope is pinned to the tenant's current admissible instance for the selected realm instead of a caller-supplied guess.
- `requestId` is the idempotency key for connect-token issuance. Retrying the same `(accountId, connectScopeId, requestId)` must return the same token payload or the same deterministic application failure rather than minting a new logical issuance result.
- `JoinPublicProductionMembership(accountId, tenantId, worldSlug, realmSlug, requestId)` is the authoritative explicit open-enrollment membership surface for a tenant's default public production realm. Connect-token issuance and `PLAY` require its result but never invoke it implicitly.
  - It is valid only for the default public production realm and only when the caller satisfies the public-production admission policy.
  - `requestId` is the attempt idempotency key. Retrying the same `{accountId, tenantId, worldSlug, realmSlug, requestId}` must return the same resulting membership identity/version or the same deterministic application failure.
  - The resulting membership must be immediately visible to `GetTenantMembershipForRuntime`.
  - Minimum preconditions: the selected realm is still the tenant's default public production realm, it remains publicly visible to the caller, current runtime entitlements still allow gameplay admission, and current admission-pointer state resolves unambiguously for that realm.
  - Concurrency rule: racing first-join requests must create at most one membership row and all successful callers must observe the same resulting membership identity/version.
  - Audit rule: successful join commits one durable outbox/audit record in the same SQL transaction, carrying at minimum `accountId`, `tenantId`, `worldSlug`, `realmSlug`, `membershipVersion`, and `requestId`.
  - Required failure codes at minimum: `PUBLIC_PRODUCTION_ADMISSION_DENIED`, `ADMISSION_POINTER_UNAVAILABLE`, and `TENANT_BILLING_BLOCKED`.
  - Failed join requests are non-committing. Once the join transaction succeeds, membership intentionally remains even if later character creation, connect-token issuance, socket connection, or `PLAY` fails.
- Runtime callers must treat missing required fields as contract failure and fail closed rather than inferring defaults.

Membership-change producer contract:

- The Account Service emits membership-change events with `eventId`, `accountId`, `tenantId`, `membershipVersion`, changed roles, and a flag indicating whether gameplay admission remains allowed.
- `membershipVersion` is monotonic per `{accountId, tenantId}` and must advance on any membership or role change that can affect gameplay admission or caller-bound tenant authority.
- When a membership or tenant-role change invalidates existing caller-bound tenant authorization, the Account Service must also advance `session:auth:generation:membership:<accountId>:<tenantId>` so previously issued control-plane tokens lose tenant authority immediately rather than waiting for expiry.
- Consumers treat duplicate/older versions as no-ops and reconcile gaps with authoritative membership reads.

Entitlement producer contract:

- `GetTenantEntitlementsForRuntime(tenantId)` is the authoritative producer for admission-critical entitlement snapshots.
- Responses must include:
  - explicit `subscriptionStatus`, `gameplayAvailable`, `allowPublicJoin`, `allowNewGameplayBindings`, `allowNewInstanceStarts`, and applicable quota fields; absence of a subscription row is not implicit permission, and free/trial hosting is explicit;
  - `evaluatedAt` (UTC RFC3339 timestamp),
  - `entitlementVersion` (monotonic per-tenant version string/integer),
  - `tenantBillingSequence` (monotonic `uint64` scoped to `tenantId`).
- `evaluatedAt` records evaluation of authoritative committed input and must not be restamped merely because an older projection was read.
- `tenantBillingSequence` must be monotonic for each tenant and must advance whenever billing-state transitions can affect availability/quotas.

## Monetization and Notification Domain Notes

### Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through Stripe as outlined in the [Core Requirements](../../../project-management/core-requirements.md#2.8-moderation-administration--monetization). Entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers expose operations for creating payment intents and managing subscriptions. The proto definitions live in [`payment_service.proto`](../../../../protos/account/v1/payment_service.proto).

Donations are stored as one-time `payment_transaction` records with the `donation` flag set to `true`. A dedicated `CreateDonation` gRPC method issues a Stripe payment intent for these cases. Refunds call Stripe's API and update the `payment_transaction` `status` to `refunded`, enabling chargeback handling workflows. More detailed designs for payments and recurring subscriptions live in the dedicated [Stripe Integration Design](./stripe-integration.md) and [Subscription Management Design](./subscription-management.md) documents.

One-time purchases that grant gameplay value must not be inferred from `payment_transaction.status` alone. Stripe success creates or activates a durable `purchase_entitlement` row through an idempotent fulfillment step keyed by provider event ID and product grant key. Refunds, chargebacks, or operator reversals move the entitlement to `revoked` unless the product contract explicitly marks it as consumed and non-revocable; in that case the refund workflow must record the non-revocable consumption reason and rely on financial/audit settlement rather than silently leaving the purchase ambiguous.

### Virtual Currency and Revenue Sharing

Each tenant may define game-specific currencies. Balances are stored in the `currency_balance` table keyed by account. The `VirtualCurrencyService` gRPC API allows services to add or spend currency for an account. Platform fees are deducted from each purchase and the remaining `creator_share_cents` is recorded on the `payment_transaction` row for revenue-sharing calculations.

### Premium Hosting

Premium hosting tiers are modeled as subscription plans with higher resource limits. Game creators can upgrade via the existing payment flows.

### Email and Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables configure `spring.mail.*` along with `firemud.mail.from`, `firemud.mail.verification-url`, and `firemud.mail.reset-url`. The gRPC API is defined in [`notification_service.proto`](../../../../protos/account/v1/notification_service.proto).
