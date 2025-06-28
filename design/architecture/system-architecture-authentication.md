# 🔐 FireMUD System Architecture: Authentication & Authorization

This document details the authentication and authorization mechanisms in FireMUD. It explains how tokens are issued and validated, how roles govern access, and how gameplay sessions handle secure context propagation and reconnection across services.

---

## 🧾 Token Issuance and Format

FireMUD uses **JWTs (JSON Web Tokens)** as *internal authentication tokens* to represent authenticated **accounts**. These are used solely within the backend system — clients never see or transmit them.

### 🏷️ Claims Included in JWT

JWTs are issued by the **Account Service** and contain signed claims such as:

| Claim        | Description                                               |
|--------------|-----------------------------------------------------------|
| `accountId`  | Unique ID of the authenticated player account             |
| `roles[]`    | Array of roles like `admin`, `moderator`, `player`        |

> JWTs do not include runtime context like `playerId` or `worldId` — that is resolved post-login and stored in session state.

After a player logs into a specific game world, the session is augmented with the active `playerId`. This allows downstream services to authorize actions based on **character-level ownership**, not just account identity.

---

## 🔑 Login Flow and JWT Propagation

### Dumb Clients, Smart Server

- Telnet and MUD clients are **unaware of authentication tokens**
- They issue a `LOGIN` command over raw TCP or WebSocket after connecting
- The **Game Session Service** processes the login by calling the **Account Service**

### Unified Login Across Clients

While modern Web clients could support more advanced OAuth-style flows, FireMUD uses a **single unified login model**. All clients — whether MUD or WebSocket — issue a plaintext `LOGIN` command, which is processed by the **Game Session Service**. This ensures consistent behavior across platforms.

### Internal JWT Handling

1. **Account Service** verifies credentials and returns a **signed JWT**
2. **Game Session Service** stores the JWT internally (e.g. in Redis and memory)
3. JWT is used on all **gRPC calls to downstream services**, encoding verified access rights

Clients **do not receive or resend the JWT**. It is **bound to the socket session** inside Game Session.

---

## 👮 Role-Based Access Control

### Role Enforcement

The `roles[]` claim in the JWT governs access to privileged features such as:

- Admin dashboards and tools
- Moderation commands (e.g., bans, mutes)
- World and game instance management APIs

### Decentralized Checks

Each service (e.g. Game Session, Admin Service) performs **local authorization**:

- Game Session injects the JWT into internal RPC calls
- Target services decode and check required roles
- Unauthorized requests are rejected at the point of use

> This distributed model avoids any single centralized auth enforcement bottleneck.

---

## 🧠 Session Context in Game Session

### State Binding

When a player logs in:

- The Game Session Service:
  - Associates the returned JWT with the active socket
  - Tracks the selected `playerId` and `worldId` (once chosen)
  - Stores all of this in Redis under the session record

### Command Execution

All commands go through the Game Session, which:

- Uses the stored JWT to check access and identity
- Validates that the selected character belongs to the account
- Includes the JWT in gRPC calls to downstream services

> ⚠️ Backend services **trust Game Session** as the authority on session validity and access rights.

---

## 🔄 Reconnection and Multi-Client Behavior

### Reconnection Requirements

FireMUD supports reconnection of disconnected clients, but **authentication must be repeated by the client**.

- Clients **must reauthenticate** via a `LOGIN` command after reconnecting
- Game Session does **not retain client credentials**, only server-side session state
- TCP Proxy and Gateway handle transport-level reconnection but do **not restore gameplay state** without re-login

> Internal reconnection between backend services (e.g. Gateway → Game Session) may occur transparently — but any reconnection at the client boundary requires reauthentication.

### Multi-Client Login Behavior

- A single **account** can be logged in from **multiple clients**, as long as each login targets a different **game world or character**.
- Each connection is associated with a separate session context in the Game Session Service.
- However, **only one active session is allowed per character**. Logging in again as the **same character** (from another client or location) will:
  - Terminate the previous session
  - Take over control of that character from the new connection
  - Rebind Redis state to the new socket, as with a reconnection

> This enables players to play different characters in parallel across clients, while preventing character duplication or conflict.

---

## ✅ Summary

| Topic                         | Description                                                               |
|------------------------------|---------------------------------------------------------------------------|
| Token Type                   | JWT (account-level, backend-only)                                         |
| Token Issuer                 | Account Service                                                           |
| Claims Used                  | `accountId`, `roles`                                                      |
| Auth Enforcement             | Enforced per service using injected JWT                                   |
| Session Binding              | Game Session manages and stores session + JWT                             |
| Character Binding            | Added to session after player selects world and character                 |
| Reconnect Handling           | Requires client to reauthenticate (e.g., via `LOGIN`)                     |
| Client Awareness             | Clients are unaware of JWTs; login is via plaintext command               |
| Trust Model                  | Backend services trust Game Session for auth context                      |
| Multi-Client Logins          | Multiple worlds/chars allowed; logging in as same char takes over session |

> FireMUD separates client simplicity from backend security — centralizing login but decentralizing role-based access control and session continuity.
