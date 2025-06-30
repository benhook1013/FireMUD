# 🔐 FireMUD System Architecture: Authentication & Authorization

This document explains how FireMUD authenticates players, manages internal session tokens, and enforces role-based access across services.

Authentication uses **plaintext `LOGIN` commands** from clients and **internal-only JWTs**. All session state and identity context are managed server-side, backed by Redis.

---

## 🔑 Login and Session Flow

Clients never see or store tokens. Instead, the `LOGIN` command initiates authentication:

1. Client connects (Telnet or Web)
2. Sends `LOGIN` with credentials
3. **Game Session Service** validates via **Account Service**
4. On success:
   - Game Session receives a **JWT** with `accountId`, `roles[]`
   - Binds the token to the socket and stores session state in Redis
5. When the player selects a character/world:
   - A new **augmented JWT** is issued with `playerId`, `worldId`

These tokens are injected into **backend gRPC calls** only — never exposed to clients.

> 🔗 For reconnection flows and Redis-backed session recovery, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## 🧾 JWT Format and Claims

| Stage                 | Claims Included                              |
|-----------------------|-----------------------------------------------|
| Initial Login Token   | `accountId`, `roles[]`                        |
| Post-Selection Token  | `accountId`, `roles[]`, `playerId`, `worldId` |

- Tokens are **short-lived**, internal, and **backend-only**
- No tokens are sent to or from clients

---

## 👮 Role-Based Authorization

Access control is enforced **locally per service** based on decoded JWT claims.

| Role       | Permissions                              |
|------------|-------------------------------------------|
| `player`   | Standard gameplay                         |
| `moderator`| Use of moderation tools                   |
| `admin`    | Admin APIs and configuration tools        |

Each service verifies the JWT and rejects unauthorized access.

---

## 🧠 Session and Identity Propagation

- Game Session is the **authority** on session state
- Redis key: `session:{playerId}` tracks socket bindings, timers, and tick state
- Game Session reissues updated JWTs after character/world selection
- JWTs are injected into all downstream gRPC calls for access control

---

## 🔄 Reconnection and Session Transfer

Reauthentication is only required after **client disconnect**:

- Clients reconnect and resend `LOGIN`
- Game Session checks Redis for session state (`accountId + playerId`)
  - If valid: gameplay resumes
  - If missing: a new session is created

> Backend restarts (Gateway, Proxy, Game Session) are transparent **if the client connection is maintained**.

### Multi-Client Behavior

- Multiple clients may connect using different characters
- Logging in as the **same character** from another client:
  - Terminates the old session
  - Transfers gameplay control to the new client

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for layered recovery and session resumption.

---

## ✅ Summary

| Topic                 | Description                                                  |
|-----------------------|--------------------------------------------------------------|
| Auth Flow             | Client sends `LOGIN`; Game Session verifies via Account      |
| Token Type            | Internal-only JWT                                            |
| Claims                | `accountId`, `roles[]`, `playerId`, `worldId` (post-select)  |
| Token Usage           | Injected into backend gRPC calls only                        |
| Auth Enforcement      | Local per-service via JWT claims                             |
| Session Storage       | Redis (`session:{playerId}`), bound to socket                |
| Reconnection          | Requires fresh `LOGIN`; resumes if Redis session is intact   |
| Multi-Client Behavior | One active session per character; new login takes control    |

---

📚 Related:

- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
