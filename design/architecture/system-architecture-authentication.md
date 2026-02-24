# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands for gameplay protocol clients and via `/auth/login` or equivalent flows for first-party web UIs. Clients are stateless; server-side “sessions” are split between gameplay bindings in Redis and short-lived auth token allowlist entries in Coordination Redis. The Game Session Service restores gameplay session state from Redis, while the Account Service validates credentials (including OTP) and issues internal JWTs. Gameplay protocol clients (Telnet and WebSocket) never see these tokens directly; first-party admin/creator web UIs and backend services use them for meta/control APIs. Accounts may also authenticate using linked external providers such as Google, Discord, or Steam.

## Implemented Status

- Prompt-based `LOGIN` flows (username then password prompts) are part of the target protocol design; until they are fully implemented across transports, clients should use `LOGIN <username> <password> [otp]` / `LOGON ...`.
- Character selection is part of the target design; until it ships, the platform binds gameplay sessions to a default character identity derived from the authenticated account and treats the `playerId` field as an abstract character identifier for forward compatibility.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook; until full role-refresh token regeneration is wired end-to-end, implementations may expose a placeholder response while still performing automatic refresh on role updates.

## Responsibility Split

- **Account Service** – Verifies credentials (including OTP), issues JWTs, and publishes JWKS for validation.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected routes but does not validate tokens.

Admin and moderator accounts can optionally enable **two-factor authentication**. When a `two_factor_secret` is present, the Account Service expects a one-time TOTP code during login. The `/auth/login` REST endpoint and the `Authenticate` gRPC call both accept an `otp` field for this purpose. The Game Session Service forwards this OTP when a player logs in.

When `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins over **plaintext Telnet** are further constrained: only accounts that both (a) have two-factor authentication enabled and (b) explicitly opt in to “allow plaintext Telnet login” may authenticate via the raw TCP port. All other accounts must use the TLS Telnet port or the web client and receive a clear error if they attempt to log in over plaintext Telnet.

Issued JWTs are allowlisted in Redis using keys `session:auth:<scope>:<tokenHash>` where `scope` encodes the authorization context and `tokenHash` is a fixed-length digest (for example, a hex-encoded SHA-256 of the JWT). This keeps key lengths bounded and avoids leaking raw token contents into key names. FireMUD standardizes the following scope formats:

- `session:auth:account:<accountId>:<tokenHash>` – account-scoped allowlist entry for a JWT. This represents “this token is currently allowed for this account” and is the baseline revocation surface for control-plane sessions.
- `session:auth:tenant:<tenantId>:<tokenHash>` – tenant-scoped allowlist entry for a JWT that is permitted to act in the regular tenant control plane and gameplay plane for a specific tenant. Services consult these entries when authorizing tenant-specific (non-billing-safe) operations based on `scopedRoles[tenantId]`.
- `session:auth:global:<accountId>:<tokenHash>` – cross-tenant allowlist entry for a JWT that carries `globalRoles` such as `platformAdmin`, `billingAdmin`, or `support`. These entries are used when authorizing cross-tenant operations that are not tied to a single `tenantId`.

JWT issuance follows these rules:

- The Account Service creates exactly one `session:auth:account:<accountId>:<tokenHash>` entry for every issued JWT.
- If a JWT contains any tenant-scoped roles, the Account Service creates one `session:auth:tenant:<tenantId>:<tokenHash>` entry per tenant in `scopedRoles`.
- If a JWT includes `globalRoles`, the Account Service creates a single `session:auth:global:<accountId>:<tokenHash>` entry in addition to the account-scoped entry.

The `session:auth:*` entries use a TTL derived from the JWT lifetime so operators do not need to tune separate “JWT” and “auth session” expiry knobs:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

Token validity semantics:

- A JWT must be cryptographically valid (signature and required time-based claims `iat` and `exp`) and must have a matching `session:auth:account:<accountId>:<tokenHash>` entry present in Coordination Redis.
- For regular tenant-scoped operations (gameplay admission, instance management, and non-billing-safe tenant control-plane APIs), the JWT must also have a matching `session:auth:tenant:<tenantId>:<tokenHash>` entry for the requested `tenantId`.
- For cross-tenant operations, the JWT must have a matching `session:auth:global:<accountId>:<tokenHash>` entry and the requested operation must be authorized by `globalRoles` per the Tenant Authorization Contract.
- Coordination Redis therefore acts as a server-side allowlist and immediate revocation surface: deleting `session:auth:*:<tokenHash>` revokes a still-unexpired JWT; coordination resets that drop `session:auth:*` force re-authentication for the affected scopes.
- During Coordination Redis outages, token-gated internal calls fail closed (authorization cannot be established without the allowlist check). This is an explicit availability vs security tradeoff; gameplay clients do not transmit JWTs directly, but backend calls made on their behalf still require the server-side auth-session/token entries to be present.

Bulk revocation (for example “logout all devices”, account bans, or tenant-wide billing suspensions) must not rely on wildcard deletes or key scans. Instead, the platform uses **revocation watermarks** in addition to per-token allowlist entries:

- `session:auth:revoked_after:account:<accountId>` – tokens with `iat` older than this timestamp are treated as revoked for this account, even if their allowlist entries still exist.
- `session:auth:revoked_after:tenant:<tenantId>` – tokens with `iat` older than this timestamp are treated as revoked for tenant-scoped operations targeting this tenant.

Revocation watermark contract requirements:

- Watermark values are UTC epoch seconds so they can be compared directly to JWT `iat` without unit conversion drift.
- Watermark keys must have TTL at least `FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` after the last relevant event so all tokens that could still be valid are covered.
- Account Service is the authoritative writer for watermark updates triggered by account-security and billing-state events.
- Services validating tokens should allow small bounded clock skew (for example up to 60 seconds) when comparing `iat` to wall-clock checks, but not when comparing `iat` to revocation watermark values.

Per-token logout remains a single-key delete of the token’s allowlist entries; bulk revocation uses watermarks and relies on TTL for eventual allowlist key cleanup.

Coordination Redis outage behavior must be deterministic:

- **Control-plane APIs (HTTP/gRPC)** – Requests that require allowlist checks fail closed while Coordination Redis is unavailable, returning a clear infrastructure error (for example `AUTH_UNAVAILABLE` / `SERVICE_UNAVAILABLE`) rather than silently bypassing authorization.
- **Gameplay admission (`LOGIN` / lobby selection via `PLAY`)** – New admissions fail closed while Coordination Redis is unavailable because allowlist and gameplay session binding state cannot be established reliably.
- **Already-entered gameplay sessions** – Ongoing gameplay behavior follows the Redis outage/degradation policy defined in `system-architecture-redis.md` and `system-architecture-redis-operations.md`. Game Session must not “assume authorization” in the absence of Redis; if coordination state needed to process commands safely is unavailable, it must degrade or halt according to the Redis policy instead of inventing local-only session authority.

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

### Tenant Authorization Contract

All meta/control services (Account, Game Design, Logging & Admin, and similar HTTP/gRPC APIs) must enforce a consistent tenant-authorization contract:

- Each incoming request is authenticated to a single `accountId` using a JWT validated against the Account Service JWKS.
- The effective tenant set for the request is derived from the token:
  - For tenant-scoped operations, the service computes the set of `tenantId` values present in `scopedRoles` combined with any tenant IDs implied by `globalRoles` (for example, `platformAdmin` may be permitted to act on all tenants; `billingAdmin` may act only on billing-safe surfaces for all tenants).
  - For cross-tenant operations, the service must explicitly check that the caller has a `globalRole` that authorizes cross-tenant access for the specific API category (for example, only `platformAdmin` for gameplay- or data-bearing operations, `billingAdmin` or `platformAdmin` for billing-safe control-plane operations, and `support` or `platformAdmin` only for explicitly designated support-safe troubleshooting surfaces). Tenant-scoped roles must never implicitly grant cross-tenant privileges.
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
   - For routes that target a specific `tenantId`, if `session:auth:revoked_after:tenant:<tenantId>` exists and the token’s `iat` is older than that value, treat the token as revoked for that tenant-scoped operation.
4. **Apply route classification** – Every protected route is classified as one of the following, and the middleware must enforce the corresponding allowlist and role rules:

| Route classification | Required allowlist entries | Required role checks | Tenant validation rules |
| --- | --- | --- | --- |
| Public | *(none)* | *(none)* | *(none)* |
| Tenant-scoped (regular) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:tenant:<tenantId>:<tokenHash>` | Require a tenant role in `scopedRoles[tenantId]` that authorizes the operation (for example `tenantAdmin`, `designer`, `moderator`, `player`) | `tenantId` must be in the effective tenant set derived from `scopedRoles` (or explicitly allowed by `globalRoles` when applicable); enforce DB query scoping by `tenantId` |
| Billing-safe (tenant-scoped) | `session:auth:account:<accountId>:<tokenHash>` | Require `tenantAdmin` for the tenant, or a global billing role (`billingAdmin`/`platformAdmin`) | `tenantId` must be validated against the caller’s effective tenant set (or permitted by global billing roles); this route must remain reachable even when the tenant is `suspended`/`canceled` for gameplay |
| Cross-tenant (support-safe) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `support` or `platformAdmin` | Tenant parameters are allowed only because the caller holds a cross-tenant support role; responses must be limited to high-level, troubleshooting-safe data (for example derived entitlements and subscription status, not invoices/payment methods); log/audit the target tenant |
| Cross-tenant (billing-safe) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `billingAdmin` or `platformAdmin` | Tenant parameters are allowed only because the caller holds a global billing role; log/audit the target tenant |
| Cross-tenant (data-bearing) | `session:auth:account:<accountId>:<tokenHash>` + `session:auth:global:<accountId>:<tokenHash>` | Require `platformAdmin` | Tenant parameters are allowed only because the caller holds `platformAdmin`; log/audit the target tenant |

1. **Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult `GetTenantEntitlements(tenantId)` and deny requests when the tenant is not available for gameplay (for example `suspended`/`canceled`). Billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.

Support-safe routes are an explicit allowlist and must not be inferred broadly from role names. The current support-safe allowlist is:

- `GetTenantEntitlements(tenantId)`
- `GetSubscription(tenantId)` returning high-level status and plan metadata only
- `ListSubscriptions` returning high-level status and plan metadata only

Support-safe endpoints must exclude invoice line items, payment method details, and subscription mutation APIs.

---

## Login and Session Flow

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

Telnet-specific behaviors (such as the optional `SESSION <gameInstanceId> <tenantId>` envelope used by advanced clients) reuse this same canonical login flow. The envelope is an advisory attach hint captured by the TCP Proxy Service and forwarded as gateway-owned headers; it is not authentication material and never bypasses the canonical `LOGIN` + lobby selection (`WORLDS`/`CHARS`/`PLAY`) authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The Telnet and WebSocket client emits `LOGIN <username> <password> [otp]` (or the `LOGON` alias).
2. The Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with a payload containing `username`, `password`, the optional `otp`, and connection metadata indicating the **transport security** (for example `transportSecurity=PLAINTEXT_TELNET` vs `transportSecurity=TLS_TELNET` / `WEB_TLS`). This metadata is derived from the TCP Proxy and Gateway handshake so the Account Service can enforce deployment-wide and per-account policies for plaintext Telnet logins. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs.
3. The Account Service validates credentials (including the OTP when present) and returns either a JWT + account metadata or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `AUTH_PLAINTEXT_TCP_NOT_PERMITTED`, or `AUTH_UPSTREAM_FAILURE`. The Game Session Service translates these codes into the text-protocol equivalents (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, `ERROR 2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `ERROR PLAINTEXT_TCP_NOT_PERMITTED`, etc.) so WebSocket and Telnet clients always see the same response format regardless of how the upstream message is worded. For plaintext Telnet logins, the combination of `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and the per-account “allow plaintext Telnet login” flag follows the safety matrix defined in the Security Architecture’s **Plaintext Telnet safety matrix** section; implementations must treat any combination outside the allowed cells as a hard denial (`AUTH_PLAINTEXT_TCP_NOT_PERMITTED` or `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`) rather than silently weakening security.
4. Success responses cause the Game Session Service to create/refresh Redis-backed gameplay session bindings and the Account Service to create the corresponding `session:auth:*` allowlist entries. The Game Session Service binds the socket to an authenticated account context and emits `OK LOGIN Logged in as <username>` on the wire. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by both the authentication handshake (`LOGIN`) and the lobby selection step (`PLAY`). Any text command received before a session is authenticated is rejected with `ERROR NOT_AUTHENTICATED`, and any gameplay command received before a world is selected is rejected with a dedicated error (for example `ERROR WORLD_NOT_SELECTED Use WORLDS/PLAY first`). Except in explicitly documented development/test bypass modes that grant temporary access, these commands are not processed for anonymous or unscoped sessions, keeping the gameplay queue free of unauthenticated traffic.

Login commands only carry account credentials (plus optional OTP). Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model).

### Tenant Selection for Gameplay (Lobby Selection)

FireMUD uses a **single shared entrypoint** for many worlds (tenants). After `LOGIN`, clients complete a lobby selection step that binds the authenticated connection to a specific world (`tenantId`) and gameplay identity (`characterId` / `playerId`) before gameplay commands are accepted.

Players must never be asked to type raw internal identifiers such as `tenantId` GUIDs or `characterId` values. Lobby selection accepts human-friendly inputs (world slugs, world names, or numbered menu indices; character names or indices) and resolves them server-side into stable internal identifiers.

After `LOGIN` succeeds, the Game Session Service requires an explicit lobby selection flow using these canonical commands:

- `WORLDS` – list worlds the authenticated account can enter (a numbered menu plus a stable world slug for each entry).
- `CHARS <world>` – list characters for the selected world (`<world>` is a world index from `WORLDS` or a world slug).
- `PLAY <world> [character]` – enter gameplay by selecting a world and optional character.

Lobby discovery source-of-truth contract:

- `WORLDS` must be sourced from Account Service tenant-membership and entitlement state (not from opportunistic local caches alone) so world visibility and billing state cannot drift across services.
- `CHARS <world>` must be sourced from the authoritative character store for the resolved tenant and filtered to `{accountId, tenantId}` ownership before any character names are returned.
- `WORLDS` and `CHARS` responses must not leak inaccessible tenants or characters; unresolved selectors return canonical errors (`WORLD_NOT_FOUND`, `WORLD_ACCESS_DENIED`, `CHARACTER_NOT_FOUND`, `CHARACTER_ACCESS_DENIED`) without exposing whether a hidden tenant exists.

The `PLAY` flow:

- Resolves `<world>` to a canonical `tenantId` (opaque GUID) and validates it exists.
- Verifies that the account is authorized to act on that `tenantId` using the Tenant Authorization Contract (roles from `globalRoles` and `scopedRoles`).
- Consults the runtime entitlement contract `GetTenantEntitlements(tenantId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated).
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId}`. Until character selection ships, the service may bind to a default character identity derived from `accountId`, but the binding model must still treat `characterId` as a distinct identifier for forward compatibility.
- Records a `gameInstanceId` in the binding. In the current architecture, gameplay lobby flow is single-instance-per-tenant and always binds to `"primary"` after entitlement checks. Introducing multi-instance player selection requires a dedicated lobby protocol update; `PLAY <world> [character]` must not silently select among multiple live instances.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:<tenantId>:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Ensures the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, regionId>`. Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, `/ws/game/**` is routed to a stable Game Session service endpoint and the edge does not implement a lease-aware shard routing plane.

`PLAY` returns canonical, stable error codes so clients can recover deterministically:

- `WORLD_NOT_FOUND` – the supplied world selection cannot be resolved to a tenant.
- `WORLD_ACCESS_DENIED` – the authenticated account is not authorized for the tenant under `scopedRoles` / `globalRoles`.
- `TENANT_BILLING_BLOCKED` – the tenant is `suspended` or `canceled` and is not available for gameplay admission.
- `TENANT_QUOTA_EXCEEDED` – entitlements allow gameplay but quota caps (for example maximum active sessions) would be exceeded.
- `CHARACTER_NOT_FOUND` / `CHARACTER_ACCESS_DENIED` – character selection is requested but the character cannot be found or is not owned by the account (reserved for when character selection ships).
- Any subsequent attempt to switch tenants or characters for a socket must go through the same tenant-selection flow so that role checks and entitlements are re-evaluated; there is no implicit cross-tenant switching based solely on the initial `LOGIN`.

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss) or when server-side auth state has expired or been revoked. After a reconnect, clients always issue a fresh `LOGIN` and then complete lobby selection again (`PLAY <world> [character]`). If a resumable gameplay session exists for the selected `{tenantId, gameInstanceId, playerId}`, the Game Session Service resumes it; otherwise it creates a fresh gameplay session binding.

In the target design, `playerId` represents a **character-level identity** within a tenant. All Redis key formats and Game Session Service APIs must treat `playerId` as an abstract character identifier so sessions bind sockets to characters rather than raw accounts.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Multi-Client Behavior and Session Takeover

Each character can only be controlled by one session at a time.

If a new login is received for the same `playerId`:

- The existing session is terminated
- The Redis session is rebound to the new socket
- Tick state, command queues, and timers are preserved

This enables:

- Clean device handoff
- Forced logins (e.g., "kick and take over")
- Seamless resumption without gameplay loss

> 🔒 All session rebinding is enforced by the Game Session Service using Redis locks.
> 🔗 See [Redis Session Keys](./system-architecture-redis.md#session-keys-and-gameplay-binding)

---

## JWT Format and Role Claims

Internal JWTs are issued by the Account Service and used for backend gRPC authorization and first-party admin/creator web UIs. Gameplay clients **never** store or transmit tokens. Admin UIs may supply JWTs, which are validated by the Logging & Admin Service or other admin consumers. The Gateway forwards tokens without validating them, and the Game Session Service forwards tokens on behalf of connected clients.

### Claims

| Field | Description |
| --- | --- |
| `accountId` | Identity of the authenticated account |
| `iat` | Issued-at timestamp (UTC epoch seconds), required for revocation watermark checks |
| `globalRoles` | Cross-tenant privileges (e.g., `platformAdmin`, `billingAdmin`, `support`) |
| `scopedRoles` | Map of `tenantId` → roles (e.g., `"tenant-abc": ["tenantAdmin", "designer"]`) |

### Example JWT Payload

- `accountId`: `"user-123"`
- `iat`: `1735689600`
- `globalRoles`: `["billingAdmin"]`
- `scopedRoles`:
  - `"tenant-abc"` → `["tenantAdmin", "designer"]`
  - `"tenant-def"` → `["moderator"]`

> Tokens are short-lived and internal only. Gameplay context (e.g., `characterId`/legacy `playerId`, `tenantId`) is stored in Redis and sent via command envelopes.

### Token Profiles and Audiences

To keep trust boundaries clear, FireMUD distinguishes between two primary JWT profiles:

- **Browser JWTs**
  - Issued via the `/auth/login` HTTP endpoint on the Account Service after a successful login from a first-party admin/creator web UI.
  - Intended audience: frontend/meta APIs (for example an `aud` claim such as `frontend` or `meta-ui`).
  - Carried only by first-party SPAs behind the Gateway; stored in memory only and sent as `Authorization: Bearer <token>` on meta/control API calls.
  - Lifetime: short (for example 15–30 minutes) and not automatically refreshed; when a Browser JWT expires or is revoked, UIs must treat this as a hard logout condition and require re-authentication.

- **Service JWTs**
  - Issued by the Account Service for backend callers (for example, Game Session, Logging & Admin, Game Design) via the gRPC `Authenticate` or equivalent internal flows.
  - Intended audience: internal services (for example an `aud` claim such as `internal`).
  - Carried only over mTLS-protected service-to-service links.
  - Lifetime: also short-lived and backed by `session:auth:*` allowlist entries; services must not cache them beyond their expiry or ignore allowlist revocation.

Meta/control services must validate both the signature and the expected audience/profile for incoming tokens and reject tokens with an unexpected `aud` (for example, a Browser JWT presented to a purely internal service endpoint that only accepts Service JWTs).

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
- Gameplay services must treat tenant/session/player identifiers in requests as untrusted inputs and must rely on Game Session’s Redis-backed session bindings (or a shared, service-authenticated session context contract) to prevent spoofing when additional internal callers are introduced.

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

- Classify it as either **public** (no auth), **tenant-scoped**, or **cross-tenant/admin**.
- For tenant-scoped and cross-tenant/admin routes, require `AuthTokenInterceptor` and the Tenant Authorization Contract described above.
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, exporting data), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`).
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.

## Mid-Session Role Updates

If roles change during an active session (e.g., a player is promoted to admin):

1. The Game Session Service detects or requests a role refresh
2. It contacts the Account Service to obtain a new JWT
3. Updated claims are injected into subsequent gRPC calls

The Game Session Service exposes `/sessions/{sessionId}/refresh-roles` for manual refreshes. Implementations must ensure the effective claims injected into subsequent backend calls reflect the latest role assignments without requiring players to re-login.

> ✅ This process is invisible to the client — no re-login is needed.

---

## Session and Identity Management

FireMUD deliberately distinguishes between several types of “sessions” so that identity, gameplay continuity, and auth token lifetimes can evolve independently:

- **Auth token sessions** – Represented by `session:auth:<scope>:<tokenHash>` entries in Coordination Redis, backing internal JWTs used for meta/control APIs.
- **Gameplay sessions** – Tenant-scoped bindings between a connected socket (or reconnect token) and a character in a specific tenant, backed by gameplay Redis keys.
- **Control-plane UI sessions** – Browser or desktop admin/creator sessions that hold short-lived JWTs client-side and rely on auth token sessions on the server.

The Game Session Service is responsible for:

- Authenticating sockets and binding identity context
- Managing gameplay Redis session state (e.g. `characterId`, `tenantId`, tick region)
- Managing JWTs for backend interactions

### Session Types and Lifetimes

FireMUD uses distinct lifetimes and invariants for each session type:

- **Auth token allowlist entries**  
  - Keys: `session:auth:<scope>:<tokenHash>` on Coordination Redis, where `<scope>` is one of:
    - `account:<accountId>` (baseline session allowlist),
    - `tenant:<tenantId>` (regular tenant-scoped operations and gameplay admission), or
    - `global:<accountId>` (cross-tenant/global-role operations).
  - Purpose: Server-side allowlist and immediate revocation surface for internal JWTs used by meta/control services. Account-scoped entries are the baseline “token is currently allowed for this account” check; tenant-scoped entries gate regular tenant operations; global entries gate cross-tenant operations.  
  - Lifetime: Absolute TTL derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`. Entries are not extended by client activity; when they expire, new tokens must be issued. Coordination Redis resets that drop `session:auth:*` entries force re-authentication for the affected scopes.

- **Gameplay session bindings**  
  - Keys: tenant-scoped session keys described in [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding), storing `accountId`, `tenantId`, `characterId` (or a temporary `playerId` alias while migration is incomplete), and tick-region context.  
  - Purpose: Bind a connected socket (or reconnect token) to a character in a specific tenant, enforce “one session per character”, and support reconnect flows.  
  - Lifetime: Sliding TTL refreshed while the player remains active. When the TTL elapses, the session is considered abandoned and is eligible for cleanup.

    Each gameplay session binding must store the **server-side auth token identity it is operating under**:

    - `authTokenHash` – the token hash for the internal JWT that Game Session uses when making backend calls for this session (clients never see or transmit this token).
    - `authTokenIssuedAt` (`iat`) – the issuance time of that JWT.
    - When roles are refreshed mid-session, the Game Session Service must update the stored `authTokenHash` / `authTokenIssuedAt` in the gameplay session binding.

    On reconnect/resume (after the client re-`LOGIN`s and re-`PLAY`s), Game Session must load the gameplay session binding and confirm:

    - `session:auth:account:<accountId>:<authTokenHash>` exists.
    - For tenant-scoped gameplay admission, `session:auth:tenant:<tenantId>:<authTokenHash>` exists.
    - Revocation watermarks do not invalidate the token for this scope:
      - `authTokenIssuedAt` is not older than `session:auth:revoked_after:account:<accountId>`.
      - `authTokenIssuedAt` is not older than `session:auth:revoked_after:tenant:<tenantId>` for operations targeting that tenant.

    If any of these checks fail, resume is rejected with a canonical “session expired/revoked” error and the player must log in again.

### Active Session Token Refresh (Required)

Long-lived gameplay sessions require periodic service-token rotation, independent of role changes. Game Session must:

1. Refresh session service JWTs on a bounded cadence (recommended at 50% of JWT lifetime with random jitter and a hard floor of 60 seconds between refresh attempts).
2. Refresh immediately when an internal backend call fails with auth-expired or auth-revoked semantics.
3. On successful refresh, atomically update gameplay session binding fields `authTokenHash` and `authTokenIssuedAt` before using the new token for subsequent backend calls.
4. If refresh fails and the existing token is expired or revoked, fail closed for gameplay actions that require backend auth and return a canonical session-expired error, forcing re-login.

Security- and billing-related events (for example, account bans, password resets, enabling two-factor auth, tenant suspension, or subscription state changes) do not all behave identically; they follow subscription-aware rules:

- For **account-level security events** such as account bans or password resets, services must:
  - Set `session:auth:revoked_after:account:<accountId>` to “now” so previously issued tokens become invalid without requiring key scans.
  - Revoke any gameplay session keys bound to the affected account across tenants so active sockets are kicked and must re-login under the new security conditions.
- For **tenant-level billing events**, see the subscription-state mapping below and [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement) for when revocation is mandatory vs when quotas and warnings apply.

### Billing State and Revocation Rules

Subscription and billing state drives how aggressively sessions are revoked:

- `trialing`, `active`  
  - No automatic revocation based solely on billing state.  
  - Quotas and entitlements from the plan apply; gameplay and control-plane access behave normally.
- `past_due`, `grace`  
  - No automatic revocation of existing sessions.  
  - Operator and creator UIs surface strong warnings; services enforce any soft restrictions defined by the plan (for example, blocking new instances while allowing existing ones to run).  
  - Auth token sessions and gameplay sessions remain valid unless explicitly revoked for security reasons.
- `suspended`, `canceled`  
  - Tenant-level hosting is disabled for gameplay:  
    - Game Session and world-management flows must reject new game instance creations, restarts, or tenant selection for gameplay for the affected `tenantId` based on `GetTenantEntitlements`.  
    - New player logins and tenant-selection attempts for that tenant are rejected with a dedicated billing error code.  
  - Existing gameplay sessions for the tenant must be revoked so connected sockets are kicked and cannot reconnect into gameplay for that tenant.
  - Tenant-scoped authorization must be bulk-revoked by setting `session:auth:revoked_after:tenant:<tenantId>` to “now”. Services must not rely on wildcard deletes (`session:auth:tenant:<tenantId>:*`) in hot paths. Billing-safe and support-safe control-plane routes remain available as described in [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement).

### Control-Plane Logout

Control-plane logout for admin and creator UIs is implemented as an explicit API on the Account Service. Clients call `POST /auth/logout` (or the equivalent gRPC method) with the current JWT in the `Authorization` header. The Account Service:

- Computes the `tokenHash` from the presented JWT.
- Deletes the corresponding `session:auth:*:<tokenHash>` allowlist entries for that token (account-scoped, plus any global and tenant-scoped entries that were created for it).
- Emits an audit event so logout activity is observable.

This flow performs a **per-token logout**: it invalidates the current browser or device session without affecting other active sessions for the same account. Global “logout from all devices” behavior, if needed, is modeled as a separate endpoint that revokes all allowlist entries for an account rather than a single token.

> 🔗 See [Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

### Control-Plane Session UX Expectations

Control-plane UIs must treat certain auth failures as hard logout conditions and others as tenant-level billing issues:

- Meta/control APIs that rely on JWTs return canonical error codes such as:
  - `AUTH_TOKEN_EXPIRED` – The presented JWT is no longer valid because its cryptographic lifetime has ended. Frontends must clear any in-memory token, redirect to login, and display a “Session expired” message.
  - `AUTH_SESSION_REVOKED` – The JWT’s auth token sessions have been revoked due to a security event (for example, password reset, account ban, or “logout all devices”). Frontends must clear in-memory token state, redirect to login, and indicate that the session was ended for security reasons.
  - `TENANT_BILLING_BLOCKED` – The operation is blocked because the tenant’s billing state (for example, `suspended` or `canceled`) does not allow the requested action. Frontends must keep the user logged in but surface a billing-specific banner or UI state for that tenant and disable gameplay/instance management actions while still allowing the billing-safe control-plane surface (for example, updating payment details or exporting data).
- Closing a browser tab or window does **not** automatically revoke auth token sessions; users must call explicit logout (or an operator must use “logout all devices”) to revoke server-side allowlist entries before TTL expiry. On shared devices, UIs must encourage explicit logout from admin/creator sections.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — supports prompt or argument input |
| JWT Usage | Issued to backend services and first-party admin/creator web UIs; gameplay protocol clients never see or send tokens |
| Claims | `accountId`, `iat`, `globalRoles[]`, `scopedRoles{}` |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Session TTL | Derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS` + `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` |
| Gameplay Reauthentication | Required after disconnect; client re-issues `LOGIN`, and Game Session resumes via Redis if the underlying gameplay and auth session state are still valid |
| Role Enforcement | Meta/control services only; gameplay services trust Game Session Service |
| Role Updates | Refreshed in-session; no client interaction needed |
| Multi-Client Behavior | One session per character; new login replaces old session |
| Two-Factor Auth | Optional TOTP for admin and moderator accounts via `/auth/login`; required for plaintext Telnet logins when `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled |

---

## Related Documentation

- [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](./user-journeys-players.md#1-sign-up)
