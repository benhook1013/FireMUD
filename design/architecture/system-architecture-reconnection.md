# FireMUD System Architecture: Reconnection Strategy

FireMUD enables seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts — using a **layered reconnection model** and **Redis-backed session state**.

---

## Implemented Status

- **Session takeover and resume** – Game Session now detects existing `{tenantId, gameInstanceId, characterId}` bindings, emits `gamesession.session.takeover` and `gamesession.session.resume` counters, and rebinds Redis tick/command queues when the same character logs back in after a disconnect or another client takes over.
- **Telnet and WebSocket parity** – The TCP Proxy → Gateway → Game Session path now shares the same **authentication and lobby-binding contract**: after any disconnect both transports reconnect, issue `LOGIN`, and then complete lobby selection with `PLAY` before gameplay commands are admitted. Transport mechanics may differ (for example WebSocket grace-state handling versus proxy bridge behavior), but client-visible reconnect outcomes and required gameplay admission steps remain equivalent.
- **Remaining work** – Admin-driven forced session transfers remain planned future steps. FireMUD does not attempt to replay or reconstruct lost gameplay commands after long outages or coordination resets; command queues are volatile coordination buffers and commands may be lost outside the bounded tail-loss envelope.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket router; enforces the close-code taxonomy (for example `1013/backend_unavailable` for sustained outages) and resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers when the edge connection remains healthy |

Each layer handles fault tolerance independently.
**Only client connection loss requires reauthentication.**
Game Session Service restarts are **visible to clients** on the WebSocket path because Spring Cloud Gateway is a WebSocket proxy: when Game Session restarts and drops upstream gameplay WebSockets, Gateway closes the corresponding `/ws/game/**` client WebSockets, and clients reconnect, re-`LOGIN`, and re-`PLAY`. Any in-flight gameplay commands at the moment of the restart may be lost, consistent with the at-most-once edge delivery semantics in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
When Spring Cloud Gateway pods restart, Web clients are disconnected; they must open a new WebSocket and issue `LOGIN` again. Once reconnected, the gateway resumes routing and Game Session uses Redis-backed session state to decide whether to resume or start fresh after the client re-selects gameplay scope with `PLAY`. Telnet clients are also disconnected because the TCP Proxy Service fail-closes Telnet sockets when its WebSocket bridge to Spring Cloud Gateway drops; clients reconnect and re-`LOGIN` and then `PLAY`.
TCP Proxy restarts drop Telnet clients, who must reconnect manually.
If the Gateway link is unavailable (for example during gateway deploys or outages), the TCP Proxy applies the bridge-availability contract from Gateway/Protocol Bridging, closes with `backend_unavailable` when gameplay admission is unavailable, and clients reconnect using backoff, then reauthenticate with `LOGIN` and re-enter gameplay scope via `PLAY`.

---

## Layer Behavior Breakdown

### TCP Proxy Service (Telnet Clients)

- Accepts raw TCP input and assembles it into commands.
- Buffers input **during connection**, but **clears on disconnect**; no gameplay state is preserved across reconnects.
- Emits a `NotifyDisconnect` event to the Game Session Service over an internal-only gRPC event sink as a best-effort, at-least-once hint when Telnet sockets close. Game Session must treat this stream as idempotent and advisory only, never as the sole source of truth for session liveness; it keys consumption by `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore.
- Runtime options such as the listening port and Spring Cloud Gateway WebSocket URL are configured via `TCP_PROXY_PORT` and `GATEWAY_WS_URL` (see the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables)).

For the **concrete `NotifyDisconnect` message shape and transport behaviour** – including retry windows, transport vs application-level failures, and the exact event fields – treat the TCP Proxy Service design’s **Service Interactions** section as canonical. This document is canonical for the **cross-service, behaviour-level contract**; see the **NotifyDisconnect Behavioral Contract (Summary)** section below for how this stream fits into the overall reconnection and liveness model.

### Spring Cloud Gateway (Web Clients)

- Stateless WebSocket router
- Resumes routing once Web clients reconnect their WebSocket connections after a blip or restart
- Holds no gameplay, auth, or session state

> TCP Proxy restarts drop Telnet connections.
> Spring Cloud Gateway restarts temporarily disconnect Web clients; clients must reconnect their WebSocket, re-`LOGIN`, and re-`PLAY`, after which Game Session reloads state from Redis if available.
> Spring Cloud Gateway restarts disconnect Telnet clients as well because the TCP Proxy fail-closes when its Proxy → Gateway WebSocket bridge drops; clients reconnect and re-`LOGIN`/`PLAY`.

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

After `LOGIN` succeeds, clients must re-establish gameplay scope by selecting a world and character via the lobby commands (`WORLDS` / `CHARS` / `PLAY`) as defined in [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay-lobby-selection). This `LOGIN` → `PLAY` sequence is mandatory for both Telnet and WebSocket reconnect flows in this multi-tenant platform; gameplay commands are not admitted before `PLAY` except in explicitly documented dev/test bypass modes. Advanced Telnet tools that use a `SESSION <gameInstanceId> <tenantId>` envelope must resend that envelope on the new TCP connection before `LOGIN` if they want those hints applied, but selection still uses `PLAY` and never bypasses authorization/entitlement checks.

Redis-backed session state enables seamless resumption if valid, or fresh login if expired.
Session entries in Redis expire after a derived `session_expiration_ms` window (`FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`) as documented in [Environment and Secrets](./infrastructure/environment-and-secrets.md#authentication).

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#🔁-login-and-session-flow)

Gameplay command idempotency for reconnects is intentionally simple from the client’s perspective: Telnet and WebSocket clients treat commands as fire-and-forget. They do not attach idempotency keys or effect identifiers to individual commands in the text protocol; idempotency is handled internally by Game Session and domain services as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#gameplay-command-idempotency-client-view) and [Transactions & Idempotency](./system-architecture-transactions.md).

---

## Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#👥-multi-client-behavior-and-session-takeover).

At most one active gameplay binding is supported per `{tenantId, gameInstanceId, characterId}` at any point in time. Legacy `playerId` wording maps one-to-one to `characterId` and is not a separate identity domain. When a new client successfully issues `LOGIN` for a character that is already bound to another connection (whether Telnet or WebSocket), Game Session treats this as a **takeover**:

- The previous connection is disconnected or demoted according to the takeover rules in the authentication design.
- No ordering guarantees are provided between the last few commands on the old connection and the first commands on the new one; only **per-connection FIFO** is maintained as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
- Clients and tools must not assume that keeping multiple concurrent connections (for example Telnet + Web) to the same character will preserve any cross-connection ordering; instead, they should rely on the single active binding and takeover semantics.

---

## Resume vs Reload Scenarios

| Event | Result |
| --- | --- |
| Client disconnect (TCP/WebSocket) | Requires new `LOGIN` and `PLAY`; may resume via Redis |
| TCP Proxy Service restart | Telnet clients disconnected; new `LOGIN` and `PLAY` required |
| Spring Cloud Gateway restart | Web clients disconnected with `1000/logout` (planned drain) or `1011/internal_error` (unplanned crash). Telnet clients are also disconnected because the TCP Proxy fail-closes when its Proxy → Gateway WebSocket bridge drops; clients reconnect and re-`LOGIN`, then re-`PLAY`. |
| Lease move / gameplay shard handoff | Not defined as a distinct edge-visible event in the current edge contract. Any future shard handoff design must define the client-visible signal and reconnection/backoff policy explicitly in the Gateway and Protocol Bridging contracts. |
| Gateway ↔ Game Session link degraded (short window) | WebSocket connections may stay open when the upstream hop remains established and Game Session is reachable but returning explicit, per-command errors; clients do not reconnect solely due to transient command failures. If the upstream gameplay WebSocket closes, clients reconnect and re-`LOGIN`, then re-`PLAY`. |
| Gateway ↔ Game Session link degraded (`unreachable` sustained) | Gameplay becomes impossible; WebSocket sessions are closed with `1013` (`backend_unavailable`) and clients should reconnect with backoff as described below. Telnet clients are closed by the TCP Proxy with `backend_unavailable` when the gateway closes the upstream gameplay WebSocket or when the proxy cannot establish or maintain its bridge; clients reconnect and re-`LOGIN`, then re-`PLAY`. |
| Game Session Service restart | Visible: Gateway closes gameplay WebSocket clients and they reconnect and re-`LOGIN` (subject to at-most-once loss of in-flight commands), then re-`PLAY`. Telnet clients are disconnected because the upstream gameplay WebSocket closes; the proxy closes the Telnet socket and clients reconnect and re-`LOGIN`, then re-`PLAY`. |
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

## Client Reconnection Behaviour

FireMUD treats reconnection as a **client responsibility**: after any disconnect, clients open a fresh transport (TCP or WebSocket), issue a new `LOGIN`, and complete `PLAY` before gameplay commands. To avoid thundering herds and to keep reconnect storms predictable during incidents, automated or first‑party clients should follow a consistent reconnection policy:

- **Backoff and jitter**
  - Start with an initial delay of `1–2s` after the first failed reconnect attempt.
  - Use exponential backoff (for example, doubling the delay on each subsequent failure) up to a maximum backoff of `30–60s`.
  - Apply jitter of ±25% to each delay to avoid synchronized reconnect bursts from many clients.
- **Retry caps**
  - Cap reconnect attempts to a reasonable rate per client (for example, no more than ~6 attempts in the first minute and ~60 attempts per hour).
  - If the last close reason clearly indicates `policy_violation` or a similar non‑retriable condition (see [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts) for close codes), clients should either stop reconnecting or switch to a much longer backoff window and surface the error to the user.
- **Scope of reconnection**
  - Telnet clients reconnect by establishing a new TCP connection to the TCP Proxy Service, issuing `LOGIN` (and any optional `SESSION`/MCP negotiation), and then issuing `PLAY` before gameplay commands.
  - Web clients reconnect by opening a new WebSocket to `/ws/game/**` via Spring Cloud Gateway, issuing `LOGIN`, and then issuing `PLAY`; they must not assume that any prior MCP or `SESSION` state has survived, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery).

### HTTP Handshake Failures on `/ws/game/**`

When Web clients attempt to establish or re-establish WebSocket connections and receive HTTP errors instead of a successful upgrade, they must interpret those signals consistently with the close-code taxonomy:

This table is the canonical client-policy matrix for `/ws/game/**` handshake failures; gateway and client documentation must reference this mapping rather than redefining retry semantics independently.

| HTTP status | Meaning on `/ws/game/**` | Client policy |
| --- | --- | --- |
| `429` | Edge rate or connection policy boundary reached | Treat as `policy_violation`; do not fast-loop. Stop auto-retry or switch to long backoff (minutes). |
| `503` | Gateway currently considers gameplay backend unavailable | Treat as `1013/backend_unavailable`; use exponential backoff with jitter and retry caps. |
| `403` | Handshake denied by gateway policy/trust boundary (for example internal-only listener, mTLS identity, security policy mismatch, or invalid/expired connect token when token enforcement is enabled) | Treat as non-retriable until configuration/permissions change; surface as actionable operator/user error. |
| `426` | Protocol upgrade requirement not met | Retry only after client transport/protocol correction (for example proper WebSocket upgrade/TLS endpoint). |
| Other `5xx` | Unexpected gateway/infra failure | Treat as `internal_error`; use exponential backoff with jitter. |

For `/ws/game/**`, HTTP `401` is not part of the normal handshake taxonomy because gameplay authentication occurs after WebSocket establishment via `LOGIN`/`PLAY`. If `401` appears in practice, treat it as a misconfiguration signal and investigate gateway policy drift.

First-party clients and tools should implement a unified “edge error → backoff policy” table that maps both WebSocket close codes and HTTP handshake errors on `/ws/game/**` to concrete backoff behaviour so reconnect storms remain predictable during incidents.

Clients that do not implement these backoff rules will still function, but first‑party tools and reference clients should treat this behaviour as the normative baseline so that production incidents do not amplify reconnect load.

## Failure Modes & Reconciliation Rules

`NotifyDisconnect` events and edge behaviour around disconnects are intentionally **advisory**, with Redis and gameplay activity as the source of truth:

- **Authoritative liveness** – Game Session treats Redis session entries, tick activity, and its own heartbeats as authoritative for session liveness. `NotifyDisconnect` is a best-effort, at-least-once hint from the TCP Proxy Service, not the sole indicator of whether a client is still connected.
- **Idempotent disconnect handling** – Game Session keys disconnect handling by `{proxyConnectionId, disconnectSequence}` and treats duplicate events for the same pair as no-ops. Late events that arrive after a new socket has been bound or after Redis has expired the session are also safe to ignore; they must not forcefully tear down a new, healthy binding.
- **Missing hints** – Absence of a `NotifyDisconnect` event is never interpreted as a guarantee that the client is still connected. Game Session relies on its own timeouts and region/tick-level activity to clean up stale sessions when disconnect hints are missing (for example due to gRPC transport failures).
- **Edge ordering vs domain idempotency** – The TCP Proxy → Gateway → Game Session path provides per-connection FIFO where delivered and at-most-once delivery, as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Game Session and downstream domain services implement durable idempotency guards (for example effect IDs and transaction rows) so retries and replays within the tick system remain safe even when edge hints are late or duplicated. See [gRPC API Style & Versioning](./system-architecture-grpc.md) and [Transactions & Idempotency](./system-architecture-transactions.md) for the underlying RPC and effect semantics.

### Backend-Unavailable Scenarios

Some failures leave edge connections technically alive while core gameplay services are degraded. To keep behaviour predictable:

- **`degraded_but_reachable` state**
  - When Game Session is reachable but intermittently failing command handling (for example transient 5xx from gameplay operations while upstream WebSocket remains established), Spring Cloud Gateway keeps existing sessions open and clients receive explicit per-command failures.
  - Telnet clients follow an equivalent outcome through the proxy bridge: while the bridge remains healthy, connections stay open and command failures remain explicit backend responses.
- **`unreachable` state**
  - When Gateway cannot establish or maintain the upstream gameplay WebSocket, it enters `unreachable` and starts the `firemud.gateway.backendUnavailableGraceMs` timer.
  - During this state, new `/ws/game/**` handshakes are rejected with HTTP `503`. If an established session attempts to send gameplay traffic while upstream is unreachable, Gateway immediately closes with `1013/backend_unavailable` rather than buffering or silently dropping commands.
- **Sustained backend unavailability**
  - When `unreachable` remains continuous beyond `firemud.gateway.backendUnavailableGraceMs`, Spring Cloud Gateway closes affected gameplay WebSocket sessions with `1013/backend_unavailable`, signalling clients to apply reconnection/backoff rules.
  - Returning from `unreachable` to normal routing requires hysteresis (consecutive successful upstream connects/forwards) as defined in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window), so one brief success does not flap clients.
  - The TCP Proxy Service enforces equivalent admission outcomes with a bridge-availability circuit breaker: while the breaker is open (continuous gateway gameplay unreachability), new Telnet sockets are rejected quickly with `backend_unavailable` and existing affected sockets are closed with `backend_unavailable` rather than being held in ambiguous half-open states. Recovery from half-open to closed requires `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful bridge probes (default `3`) so proxy admission hysteresis stays aligned with gateway recovery criteria.
  - Operators should treat elevated counts of `1013` (`backend_unavailable`) and proxy‑side “backend unavailable” disconnects as indicators of core gameplay outages rather than client misuse, and use the metrics referenced in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients) and the Telnet degraded runbook to distinguish these from slow‑client backpressure events.

### NotifyDisconnect Behavioral Contract (Summary)

The TCP Proxy Service emits `NotifyDisconnect` events to the Game Session Service over an internal-only gRPC stream whenever Telnet sockets close. Behaviourally, this stream follows a simple, canonical contract:

- **At-least-once, advisory delivery** – Transport for `NotifyDisconnect` is intentionally at-least-once: events may be delivered more than once or arrive late relative to the underlying TCP close. Game Session treats this stream as a best-effort hint about Telnet liveness, not as the source of truth; Redis session state and gameplay activity remain authoritative.
- **Idempotency key** – Every event carries an idempotency key derived from `{proxyConnectionId, disconnectSequence}`. Game Session persists the latest processed `disconnectSequence` per `proxyConnectionId` and must treat older or duplicate events for the same pair as no-ops so proxy-side retries remain simple and safe.
- **Idempotency record retention** – Game Session retains the latest processed sequence record per `proxyConnectionId` for at least `session_expiration_ms + TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` so delayed retries and reconnect races remain deduplicated. These records then expire via TTL-based cleanup.
- **Capacity and eviction guardrails** – Consumer implementations must enforce a bounded maximum cardinality for active dedupe records (for example per tenant and per process shard). If the cap is hit during disconnect floods, eviction policy must be deterministic (`oldest-expiry-first` or equivalent) and must emit explicit overload metrics/logs (for example `gamesession.notifydisconnect.dedupe.capacity_reached`) so operators can distinguish flood pressure from logic bugs.
- **Retry window** – The proxy retries failed `NotifyDisconnect` calls for a short, bounded window after Telnet socket close (see the TCP Proxy Service design’s **Service Interactions** section and the `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` configuration for exact timing). After this window, the proxy stops retrying and relies on Game Session’s own liveness detection and Redis timeouts to reconcile any missing hints.
- **Complement to edge delivery guarantees** – `NotifyDisconnect` complements, but does not change, the at-most-once edge delivery model for gameplay commands described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Late or duplicate disconnect hints must never cause healthy, newly bound sessions to be torn down; consumers always key decisions off the idempotency key and current Redis state.

This section is the canonical behaviour-level summary for `NotifyDisconnect`. The TCP Proxy Service design remains authoritative for message fields, configuration knobs, and implementation details, while [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics) defines the general pattern for similar at-least-once event sinks elsewhere in the system.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
