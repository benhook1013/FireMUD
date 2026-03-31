# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands for gameplay protocol clients and via `/auth/login` or equivalent flows for first-party web UIs. Clients are stateless; server-side “sessions” are split between gameplay bindings in Redis and short-lived auth token allowlist entries in Coordination Redis. The Game Session Service restores gameplay session state from Redis, while the Account Service validates credentials (including OTP) and issues internal JWTs. Gameplay protocol clients (Telnet and WebSocket) never see these JWTs directly; first-party admin/creator web UIs and backend services use them for meta/control APIs. First-party gameplay WebSocket clients also carry a short-lived edge connect token at handshake time as described below. Accounts may also authenticate using linked external providers such as Google, Discord, or Steam.

## Implemented Status

- Prompt-based `LOGIN` flows (username then password prompts) are part of the target protocol design; until they are fully implemented across transports, clients should use `LOGIN <username> <password> [otp]` / `LOGON ...`.
- Character selection and gameplay takeover semantics are canonicalized on `{tenantId, gameInstanceId, characterId}`.
- First-party `/ws/game/**` now uses the concrete bootstrap path documented below: `POST /auth/player-bootstrap`, bootstrap-backed `POST /auth/connect-token`, gateway connect-token enforcement plus signed connect-context, then bare first-party `LOGIN` followed by `PLAY`.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook; until full role-refresh token regeneration is wired end-to-end, implementations may expose a placeholder response while still performing automatic refresh on role updates.

## Contract Decisions (Normative)

The following contract decisions are mandatory and resolve cross-document ambiguity:

- **Revocation writer authority** – The Account Service is the sole writer of `session:auth:revoked_after:*` watermarks. Other services must publish billing/security events and must not write these watermark keys directly.
- **Tenant watermark scope** – `session:auth:revoked_after:tenant:<tenantId>` applies to tenant-scoped regular and gameplay-affecting operations. It does not block explicitly classified billing-safe or support-safe routes.
- **Membership revocation scope** – `session:auth:revoked_after:membership:<accountId>:<tenantId>` applies to caller-bound tenant authorization for one account in one tenant and is used when membership or tenant roles change without triggering a tenant-wide billing cutoff.
- **Gameplay session identity key** – Session uniqueness and takeover scope are keyed by `{tenantId, gameInstanceId, characterId}`.
- **JWT claim contract** – Services must validate a strict JWT claim profile (required claims and audience per token profile), not only signature plus ad-hoc fields.
- **Internal delegation boundary** – Gameplay services must validate a Game Session-issued `SessionAttestation` on internal calls; mTLS-only trust is insufficient for end-user identity delegation.
- **Session attestation audience binding** – `SessionAttestation` must be bound to the destination gameplay service and RPC/method; a valid attestation for one gameplay API must not be reusable against another.
- **Route classification governance** – Protected routes must be classified in the shared route matrix document and enforced through middleware annotations/interceptors; behavior must not rely on per-service ad-hoc interpretation.
- **Gameplay session indexing** – Game Session is the authoritative writer for bounded secondary indexes that map gameplay bindings by uniqueness key, account/tenant scope, and tenant scope so takeover, reconnect, and revocation do not require scans.
- **Gameplay admission semantics** – `LOGIN` authenticates account identity, while `PLAY` binds gameplay identity and gameplay scope. These must remain distinct concepts even when a client UX makes them feel nearly back-to-back.

## Responsibility Split

- **Account Service** – Verifies credentials (including OTP), issues JWTs, and publishes JWKS for validation.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected routes but does not validate tokens.

Admin and moderator accounts can optionally enable **two-factor authentication**. When a `two_factor_secret` is present, the Account Service expects a one-time TOTP code during login. The `/auth/login` REST endpoint and the `Authenticate` gRPC call both accept an `otp` field for this purpose. The Game Session Service forwards this OTP when a player logs in.

When `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins over **plaintext Telnet** are further constrained: only accounts that both (a) have two-factor authentication enabled and (b) explicitly opt in to “allow plaintext Telnet login” may authenticate via the raw TCP port. All other accounts must use the TLS Telnet port or the web client and receive a clear error if they attempt to log in over plaintext Telnet.

Issued JWTs, allowlist entries, revocation watermarks, and token-profile validation rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This document still defines how those token contracts are applied to route classification, gameplay admission, and tenant authorization, but it no longer carries the full token catalog inline.

---

## Identity, Roles, and Tenant Access

Authentication always identifies a single platform account, represented by the `accountId` claim. Tenant-specific state and permissions are layered on top of this identity:

- `accountId` – Global platform identity managed by the Account Service.
- `globalRoles` – Cross-tenant roles such as `platformAdmin`, `billingAdmin`, or `support`.
- `scopedRoles` – A map from `tenantId` to roles granted to the account within that tenant (for example, `"tenant-abc": ["player", "designer"]`).

For the data model underpinning `accountId`, `tenantId`, characters, and membership relationships, see the [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) design.

### Role Model

FireMUD standardizes a small, explicit role set so tenant authorization and cross-tenant behavior remain consistent across services:

- **Global roles (`globalRoles`)**
  - `platformAdmin` – Full cross-tenant administrative access, including starting and stopping game instances, viewing cross-tenant analytics, and reading billing and subscription state for any tenant.
  - `support` – Limited cross-tenant support tools, subject to audit. Support roles may view high-level subscription state and entitlements for troubleshooting (for example, whether a tenant is `active` or `suspended` and what quotas apply), but cannot view detailed billing artifacts such as invoices or payment methods and cannot modify subscriptions.
  - `billingAdmin` – Cross-tenant access to billing-safe control-plane APIs (for example, viewing invoices, updating payment methods, and managing subscriptions) but no gameplay or design privileges.
- **Tenant roles (`scopedRoles[tenantId]`)**
  - `player` – Can join gameplay for the tenant subject to entitlements and quotas; no design, admin, or billing capabilities.
  - `designer` – Can edit design-time content for the tenant via Game Design tools; cannot control runtime instances or billing.
  - `tenantAdmin` – Owns the tenant in day-to-day operations: can manage game instances, configure runtime settings, and manage subscriptions and billing for that tenant.
  - `moderator` – Performs tenant-scoped moderation actions (for example, muting or banning players) but cannot alter billing or platform-wide configuration.

Services must not introduce ad-hoc roles without updating this model and the Tenant Authorization Contract. Where finer-grained behavior is required, services should prefer additional capabilities/flags derived from these core roles rather than inventing new top-level roles.

### Global Role to Route-Class Matrix (Normative)

Global roles must not be interpreted as broad tenant access shortcuts. Authorization must use the route classification model, with the following mandatory limits:

| Global role | Allowed route classifications | Explicitly disallowed |
| --- | --- | --- |
| `platformAdmin` | `tenant_regular` (operational/control-plane only), `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing` | `billing_safe_tenant` caller-bound variants; gameplay admission/switching |
| `billingAdmin` | `cross_tenant_billing_safe` | `tenant_regular`, `billing_safe_tenant`, `cross_tenant_data_bearing`, gameplay admission/switching |
| `support` | `cross_tenant_support_safe` | `tenant_regular`, `billing_safe_tenant` mutations, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, gameplay admission/switching |

Tenant-scoped operational/design/runtime actions may allow `platformAdmin` per the route-class matrix, but gameplay admission and gameplay switching must not. Gameplay entry (`WORLDS`/`REALMS`/`CHARS`/`PLAY`) requires caller-bound tenant gameplay authority from authoritative membership data or the canonical public-production admission policy for the target realm; global roles alone must never grant gameplay access. `billingAdmin` and `support` must never gain gameplay/design authority by virtue of being global roles.
`account_scoped` routes are authorized by authenticated account context and explicit subject-binding rules; global roles do not broaden `account_scoped` access unless a specific route explicitly allows a `platformAdmin` override.

### Tenant Authorization Contract

All meta/control services (Account, Game Design, Logging & Admin, and similar HTTP/gRPC APIs) must enforce a consistent tenant-authorization contract:

- Each incoming request is authenticated to a single `accountId` using a JWT validated against the Account Service JWKS.
- The effective tenant set for the request is derived from the token:
  - For tenant-scoped operations, the service computes the set of `tenantId` values from `scopedRoles` plus explicit global-role allowances from the route-class matrix above. Gameplay lobby/admission routes are stricter: they must derive authority from caller-bound tenant membership and `gameplayAdmissionAllowed`, not from global-role shortcuts. Billing-related global access must use explicitly cross-tenant billing-safe route variants.
  - For cross-tenant operations, the service must explicitly check that the caller has a `globalRole` that authorizes cross-tenant access for the specific API category (for example, only `platformAdmin` for gameplay- or data-bearing operations, `billingAdmin` or `platformAdmin` for billing-safe control-plane operations, and `support` or `platformAdmin` only for explicitly designated support-safe troubleshooting surfaces). Tenant-scoped roles must never implicitly grant cross-tenant privileges.
- For account-scoped operations, authorization must bind to authenticated `accountId` and route-level subject-binding rules, without deriving or requiring tenant scope.
- If an API accepts a `tenantId` (path, query parameter, or body field), the service must validate that:
  - `tenantId` is in the effective tenant set for tenant-scoped calls, or
  - The caller holds a cross-tenant `globalRole` that explicitly allows operating on the requested tenant.
- Services must apply the `tenantId` filter to all read and write queries, even when the client does not explicitly supply a `tenantId` (for example, when inferring tenant from a game instance).

A shared library helper (for example, a `TenantAccessGuard` used by `AuthTokenInterceptor`) should be used by all meta/control services so this contract is implemented in one place and kept in sync with future role/tenant model changes.

### Auth Middleware Algorithm (Normative)

Any HTTP/gRPC route that depends on identity, roles, or tenant scoping must be protected by the shared auth middleware (for example, `AuthTokenInterceptor` plus a `TenantAccessGuard`). Implementations must follow the same decision logic so authorization behavior does not drift across services:

1. **Validate the JWT** – Verify signature (JWKS), time-based claims (`exp`, `nbf`), and the expected token profile/audience (`aud`). Reject tokens with an unexpected profile (for example a Browser JWT presented to an internal-only endpoint).
2. **Check baseline allowlist** – Compute `tokenHash` and require `session:auth:account:<accountId>:<tokenHash>` to exist in Coordination Redis. If missing, treat the session as revoked and return the canonical “session revoked” error (`AUTH_SESSION_REVOKED` or equivalent).
3. **Check revocation watermarks** – Enforce bulk revocation without relying on wildcard deletes or key scans:
   - If `session:auth:revoked_after:account:<accountId>` exists and the token’s `iat` is older than that value, treat the session as revoked.
   - For routes classified as tenant-scoped regular or gameplay-affecting, if `session:auth:revoked_after:tenant:<tenantId>` exists and the token’s `iat` is older than that value, treat the token as revoked for that tenant-scoped operation.
   - For routes classified as tenant-scoped regular or billing-safe tenant-scoped, if `session:auth:revoked_after:membership:<accountId>:<tenantId>` exists and the token’s `iat` is older than that value, treat the token as revoked for that caller-bound tenant operation.
   - For routes classified as billing-safe or support-safe, tenant revocation watermarks do not by themselves revoke access; role checks and route classification still apply.
4. **Apply route classification** – Every protected route is classified as one of the following, and the middleware must enforce the corresponding allowlist and role rules:

| Route classification | Required allowlist entries | Required role checks | Tenant watermark applied? | Tenant validation rules |
| --- | --- | --- | --- | --- |
| Public | *(none)* | *(none)* | No | *(none)* |
| Account-scoped | `session:auth:account:<accountId>:<tokenHash>` | Require authenticated caller; enforce subject binding (`accountId` path/body must match caller) unless route explicitly allows `platformAdmin` override | No | No tenant scope for auth; never require `session:auth:tenant:*` for account-scoped routes |
| `player_bootstrap_tenant` | `session:auth:account:<accountId>:<tokenHash>` | Require a valid player-bootstrap token profile for the authenticated account | No | Used only for gameplay bootstrap routes such as `POST /auth/connect-token`; tenant access is established by live membership, entitlement, and admission-pointer checks during connect-token issuance, not by `session:auth:tenant:*` alone |
| Pre-tenant discovery | `session:auth:account:<accountId>:<tokenHash>` | Require authenticated caller; no caller-supplied `tenantId` is trusted yet | No | Used only for authenticated lobby/discovery surfaces such as `WORLDS`; services must derive visible tenants by filtering authoritative membership/entitlement data server-side. Global roles do not widen gameplay discovery. |
| Tenant-scoped (regular) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:tenant:<tenantId>:<tokenHash>` | Require a tenant role in `scopedRoles[tenantId]` that authorizes the operation (for example `tenantAdmin`, `designer`, `moderator`, `player`) or an explicitly documented route-level `platformAdmin` allowance | Yes | `tenantId` must be in `scopedRoles` for tenant-role callers unless a specific route explicitly allows a global-role override. `billingAdmin` and `support` must be rejected for `tenant_regular`. Gameplay admission/switching routes must not use `platformAdmin` as an implicit override and must enforce caller-bound gameplay membership plus DB/query scoping by `tenantId`. |
| Billing-safe (tenant-scoped) | `session:auth:account:<accountId>:<tokenHash>` | Require caller-bound tenant membership with `tenantAdmin` for the target tenant | No tenant-billing watermark; yes membership watermark | `tenantId` must be validated against caller tenant scope; services must perform a live caller-bound membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing billing-safe mutations; this route must remain reachable even when the tenant is `suspended`/`canceled` for gameplay, but it must fail immediately after caller membership/role revocation via the membership watermark or the live membership check |
| Cross-tenant (support-safe) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `support` or `platformAdmin` | No | Tenant parameters are allowed only because the caller holds a cross-tenant support role; responses must be limited to high-level, troubleshooting-safe data (for example derived entitlements and subscription status, not invoices/payment methods); log/audit the target tenant |
| Cross-tenant (billing-safe) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `billingAdmin` or `platformAdmin` | No | Tenant parameters are allowed only because the caller holds a global billing role; log/audit the target tenant |
| Cross-tenant (data-bearing) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `platformAdmin` | Yes when operation targets tenant-scoped data | Tenant parameters are allowed only because the caller holds `platformAdmin`; log/audit the target tenant |

Protected routes that are absent from the route matrix must fail CI and deployment policy checks. Runtime middleware must still default-deny such routes as `tenant_regular` semantics (watermark applies) if misconfiguration reaches execution.

Billing-safe mutation membership contract (normative):

- Billing-safe tenant mutations must perform an authoritative, live membership/role check via Account Service API (`GetCallerTenantMembership(tenantId)` or protocol-equivalent) before mutation.
- JWT role claims are sufficient for routing and preliminary checks but are not sufficient alone for billing-safe mutations.
- If membership authority is unavailable, billing-safe mutations fail closed with canonical error `MEMBERSHIP_AUTH_UNAVAILABLE`; read-only billing-safe surfaces may return a retriable unavailable response using the same code.
- Immediate caller-bound revocation for tenant membership/role changes is enforced by `session:auth:revoked_after:membership:<accountId>:<tenantId>` in addition to the live membership check; implementers must not rely on JWT expiry alone.
- Tenant-scoped membership checks use `GetCallerTenantMembership(tenantId)` and must bind the subject to the authenticated caller (`accountId` from token); clients must not provide an arbitrary target `accountId` on this path.
- Global billing roles (`billingAdmin`/`platformAdmin`) must use explicitly cross-tenant billing-safe route variants and must not rely on caller-bound tenant membership endpoints intended for `billing_safe_tenant`.
- Cross-tenant membership checks for billing/reporting use a separate admin API (`GetTenantMembershipForAccount(tenantId, accountId)` or equivalent) restricted to `billingAdmin`/`platformAdmin`.
- Membership responses must include `evaluatedAt` and `membershipVersion` fields so callers can audit freshness and detect stale reads.

1. **Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult the internal runtime entitlement surface (`GetTenantEntitlementsForRuntime(tenantId)` or protocol-equivalent) and deny requests when the tenant is not available for gameplay (for example `suspended`/`canceled`). Billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.
2. **Entitlement freshness SLA** – Admission-critical flows (`PLAY`, new gameplay session admission, instance start/restart/rollback) must use an entitlement snapshot that is no older than 15 seconds. If no fresh snapshot is available (for example event lag, cache miss, or Account Service uncertainty), the flow fails closed with canonical error `ENTITLEMENT_UNAVAILABLE` (text protocol: `ERROR ENTITLEMENT_UNAVAILABLE`) rather than admitting based on stale entitlement cache data.
   - Entitlement snapshots must carry `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
   - Admission logic must reject snapshots that are stale by time (`evaluatedAt`) or stale by sequence (`tenantBillingSequence` older than locally observed sequence for the tenant).
   - On detected sequence gaps, services must reconcile by calling `GetTenantEntitlementsForRuntime(tenantId)` before retrying admission.

Support-safe routes are an explicit allowlist and must not be inferred broadly from role names. The current support-safe allowlist is:

- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)` returning high-level entitlement status only
- `GetSubscriptionCrossTenantSupportSafe(tenantId)` returning high-level status and plan metadata only
- `ListSubscriptionsCrossTenantSupportSafe` returning high-level status and plan metadata only

Support-safe endpoints must exclude invoice line items, payment method details, and subscription mutation APIs.

All route classifications must also be registered in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md) with machine-readable entries in `system-architecture-authz-route-matrix.yaml`. Middleware annotations and CI checks must reject protected routes that are not present in that matrix.

---

## Login and Session Flow

The canonical player-facing flow is intentionally simple:

```text
WORLDS
LOGIN <username> <password> [otp]
PLAY <world> [character]
```

`WORLDS` must be available before login as a public browse/discovery command so prospective players can explore the platform before deciding to authenticate. `REALMS` and `CHARS` remain available as helper commands when a world choice is ambiguous or when a player wants to browse more deeply, but they are not intended to be mandatory ceremony in the ordinary happy path.

Normative semantic split:

- `LOGIN` proves or restores account identity.
- `PLAY` binds the gameplay session to `{tenantId, gameInstanceId, characterId}`.

Transport state, connect-token state, and any future hidden Telnet smart-client metadata are inputs to this flow; they are not peers to the authoritative gameplay binding.

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command.

Target protocol behavior:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

Current implementation note:

- Prompt-based `LOGIN` remains the target behavior for Telnet and generic WebSocket clients, but until the prompt flow is fully implemented those transports currently require `LOGIN <username> <password> [otp]` and return `PROMPT_LOGIN_UNSUPPORTED` on bare `LOGIN`.
- First-party `/ws/game/**` remains the exception: once Gateway has validated a connect token and attached a signed connect context, bare `LOGIN` is the canonical bootstrap-backed path and must not prompt for or replay credentials. The current implementation now includes dedicated `POST /auth/player-bootstrap` and `POST /auth/connect-token` endpoints, gateway-side handshake rejection for missing, expired, replayed, or scope-mismatched connect tokens, and Game Session validation of the signed connect context before admitting bare first-party `LOGIN`.

Telnet-specific smart-client attach hints, if they return later, should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line. Those hints remain advisory transport metadata only, are not authentication material, and never bypass the canonical `LOGIN` + `PLAY` authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

Any future hidden attach hints may include a target `{gameInstanceId, tenantId}` for advanced clients, but the canonical source of gameplay target selection remains the authenticated lobby/admission flow. Clients must not rely on unauthenticated transport hints to bypass membership, entitlement, or world-visibility checks.

### WebSocket Connect Token Contract (`/ws/game/**`)

For first-party WebSocket clients, the control plane issues a short-lived connect token used only for handshake-time edge policy (for example tenant-aware rate limiting before `LOGIN` completes).

FireMUD standardizes a dedicated **player bootstrap** contract for first-party gameplay web/mobile clients:

- The first-party player UI authenticates directly against a dedicated bootstrap endpoint (for example `POST /auth/player-bootstrap`) using the same primary account credentials and abuse/2FA policy as gameplay login.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It is not derived from an existing admin/creator browser session and must not require or return a control-plane Browser JWT.
- On success, the endpoint returns one short-lived, memory-only **player bootstrap token** plus expiry metadata.
- This bootstrap token is not a control-plane Browser JWT and must not be accepted on admin/creator APIs.
- It is still an Account Service-issued JWT profile and must carry at least `iss`, `sub`, `accountId`, `aud=player-bootstrap`, `jti`, `iat`, `nbf`, and `exp`, backed by `session:auth:account:<accountId>:<tokenHash>` so account-level revocation and logout semantics apply.
- Audience/scope is limited to first-party gameplay bootstrap functions such as discovery and `POST /auth/connect-token`.
- Lifetime is intentionally short (target <= 5 minutes), stored in memory only, and cleared on tab reload/logout.
- `POST /auth/connect-token` must derive caller identity from this bootstrap token; clients must not supply an arbitrary `accountId`.
- The subsequent gameplay `LOGIN` remains mandatory but, for first-party `/ws/game/**` clients, it must complete using the already-verified bootstrap/connect context rather than requiring the browser to re-submit account credentials. In other words, first-party bare `LOGIN` on `/ws/game/**` is an identity-consumption/binding step, not a second credential-entry step. A mismatch between the verified bootstrap identity and the gameplay login result is a hard failure and the connect context must not be honored.

- Bootstrap issuance API: Account Service endpoint (for example `POST /auth/player-bootstrap`) that authenticates the player account for first-party gameplay bootstrap only and returns one short-lived bootstrap token plus expiry metadata.
- Issuer: Account/authentication control-plane only, after direct player-account authentication. Tenant membership and entitlement checks do not occur here because no gameplay tenant has been selected yet.
- Bootstrap-discovery APIs: authenticated first-party HTTP endpoints (for example `GET /auth/bootstrap/worlds`, `GET /auth/bootstrap/worlds/{world}/realms`, `GET /auth/bootstrap/worlds/{world}/realms/{realm}/characters`) that accept only the `player-bootstrap` token profile and return the canonical lobby discovery data used to choose a target before socket open.
  - These endpoints are the canonical pre-socket discovery path for first-party clients.
  - They must apply the same caller-bound membership, realm visibility, and entitlement filtering rules as in-band `WORLDS` / `REALMS` / `CHARS`.
  - Hidden or unauthorized tenants, realms, and characters must not be inferable by probing these endpoints.
  - Discovery responses must return a canonical connect-token selector for each admissible realm target. FireMUD standardizes this as an opaque `connectScopeId` plus resolved routing metadata.
  - `connectScopeId` is the only client-supplied selector accepted by `POST /auth/connect-token`; first-party clients must not invent or derive `tenantId` / `gameInstanceId` pairs locally from slugs.
  - Minimum selector fields returned by discovery for an admissible realm target: `connectScopeId`, `tenantId`, `realmSlug`, `gameInstanceId`, `pointerVersion`, and a bounded freshness timestamp (`evaluatedAt` or equivalent).
  - For non-public realms such as playtest forks, visibility is controlled by an explicit realm-access grant. The minimum grant record is `{tenantId, realmSlug, accountId, grantedByAccountId, grantedAt, expiresAt?}`.
  - Realm-access grants are owned by Account Service. Account Service is the sole writer and read authority for grant visibility decisions; Game Session and frontend callers consume grant-filtered results and must not maintain independent grant stores.
  - `tenantAdmin` is the routine owner of creating and revoking realm-access grants for that tenant through Account Service-owned admin surfaces. `platformAdmin` may do the same only as break-glass support.
  - Required internal read contract: Account Service must expose a caller-bound runtime lookup for realm visibility/admission, for example `GetRealmAccessGrant(accountId, tenantId, realmSlug)` or a batch/list equivalent consumed by bootstrap discovery, in-band `REALMS`, `POST /auth/connect-token`, and `PLAY`.
  - Required semantics for realm-access-grant reads/writes:
    - idempotent create/revoke by `{accountId, tenantId, realmSlug}`
    - expired grants are treated as non-existent for visibility and admission
    - successful create/revoke must be immediately visible to subsequent discovery/admission reads
    - if grant authority is unavailable, non-public realm discovery and admission fail closed rather than falling back to stale local cache state
- Connect-token issuance API: control-plane endpoint (for example `POST /auth/connect-token`) that returns exactly one short-lived token per request and logs `accountId`, `tenantId`, `gameInstanceId`, `jti`, and issuance timestamp.
  - Minimum request fields: `connectScopeId`, `requestId`.
  - Minimum response fields: `connectToken`, `expiresAt`, `accountId`, `tenantId`, `gameInstanceId`, `realmSlug`, `jti`, `issuedAt`.
  - Before issuance, Account Service must resolve `connectScopeId` to the canonical `{tenantId, realmSlug, gameInstanceId, pointerVersion}` tuple, perform a live membership/public-admission check for `{accountId, tenantId, realmSlug}`, a live runtime entitlement check for `tenantId`, and a live realm-routing read for the selected realm target via the Game Session control-plane API.
  - If realm-routing state is unavailable or ambiguous, connect-token issuance fails closed with `ADMISSION_POINTER_UNAVAILABLE`.
  - If `connectScopeId` no longer resolves to the current admissible target for the selected realm, connect-token issuance fails closed with `CONNECT_SCOPE_MISMATCH`; it must not mint a token for a stale or non-admissible target and rely on `PLAY` to correct it later.
  - First-party clients may request connect tokens only for realm targets returned by the canonical bootstrap-discovery contract for that caller; hidden or unauthorized realms must not be inferable by probing connect-token issuance directly.
  - Missing required request/response fields are contract violations and must fail closed rather than being defaulted by callers.
- Transport: `X-Firemud-Connect-Token` header on `/ws/game/**` handshake.
- Required claims: `accountId`, `tenantId`, `gameInstanceId`, `exp`, `jti`.
- Lifetime: short-lived (target <= 30 seconds).
- Signing and verification: token is signed by the Account/authentication control-plane key set and verified only at Gateway for `/ws/game/**` policy decisions.
- Replay defense: gateway validates `jti` against a bounded replay cache and rejects replays until token expiry.
  - Replay cache owner: Gateway.
  - Replay key format: `gateway:connect-token:replay:<jti>`.
  - Replay TTL: `exp + bounded_skew` (recommended 30 seconds).
  - Capacity policy: bounded cardinality with deterministic eviction (`oldest-expiry-first`) and overload metrics when capacity limits are reached.
- Enforcement:
  - `/ws/game/**` is the only gameplay WebSocket route.
  - Non-proxy gameplay clients must present a valid connect token; missing/invalid/expired/replayed tokens are rejected with HTTP `403`.
  - TCP Proxy bridge traffic is admitted without a connect token only when the gateway authenticates the proxy identity over the internal mTLS listener and header-trust checks pass.
- Error mapping: invalid/expired/replayed/missing token where required maps to HTTP `403` at handshake.

The connect token is not a gameplay authorization grant and does not replace the canonical `LOGIN` + `PLAY` flow. It is an edge-admission artifact bound to a prior first-party bootstrap identity, not a substitute for gameplay authentication or gameplay binding.

#### Gateway-to-Game Session connect context (normative)

Gateway verification of `X-Firemud-Connect-Token` must not be translated into trust of raw forwarded headers. For first-party `/ws/game/**` handshakes, Gateway must attach a short-lived signed connect context that Game Session verifies before applying connect-token scope checks.

- Gateway-issued context fields (minimum): `accountId`, `tenantId`, `gameInstanceId`, `connectTokenJti`, `verifiedAt`, `expiresAt`, `gatewayRequestId`.
- Transport: single signed compact payload header (for example `X-Firemud-Connect-Context`) plus `kid` metadata if not embedded in payload.
- Signature: asymmetric gateway signing key set; Game Session validates signature and `kid` against Gateway verification keys.
- TTL: <= 30 seconds from `verifiedAt`; Game Session rejects stale/expired contexts.
- Replay guard: Gateway owns replay protection for `connectTokenJti` at handshake time using the shared replay cache. Game Session does not implement a second replay authority for that token identifier; it treats `connectTokenJti` inside the signed context as auditable scope metadata only.
- Failure mode: if signed context is missing/invalid for a first-party handshake that required connect-token verification, Game Session must fail admission with `CONNECT_CONTEXT_INVALID` before `PLAY`.
- Key-management operational contract:
  - Gateway is the sole signer for connect-context payloads and must expose a verification-key set with stable issuer identity and bounded-key cardinality.
  - Rotation must support overlap: old and new `kid` values remain verifiable for a bounded overlap window so rolling deploys do not break in-flight reconnects.
  - Game Session maintains a bounded TTL cache of Gateway verification keys and must refresh on unknown `kid`; if no valid verification keys are available, fail closed with `CONNECT_CONTEXT_INVALID`.
  - Observability must expose bounded failure reasons (`unknown_kid`, `signature_invalid`, `context_expired`, `verification_keys_unavailable`) so operators can distinguish key-rollout issues from client misuse.

`CONNECT_SCOPE_MISMATCH` must be computed from this verified context, not from raw `X-Tenant-Id`/`X-Game-Instance-Id` headers.

#### First-party WebSocket admission sequence (normative)

To remove ambiguity between connect-token admission and `LOGIN`, first-party web/mobile gameplay clients must follow this sequence:

1. Call the dedicated first-party player bootstrap endpoint (for example `POST /auth/player-bootstrap`) and establish a short-lived player bootstrap identity.
2. Use bootstrap-authenticated discovery endpoints to select a caller-visible world/realm/character target.
3. Request a short-lived gameplay connect token for one target selected by `connectScopeId` returned by that discovery contract. This call performs the live membership/public-admission and runtime entitlement checks for that target.
   - The issuance path must also validate the target against the authoritative realm-routing record. If the target is no longer admissible for the selected realm, the request fails before socket open rather than issuing a stale token.
4. Open gameplay WebSocket on `/ws/game/**` with `X-Firemud-Connect-Token`.
5. Complete gameplay authentication in-band using `LOGIN` (or `LOGON`) and then lobby binding with `PLAY`.

Normative constraints:

- First-party clients must not treat successful handshake as gameplay authentication; gameplay remains unauthenticated until `LOGIN` succeeds.
- `/ws/game/**` requires a valid connect token for non-proxy clients and rejects missing tokens with `403`.
- For first-party `/ws/game/**` clients, bare `LOGIN` (or `LOGON`) must complete gameplay authentication by consuming the verified connect context plus the bootstrap identity already bound to that context. Browsers must not be required to replay username/password/OTP after bootstrap.
- Third-party clients and non-bootstrap transports continue to use credential-bearing `LOGIN <username> <password> [otp]` or the prompt flow.
- Game Session must bind the verified connect context to the authenticated gameplay login: if bootstrap-backed `LOGIN` resolves to an `accountId` different from the connect-context `accountId`, the session fails closed with canonical error `ACCOUNT_MISMATCH` and no gameplay scope is bound.
- For first-party clients on `/ws/game/**`, the `PLAY` selection must match the connect-token scope `{tenantId, gameInstanceId}`. Scope mismatch is rejected with canonical error `CONNECT_SCOPE_MISMATCH`; clients must request a fresh connect token for the intended target and reconnect.
- Because `/auth/connect-token` validates against the authoritative realm-routing state for the caller, `CONNECT_SCOPE_MISMATCH` at `PLAY` is treated as drift between issuance and admission (for example route movement during reconnect), not as normal stale-client correction.
- The bootstrap-discovery contract, connect-token contract, and the lobby `PLAY` contract together form the canonical player-selected `{tenantId, gameInstanceId}` path. Connect tokens must be issued only for a concrete target returned by the same discovery and realm-routing contract rather than via a side-channel selector, and first-party clients must carry that selection forward via `connectScopeId`.

Canonical first-party browser sequence (example):

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [{
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "tenant-demo",
     gameInstanceId: "production",
     connectScopeId: "cs_demo_production_v17"
   }]

GET /auth/bootstrap/worlds/demo/realms/production/characters
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-123" }
-> { connectToken, accountId, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production", expiresAt }

GET /ws/game/** with X-Firemud-Connect-Token: <connectToken>

LOGIN
OK LOGIN Logged in
WORLDS
REALMS demo
CHARS demo production
PLAY demo production Mara
OK PLAY Entered Demo World / Live Realm as Mara
```

Canonical first-public-join sequence (example):

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "emberfall", displayName: "Emberfall" }]

GET /auth/bootstrap/worlds/emberfall/realms
Authorization: Bearer <bootstrapToken>
-> [{
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "tenant-emberfall",
     gameInstanceId: "production",
     connectScopeId: "cs_emberfall_production_v1"
   }]

GET /auth/bootstrap/worlds/emberfall/realms/production/characters
Authorization: Bearer <bootstrapToken>
-> []

POST /characters
Authorization: Bearer <bootstrapToken>
{ worldSlug: "emberfall", realmSlug: "production", name: "Mara", template: "human-fighter" }
-> { characterName: "Mara", characterId: "char-9001" }

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-join-1" }
-> { connectToken, accountId, tenantId: "tenant-emberfall", realmSlug: "production", gameInstanceId: "production", expiresAt }

GET /ws/game/** with X-Firemud-Connect-Token: <connectToken>
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

Required postconditions for the first successful public-production join:

- the account now has canonical `player` membership for the tenant;
- future `WORLDS` results for that account may rely on membership rather than public discovery alone; and
- if character creation or admission fails before `OK PLAY`, the service must not persist a partial membership grant.

Canonical character-creation contract for this flow:

- The player-facing control-plane surface is `POST /characters` using the current bootstrap-authenticated account identity plus the selected `{worldSlug, realmSlug}` target.
- Entity Management owns the underlying `CreateCharacter` semantics and persistence contract; Account Service/authentication docs define the admission prerequisites for when this route may be called.
- `POST /characters` is allowed only after bootstrap discovery has selected a caller-visible realm target and before `POST /auth/connect-token` / gameplay `PLAY` succeed for that new character.
- The route must reject requests for realms that are not currently visible/admissible to the bootstrap-authenticated account.

Example first-party browser sequence for a playtest fork:

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [
     {
       realmSlug: "production",
       displayName: "Live Realm",
       tenantId: "tenant-demo",
       gameInstanceId: "production",
       connectScopeId: "cs_demo_production_v17"
     },
     {
       realmSlug: "playtest-docks",
       displayName: "Playtest Fork",
       tenantId: "tenant-demo",
       gameInstanceId: "playtest-docks",
       connectScopeId: "cs_demo_playtest_docks_v4"
     }
   ]

GET /auth/bootstrap/worlds/demo/realms/playtest-docks/characters
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_playtest_docks_v4", requestId: "req-456" }
-> { connectToken, accountId, tenantId: "tenant-demo", realmSlug: "playtest-docks", gameInstanceId: "playtest-docks", expiresAt }

GET /ws/game/** with X-Firemud-Connect-Token: <connectToken>

LOGIN
OK LOGIN Logged in
CHARS demo playtest-docks
PLAY demo playtest-docks Mara
OK PLAY Entered Demo World / Playtest Fork as Mara
```

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The client emits one of the canonical gameplay-login forms:
   - `LOGIN <username> <password> [otp]` (or `LOGON ...`) for Telnet, generic WebSocket, and any non-bootstrap transport.
   - bare `LOGIN` / `LOGON` on first-party `/ws/game/**` after Gateway has validated a connect token and attached a signed connect context. In this case, the browser is completing gameplay auth from the previously established bootstrap identity rather than sending credentials a second time.
2. For credential-bearing login, the Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with a payload containing `username`, `password`, the optional `otp`, and connection metadata indicating the **transport security** (for example `transportSecurity=PLAINTEXT_TELNET` vs `transportSecurity=TLS_TELNET` / `WEB_TLS`). This metadata is derived from the TCP Proxy and Gateway handshake so the Account Service can enforce deployment-wide and per-account policies for plaintext Telnet logins. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs.
3. For bootstrap-backed first-party login, Game Session validates the signed connect context, binds it to the bootstrap-authenticated account identity established before `/auth/connect-token`, and obtains/refreshes the backend token material needed for subsequent internal calls. This path must not prompt for or require replay of account credentials from the browser.
4. The Account Service-backed credential path validates credentials (including the OTP when present) and returns either a JWT + account metadata or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `AUTH_PLAINTEXT_TCP_NOT_PERMITTED`, or `AUTH_UPSTREAM_FAILURE`. The Game Session Service translates these codes into the text-protocol equivalents (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, `ERROR 2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `ERROR PLAINTEXT_TCP_NOT_PERMITTED`, etc.) so WebSocket and Telnet clients always see the same response format regardless of how the upstream message is worded. For plaintext Telnet logins, the combination of `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and the per-account “allow plaintext Telnet login” flag follows the safety matrix defined in the Security Architecture’s **Plaintext Telnet safety matrix** section; implementations must treat any combination outside the allowed cells as a hard denial (`AUTH_PLAINTEXT_TCP_NOT_PERMITTED` or `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`) rather than silently weakening security.
5. Success responses cause the Game Session Service to create/refresh Redis-backed gameplay session bindings and the Account Service to create or refresh the corresponding `session:auth:*` allowlist entries for backend use. The Game Session Service binds the socket to an authenticated account context and emits `OK LOGIN Logged in` (or equivalent account-confirming text) on the wire. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by both the authentication handshake (`LOGIN`) and the lobby selection step (`PLAY`). Any text command received before login should be rejected with stage-aware guidance such as `ERROR LOGIN_REQUIRED ...`, and any gameplay command received before `PLAY` should be rejected with stage-aware guidance such as `ERROR PLAY_REQUIRED ...`. Except in explicitly documented development/test bypass modes that grant temporary access, these commands are not processed for anonymous or unscoped sessions, keeping the gameplay queue free of unauthenticated traffic.

Credential-bearing login commands carry account credentials (plus optional OTP). Bootstrap-backed first-party `LOGIN` carries no credentials because it consumes the already verified bootstrap/connect context. Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model).

### Tenant Selection for Gameplay (Lobby Selection)

FireMUD uses a **single shared entrypoint** for many worlds (tenants). After `LOGIN`, clients complete a lobby selection step that binds the authenticated connection to a specific world (`tenantId`), gameplay-admissible instance (`gameInstanceId`), and gameplay identity (`characterId`) before gameplay commands are accepted.

Players must never be asked to type raw internal identifiers such as `tenantId` UUIDs, `gameInstanceId` values, or `characterId` values. Lobby selection accepts human-friendly inputs (world slugs, world menu indices, character names or indices) and resolves them server-side into stable internal identifiers.

After `LOGIN` succeeds, the Game Session Service requires an explicit lobby selection flow using these canonical commands:

- `WORLDS` – list worlds the authenticated account can enter (a numbered menu plus a stable world slug for each entry).
- `REALMS <world>` – list the visible realms for the selected world (`<world>` is a world index from `WORLDS` or a world slug). Responses include the default production realm plus any explicitly authorized additional realms such as playtest forks.
- `CHARS <world> [realm]` – list characters for the selected world and optional realm.
- `PLAY <world> [realm] [character]` – enter gameplay by selecting a world, an optional realm, and an optional character.

`public_production_onboarding` is the first-party lobby route class for the default public production realm. Brand-new authenticated accounts may see that realm before a tenant membership row exists, but the first successful `PLAY` must still atomically create the caller's `player` membership before gameplay binding is committed.

Realm discovery and routing contract:

- A tenant may expose multiple player-addressable realms. Each visible realm resolves to exactly one admissible `gameInstanceId` at a time through the authoritative realm-routing records owned by Game Session.
- One realm may be designated as the default public production realm. In v1, this production realm is the only realm that may be publicly discoverable without an existing tenant membership row, and `public_production_onboarding` governs the first-join path through that realm.
- Additional realms are access-controlled in v1. Unauthorized or hidden realms must not appear in discovery, and non-production realms such as playtest forks require explicit access grants.
- Explicit access grants for non-public realms are sourced from Account Service runtime grant authority, not from Game Session-local configuration or frontend-cached state.
- Connect-token issuance, `REALMS`, `CHARS`, and `PLAY` must all consume the same realm-routing state so clients never infer realm identity from transport-side hints alone.

Lobby discovery source-of-truth contract:

- `WORLDS` must be sourced from Account Service tenant-membership, public-production discovery, and entitlement state (not from opportunistic local caches alone) so world visibility and billing state cannot drift across services.
- If fresh enough entitlement state cannot be established to build the visible-world set safely, `WORLDS` must fail closed with canonical error `ENTITLEMENT_UNAVAILABLE` rather than listing worlds from stale discovery data (for example text rendering `ERROR ENTITLEMENT_UNAVAILABLE Entitlement state is temporarily unavailable; retry discovery.`).
- `WORLDS` discovery does not need to reuse the full 15-second admission SLA verbatim, but it must still be based on entitlement state fresh enough to avoid exposing worlds that are no longer gameplay-available. If that freshness bar cannot be met safely, discovery fails closed with `ENTITLEMENT_UNAVAILABLE`.
- `REALMS <world>` must distinguish between public-production visibility and explicit realm grants. Only the default production realm may be visible through public discovery in v1.
- Bootstrap discovery, `REALMS`, `POST /auth/connect-token`, and `PLAY` must all consume the same Account Service-owned realm-access-grant authority for non-public realms so visibility and admission cannot drift by surface.
- `CHARS <world> [realm]` must be sourced from the authoritative character store for the resolved `{tenantId, gameInstanceId}` target and filtered to the caller's valid character choices for that realm. Shared-state realms may surface the tenant's normal live characters, while isolated realms may surface copied, seeded, or otherwise instance-local character state for the same account.
- `WORLDS` and `CHARS` responses must not leak inaccessible tenants or characters; unresolved selectors return canonical errors (`WORLD_NOT_FOUND`, `WORLD_ACCESS_DENIED`, `CHARACTER_NOT_FOUND`, `CHARACTER_ACCESS_DENIED`) without exposing whether a hidden tenant exists.

Lobby command classification contract:

- `WORLDS` is an authenticated **pre-tenant discovery** operation, not a normal tenant-scoped route. It runs after account authentication but before a single `tenantId` has been selected.
- `REALMS <world>`, `CHARS <world> [realm]`, and `PLAY <world> [realm] [character]` participate in `public_production_onboarding` when the selected realm is the default public production realm. Brand-new authenticated accounts may discover that realm without a pre-existing membership row, but the first successful `PLAY` must atomically create the caller's `player` membership before gameplay binding is committed.
- `REALMS <world>` remains a tenant-scoped discovery operation after `<world>` resolves to a canonical `tenantId`, but before the client is bound to one `gameInstanceId`.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` become tenant/realm-scoped only after `<world>` and optional `[realm]` are resolved server-side to canonical `{tenantId, gameInstanceId}`.
- Shared auth middleware and route-matrix entries must not model all lobby commands as one undifferentiated tenant-scoped surface.

The `PLAY` flow:

- Resolves `<world>` to a canonical `tenantId` and validates it exists.
- Resolves optional `[realm]` to a canonical realm for that tenant. If no realm is supplied, the tenant's default production realm is selected.
- Verifies that the account is authorized to play in that `tenantId` using caller-bound gameplay membership authority or the canonical public-production admission policy for the default production realm. Global roles alone must not satisfy gameplay admission.
- If admission is proceeding through public-production discovery rather than an existing membership row, the first successful `PLAY` must atomically create the caller's `player` membership before gameplay binding is committed. Failed admissions must not leave behind a partial membership grant.
  - Membership creation writer authority: Account Service only. Game Session must not write membership rows directly.
  - Required API: `EnsurePublicProductionPlayerMembership(accountId, tenantId, realmSlug, requestId)` or protocol-equivalent, owned by Account Service.
  - This API is valid only for the tenant's default production realm under the canonical public-production admission policy; it must fail closed for non-production realms or callers that are not eligible for public admission.
  - Idempotency: repeated requests for the same `{accountId, tenantId, realmSlug}` must either return the existing `player` membership or create it exactly once without duplicating grants.
  - Concurrency: if multiple first-join attempts race, exactly one membership row may be created and all successful callers must observe the same resulting membership identity/version.
  - Visibility: on success, the resulting membership must be immediately visible to `GetTenantMembershipForRuntime(accountId, tenantId)` for the same admission transaction.
  - Failure semantics: gameplay binding, session creation, and any character-binding side effects must not commit unless `EnsurePublicProductionPlayerMembership` commits successfully.
- Performs an authoritative internal membership read for `{accountId, tenantId}` and persists the returned `membershipVersion` into the gameplay session binding on successful admission. The membership response must also assert `gameplayAdmissionAllowed=true`; gameplay admission must not source `membershipVersion` or gameplay authority from JWT claims or local caches.
- Consults the runtime entitlement contract `GetTenantEntitlementsForRuntime(tenantId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated).
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId, gameInstanceId}` according to the selected realm's character policy.
  - Explicit character creation and selection are part of the v1 contract. If the selected realm has no visible character for the caller, the client must complete the canonical character-creation flow before `PLAY` can succeed.
  - `PLAY` may omit `[character]` only when exactly one visible character exists for the resolved realm. Otherwise admission fails with `CHARACTER_REQUIRED`.
- Resolves the selected realm's gameplay-admissible instance and records that `gameInstanceId` in the gameplay binding.
  - First-party `/ws/game/**` contract: if a validated connect token is present, resolved `tenantId` and `gameInstanceId` must match token claims. On mismatch, reject admission with `CONNECT_SCOPE_MISMATCH` and do not bind session scope.
  - Runtime control-plane and admission flows use the realm-routing contract from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) as the source of truth for which concrete `gameInstanceId` is admissible for the selected realm.
- On successful admission, runtime must return the resolved realm bundle identity at minimum as `versionId`, optional `scriptPatchVersion`, and manifest location/hash (or a stable bundle token that resolves to those fields) so clients can apply realm-specific branding and assets.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:<tenantId>:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Ensures the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, regionId>`. Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, `/ws/game/**` is routed to a stable Game Session service endpoint and the edge does not implement a lease-aware shard routing plane.

`PLAY` returns canonical, stable error codes so clients can recover deterministically:

- `WORLD_NOT_FOUND` – the supplied world selection cannot be resolved to a tenant.
- `WORLD_ACCESS_DENIED` – the authenticated account is not authorized for gameplay admission in the tenant under caller-bound membership authority. Global roles alone must not satisfy this check.
- `TENANT_BILLING_BLOCKED` – the tenant is `suspended` or `canceled` and is not available for gameplay admission.
- `TENANT_QUOTA_EXCEEDED` – entitlements allow gameplay but quota caps (for example maximum active sessions) would be exceeded.
- `CONNECT_CONTEXT_INVALID` – first-party `/ws/game/**` admission is missing or has invalid/expired gateway-signed connect context for the handshake that required connect-token verification.
- `CONNECT_SCOPE_MISMATCH` – first-party `/ws/game/**` reconnect/admission attempted `PLAY` scope that does not match the connect-token `{tenantId, gameInstanceId}`.
- `ACCOUNT_MISMATCH` – bootstrap-backed `LOGIN` resolved to an account different from the validated connect-context subject, so no gameplay scope may be bound.
- `ADMISSION_POINTER_UNAVAILABLE` – realm-routing state is unavailable or ambiguous for the selected realm; admission is denied until routing reconciliation succeeds.
- `PLAY_REQUIRED` – a gameplay command requiring admitted gameplay scope was issued before `PLAY` completed successfully.
- `CHARACTER_REQUIRED` – the selected realm requires an explicit character choice because zero or multiple visible characters exist for the caller.
- `CHARACTER_CREATION_NOT_ALLOWED` – the selected realm has no visible character for the caller and current realm or fork policy forbids creating a new one; clients must surface this as a hard deny rather than as a generic selection prompt.
- `CHARACTER_NOT_FOUND` / `CHARACTER_ACCESS_DENIED` – character selection is requested but the character cannot be found or is not owned by the account.
- Any subsequent attempt to switch tenants or characters for a socket must go through the same tenant-selection flow so that role checks and entitlements are re-evaluated; there is no implicit cross-tenant switching based solely on the initial `LOGIN`.

First-party gameplay admission and reconnect clients should treat the following errors as canonical:

| Surface | Canonical code | Trigger condition | Required client reaction |
| --- | --- | --- | --- |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REJECTED` | Connect token is missing, expired, invalid, or replayed where required | Obtain a fresh connect token and open a new socket with bounded retry/backoff. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `/ws/game/**` handshake (`403`) | `POLICY_DENY` | Edge policy rejects the handshake for a non-token reason (for example proxy trust/config mismatch) | Treat as non-retriable until operator/client configuration is corrected. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_CONTEXT_INVALID` | Required gateway-signed connect context is missing, expired, unverifiable, or otherwise invalid | Refresh connect token, reconnect, then re-`LOGIN`; do not retry `PLAY` on the current socket. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_SCOPE_MISMATCH` | Requested `{tenantId, gameInstanceId}` does not match the validated connect-token scope | Re-select the intended world, obtain a fresh connect token for that target, reconnect, and retry `PLAY`. |
| `LOGIN` on first-party `/ws/game/**` | `ACCOUNT_MISMATCH` | Bootstrap-backed login resolved to an account different from the validated connect-context subject | Treat as a hard auth failure for the current socket; clear the gameplay bootstrap/connect flow and require a fresh authenticated bootstrap. |
| `PLAY` | `WORLD_ACCESS_DENIED` | Caller-bound membership authority does not allow gameplay admission for the resolved tenant | Keep auth state, surface an authorization error, and do not infer hidden-tenant existence beyond the canonical code. |
| `PLAY` | `TENANT_BILLING_BLOCKED` | Tenant entitlement state is `suspended` or `canceled` for gameplay | Keep auth state, surface a billing-blocked state for that tenant, and disable gameplay admission flows. |
| `PLAY`, new admission, restart/rollback | `ENTITLEMENT_UNAVAILABLE` | No entitlement snapshot fresh enough for the 15-second admission SLA can be established | Keep auth state, retry with bounded backoff, and avoid admitting based on stale entitlement cache state. |
| Gameplay command before `PLAY` | `PLAY_REQUIRED` | Client issued a world-scoped gameplay command before lobby admission completed | Keep auth state and route the client back through `PLAY`, `REALMS`, or `CHARS` as appropriate. |

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss) or when server-side auth state has expired or been revoked. After a reconnect, clients always issue a fresh `LOGIN` and then complete lobby selection again (`PLAY <world> [realm] [character]`). If a resumable gameplay session exists for the selected `{tenantId, gameInstanceId, characterId}`, the Game Session Service resumes it; otherwise it creates a fresh gameplay session binding.

Gameplay identity is canonicalized on `characterId` within a tenant. All Redis key formats and Game Session Service APIs must treat `characterId` as the abstract character identifier so sessions bind sockets to characters rather than raw accounts. Canonical takeover and resume identity is `{tenantId, gameInstanceId, characterId}`.

Gameplay identity is single-mode and canonical: uniqueness key `{tenantId, gameInstanceId, characterId}`.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Related Token and Session Contracts

The detailed token and lifecycle contracts now live in focused sibling docs:

- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) defines JWT claim requirements, token profiles, allowlist entries, revocation watermarks, and Redis-outage behavior for token validation.
- [Session Behavior](./system-architecture-session-behavior.md) defines gameplay takeover, session rebinding, mid-session role refresh, membership-version handling, and control-plane logout behavior.

---

## Role-Based Authorization

Access to services is governed by roles from the JWT:

| Context | Description |
| --- | --- |
| `globalRoles` | Platform-wide access (e.g., moderation, admin dashboards) |
| `scopedRoles` | Per-game access (e.g., designer tools, admin features for a game) |

### JWT Usage Scope

- ✅ **Meta/control services** (e.g. Game Design, Admin, Account) validate JWTs to authorize access
- 🚫 **Gameplay services** (e.g. Game Logic, Entity, World) do **not** validate JWTs — they rely on the Game Session Service to enforce access

Because gameplay services do not validate end-user JWTs, they must still enforce a strict internal trust boundary:

- All gameplay gRPC endpoints must require mTLS and must validate the caller’s service identity (for example via SPIFFE/SAN allowlists) so only authorized internal callers (typically the Game Session Service) can invoke gameplay APIs.
- Gameplay services must treat tenant/session/player identifiers in requests as untrusted inputs and must rely on a Game Session-issued `SessionAttestation` contract to prevent spoofing when additional internal callers are introduced.

### Session Attestation Contract (Normative)

When Game Session calls gameplay services on behalf of an authenticated player, the request must include a short-lived `SessionAttestation` produced by Game Session. At minimum, the attestation includes:

- `accountId`
- `tenantId`
- `gameInstanceId`
- `characterId`
- `sessionId`
- `aud` or `targetService`
- `targetMethod`
- `issuedAt`
- `expiresAt`
- `nonce` or `jti`

Gameplay services must verify attestation signature (or MAC), expiry bounds, destination audience/method, and caller service identity, then reject calls where attestation fields do not match request payload scope. A valid attestation for one gameplay service or RPC must not be accepted by another gameplay service or RPC. Raw forwarded headers/metadata (`X-Tenant-Id`, `X-Game-Instance-Id`, etc.) are advisory only and are never sufficient identity proof by themselves.

Attestation crypto and replay requirements:

- Use asymmetric signatures (recommended `EdDSA`/`Ed25519` or `ES256`) with Game Session as issuer.
- Keys must be rotated on a bounded cadence and distributed through the same secure secret-management pipeline used for service credentials.
- Attestation verification keys must be published through a versioned key set with explicit `kid` values so gameplay services can select verification keys deterministically during overlap windows.
- Verification-key discovery must use a single authoritative interface (JWKS-like HTTP endpoint or gRPC equivalent) owned by Game Session control-plane.
- Rotation must maintain overlap for at least `2 x max_attestation_ttl` before old keys are removed, and services must fail closed if they cannot resolve a referenced `kid`.
- Maximum attestation TTL: 120 seconds; reject attestations older than TTL or outside bounded clock-skew tolerance (recommended 60 seconds).
- Attestations are minted per outbound gameplay RPC using the destination service/method as part of the signed scope; consumers must not accept wildcard or omitted destination scope.
- Require unique `jti`/`nonce` replay guard within TTL; gameplay services must reject duplicates.
- Replay guards must be backed by a shared, bounded, low-latency store per gameplay trust domain (for example Coordination Redis prefix) so duplicate detection is consistent across horizontally scaled consumers.
- Replay guard keys must include `{issuer, jti}` (or equivalent globally unique tuple) and expire automatically at `expiresAt + bounded_skew`; consumers must emit overload metrics when replay-cache capacity limits are hit.
- On unknown `kid`, consumers must force-refresh attestation keys once and retry validation exactly once before failing closed.
- Validation failures must return canonical auth errors (`AUTH_SESSION_REVOKED`/`AUTH_UNAUTHORIZED_CONTEXT`), not generic transport errors.

All meta services use a shared `AuthTokenInterceptor` that extracts claims from the `Authorization` header and stores them in a thread-local `SessionContext`. Service methods read roles from this context via the `@RequireAdminRole` annotation (or similar). Gameplay services never read or propagate these claims.

### Mandatory Auth Middleware

All meta/control services that depend on JWT claims must install the shared security configuration that wires `AuthTokenInterceptor` into both HTTP and gRPC stacks. No controller or gRPC service that relies on authorization may be reachable without passing through this middleware. New routes that require authentication must opt into this configuration from the outset; adding endpoints that bypass it is considered an architectural violation and must be corrected before promotion to shared environments.

---

## Trust Boundaries and Token Validation

The Gateway sits at the edge of the platform and is deliberately **not** an authorization authority:

- Spring Cloud Gateway enforces the presence of an `Authorization` header for protected routes but does not validate or interpret JWT contents.
- All meta/control services that receive requests from the Gateway must validate JWTs using the Account Service JWKS and the shared `AuthTokenInterceptor`. No route that depends on JWT claims may bypass this middleware.
- Gameplay services never accept or validate browser- or client-supplied JWTs directly. They rely on the Game Session Service to enforce access based on Redis session context and server-to-server JWTs.

When adding a new public HTTP/gRPC route:

- Classify it using the shared classes from [Authorization Route Matrix](./system-architecture-authz-route-matrix.md): `public`, `account_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, or `cross_tenant_data_bearing`.
- For all non-public routes, require `AuthTokenInterceptor` and the Tenant Authorization Contract described above.
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, exporting data), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`).
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.
- Register the route and its classification in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md) so middleware and CI policy checks can enforce consistency.

## Session Lifecycle and Rebinding

Gameplay takeover, reconnect, token refresh, membership-version handling, and control-plane logout behavior are defined in [Session Behavior](./system-architecture-session-behavior.md). This parent doc keeps the admission and authorization model while the sibling doc carries the long-form lifecycle rules.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — supports prompt or argument input |
| JWT Usage | Issued to backend services and first-party admin/creator web UIs; gameplay protocol clients never see or send tokens |
| Claims | `iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, `globalRoles[]`, `scopedRoles{}` |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Session TTL | Derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS` + `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` |
| Gameplay Reauthentication | Required after disconnect; client re-issues `LOGIN`, and Game Session resumes via Redis if the underlying gameplay and auth session state are still valid |
| Role Enforcement | Meta/control services validate JWTs directly; gameplay services trust Game Session Service plus validated `SessionAttestation` |
| Role Updates | Refreshed in-session; no client interaction needed |
| Multi-Client Behavior | One session per character; new login replaces old session |
| Two-Factor Auth | Optional TOTP for admin and moderator accounts via `/auth/login`; required for plaintext Telnet logins when `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled |

---

## Related Documentation

- [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication)
- [Authorization Route Matrix](./system-architecture-authz-route-matrix.md)
- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Session Behavior](./system-architecture-session-behavior.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](./user-journeys-players.md#1-sign-up)
