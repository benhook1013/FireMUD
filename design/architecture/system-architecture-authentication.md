# 🔐 FireMUD System Architecture: Authentication & Authorization

This document outlines how authentication and authorization are handled in FireMUD, including how accounts are authenticated, how identity context is propagated across services, and how roles govern access to features. Authentication uses internal-only JWTs, and reconnection is backed by Redis session state.

---

## 🧾 Token Format and Claims

FireMUD uses **JWTs (JSON Web Tokens)** as internal backend-only tokens — never seen or stored by clients.

Tokens are issued by the **Account Service** and used by other services to validate access context. A token's contents depend on whether the player has selected a character yet.

### 🔖 Token Claims Summary

- **Initial Login JWT**:  
  Includes `accountId`, `roles[]`  
  Used after authentication, before entering a game world

- **Post-Character Selection JWT**:  
  Includes `accountId`, `roles[]`, `playerId`, `worldId`  
  Used after selecting a character and entering a game world

> These tokens are passed only through backend gRPC calls. Clients never see or store them.

---

## 🔑 Login Flow and Token Handling

Clients send a plaintext `LOGIN` command after connecting via Telnet or Web. They never transmit or manage JWTs — all token logic is internal.

### 🔁 Login Flow Summary

1. **Client sends `LOGIN`** command with credentials
2. **Game Session** verifies via the **Account Service**
3. Account Service issues an **initial JWT** with `accountId`, `roles[]`
4. Game Session stores it in Redis and binds it to the connection
5. After the player selects a character/world, Game Session issues a **new JWT** with `playerId`, `worldId`

This JWT is injected into all gRPC calls to downstream services, which use it to authorize commands and actions.

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for how session resumption works after disconnects.

---

## 👮 Role-Based Authorization

Access to admin tools, moderation commands, and world configuration APIs is governed by the `roles[]` claim in the JWT.

### Local Authorization Checks

- All services decode the JWT on each request
- Validation is done **per-service** with no central auth gateway
- Unauthorized requests are rejected locally

Roles include:

- `player` — standard gameplay
- `moderator` — moderation tools
- `admin` — game config and admin APIs

---

## 🧠 Session Context and Propagation

The **Game Session Service** is the authority on gameplay sessions:

- Binds the JWT to the client socket after login
- Stores session state in Redis (e.g. `session:{playerId}`)
- Reissues JWTs with `playerId` and `worldId` after character selection
- Validates and forwards tokens in all internal gRPC calls

> Clients are stateless. They reconnect by sending `LOGIN` again, and Game Session may resume the previous session if Redis state is intact.

---

## 🔄 Reconnection and Multi-Client Behavior

### Reconnection

- Clients must **re-authenticate** via `LOGIN` after disconnect
- If the same account/character is reused:
  - Game Session checks Redis
  - If session exists → resumes gameplay
  - Otherwise → starts a fresh session

> Backend service restarts (e.g. Gateway, Game Session) do not require re-authentication as long as the client's network connection remains up.

### Multi-Client Sessions

- An account may connect from **multiple clients** using different characters
- Logging in as the **same character** on a new client:
  - Terminates the old session
  - Transfers control to the new session

---

## ✅ Summary

| Topic                    | Description                                                            |
|--------------------------|------------------------------------------------------------------------|
| Token Type               | JWT (internal-only, never client-visible)                              |
| Issuer                   | Account Service                                                        |
| Initial Claims           | `accountId`, `roles[]`                                                 |
| Post-Login Claims        | `accountId`, `roles[]`, `playerId`, `worldId`                          |
| Token Usage              | Injected into gRPC calls only                                          |
| Auth Enforcement         | Local per-service based on decoded JWT claims                          |
| Session Storage          | Redis (`session:{playerId}`), bound to socket                          |
| Reconnection Behavior    | Requires fresh `LOGIN`; may resume via Redis                           |
| Multi-Client Sessions    | One per character; logging in again transfers session control          |

> 🔗 For more on reconnection behavior, session resumption, and layered fault tolerance, see [Reconnection Strategy](./system-architecture-reconnection.md).
