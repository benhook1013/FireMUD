# FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a **layered reconnection model** and **Redis-backed session state**.

---

## Implemented Status

- **Session takeover and resume** – Game Session now detects existing `accountId`/`playerId` links, emits `gamesession.session.takeover` and `gamesession.session.resume` counters, and rebinds Redis tick/command queues when the same character logs back in after a disconnect or another client takes over.
- **Telnet/WebSocket parity** – The TCP Proxy → Gateway → Game Session path now shares the same login/resume flow so Telnet SESSION envelopes and WebSocket clients follow identical reconnection behavior.
- **Remaining work** – Cross-region handoff, **Redis-backed command queue replay** after long outages (not proxy-side buffering), and admin-driven forced session transfers remain planned future steps.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket passthrough; resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers |

Each layer handles fault tolerance independently.
**Only client connection loss requires reauthentication.**
Game Session Service restarts are **transparent** if the client remains connected.
When Spring Cloud Gateway or Game Session pods restart, Web clients **reconnect their WebSocket connections**; once reconnected, the gateway resumes routing and Game Session uses Redis-backed session state to decide whether to resume or start fresh. Telnet clients typically remain connected to the TCP Proxy Service during a Gateway restart, but gameplay traffic may pause while the proxy re-establishes its WebSocket bridge to Spring Cloud Gateway (input buffering is limited and is governed by the proxy’s per-connection ceilings). See [Protocol Bridging](./system-architecture-protocol-bridging.md) for how TCP and WebSocket clients share the same backend.
TCP Proxy restarts drop Telnet clients, who must reconnect manually.
If the Gateway link remains unavailable beyond the TCP Proxy Service’s short reconnect window (see `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` in the TCP Proxy design), the proxy fail-closes Telnet sockets with a clear message rather than buffering unbounded input at the DMZ edge; clients then reconnect and reauthenticate with `LOGIN`.

---

## Layer Behavior Breakdown

### TCP Proxy Service (Telnet Clients)

- Accepts raw TCP input and assembles it into commands.
- Buffers input **during connection**, but **clears on disconnect**; no gameplay state is preserved across reconnects.
- Emits a `NotifyDisconnect` event to the Game Session Service over an internal-only gRPC event sink as a best-effort, at-least-once hint when Telnet sockets close. Game Session must treat this stream as idempotent and advisory only, never as the sole source of truth for session liveness; it keys consumption by `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore.
- Runtime options such as the listening port and Spring Cloud Gateway WebSocket URL are configured via `TCP_PROXY_PORT` and `GATEWAY_WS_URL` (see the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables)). The short reconnect/buffering window referenced earlier in this document is governed by `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` and `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` as described in the TCP Proxy design’s **Data Flow** section.

For the **canonical `NotifyDisconnect` contract** – including retry windows, transport vs application-level failures, and the exact event fields – treat the TCP Proxy Service design’s **Service Interactions** section as the single source of truth. This document intentionally summarizes only the layering behaviour so it stays aligned as the proxy implementation evolves.

### Spring Cloud Gateway (Web Clients)

- Stateless WebSocket router
- Resumes routing once Web clients reconnect their WebSocket connections after a blip or restart
- Holds no gameplay, auth, or session state

> TCP Proxy restarts drop Telnet connections.
> Spring Cloud Gateway restarts temporarily disconnect Web clients; clients must reconnect their WebSocket and re-`LOGIN`, after which Game Session reloads state from Redis if available.
> Telnet clients may remain connected to the TCP Proxy Service during brief Gateway blips, but if the proxy cannot re-establish its WebSocket bridge within its short reconnect window it closes the Telnet socket and the client reconnects.

### Game Session Service

- Uses Redis to store session state such as command queues, tick participation, cooldowns, and retry info. Reconnect logic restores these details.
- On reconnect, rebinds:
  - Socket connection
  - Tick region context
  - Timers and in-flight actions

> 🔗 Full structure of Redis session keys is documented in [Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding).
> See also the [Game Session Service design](./microservices/game-session-service/README.md#redis-keys) for how session state is stored for reconnect recovery.

---

## When Reauthentication Is Required

Clients must send a `LOGIN` command **after any disconnect**, such as:

- TCP loss (Telnet clients)
- WebSocket loss (Web clients)
- If two-factor authentication is enabled, include the one-time `otp` value with the `LOGIN` command. See [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication).

Redis-backed session state enables seamless resumption if valid, or fresh login if expired.
Session entries in Redis expire after a derived `session_expiration_ms` window (`FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`) as documented in [Environment and Secrets](./infrastructure/environment-and-secrets.md#authentication).

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#🔁-login-and-session-flow)

---

## Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#👥-multi-client-behavior-and-session-takeover).

---

## Resume vs Reload Scenarios

| Event | Result |
| --- | --- |
| Client disconnect (TCP/WebSocket) | Requires new `LOGIN`; may resume via Redis |
| TCP Proxy Service restart | Telnet clients disconnected; new `LOGIN` required |
| Spring Cloud Gateway restart | Web clients disconnected; Telnet clients may stay connected to the TCP Proxy Service for brief restarts (gameplay may pause while the proxy reconnects its WebSocket bridge; prolonged outages close Telnet sockets) |
| Game Session Service restart | Transparent if client remains connected |
| Manual re-`LOGIN` from same character | Treated as reconnect; resumes if Redis intact |
| Redis session expired/missing | Treated as fresh login; gameplay starts anew |
| New client logs in as same character | Old session terminated; new one resumes control |

---

## Design Principles

- Redis stores:
  - Socket bindings and session metadata
  - Queued commands and tick state
  - Timers, cooldowns, and retry info
- Game Session Service governs all reconnection, deduplication, and rebinding
- Clients are **fully stateless**
- Transparent failover is supported across infrastructure layers

## Failure Modes & Reconciliation Rules

`NotifyDisconnect` events and edge behaviour around disconnects are intentionally **advisory**, with Redis and gameplay activity as the source of truth:

- **Authoritative liveness** – Game Session treats Redis session entries, tick activity, and its own heartbeats as authoritative for session liveness. `NotifyDisconnect` is a best-effort, at-least-once hint from the TCP Proxy Service, not the sole indicator of whether a client is still connected.
- **Idempotent disconnect handling** – Game Session keys disconnect handling by `{proxyConnectionId, disconnectSequence}` and treats duplicate events for the same pair as no-ops. Late events that arrive after a new socket has been bound or after Redis has expired the session are also safe to ignore; they must not forcefully tear down a new, healthy binding.
- **Missing hints** – Absence of a `NotifyDisconnect` event is never interpreted as a guarantee that the client is still connected. Game Session relies on its own timeouts and region/tick-level activity to clean up stale sessions when disconnect hints are missing (for example due to gRPC transport failures).
- **Edge ordering vs domain idempotency** – The TCP Proxy → Gateway → Game Session path provides per-connection FIFO where delivered and at-most-once delivery, as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Game Session and downstream domain services implement durable idempotency guards (for example effect IDs and transaction rows) so retries and replays within the tick system remain safe even when edge hints are late or duplicated. See [gRPC API Style & Versioning](./system-architecture-grpc.md) and [Transactions & Idempotency](./system-architecture-transactions.md) for the underlying RPC and effect semantics.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
