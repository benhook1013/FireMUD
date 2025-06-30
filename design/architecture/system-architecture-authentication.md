# 🔐 FireMUD System Architecture: Authentication & Authorization

This document explains how FireMUD authenticates players, manages internal session tokens, and enforces role-based access across services.

Authentication uses **plaintext `LOGIN` commands** (also aliased as `LOGON`) and internal-only JWTs. All session state and identity context are managed server-side and backed by Redis.

---

## 🧭 Login Command and Flow

FireMUD supports both **MUD-style prompt-based login** and **argument-based login** through a unified `LOGIN` command:

- `LOGIN` → Begins interactive prompt-based login flow (e.g., name → password)
- `LOGIN <username> <password>` → Direct login attempt

> The `LOGON` command is an exact alias of `LOGIN`. Both behave identically and are interchangeable.

### 🔁 Login Flow Summary

1. Client connects (Telnet or Web)
2. Sends `LOGIN` (with or without arguments)
3. **Game Session Service** validates credentials via **Account Service**
4. On success:
   - A **JWT** is issued with identity and role claims
   - Session metadata is stored in Redis and bound to the socket
5. After character/world selection, session state is updated with `playerId`, `worldId`

---

## 🧾 JWT Format and Claims

JWTs are issued by the Account Service and used only for backend gRPC calls. Clients never see or store tokens.

| Field          | Description                                                                 |
|----------------|-----------------------------------------------------------------------------|
| `accountId`    | Global identity of the authenticated account                                |
| `globalRoles`  | (Optional) Roles valid across all games — e.g., `moderator`, `platformAdmin`|
| `scopedRoles`  | Map of `gameId` → list of roles — e.g. `"game-abc": ["admin", "designer"]`  |

Example JWT payload:

- `accountId`: `"user-123"`
- `globalRoles`: `["moderator"]`
- `scopedRoles`:
  - `"game-abc"` → `["admin", "designer"]`
  - `"game-def"` → `["moderator"]`

> Tokens are short-lived and scoped to service communication.  
> Gameplay context (`playerId`, `worldId`) is managed separately in Redis and command envelopes.

---

## 👮 Role-Based Authorization

Access to meta/control-plane services is governed by JWT role claims.

| Role Context   | Description                                                           |
|----------------|-----------------------------------------------------------------------|
| `globalRoles[]`| Cross-game privileges like moderation or platform-wide admin access   |
| `scopedRoles{}`| Per-game access for roles like `admin`, `designer`, or `moderator`    |

### JWT Use Scope

- **Meta/control services** (e.g. Admin, Game Design, Account) validate JWT claims to authorize access to tooling, configuration, or moderation features.
- **Gameplay services** (e.g. Game Logic, Entity, World) do **not** validate JWTs directly — they trust Game Session for all gameplay context and access enforcement.

---

## 🔄 Role Updates and Token Refresh

If an account is granted new roles during an active session (e.g. made admin for a game), the system can **refresh the JWT mid-session**:

- Game Session detects or requests role update (polling, subscription, manual trigger)
- Game Session re-contacts the Account Service to reissue an updated JWT
- All future gRPC calls use the new token with updated claims

> This enables dynamic permission updates **without requiring logout or re-login**, since the client never handles tokens directly.

---

## 🧠 Session and Identity Propagation

The **Game Session Service** manages:

- Socket authentication and identity binding
- Redis session storage (`session:{playerId}`)
- Gameplay context such as `playerId` and `worldId` after character selection
- Injection of JWTs into downstream gRPC calls

`playerId` and `worldId` are attached to commands routed by Game Session, not embedded in the JWT. This separation simplifies character switching and gameplay context management.

---

## 🔁 Reconnection and Session Transfer

Reauthentication is only required after **client disconnect**:

- Clients reconnect and resend `LOGIN`
- Game Session checks Redis for session state (`accountId + playerId`)
  - If valid: gameplay resumes
  - If missing: a new session is created

> Backend restarts (Gateway, Proxy, Game Session) are transparent **if the client connection is maintained**.

## 👥 Multi-Client and Session Takeover

An account may be logged in from **multiple clients** simultaneously, each using a different character.

If a client logs into the **same character** from another session:

- The existing session is **terminated**
- Control is **transferred** to the new session
- Redis session data (`session:{playerId}`) is rebound to the new socket
- Gameplay resumes seamlessly, preserving tick participation, command queues, and timers

This mechanism enables:

- Fast, clean **session handoff** between devices or locations
- Administrative or player-initiated **"force login"** behavior without data loss
- A consistent model that aligns session ownership strictly with socket binding

> 🔒 This behavior is enforced by the **Game Session Service**, based on Redis state and connection locks.

---

## ✅ Summary

| Topic                 | Description                                                        |
|-----------------------|--------------------------------------------------------------------|
| Auth Command          | `LOGIN` (or `LOGON`) — supports prompt or argument input           |
| Token Type            | JWT (internal use only)                                            |
| Claims                | `accountId`, `globalRoles[]`, `scopedRoles{}`                     |
| Character/World Data  | Stored in Redis; passed as fields in gameplay RPCs                |
| Auth Enforcement      | Meta/control services inspect JWT for per-game or global roles    |
| Session State         | Managed in Redis and bound to client socket                        |
| Role Changes          | Handled via mid-session token refresh through Game Session         |
| Reconnection          | Requires fresh `LOGIN`; resumes if Redis session exists           |
| Multi-Client Behavior | One session per character; reconnect takes control cleanly         |

---

📚 Related:

- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
