# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues the exact JWT profiles defined by the token contract, manages session state, and enforces role-based access across services.

Authentication is performed via credential-bearing `LOGIN` commands for raw Telnet gameplay clients, `/auth/player-bootstrap` plus connect-token flows for first-party gameplay clients, and `/auth/login` only for control-plane UIs or other explicitly classified control-plane clients. Clients are stateless; server-side “sessions” are split between gameplay bindings in Redis and short-lived issued-token registry records in Coordination Redis. The Game Session Service restores gameplay session state from Redis, while the Account Service validates the supplied login secret and issues the exact `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT profile required by the destination. Raw Telnet gameplay command streams never carry JWT authorization. Browser/mobile gameplay clients temporarily use a `player-bootstrap` JWT for HTTPS bootstrap calls and a cookie-carried one-use connect token for the `/ws/game/**` handshake; explicitly classified non-browser WebSocket clients use the same bootstrap/connect-token contract with the dedicated handshake header. First-party admin/creator UIs and backend services use their own permitted token profiles. Accounts may also authenticate using linked external providers such as Google, Discord, or Steam.

## Implemented Status

- Prompt-based `LOGIN` flows (username then password prompts) are part of the target protocol design; until they are fully implemented across transports, clients should use `LOGIN <username> <secret>` / `LOGON ...`. The secret is a password or an active verified-email login code according to the account's selected mode.
- Character selection and gameplay takeover semantics are canonicalized on `{tenantId, gameInstanceId, characterId}`.
- First-party `/ws/game/**` now uses the concrete transport path documented below: tenant-free, factor-aware `POST /auth/player-bootstrap`, bootstrap-backed `POST /auth/connect-token`, gateway connect-token enforcement plus signed connect-context, then bare first-party `LOGIN` followed by `PLAY`. Explicit `JOIN`/`Join & Play` through target-state `POST /auth/bootstrap/join` (not implemented) and removal of implicit membership creation from connect-token/`PLAY` remain tracked gaps.
- First-party browser and mobile gameplay use the short-lived `player-bootstrap` JWT for HTTPS bootstrap calls and carry the resulting gameplay-connect token only in the `Firemud-Connect-Token` HttpOnly cookie, including mobile/server-side cookie jars. Explicitly classified non-browser WebSocket clients use the same bootstrap contract and the dedicated `X-Firemud-Connect-Token` handshake header. Telnet and other non-WebSocket text transports use credential-bearing `LOGIN` and do not carry public JWTs or connect tokens.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook, but current role-refresh token regeneration and periodic active-session `game-session-account-delegation` rotation remain implementation gaps; the placeholder response is not proof of refresh.
- The current Account `Authenticate` proto path still lacks the target `requestId`/immutable-digest replay envelope and orphan-token retirement contract described below; those fields and recovery semantics remain implementation/proof gaps rather than implied current behavior.
- Account's JWKS endpoint and conditional secret watcher are implemented, but Account-only asymmetric validation, non-exportable signer delegation, rotation/convergence, issued-token registry enforcement, and Account-owned authority generations remain target-state. No authority-generation issuance, advancement, propagation, or validation proof is currently claimed.

## Contract Decisions (Normative)

The following contract decisions are mandatory and resolve cross-document ambiguity:

- **Authority-generation writer** – The Account Service is the sole writer of issuer, account, tenant, and `{accountId, tenantId}` membership authority generations. Other services must publish billing/security events and must not write authority-generation state directly.
- **Tenant authority-generation scope** – The tenant authority generation applies to tenant-scoped regular and gameplay-affecting operations. It does not block explicitly classified billing-safe or support-safe routes.
- **Membership authority-generation scope** – The `{accountId, tenantId}` membership authority generation applies to caller-bound tenant authorization for one account in one tenant when membership or tenant roles change without triggering a tenant-wide billing cutoff.
- **Gameplay session identity key** – Session uniqueness and takeover scope are keyed by `{tenantId, gameInstanceId, characterId}`.
- **JWT claim contract** – Services must validate a strict JWT claim profile (required claims and audience per token profile), not only signature plus ad-hoc fields.
- **Internal gameplay delegation boundary** – Gameplay services authenticate the concrete mTLS workload identity, enforce an exact method-level caller allowlist, and validate a typed `PlayerExecutionContext` against request and domain scope.
- **No universal player attestation** – Routine gameplay delegation does not use signed per-action player attestations or a replay cache. Mutation replay is controlled by the owning command/effect/request idempotency contract.
- **Route classification governance** – Protected routes must be classified in the shared route matrix document and enforced through middleware annotations/interceptors; behavior must not rely on per-service ad-hoc interpretation.
- **Gameplay session indexing** – Game Session is the authoritative writer for bounded secondary indexes that map gameplay bindings by uniqueness key, account/tenant scope, and tenant scope so takeover, reconnect, and revocation do not require scans.
- **Gameplay admission semantics** – `LOGIN` authenticates account identity, while `PLAY` binds gameplay identity and gameplay scope. These must remain distinct concepts even when a client UX makes them feel nearly back-to-back.
- **Ingress identity validation** – Public and cross-service readers validate the declared shape of UUID-governed identifiers before authorization or lookup, then treat the values as opaque. Identifier contents never confer authority or determine tenant scope.

## Responsibility Split

- **Account Service** – Verifies login secrets according to account-selected password/email-code modes, issues JWTs, and remains authoritative for signing-generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations delegated by Account.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected control-plane routes but does not validate control-plane JWTs. The deliberate exception is `/ws/game/**` edge admission: Gateway validates short-lived gameplay connect tokens, performs replay checks, and emits a signed connect context for Game Session as specified in [Gateway Architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake).

[ADR 0022](./decisions/adr-0022-account-authority-and-gameplay-session-ownership.md) is the authority for this ownership split. Current implementation gaps in authority-generation enforcement, monotonic membership versions, or gameplay token storage do not transfer authority to another service.

### Client Classes and Token Carriage

- Telnet and other non-WebSocket text clients authenticate with credential-bearing `LOGIN <username> <secret>` (or the target prompt flow). They do not receive or transmit `control-ui`, `player-bootstrap`, private delegation, or gameplay-connect JWTs.
- First-party browser and mobile clients authenticate to `/auth/player-bootstrap`, keep that short-lived JWT in memory for bootstrap/discovery HTTP calls, and receive the gameplay-connect credential only as the HttpOnly `Firemud-Connect-Token` cookie. The cookie may be maintained by a mobile or server-side cookie jar; no header or response-body fallback exists.
- Explicitly classified non-browser WebSocket clients authenticate through the same bootstrap control plane and present the gameplay-connect token only through `X-Firemud-Connect-Token`.
- After Gateway validates and consumes the gameplay-connect credential, public non-proxy WebSocket clients use bare `LOGIN` followed by `PLAY`; no transport sends an end-user JWT as gameplay command authorization.

The implemented account login modes are `PASSWORD` and verified-email `EMAIL_OTP`. Authenticator-app TOTP enrollment remains future account-security work; the REST and gRPC authentication contracts do not carry a separate `otp` field. Public player-facing text clients use Telnet-over-TLS, while plaintext Telnet is limited to local, test, and explicitly private-network compatibility. TOTP is not a transport gate or a substitute for channel protection; [ADR 0033](./decisions/adr-0033-public-player-facing-telnet-requires-tls.md) owns that boundary.

Issued JWTs, registry records, authority generations, and token-profile validation rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This document still defines how those token contracts are applied to route classification, gameplay admission, and tenant authorization, but it no longer carries the full token catalog inline.

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

Tenant-scoped operational/design/runtime actions may allow `platformAdmin` per the route-class matrix, but gameplay admission and gameplay switching must not. Anonymous `WORLDS` may expose only the bounded public-production catalog. Authenticated discovery may combine caller-bound membership with public-production visibility. Character access, character creation, connect-token issuance, and `PLAY` require an existing caller-bound membership plus any applicable non-public realm grant; global roles or public visibility alone must never grant gameplay access. `billingAdmin` and `support` must never gain gameplay/design authority by virtue of being global roles.

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

1. **Validate the JWT** – Verify signature (JWKS), time-based claims (`exp`, `nbf`), and the expected token profile/audience (`aud`). Reject tokens with an unexpected profile (for example a `control-ui` JWT presented to a player-bootstrap endpoint).
2. **Check issued-token registry** – Compute `tokenHash` and require one matching `session:auth:token:<tokenHash>` record in Coordination Redis. Validate its account, profile, `jti`, per-lineage `tokenGeneration`, and time fields against the already verified token. Account-owned authority generations are a separate validation dimension; private-token refresh must compare-and-set the applicable durable authority generation so it cannot commit across logout-all or another security cutoff. Missing or mismatched state means the token is revoked or unregistered and returns the canonical “session revoked” error (`AUTH_SESSION_REVOKED` or equivalent).
3. **Check Account authority generations** – Enforce bulk revocation without relying on wildcard deletes, key scans, or JWT timestamps. Every route that accepts an issued JWT applies the issuer and account authority-generation checks below, unless the route is explicitly public or explicitly has no issued-token state. Tenant and membership generations are additional, route-class-specific checks; they do not replace the universal issuer/account checks. The route table distinguishes those conditional tenant and membership checks from the universal checks:
   - The issuer authority generation applies to every protected route and must advance for Account signing-key compromise or player-facing post-restore trust reset; it does not replace rejection of the affected `kid`.
   - The account authority generation applies to account-wide security cutoffs.
   - For routes classified as tenant-scoped regular or gameplay-affecting, the tenant authority generation applies to the requested tenant.
   - The caller-bound membership authority generation applies to `{accountId, tenantId}` for tenant-scoped regular and billing-safe routes, caller-membership-scoped lifecycle routes, `player_bootstrap_tenant` routes where declared, and public-production onboarding after membership is created. The route-class table below is authoritative for each classification.
   - For routes classified as billing-safe or support-safe, tenant authority generation does not by itself revoke access when the route explicitly remains billing-safe; role checks, membership authority, and route classification still apply.
4. **Apply route classification** – Every protected route is classified as one of the following, and the middleware must enforce the corresponding registry and role rules:

| Route classification | Required issued-token state | Required role checks | Universal issuer/account generations | Additional tenant/membership generations | Tenant validation rules |
| --- | --- | --- | --- | --- | --- |
| Public | *(none)* | *(none)* | None | None | *(none)* |
| Account-scoped | One matching token record for the exact profile declared by the route | Require authenticated caller; enforce subject binding (`accountId` path/body must match caller) unless route explicitly allows `platformAdmin` override | Issuer + account | None | No tenant scope for auth |
| Caller-membership-scoped | One matching token record for the exact profile declared by the route | Bind the subject to the authenticated caller and require a live current membership for the selected tenant; any current membership role may perform the explicitly allowlisted self-lifecycle action | Issuer + account | Membership | Used only for caller-owned membership lifecycle such as leaving a game. It accepts no arbitrary account target and no global-role override |
| `player_bootstrap_tenant` | One matching `player-bootstrap` token record | Require the `player-bootstrap` token profile for the authenticated account | Issuer + account | Membership where declared | Used only for gameplay bootstrap routes such as `POST /auth/connect-token`; `IssueConnectToken` must use current caller-bound membership authority generation plus live membership, entitlement, and admission-pointer checks |
| `public_production_onboarding` | No JWT for in-band commands; otherwise the exact route-declared profile | Require explicit caller-bound join before character creation, connect-token issuance, or `PLAY` | If a JWT route is used: issuer + account | Membership after join | Public-production discovery may precede membership, but join creates the durable Account-owned membership and does not grant gameplay authority from global roles |
| Pre-tenant discovery | No JWT for in-band commands; otherwise one matching record for the exact route-declared profile | Require authenticated caller; no caller-supplied `tenantId` is trusted yet | If a JWT route is used: issuer + account | None | Used only for authenticated lobby/discovery surfaces such as `WORLDS`; services must derive visible tenants by filtering authoritative membership/entitlement data server-side. Global roles do not widen gameplay discovery. |
| Tenant-scoped (regular) | One matching token record for the exact profile declared by the route | Require a tenant role in `scopedRoles[tenantId]` that authorizes the operation (for example `tenantAdmin`, `designer`, `moderator`, `player`) or an explicitly documented route-level `platformAdmin` allowance | Issuer + account | Tenant + membership | `tenantId` must be in `scopedRoles` for tenant-role callers unless a specific route explicitly allows a global-role override. `billingAdmin` and `support` must be rejected for `tenant_regular`. Gameplay admission/switching routes must not use `platformAdmin` as an implicit override and must enforce caller-bound gameplay membership plus DB/query scoping by `tenantId`. |
| Billing-safe (tenant-scoped) | One matching token record for the exact profile declared by the route | Require caller-bound tenant membership with `tenantAdmin` for the target tenant | Issuer + account | Membership | `tenantId` must be validated against caller tenant scope; services must perform a live caller-bound membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing billing-safe mutations; this route must remain reachable even when the tenant is `suspended`/`canceled` for gameplay, but it must fail immediately after caller membership/role revocation via the membership authority generation or the live membership check |
| Cross-tenant (support-safe) | One matching token record for the exact profile declared by the route | Require `support` or `platformAdmin` | Issuer + account | None | Tenant parameters are allowed only because the caller holds a cross-tenant support role; responses must be limited to high-level, troubleshooting-safe data (for example derived entitlements and subscription status, not invoices/payment methods); log/audit the target tenant |
| Cross-tenant (billing-safe) | One matching token record for the exact profile declared by the route | Require `billingAdmin` or `platformAdmin` | Issuer + account | None | Tenant parameters are allowed only because the caller holds a global billing role; log/audit the target tenant |
| `internal_workload` | Route-specific: no JWT or one exact private player-delegation profile | Exact mTLS workload identity and method caller allowlist; both constraints must pass | If a private JWT is accepted: issuer + account | Route-specific | Internal routes do not inherit an end-user JWT requirement. `game-session-account-delegation` is accepted only for its named receiver with audience `account-service`. |
| Cross-tenant (data-bearing) | One matching token record | Require `platformAdmin` | Issuer + account | Tenant when operation targets tenant-scoped data | Tenant parameters are allowed only because the caller holds `platformAdmin`; log/audit the target tenant |

Protected routes that are absent from the route matrix are currently recorded as inventory drift/gap because source-stable OpenAPI/protobuf coverage and comparison validation are incomplete. Runtime middleware must nevertheless reject every protected route whose classification is not deterministically known, immediately and independently of the inventory gate; it must not approximate the route as `tenant_regular` or another route class. Separately, CI and deployment policy checks must fail a validated candidate route that lacks matrix registration. The incomplete matrix must not be converted into generated policy, and its inventory failure must not weaken the runtime rejection.

Billing-safe mutation membership contract (normative):

- Billing-safe tenant mutations must perform an authoritative, live membership/role check via Account Service API (`GetCallerTenantMembership(tenantId)` or protocol-equivalent) before mutation.
- JWT role claims are sufficient for routing and preliminary checks but are not sufficient alone for billing-safe mutations.
- If membership authority is unavailable, billing-safe mutations fail closed with canonical error `MEMBERSHIP_AUTH_UNAVAILABLE`; read-only billing-safe surfaces may return a retriable unavailable response using the same code.
- Immediate caller-bound revocation for tenant membership/role changes is enforced by advancing the `{accountId, tenantId}` membership authority generation in addition to the live membership check; implementers must not rely on JWT expiry alone.
- Tenant-scoped membership checks use `GetCallerTenantMembership(tenantId)` and must bind the subject to the authenticated caller (`accountId` from token); clients must not provide an arbitrary target `accountId` on this path.
- Global billing roles (`billingAdmin`/`platformAdmin`) must use explicitly cross-tenant billing-safe route variants and must not rely on caller-bound tenant membership endpoints intended for `billing_safe_tenant`.
- Cross-tenant membership checks for billing/reporting use a separate admin API (`GetTenantMembershipForAccount(tenantId, accountId)` or equivalent) restricted to `billingAdmin`/`platformAdmin`.
- Membership responses must include `evaluatedAt` and `membershipVersion` fields so callers can audit freshness and detect stale reads.

**5. Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult the internal runtime entitlement surface (`GetTenantEntitlementsForRuntime(tenantId)` or protocol-equivalent) and enforce its operation-specific flags as well as quotas. `past_due` remains playable under ordinary quotas; `grace` preserves connected sessions and same-session resume but denies first-time public join, first/new gameplay bindings, new instances, scale-out, and quota growth; `suspended`/`canceled` denies gameplay. Billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.

**6. Entitlement freshness and continuity SLA** – A snapshot is fresh for 15 seconds from its authoritative `evaluatedAt`. Explicit public join, first/new gameplay binding, new instance/scale, quota increase, paid-feature activation, and capacity-creating cutover require a fresh snapshot and fail closed with canonical error `ENTITLEMENT_UNAVAILABLE` when refresh cannot establish one. Reconnect of the same resumable session and non-expanding restart/rollback/recovery may use a previously authoritative positive snapshot for at most five minutes when refresh is unavailable.

- Entitlement snapshots must carry operation flags for public join, new gameplay binding, and new instance/scale authority plus `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
- Last-known-good continuity is forbidden after observed `suspended`/`canceled`, revocation, explicit denial, a newer billing sequence, a sequence gap, or when no prior positive snapshot exists. Five minutes is a platform hard maximum; operators may only shorten or disable it.
- Last-known-good entitlement continuity does not relax revocation-authority freshness. If the separate batched revocation reconciliation lease cannot be renewed, active authority terminates at its stricter 60-second bound.
- On detected sequence gaps, services must reconcile by calling `GetTenantEntitlementsForRuntime(tenantId)` before retrying admission.
- Existing uninterrupted sessions do not re-read entitlement state per action. Observed hard billing states still revoke them through sequenced events and tenant authority generations, with batched reconciliation bounding missed-event exposure to 60 seconds as defined in Session Behavior.

Support-safe routes are an explicit allowlist and must not be inferred broadly from role names. The current support-safe allowlist is:

- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)` returning high-level entitlement status only
- `GetSubscriptionCrossTenantSupportSafe(tenantId)` returning high-level status and plan metadata only
- `ListSubscriptionsCrossTenantSupportSafe` returning high-level status and plan metadata only

Support-safe endpoints must exclude invoice line items, payment method details, and subscription mutation APIs.

All route classifications represented in a validated source inventory must also be registered in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md) with machine-readable entries in `system-architecture-authz-route-matrix.yaml`. Until source-stable OpenAPI/protobuf coverage and comparison validation complete the inventory gate, a discovered protected route missing from the incomplete matrix is recorded as authorization drift/gap rather than fed into generated default-deny policy.

---

## Login and Session Flow

The canonical player-facing flow is intentionally simple:

```text
WORLDS
LOGIN <username> <secret>
[JOIN <world>]  # required only for a first-time public-production account
PLAY <world> [realm] [character]
```

`WORLDS` must be available before login as a public browse/discovery command so prospective players can explore the platform before deciding to authenticate. `REALMS` and `CHARS` remain available as helper commands when a world choice is ambiguous or when a player wants to browse more deeply, but they are not intended to be mandatory ceremony in the ordinary happy path.

`WORLDS` deliberately has two canonical modes rather than one replacing the other:

- Before `LOGIN`, `WORLDS_PUBLIC` is public browse-only discovery. It may expose only the bounded public-production catalog and availability metadata; it has no account identity, membership filtering, hidden-tenant disclosure, or gameplay authority.
- After `LOGIN`, `WORLDS_AUTHENTICATED` is authenticated pre-tenant discovery. Game Session derives the account from its authenticated gameplay context and combines current Account-owned membership/grant visibility with public-production visibility and entitlement filtering before any single tenant is selected. It may return more than the public catalog for that account, but it does not itself bind a tenant, create membership, mint a connect token, or authorize `PLAY`.

These modes are complementary: public browse remains available before authentication, while authenticated discovery remains membership-aware after authentication.

Normative semantic split:

- `LOGIN` proves or restores account identity.
- For a first-time public-production account, `JOIN <world>` explicitly creates membership after `LOGIN`; a returning member omits `JOIN` and continues to `PLAY`. First-party browser/mobile clients use the equivalent Account bootstrap join endpoint.
- `PLAY` binds the gameplay session to `{tenantId, gameInstanceId, characterId}`.

Transport state, connect-token state, and any future hidden Telnet smart-client metadata are inputs to this flow; they are not peers to the authoritative gameplay binding.

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command.

Target protocol behavior:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

Current implementation note:

- Prompt-based `LOGIN` remains the target behavior for Telnet and other non-WebSocket text clients, but until the prompt flow is fully implemented those transports currently require `LOGIN <username> <secret>` and return `PROMPT_LOGIN_UNSUPPORTED` on bare `LOGIN`.
- Public non-proxy `/ws/game/**` uses the bootstrap-backed path: once Gateway has validated a connect token and attached a signed connect context, bare `LOGIN` is canonical and must not prompt for or replay credentials. The current implementation includes dedicated `POST /auth/player-bootstrap` and `POST /auth/connect-token` endpoints, gateway-side handshake rejection for missing, expired, replayed, or scope-mismatched connect tokens, and Game Session validation of the signed connect context before admitting bare `LOGIN`.

Telnet-specific smart-client attach hints, if they return later, should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line. Those hints remain advisory transport metadata only, are not authentication material, and never bypass the canonical `LOGIN` + `PLAY` authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

Any future hidden attach hints may include a target `{gameInstanceId, tenantId}` for advanced clients, but the canonical source of gameplay target selection remains the authenticated lobby/admission flow. Clients must not rely on unauthenticated transport hints to bypass membership, entitlement, or world-visibility checks.

Admission-routing convergence rule:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, `POST /auth/connect-token`, and reconnect validation must all consume the same authoritative realm-catalog and `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract).
- Those surfaces may expose different projections of the same routing truth, but they must not maintain separate interpretation rules for which realm maps to which admissible `gameInstanceId`.
- If pointer state is missing, ambiguous, or no longer matches the selected realm target, the flow fails closed with admission-routing errors such as `ADMISSION_POINTER_UNAVAILABLE` or `CONNECT_SCOPE_MISMATCH` rather than silently rebinding the player to a different runtime target.

### WebSocket Connect Token Contract (`/ws/game/**`)

For every public non-proxy WebSocket client, the control plane issues a short-lived connect token used only for handshake-time edge policy (for example tenant-aware rate limiting before `LOGIN` completes).

FireMUD standardizes a dedicated **player bootstrap** contract for first-party gameplay web/mobile clients:

- The first-party player UI authenticates directly against a dedicated bootstrap endpoint (for example `POST /auth/player-bootstrap`) using the same primary account-secret and abuse policy as gameplay login.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It is not derived from an existing admin/creator control UI session and must not require or return a `control-ui` JWT.
- On success, the endpoint returns one short-lived, memory-only **player bootstrap token** plus expiry metadata.
- This bootstrap token is not a control-plane `control-ui` JWT and must not be accepted on admin/creator APIs.
- It is still an Account Service-issued JWT profile and must carry at least `iss`, `sub`, `accountId`, `aud=player-bootstrap`, `jti`, `iat`, `nbf`, `exp`, and positive monotonic `tokenGeneration`, backed by one `session:auth:token:<tokenHash>` record so account-level revocation and logout semantics apply.
- Audience/scope is limited to first-party gameplay bootstrap functions such as discovery and `POST /auth/connect-token`.
- Lifetime is intentionally short (target <= 5 minutes), stored in memory only, and cleared on tab reload/logout.
- `POST /auth/connect-token` must derive caller identity from this bootstrap token; clients must not supply an arbitrary `accountId`.
- The subsequent gameplay `LOGIN` remains mandatory but, for first-party `/ws/game/**` clients, it must complete using the already-verified bootstrap/connect context rather than requiring the browser to re-submit account credentials. In other words, first-party bare `LOGIN` on `/ws/game/**` is an identity-consumption/binding step, not a second credential-entry step. A mismatch between the verified bootstrap identity and the gameplay login result is a hard failure and the connect context must not be honored.

- Bootstrap issuance API: Account Service endpoint (for example `POST /auth/player-bootstrap`) that authenticates the player account for first-party gameplay bootstrap only and returns one short-lived bootstrap token plus expiry metadata.
- Issuer: Account/authentication control-plane only, after direct player-account authentication. Tenant membership and entitlement checks do not occur here because no gameplay tenant has been selected yet.
- First-party bootstrap ownership: Account Service owns `POST /auth/player-bootstrap`, bootstrap discovery, explicit `/auth/bootstrap/join`, `POST /auth/connect-token`, and membership lifecycle. Game Session exposes the equivalent text `JOIN` command and owns in-socket `LOGIN`/`PLAY`, but delegates membership mutation to Account and never creates it during `PLAY`.
- `POST /auth/bootstrap/join` and the delegated `JoinPublicProductionMembership` operation accept the verified discovery `connectScopeId` plus `requestId`, not an independently authoritative tenant/world/realm tuple. Account resolves the selector for the caller, binds the resolved target and `pointerVersion` into the request/operation digest, and rechecks that selector and digest at the membership commit gate.
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
  - Response fields for all clients are non-secret metadata only: `expiresAt`, `accountId`, `tenantId`, `gameInstanceId`, `realmSlug`, `jti`, and `issuedAt`. The connect token is set only as the `Firemud-Connect-Token` HttpOnly cookie and is never returned in the response body.
  - Before issuance, Account Service must resolve `connectScopeId` to the canonical `{tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion}` tuple, perform a live membership/public-admission check for `{accountId, tenantId, worldSlug, realmSlug}`, validate the current membership authority generation for that caller and tenant, perform a live runtime entitlement check for `tenantId`, and perform a live realm-routing read for the selected realm target via the Game Session control-plane API.
  - Account must also read the shared replay-readiness record as `OPEN` and bind its exact `replayAdmissionFence` into the signed token. Missing, unreadable, `QUARANTINED`, or changing replay readiness fails issuance with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`; a token racing a later fence advance is rejected by Gateway.
  - The resolved tuple used for issuance must be treated as immutable for that request. Issuance may succeed only if `connectScopeId`, current realm visibility/grant state, and current admission-pointer state still converge on the same target at evaluation time.
  - `requestId` is the idempotency key for connect-token issuance. Retrying the same `(accountId, connectScopeId, requestId)` must return the same token payload or the same deterministic application failure; callers must use a new `requestId` when intentionally starting a new issuance attempt after rediscovery.
  - If realm-routing state is unavailable or ambiguous, connect-token issuance fails closed with `ADMISSION_POINTER_UNAVAILABLE`.
  - If `connectScopeId` no longer resolves to the current admissible target for the selected realm, connect-token issuance fails closed with `CONNECT_SCOPE_MISMATCH`; it must not mint a token for a stale or non-admissible target and rely on `PLAY` to correct it later.
  - First-party clients may request connect tokens only for realm targets returned by the canonical bootstrap-discovery contract for that caller; hidden or unauthorized realms must not be inferable by probing connect-token issuance directly.
  - If the realm was only caller-visible through an explicit non-public access grant, connect-token issuance must re-check that grant at issuance time rather than trusting earlier discovery alone.
  - Missing required request/response fields are contract violations and must fail closed rather than being defaulted by callers.
- Transport: connect-token carriage on `/ws/game/**` handshake.
  - First-party browser clients use the cookie `Firemud-Connect-Token` set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The cookie value is the connect token; browser JavaScript must not read or persist it.
  - Mobile and other non-browser first-party clients use a cookie jar for the same `Firemud-Connect-Token` cookie; no dedicated header carrier is supported.
  - An explicitly classified non-browser/server WebSocket route uses the dedicated `X-Firemud-Connect-Token` handshake header. This is the public generic-WebSocket carrier; it does not apply to Telnet credential-login traffic or create a first-party mobile header fallback.
  - Gateway must accept exactly one non-empty, single-valued supported carrier for non-proxy gameplay handshakes. Duplicate header values, duplicate cookie values, a malformed carrier, or simultaneous header and cookie carriers are rejected as `CONNECT_TOKEN_REJECTED`; Gateway never chooses precedence.
  - Query-string carriage is not a supported connect-token carrier in player-facing environments.
- Required claims: `iss`, `aud`, `accountId`, `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`, `connectScopeId`, `requestId`, `iat`, `exp`, `jti`, `replayAdmissionFence`.
- `iss` is required and must exactly match the deployment's configured Account Service issuer identifier used by the Account JWKS trust configuration; callers cannot select or override it.
- `aud` is required and must be exactly `gameplay-connect`; Gateway rejects a missing, multi-valued, or different audience before consuming `jti`.
- Lifetime: a platform hard maximum of 30 seconds from signed `iat` to `exp`; issuers may shorten but not widen it, and Gateway independently rejects missing/future-skewed `iat`, invalid ordering, and lifetimes above the maximum.
- Signing and verification: token is signed by the Account/authentication control-plane key set and verified only at Gateway for `/ws/game/**` policy decisions.
- Replay defense: gateway validates `jti` against a bounded replay cache and rejects replays until token expiry.
  - Before and atomically during consumption, Gateway requires replay readiness to be `OPEN` and the signed `replayAdmissionFence` to equal the current shared fence.
  - Replay cache owner: Gateway.
  - Replay key format: `gateway:connect-token:jti:<jti>`.
  - Replay TTL: through `exp + bounded_skew`, covering the token's complete acceptance window.
  - Capacity policy: bounded cardinality with expired-marker cleanup and overload metrics. Gateway must never evict an unexpired marker; when capacity cannot be reclaimed without doing so, new connect admission fails closed with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` until capacity recovers.
- Enforcement:
  - `/ws/game/**` is the only gameplay WebSocket route.
  - Non-proxy gameplay clients must present a valid connect token; missing, invalid, expired, replayed, scope-mismatched, or replay-protection-unavailable token state is rejected with HTTP `403` and the bounded handshake classes defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - TCP Proxy bridge traffic is admitted without a connect token only when the gateway authenticates the proxy identity over the internal mTLS listener and header-trust checks pass.
- Error mapping: connect-token admission failures map to HTTP `403` at handshake, with specific `CONNECT_*` classes when the gateway can classify the failure.

The connect token carries a short-lived, immutable snapshot of the selected gameplay target for edge admission. It is not a gameplay command authority, is not a gameplay authorization grant, and does not replace the canonical `LOGIN` + `PLAY` flow; it is an edge-admission artifact bound to a prior first-party bootstrap identity, not a substitute for gameplay authentication or gameplay binding.

#### Gateway-to-Game Session connect context (normative)

Gateway verification of a supported connect-token carrier must not be translated into trust of raw forwarded headers. Gateway must validate and consume the token, strip every external carrier before the upgrade completes, and attach a short-lived signed connect context that Game Session verifies before applying connect-token scope checks.

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
2. Use bootstrap-authenticated discovery endpoints to select a caller-visible world and realm target.
3. If the public-production target is visible but the account is not already a member, explicitly call `POST /auth/bootstrap/join`. Character discovery and creation require the resulting membership; a returning member skips this step.
4. Select or create a caller-visible character, then request a short-lived gameplay connect token for the target selected by `connectScopeId`. This call performs live membership, current membership authority-generation, applicable realm-grant, and runtime entitlement checks.
   - The issuance path must also validate the target against the authoritative realm-routing record. If the target is no longer admissible for the selected realm, the request fails before socket open rather than issuing a stale token.
5. Open gameplay WebSocket on `/ws/game/**` with the `Firemud-Connect-Token` HttpOnly cookie set by `POST /auth/connect-token`; mobile and other non-browser first-party clients use a cookie jar rather than a header fallback.
6. Complete gameplay authentication in-band using `LOGIN` (or `LOGON`) and then lobby binding with `PLAY`.

Normative constraints:

- First-party clients must not treat successful handshake as gameplay authentication; gameplay remains unauthenticated until `LOGIN` succeeds.
- `/ws/game/**` requires a valid connect token for non-proxy clients and rejects missing tokens with `403`.
- For first-party `/ws/game/**` clients, bare `LOGIN` (or `LOGON`) must complete gameplay authentication by consuming the verified connect context plus the bootstrap identity already bound to that context. Browsers must not be required to replay credentials after bootstrap.
- Telnet and other non-WebSocket text transports continue to use credential-bearing `LOGIN <username> <secret>` or the prompt flow. Third-party WebSocket clients must use the classified bootstrap/connect-token header path rather than introducing a credential-bearing public WebSocket exception.
- Game Session must bind the verified connect context to the authenticated gameplay login: if bootstrap-backed `LOGIN` resolves to an `accountId` different from the connect-context `accountId`, the session fails closed with canonical error `ACCOUNT_MISMATCH` and no gameplay scope is bound.
- For first-party clients on `/ws/game/**`, `PLAY` accepts stable world/realm/character selection only. Game Session resolves the current admissible `{tenantId, gameInstanceId}` server-side and requires that resolved scope to match the connect-token context. Scope mismatch is rejected with canonical error `CONNECT_SCOPE_MISMATCH`; clients must request a fresh connect token for the intended stable realm target and reconnect.
- Because `/auth/connect-token` validates against the authoritative realm-routing state for the caller, `CONNECT_SCOPE_MISMATCH` at `PLAY` is treated as drift between issuance and admission (for example route movement during reconnect), not as normal stale-client correction.
- The bootstrap-discovery contract, connect-token contract, and lobby `PLAY` contract together form the canonical player-selected stable world/realm/character path. `connectScopeId` binds that selection to a server-resolved concrete runtime target; first-party clients carry the opaque scope forward and never select or invent `tenantId` / `gameInstanceId` routing authority.

Canonical returning-member first-party browser sequence (example):

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
     tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
     gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
     connectScopeId: "cs_demo_production_v17"
   }]

GET /auth/bootstrap/worlds/demo/realms/production/characters?connectScopeId=cs_demo_production_v17
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-123" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", expiresAt, jti, issuedAt }

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
     tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40",
     gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce",
     connectScopeId: "cs_emberfall_production_v1"
   }]

POST /auth/bootstrap/join
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-join-1" }
-> { tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", membershipVersion: 1, joined: true }

GET /auth/bootstrap/worlds/emberfall/realms/production/characters?connectScopeId=cs_emberfall_production_v1
Authorization: Bearer <bootstrapToken>
-> []

POST /auth/bootstrap/worlds/emberfall/realms/production/characters
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", name: "Mara", template: "human-fighter" }
-> { characterName: "Mara", characterId: "c7f4b18b-6eb5-4fd8-a906-c9606d17d4dc" }

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-connect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", realmSlug: "production", gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce", expiresAt, jti, issuedAt }

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

- The player-facing control-plane surface is Account-owned `POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters`, using the current bootstrap-authenticated account identity and signed discovery `connectScopeId`; the route must match that signed target.
- Account validates the admission prerequisites and delegates the authorized internal write to Entity Management, which owns `CreateCharacter` semantics and persistence. Entity Management remains internal-only and exposes no direct player REST route.
- The Account facade is allowed only after the caller has explicitly joined the public production game or already has the required membership/grant, and before `POST /auth/connect-token` / gameplay `PLAY` succeed for that new character.
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
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
       connectScopeId: "cs_demo_production_v17"
     },
     {
       realmSlug: "playtest-docks",
       displayName: "Playtest Fork",
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d",
       connectScopeId: "cs_demo_playtest_docks_v4"
     }
   ]

GET /auth/bootstrap/worlds/demo/realms/playtest-docks/characters?connectScopeId=cs_demo_playtest_docks_v4
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_playtest_docks_v4", requestId: "req-456" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "playtest-docks", gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d", expiresAt, jti, issuedAt }

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
   - `LOGIN <username> <secret>` (or `LOGON ...`) for Telnet and other non-WebSocket text transports.
   - bare `LOGIN` / `LOGON` on any public non-proxy `/ws/game/**` connection after Gateway has validated a connect token and attached a signed connect context. First-party browsers carry the token in the protected cookie; explicitly classified non-browser WebSocket clients carry it in `X-Firemud-Connect-Token`. In both cases, the client is completing gameplay auth from the previously established bootstrap identity rather than sending credentials a second time.
2. For credential-bearing login, the Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with `username`, one supplied `secret`, a typed `CredentialSourceContext`, and a stable high-entropy `requestId` for that one login attempt. Account binds the request ID to an immutable, server-keyed and versioned digest of the normalized authentication operation, credential presentation, source context, and applicable scope. The dedicated Account-owned digest-key version remains available for at least the response-envelope lifetime; neither the raw secret nor an unkeyed reusable credential hash is persisted or logged. Account retains a bounded, protected response envelope for that exact operation so a retry with the same request ID and matching digest returns the same stored token/result or the same deterministic failure without minting again. Reuse with a different digest or scope is rejected as an idempotency conflict and cannot issue another token. The context carries the server-derived canonical client address and transport class from the trusted Gateway or authenticated TCP Proxy chain; public input cannot populate or override it. Account rejects a missing, unknown, or untrusted source context in player-facing environments. Account Service interprets that secret against the account's enabled `PASSWORD` and `EMAIL_OTP` modes. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs.
3. For bootstrap-backed WebSocket login, Game Session validates the signed connect context, binds it to the bootstrap-authenticated account identity established before `/auth/connect-token`, and obtains/refreshes the backend token material needed for subsequent internal calls. This path must not prompt for or require replay of account credentials from the WebSocket client.
4. The Account Service-backed credential path validates the supplied secret according to the enabled account modes and returns account metadata plus the private `game-session-account-delegation` JWT profile with audience `account-service`, or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED`, or `AUTH_UPSTREAM_FAILURE`. This exact receiver-specific profile is the only Account token Game Session accepts from credential authentication; a generic backend JWT or another audience is invalid. The Game Session Service translates Account error codes into the text-protocol equivalents so WebSocket and Telnet clients always see the same response format regardless of upstream wording.
5. Success responses cause the Game Session Service to create or refresh the Redis-backed gameplay session binding. Account creates the one issued-token registry record for the returned `game-session-account-delegation` token, and Game Session uses that token only for Account-bound backend calls under its exact profile and audience. If the Game Session binding CAS fails after token issuance, Game Session must call Account's idempotent retire/abort operation for that exact request and token identity; Account removes or revokes the orphan registry record, and an orphaned token is never accepted merely because it was cryptographically valid. Game Session binds the socket to an authenticated account context and emits `OK LOGIN Logged in` (or equivalent account-confirming text) on the wire only after the binding succeeds. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

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

- `WORLDS_PUBLIC` is the public browse-only mode before authentication. `WORLDS_AUTHENTICATED` is the authenticated **pre-tenant discovery** mode, not a normal tenant-scoped route; it runs after account authentication but before a single `tenantId` has been selected.
- `REALMS <world>` and explicit `JOIN <world>` participate in `public_production_onboarding` when the selected realm is the default public production realm. Brand-new authenticated accounts may discover that realm without a membership, but `CHARS`, character creation, connect-token issuance, and `PLAY` require durable membership plus any applicable realm grant. A grant never substitutes for membership; grant-backed private/playtest entry skips public `JOIN` only when membership already exists or has been provisioned through the owning membership lifecycle.
- `REALMS <world>` remains a tenant-scoped discovery operation after `<world>` resolves to a canonical `tenantId`, but before the client is bound to one `gameInstanceId`.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` become tenant/realm-scoped only after `<world>` and optional `[realm]` are resolved server-side to canonical `{tenantId, gameInstanceId}`.
- Shared auth middleware and route-matrix entries must not model all lobby commands as one undifferentiated tenant-scoped surface.

The `PLAY` flow:

- Resolves `<world>` to a canonical `tenantId` and validates it exists.
- Resolves optional `[realm]` to a canonical realm for that tenant. If no realm is supplied, the tenant's default production realm is selected.
- Verifies that the account is authorized to play in that `tenantId` using caller-bound gameplay membership and any required realm grant. Global roles and public discoverability alone must not satisfy gameplay admission.
- If the public realm is visible but the account has not explicitly joined, returns `JOIN_REQUIRED` with `JOIN <world>`/`Join & Play` recovery guidance and does not create membership or other admission state.
- Membership creation writer authority remains Account Service through the current proto seam `EnsurePublicProductionPlayerMembership` or an explicitly named target equivalent. The target operation consumes the verified caller-bound `connectScopeId` plus `requestId`; Account resolves and revalidates the tenant, world, realm, game instance, and pointer version at the commit gate rather than accepting those fields as independently authoritative player inputs. The operation is explicit `JOIN`/`Join & Play`; current Game Session, connect-token issuance, character creation, and `PLAY` must not create membership implicitly.
- Performs an authoritative internal membership read for `{accountId, tenantId}` and persists the returned `membershipVersion` into the gameplay session binding on successful admission. The membership response must also assert `gameplayAdmissionAllowed=true`; gameplay admission must not source `membershipVersion` or gameplay authority from JWT claims or local caches.
- Consults the runtime entitlement contract `GetTenantEntitlementsForRuntime(tenantId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated).
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId, gameInstanceId}` according to the selected realm's character policy.
  - Explicit character creation and selection are part of the v1 contract. If the selected realm has no visible character for the caller, the client must complete the canonical character-creation flow before `PLAY` can succeed.
  - `PLAY` may omit `[character]` only when exactly one visible character exists for the resolved realm. Otherwise admission fails with `CHARACTER_REQUIRED`.
- Resolves the selected realm's gameplay-admissible instance and records that `gameInstanceId` in the gameplay binding.
  - First-party `/ws/game/**` contract: if a validated connect token is present, resolved `tenantId` and `gameInstanceId` must match token claims. On mismatch, reject admission with `CONNECT_SCOPE_MISMATCH` and do not bind session scope.
  - Runtime control-plane and admission flows use the realm-routing contract from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) as the source of truth for which concrete `gameInstanceId` is admissible for the selected realm.
- **Current live admission boundary:** `PLAY` is authoritative at the selected `{tenantId, gameInstanceId}` runtime target and binds the gameplay identity under `{tenantId, gameInstanceId, characterId}`. The current first slice resolves the admissible `gameInstanceId` from realm routing but does not claim that `PLAY` already performs authoritative `regionId`, `regionEpoch`, or lease-fence resolution.
- **Target authoritative admission:** before committing gameplay binding, resolve the current `regionId` and lease owner/fence for the selected `{tenantId, gameInstanceId}` runtime target from the Game Session control plane. The binding and any forwarded request preserve the selected `playableStateScope`, but that scope does not create a separate lease owner.
  - Missing or ambiguous region ownership fails closed with `OWNERSHIP_UNAVAILABLE`.
  - A stale or mismatched region, `regionEpoch`, lease fence, or verified routing target fails closed with `STALE_TIMELINE` or the applicable `CONNECT_SCOPE_MISMATCH`; `PLAY` must not bind from cached ownership, raw transport headers, or a stale discovery result.
- On successful admission, runtime must return the resolved realm bundle identity at minimum as `versionId`, optional `scriptPatchVersion`, and manifest location/hash (or a stable bundle token that resolves to those fields) so clients can apply realm-specific branding and assets.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Target-state admission must ensure the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, gameInstanceId, regionId>`. Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, `/ws/game/**` is routed to a stable Game Session service endpoint and the edge does not implement a lease-aware shard routing plane.

### PLAY Current and Target Failure Boundaries

The current shipped `PLAY` path resolves the selected realm to its admissible `{tenantId, gameInstanceId}`, validates caller-bound gameplay access and entitlement state, resolves the character, and binds `{tenantId, gameInstanceId, characterId}`. It does not claim that the live path already resolves or commits the target region, region epoch, lease owner, or lease fence. Those ownership and timeline checks are target-state responsibilities described above and must not be reported as current behavior merely because their failure codes are already reserved.

`CONNECT_SCOPE_MISMATCH` and `STALE_TIMELINE` are intentionally disjoint:

- `CONNECT_SCOPE_MISMATCH` means the verified first-party connect context or connect-token scope does not match the `{tenantId, gameInstanceId}` selected by `PLAY`. It is an admission-scope/issuance drift and requires fresh bootstrap, token issuance, and connection establishment.
- `STALE_TIMELINE` means the selected runtime target was valid, but its authoritative region, epoch, lease fence, or equivalent runtime timeline no longer matches at the ownership check. It requires rediscovery and explicit retry; it must never be repaired by silently rebinding to a different target.

The target ownership checks may produce `OWNERSHIP_UNAVAILABLE` when authority cannot be read at all, but they must not relabel a verified connect-scope mismatch as a stale timeline or relabel a stale runtime fence as a connect-scope mismatch.

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
- `REALM_UNAVAILABLE` – the selected realm is deliberately closed and has no current admissible gameplay target.
- `OWNERSHIP_UNAVAILABLE` – the selected runtime region or current lease owner/fence cannot be resolved authoritatively; no gameplay binding is created.
- `STALE_TIMELINE` – the selected region, epoch, or lease fence no longer matches current runtime authority; the client must rediscover/retry rather than being rebound implicitly.
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
| `PLAY` on first-party `/ws/game/**` | `CONNECT_SCOPE_MISMATCH` | The server-resolved runtime target for the requested stable world/realm selector does not match the validated connect-token scope | Re-select the intended world/realm, obtain a fresh connect token for that target, reconnect, and retry `PLAY`. |
| `LOGIN` on first-party `/ws/game/**` | `ACCOUNT_MISMATCH` | Bootstrap-backed login resolved to an account different from the validated connect-context subject | Treat as a hard auth failure for the current socket; clear the gameplay bootstrap/connect flow and require a fresh authenticated bootstrap. |
| `PLAY` | `WORLD_ACCESS_DENIED` | Caller-bound membership authority does not allow gameplay admission for the resolved tenant | Keep auth state, surface an authorization error, and do not infer hidden-tenant existence beyond the canonical code. |
| `PLAY` | `TENANT_BILLING_BLOCKED` | Tenant entitlement state is `suspended` or `canceled` for gameplay | Keep auth state, surface a billing-blocked state for that tenant, and disable gameplay admission flows. |
| `PLAY`, new admission, restart/rollback, another new commitment, or ineligible continuity operation | `ENTITLEMENT_UNAVAILABLE` | Fresh entitlement authority is unavailable and no operation-eligible last-known-good snapshot exists; strict new commitments require a snapshot fresh enough for the 15-second admission SLA | Keep auth state, retry with bounded backoff, never admit a strict commitment from stale entitlement state, and never use grace after hard denial, revocation, or sequence uncertainty. |
| `PLAY` | `OWNERSHIP_UNAVAILABLE` | The selected runtime region or current lease owner/fence cannot be resolved authoritatively | Keep auth state, create no gameplay binding, rediscover runtime ownership with bounded backoff, and retry admission only after fresh authority is available. |
| `PLAY` | `STALE_TIMELINE` | The selected region, epoch, or lease fence no longer matches current runtime authority | Keep auth state, create no gameplay binding, rediscover the realm and runtime timeline, and retry admission explicitly; never accept an implicit rebind. |
| Gameplay command before `PLAY` | `PLAY_REQUIRED` | Client issued a world-scoped gameplay command before lobby admission completed | Keep auth state and route the client back through `PLAY`, `REALMS`, or `CHARS` as appropriate. |

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss) or when server-side auth state has expired or been revoked. After a reconnect, clients always issue a fresh `LOGIN` and then complete lobby selection again (`PLAY <world> [realm] [character]`). If a resumable gameplay session exists for the selected `{tenantId, gameInstanceId, characterId}`, the Game Session Service resumes it; otherwise it creates a fresh gameplay session binding.

Gameplay identity is canonicalized on `characterId` within a tenant. All Redis key formats and Game Session Service APIs must treat `characterId` as the abstract character identifier so sessions bind sockets to characters rather than raw accounts. Canonical takeover and resume identity is `{tenantId, gameInstanceId, characterId}`.

Gameplay identity is single-mode and canonical: uniqueness key `{tenantId, gameInstanceId, characterId}`.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Related Token and Session Contracts

The detailed token and lifecycle contracts now live in focused sibling docs:

- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) defines JWT claim requirements, token profiles, issued-token registry records, authority generations, and Redis-outage behavior for token validation.
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
- Gameplay services never accept or validate browser- or client-supplied JWTs directly. They rely on the Game Session Service to enforce access based on Redis session context and the exact receiver-specific private player-delegation contract where an Account call requires it.

When adding a new public HTTP/gRPC route:

- Classify it using the shared classes from [Authorization Route Matrix](./system-architecture-authz-route-matrix.md): `public`, `account_scoped`, `caller_membership_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `public_production_onboarding`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, or `internal_workload`.
- For non-public routes, require the route-matrix-defined authentication contract and the Tenant Authorization Contract described above. JWT-bearing route classes install `AuthTokenInterceptor`; `internal_workload` routes use their exact authenticated mTLS identity, method allowlist, and typed context contract instead of a blanket JWT requirement.
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, or tenant-scoped data export), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`). Full account export remains `account_scoped` and must not be used as the suspended-tenant recovery export.
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.
- Register the route and its classification in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md). Runtime middleware rejects an unclassified protected route immediately; independently, CI and deployment policy checks fail a validated candidate route with missing registration. Neither check generates runtime policy from the incomplete matrix.

## Session Lifecycle and Rebinding

Gameplay takeover, reconnect, token refresh, membership-version handling, and control-plane logout behavior are defined in [Session Behavior](./system-architecture-session-behavior.md). This parent doc keeps the admission and authorization model while the sibling doc carries the long-form lifecycle rules.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — supports prompt or argument input |
| JWT Usage | Raw Telnet gameplay command streams do not carry JWTs; browser/mobile gameplay uses a short-lived `player-bootstrap` JWT for HTTPS bootstrap and an HttpOnly-cookie connect token for `/ws/game/**`, while explicitly classified non-browser WebSocket clients use the dedicated connect-token handshake header; admin/creator UIs and backend services use their exact permitted profiles |
| Claims | Profile-dependent: shared JWT claims include `iss`, `sub`, `jti`, `aud`, `iat`, `nbf`, and `exp`; applicable profiles additionally carry `accountId`, `tokenGeneration`, `globalRoles[]`, or `scopedRoles{}` according to the token contract |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Gameplay Continuity TTL | Separate `session_expiration_ms` policy with an independent effective maximum of five minutes; the configurable JWT cleanup margin applies only to issued-token registry retention |
| Issued-Token Registry TTL | Each token record is retained through its actual JWT `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; activity does not extend it |
| Gameplay Reauthentication | Required after disconnect; client re-issues `LOGIN`, and Game Session resumes via Redis if the underlying gameplay and auth session state are still valid |
| Role Enforcement | Meta/control services validate JWTs directly; gameplay services enforce concrete mTLS workload identity, method caller allowlists, and validated `PlayerExecutionContext` scope |
| Role Updates | Target: Account-owned role/token refresh is intended to be invisible in-session; current role-refresh regeneration and end-to-end proof remain a documented implementation gap |
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
