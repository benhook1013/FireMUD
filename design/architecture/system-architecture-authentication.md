# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands. Clients are stateless; session state is managed server-side in Redis and restored via the Game Session Service. The service delegates credential verification to the Account Service's `/auth/login` endpoint. Accounts may also authenticate using linked external providers such as Google, Discord, or Steam.

## Responsibility Split

- **Account Service** – Verifies credentials (including OTP), issues JWTs, and publishes JWKS for validation.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected routes but does not validate tokens.

Admin and moderator accounts can optionally enable **two-factor authentication**. When a `two_factor_secret` is present, the Account Service expects a one-time TOTP code during login. The `/auth/login` REST endpoint and the `Authenticate` gRPC call both accept an `otp` field for this purpose. The Game Session Service forwards this OTP when a player logs in.

When `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins over **plaintext Telnet** are further constrained: only accounts that both (a) have two-factor authentication enabled and (b) explicitly opt in to “allow plaintext Telnet login” may authenticate via the raw TCP port. All other accounts must use the TLS Telnet port or the web client and receive a clear error if they attempt to log in over plaintext Telnet.

Issued JWTs are stored in Redis using keys `session:auth:<scope>:<tokenHash>` where `scope` typically encodes tenant- or account-level context (for example `tenant:<tenantId>`), and `tokenHash` is a fixed-length digest (for example, a hex-encoded SHA-256 of the JWT). This keeps key lengths bounded and avoids leaking raw token contents into key names. The entries use a TTL derived from the JWT lifetime so operators do not need to tune separate “JWT” and “session” expiry knobs:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

Token validity semantics:

- A JWT must be cryptographically valid (signature and time-based claims such as `exp`), and its `session:auth:<scope>:<tokenHash>` entry must be present in Redis.
- Redis therefore acts as a server-side allowlist and immediate revocation surface: deleting `session:auth:<scope>:<tokenHash>` revokes a still-unexpired JWT; coordination resets that drop `session:*` force re-authentication for the affected scopes.
- During Coordination Redis outages, token-gated internal calls fail closed (authorization cannot be established without the allowlist check). This is an explicit availability vs security tradeoff; gameplay clients do not transmit JWTs directly, but backend calls made on their behalf still require the server-side session/token entries to be present.

---

## Identity, Roles, and Tenant Access

Authentication always identifies a single platform account, represented by the `accountId` claim. Tenant-specific state and permissions are layered on top of this identity:

- `accountId` – Global platform identity managed by the Account Service.
- `globalRoles` – Cross-tenant roles such as `platformAdmin` or global moderators.
- `scopedRoles` – A map from `tenantId` to roles granted to the account within that tenant (for example, `"tenant-abc": ["player", "designer"]`).

For the data model underpinning `accountId`, `tenantId`, characters, and membership relationships, see the [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) design.

### Tenant Authorization Contract

All meta/control services (Account, Game Design, Logging & Admin, and similar HTTP/gRPC APIs) must enforce a consistent tenant-authorization contract:

- Each incoming request is authenticated to a single `accountId` using a JWT validated against the Account Service JWKS.
- The effective tenant set for the request is derived from the token:
  - For tenant-scoped operations, the service computes the set of `tenantId` values present in `scopedRoles` combined with any tenant IDs implied by `globalRoles` (for example, `platformAdmin` may be permitted to act on all tenants).
  - For cross-tenant operations, the service must explicitly check that the caller has a `globalRole` that authorizes cross-tenant access; tenant-scoped roles must never implicitly grant cross-tenant privileges.
- If an API accepts a `tenantId` (path, query parameter, or body field), the service must validate that:
  - `tenantId` is in the effective tenant set for tenant-scoped calls, or
  - The caller holds a cross-tenant `globalRole` that explicitly allows operating on the requested tenant.
- Services must apply the `tenantId` filter to all read and write queries, even when the client does not explicitly supply a `tenantId` (for example, when inferring tenant from a game instance).

A shared library helper (for example, a `TenantAccessGuard` used by `AuthTokenInterceptor`) should be used by all meta/control services so this contract is implemented in one place and kept in sync with future role/tenant model changes.

---

## Login and Session Flow

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

Telnet-specific behaviors (such as the optional `SESSION` envelope used by advanced clients) reuse this same canonical login flow. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The Telnet/WebSocket client emits `LOGIN <username> <password> [otp]` (or the `LOGON` alias).
2. The Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `/auth/login` REST endpoint or the `Authenticate` gRPC method with a payload containing `username`, `password`, the optional `otp`, and connection metadata indicating the **transport security** (for example `transportSecurity=PLAINTEXT_TELNET` vs `transportSecurity=TLS_TELNET` / `WEB_TLS`). This metadata is derived from the TCP Proxy and Gateway handshake so the Account Service can enforce deployment-wide and per-account policies for plaintext Telnet logins.
3. The Account Service validates credentials (including the OTP when present) and returns either a JWT + account metadata or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `AUTH_PLAINTEXT_TCP_NOT_PERMITTED`, or `AUTH_UPSTREAM_FAILURE`. The Game Session Service translates these codes into the text-protocol equivalents (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, `ERROR 2FA_REQUIRED_FOR_PLAINTEXT_TCP`, `ERROR PLAINTEXT_TCP_NOT_PERMITTED`, etc.) so WebSocket and Telnet clients always see the same response format regardless of how the upstream message is worded. For plaintext Telnet logins, the combination of `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and the per-account “allow plaintext Telnet login” flag follows the safety matrix defined in the Security Architecture’s **Plaintext Telnet safety matrix** section; implementations must treat any combination outside the allowed cells as a hard denial (`AUTH_PLAINTEXT_TCP_NOT_PERMITTED` or `AUTH_2FA_REQUIRED_FOR_PLAINTEXT_TCP`) rather than silently weakening security.
4. Success responses cause the Game Session Service to store the JWT and claims in Redis, bind the socket to the authenticated session, and emit `OK LOGIN Logged in as <username>` on the wire. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by this authentication handshake. Any text command received before a session is authenticated is rejected with `ERROR NOT_AUTHENTICATED`, except in explicitly documented development/test bypass modes that grant temporary access. Once the login-and-session vertical slice ships, these commands are no longer processed for anonymous sessions, keeping the gameplay queue free of unauthenticated traffic.

Login commands only carry account credentials (plus optional OTP). Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds. Tenant context is bound later when the client selects a world, and the Game Session Service derives the `tenantId` from that world selection to enforce isolation when creating Redis session entries.

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss). If a valid Redis session exists (`accountId + playerId`), the Game Session Service resumes gameplay seamlessly.

**Note:** This slice treats `playerId` as the authenticated `accountId` because explicit character selection is not yet implemented. Once characters land, the service will map `playerId` to the selected avatar instead of the raw account identifier so session resumption aligns with actual player context.

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

Internal JWTs are issued by the Account Service and used for backend gRPC authorization. Gameplay clients **never** store or transmit tokens. Admin UIs may supply JWTs, which are validated by the Logging & Admin Service or other admin consumers. The Gateway forwards tokens without validating them, and the Game Session Service forwards tokens on behalf of connected clients.

### Claims

| Field | Description |
| --- | --- |
| `accountId` | Identity of the authenticated account |
| `globalRoles` | Cross-game privileges (e.g., `platformAdmin`, `moderator`) |
| `scopedRoles` | Map of `tenantId` → roles (e.g., `"tenant-abc": ["admin", "designer"]`) |

### Example JWT Payload

- `accountId`: `"user-123"`
- `globalRoles`: `["moderator"]`
- `scopedRoles`:
  - `"tenant-abc"` → `["admin", "designer"]`
  - `"tenant-def"` → `["moderator"]`

> Tokens are short-lived and internal only. Gameplay context (e.g., `playerId`, `tenantId`) is stored in Redis and sent via command envelopes.

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

All meta services use a shared `AuthTokenInterceptor` that extracts claims from the `Authorization` header and stores them in a thread-local `SessionContext`. Service methods read roles from this context via the `@RequireAdminRole` annotation (or similar). Gameplay services never read or propagate these claims.

---

## Trust Boundaries and Token Validation

The Gateway sits at the edge of the platform and is deliberately **not** an authorization authority:

- Spring Cloud Gateway enforces the presence of an `Authorization` header for protected routes but does not validate or interpret JWT contents.
- All meta/control services that receive requests from the Gateway must validate JWTs using the Account Service JWKS and the shared `AuthTokenInterceptor`. No route that depends on JWT claims may bypass this middleware.
- Gameplay services never accept or validate browser- or client-supplied JWTs directly. They rely on the Game Session Service to enforce access based on Redis session context and server-to-server JWTs.

When adding a new public HTTP/gRPC route:

- Classify it as either **public** (no auth), **tenant-scoped**, or **cross-tenant/admin**.
- For tenant-scoped and cross-tenant/admin routes, require `AuthTokenInterceptor` and the Tenant Authorization Contract described above.
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.

## Mid-Session Role Updates

If roles change during an active session (e.g., a player is promoted to admin):

1. The Game Session Service detects or requests a role refresh
2. It contacts the Account Service to obtain a new JWT
3. Updated claims are injected into subsequent gRPC calls

The Game Session Service exposes `/sessions/{sessionId}/refresh-roles` for manual refreshes. The current implementation logs the request and returns `"refreshed"`, while full token regeneration occurs automatically during role updates.

> ✅ This process is invisible to the client — no re-login is needed.

---

## Session and Identity Management

- The Game Session Service is responsible for:

  - Authenticating sockets and binding identity context
  - Managing Redis session state (e.g. `playerId`, `tenantId`, tick region)
  - Managing JWTs for backend interactions

- Session entries in Redis expire after the derived `session_expiration_ms` window (`FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`) so abandoned sessions cannot linger indefinitely.

### Session Types and Lifetimes

FireMUD uses distinct types of “sessions” with different lifetimes and responsibilities:

- **Auth token allowlist entries**  
  - Keys: `session:auth:<scope>:<tokenHash>` on Coordination Redis.  
  - Purpose: Server-side allowlist and revocation surface for internal JWTs used by meta/control services.  
  - Lifetime: Absolute TTL derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`. Entries are not extended by client activity; when they expire, new tokens must be issued.

- **Gameplay session bindings**  
  - Keys: tenant-scoped session keys described in [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding), storing `accountId`, `tenantId`, character identifiers, and tick-region context.  
  - Purpose: Bind a connected socket (or reconnect token) to a character in a specific tenant, enforce “one session per character”, and support reconnect flows.  
  - Lifetime: Sliding TTL refreshed while the player remains active. When the TTL elapses, the session is considered abandoned and is eligible for cleanup.

Security- and billing-related events (for example, account bans, password resets, enabling two-factor auth, tenant suspension, or subscription expiration) must actively revoke both:

- Relevant `session:auth:*` entries so new meta/control calls fail closed until re-authentication, and
- Any gameplay session keys bound to the affected account and tenant, so active sockets are kicked and must re-login under the new security or billing conditions.

> 🔗 See [Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — supports prompt or argument input |
| JWT Usage | Internal-only for backend gRPC auth |
| Claims | `accountId`, `globalRoles[]`, `scopedRoles{}` |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Session TTL | Derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS` + `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` |
| Reauthentication | Required after disconnect; resumes via Redis if valid |
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
