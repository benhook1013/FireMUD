# Account Service API Contracts

This document defines the Account Service REST and gRPC contracts, authentication classes, subject-binding rules, and runtime membership and entitlement response semantics.

The authoritative REST schema source lives in [../../../../services/account-service/src/main/resources/openapi.yaml](../../../../services/account-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## Implementation Notes

The account lifecycle, full-account export, tenant-scoped export, and deletion precondition contracts below are implemented at the current Account Service boundary. `ExportAccount` and `DeleteAccount` are account-scoped and no longer accept a caller-selected `tenantId`; `ExportTenantData` is the separate tenant-scoped recovery/export surface. Password reset, username reminder, and email-verification tokens are account-scoped rather than tenant-keyed.

## gRPC APIs

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `account_service.proto`.
- `CreateAccount` – registers a new user and returns its `accountId` so internal services can establish their own sessions using the authentication flows described below.
- `SendNotification` – deliver account notifications asynchronously.
- `Authenticate` – verifies credentials and issues a Service JWT (internal token profile) backed by `session:auth:*` allowlist entries for meta/control APIs.
- `GetProfile` – retrieves profile information for the current account.
- `UpdateProfile` – modifies profile fields and triggers notification emails. Account holders may select `PUBLIC`, `FRIENDS_ONLY`, or `PRIVATE` presence visibility; `HIDDEN_STAFF` is reserved for the staff-visibility owner and cannot be set through profile writes.
- `ListPresenceVisibilityPolicies` – bounded internal bulk read of current tenant-scoped profile visibility policy for up to 100 account IDs. Social projections consume this authority at read time; unknown or unavailable entries are intentionally omitted so callers can fail closed.
- `ExportAccount` – account-scoped export of portable account-owned data across all tenants visible to the authenticated subject.
- `ExportTenantData` – tenant-scoped billing-safe export for one tenant, available to `tenantAdmin` while gameplay is billing-blocked and limited to that tenant's exportable game/billing records.
- `DeleteAccount` – begins or completes global account deletion according to the account lifecycle state machine; it is not a tenant-scoped membership deletion.
- `RequestPasswordReset` – initiate a password reset email.
- `CompletePasswordReset` – update the password using a token.
- `LinkExternalAccount` – attach a Google, Discord, or Steam ID.
- `IssuePlayerBootstrapToken` – authenticate a first-party player account and issue the short-lived `player-bootstrap` token profile used only for gameplay bootstrap.
- `ListBootstrapWorlds` – list caller-visible worlds for first-party gameplay bootstrap.
- `ListBootstrapRealms` – list caller-visible realms for a selected world during first-party gameplay bootstrap.
- `ListBootstrapCharacters` – list caller-visible characters for a selected world/realm during first-party gameplay bootstrap.
- Bootstrap discovery response contract: for each admissible realm target, return `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`.
  - Required behavior: treat this as a short-lived snapshot proof of the evaluated realm target, not a durable reservation; callers must rerun discovery after `connectScopeExpiresAt` or after stale-scope failures.
- `IssueConnectToken` – issue short-lived gameplay connect token for `/ws/game/**` handshake policy after resolving discovery `connectScopeId`, validating live membership/public admission, runtime entitlements, and the current admission pointer for the target `{tenantId, worldSlug, realmSlug, gameInstanceId}`.
  - Required behavior: `connectScopeId` is an opaque short-lived selector for one caller-visible realm target, must be revalidated against current visibility/grant state and current admission-pointer state at issuance time, and must fail closed with `CONNECT_SCOPE_MISMATCH` or `ADMISSION_POINTER_UNAVAILABLE` when the earlier discovery target is no longer admissible.
  - Required behavior: `requestId` is the idempotency key for issuance. Retrying the same `{accountId, connectScopeId, requestId}` must return the same token payload or the same deterministic application failure.
- `JoinPublicProductionMembership` – perform the caller's explicit open-enrollment join for the default public production realm, idempotently create or return the durable `player` membership, and return its monotonic `membershipVersion`.
  - Required behavior: valid only for the tenant's current default public production realm, must fail closed for non-production realms, must treat `requestId` as the attempt idempotency key, and must emit one durable audit/event record on successful first-join creation.
  - Required failure codes at minimum: `PUBLIC_PRODUCTION_ADMISSION_DENIED`, `ADMISSION_POINTER_UNAVAILABLE`, `TENANT_BILLING_BLOCKED`.
- `GetCallerTenantMembership` – return authoritative caller-bound account-tenant membership and roles for billing-safe mutation checks.
- `GetTenantMembershipForAccount` – cross-tenant membership lookup for billing/reporting workflows (`billingAdmin`/`platformAdmin` only).
- `GetTenantMembershipForRuntime` – authoritative internal membership read for gameplay admission, reconnect/resume, and membership-gap reconciliation.
- `GetTenantEntitlementsForRuntime` – internal runtime/admission entitlement snapshot for Game Session and other gameplay-affecting services.
- `GetRealmAccessGrant` / `ListRealmAccessGrantsForAccount` – authoritative internal realm-grant reads for non-public realm bootstrap discovery and gameplay admission.
- `GetTenantEntitlementsTenant` – caller-bound tenant-admin entitlement view for billing-safe control-plane UX.
- `GetTenantEntitlementsCrossTenantSupportSafe` – cross-tenant support-safe entitlement view with redacted, high-level fields only.
- `RequestEmailVerification` – send a verification email.
- `VerifyEmail` – confirm an email verification token.
- `CreatePaymentIntent` – initiate a Stripe payment.
- `CreateSubscription` – start a recurring subscription.
- `CreateDonation` – process a donation payment.
- `RefundPayment` – issue a refund for a payment.
- `GetBalance` – retrieve a virtual currency balance.
- `AddCurrency` – increase virtual currency for an account.
- `SpendCurrency` – deduct virtual currency from an account.

## REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Simple health check |
| `POST` | `/auth/login` | Authenticate and establish a control-plane session for first-party UIs by returning a Browser JWT; the token is allowlisted server-side via `session:auth:*` entries for revocation |
| `POST` | `/auth/logout` | Revoke the currently presented control-plane token (`session:auth:*:<tokenHash>` delete for that token) |
| `POST` | `/auth/logout-all` | Revoke all active control-plane tokens for the authenticated account by advancing `session:auth:revoked_after:account:<accountId>`; the account watermark is the immediate revocation authority and existing tenant/global allowlist keys may be cleaned up asynchronously |
| `POST` | `/auth/request-password-reset` | Request account-scoped password reset |
| `POST` | `/auth/complete-password-reset` | Complete account-scoped password reset |
| `POST` | `/auth/request-email-verification` | Send account-scoped verification email |
| `POST` | `/auth/verify-email` | Verify account-scoped email token |
| `POST` | `/auth/recover-username` | Send account-scoped username reminder |
| `POST` | `/accounts` | Create a new user account |
| `GET` | `/accounts/{accountId}/export` | Export full account data across tenants |
| `GET` | `/accounts/{accountId}/tenant-export?tenantId={tenantId}` | Tenant-scoped billing-safe export for the authenticated subject or tenant operator |
| `DELETE` | `/accounts/{accountId}` | Delete the global account after billing-owner preconditions pass |
| `POST` | `/accounts/{accountId}/external` | Link external account |
| `POST` | `/auth/player-bootstrap` | Authenticate a first-party player account and return a short-lived `player-bootstrap` token for gameplay bootstrap only |
| `GET` | `/auth/bootstrap/worlds` | List caller-visible worlds for first-party gameplay bootstrap |
| `GET` | `/auth/bootstrap/worlds/{world}/realms` | List caller-visible realms for a selected world during first-party gameplay bootstrap |
| `GET` | `/auth/bootstrap/worlds/{world}/realms/{realm}/characters` | List caller-visible characters for a selected world and realm during first-party gameplay bootstrap |
| `POST` | `/internal/runtime/public-production-join` | Account-owned internal boundary used by explicit text-client `JOIN`; idempotently create or return the durable public-production `player` membership |
| `GET` | `/internal/runtime/realm-access-grants/{tenantId}/{realmSlug}/{accountId}` | Internal-only authoritative lookup for one non-public realm-access grant |
| `GET` | `/internal/runtime/accounts/{accountId}/realm-access-grants` | Internal-only authoritative listing of the caller's non-public realm-access grants for discovery/admission filtering |
| `POST` | `/tenant-admin/tenants/{tenantId}/realm-access-grants` | Target tenant-admin surface for granting non-public realm visibility/admission to one account |
| `DELETE` | `/tenant-admin/tenants/{tenantId}/realm-access-grants/{realmSlug}/{accountId}` | Target tenant-admin surface for revoking non-public realm visibility/admission without deleting the realm |
| `GET` | `/tenant-admin/tenants/{tenantId}/realm-access-grants` | Target tenant-admin surface for listing and auditing non-public realm grants |
| `GET` | `/profiles/{accountId}` | Retrieve profile information |
| `PUT` | `/profiles/{accountId}` | Update profile information |
| `GET` | `/tenants/{tenantId}/memberships/me` | Authoritative caller-bound membership and roles for billing-safe mutation guards |
| `GET` | `/tenants/{tenantId}/memberships/{accountId}` | Cross-tenant membership lookup for billing/reporting roles (`billingAdmin`/`platformAdmin`) |
| `POST` | `/auth/connect-token` | Issue a short-lived gameplay connect token for one discovery-selected realm target using caller-bound player bootstrap identity after live membership, applicable realm-grant, runtime-entitlement, and admission-pointer validation. It returns `JOIN_REQUIRED` rather than creating public membership. Browser clients receive the token as the `Firemud-Connect-Token` HttpOnly cookie; non-browser clients may receive it in the response body for `X-Firemud-Connect-Token` carriage. |
| `POST` | `/auth/bootstrap/join` | Perform an explicit caller-bound `Join & Play` membership operation for a discovery-selected public production realm; commits membership plus durable audit/outbox before character creation or connect-token issuance |
| `DELETE` | `/tenants/{tenantId}/memberships/me` | Leave a joined game using caller-bound membership authority; removes future membership-based discovery/admission while retained character, purchase, audit, and legal data follow their owning policies |
| `GET` | `/internal/runtime/tenants/{tenantId}/entitlements` | Internal runtime/admission entitlement snapshot for gameplay-affecting services |
| `GET` | `/tenants/{tenantId}/entitlements/me` | Caller-bound tenant-admin entitlement view for billing-safe UX |
| `GET` | `/support/tenants/{tenantId}/entitlements` | Cross-tenant support-safe entitlement view with redacted fields |
| `GET` | `/.well-known/jwks.json` | JWKS for verifying issued JWTs |

Canonical `/auth/login` success shape:

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

Error responses use the standard `shared.v1.ErrorDetail` structure and `AuthenticationErrorCodes`.

## Endpoint Authentication Classes

| Surface | Examples | Required auth path | Notes |
| --- | --- | --- | --- |
| Public auth/bootstrap | `/auth/login`, `/auth/request-password-reset`, `/auth/complete-password-reset`, `/auth/request-email-verification`, `/auth/verify-email`, `/auth/recover-username`, `/.well-known/jwks.json` | No pre-existing user JWT; endpoint-specific validation and abuse controls | Intended for initial auth/bootstrap flows. |
| Player bootstrap issuance | `/auth/player-bootstrap` | First-party player account authentication bootstrap | Issues the `player-bootstrap` token profile only; must not return a control-plane Browser JWT or perform tenant-scoped admission checks. |
| Player bootstrap | `/auth/bootstrap/worlds`, `/auth/bootstrap/worlds/{world}/realms`, `/auth/bootstrap/join`, `/auth/bootstrap/worlds/{world}/realms/{realm}/characters`, `/auth/connect-token` | Short-lived, memory-only `player-bootstrap` token in `Authorization: Bearer ...` | Caller identity is derived from bootstrap auth context. Discovery may show the public production realm before membership exists. `/auth/bootstrap/join` is the only first-party open-enrollment writer; later character and connect-token surfaces require live membership plus applicable grant, entitlement, and pointer checks. Browser connect-token response mode sets the secure HttpOnly cookie. |
| Authenticated account control-plane APIs | `/auth/logout`, `/auth/logout-all`, `/profiles/*`, `/accounts/*/export`, `/accounts/*`, `/tenants/{tenantId}/memberships/me`, `/tenants/{tenantId}/memberships/{accountId}`, `/tenants/{tenantId}/export` | JWT middleware (`AuthTokenInterceptor` + route classification) | Must enforce route class, subject-binding rules, and tenant/global role checks. |
| Internal service gRPC | `Authenticate`, `GetCallerTenantMembership`, `GetTenantMembershipForAccount`, `GetTenantMembershipForRuntime`, `GetTenantEntitlementsForRuntime`, `ListPresenceVisibilityPolicies`, payment and profile gRPC APIs | mTLS caller identity plus method-level auth policy | Internal service surfaces are not edge-exposed directly. |

## Runtime Membership and Entitlement Response Shapes

The internal runtime auth/admission methods must return deterministic response fields because gameplay admission, reconnect, and entitlement freshness rules depend on them.

Illustrative `GetTenantMembershipForRuntime(accountId, tenantId)` response:

```json
{
  "accountId": "acct_123",
  "tenantId": "tenant-demo",
  "gameplayAdmissionAllowed": true,
  "roles": ["player"],
  "membershipVersion": 42,
  "evaluatedAt": "2026-03-13T09:15:30Z"
}
```

Required semantics:

- `gameplayAdmissionAllowed` is the authoritative boolean for gameplay admission.
- `membershipVersion` advances monotonically for the `{accountId, tenantId}` membership scope whenever gameplay-relevant membership or role authority changes.
- `evaluatedAt` timestamps the live membership decision used for admission or resume.
- `GetTenantMembershipForRuntime` is an internal-only gameplay/runtime authority surface and must not be reused as a caller-facing tenant membership endpoint or as a substitute for `GetCallerTenantMembership`.

Illustrative `GetRealmAccessGrant(accountId, tenantId, worldSlug, realmSlug)` response:

```json
{
  "accountId": "acct_123",
  "tenantId": "tenant-demo",
  "worldSlug": "demo",
  "realmSlug": "playtest-docks",
  "accessAllowed": true,
  "grantedByAccountId": "acct_admin_9",
  "grantedAt": "2026-03-13T09:10:00Z",
  "expiresAt": "2026-03-20T09:10:00Z",
  "evaluatedAt": "2026-03-13T09:15:31Z"
}
```

Required semantics:

- Realm-access grants are authoritative only in Account Service; other services must not persist or infer their own non-public realm grant state.
- `accessAllowed=false` is returned when the grant is missing, revoked, or expired.
- Reads are used by bootstrap discovery, in-band `REALMS`, `POST /auth/connect-token`, and `PLAY` for non-public realms; these surfaces must share this authority rather than re-implementing grant logic separately.
- Successful create/revoke operations must be immediately visible to subsequent runtime reads for the same `{accountId, tenantId, realmSlug}`.
- If grant authority is unavailable, discovery/admission for non-public realms fails closed.
- The current implementation has the internal Account Service-owned grant substrate and runtime enforcement in place. Tenant-admin list/grant/revoke APIs, expiry handling, and user-facing account search/selection remain the product control-plane work needed to make the creator playtest journey complete.

Illustrative `GetTenantEntitlementsForRuntime(tenantId)` response:

```json
{
  "tenantId": "tenant-demo",
  "subscriptionStatus": "active",
  "gameplayAvailable": true,
  "allowNewInstanceStarts": true,
  "quotas": {
    "maxActiveSessions": 250,
    "maxConcurrentInstances": 1
  },
  "entitlementVersion": 19,
  "tenantBillingSequence": 311,
  "evaluatedAt": "2026-03-13T09:15:32Z"
}
```

Required semantics:

- `subscriptionStatus` uses the canonical billing lifecycle values (`trialing`, `active`, `past_due`, `grace`, `suspended`, `canceled`).
- `gameplayAvailable` is the admission-critical availability flag consumed by gameplay-affecting services.
- `allowNewInstanceStarts` and quota fields distinguish permission to add load from permission to continue already-entitled capacity.
- `entitlementVersion` identifies the evaluated entitlement snapshot.
- `tenantBillingSequence` allows consumers to detect stale or gapped billing-event application before admitting gameplay.
- `evaluatedAt` records evaluation of authoritative committed input and is used with the differentiated freshness policy from the authentication and subscription-management designs; reading an old projection must not restamp it as fresh.
- Missing subscription state is not implicit availability. Free or trial hosting is returned as an explicit entitlement state.

## Subject-Binding Rules (Normative)

- `GET /profiles/{accountId}`, `PUT /profiles/{accountId}`, `GET /accounts/{accountId}/export`, `DELETE /accounts/{accountId}`, and `POST /accounts/{accountId}/external` are subject-bound account routes:
  - Default rule: `path accountId` must equal authenticated `accountId` from JWT (`sub`/`accountId` claim).
  - Exception: cross-account access is allowed only for explicitly authorized global roles (`platformAdmin`) on routes that explicitly document this override.
  - On mismatch without eligible role, return canonical authorization failure (`AUTH_FORBIDDEN_SUBJECT_MISMATCH` or service-equivalent).
- `GET /accounts/{accountId}/export` is the full account export route. It must not require a caller-selected `tenantId`, and it must not be treated as the billing-safe recovery export for a suspended tenant.
- `GET /accounts/{accountId}/tenant-export?tenantId={tenantId}` is the tenant-scoped billing-safe export route. It requires either the authenticated subject itself or caller-bound tenant operator access via the canonical tenant-access check, remains reachable when the tenant is `suspended` or `canceled`, and returns only data scoped to that `{tenantId, accountId}` pair.
- `DELETE /accounts/{accountId}` is global account deletion. It must reject deletion with canonical error `ACCOUNT_DELETE_ACTIVE_BILLING_OWNER` if the account owns any nonterminal subscription (`trialing`, `active`, `past_due`, `grace`, or `suspended`) in any tenant, and the response should include the affected tenant IDs when safe to disclose to the caller. Platform-admin deletion uses the same billing-owner precondition unless an explicit break-glass compliance workflow is documented separately.
- `GET /tenants/{tenantId}/memberships/me` must ignore any caller-supplied account identifier and always bind subject from authenticated caller context.
- `GET /tenants/{tenantId}/memberships/{accountId}` is cross-subject by design and is restricted to `billingAdmin`/`platformAdmin`; every call must be audit-logged with caller identity and target `{tenantId, accountId}`.

## Billing and Support Variant Contract (Normative)

Billing, entitlement, and subscription APIs must expose distinct route/method variants per authorization class. A single endpoint must not multiplex tenant-safe, support-safe, and cross-tenant billing-safe behavior via optional flags or query parameters.

- Tenant-scoped billing-safe variants (`billing_safe_tenant`) are caller-bound and require live `GetCallerTenantMembership(tenantId)` checks.
- Cross-tenant support-safe variants (`cross_tenant_support_safe`) are separate methods/routes and return only high-level response profiles (status/plan/derived entitlement summaries).
- Cross-tenant billing-safe variants (`cross_tenant_billing_safe`) are separate methods/routes restricted to `billingAdmin`/`platformAdmin` and may include billing-reporting fields.
- Shared response-profile identifiers (`high_level_only`, `billing_reporting`, `membership_self_only`, `membership_reporting`) must be declared in the auth route matrix YAML entry for each variant so CI can enforce redaction tests by class.

## Login Modes

Account Service supports `PASSWORD` and verified-email `EMAIL_OTP` as account-selected ordinary gameplay login modes. Both modes must also be accepted by first-party player bootstrap when enabled for the account. `/auth/login` and internal `Authenticate` accept one login secret; Account Service first recognizes an active eligible email-login code and otherwise verifies a password when that mode is enabled. Authenticator-app enrollment, TOTP, and a separate authentication `otp` field are not mandatory gameplay contracts; stronger factors for elevated control-plane actions are a separate security policy.

## Login Error Codes

Both the `/auth/login` REST endpoint and the gRPC `Authenticate` method return structured `shared.v1.ErrorDetail` responses when authentication fails. Responses use the canonical codes defined in `AuthenticationErrorCodes` so downstream services can rely on stable semantics:

- `AUTH_INVALID_CREDENTIALS` - wrong username or unsupported/invalid login secret
- `AUTH_ACCOUNT_LOCKED` - account suspended or locked by policy (reserved for future enforcement)
- `AUTH_UPSTREAM_FAILURE` - infrastructure/grpc failures before authentication could complete

The Game Session Service translates these codes into text-protocol `ERROR <CODE>` responses so Telnet and WebSocket clients always see consistent login error semantics even when human-facing messages evolve.

Canonical non-login authorization/entitlement errors:

- `MEMBERSHIP_AUTH_UNAVAILABLE` - authoritative membership/role lookup is unavailable for a billing-safe mutation; callers must fail closed.
- `BILLING_SHARED_INSTRUMENT_ACK_REQUIRED` - a tenant-scoped billing-safe mutation attempted to modify an account-shared payment instrument without explicit caller acknowledgement of cross-tenant impact; callers must re-submit only after the acknowledgement field is set.
- `ENTITLEMENT_UNAVAILABLE` - authoritative entitlement snapshot could not be produced at required freshness/sequence guarantees.
- `ACCOUNT_DELETE_ACTIVE_BILLING_OWNER` - account deletion was requested for an account that still owns at least one nonterminal tenant subscription; callers must cancel terminally or transfer billing ownership before retrying deletion.

## Examples

Example account creation request:

```bash
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"secret"}'
```

Example login request:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"username":"demo","password":"secret"}'
```

Call the gRPC method with:

```bash
grpcurl -plaintext localhost:6565 account.v1.AccountService/Ping
```
