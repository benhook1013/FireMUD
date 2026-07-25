# Account Service API Contracts

This document defines the Account Service REST and gRPC contracts, authentication classes, subject-binding rules, and runtime membership and entitlement response semantics.

The authoritative REST schema source lives in [../../../../services/account-service/src/main/resources/openapi.yaml](../../../../services/account-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## Implementation Status

The account lifecycle, full-account export, tenant-scoped export, and deletion precondition contracts below are implemented at the current Account Service boundary. `ExportAccount` and `DeleteAccount` are account-scoped and no longer accept a caller-selected `tenantId`; `ExportTenantData` is the separate tenant-scoped recovery/export surface. Password reset, username reminder, and email-verification tokens are account-scoped rather than tenant-keyed. Explicit `JOIN` / `Join & Play` is not implemented: current connect-token and `PLAY` paths may invoke `EnsurePublicProductionPlayerMembership` implicitly. That is recorded drift; the target contracts below require an explicit join and return `JOIN_REQUIRED` from later admission surfaces when membership is absent. `/auth/player-bootstrap` is account-first and factor-aware, and its token and Account-owned allowlist record do not require tenant selection. The browser connect-token body and OpenAPI response are metadata-only and carry the raw token only in the HttpOnly cookie. Current REST DTOs/OpenAPI still retain numeric account and tenant IDs, so migration to ADR 0020 UUID wire identities remains incomplete.

## gRPC APIs

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `account_service.proto`.
- `CreateAccount` – registers a new user and returns its `accountId` so internal services can establish their own sessions using the authentication flows described below.
- `SendNotification` – deliver account notifications asynchronously.
- `Authenticate` – verifies credentials using a required typed `CredentialSourceContext` and issues the receiver-specific private `game-session-account-delegation` JWT profile with audience `account-service`, backed by one `session:auth:token:<tokenHash>` registry record. The context contains a server-derived canonical client address and transport class; Account rejects absent or untrusted context in player-facing environments, and public callers cannot assert it.
- `RefreshGameplayServiceToken` – rotate the private `game-session-account-delegation` JWT used by an active Game Session binding after validating the presented per-lineage `tokenGeneration`, binding identity, account and membership authority generations, and all applicable live authority state. Replacement issuance must lock or compare-and-set the durable account authority generation so it cannot commit across a concurrent logout-all or equivalent security cutoff. Game Session mTLS identity alone is not refresh authority. After installing the replacement, Game Session uses the Account-owned retirement acknowledgement from this refresh contract to submit the prior token identity, lineage, and retirement request ID. Account finalizes the matching installation immediately, retains the predecessor registry record in `retiring` only through the bounded in-flight cutoff, then removes it idempotently; it replays stored success for the same completed acknowledgement and rejects mismatched or unproved absent-record retirement.
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
- `ListBootstrapCharacters` – list caller-visible characters for the selected signed `connectScopeId` target during first-party gameplay bootstrap; the route world/realm must match the signed scope.
- Bootstrap discovery response contract: for each admissible realm target, return `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`.
  - Required behavior: treat this as a short-lived snapshot proof of the evaluated realm target, not a durable reservation; callers must rerun discovery after `connectScopeExpiresAt` or after stale-scope failures.
- `IssueConnectToken` – issue short-lived gameplay connect token for `/ws/game/**` handshake policy after resolving discovery `connectScopeId`, validating live membership and the current applicable membership authority generation, public admission, runtime entitlements, and the current admission pointer for the target `{tenantId, worldSlug, realmSlug, gameInstanceId}`.
  - Required behavior: `connectScopeId` is an opaque short-lived selector for one caller-visible realm target, must be revalidated against current visibility/grant state and current admission-pointer state at issuance time, and must fail closed with `CONNECT_SCOPE_MISMATCH` or `ADMISSION_POINTER_UNAVAILABLE` when the earlier discovery target is no longer admissible.
  - Required behavior: `requestId` is the idempotency key for issuance. Retrying the same `{accountId, connectScopeId, requestId}` must return the same token payload or the same deterministic application failure. If the caller cannot determine whether Gateway may already have consumed the resulting `gameplay-connect` token, it must rediscover the target and retry with a new `requestId`; it must not reuse the old idempotency key after possible Gateway consumption.
- `EnsurePublicProductionPlayerMembership` – current proto RPC name for the public-production membership seam. Its canonical semantics are the caller's explicit open-enrollment join for the default public production realm, idempotently creating or returning the durable `player` membership and returning its monotonic `membershipVersion`.
  - Required behavior: valid only for the tenant's current default public production realm, must fail closed for non-production realms, must treat `requestId` as the attempt idempotency key, and must emit one durable audit/event record on successful first-join creation.
  - Required failure codes at minimum: `PUBLIC_PRODUCTION_ADMISSION_DENIED`, `ADMISSION_POINTER_UNAVAILABLE`, `TENANT_BILLING_BLOCKED`.
- `GetCallerTenantMembership` – return authoritative caller-bound account-tenant membership and roles for billing-safe mutation checks.
- `GetTenantMembershipForAccount` – cross-tenant membership lookup for billing/reporting workflows (`billingAdmin`/`platformAdmin` only).
- `GetTenantMembershipForRuntime` – authoritative internal membership read for gameplay admission, reconnect/resume, and membership-gap reconciliation.
- `GetTenantEntitlementsForRuntime` – unredacted internal runtime/admission entitlement snapshot for exact Game Session and World Management workload identities. Game Session supplies current private delegation for player-scoped admission; World Management supplies tenant/operation-bound instance-lifecycle context. Logging and Admin and `control-ui` callers must use the redacted route variants instead.
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
| `POST` | `/auth/login` | Authenticate and establish a control-plane session for first-party admin/creator UIs by returning a `control-ui` JWT after its single `session:auth:token:<tokenHash>` registry record exists |
| `POST` | `/auth/logout` | Idempotently delete only the currently presented control-plane or player-bootstrap token record; other devices and unrelated gameplay bindings remain active |
| `POST` | `/auth/logout-all` | Idempotently revoke all control-plane, player-bootstrap, and active gameplay authority for the authenticated account by advancing the Account authority generation and emitting the account-security event; older token records may be cleaned up asynchronously |
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
| `POST` | `/auth/player-bootstrap` | Authenticate a first-party player account and return a short-lived `player-bootstrap` token for gameplay bootstrap only; it is not the `/auth/login` control-ui session |
| `GET` | `/auth/bootstrap/worlds` | List caller-visible worlds for first-party gameplay bootstrap |
| `GET` | `/auth/bootstrap/worlds/{world}/realms` | List caller-visible realms for a selected world during first-party gameplay bootstrap |
| `GET` | `/auth/bootstrap/worlds/{world}/realms/{realm}/characters?connectScopeId={scope}` | List caller-visible characters for the signed realm-discovery target during first-party gameplay bootstrap |
| `POST` | `/auth/bootstrap/worlds/{world}/realms/{realm}/characters` | Account-owned player-bootstrap facade for scoped character creation; delegates persistence to Entity Management after signed-scope and membership/grant checks |
| `POST` | `/internal/runtime/public-production-join` | Target Account-owned boundary used by explicit text-client `JOIN`; its current proto seam is `EnsurePublicProductionPlayerMembership`, while explicit JOIN behavior remains a target gap |
| `GET` | `/internal/runtime/realm-access-grants/{tenantId}/{realmSlug}/{accountId}` | Internal-only authoritative lookup for one non-public realm-access grant |
| `GET` | `/internal/runtime/accounts/{accountId}/realm-access-grants` | Internal-only authoritative listing of the caller's non-public realm-access grants for discovery/admission filtering |
| `POST` | `/tenant-admin/tenants/{tenantId}/realm-access-grants` | Target tenant-admin surface for granting non-public realm visibility/admission to one account |
| `DELETE` | `/tenant-admin/tenants/{tenantId}/realm-access-grants/{realmSlug}/{accountId}` | Target tenant-admin surface for revoking non-public realm visibility/admission without deleting the realm |
| `GET` | `/tenant-admin/tenants/{tenantId}/realm-access-grants` | Target tenant-admin surface for listing and auditing non-public realm grants |
| `GET` | `/profiles/{accountId}` | Retrieve profile information |
| `PUT` | `/profiles/{accountId}` | Update profile information |
| `GET` | `/tenants/{tenantId}/memberships/me` | Authoritative caller-bound membership and roles for billing-safe mutation guards |
| `GET` | `/tenants/{tenantId}/memberships/{accountId}` | Cross-tenant membership lookup for billing/reporting roles (`billingAdmin`/`platformAdmin`) |
| `POST` | `/auth/connect-token` | Target behavior issues a short-lived gameplay connect token for one discovery-selected realm target using caller-bound player-bootstrap identity after current membership authority generation, live membership, applicable realm-grant, runtime-entitlement, and admission-pointer validation. It returns `JOIN_REQUIRED` rather than creating public membership. All first-party gameplay clients receive the connect token only as the `Firemud-Connect-Token` HttpOnly cookie; the response body contains non-secret metadata only and never provides a token or header fallback. |
| `POST` | `/auth/bootstrap/join` | **Target-state only; not implemented at the current Account Service boundary.** Perform an explicit caller-bound `Join & Play` membership operation for a discovery-selected public production realm; commits membership plus durable audit/outbox before character creation or connect-token issuance |
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
      "accountId": "550e8400-e29b-41d4-a716-446655440000",
      "email": "demo@example.com"
    },
    "scopedRoles": {
      "7b3b074e-d597-4e9b-b96f-4f5946d26120": ["tenantAdmin", "designer"],
      "c56a4180-65aa-42ec-a945-5fd21dec0538": ["player"]
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
| Player bootstrap issuance | `/auth/player-bootstrap` | First-party player account authentication bootstrap | Issues the `player-bootstrap` token profile only; must not return a `control-ui` JWT or perform tenant-scoped admission checks. It may share Account's credential verification, abuse controls, and account-state checks with `/auth/login`, but the endpoint, audience, claims, lifetime, and allowed surfaces remain distinct. |
| Player bootstrap | `/auth/bootstrap/worlds`, `/auth/bootstrap/worlds/{world}/realms`, `/auth/bootstrap/join`, `/auth/bootstrap/worlds/{world}/realms/{realm}/characters`, `/auth/connect-token` | Short-lived, memory-only `player-bootstrap` token in `Authorization: Bearer ...` | The implemented bootstrap derives caller identity before tenant selection, then resolves tenant scope through realm discovery. Character discovery and connect-token issuance consume the opaque signed `connectScopeId`, verify the route matches it, and revalidate its tenant/runtime target against current admission state. Discovery may show a public production realm before membership exists in the target; explicit `/auth/bootstrap/join` is the target-state first-party open-enrollment writer and is not implemented, while later character and connect-token surfaces must require live membership plus applicable grant, entitlement, and pointer checks. The connect token is carried only by the secure HttpOnly `Firemud-Connect-Token` cookie; the response body is metadata only. |

| Authenticated account control-plane APIs | `/auth/logout`, `/auth/logout-all`, `/profiles/*`, `/accounts/*/export`, `/accounts/*/tenant-export`, `/accounts/*`, `/tenants/{tenantId}/memberships/me`, `/tenants/{tenantId}/memberships/{accountId}` | JWT middleware (`AuthTokenInterceptor` + route classification) | Must enforce route class, subject-binding rules, and tenant/global role checks. |
| Internal service gRPC | `Authenticate`, `GetCallerTenantMembership`, `GetTenantMembershipForAccount`, `GetTenantMembershipForRuntime`, `GetTenantEntitlementsForRuntime`, `ListPresenceVisibilityPolicies`, payment and profile gRPC APIs | mTLS caller identity plus method-level auth policy | Internal service surfaces are not edge-exposed directly. |

`/auth/login` and `/auth/player-bootstrap` may share the same underlying credential-verification and abuse-policy implementation, but they are separate authentication products. `/auth/login` establishes a `control-ui` control-plane session for admin/creator surfaces; `/auth/player-bootstrap` establishes only the short-lived `player-bootstrap` gameplay-discovery context. Neither endpoint may substitute one profile for the other.

### Logout Retry and Idempotency

`POST /auth/logout` and `POST /auth/logout-all` use one canonical retry envelope. Each request carries a high-entropy caller-generated `requestId`. Account computes and stores a versioned `requestDigest` as the SHA-256 of the normalized operation tuple: `TOKEN_LOGOUT`, the authenticated subject account, the exact token profile (`control-ui` or `player-bootstrap`), and the presented token hash for `/auth/logout`; `ACCOUNT_LOGOUT_ALL`, the authenticated subject account, the exact token profile (`control-ui` or `player-bootstrap`), and the presented token hash for `/auth/logout-all`. Clients do not supply that digest. Raw JWT values must not appear in the digest, durable evidence, logs, or audit records.

- Every logout request binds its `requestId` to that immutable request digest. Reusing a request ID with different meaning, token, profile, account, or scope is rejected as an idempotency conflict.
- Account persists a bounded durable operation record outside the Redis token registry. Before the first registry mutation it commits a `PENDING` record containing the immutable operation ID, request digest, operation kind, subject, and expected token identity. After the idempotent registry mutation and required durable acknowledgement, it advances that same record to `COMMITTED` with the mutation outcome and completed tombstone; validation or idempotency conflicts become terminal `FAILED` records and do not mutate Redis. A completed logout-all also records the durable logout event identity and the account authority generation that superseded the presented token. The evidence is not an authorization grant and contains no raw JWT.
- A retry with the same `requestId` and matching `requestDigest` returns stored success only from `COMMITTED` after the required token profile, time, and subject validation. A `PENDING` record is reconciled or resumed and a `FAILED` record returns its stable failure; neither is reported as success. A per-token logout with no matching registry record and no completed evidence remains denied; the documented no-op exception requires full token validation plus durable evidence of the earlier operation.
- `logout-all` advances the account authority generation at most once for one `requestId`. Retry evidence remains available through the supported idempotency, audit, and cleanup windows.

This `requestId`/request-digest contract is the Account-owned retry boundary for logout and issued-token-registry mutations.

`POST /auth/bootstrap/worlds/{world}/realms/{realm}/characters` is the canonical player-facing character-creation facade. Account derives the caller from the bootstrap token, requires the signed discovery `connectScopeId` to match the route, revalidates that target, then delegates the scoped write to Entity Management `CreateCharacter`; it does not persist character state itself.

The character-creation facade is included in the player-bootstrap authentication class and the Account REST inventory above. It is an Account-owned admission facade, not an Account-owned character persistence surface: Entity Management remains authoritative for the created character and its playable-state scope.

## Runtime Membership and Entitlement Response Shapes

The internal runtime auth/admission methods must return deterministic response fields because gameplay admission, reconnect, and entitlement freshness rules depend on them.

Illustrative `GetTenantMembershipForRuntime(accountId, tenantId)` response:

```json
{
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
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
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "worldSlug": "demo",
  "realmSlug": "playtest-docks",
  "accessAllowed": true,
  "grantedByAccountId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
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
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "subscriptionStatus": "active",
  "gameplayAvailable": true,
  "allowPublicJoin": true,
  "allowNewGameplayBindings": true,
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
- `gameplayAvailable` reports whether already-authorized gameplay may continue; callers must also enforce the operation-specific flag for a new commitment.
- `allowPublicJoin`, `allowNewGameplayBindings`, `allowNewInstanceStarts`, and quota fields distinguish new admission/load from permission to continue already-entitled capacity. In `grace`, general gameplay availability may remain true while all three new-commitment flags are false.
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
- `GET /accounts/{accountId}/tenant-export?tenantId={tenantId}` is the canonical tenant-scoped billing-safe export route. It requires either the authenticated subject itself or caller-bound tenant operator access via the canonical tenant-access check, remains reachable when the tenant is `suspended` or `canceled`, and returns only data scoped to that `{tenantId, accountId}` pair.
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
- `AUTH_RETRY_LATER` - a graduated credential-abuse throttle rejected the attempt; bounded retry metadata is supplied without confirming account existence
- `AUTH_ACCOUNT_LOCKED` - durable account-security policy denies authentication after sufficient identity proof; arbitrary failed attempts must not use this as an account-existence oracle
- `AUTH_ABUSE_CONTROL_UNAVAILABLE` - shared credential-abuse enforcement is unavailable, so new player-facing authentication fails closed and may be retried
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

Target account-first login request:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"secret"}'
```

Call the gRPC method with:

```bash
grpcurl -plaintext localhost:6565 account.v1.AccountService/Ping
```
