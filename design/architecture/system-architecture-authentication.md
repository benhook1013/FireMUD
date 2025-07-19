# 🔐 FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands. Clients are stateless;
session state is managed server-side in Redis and restored via the Game Session
Service (TODO: Not yet implemented). The service delegates credential verification
to the Account Service's `/auth/login` endpoint. Accounts may also authenticate
using linked external providers such as Google, Discord, or Steam (TODO: Not yet
implemented).

Admin and moderator accounts can optionally enable **two-factor authentication**. When a
`two_factor_secret` is present, the Account Service expects a one-time TOTP code during login.
The `/auth/login` REST endpoint and the `Authenticate` gRPC call both accept an `otp` field for
this purpose. The Game Session Service should forward this OTP when a player logs in. (TODO: Not yet implemented)
Issued JWTs are stored in Redis using keys `session:{tenantId}:{token}` with an expiration controlled by `session-expiration-ms` (default `3600000` ms).
JWT and session lifetimes are configured via the `FIREMUD_AUTH_JWT_EXPIRATION_MS` and `FIREMUD_AUTH_SESSION_EXPIRATION_MS` environment variables documented in
[Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

---

## 🔁 Login and Session Flow

All clients — whether connecting via Telnet or WebSocket — must authenticate using the `LOGIN` command (TODO: Not yet implemented):

- `LOGIN` → Starts prompt-based login (username → password) (TODO: Not yet implemented)
- `LOGIN <username> <password>` → Attempts immediate login (TODO: Not yet implemented)
- `LOGON` → Alias for `LOGIN` (TODO: Not yet implemented)

Login commands include the `tenantId` along with the account credentials and optional OTP code (TODO: Not yet implemented).
This selects the target game during authentication and enforces multi-tenant isolation from the
start. Account management endpoints still rely solely on the account ID.

Clients must re-authenticate **only after disconnecting** (TCP or WebSocket loss) (TODO: Not yet implemented).
If a valid Redis session exists (`accountId + playerId`), the Game Session Service resumes
gameplay seamlessly. (TODO: Not yet implemented)

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## 👥 Multi-Client Behavior and Session Takeover (TODO: Not yet implemented)

Each character can only be controlled by one session at a time.

If a new login is received for the same `playerId`:

- The existing session is terminated
- The Redis session is rebound to the new socket
- Tick state, command queues, and timers are preserved

This enables:

- Clean device handoff
- Forced logins (e.g., "kick and take over")
- Seamless resumption without gameplay loss
(TODO: Not yet implemented)

> 🔒 All session rebinding is enforced by the Game Session Service using Redis locks.
> 🔗 See [Redis Session Keys](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding)

---

## 🧾 JWT Format and Role Claims

Internal JWTs are issued by the Account Service and used for backend gRPC authorization.
Gameplay clients **never** store or transmit tokens. Admin UIs may supply JWTs, which are
validated by the Logging & Admin Service or other admin consumers. The Gateway currently forwards
tokens without validating them, while the Game Session Service will add token forwarding logic in a
future iteration. (TODO: Not yet implemented)

### 🧠 Claims

| Field         | Description                                                             |
|---------------|-------------------------------------------------------------------------|
| `accountId`   | Identity of the authenticated account                                   |
| `globalRoles` | Cross-game privileges (e.g., `platformAdmin`, `moderator`)              |
| `scopedRoles` | Map of `tenantId` → roles (e.g., `"tenant-abc": ["admin", "designer"]`) (TODO: Not yet implemented) |

### 🧾 Example JWT Payload

- `accountId`: `"user-123"`
- `globalRoles`: `["moderator"]`
- `scopedRoles` (TODO: Not yet implemented):
  - `"tenant-abc"` → `["admin", "designer"]`
  - `"tenant-def"` → `["moderator"]`

> Tokens are short-lived and internal only. Gameplay context (e.g., `playerId`, `tenantId`) is
> stored in Redis and sent via command envelopes.

---

## 👮 Role-Based Authorization

Access to services is governed by roles from the JWT:

| Context        | Description                                                         |
|----------------|---------------------------------------------------------------------|
| `globalRoles`  | Platform-wide access (e.g., moderation, admin dashboards)           |
| `scopedRoles`  | Per-game access (e.g., designer tools, admin features for a game)   |

### JWT Usage Scope

- ✅ **Meta/control services** (e.g. Game Design, Admin, Account) validate JWTs to authorize access
- 🚫 **Gameplay services** (e.g. Game Logic, Entity, World) do **not** validate JWTs — they
  rely on the Game Session Service to enforce access

All meta services use a shared `AuthTokenInterceptor` that extracts claims from
the `Authorization` header and stores them in a thread-local `SessionContext`.
Service methods read roles from this context via the `@RequireAdminRole`
annotation (or similar). Gameplay services never read or propagate these claims.

---

## 🔄 Mid-Session Role Updates (TODO: Not yet implemented)

If roles change during an active session (e.g., a player is promoted to admin):

1. The Game Session Service detects or requests a role refresh (TODO: Not yet implemented)
2. It contacts the Account Service to obtain a new JWT (TODO: Not yet implemented)
3. Updated claims are injected into subsequent gRPC calls (TODO: Not yet implemented)

The Game Session Service exposes `/sessions/{sessionId}/refresh-roles` for manual refreshes. The
current implementation simply logs the request and returns `"refreshed"`; full token regeneration
will be implemented in a future iteration. (TODO: Not yet implemented)

> ✅ This process is invisible to the client — no re-login is needed.

---

## 🧠 Session and Identity Management

- The Game Session Service is responsible for:

- Authenticating sockets and binding identity context (TODO: Not yet implemented)
- Managing Redis session state (e.g. `playerId`, `tenantId`, tick region)
- Managing JWTs for backend interactions (TODO: Not yet implemented)
- Session entries in Redis expire after `FIREMUD_AUTH_SESSION_EXPIRATION_MS` milliseconds so
  abandoned sessions cannot linger indefinitely. The default value is `3600000` ms as defined by
  `AuthProperties.sessionExpirationMs`.

> 🔗 See [Session Keys and Gameplay Binding](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding)
> for Redis structure and gameplay rebinding.

---

## ✅ Summary

| Topic                 | Description                                                      |
|-----------------------|------------------------------------------------------------------|
| Auth Command          | `LOGIN` (or `LOGON`) — supports prompt or argument input (TODO: Not yet implemented) |
| JWT Usage             | Internal-only for backend gRPC auth                             |
| Claims                | `accountId`, `globalRoles[]`, `scopedRoles{}` (TODO: Not yet implemented) |
| Session State         | Stored in Redis; bound to socket by Game Session Service (TODO: Not yet implemented) |
| Session TTL           | Controlled by `FIREMUD_AUTH_SESSION_EXPIRATION_MS`             |
| Reauthentication      | Required after disconnect; resumes via Redis if valid (TODO: Not yet implemented) |
| Role Enforcement      | Meta/control services only; gameplay services trust Game Session Service |
| Role Updates          | Refreshed in-session; no client interaction needed (TODO: Not yet implemented) |
| Multi-Client Behavior | One session per character; new login replaces old session (TODO: Not yet implemented) |
| Two-Factor Auth       | Optional TOTP for admin and moderator accounts via `/auth/login` |

---

## 📚 Related Documentation

- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication)
- [User Journeys – Sign Up](./user-journeys.md#1-sign-up)
