# 🔐 FireMUD System Architecture: Authentication & Authorization

This document details the authentication and authorization mechanisms in FireMUD. It explains how tokens are issued and validated, how roles govern access, and how gameplay sessions handle secure context propagation and reconnection across services.

---

## 🧾 Token Issuance and Format

FireMUD uses **JWTs (JSON Web Tokens)** as *internal authentication tokens* to represent authenticated **accounts** and, once selected, their **active character and world**. These tokens are used **solely within the backend system** — clients never see or transmit them.

### 🏷️ Claims in JWT

JWTs are issued by the **Account Service** and signed for internal use only.

#### 🔹 Initial Claims (after LOGIN)

| Claim        | Description                                               |
|--------------|-----------------------------------------------------------|
| `accountId`  | Unique ID of the authenticated player account             |
| `roles[]`    | Array of roles like `admin`, `moderator`, `player`        |

#### 🔸 Augmented Claims (after character and world selection)

Once the player selects a character and enters a game world, the session is updated and a new JWT is issued with additional claims:

| Claim        | Description                                               |
|--------------|-----------------------------------------------------------|
| `playerId`   | ID of the selected character, bound to the current session|
| `worldId`    | ID of the selected world the player has entered           |

This updated JWT is used for all gameplay commands and validated by downstream services to ensure **character-level and world-specific access control**.

> Clients never transmit or see this JWT. It is entirely internal and used only for service-to-service gRPC communication.

---

## 🔑 Login Flow and JWT Propagation

### Dumb Clients, Smart Server

- Telnet and MUD clients are **unaware of authentication tokens**
- They issue a `LOGIN` command over raw TCP or WebSocket after connecting
- The **Game Session Service** processes the login by calling the **Account Service**

### Unified Login Across Clients

All clients — whether Web or Telnet — use a unified plaintext `LOGIN` command. This provides consistency across platforms while centralizing access control logic in Game Session.

### Internal JWT Handling

1. **Account Service** verifies credentials and issues an initial **account-only JWT**
2. **Game Session Service** stores the JWT internally and binds it to the session
3. Once the player selects a character and world, Game Session updates the session and **injects `playerId` and `worldId` into a new JWT**
4. All gRPC calls use the **latest JWT**, including `accountId`, `roles`, `playerId`, and `worldId`

> Clients always send `LOGIN` again after a disconnect. If the same account and character are reused, Game Session may resume the previous session from Redis automatically.

---

## 👮 Role-Based Access Control

### Role Enforcement

The `roles[]` claim governs access to privileged features such as:

- Admin dashboards and tools
- Moderation commands
- World and game management APIs

### Decentralized Checks

Each service performs **local authorization**:

- Game Session injects the current JWT into all internal gRPC calls
- Services decode and validate claims (`accountId`, `roles`, `playerId`, `worldId`)
- Invalid or unauthorized requests are rejected locally

---

## 🧠 Session Context in Game Session

### Session Binding and Character Context

- When a player logs in, Game Session binds the JWT to the socket and session
- After character/world selection, the session is upgraded to include:
  - `playerId`
  - `worldId`
- A new JWT including `playerId` and `worldId` is created and used for subsequent internal calls

### Command Execution Context

All commands go through Game Session, which:

- Validates account and character ownership
- Uses the updated JWT to enforce identity and authorization
- Includes it in gRPC calls to other services

> ⚠️ Backend services trust Game Session to issue and forward valid JWTs.  
> 🧠 JWTs are never client-visible, and clients do not store or reuse them — reconnection always requires a fresh `LOGIN`.

---

## 🔄 Reconnection and Multi-Client Behavior

### Reconnection

- Clients must **re-authenticate** after a disconnect using `LOGIN`
- Game Session determines whether to **resume an existing session** using Redis or create a fresh one
- No client-side token storage or reuse is allowed

### Multi-Client Support

- Accounts can be logged in from **multiple clients** at once
- Each session must target a **different character or world**
- Logging into the **same character again**:
  - Terminates the old session
  - Transfers control to the new connection
  - Updates the socket binding in Redis

---

## ✅ Summary

| Topic                         | Description                                                               |
|------------------------------|---------------------------------------------------------------------------|
| Token Type                   | JWT (backend-only, internal use)                                          |
| Token Issuer                 | Account Service                                                           |
| Initial Claims               | `accountId`, `roles[]`                                                    |
| Post-Login Claims            | `accountId`, `roles[]`, `playerId`, `worldId`                             |
| Token Usage                  | Injected into gRPC calls; never seen by client                            |
| Session Storage              | Redis (bound to socket + updated after character selection)               |
| Auth Enforcement             | Local per-service, based on injected JWT claims                           |
| Reconnect Behavior           | Requires fresh `LOGIN`; Game Session may resume session via Redis         |
| Multi-Client Sessions        | Allowed per-account, limited to one session per character                 |

> FireMUD separates authentication (account-level) from gameplay execution (character-level), using staged JWT augmentation to securely propagate player identity and access context across microservices.
