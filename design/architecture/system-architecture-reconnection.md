# 🔁 FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a **layered reconnection model** and **Redis-backed session state**.

---

## 🧩 Reconnection Layers

| Layer              | Responsibility                                                               |
|-------------------|-------------------------------------------------------------------------------|
| **TCP Proxy Service**      | Parses Telnet input; clears input buffer on disconnect                        |
| **Spring Cloud Gateway** | Stateless WebSocket passthrough; re-establishes backend connection automatically |
| **Game Session Service**   | Restores session from Redis; rebinds socket, tick region, and timers          |

Each layer handles fault tolerance independently.
**Only client connection loss requires reauthentication.**
Game Session Service restarts are **transparent** if the client remains connected. The Gateway automatically re-establishes WebSocket sessions after a restart while Telnet clients stay bridged through the proxy. TCP Proxy restarts drop Telnet clients.

---

## 🛰️ Layer Behavior Breakdown

### TCP Proxy Service (Telnet Clients)

- Accepts raw TCP input and assembles it into commands
- Buffers input **during connection**, but **clears on disconnect**
- No gameplay state is preserved across reconnects — Game Session Service handles recovery
- Runtime options such as the listening port and gateway WebSocket URL are configured via `TCP_PROXY_PORT` and `GATEWAY_WS_URL` (see the [TCP Proxy Service README](./microservices/tcp-proxy-service/README.md#environment-variables)).

### Spring Cloud Gateway (Web Clients)

- Stateless WebSocket router
- Automatically re-establishes backend connections if restarted
- Holds no gameplay, auth, or session state

> TCP Proxy restarts drop Telnet connections. Spring Cloud Gateway restarts temporarily disconnect Web clients, but the WebSocket connection is reestablished automatically. Telnet clients proxied through the Gateway remain connected.

### Game Session Service

- Uses Redis to store and recover session state, including command queues, tick participation, cooldowns, and retry info
- On reconnect, rebinds:
  - Socket connection
  - Tick region context
  - Timers and in-flight actions

> 🔗 Full structure of Redis session keys is documented in [Session Keys and Gameplay Binding](./system-architecture-redis.md#🧠-session-keys-and-gameplay-binding).
> See also the [Game Session Service README](./microservices/game-session-service/README.md#redis-keys) for how session state is stored for reconnect recovery.

---

## 🔐 When Reauthentication Is Required

Clients must send a `LOGIN` command **after any disconnect**, such as:

- TCP loss (Telnet clients)
- WebSocket loss (Web clients)
- If two-factor authentication is enabled, include the one-time `otp` value with the `LOGIN` command. See [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication).

Redis-backed session state enables seamless resumption if valid, or fresh login if expired.
Session entries in Redis expire after `FIREMUD_AUTH_SESSION_EXPIRATION_MS` milliseconds (defaults to `3600000`, or **1 hour**) as documented in [Environment and Secrets](./infrastructure/environment-and-secrets.md#authentication).

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#🔁-login-and-session-flow)

---

## 👥 Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#👥-multi-client-behavior-and-session-takeover).

---

## 🔄 Resume vs Reload Scenarios

| Event                                           | Result                                         |
|------------------------------------------------|------------------------------------------------|
| Client disconnect (TCP/WebSocket)              | Requires new `LOGIN`; may resume via Redis     |
| TCP Proxy Service restart                              | Telnet clients disconnected; new `LOGIN` required       |
| Spring Cloud Gateway restart                           | Web clients disconnected; Telnet clients stay connected |
| Game Session Service restart                          | Transparent if client remains connected         |
| Manual re-`LOGIN` from same character          | Treated as reconnect; resumes if Redis intact  |
| Redis session expired/missing                  | Treated as fresh login; gameplay starts anew   |
| New client logs in as same character           | Old session terminated; new one resumes control |

> 🔑 Only **client disconnection** requires `LOGIN`. Game Session Service restarts are invisible if the socket stays open. TCP Proxy restarts drop Telnet clients, while Gateway restarts disconnect Web clients; Telnet clients proxied through the Gateway remain connected.

---

## 🧠 Design Principles

- Redis stores:
  - Socket bindings and session metadata
  - Queued commands and tick state
  - Timers, cooldowns, and retry info
- Game Session Service governs all reconnection, deduplication, and rebinding
- Clients are **fully stateless**
- Transparent failover is supported across infrastructure layers

---

## 📚 Related Documentation

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service README](./microservices/game-session-service/README.md)
