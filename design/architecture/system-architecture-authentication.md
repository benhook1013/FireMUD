# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands. Clients are stateless; session state is managed server-side in Redis and restored via the Game Session Service. The service delegates credential verification to the Account Service's `/auth/login` endpoint. Accounts may also authenticate using linked external providers such as Google, Discord, or Steam.

## Responsibility Split

- **Account Service** – Verifies credentials (including OTP), issues JWTs, and publishes JWKS for validation.
- **Game Session Service** – Fronts the `LOGIN` command, stores session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login; validates tokens only for admin/meta flows.

Admin and moderator accounts can optionally enable **two-factor authentication**. When a `two_factor_secret` is present, the Account Service expects a one-time TOTP code during login. The `/auth/login` REST endpoint and the `Authenticate` gRPC call both accept an `otp` field for this purpose. The Game Session Service forwards this OTP when a player logs in.

Issued JWTs are stored in Redis using keys `session:{tenantId}:{token}` with an expiration controlled by `session-expiration-ms` (default `3600000` ms). JWT and session lifetimes are configured via the `FIREMUD_AUTH_JWT_EXPIRATION_MS` and `FIREMUD_AUTH_SESSION_EXPIRATION_MS` environment variables documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

---

## Login and Session Flow

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The Telnet/WebSocket client emits `LOGIN <username> <password> [otp]` (or the `LOGON` alias).
2. The Game Session Service parses the line, normalizes casing, and issues a synchronous call to the Account Service `/auth/login` REST endpoint or the `Authenticate` gRPC method with a payload containing `username`, `password`, and the optional `otp`.
3. The Account Service validates credentials (including the OTP when present) and returns either a JWT + account metadata or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, or `AUTH_UPSTREAM_FAILURE`. The Game Session Service translates these codes into the text-protocol equivalents (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, etc.) so WebSocket and Telnet clients always see the same response format regardless of how the upstream message is worded.
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
> 🔗 See [Redis Session Keys](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding)

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
- Session entries in Redis expire after `FIREMUD_AUTH_SESSION_EXPIRATION_MS` milliseconds so abandoned sessions cannot linger indefinitely. The default value is `3600000` ms as defined by `AuthProperties.sessionExpirationMs`.

> 🔗 See [Session Keys and Gameplay Binding](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — supports prompt or argument input |
| JWT Usage | Internal-only for backend gRPC auth |
| Claims | `accountId`, `globalRoles[]`, `scopedRoles{}` |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Session TTL | Controlled by `FIREMUD_AUTH_SESSION_EXPIRATION_MS` |
| Reauthentication | Required after disconnect; resumes via Redis if valid |
| Role Enforcement | Meta/control services only; gameplay services trust Game Session Service |
| Role Updates | Refreshed in-session; no client interaction needed |
| Multi-Client Behavior | One session per character; new login replaces old session |
| Two-Factor Auth | Optional TOTP for admin and moderator accounts via `/auth/login` |

---

## Related Documentation

- [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](./user-journeys.md#1-sign-up)
