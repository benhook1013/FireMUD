# 🔁 FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a **layered reconnection model** and **Redis-backed session state**.

---

## 🧩 Reconnection Layers

| Layer              | Responsibility                                                               |
|-------------------|-------------------------------------------------------------------------------|
| **TCP Proxy**      | Parses Telnet input; clears input buffer on disconnect                        |
| **Spring Gateway** | Stateless WebSocket passthrough; re-establishes backend connection automatically |
| **Game Session**   | Restores session from Redis; rebinds socket, tick region, and timers          |

Each layer handles fault tolerance independently.  
**Only client connection loss requires reauthentication.**  
Infra restarts (Proxy, Gateway, Session) are **transparent** if the client remains connected.

---

## 🛰️ Layer Behavior Breakdown

### **TCP Proxy (Telnet Clients)**

- Accepts raw TCP input and assembles it into commands
- Buffers input **during connection**, but **clears on disconnect**
- No gameplay state is preserved across reconnects — Game Session handles recovery

### **Spring Cloud Gateway (Web Clients)**

- Stateless WebSocket router
- Automatically re-establishes backend connections if restarted
- Holds no gameplay, auth, or session state

> Proxy and Gateway restarts do not interrupt gameplay as long as the client’s physical connection is maintained.

### **Game Session Service**

- Owns Redis session data: `session:{playerId}`, tick region, queued actions, timers
- On reconnect, rebinds:
  - Socket connection
  - Tick region participation
  - Cooldowns, retry state, and in-flight commands
- Deduplicates concurrent reconnect attempts using Redis locks

---

## 🔐 When Reauthentication Is Required

Clients must send a `LOGIN` command **only after losing connection**:

- **Telnet**: TCP connection to Proxy is lost
- **Web**: WebSocket connection to Gateway drops

After reconnecting and logging in:

- **Game Session** checks Redis for an existing session (by `accountId + playerId`)
- If found: session resumes automatically
- If missing or expired: a new session is started

> Clients are **stateless** — they do not see, store, or reuse tokens.

---

## 👥 Multi-Client and Session Takeover

- An account may be logged in from **multiple clients** simultaneously, using different characters
- Logging into the **same character** from another client:
  - Terminates the old session
  - Transfers control to the new one
  - Rebinds session and gameplay context in Redis

This mirrors reconnection — gameplay resumes with no loss if Redis state is valid.

---

## 🔄 Resume vs Reload Scenarios

| Event                                           | Result                                         |
|------------------------------------------------|------------------------------------------------|
| Client disconnect (TCP/WebSocket)              | Requires new `LOGIN`; may resume via Redis     |
| Proxy/Gateway/Game Session restart             | Transparent — if client remains connected      |
| Manual re-`LOGIN` from same character          | Treated as reconnect; resumes if Redis intact  |
| Redis session expired/missing                  | Treated as fresh login; gameplay starts anew   |
| New client logs in as same character           | Old session terminated; new one resumes control |

> 🔑 Only **client disconnection** requires `LOGIN`. Backend service restarts are invisible unless the physical connection is lost.

---

## 🧠 Design Principles

- Redis stores:
  - Socket bindings and session metadata
  - Queued commands and tick state
  - Timers, cooldowns, and retry info
- Game Session governs all reconnection, deduplication, and rebinding
- Clients are **fully stateless**
- Transparent failover is supported across infrastructure layers

---

📚 Related:

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
