# 🔁 FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a layered reconnection strategy and Redis-backed session state.

---

## 🧩 Reconnection Layers

| Layer              | Responsibility                                               |
|-------------------|---------------------------------------------------------------|
| **TCP Proxy**      | Parses Telnet input; clears buffer on disconnect              |
| **Spring Gateway** | Stateless WebSocket router; auto-reconnects downstream        |
| **Game Session**   | Restores gameplay session from Redis; manages resume behavior |

Each layer handles fault tolerance independently. **Only client connection loss requires reauthentication**.  
Internal service restarts (Proxy, Gateway, Game Session) are transparent.

---

## 🔐 When Clients Must Reauthenticate

A `LOGIN` command is required **only** when the client itself disconnects:

- **Telnet clients**: if TCP connection to the Proxy is lost
- **Web clients**: if WebSocket connection to the Gateway drops

In these cases:

- The client re-establishes a connection
- Issues a `LOGIN` command
- Game Session uses Redis to detect if a prior session exists (same account + character)  
  → If so, gameplay can **resume automatically**

> Clients **never see or store tokens** — session restoration is entirely server-driven.

---

## 🎮 Game Session Recovery Logic

On valid `LOGIN`, Game Session:

- Looks for Redis session data (`session:{playerId}`)
- If found:
  - Rebinds the socket
  - Restores tick region participation
  - Recovers queued actions, timers, and cooldowns
- Deduplicates concurrent reconnects using Redis locks

If Redis data is unavailable or expired, the login is treated as a **fresh session**.

---

## 🛰️ TCP Proxy and Gateway Behavior

**TCP Proxy (Telnet clients):**

- Parses raw TCP input into commands
- Clears input buffer on disconnect
- No state is retained across reconnects

**Spring Cloud Gateway (Web clients):**

- Stateless WebSocket passthrough
- Automatically re-establishes backend connections
- Transparent to gameplay — no state loss if the client remains connected

> Infrastructure restarts do **not** require client re-login if the connection remains alive.

---

## 👥 Multi-Client Sessions & Takeover

- An account may be logged in from multiple clients using **different characters**
- Logging in to the *same character* from another client:
  - Terminates the old session
  - Transfers control to the new client
  - Rebinds Redis session state

This behaves identically to a reconnect — gameplay continues with no loss.

---

## 🔄 Resume vs Reload Scenarios

| Trigger                                  | Result                                  |
|------------------------------------------|------------------------------------------|
| TCP/WebSocket disconnect (client-side)   | Requires new connection + `LOGIN`; may resume |
| Proxy / Gateway restart (infra only)     | Transparent — no client action needed   |
| Game Session restart                     | Transparent if Redis state is available |
| Manual `LOGIN` from same character       | Treated as reconnect; session resumes if possible |
| Redis state expired/unavailable          | Full reload — fresh session required     |
| New client takes over same character     | Old session terminated, new one resumes control |

> 🔑 Only disconnection of the **client’s connection** triggers a new `LOGIN`.  
> Service restarts **do not** interrupt gameplay unless the connection itself is dropped.

---

## 🧠 Design Principles

- Redis stores all gameplay session data: timers, queues, socket bindings, locks
- Clients are stateless: no tokens or session memory
- Game Session governs all reconnection and session rebinding logic
- Transparent failover is supported at the infrastructure level (Proxy, Gateway, Game Session)

---

📚 Related:

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
