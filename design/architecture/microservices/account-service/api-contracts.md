# Account Service API Contracts

This document defines the Account Service REST and gRPC contracts, authentication classes, subject-binding rules, and runtime membership and entitlement response semantics.

The authoritative REST schema source lives in [../../../../services/account-service/src/main/resources/openapi.yaml](../../../../services/account-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## Implementation Status

The account lifecycle API, export surfaces, and deletion-precondition contracts are defined at the current Account Service boundary, but lifecycle state-transition execution remains partial as recorded in [Runtime & Data](./runtime-and-data.md). Account is the authority and transaction boundary for the committed transition, authority-generation/version advance, and durable event/outbox evidence. Downstream revocation, projection, cleanup, and provider reconciliation are ordered transition execution; partial downstream completion must be reported separately and must not be mistaken for a rolled-back lifecycle state or completed enforcement outcome. The current `GET /accounts/{accountId}/export` and `ExportAccount` implementation is Account/profile-local; the canonical target full-export lifecycle is `POST /accounts/{accountId}/exports` followed by the stable status and content resources defined below. `ExportTenantData` targets the separate tenant-admin route `GET /tenant-admin/tenants/{tenantId}/export`, which is tenant-wide and selects no account subject; the current account-targeted gRPC and legacy tenant-export wiring remain implementation drift. Password reset, username reminder, and email-verification tokens are account-scoped rather than tenant-keyed. Profiles are tenant-scoped under ADR 0042, so the current account-only `/profiles/{accountId}` route/classification remains implementation drift pending direct convergence on tenant-qualified routes. Explicit `JOIN` / `Join & Play` is not implemented. Connect-token issuance may still invoke `EnsurePublicProductionPlayerMembership` implicitly, while text `PLAY` now returns `JOIN_REQUIRED` when public-production membership is absent. That remaining writer is recorded drift; the target contracts below require an explicit join and the same fail-closed result from every later admission surface. `/auth/player-bootstrap` is account-first and factor-aware, and the browser connect-token body and OpenAPI response are metadata-only and carry the raw token only in the HttpOnly cookie. The registry-backed issuance, refresh, revocation, durable retry, and authority-generation behaviors below are target state, including the contracts for `Authenticate`, `RefreshGameplayServiceToken`, `/auth/player-bootstrap`, `/auth/logout`, and `/auth/logout-all`; `/auth/login` is currently routable but still lacks the complete registry-backed target behavior. Current Account sessions use the legacy `session:auth:account:<accountId>:<tokenHash>` key and, for tenant-scoped sessions, the companion `session:auth:tenant:<tenantId>:<tokenHash>` key rather than the canonical single `session:auth:token:<tokenHash>` registry record. Current REST DTOs/OpenAPI still retain numeric account and tenant IDs, so migration to ADR 0020 UUID wire identities remains incomplete. Scoped-role population and tenant-switching proof are also target-state work: current `authenticate` issuance provides `globalRoles` but does not yet populate or prove `scopedRoles[tenantId]` as authorization evidence.

## gRPC APIs

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `account_service.proto`.
- `CreateAccount` – registers a new user and returns its `accountId` so internal services can establish their own sessions using the authentication flows described below.
- `SendNotification` – deliver account notifications asynchronously.
- `Authenticate` – verifies credentials using a required, typed `CredentialSourceContext` and, only for the approved Game Session-to-Account control path, issues the receiver-specific private `game-session-account-delegation` JWT with exact audience `account-service`. The target registry-backed flow stores one `session:auth:token:<tokenHash>` record; current issuance still uses the legacy account/tenant session keys identified above. Generic `Service JWT` and `aud=internal` profiles are forbidden; workload-only authorization uses mTLS. The context contains a server-derived canonical client address and transport class; Account rejects absent or untrusted context in player-facing environments, and public callers cannot assert it.
- `RefreshGameplayServiceToken` – rotates the private `game-session-account-delegation` JWT used by an active Game Session binding after validating its exact `account-service` audience, the presented current per-lineage `tokenGeneration`, captured applicable issuer/account/tenant/membership authority generations, binding identity, account state, current membership, and all applicable live authority state. Replacement issuance must lock or compare-and-set the durable account authority generation so it cannot commit across a concurrent logout-all or equivalent security cutoff. Game Session mTLS identity alone is not refresh authority. Account first creates the replacement as non-authorizing `pending`, commits the matching durable rotation operation, and atomically marks the replacement registry record `active` while moving the predecessor to `retiring` before exposing the response. The rotated private token remains non-authorizing until the exact Account-owned installation lease and binding-installation fence are durably `INSTALLED`; mismatched or unproved retirement acknowledgements fail closed.
- `IssueOperatorAuthorization` – target Account-owned issuance RPC used only by the exact Logging & Admin workload while forwarding the authenticated operator's `control-ui` JWT. It validates the current actor, required role and assurance, derives the actor account and authority generation rather than trusting request-supplied identity, and issues one opaque bounded reference for the requested owner, action family, target scope, `controlPlaneRequestId`, and `mutationDigest`.
- `RedeemOperatorAuthorization` – target Account-owned redemption RPC used only by the exact owner workload named in the issued record. The owner presents the opaque reference plus its independently derived action family, target scope, `controlPlaneRequestId`, and canonical payload `mutationDigest`; Account returns the bounded actor/authorization projection only when every value and current authority state still match.
- `GetProfile` – retrieves the caller's profile relationship for one explicit tenant through the tenant-scoped `tenant_regular` route. It requires the exact `control-ui` profile, live membership, one of `player`, `moderator`, `designer`, or `tenantAdmin` in `scopedRoles[tenantId]`, and `accountId` bound to `caller_account_id`; `platformAdmin` is not an override.
- `UpdateProfile` – modifies the caller's profile relationship for one explicit tenant under the same tenant-scoped `tenant_regular` authorization and caller-subject binding; it requires one of `player`, `moderator`, `designer`, or `tenantAdmin` in `scopedRoles[tenantId]` and triggers notification emails. Account holders may select `PUBLIC`, `FRIENDS_ONLY`, or `PRIVATE` presence visibility; `HIDDEN_STAFF` remains reserved for the staff-visibility owner and cannot be set through ordinary profile writes. `platformAdmin` is not an override.
- `ListPresenceVisibilityPolicies` – bounded internal bulk read of current tenant-scoped profile visibility policy for up to 100 account IDs. Social projections consume this authority at read time; unknown or unavailable entries are intentionally omitted so callers apply complete `PRIVATE` redaction.
- `ExportAccount` – starts or reads an asynchronous versioned full-subject export manifest across all owning services. Complete, partial, omitted, redacted, retryable, and separately retained owner contributions remain explicit in the manifest.
- `ExportTenantData` – tenant-wide billing-safe export for one tenant, available to the caller's live `tenantAdmin` membership while gameplay is billing-blocked and limited to tenant-owned exportable records plus minimum stable subject references. The target request is tenant-only; the current account-targeted gRPC request is implementation drift.
- `DeleteAccount` – begins or completes global account deletion according to the account lifecycle state machine; it is not a tenant-scoped membership deletion.
- Pending-deletion access is a separate opaque Account credential, not a JWT or normal account session. The target routes are `GET /accounts/{accountId}/deletion`, `POST /accounts/{accountId}/deletion/cancel`, the full-subject export lifecycle `POST /accounts/{accountId}/exports`, `GET /accounts/{accountId}/exports/{exportId}`, and `GET /accounts/{accountId}/exports/{exportId}/content`, plus `POST /accounts/{accountId}/deletion/billing-settlement`. Each is classified `pending_deletion_scoped` for its exact action and bound to the Account-owned deletion workflow registry. These target routes are not currently routable.
- `POST /auth/pending-deletion/recovery/challenge` and `POST /auth/pending-deletion/recovery` are dedicated target recovery routes classified `public`; they may issue only `pending-deletion-access`, never normal login, bootstrap, connect-token, tenant, purchase, or gameplay authority. These target routes are not currently routable.
- `RequestPasswordReset` – initiate a password reset email.
- `CompletePasswordReset` – update the password using a token.
- Provider-specific external identity linking is planned but not currently routable. Each provider must use a server-verified callback, global `{provider, issuer, subject}` uniqueness, recent reauthentication, and safe unlink/recovery behavior before its dedicated surface is advertised; the caller-asserted `LinkExternalAccount` scaffold is unsupported drift.
- `IssuePlayerBootstrapToken` – authenticate a first-party player account and issue the short-lived `player-bootstrap` token profile used only for gameplay bootstrap.
- `ListBootstrapWorlds` – list caller-visible worlds for first-party gameplay bootstrap.
- `ListBootstrapRealms` – list caller-visible realms for a selected world during first-party gameplay bootstrap.
- `ListBootstrapCharacters` – list caller-visible characters for the selected opaque server-issued `connectScopeId` target during first-party gameplay bootstrap; the route world/realm must match the resolved scope.
- Bootstrap discovery response contract: for each admissible realm target, return `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`.
  - Required behavior: treat this as a short-lived snapshot proof of the evaluated realm target, not a durable reservation; callers must rerun discovery after `connectScopeExpiresAt` or after stale-scope failures.
- `IssueConnectToken` – issue short-lived gameplay connect token for `/ws/game/**` handshake policy after resolving discovery `connectScopeId`, validating live membership and the current applicable membership authority generation, public admission, runtime entitlements, and the current admission pointer for the target `{tenantId, worldSlug, realmSlug, gameInstanceId}`.
  - Required behavior: `connectScopeId` is an opaque short-lived selector for one caller-visible realm target, must be revalidated against current visibility/grant state and current admission-pointer state at issuance time, and must fail closed with `CONNECT_SCOPE_MISMATCH` or `ADMISSION_POINTER_UNAVAILABLE` when the earlier discovery target is no longer admissible.
  - Required behavior: read shared replay readiness as `OPEN`, bind the exact `replayAdmissionFence` into the token, and fail with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` when readiness is missing, unreadable, quarantined, or changes during issuance.
  - Required behavior: `requestId` is the idempotency key for issuance. Retrying the same `{accountId, connectScopeId, requestId}` must return the same token payload or the same deterministic application failure. If the caller cannot determine whether Gateway may already have consumed the resulting `gameplay-connect` token, it must rediscover the target and retry with a new `requestId`; it must not reuse the old idempotency key after possible Gateway consumption.
- `EnsurePublicProductionPlayerMembership` – current proto RPC name for the public-production membership seam. Its canonical target semantics are the caller's explicit open-enrollment join selected by verified `connectScopeId`, idempotently creating or returning the durable `player` membership and returning its monotonic `membershipVersion`.
  - Required behavior: consume the verified caller-bound `connectScopeId` plus `requestId`, resolve and revalidate the tenant/world/realm/game-instance/pointer target at the Account commit gate, permit only the tenant's current default public production realm, fail closed for non-production realms, bind `requestId` to the resolved operation/account/target identity, validate fresh entitlement atomically at the membership commit boundary, and emit one durable audit/event record on successful first-join creation. Independently supplied account, tenant, world, or realm values are not player-path authority.
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

### Operator Authorization References

`IssueOperatorAuthorizationRequest` contains the target owner service, typed target scope, action family, `controlPlaneRequestId`, and canonical `mutationDigest`. Actor identity is not a request field: Account derives it from the validated `control-ui` token forwarded by the exact Logging & Admin mTLS workload. Account chooses a short expiry and stores only a hash of the high-entropy opaque reference, bound to the actor account and authority generation, required role and assurance, issuer and redeemer workload identities, target owner/scope, action family, request identity, digest, issue time, expiry, and revocation state. The response contains only `operatorAuthorizationReference` and `expiresAt`.

`RedeemOperatorAuthorizationRequest` contains the opaque reference and the owner-recomputed target owner/scope, action family, `controlPlaneRequestId`, and `mutationDigest`. Account authenticates the exact mTLS caller and requires it to equal the recorded redeemer. A matching retry before expiry returns the same bounded actor and authorization projection; redemption is not destructively consumed because the owner may need to recover a lost response. A different caller, action, scope, request identifier, or digest cannot reuse the reference.

Malformed references return `OPERATOR_AUTH_REFERENCE_INVALID`; expiry returns `OPERATOR_AUTH_REFERENCE_EXPIRED`; explicit revocation returns `OPERATOR_AUTH_REFERENCE_REVOKED`; owner mismatch returns `OPERATOR_AUTH_REFERENCE_CALLER_MISMATCH`; action, scope, request, or digest mismatch returns `OPERATOR_AUTH_REFERENCE_BINDING_MISMATCH`; and changed actor authority, role, assurance, or account state returns `OPERATOR_AUTH_REFERENCE_AUTHORITY_STALE`. These are application-level `ErrorDetail` outcomes and never authorize the owner mutation. Transport uncertainty remains ambiguous: callers retry the same issue or redeem identity and never mint a replacement `controlPlaneRequestId` for the same mutation attempt. Raw references must not appear in logs, audit records, or mutation digests. Logging & Admin may retain the reference only in encrypted bounded-delivery state until expiry so it can recover a lost owner response; the owner redeems it immediately and does not persist it.

These RPCs and schemas are accepted target state but are not present in the current Account proto or implementation. Logging & Admin remains the external operator ingress, Account remains the authorization authority, and Game Session remains the domain mutation owner.

## REST APIs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/ping` | Simple health check |
| `POST` | `/auth/login` | **Current route; partial target behavior.** Authenticate the global platform account and return a `control-ui` token without authoritative tenant selection. Registry-backed issuance and complete scoped-role authority remain target gaps. |
| `POST` | `/auth/logout` | **Target-state only; not currently routable.** Idempotently delete only the currently presented control-plane or player-bootstrap token record; other devices and unrelated gameplay bindings remain active. |
| `POST` | `/auth/logout-all` | **Target-state only; not currently routable.** Idempotently revoke all control-plane, player-bootstrap, and active gameplay authority for the authenticated account by advancing the Account authority generation, projecting `session:auth:generation:account:<accountId>` set-if-greater, and emitting the account-security event; older token records may be cleaned up asynchronously. |
| `POST` | `/auth/request-password-reset` | Request account-scoped password reset |
| `POST` | `/auth/complete-password-reset` | Complete account-scoped password reset |
| `POST` | `/auth/request-email-verification` | Send account-scoped verification email |
| `POST` | `/auth/verify-email` | Verify account-scoped email token |
| `POST` | `/auth/recover-username` | Send account-scoped username reminder |
| `POST` | `/accounts` | Create a new user account |
| `GET` | `/accounts/{accountId}/export` | **Current legacy route.** Returns an Account/profile-local export and does not satisfy ADR 0050. |
| `POST` | `/accounts/{accountId}/exports` | **Target-state only.** Initiate or idempotently replay one asynchronous full-subject export and return its stable `exportId` and status resource. |
| `GET` | `/accounts/{accountId}/exports/{exportId}` | **Target-state only.** Read durable export status, owner contribution state, and the versioned manifest. |
| `GET` | `/accounts/{accountId}/exports/{exportId}/content` | **Target-state only.** Download the completed export artifact. |
| `GET` | `/accounts/{accountId}/deletion` | **Target-state only.** Read `pending_deletion_scoped` workflow status using the Account-owned pending-deletion credential. |
| `POST` | `/accounts/{accountId}/deletion/cancel` | **Target-state only.** Cancel eligible pending deletion and establish a fresh normal account generation after the dedicated recovery proof. |
| `POST` | `/accounts/{accountId}/deletion/billing-settlement` | **Target-state only.** Perform only the necessary billing-settlement action allowed by the pending-deletion workflow. |
| `GET` | `/tenant-admin/tenants/{tenantId}/export` | **Target-state only; not currently routable.** Tenant-wide billing-safe export of tenant-owned data for a live `tenantAdmin`; no account subject selector. |
| `DELETE` | `/accounts/{accountId}` | Delete the global account after billing-owner preconditions pass |
| Provider-specific callback route (future) | Not yet routable | Link one server-verified provider identity after that provider's complete contract and proof exist; the caller-asserted `/accounts/{accountId}/external` route is unsupported |
| `POST` | `/auth/player-bootstrap` | Current endpoint; **target registry-backed behavior:** return a short-lived `player-bootstrap` token for gameplay bootstrap only after Account has atomically established exactly one active issued-token registry record; registry failure fails issuance before the token is exposed, and the token is not the `/auth/login` control-ui session |
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
| `GET` | `/tenants/{tenantId}/profiles/{accountId}` | **Target tenant-qualified route.** The current implementation remains `/profiles/{accountId}` and is known drift. |
| `PUT` | `/tenants/{tenantId}/profiles/{accountId}` | **Target tenant-qualified route.** The current implementation remains `/profiles/{accountId}` and is known drift. |
| `GET` | `/tenants/{tenantId}/memberships/me` | Authoritative caller-bound membership and roles for billing-safe mutation guards |
| `GET` | `/tenants/{tenantId}/memberships/{accountId}` | Cross-tenant membership lookup for billing/reporting roles (`billingAdmin`/`platformAdmin`) |
| `POST` | `/auth/connect-token` | Target behavior issues a short-lived gameplay connect token for one discovery-selected realm target using caller-bound player-bootstrap identity after current membership authority generation, live membership, applicable realm-grant, runtime-entitlement, and admission-pointer validation. It returns `JOIN_REQUIRED` rather than creating public membership. HTTP response delivery uses only the protected `Set-Cookie` field: the response body and custom response headers never expose the raw token or provide a header fallback. First-party browser/mobile/server clients retain that protected cookie; an explicitly classified non-first-party generic WebSocket client may carry the same cookie-jar value in the dedicated handshake header, which is transport carriage rather than Account response delivery. |
| `POST` | `/auth/bootstrap/join` | **Target-state only; not implemented at the current Account Service boundary.** Perform an explicit caller-bound `Join & Play` membership operation for a discovery-selected public production realm; commits membership plus durable audit/outbox before character creation or connect-token issuance |
| `DELETE` | `/tenants/{tenantId}/memberships/me` | Leave a joined game using caller-bound membership authority; removes future membership-based discovery/admission while retained character, purchase, audit, and legal data follow their owning policies |
| `GET` | `/internal/runtime/tenants/{tenantId}/entitlements` | Internal runtime/admission entitlement snapshot for gameplay-affecting services |
| `GET` | `/tenants/{tenantId}/entitlements/me` | Caller-bound tenant-admin entitlement view for billing-safe UX |
| `GET` | `/support/tenants/{tenantId}/entitlements` | Cross-tenant support-safe entitlement view with redacted fields |
| `GET` | `/.well-known/jwks.json` | JWKS for verifying issued JWTs |

The current executable REST and internal response examples use numeric account and tenant IDs. UUID wire identities are an ADR 0020 target-state migration and are not current executable examples.

Canonical current `/auth/login` success shape:

```json
{
  "status": "SUCCESS",
  "data": {
    "accountId": 42,
    "authToken": "jwt-token-here"
  }
}
```

Error responses use the standard `shared.v1.ErrorDetail` structure and `AuthenticationErrorCodes`.

### Control-Plane Tenant Selection

`/auth/login` accepts account credentials, not a `tenantId`. The target `control-ui` token identifies the global account and may carry global roles plus roles scoped to multiple tenants; current issuance provides global roles only, as recorded in the implementation status. A first-party UI may remember a selected tenant for navigation, but that state is not authorization evidence and switching it does not require a new token.

Every tenant-targeted request identifies its tenant in the route or request contract and independently enforces the `control-ui` profile, account subject, route class, scoped role, current membership generation, and applicable account or tenant authority generation. Global-role operations use explicit global or cross-tenant route classes and never derive authority from the UI's selected tenant. Gameplay discovery and admission continue to use the separate `player-bootstrap` profile.

## Endpoint Authentication Classes

| Surface | Examples | Required auth path | Notes |
| --- | --- | --- | --- |
| Public auth/bootstrap | `/auth/login`, `/auth/request-password-reset`, `/auth/complete-password-reset`, `/auth/request-email-verification`, `/auth/verify-email`, `/auth/recover-username`, `/.well-known/jwks.json` | No pre-existing user JWT; endpoint-specific validation and abuse controls | Intended for initial auth/bootstrap flows. |
| Player bootstrap issuance | `/auth/player-bootstrap` | First-party player account authentication bootstrap | **Target state:** issues the `player-bootstrap` token profile only after atomically creating its one active `session:auth:token:<tokenHash>` registry record; registry failure fails issuance before exposure. It must not return a `control-ui` JWT or perform tenant-scoped admission checks. It may share Account's credential verification, abuse controls, and account-state checks with `/auth/login`, but the endpoint, audience, claims, lifetime, and allowed surfaces remain distinct. **Current implementation:** uses the legacy `session:auth:account:<accountId>:<tokenHash>` Account session key rather than the ADR 0035 registry shape. |
| Player bootstrap | `/auth/bootstrap/worlds`, `/auth/bootstrap/worlds/{world}/realms`, `/auth/bootstrap/join`, `/auth/bootstrap/worlds/{world}/realms/{realm}/characters`, `/auth/connect-token` | Target first-party flow: short-lived, memory-only `player-bootstrap` token in `Authorization: Bearer ...` | The implemented bootstrap derives caller identity before tenant selection, then resolves tenant scope through realm discovery. Character discovery and connect-token issuance consume the opaque server-issued `connectScopeId`, verify the route matches it, and revalidate its tenant/runtime target against current admission state. Discovery may show a public production realm before membership exists in the target; explicit `/auth/bootstrap/join` is the target-state first-party open-enrollment writer and is not implemented, while later character and connect-token surfaces must require live membership plus applicable grant, entitlement, and pointer checks. `POST /auth/connect-token` revalidates the current caller-bound membership authority generation. Account delivers the connect token only through the protected `Firemud-Connect-Token` `Set-Cookie` field; the HTTP response body and custom response-header fields are metadata-only. Only an explicitly classified non-first-party generic WebSocket client may carry that cookie-jar value in the dedicated handshake header. |

`POST /auth/bootstrap/worlds/{world}/realms/{realm}/characters` is the canonical player-facing character-creation facade. Account derives the caller from the bootstrap token, resolves and authorizes the selected realm, then delegates the scoped write to Entity Management `CreateCharacter`; it does not persist character state itself.

| Authenticated account control-plane APIs | Target `/auth/logout`, `/auth/logout-all`, `/tenants/{tenantId}/profiles/{accountId}`, active-account `/accounts/{accountId}/exports/**`, `/accounts/{accountId}`, `/tenants/{tenantId}/memberships/me`, `/tenants/{tenantId}/memberships/{accountId}`, `/tenant-admin/tenants/{tenantId}/export` | Exact `control-ui` profile through JWT middleware (`AuthTokenInterceptor` + route classification) | This is the target authentication class only while the account remains eligible for ordinary authority. The REST inventory identifies which routes are currently implemented and which remain target-only. |
| Pending-deletion access | `/accounts/{accountId}/deletion`, `/accounts/{accountId}/deletion/cancel`, pending-account `/accounts/{accountId}/exports/**`, `/accounts/{accountId}/deletion/billing-settlement` | Opaque `pending-deletion-access` credential through the Account-owned workflow registry | Target-only exact `pending_deletion_scoped` route classification. Once the account enters pending deletion, this row takes precedence over ordinary control-plane classification: `control-ui` authority is revoked and cannot access export or any other pending-deletion route. |
| Internal service gRPC | `Authenticate`, `GetCallerTenantMembership`, `GetTenantMembershipForAccount`, `GetTenantMembershipForRuntime`, `GetTenantEntitlementsForRuntime`, `ListPresenceVisibilityPolicies`, payment and profile gRPC APIs | mTLS caller identity plus method-level auth policy | Internal service surfaces are not edge-exposed directly. |

`/auth/login` and `/auth/player-bootstrap` may share the same underlying credential-verification and abuse-policy implementation, but they are separate authentication products. `/auth/login` establishes a `control-ui` control-plane session for admin/creator surfaces; `/auth/player-bootstrap` establishes only the short-lived `player-bootstrap` gameplay-discovery context. Neither endpoint may substitute one profile for the other.

### Logout Retry and Idempotency

**Target state:** `POST /auth/logout` and `POST /auth/logout-all` use one canonical retry envelope. Each request carries a high-entropy caller-generated `requestId`. Account computes and stores a versioned `requestDigest` as the SHA-256 of the normalized operation tuple: `TOKEN_LOGOUT`, the authenticated subject account, the exact token profile (`control-ui` or `player-bootstrap`), and the presented token hash for `/auth/logout`; `ACCOUNT_LOGOUT_ALL`, the authenticated subject account, the exact presented token profile, and the presented token hash for `/auth/logout-all`. Clients do not supply that digest. Raw JWT values must not appear in the digest, durable evidence, logs, or audit records. The current legacy Account session-key implementation does not yet provide this durable retry proof.

- Every logout request binds its `requestId` to that immutable request digest. Reusing a request ID with different meaning, token, profile, account, or scope is rejected as an idempotency conflict.
- Account persists a bounded durable operation record outside the Redis token registry. For `TOKEN_LOGOUT`, the same Account transaction that commits the `PENDING` operation first advances the exact-token monotonic fence to `PENDING_LOGOUT`, bound to the request ID, digest, and exact token identity; that durable fence is authoritative before any Redis mutation. Refresh, rebind, installation, and reconciliation compare-and-set paths must validate the current fence and cannot recreate or admit the logged-out identity. Only then does Account perform the idempotent registry mutation and, after required durable acknowledgement, advance the matching operation and fence to `COMMITTED` with the mutation outcome and completed tombstone. Validation or idempotency conflicts become terminal `FAILED` records and do not mutate Redis. A completed logout-all also records the durable logout event identity and the account authority generation that superseded the presented token. The evidence is not an authorization grant and contains no raw JWT.
- A retry with the same `requestId` and matching `requestDigest` returns stored success only from `COMMITTED` after the required token profile, time, and subject validation. A `PENDING` record is reconciled or resumed and a `FAILED` record returns its stable failure; neither is reported as success. A per-token logout with no matching registry record and no completed evidence remains denied; the documented no-op exception requires full token validation plus durable evidence of the earlier operation.
- `logout-all` compares and sets the expected Account authority generation in the same Account transaction that commits the operation outcome, one account-security event, one audit record, and one outbox entry. That event invalidates all Account-issued `game-session-account-delegation` lineages and instructs Game Session to terminate the account's active gameplay bindings through its idempotent account-wide index/reconciliation path. It advances the generation and emits those durable records at most once for one `requestId`; retries with the same digest return the stored outcome, while a digest conflict is rejected.

This `requestId`/request-digest contract is the Account-owned retry boundary for logout and issued-token-registry mutations.

`POST /auth/bootstrap/worlds/{world}/realms/{realm}/characters` is the canonical player-facing character-creation facade. Account derives the caller from the bootstrap token, requires the opaque server-issued discovery `connectScopeId` to match the route, revalidates that target, then delegates the scoped write to Entity Management `CreateCharacter`; it does not persist character state itself.

The character-creation facade is included in the player-bootstrap authentication class and the Account REST inventory above. It is an Account-owned admission facade, not an Account-owned character persistence surface: Entity Management remains authoritative for the created character and its playable-state scope.

## Runtime Membership and Entitlement Response Shapes

The internal runtime auth/admission methods must return deterministic response fields because gameplay admission, reconnect, and entitlement freshness rules depend on them.

Illustrative `GetTenantMembershipForRuntime(accountId, tenantId)` response:

```json
{
  "accountId": 42,
  "tenantId": 7,
  "membershipExists": true,
  "gameplayAdmissionAllowed": true,
  "roles": ["player"],
  "membershipVersion": 42,
  "membershipAuthorityGeneration": 8,
  "evaluatedAt": "2026-03-13T09:15:30Z"
}
```

Required semantics:

- `membershipExists` is the authoritative presence signal. Callers must not infer absence from `gameplayAdmissionAllowed=false` or a sentinel membership version.
- `gameplayAdmissionAllowed` is the authoritative boolean for gameplay admission when membership exists; `false` may represent either an absent membership or an existing denied/revoked membership and must be interpreted with `membershipExists`.
- `membershipVersion` advances monotonically for the `{accountId, tenantId}` membership scope whenever gameplay-relevant membership or role authority changes.
- `membershipAuthorityGeneration` advances whenever membership or tenant-role authority issued to the caller must be invalidated; it is the authority fence for caller-bound tokens and control-plane admission, and is distinct from the membership content/version counter.
- `evaluatedAt` timestamps the live membership decision used for admission or resume.
- `GetTenantMembershipForRuntime` is an internal-only gameplay/runtime authority surface and must not be reused as a caller-facing tenant membership endpoint or as a substitute for `GetCallerTenantMembership`.

Illustrative `GetRealmAccessGrant(accountId, tenantId, worldSlug, realmSlug)` response:

```json
{
  "accountId": 42,
  "tenantId": 7,
  "worldSlug": "demo",
  "realmSlug": "playtest-docks",
  "accessAllowed": true,
  "grantedByAccountId": 84,
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
  "tenantId": 7,
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

- `GET /tenants/{tenantId}/profiles/{accountId}` and `PUT /tenants/{tenantId}/profiles/{accountId}` are tenant-qualified, caller-subject-bound profile routes. They require live membership and the path `accountId` must equal the authenticated account; global roles do not broaden these routes. Cross-account moderation/support views require separate minimized contracts.
- The full-subject `/accounts/{accountId}/exports/**` lifecycle and `DELETE /accounts/{accountId}` are subject-bound account routes:
  - Default rule: `path accountId` must equal authenticated `accountId` from JWT (`sub`/`accountId` claim).
  - Exception: cross-account access is allowed only for explicitly authorized global roles (`platformAdmin`) on routes that explicitly document this override.
  - On mismatch without eligible role, return canonical authorization failure (`AUTH_FORBIDDEN_SUBJECT_MISMATCH` or service-equivalent).
- The target `/accounts/{accountId}/exports/**` lifecycle is the full account export contract. It must not require a caller-selected `tenantId`, and it must not be treated as the billing-safe recovery export for a suspended tenant. The current singular `/accounts/{accountId}/export` route is Account/profile-local drift.
- Full export is asynchronous and versioned. Its manifest names every required owning-service contribution and schema version, exposes partial/retryable owner failures, and explicitly records redacted, omitted, unavailable, and separately retained categories rather than silently dropping them.
- `GET /tenant-admin/tenants/{tenantId}/export` is the canonical tenant-wide billing-safe export route. It requires the exact `control-ui` profile plus the caller's live `tenantAdmin` membership for the path `tenantId`, remains reachable when that tenant is `suspended` or `canceled`, and returns only tenant-owned exportable records with minimum stable subject references. It has no `accountId` selector and is not subject-bound to one account.
- `DELETE /accounts/{accountId}` requests global account deletion after recent authentication and explicit confirmation and must offer a clear full-account export path. It rejects with canonical error `ACCOUNT_DELETE_ACTIVE_BILLING_OWNER` if the account owns any nonterminal subscription (`trialing`, `active`, `past_due`, `grace`, or `suspended`) in any tenant, and returns every affected tenant ID when safe to disclose. An eligible request enters `deactivated_pending_delete`; it does not synchronously hard-delete the account or claim terminal erasure. Platform-admin deletion uses the same billing-owner precondition unless an explicit break-glass compliance workflow is documented separately.
- Pending-deletion status, cancel, export, and necessary billing-settlement access use only the separate Account-owned pending-deletion credential and remain unavailable to ordinary `control-ui`/`player-bootstrap` login paths while pending. The credential is revoked on cancel, terminal completion, or expiry; a dedicated recovery challenge can issue a replacement pending credential only.
- `POST /auth/login` and `POST /auth/player-bootstrap` perform live account-state eligibility checks and reject `deactivated_pending_delete`; pending-deletion access cannot be upgraded into normal `control-ui` or `player-bootstrap` authority.
- `GET /tenants/{tenantId}/memberships/me` must ignore any caller-supplied account identifier and always bind subject from authenticated caller context.
- `GET /tenants/{tenantId}/memberships/{accountId}` is cross-subject by design and is restricted to `billingAdmin`/`platformAdmin`; every call must be audit-logged with caller identity and target `{tenantId, accountId}`.

## Billing and Support Variant Contract (Normative)

Billing, entitlement, and subscription APIs must expose distinct route/method variants per authorization class. A single endpoint must not multiplex tenant-safe, support-safe, and cross-tenant billing-safe behavior via optional flags or query parameters.

- Tenant-scoped billing-safe variants (`billing_safe_tenant`) are caller-bound and require live `GetCallerTenantMembership(tenantId)` checks.
- Cross-tenant support-safe variants (`cross_tenant_support_safe`) are separate methods/routes and return only high-level response profiles (status/plan/derived entitlement summaries).
- Cross-tenant billing-safe variants (`cross_tenant_billing_safe`) are separate methods/routes restricted to `billingAdmin`/`platformAdmin` and may include billing-reporting fields.
- Shared response-profile identifiers (`high_level_only`, `billing_reporting`, `membership_self_only`, `membership_reporting`) must be declared in the auth route matrix YAML entry for each variant so CI can enforce redaction tests by class.

## Login Modes

Account Service supports `PASSWORD` and verified-email `EMAIL_OTP` as account-selected ordinary login modes. Both modes must also be accepted by first-party player bootstrap when enabled for the account. `/auth/login` and internal `Authenticate` accept one login secret; Account Service first recognizes an active eligible email-login code and otherwise verifies a password when that mode is enabled. Authenticator TOTP and a separate `otp` field are not ordinary gameplay contracts; TOTP is target-state only for entering bounded `platformAdmin` or cross-tenant `billingAdmin` elevation.

### Sensitive Actions and Gameplay Handoff

- Account identity/factor changes, new real-money charges, saved payment-instrument changes, global deletion, billing-owner transfer, and global administration are HTTPS-only account/control operations. Clients may be web, native, or CLI, but a Telnet gameplay connection cannot complete them or receive elevated authority.
- Sensitive personal and billing mutations require recent ordinary reauthentication. Global privileged elevation additionally requires independent TOTP once per bounded elevated window, not once per action.
- A gameplay client may explicitly initiate an eligible action and receive an opaque, short-lived, single-use HTTPS URL. Its server-side intent binds the initiating account, gameplay session, tenant when applicable, exact action, product and immutable amount/currency when applicable, and `requestId`. The URL contains no account, payment, or factor secret and grants no completion authority without independent HTTPS authentication and any required provider flow.
- Account records only a verified, idempotent completion. Gameplay may poll or receive the resulting success, failure, expiry, or cancellation but cannot approve it.
- Existing non-withdrawable premium-balance spending remains a confirmed, capped, idempotent gameplay mutation rather than a new real-money charge. Cash redemption, withdrawal, or cash-equivalent transfer is outside this contract.

## Login Error Codes

Both the `/auth/login` REST endpoint and the gRPC `Authenticate` method return structured `shared.v1.ErrorDetail` responses when authentication fails. Responses use the canonical codes defined in `AuthenticationErrorCodes` so downstream services can rely on stable semantics:

- `AUTH_INVALID_CREDENTIALS` - wrong username or unsupported/invalid login secret
- `AUTH_RETRY_LATER` - a graduated credential-abuse throttle rejected the attempt; bounded retry metadata is supplied without confirming account existence
- `AUTH_ACCOUNT_LOCKED` - durable account-security policy denies authentication after sufficient identity proof; arbitrary failed attempts must not use this as an account-existence oracle
- `AUTH_ABUSE_CONTROL_UNAVAILABLE` - shared credential-abuse enforcement is unavailable, so new player-facing authentication fails closed and may be retried
- `AUTH_UNAVAILABLE` - authentication-authority or infrastructure/gRPC failures before authentication could complete

The Game Session Service translates Account application errors into the text-protocol namespace: `AUTH_INVALID_CREDENTIALS`, `AUTH_RETRY_LATER`, `AUTH_ACCOUNT_LOCKED`, `AUTH_ABUSE_CONTROL_UNAVAILABLE`, and `AUTH_UNAVAILABLE` become `ERROR INVALID_CREDENTIALS`, `ERROR RETRY_LATER`, `ERROR ACCOUNT_LOCKED`, `ERROR ABUSE_CONTROL_UNAVAILABLE`, and `ERROR UNAVAILABLE` respectively. Telnet and WebSocket clients therefore receive stable protocol codes without collapsing distinct retry throttling, abuse-control outage, account-state, and general availability failures.

Canonical non-login authorization/entitlement errors:

- `MEMBERSHIP_AUTH_UNAVAILABLE` - authoritative membership/role lookup is unavailable for a billing-safe mutation; callers must fail closed.
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
