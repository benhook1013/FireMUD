# 🔁 FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a **layered reconnection model** and **Redis-backed session state**.

---

## 🧩 Reconnection Layers

| Layer              | Responsibility                                                               |
|-------------------|-------------------------------------------------------------------------------|
| **TCP Proxy**      | Parses Telnet input; clears input buffer on disconnect                        |
| **Spring Cloud Gateway** | Stateless WebSocket passthrough; re-establishes backend connection automatically |
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

- Uses Redis to store and recover session state, including command queues, tick participation, cooldowns, and retry info  
- On reconnect, rebinds:
  - Socket connection
  - Tick region context
  - Timers and in-flight actions

> 🔗 Full structure of Redis session keys is documented in [Session Keys and Gameplay Binding](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding)

---

## 🔐 When Reauthentication Is Required

Clients must send a `LOGIN` command **after any disconnect**, such as:

- TCP loss (Telnet clients)
- WebSocket loss (Web clients)

Redis-backed session state enables seamless resumption if valid, or fresh login if expired.

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#🔁-login-flow-and-reauthentication)

---

## 👥 Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#👥-multi-client-and-session-takeover).

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
