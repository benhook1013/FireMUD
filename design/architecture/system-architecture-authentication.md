# 🔐 FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues internal JWTs, manages session state, and enforces role-based access across services.

Authentication is performed via plaintext `LOGIN` commands. Clients are stateless; session state is managed server-side in Redis and restored via the Game Session Service.

---

## 🔁 Login and Session Flow

All clients — whether connecting via Telnet or WebSocket — must authenticate using the `LOGIN` command:

- `LOGIN` → Starts prompt-based login (username → password)
- `LOGIN <username> <password>` → Attempts immediate login
- `LOGON` → Alias for `LOGIN`

Clients must re-authenticate **only after disconnecting** (TCP or WebSocket loss).  
If a valid Redis session exists (`accountId + playerId`), the Game Session Service resumes gameplay seamlessly.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## 👥 Multi-Client Behavior and Session Takeover

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

## 🧾 JWT Format and Role Claims

Internal JWTs are issued by the Account Service and used for backend gRPC authorization. Clients **never** store or transmit tokens.

### 🧠 Claims

| Field         | Description                                                             |
|---------------|-------------------------------------------------------------------------|
| `accountId`   | Identity of the authenticated account                                   |
| `globalRoles` | Cross-game privileges (e.g., `platformAdmin`, `moderator`)              |
| `scopedRoles` | Map of `gameId` → roles (e.g., `"game-abc": ["admin", "designer"]`)     |

### 🧾 Example JWT Payload

- `accountId`: `"user-123"`
- `globalRoles`: `["moderator"]`
- `scopedRoles`:
  - `"game-abc"` → `["admin", "designer"]`
  - `"game-def"` → `["moderator"]`

> Tokens are short-lived and internal only. Gameplay context (e.g., `playerId`, `worldId`) is stored in Redis and sent via command envelopes.

---

## 👮 Role-Based Authorization

Access to services is governed by roles from the JWT:

| Context        | Description                                                         |
|----------------|---------------------------------------------------------------------|
| `globalRoles`  | Platform-wide access (e.g., moderation, admin dashboards)           |
| `scopedRoles`  | Per-game access (e.g., designer tools, admin features for a game)   |

### JWT Usage Scope

- ✅ **Meta/control services** (e.g. Game Design, Admin, Account) validate JWTs to authorize access
- 🚫 **Gameplay services** (e.g. Game Logic, Entity, World) do **not** validate JWTs — they rely on the Game Session Service to enforce access

---

## 🔄 Mid-Session Role Updates

If roles change during an active session (e.g., a player is promoted to admin):

1. The Game Session Service detects or requests a role refresh
2. It contacts the Account Service to obtain a new JWT
3. Updated claims are injected into subsequent gRPC calls

> ✅ This process is invisible to the client — no re-login is needed.

---

## 🧠 Session and Identity Management

The Game Session Service is responsible for:

- Authenticating sockets and binding identity context
- Managing Redis session state (e.g. `playerId`, `worldId`, tick region)
- Reinjecting updated JWTs into backend calls when needed

> 🔗 See [Session Keys and Gameplay Binding](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

---

## ✅ Summary

| Topic                 | Description                                                      |
|-----------------------|------------------------------------------------------------------|
| Auth Command          | `LOGIN` (or `LOGON`) — supports prompt or argument input         |
| JWT Usage             | Internal-only for backend gRPC auth                             |
| Claims                | `accountId`, `globalRoles[]`, `scopedRoles{}`                   |
| Session State         | Stored in Redis; bound to socket by Game Session                |
| Reauthentication      | Required after disconnect; resumes via Redis if valid           |
| Role Enforcement      | Meta/control services only; gameplay services trust Game Session |
| Role Updates          | Refreshed in-session; no client interaction needed              |
| Multi-Client Behavior | One session per character; new login replaces old session        |

---

📚 Related:

- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
