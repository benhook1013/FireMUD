# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands for gameplay protocol clients and via `/auth/login` or equivalent flows for first-party web UIs. Clients are stateless; server-side “sessions” are split between gameplay bindings in Redis and short-lived issued-token registry records in Coordination Redis. The Game Session Service restores gameplay session state from Redis, while the Account Service validates the supplied login secret and issues internal JWTs. Gameplay protocol clients (Telnet and WebSocket) never see these JWTs directly; first-party admin/creator web UIs and backend services use them for meta/control APIs. First-party gameplay WebSocket clients also carry a short-lived edge connect token at handshake time as described below. [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md) permits optional provider-specific HTTPS sign-in only after complete provider proof; password and verified-email code remain the Account-owned baseline and fallback, and Telnet never carries provider credentials.

## Implemented Status

- Prompt-based `LOGIN` flows (username then password prompts) are part of the target protocol design; until they are fully implemented across transports, clients should use `LOGIN <username> <secret>` / `LOGON ...`. The secret is a password or an active verified-email login code according to the account's selected mode.
- Character selection resolves a concrete `{tenantId, gameInstanceId, characterId}` target, while single-controller takeover authority is canonicalized on `{tenantId, playableStateNamespaceId, characterId}`.
- First-party `/ws/game/**` now uses the concrete bootstrap path documented below: `POST /auth/player-bootstrap`, bootstrap-backed `POST /auth/connect-token`, gateway connect-token enforcement plus signed connect-context, then bare first-party `LOGIN` followed by `PLAY`.
- The browser-safe `Firemud-Connect-Token` HttpOnly cookie carrier is now implemented for first-party browser gameplay. Non-browser clients may still use the dedicated `X-Firemud-Connect-Token` header carrier, but Gateway rejects handshakes that try to present both carriers at once.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook, but current role-refresh token regeneration and periodic active-session Service JWT rotation remain implementation gaps; the placeholder response is not proof of refresh.

## Contract Decisions (Normative)

The following contract decisions are mandatory and resolve cross-document ambiguity:

- **Revocation writer authority** – The Account Service owns durable monotonic auth generations and is the sole writer of `session:auth:generation:*` projections. Other services publish billing/security events and must not write these generation keys directly.
- **Tenant generation scope** – `session:auth:generation:tenant:<tenantId>` applies to tenant-scoped regular and gameplay-affecting operations. It does not block explicitly classified billing-safe or support-safe routes.
- **Membership generation scope** – `session:auth:generation:membership:<accountId>:<tenantId>` applies to caller-bound tenant authorization for one account in one tenant and advances when membership or tenant roles change without triggering a tenant-wide billing cutoff.
- **Gameplay session identity key** – Session uniqueness and takeover scope are keyed by `{tenantId, playableStateNamespaceId, characterId}`. The admitted binding separately retains `gameInstanceId` for runtime routing and fences.
- **JWT claim contract** – Services must validate a strict JWT claim profile (required claims and audience per token profile), not only signature plus ad-hoc fields.
- **Internal gameplay delegation boundary** – Gameplay services authenticate the concrete mTLS workload identity, enforce an exact method-level caller allowlist, and validate a typed `PlayerExecutionContext` against request and domain scope.
- **No universal player attestation** – Routine gameplay delegation does not use signed per-action player attestations or a replay cache. Mutation replay is controlled by the owning command/effect/request idempotency contract.
- **Route classification governance** – Protected routes must be classified in the shared route matrix document and enforced through middleware annotations/interceptors; behavior must not rely on per-service ad-hoc interpretation.
- **Gameplay session indexing** – Game Session is the authoritative writer for bounded secondary indexes that map gameplay bindings by uniqueness key, account/tenant scope, and tenant scope so takeover, reconnect, and revocation do not require scans.
- **Gameplay admission semantics** – `LOGIN` authenticates account identity, while `PLAY` binds gameplay identity and gameplay scope. These must remain distinct concepts even when a client UX makes them feel nearly back-to-back.

## Responsibility Split

- **Account Service** – Verifies login secrets according to account-selected password/email-code modes, issues JWTs, and publishes JWKS for validation.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected control-plane routes but does not validate control-plane JWTs. The deliberate exception is `/ws/game/**` edge admission: Gateway validates short-lived gameplay connect tokens, performs replay checks, and emits a signed connect context for Game Session as specified in [Gateway Architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake).

[ADR 0022](./decisions/adr-0022-account-authority-and-gameplay-session-ownership.md) is the authority for this ownership split. Current implementation gaps in generation enforcement, monotonic membership versions, or gameplay token storage do not transfer authority to another service.

The implemented ordinary account login modes are `PASSWORD` and verified-email `EMAIL_OTP`; the REST and gRPC ordinary authentication contracts do not carry a separate `otp` field. Authenticator TOTP is required target-state work only for bounded global privileged elevation, not ordinary gameplay. Public player-facing text clients use Telnet-over-TLS, while plaintext Telnet is limited to local, test, and explicitly private-network compatibility. TOTP is not a transport gate or a substitute for channel protection; [ADR 0033](./decisions/adr-0033-public-player-facing-telnet-requires-tls.md) owns that boundary.

### Ordinary Login and Sensitive-Action Step-Up

- Ordinary Telnet, gameplay bootstrap, and account/control login use the account-selected `PASSWORD`, verified `EMAIL_OTP`, or both. Gameplay never solicits TOTP or repeats account authentication per command, and a gameplay session cannot become elevated control-plane authority.
- Routine gameplay and ordinary tenant-scoped creator or moderation work rely on their existing authenticated session, tenant capabilities, route policy, and audit. They do not trigger an unexpected factor prompt.
- Account email/password/factor changes, external-identity changes, account deletion, new real-money charges, payment-instrument management, billing-owner transfer, and global administration complete only through the HTTPS account/control plane. The client may be web, native, or CLI; raw Telnet cannot complete them.
- Sensitive personal and billing mutations require recent ordinary reauthentication. Entering a bounded `platformAdmin` or cross-tenant `billingAdmin` elevated window additionally requires an independently enrolled TOTP. That factor is supplied once per elevated window rather than once per action and never appears in gameplay.
- Gameplay may explicitly initiate a sensitive commercial or account action and receive a short-lived, single-use opaque HTTPS handoff URL. The handle grants no authority by itself and resolves to server-side intent bound to account, gameplay session, tenant where applicable, exact action, product and immutable amount/currency where applicable, and `requestId`. The HTTPS client independently authenticates, performs required step-up/provider work, and reports a verified idempotent outcome that gameplay may observe asynchronously.
- Spending an existing non-withdrawable premium balance remains gameplay. It requires exact purchase confirmation, idempotent identity, audit, and applicable caps, but no general account reauthentication. Withdrawal, cash redemption, or cash-equivalent transfer requires a new decision.

[ADR 0045](./decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) records this factor and protocol boundary.

Issued JWTs, registry records, revocation generations, and token-profile validation rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This document still defines how those token contracts are applied to route classification, gameplay admission, and tenant authorization, but it no longer carries the full token catalog inline.

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

Global roles also confer no authority after ordinary gameplay admission. Gameplay presence, commands, actor capabilities, and `PlayerExecutionContext` must ignore `globalRoles`; only explicit tenant-scoped gameplay grants may produce moderator, administrator, game-master, or equivalent in-world capabilities. A `platformAdmin`, `support`, or `billingAdmin` account that joins a public game without such a tenant-scoped grant appears and acts as an ordinary `PLAYER`. Break-glass platform operations remain separate audited control-plane actions and must not create a player actor or gameplay session.

The current target has no support impersonation, live-session attachment, or hidden-observer mode. Support uses minimized support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations. Adding any impersonation or observation product requires a new human-reviewed privacy, tenant-consent, notification, audit, and capability decision; implementations must not preserve speculative bypass hooks.

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
2. **Check issued-token registry** – Compute `tokenHash` and require one matching `session:auth:token:<tokenHash>` record in Coordination Redis. Validate its account, profile, `jti`, generation, and time fields against the already verified token. Missing or mismatched state means the token is revoked or unregistered and returns the canonical “session revoked” error (`AUTH_SESSION_REVOKED` or equivalent).
3. **Check revocation generations** – Enforce bulk revocation without relying on clocks, wildcard deletes, or key scans:
   - Require the token's `issuerAuthGeneration` to equal `session:auth:generation:issuer:<issuerId>` and its `accountAuthGeneration` to equal `session:auth:generation:account:<accountId>` for every protected route.
   - For tenant-scoped regular or gameplay-affecting routes, require the token's target-tenant generation to equal `session:auth:generation:tenant:<tenantId>`.
   - For tenant-scoped regular or billing-safe tenant-scoped routes, require the token's target-membership generation to equal `session:auth:generation:membership:<accountId>:<tenantId>`.
   - Billing-safe and support-safe routes do not apply the tenant billing generation merely because they target a tenant; role, membership-generation, live-authority, and route-classification checks still apply as declared.
   - Missing, malformed, unavailable, or lower-than-durable projection state fails closed; consumers never infer an initial generation locally.
4. **Apply route classification** – Every protected route is classified as one of the following, and the middleware must enforce the corresponding registry and role rules:

| Route classification | Required issued-token state | Required role checks | Tenant generation applied? | Tenant validation rules |
| --- | --- | --- | --- | --- |
| Public | *(none)* | *(none)* | No | *(none)* |
| Account-scoped | One matching token record | Require authenticated caller; enforce subject binding (`accountId` path/body must match caller) unless route explicitly allows `platformAdmin` override | No | No tenant scope for auth |
| `player_bootstrap_tenant` | One matching token record | Require a valid player-bootstrap token profile for the authenticated account | No | Used only for gameplay bootstrap routes such as `POST /auth/connect-token`; tenant access is established by live membership, entitlement, and admission-pointer checks during connect-token issuance |
| Pre-tenant discovery | One matching token record | Require authenticated caller; no caller-supplied `tenantId` is trusted yet | No | Used only for authenticated lobby/discovery surfaces such as `WORLDS`; services must derive visible tenants by filtering authoritative membership/entitlement data server-side. Global roles do not widen gameplay discovery. |
| Tenant-scoped (regular) | One matching token record | Require a tenant role in `scopedRoles[tenantId]` that authorizes the operation (for example `tenantAdmin`, `designer`, `moderator`, `player`) or an explicitly documented route-level `platformAdmin` allowance | Yes | `tenantId` must be in `scopedRoles` for tenant-role callers unless a specific route explicitly allows a global-role override. `billingAdmin` and `support` must be rejected for `tenant_regular`. Gameplay admission/switching routes must not use `platformAdmin` as an implicit override and must enforce caller-bound gameplay membership plus DB/query scoping by `tenantId`. |
| Billing-safe (tenant-scoped) | One matching token record | Require caller-bound tenant membership with `tenantAdmin` for the target tenant | No tenant-billing generation; yes membership generation | `tenantId` must be validated against caller tenant scope; services must perform a live caller-bound membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing billing-safe mutations; this route must remain reachable even when the tenant is `suspended`/`canceled` for gameplay, but it must fail immediately after caller membership/role revocation via the membership generation or the live membership check |
| Cross-tenant (support-safe) | One matching token record | Require `support` or `platformAdmin` | No | Tenant parameters are allowed only because the caller holds a cross-tenant support role; responses must be limited to high-level, troubleshooting-safe data (for example derived entitlements and subscription status, not invoices/payment methods); log/audit the target tenant |
| Cross-tenant (billing-safe) | One matching token record | Require `billingAdmin` or `platformAdmin` | No | Tenant parameters are allowed only because the caller holds a global billing role; log/audit the target tenant |
| Cross-tenant (data-bearing) | One matching token record | Require `platformAdmin` | Yes when operation targets tenant-scoped data | Tenant parameters are allowed only because the caller holds `platformAdmin`; log/audit the target tenant |

Protected routes that are absent from the route matrix must fail CI and deployment policy checks. If misconfiguration reaches execution, runtime middleware must reject the unclassified protected route rather than approximating it as `tenant_regular` or another route class.

Billing-safe mutation membership contract (normative):

- Billing-safe tenant mutations must perform an authoritative, live membership/role check via Account Service API (`GetCallerTenantMembership(tenantId)` or protocol-equivalent) before mutation.
- JWT role claims are sufficient for routing and preliminary checks but are not sufficient alone for billing-safe mutations.
- If membership authority is unavailable, billing-safe mutations fail closed with canonical error `MEMBERSHIP_AUTH_UNAVAILABLE`; read-only billing-safe surfaces may return a retriable unavailable response using the same code.
- Immediate caller-bound revocation for tenant membership/role changes is enforced by `session:auth:generation:membership:<accountId>:<tenantId>` in addition to the live membership check; implementers must not rely on JWT expiry alone.
- Tenant-scoped membership checks use `GetCallerTenantMembership(tenantId)` and must bind the subject to the authenticated caller (`accountId` from token); clients must not provide an arbitrary target `accountId` on this path.
- Global billing roles (`billingAdmin`/`platformAdmin`) must use explicitly cross-tenant billing-safe route variants and must not rely on caller-bound tenant membership endpoints intended for `billing_safe_tenant`.
- Cross-tenant membership checks for billing/reporting use a separate admin API (`GetTenantMembershipForAccount(tenantId, accountId)` or equivalent) restricted to `billingAdmin`/`platformAdmin`.
- Membership responses must include `evaluatedAt` and `membershipVersion` fields so callers can audit freshness and detect stale reads.

1. **Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult the internal runtime entitlement surface (`GetTenantEntitlementsForRuntime(tenantId)` or protocol-equivalent) and enforce its operation-specific flags as well as quotas. `past_due` remains playable under ordinary quotas; `grace` preserves connected sessions and same-session resume but denies first-time public join, first/new gameplay bindings, new instances, scale-out, and quota growth; `suspended`/`canceled` denies gameplay. Billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.
2. **Entitlement freshness and continuity SLA** – A snapshot is fresh for 15 seconds from its authoritative `evaluatedAt`. Explicit public join, first/new gameplay binding, new instance/scale, quota increase, paid-feature activation, and capacity-creating cutover require a fresh snapshot and fail closed with canonical error `ENTITLEMENT_UNAVAILABLE` when refresh cannot establish one. Reconnect of the same resumable session and non-expanding restart/rollback/recovery may use a previously authoritative positive snapshot for at most five minutes when refresh is unavailable.
   - Entitlement snapshots must carry operation flags for public join, new gameplay binding, and new instance/scale authority plus `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
   - Last-known-good continuity is forbidden after observed `suspended`/`canceled`, revocation, explicit denial, a newer billing sequence, a sequence gap, or when no prior positive snapshot exists. Five minutes is a platform hard maximum; operators may only shorten or disable it.
   - Last-known-good entitlement continuity does not relax revocation-authority freshness. If the separate batched revocation reconciliation lease cannot be renewed, active authority terminates at its stricter 60-second bound.
   - On detected sequence gaps, services must reconcile by calling `GetTenantEntitlementsForRuntime(tenantId)` before retrying admission.
   - Existing uninterrupted sessions do not re-read entitlement state per action. Observed hard billing states still revoke them through sequenced events and tenant auth-generation advancement, with batched reconciliation bounding missed-event exposure to 60 seconds as defined in Session Behavior.

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
LOGIN <username> <secret>
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

- Prompt-based `LOGIN` remains the target behavior for Telnet and generic WebSocket clients, but until the prompt flow is fully implemented those transports currently require `LOGIN <username> <secret>` and return `PROMPT_LOGIN_UNSUPPORTED` on bare `LOGIN`.
- First-party `/ws/game/**` remains the exception: once Gateway has validated a connect token and attached a signed connect context, bare `LOGIN` is the canonical bootstrap-backed path and must not prompt for or replay credentials. The current implementation now includes dedicated `POST /auth/player-bootstrap` and `POST /auth/connect-token` endpoints, gateway-side handshake rejection for missing, expired, replayed, or scope-mismatched connect tokens, and Game Session validation of the signed connect context before admitting bare first-party `LOGIN`.

Telnet-specific smart-client attach hints, if they return later, should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line. Those hints remain advisory transport metadata only, are not authentication material, and never bypass the canonical `LOGIN` + `PLAY` authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

Any future hidden attach hints may include a target `{gameInstanceId, tenantId}` for advanced clients, but the canonical source of gameplay target selection remains the authenticated lobby/admission flow. Clients must not rely on unauthenticated transport hints to bypass membership, entitlement, or world-visibility checks.

Admission-routing convergence rule:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, `POST /auth/connect-token`, and reconnect validation must all consume the same authoritative realm-catalog and `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract).
- Those surfaces may expose different projections of the same routing truth, but they must not maintain separate interpretation rules for which realm maps to which admissible `gameInstanceId`.
- If pointer state is missing, ambiguous, or no longer matches the selected realm target, the flow fails closed with admission-routing errors such as `ADMISSION_POINTER_UNAVAILABLE` or `CONNECT_SCOPE_MISMATCH` rather than silently rebinding the player to a different runtime target.

### WebSocket Connect Token Contract (`/ws/game/**`)

For first-party WebSocket clients, the control plane issues a short-lived connect token used only for handshake-time edge policy (for example tenant-aware rate limiting before `LOGIN` completes).

FireMUD standardizes a dedicated **player bootstrap** contract for first-party gameplay web/mobile clients:

- The first-party player UI authenticates directly against a dedicated bootstrap endpoint (for example `POST /auth/player-bootstrap`) using the same primary account-secret and abuse policy as gameplay login.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It is not derived from an existing admin/creator browser session and must not require or return a control-plane Browser JWT.
- On success, the endpoint returns one short-lived, memory-only **player bootstrap token** plus expiry metadata.
- This bootstrap token is not a control-plane Browser JWT and must not be accepted on admin/creator APIs.
- It is still an Account Service-issued JWT profile and must carry at least `iss`, `sub`, `accountId`, `aud=player-bootstrap`, `jti`, `iat`, `nbf`, and `exp`, backed by one `session:auth:token:<tokenHash>` record so account-level revocation and logout semantics apply.
- Audience/scope is limited to first-party gameplay bootstrap functions such as discovery and `POST /auth/connect-token`.
- Lifetime is intentionally short (target <= 5 minutes), stored in memory only, and cleared on tab reload/logout.
- `POST /auth/connect-token` must derive caller identity from this bootstrap token; clients must not supply an arbitrary `accountId`.
- The subsequent gameplay `LOGIN` remains mandatory but, for first-party `/ws/game/**` clients, it must complete using the already-verified bootstrap/connect context rather than requiring the browser to re-submit account credentials. In other words, first-party bare `LOGIN` on `/ws/game/**` is an identity-consumption/binding step, not a second credential-entry step. A mismatch between the verified bootstrap identity and the gameplay login result is a hard failure and the connect context must not be honored.

- Bootstrap issuance API: Account Service endpoint (for example `POST /auth/player-bootstrap`) that authenticates the player account for first-party gameplay bootstrap only and returns one short-lived bootstrap token plus expiry metadata.
- Issuer: Account/authentication control-plane only, after direct player-account authentication. Tenant membership and entitlement checks do not occur here because no gameplay tenant has been selected yet.
- First-party bootstrap ownership: Account Service owns `POST /auth/player-bootstrap`, bootstrap discovery, explicit `/auth/bootstrap/join`, `POST /auth/connect-token`, and membership lifecycle. Game Session exposes the equivalent text `JOIN` command and owns in-socket `LOGIN`/`PLAY`, but delegates membership mutation to Account and never creates it during `PLAY`.
- Bootstrap-discovery APIs: authenticated first-party HTTP endpoints (for example `GET /auth/bootstrap/worlds`, `GET /auth/bootstrap/worlds/{world}/realms`, `GET /auth/bootstrap/worlds/{world}/realms/{realm}/characters`) that accept only the `player-bootstrap` token profile and return the canonical lobby discovery data used to choose a target before socket open.
  - These endpoints are the canonical pre-socket discovery path for first-party clients.
  - They must apply the same caller-bound membership, realm visibility, and entitlement filtering rules as in-band `WORLDS` / `REALMS` / `CHARS`.
  - Hidden or unauthorized tenants, realms, and characters must not be inferable by probing these endpoints.
  - Discovery responses must return a canonical connect-token selector for each admissible realm target. FireMUD standardizes this as an opaque `connectScopeId` plus resolved routing metadata.
  - `connectScopeId` is the only client-supplied selector accepted by `POST /auth/connect-token`; first-party clients must not invent or derive `tenantId` / `gameInstanceId` pairs locally from slugs.
  - Minimum selector fields returned by discovery for an admissible realm target: `connectScopeId`, `tenantId`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`.
  - `connectScopeId` is an opaque server-issued selector for one caller-visible realm target, not a durable public identifier. Clients may cache it only as a short-lived convenience token for reconnect/bootstrap flows and must be prepared to discard it when discovery, pointer version, or visibility state changes.
  - Discovery responses are snapshot proofs, not durable reservations. `evaluatedAt` and `connectScopeExpiresAt` describe the freshness window for the selector that was returned; they do not promise the realm remains admissible until gameplay starts.
  - `connectScopeId` must not outlive the routing truth it was derived from. If the realm's `pointerVersion`, visibility, or entitlement posture changes such that the previously discovered target is no longer admissible, `POST /auth/connect-token` must reject the stale selector rather than silently translating it to a newer target.
  - For non-public realms such as playtest forks, visibility is controlled by an explicit realm-access grant. The target minimum grant record is `{tenantId, worldSlug, realmSlug, accountId, grantedByAccountId, grantedAt, expiresAt?}`. The current first implementation centralizes Account Service grant authority and runtime reads, but expiry and tenant-admin management UX remain product/control-plane follow-through.
  - Realm-access grants are owned by Account Service. Account Service is the sole writer and read authority for grant visibility decisions; Game Session and frontend callers consume grant-filtered results and must not maintain independent grant stores.
  - `tenantAdmin` is the routine owner of creating and revoking realm-access grants for that tenant through Account Service-owned admin surfaces. `platformAdmin` may do the same only as break-glass support.
  - Required internal read contract: Account Service must expose a caller-bound runtime lookup for realm visibility/admission, for example `GetRealmAccessGrant(accountId, tenantId, worldSlug, realmSlug)` or a batch/list equivalent consumed by bootstrap discovery, in-band `REALMS`, `POST /auth/connect-token`, and `PLAY`.
  - Required semantics for realm-access-grant reads/writes:
    - idempotent create/revoke by `{accountId, tenantId, worldSlug, realmSlug}`
    - expired grants are treated as non-existent for visibility and admission
    - successful create/revoke must be immediately visible to subsequent discovery/admission reads
    - if grant authority is unavailable, non-public realm discovery and admission fail closed rather than falling back to stale local cache state
- Connect-token issuance API: control-plane endpoint (for example `POST /auth/connect-token`) that mints exactly one short-lived token per request and logs `accountId`, `tenantId`, `gameInstanceId`, `jti`, and issuance timestamp.
  - Minimum request fields: `connectScopeId`, `requestId`.
  - Minimum response fields for non-browser clients: `connectToken`, `expiresAt`, `accountId`, `tenantId`, `gameInstanceId`, `realmSlug`, `jti`, `issuedAt`.
  - Browser response mode: first-party browser clients receive the connect token as an HttpOnly cookie rather than as JavaScript-readable response data. The response still returns non-secret metadata (`expiresAt`, `accountId`, `tenantId`, `gameInstanceId`, `realmSlug`, `jti`, `issuedAt`) so the client can drive retry UX without reading the token.
  - Before issuance, Account Service must resolve `connectScopeId` to the canonical `{tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion}` tuple, perform a live membership/public-admission check for `{accountId, tenantId, worldSlug, realmSlug}`, a live runtime entitlement check for `tenantId`, and a live realm-routing read for the selected realm target via the Game Session control-plane API.
  - The resolved tuple used for issuance must be treated as immutable for that request. Issuance may succeed only if `connectScopeId`, current realm visibility/grant state, and current admission-pointer state still converge on the same target at evaluation time.
  - `requestId` is the idempotency key for connect-token issuance. Retrying the same `(accountId, connectScopeId, requestId)` must return the same token payload or the same deterministic application failure; callers must use a new `requestId` when intentionally starting a new issuance attempt after rediscovery.
  - If realm-routing state is unavailable or ambiguous, connect-token issuance fails closed with `ADMISSION_POINTER_UNAVAILABLE`.
  - If `connectScopeId` no longer resolves to the current admissible target for the selected realm, connect-token issuance fails closed with `CONNECT_SCOPE_MISMATCH`; it must not mint a token for a stale or non-admissible target and rely on `PLAY` to correct it later.
  - First-party clients may request connect tokens only for realm targets returned by the canonical bootstrap-discovery contract for that caller; hidden or unauthorized realms must not be inferable by probing connect-token issuance directly.
  - If the realm was only caller-visible through an explicit non-public access grant, connect-token issuance must re-check that grant at issuance time rather than trusting earlier discovery alone.
  - Missing required request/response fields are contract violations and must fail closed rather than being defaulted by callers.
- Transport: connect-token carriage on `/ws/game/**` handshake.
  - First-party browser clients use the cookie `Firemud-Connect-Token` set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The cookie value is the connect token; browser JavaScript must not read or persist it.
  - Server-side and non-browser clients may use the dedicated `X-Firemud-Connect-Token` handshake header.
  - Gateway must accept exactly one non-empty, single-valued connect-token carrier for non-proxy gameplay handshakes. Duplicate values, both carrier types, or a malformed carrier are rejected as `CONNECT_TOKEN_REJECTED` rather than choosing precedence.
  - Query-string carriage is not a supported connect-token carrier in player-facing environments.
- Required claims: `accountId`, `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`, `connectScopeId`, `requestId`, `exp`, `jti`.
- Lifetime: a platform hard maximum of 30 seconds from issuance to `exp`; issuers may shorten but not widen it, and Gateway independently enforces the maximum.
- Signing and verification: token is signed by the Account/authentication control-plane key set and verified only at Gateway for `/ws/game/**` policy decisions.
- Replay defense: gateway validates `jti` against a bounded replay cache and rejects replays until token expiry.
  - Replay cache owner: Gateway.
  - Replay key format: `gateway:connect-token:jti:<jti>`.
  - Replay TTL: through `exp + bounded_skew`, covering the token's complete acceptance window.
  - Capacity policy: bounded cardinality with deterministic eviction (`oldest-expiry-first`) and overload metrics when capacity limits are reached.
- Enforcement:
  - `/ws/game/**` is the only gameplay WebSocket route.
  - Non-proxy gameplay clients must present a valid connect token; missing, invalid, expired, replayed, scope-mismatched, or replay-protection-unavailable token state is rejected with HTTP `403` and the bounded handshake classes defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - TCP Proxy bridge traffic is admitted without a connect token only when the gateway authenticates the proxy identity over the internal mTLS listener and header-trust checks pass.
- Error mapping: connect-token admission failures map to HTTP `403` at handshake, with specific `CONNECT_*` classes when the gateway can classify the failure.

The connect token is not a gameplay authorization grant and does not replace the canonical `LOGIN` + `PLAY` flow. It is an edge-admission artifact bound to a prior first-party bootstrap identity, not a substitute for gameplay authentication or gameplay binding.

#### Gateway-to-Game Session connect context (normative)

Gateway verification of the presented connect token, whether from `Firemud-Connect-Token` cookie or `X-Firemud-Connect-Token` header, must not be translated into trust of raw forwarded headers. For first-party `/ws/game/**` handshakes, Gateway must attach a short-lived signed connect context that Game Session verifies before applying connect-token scope checks.

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
4. Open gameplay WebSocket on `/ws/game/**` with connect-token carriage appropriate to the client. Browser clients use the `Firemud-Connect-Token` HttpOnly cookie set by `POST /auth/connect-token`; server-side and non-browser clients may use `X-Firemud-Connect-Token`.
5. Complete gameplay authentication in-band using `LOGIN` (or `LOGON`) and then lobby binding with `PLAY`.

Normative constraints:

- First-party clients must not treat successful handshake as gameplay authentication; gameplay remains unauthenticated until `LOGIN` succeeds.
- `/ws/game/**` requires a valid connect token for non-proxy clients and rejects missing tokens with `403`.
- For first-party `/ws/game/**` clients, bare `LOGIN` (or `LOGON`) must complete gameplay authentication by consuming the verified connect context plus the bootstrap identity already bound to that context. Browsers must not be required to replay credentials after bootstrap.
- Third-party clients and non-bootstrap transports continue to use credential-bearing `LOGIN <username> <secret>` or the prompt flow.
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
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

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

POST /auth/bootstrap/join
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-join-1" }
-> { tenantId: "tenant-emberfall", membershipVersion: 1, joined: true }

GET /auth/bootstrap/worlds/emberfall/realms/production/characters
Authorization: Bearer <bootstrapToken>
-> []

POST /characters
Authorization: Bearer <bootstrapToken>
{ worldSlug: "emberfall", realmSlug: "production", name: "Mara", template: "human-fighter" }
-> { characterName: "Mara", characterId: "char-9001" }

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-connect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "tenant-emberfall", realmSlug: "production", gameInstanceId: "production", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

Required postconditions for the explicit public-production join:

- the account now has canonical `player` membership for the tenant;
- future `WORLDS` results for that account may rely on membership rather than public discovery alone; and
- later character, token, socket, or `PLAY` failure does not remove the intentionally joined membership.

Canonical character-creation contract for this flow:

- The player-facing control-plane surface is `POST /characters` using the current bootstrap-authenticated account identity plus the selected `{worldSlug, realmSlug}` target.
- Entity Management owns the underlying `CreateCharacter` semantics and persistence contract; Account Service/authentication docs define the admission prerequisites for when this route may be called.
- `POST /characters` is allowed only after the caller has explicitly joined the public production game or already has the required membership/grant, and before `POST /auth/connect-token` / gameplay `PLAY` succeed for that new character.
- The route must reject requests for realms that are not currently visible/admissible to the bootstrap-authenticated account.
- The current realm-scoped backend creation substrate carries `{tenantId, accountId, name, gameInstanceId, playableStateScope}` into Entity Management. The richer player-facing descriptor for template/race/class/options is still a required product contract before first-party clients can render nontrivial character creation without game-specific assumptions.

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
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "tenant-demo", realmSlug: "playtest-docks", gameInstanceId: "playtest-docks", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
CHARS demo playtest-docks
PLAY demo playtest-docks Mara
OK PLAY Entered Demo World / Playtest Fork as Mara
```

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The client emits one of the canonical gameplay-login forms:
   - `LOGIN <username> <secret>` (or `LOGON ...`) for Telnet, generic WebSocket, and any non-bootstrap transport.
   - bare `LOGIN` / `LOGON` on first-party `/ws/game/**` after Gateway has validated a connect token and attached a signed connect context. In this case, the browser is completing gameplay auth from the previously established bootstrap identity rather than sending credentials a second time.
2. For credential-bearing login, the Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with `username` and one supplied `secret`. Account Service interprets that secret against the account's enabled `PASSWORD` and `EMAIL_OTP` modes. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs.
3. For bootstrap-backed first-party login, Game Session validates the signed connect context, binds it to the bootstrap-authenticated account identity established before `/auth/connect-token`, and obtains/refreshes the backend token material needed for subsequent internal calls. This path must not prompt for or require replay of account credentials from the browser.
4. The Account Service-backed credential path validates the supplied secret according to the enabled account modes and returns either a JWT + account metadata or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED`, or `AUTH_UPSTREAM_FAILURE`. The Game Session Service translates those codes into the text-protocol equivalents so WebSocket and Telnet clients always see the same response format regardless of upstream wording.
5. Success responses cause the Game Session Service to create/refresh Redis-backed gameplay session bindings and the Account Service to create or refresh the corresponding single issued-token registry record for backend use. The Game Session Service binds the socket to an authenticated account context and emits `OK LOGIN Logged in` (or equivalent account-confirming text) on the wire. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by both the authentication handshake (`LOGIN`) and the lobby selection step (`PLAY`). Any text command received before login should be rejected with stage-aware guidance such as `ERROR LOGIN_REQUIRED ...`, and any gameplay command received before `PLAY` should be rejected with stage-aware guidance such as `ERROR PLAY_REQUIRED ...`. Except in explicitly documented development/test bypass modes that grant temporary access, these commands are not processed for anonymous or unscoped sessions, keeping the gameplay queue free of unauthenticated traffic.

Credential-bearing login commands carry an account email/username and one secret. Bootstrap-backed first-party `LOGIN` carries no credentials because it consumes the already verified bootstrap/connect context. Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model).

### Tenant Selection for Gameplay (Lobby Selection)

FireMUD uses a **single shared entrypoint** for many worlds (tenants). After `LOGIN`, clients complete a lobby selection step that binds the authenticated connection to a specific world (`tenantId`), gameplay-admissible instance (`gameInstanceId`), and gameplay identity (`characterId`) before gameplay commands are accepted.

Players must never be asked to type platform-scope identifiers such as `tenantId`, `gameInstanceId`, or `characterId` during lobby selection. Lobby flows accept human-friendly world slugs, menu indices, and character names or indices and resolve them server-side. Gameplay may separately expose stable numeric runtime-entity IDs when useful for distinguishing visible live instances; those IDs remain scoped selectors rather than authorization.

After `LOGIN` succeeds, the Game Session Service requires an explicit lobby selection flow using these canonical commands:

- `WORLDS` – list worlds the authenticated account can enter (a numbered menu plus a stable world slug for each entry).
- `REALMS <world>` – list the visible realms for the selected world (`<world>` is a world index from `WORLDS` or a world slug). Responses include the default production realm plus any explicitly authorized additional realms such as playtest forks.
- `JOIN <world>` – explicitly create or return the caller's durable `player` membership for the world's public production realm. First-party clients expose the equivalent `Join & Play` action through Account bootstrap.
- `CHARS <world> [realm]` – list characters for the selected world and optional realm.
- `PLAY <world> [realm] [character]` – enter gameplay by selecting a world, an optional realm, and an optional character.

`public_production_onboarding` is the lobby route class for the default public production realm. Brand-new authenticated accounts may see that realm before membership exists, but must explicitly use `Join & Play` or `JOIN <world>` before character creation, connect-token issuance, or `PLAY`. The resulting membership is the intended durable account-to-game relationship used by later discovery and return flows.

Realm discovery and routing contract:

- A tenant may expose multiple player-addressable realms. Each realm-routing record is explicitly `OPEN` on exactly one admissible `gameInstanceId` or `CLOSED` with none and is owned by Game Session; visibility remains separately revisioned catalog/policy state.
- One realm may be designated as the default public production realm. In v1, this production realm is the only realm that may be publicly discoverable without an existing tenant membership row, and `public_production_onboarding` governs the first-join path through that realm.
- Additional realms are access-controlled in v1. Unauthorized or hidden realms must not appear in discovery, and non-production realms such as playtest forks require explicit access grants.
- Explicit access grants for non-public realms are sourced from Account Service runtime grant authority, not from Game Session-local configuration or frontend-cached state.
- Connect-token issuance, `REALMS`, `CHARS`, and `PLAY` must all consume the same realm-routing state so clients never infer realm identity from transport-side hints alone.
- Realm-routing state is split into the visible realm catalog plus the current admission pointer for one `{tenantId, worldSlug, realmSlug}` target. The realm catalog answers "is this realm visible and selectable for this caller?" while the admission pointer answers "which exact `gameInstanceId` is currently admissible for that realm?".
- Clients may cache visible realm choices for presentation, but admission-critical flows must re-read current pointer truth before binding or minting connect scope.

Lobby discovery source-of-truth contract:

- `WORLDS` must be sourced from Account Service tenant-membership, public-production discovery, and entitlement state (not from opportunistic local caches alone) so world visibility and billing state cannot drift across services.
- If entitlement refresh is unavailable, `WORLDS` may present a last-known visible game with an explicit availability-unknown state. Discovery is not authority: it cannot create membership, mint a connect token, bind gameplay, or start capacity, and the applicable strict or continuity entitlement check still runs before those operations.
- `REALMS <world>` must distinguish between public-production visibility and explicit realm grants. Only the default production realm may be visible through public discovery in v1.
- Bootstrap discovery, `REALMS`, `POST /auth/connect-token`, and `PLAY` must all consume the same Account Service-owned realm-access-grant authority for non-public realms so visibility and admission cannot drift by surface.
- Losing realm visibility or realm-grant authority must fail admission before gameplay binding; clients must not remain eligible for a non-public realm only because they still hold a stale discovery response.
- `CHARS <world> [realm]` must be sourced from the authoritative character store for the resolved `{tenantId, gameInstanceId}` target and filtered to the caller's valid character choices for that realm. Shared-state realms may surface the tenant's normal live characters, while isolated realms may surface copied, seeded, or otherwise instance-local character state for the same account.
- `WORLDS` and `CHARS` responses must not leak inaccessible tenants or characters; unresolved selectors return canonical errors (`WORLD_NOT_FOUND`, `WORLD_ACCESS_DENIED`, `CHARACTER_NOT_FOUND`, `CHARACTER_ACCESS_DENIED`) without exposing whether a hidden tenant exists.

Lobby command classification contract:

- `WORLDS` is an authenticated **pre-tenant discovery** operation, not a normal tenant-scoped route. It runs after account authentication but before a single `tenantId` has been selected.
- `REALMS <world>` and explicit `JOIN <world>` participate in `public_production_onboarding` when the selected realm is the default public production realm. Brand-new authenticated accounts may discover that realm without a membership, but `CHARS`, character creation, connect-token issuance, and `PLAY` require the explicit join result or another applicable membership/grant.
- `REALMS <world>` remains a tenant-scoped discovery operation after `<world>` resolves to a canonical `tenantId`, but before the client is bound to one `gameInstanceId`.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` become tenant/realm-scoped only after `<world>` and optional `[realm]` are resolved server-side to canonical `{tenantId, gameInstanceId}`.
- Shared auth middleware and route-matrix entries must not model all lobby commands as one undifferentiated tenant-scoped surface.

The `PLAY` flow:

- Resolves `<world>` to a canonical `tenantId` and validates it exists.
- Resolves optional `[realm]` to a canonical realm for that tenant. If no realm is supplied, the tenant's default production realm is selected.
- Verifies that the account is authorized to play in that `tenantId` using caller-bound gameplay membership and any required realm grant. Global roles and public discoverability alone must not satisfy gameplay admission.
- If the public realm is visible but the account has not explicitly joined, returns `JOIN_REQUIRED` with `JOIN <world>`/`Join & Play` recovery guidance and does not create membership or other admission state.
- Membership creation writer authority remains Account Service through `JoinPublicProductionMembership(accountId, tenantId, worldSlug, realmSlug, requestId)` or protocol-equivalent. Game Session, connect-token issuance, character creation, and `PLAY` must not create membership implicitly.
- Performs an authoritative internal membership read for `{accountId, tenantId}` and persists the returned `membershipVersion` into the gameplay session binding on successful admission. The membership response must also assert `gameplayAdmissionAllowed=true`; gameplay admission must not source `membershipVersion` or gameplay authority from JWT claims or local caches.
- Consults the runtime entitlement contract `GetTenantEntitlementsForRuntime(tenantId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated).
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId, gameInstanceId}` according to the selected realm's character policy.
  - Explicit character creation and selection are part of the v1 contract. If the selected realm has no visible character for the caller, the client must complete the canonical character-creation flow before `PLAY` can succeed.
  - `PLAY` may omit `[character]` only when exactly one visible character exists for the resolved realm. Otherwise admission fails with `CHARACTER_REQUIRED`.
- Resolves the selected realm's gameplay-admissible instance and records that `gameInstanceId` in the gameplay binding.
  - First-party `/ws/game/**` contract: if a validated connect token is present, resolved `tenantId` and `gameInstanceId` must match token claims. On mismatch, reject admission with `CONNECT_SCOPE_MISMATCH` and do not bind session scope.
  - Runtime control-plane and admission flows use the realm-routing contract from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) as the source of truth for which concrete `gameInstanceId` is admissible for the selected realm.
- On successful admission, runtime must return the resolved realm bundle identity at minimum as `versionId`, optional `scriptPatchVersion`, and manifest location/hash (or a stable bundle token that resolves to those fields) so clients can apply realm-specific branding and assets.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Ensures the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, regionId>`. Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, `/ws/game/**` is routed to a stable Game Session service endpoint and the edge does not implement a lease-aware shard routing plane.

`PLAY` returns canonical, stable error codes so clients can recover deterministically:

- `WORLD_NOT_FOUND` – the supplied world selection cannot be resolved to a tenant.
- `WORLD_ACCESS_DENIED` – the authenticated account is not authorized for gameplay admission in the tenant under caller-bound membership authority. Global roles alone must not satisfy this check.
- `PUBLIC_PRODUCTION_ADMISSION_DENIED` – the caller does not satisfy the public-production admission policy for the selected default production realm, or the realm is no longer eligible for public first admission.
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
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_MISSING` | Connect token is absent where required | Obtain a fresh connect token and open a new socket with bounded retry/backoff. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_EXPIRED` | Connect token expired before gateway validation completed | Obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Obtain a fresh connect token and open a new socket with bounded retry/backoff; repeated replay failures should not fast-loop. |
| `/ws/game/**` handshake (`403`) | `CONNECT_SCOPE_MISMATCH` | Handshake-carried scope does not match the verified connect-token scope | Rerun bootstrap discovery for the intended realm target, obtain a fresh connect token, and open a new socket. |
| `/ws/game/**` handshake (`403`) | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded slower backoff and surface temporary edge-auth-unavailable context rather than backend-outage messaging. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REJECTED` | Connect token is malformed, signature-invalid, missing required claims, wrong-audience, or otherwise rejected outside the narrower classes above | Obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `POLICY_DENY` | Edge policy rejects the handshake for a non-token reason (for example proxy trust/config mismatch) | Treat as non-retriable until operator/client configuration is corrected. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_CONTEXT_INVALID` | Required gateway-signed connect context is missing, expired, unverifiable, or otherwise invalid | Refresh connect token, reconnect, then re-`LOGIN`; do not retry `PLAY` on the current socket. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_SCOPE_MISMATCH` | Requested `{tenantId, gameInstanceId}` does not match the validated connect-token scope | Re-select the intended world, obtain a fresh connect token for that target, reconnect, and retry `PLAY`. |
| `LOGIN` on first-party `/ws/game/**` | `ACCOUNT_MISMATCH` | Bootstrap-backed login resolved to an account different from the validated connect-context subject | Treat as a hard auth failure for the current socket; clear the gameplay bootstrap/connect flow and require a fresh authenticated bootstrap. |
| `PLAY` | `WORLD_ACCESS_DENIED` | Caller-bound membership authority does not allow gameplay admission for the resolved tenant | Keep auth state, surface an authorization error, and do not infer hidden-tenant existence beyond the canonical code. |
| `PLAY` | `TENANT_BILLING_BLOCKED` | Tenant entitlement state is `suspended` or `canceled` for gameplay | Keep auth state, surface a billing-blocked state for that tenant, and disable gameplay admission flows. |
| New commitment or ineligible continuity operation | `ENTITLEMENT_UNAVAILABLE` | Fresh entitlement authority is unavailable and no operation-eligible last-known-good snapshot exists | Keep auth state, retry with bounded backoff, and never use grace after hard denial, revocation, or sequence uncertainty. |
| Gameplay command before `PLAY` | `PLAY_REQUIRED` | Client issued a world-scoped gameplay command before lobby admission completed | Keep auth state and route the client back through `PLAY`, `REALMS`, or `CHARS` as appropriate. |

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss) or when server-side auth state has expired or been revoked. After a reconnect, clients always issue a fresh `LOGIN` and then complete lobby selection again (`PLAY <world> [realm] [character]`). Game Session resolves the selected instance's `playableStateNamespaceId`; if a resumable gameplay session exists for `{tenantId, playableStateNamespaceId, characterId}`, it resumes or takes over that binding, otherwise it creates a fresh gameplay session binding.

Gameplay identity is canonicalized on `characterId` within a tenant and playable-state namespace. All Redis key formats and Game Session Service APIs must treat `characterId` as the abstract character identifier so sessions bind sockets to characters rather than raw accounts. Canonical takeover and resume identity is `{tenantId, playableStateNamespaceId, characterId}`; `gameInstanceId` remains part of the admitted routing bundle.

Gameplay identity has one authoritative command controller per `{tenantId, playableStateNamespaceId, characterId}` under [ADR 0128](./decisions/adr-0128-namespace-scoped-single-character-controller.md).

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Related Token and Session Contracts

The detailed token and lifecycle contracts now live in focused sibling docs:

- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) defines JWT claim requirements, token profiles, issued-token registry records, revocation generations, and Redis-outage behavior for token validation.
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
- Gameplay services must treat tenant/session/player identifiers in requests as scoped data that requires validation. Client-supplied headers cannot create trusted context, and an authenticated workload may invoke only explicitly allowlisted methods.

### Gameplay Player Execution Context Contract (Normative)

When one trusted gameplay workload calls another on behalf of a player, the request carries a typed protobuf `PlayerExecutionContext` with the required subset of:

- `accountId`
- `tenantId`
- `gameInstanceId`
- `characterId`
- `sessionId`
- applicable room, region, lease/epoch, admitted-bundle, realm, pointer, or playable-state scope
- stable request, command, or effect identity where the operation requires it

`PlayerExecutionContext` is unsigned structured scope data, not a credential. Consumers authenticate the immediate caller through its concrete mTLS certificate identity, check the RPC's caller allowlist, validate context/request equality, and enforce the complete tenant/game/resource and domain-ownership scope in existing reads and writes. These checks must not add a fresh Account, Redis, or database lookup solely to authorize every routine action.

Gameplay mutations use their command/effect/request idempotency contract. Reads do not use a generic replay store. FireMUD deliberately accepts that a compromised allowlisted intermediary can fabricate player context for methods it is permitted to call; [ADR 0024](./decisions/adr-0024-trusted-gameplay-workload-delegation.md) records that trust boundary and the separate protections for operator and financial actions.

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
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, or tenant-scoped data export), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`). Full account export remains `account_scoped` and must not be used as the suspended-tenant recovery export.
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
| Role Enforcement | Meta/control services validate JWTs directly; gameplay services enforce concrete mTLS workload identity, method caller allowlists, and validated `PlayerExecutionContext` scope |
| Role Updates | Refreshed in-session; no client interaction needed |
| Multi-Client Behavior | One session per character; new login replaces old session |
| Login Modes | `PASSWORD` and verified-email `EMAIL_OTP` are the current account-level modes; authenticator-app factors remain future work |

---

## Related Documentation

- [Authorization Route Matrix](./system-architecture-authz-route-matrix.md)
- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Session Behavior](./system-architecture-session-behavior.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](./user-journeys-players.md#1-sign-up)
