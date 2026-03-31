# FireMUD System Architecture: Reconnection Strategy

FireMUD targets seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts using a **layered reconnection model** and **Redis-backed session state**. The canonical recovery contract remains explicit and protocol-visible at the edge so third-party MUD clients can recover reliably, while first-party clients may automate that same recovery path to reduce user-visible friction. At the same time, FireMUD should prefer **non-edge restart invisibility** wherever shared state and durable coordination make that feasible: restarts of Game Session, Game Logic, or other non-edge gameplay services should ideally cause at most a short stall rather than a forced user-visible re-login/re-`PLAY` cycle.

---

## Implemented Status

- **Session takeover and resume** – Game Session emits `gamesession.session.takeover` and `gamesession.session.resume` counters and rebinds Redis tick/command queues on reconnect/takeover. The active uniqueness key is `{tenantId, gameInstanceId, characterId}`.
- **Telnet and WebSocket parity** – Both transports share the same gameplay authentication and lobby-binding contract (`LOGIN` then `PLAY`) after reconnect. First-party WebSocket clients must obtain a fresh connect token before opening `/ws/game/**`.
- **Runtime authority checks** – `PLAY` now performs a first-pass runtime membership and tenant-entitlement check before fresh entry or resume/takeover. This closes the earlier gap where reconnect semantics relied only on Redis gameplay identity plus a fresh `LOGIN`.
- **Remaining work** – Admin-driven forced session transfers remain planned future steps. FireMUD does not attempt to replay or reconstruct lost gameplay commands after long outages or coordination resets; command queues are volatile coordination buffers and commands may be lost outside the bounded tail-loss envelope. The design still needs stronger durable coordination so non-edge restarts become operationally invisible in the common case instead of visibly dropping clients. The broader admission target still needs follow-up work for admission pointers, public-production first-join membership creation, and any future prompt/output scheduling refinements beyond the current screen-buffer-plus-fresh-redraw contract.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket router; enforces the close-code taxonomy (for example `1013/backend_unavailable` for sustained outages) and resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers when the edge connection remains healthy |

Each layer handles fault tolerance independently.
Reauthentication is required when a client disconnects, or when server-side auth state expires or is revoked.
Edge disconnects remain the canonical explicit recovery boundary. Non-edge service restarts should ideally be absorbed behind shared Redis-backed gameplay state and durable coordination so they do not force a visible reconnect. Where the current implementation still makes Game Session or other non-edge restarts visible on the WebSocket or Telnet path, treat that as an implementation gap rather than target behavior. Any in-flight gameplay commands at the moment of a hard failure may still be lost, consistent with the at-most-once edge delivery semantics in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
Spring Cloud Gateway should fail over cleanly at the fleet level: unaffected sockets on other healthy Gateway instances remain up, and new gameplay handshakes should continue against healthy instances. Clients whose live WebSocket or Telnet bridge was terminated on the specific Gateway process that restarted or crashed still see a visible retryable edge failure because that edge-owned socket was lost. Those affected Web clients must request a fresh connect token, open a new WebSocket, and issue `LOGIN` again. Once reconnected, the gateway resumes routing and Game Session uses Redis-backed session state to decide whether to resume or start fresh after the client re-selects gameplay scope with `PLAY`. Restart classification remains canonical: planned Gateway drain is surfaced by the TCP Proxy as `logout` with `gateway_restart` context when the bridge-drain signal is delivered, while abrupt or unattributed loss of the specific Gateway bridge/socket serving a client is surfaced immediately as `backend_unavailable` on Telnet and `internal_error` retry class when WebSocket close metadata is missing.
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
> Spring Cloud Gateway restarts temporarily disconnect Web clients; clients must request a fresh connect token, reconnect their WebSocket, re-`LOGIN`, and re-`PLAY`, after which Game Session reloads state from Redis if available.
> Spring Cloud Gateway restarts disconnect Telnet clients under the canonical two-path rule: planned drains map to `logout` with `gateway_restart` context when the deterministic bridge-drain signal is received, while abrupt or unattributed bridge loss is surfaced immediately as `backend_unavailable`.

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

After `LOGIN` succeeds, clients must re-establish gameplay scope by selecting a world and character via the lobby commands (`WORLDS` / `CHARS` / `PLAY`) as defined in [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay-lobby-selection). This `LOGIN` → `PLAY` sequence is mandatory for both Telnet and WebSocket reconnect flows in this multi-tenant platform; first-party WebSocket reconnects must also acquire a fresh connect token before the `/ws/game/**` handshake. Gameplay commands are not admitted before `PLAY` except in explicitly documented dev/test bypass modes. If Telnet smart-client attach hints return later, they should ride hidden MCP metadata on the new TCP connection and remain advisory only.

Redis-backed session state allows resumable recovery when still valid, or a fresh login when it is not.
Session entries in Redis expire after a derived `session_expiration_ms` window (`FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`) as documented in [Environment and Secrets](./infrastructure/environment-and-secrets.md#authentication).

Resume is authorized from current identity and current membership/entitlement authority, not from the previous backend token alone. After a fresh successful `LOGIN`, Game Session must rebind any resumed gameplay session to a fresh backend token and reject resume if current membership authority for the tenant has been removed.

If the prior resumable gameplay state is stale or partially missing, Game Session should prefer invisible fresh entry whenever current `PLAY` admission is still valid. Missing room or game-instance resume context is not a player-facing failure by itself; the normal outcome is a successful fresh `PLAY` that rebinds the session to canonical entry state.

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow)

Gameplay command idempotency for reconnects is intentionally simple from the client’s perspective: Telnet and WebSocket clients treat commands as fire-and-forget. They do not attach idempotency keys or effect identifiers to individual commands in the text protocol; idempotency is handled internally by Game Session and domain services as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#gameplay-command-idempotency-client-view) and [Transactions & Idempotency](./system-architecture-transactions.md).

### Example Recovery Outcomes

Player-visible recovery output should stay helpful and protocol-shaped rather than surfacing backend jargon.

Successful reconnect after a normal disconnect:

```text
OK LOGIN Logged in as demo@example.com
OK PLAY Entered world: demo
<fresh LOOK or prompt output follows>
```

Stale or expired resumable state where a fresh `PLAY` can still be admitted should not force the player to type the same command twice. The normal outcome is still a successful fresh entry:

```text
OK LOGIN Logged in as demo@example.com
OK PLAY Entered world: demo
<fresh LOOK or prompt output follows>
```

Only failures that genuinely require player or client action should surface as errors, for example access revocation, missing entitlements, or backend unavailability.

Reconstructed session state after resume or fresh-entry fallback is intentionally bounded:

- current authenticated gameplay binding;
- current room/view state via fresh `LOOK`-style redraw or rerun;
- current tick/region participation and timers that still exist in shared authoritative state.

What is intentionally not replayed:

- pre-disconnect transport bytes or frames;
- partially delivered prompt/output text;
- volatile in-memory command buffering that did not survive the disconnect or restart.

---

## Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#multi-client-behavior-and-session-takeover).

At most one active gameplay binding is supported per uniqueness key `{tenantId, gameInstanceId, characterId}` at any point in time.

The active gameplay identity is `characterId`. When a new client successfully issues `LOGIN` for an identity that is already bound to another connection (whether Telnet or WebSocket), Game Session treats this as a **takeover**:

- The previous connection is disconnected or demoted according to the takeover rules in the authentication design.
- No ordering guarantees are provided between the last few commands on the old connection and the first commands on the new one; only **per-connection FIFO** is maintained as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
- Clients and tools must not assume that keeping multiple concurrent connections (for example Telnet + Web) to the same character will preserve any cross-connection ordering; instead, they should rely on the single active binding and takeover semantics.

---

## Resume vs Reload Scenarios

| Event | Result |
| --- | --- |
| Client disconnect (TCP/WebSocket) | Requires new `LOGIN` and `PLAY`; may resume via Redis |
| TCP Proxy Service restart | Telnet clients disconnected; new `LOGIN` and `PLAY` required |
| Spring Cloud Gateway restart | Fleet-level behavior should stay largely invisible: unaffected sockets on other healthy Gateway instances remain up and new handshakes continue. Connections bound to the specific Gateway instance being drained or crashing are visible failures. Planned drains should emit `1000/logout` (`subreason=gateway_restart`) for affected WebSocket sessions. Graceful unplanned failures may emit `1011/internal_error`, while hard crashes may drop transport without a close frame; clients treat missing close metadata as the `internal_error` retry class and must fetch a fresh connect token before reopening `/ws/game/**`. Telnet clients follow the same scope split: unaffected sessions bridged through other healthy Gateway instances continue normally, while planned drains on the serving bridge are surfaced by the proxy as `logout` (with `gateway_restart` context when available) and abrupt loss of the serving bridge is surfaced immediately as `backend_unavailable`. |
| Lease move / gameplay shard handoff | Ordinary in-cluster lease rebalancing remains internal to the Game Session layer and is not a distinct edge-visible event in the current contract; session front-end pods may continue serving the socket while forwarding region-owned work to the new lease owner. Forwarded requests use lease/epoch fencing. If fencing fails before gameplay side effects begin, the command is rejected visibly; if safe retry is possible, it is retried only behind effect/idempotency guards after ownership refresh; if ownership remains ambiguous, the session falls back to the existing structured command-failure or reconnect behavior rather than silently dropping or partially applying the command. If a future design introduces client-visible handoff semantics, the signal and reconnection/backoff policy must be defined explicitly in the Gateway and Protocol Bridging contracts. |
| Gateway ↔ Game Session link degraded (short window) | WebSocket connections may stay open when the upstream hop remains established and Game Session is reachable but returning explicit, per-command errors; clients do not reconnect solely due to transient command failures. If the upstream gameplay WebSocket closes, clients reconnect and re-`LOGIN`, then re-`PLAY`. |
| Gateway ↔ Game Session link degraded (`unreachable` sustained) | Gameplay becomes impossible; WebSocket sessions are closed with `1013` (`backend_unavailable`) and clients should reconnect with backoff as described below. Telnet clients are closed by the TCP Proxy with `backend_unavailable` when the gateway closes the upstream gameplay WebSocket or when the proxy cannot establish or maintain its bridge; clients reconnect and re-`LOGIN`, then re-`PLAY`. |
| Game Session Service restart | **Target state:** usually invisible to clients, with gameplay continuity recovered from shared state after a short stall. **Current implementation may still be visible in some paths** if the upstream gameplay WebSocket is dropped; treat that as implementation debt to remove rather than canonical behavior. |
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
- Non-edge gameplay services should remain replaceable/stateless workers against shared state; if restarting one of them visibly disconnects clients, the system should treat that as a gap in durable coordination rather than an accepted steady-state behavior
- The canonical recovery path remains explicit and protocol-visible for all clients
- Third-party MUD clients must be able to recover cleanly using the documented reconnect flow alone
- First-party clients may automate reconnect, reauthentication, and gameplay re-entry, but they must not depend on a private recovery model unavailable to other clients

## Client Reconnection Behaviour

FireMUD treats reconnection as an explicit **client-visible recovery flow**: after any disconnect, clients open a fresh transport (TCP or WebSocket), issue a new `LOGIN`, and complete `PLAY` before gameplay commands. This documented flow is the canonical interoperability contract for third-party MUD clients. First-party WebSocket clients may automate the same sequence for a smoother UX, but they must still follow the same underlying connect-token, `LOGIN`, and `PLAY` rules. To avoid thundering herds and to keep reconnect storms predictable during incidents, automated or first-party clients should follow a consistent reconnection policy:

- **Backoff and jitter**
  - Start with an initial delay of `1–2s` after the first failed reconnect attempt.
  - Use exponential backoff (for example, doubling the delay on each subsequent failure) up to a maximum backoff of `30–60s`.
  - Apply jitter of ±25% to each delay to avoid synchronized reconnect bursts from many clients.
- **Retry caps**
  - Cap reconnect attempts to a reasonable rate per client (for example, no more than ~6 attempts in the first minute and ~60 attempts per hour).
  - Client policy handling is split into explicit classes:
    - `policy_pressure_retriable` (for example HTTP `429`) – retry with slower backoff and strict caps.
    - `policy_violation_non_retriable` (for example sustained abuse/malformed protocol outcomes) – stop auto-retry or switch to very long backoff and surface corrective action to the user.
    - `policy_violation_edge_backpressure_retriable` – if wire-visible disconnect metadata explicitly indicates edge backpressure (for example WebSocket close `1008/policy_violation` with `subreason=edge_backpressure`, or the Telnet disconnect token `policy_violation;subreason=edge_backpressure`), treat as retriable with backend-unavailable backoff. If that metadata is absent, keep the default non-retriable policy for `policy_violation`.
- **Scope of reconnection**
  - Telnet clients reconnect by establishing a new TCP connection to the TCP Proxy Service, issuing `LOGIN`, and then issuing `PLAY` before gameplay commands. If smart-client attach hints return later, they should be re-sent as hidden MCP metadata on the new connection.
- Web clients reconnect by first obtaining a fresh connect token, opening a new WebSocket to `/ws/game/**`, issuing `LOGIN`, and then issuing `PLAY`; they must not assume that any prior MCP or hidden smart-client attach metadata has survived, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery).

For clarity, Telnet clients never receive a hidden transport-preserving recovery on an already-established session. Even if the proxy-wide admission circuit later returns to closed after an outage, any Telnet session whose established gameplay bridge was lost must reconnect on a fresh TCP socket and repeat `LOGIN`/`PLAY`.

Clients must also treat pre-disconnect output as non-resumable transport state. FireMUD does not replay raw transport bytes, prior WebSocket frames, or unsent Telnet output onto a newly opened connection. Instead, reconnect restores player-visible context in two distinct ways:

- a bounded per-player screen buffer keyed to gameplay identity may replay the most recent narrative/system transcript lines for that player after successful `LOGIN` + `PLAY`;
- then FireMUD emits fresh state-derived reconstruction output such as `LOOK` and prompt/status information.

This screen buffer is context restoration, not a transport delivery guarantee. It exists to help players understand what just happened around a disconnect; it does not promise exact delivery of every missed output line.

Prompt/status output is a separate output class from transcript lines:

- prompts are coalesced UI/state summaries, not ordinary scrollback messages;
- prompts are not part of the reconnect screen buffer by default;
- reconnect should restore transcript context first, then emit a fresh `LOOK`, then emit one fresh current prompt;
- first-party web and MCP-aware clients may consume prompt/status as structured data instead of rendering it into the main text transcript.

### Abnormal WebSocket Transport Loss

Client implementations must not assume every disconnect includes a close frame and reason token. Planned drains and many graceful failures will carry canonical close codes, but abrupt process/node/network failures can terminate transport before the edge emits a close frame.

- If the connection drops without a usable close code/reason, classify it as `internal_error` for retry/backoff policy purposes.
- Do not treat missing close metadata as `logout` or `policy_violation`.

### Gameplay WebSocket route handshake policy (normative)

`/ws/game/**` is the only gameplay route. Non-proxy clients with missing/invalid/expired/replayed connect-token state are rejected with HTTP `403` and handshake class `CONNECT_TOKEN_REJECTED`. Trust-boundary denials (for example internal-listener/mTLS policy mismatch) are rejected with HTTP `403` and handshake class `POLICY_DENY`. Successful first-party gameplay handshakes emit `X-Firemud-Connection-Mode: first_party_web`, while TCP Proxy bridge requests are admitted without a connect token only when proxy mTLS identity and trust checks pass and emit `X-Firemud-Connection-Mode: trusted_tcp_proxy`, so Game Session can distinguish both paths using positive markers rather than header absence.

### HTTP Handshake Failures on `/ws/game/**`

When Web clients attempt to establish or re-establish WebSocket connections and receive HTTP errors instead of a successful upgrade, they must interpret those signals consistently with the close-code taxonomy:

This table is the canonical client-policy matrix for gameplay-route handshake failures; gateway and client documentation must reference this mapping rather than redefining retry semantics independently.
Client behavior must key on handshake error class first, then HTTP status as a secondary signal.

| HTTP status | Handshake error class | Meaning on gameplay routes | Client policy |
| --- | --- | --- | --- |
| `429` | `POLICY_PRESSURE` | Edge rate or connection policy boundary reached | Classify as `policy_pressure_retriable`: retry with slower exponential backoff (start `5s`, cap `120s`, ±25% jitter) and strict retry caps; do not fast-loop. |
| `503` | `BACKEND_UNAVAILABLE` | Gateway currently considers gameplay backend unavailable | Treat as `1013/backend_unavailable`; use exponential backoff with jitter and retry caps. |
| `503` | `REPLAY_CHECK_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded exponential backoff (start `10s`, cap `60s`, ±25% jitter) and surface temporary edge-auth-unavailable context (not backend-outage messaging). |
| `403` | `CONNECT_TOKEN_REJECTED` | Connect-token state invalid for gameplay handshake (missing, expired, replayed, malformed, or failed signature/claim checks) | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `POLICY_DENY` | Handshake denied by gateway policy/trust boundary (for example internal-only listener, mTLS identity, security policy mismatch) | Treat as non-retriable until configuration/permissions change; surface as actionable operator/user error. |
| `426` | `PROTOCOL_MISMATCH` | Protocol upgrade requirement not met | Retry only after client transport/protocol correction (for example proper WebSocket upgrade/TLS endpoint). |
| Other `5xx` | `INTERNAL_ERROR` | Unexpected gateway/infra failure | Treat as `internal_error`; use exponential backoff with jitter. |

For gameplay routes, HTTP `401` is not part of the normal handshake taxonomy because gameplay authentication occurs after WebSocket establishment via `LOGIN`/`PLAY`. If `401` appears in practice, treat it as a misconfiguration signal and investigate gateway policy drift.

First-party clients and tools should implement a unified “edge error → backoff policy” table that maps WebSocket close codes (plus abnormal no-close transport loss) and handshake error classes (`POLICY_PRESSURE`, `BACKEND_UNAVAILABLE`, `REPLAY_CHECK_UNAVAILABLE`, `CONNECT_TOKEN_REJECTED`, `POLICY_DENY`, `PROTOCOL_MISMATCH`, `INTERNAL_ERROR`) to concrete backoff behaviour so reconnect storms remain predictable during incidents. This policy table must be derivable from wire-visible signals alone; operator-only metrics are supplemental for diagnostics, not required for client retry decisions.

For clarity: HTTP `429` is not a hard “never retry” signal in FireMUD. It is a controlled `policy_pressure_retriable` class intended to protect the edge under pressure while still allowing eventual recovery for legitimate clients.

Clients that do not implement these backoff rules will still function, but first‑party tools and reference clients should treat this behaviour as the normative baseline so that production incidents do not amplify reconnect load.

## Failure Modes & Reconciliation Rules

`NotifyDisconnect` events and edge behaviour around disconnects are intentionally **advisory**, with Redis and gameplay activity as the source of truth:

- **Authoritative liveness** – Game Session treats Redis session entries, tick activity, and its own heartbeats as authoritative for session liveness. `NotifyDisconnect` is a best-effort, at-least-once hint from the TCP Proxy Service, not the sole indicator of whether a client is still connected.
- **Idempotent disconnect handling** – Game Session keys disconnect handling by `{proxyConnectionId, disconnectSequence}` and treats duplicate events for the same pair as no-ops. Late events that arrive after a new socket has been bound or after Redis has expired the session are also safe to ignore; they must not forcefully tear down a new, healthy binding.
- **Single close transition** – For a given gameplay binding, Game Session must implement one authoritative close/suspend transition keyed by the current bound connection identity. Upstream WebSocket close detection and `NotifyDisconnect` are separate signals that race into that same transition; neither path may independently perform duplicate teardown, suspend, or metric side effects once the binding has already advanced state.
- **Missing hints** – Absence of a `NotifyDisconnect` event is never interpreted as a guarantee that the client is still connected. Game Session relies on its own timeouts and region/tick-level activity to clean up stale sessions when disconnect hints are missing (for example due to gRPC transport failures).
- **Edge ordering vs domain idempotency** – The TCP Proxy → Gateway → Game Session path provides per-connection FIFO where delivered and at-most-once delivery, as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Game Session and downstream domain services implement durable idempotency guards (for example effect IDs and transaction rows) so retries and replays within the tick system remain safe even when edge hints are late or duplicated. See [gRPC API Style & Versioning](./system-architecture-grpc.md) and [Transactions & Idempotency](./system-architecture-transactions.md) for the underlying RPC and effect semantics.

### Backend-Unavailable Scenarios

Some failures leave edge connections technically alive while core gameplay services are degraded. To keep behaviour predictable:

- **`degraded_but_reachable` state**
  - When Game Session is reachable and can still return a bounded explicit gameplay/protocol response for each affected command over the existing upstream WebSocket, Spring Cloud Gateway keeps existing sessions open and clients receive explicit per-command failures.
  - Telnet clients follow an equivalent outcome through the proxy bridge: while the bridge remains healthy and backend responses remain explicit and bounded, connections stay open and command failures remain explicit backend responses.
  - This state must not be used for “connected but blackholed” behavior. If the platform cannot produce a bounded explicit response for gameplay on an established session, the edge must treat the route as `unreachable` for that session rather than leaving the client connected without progress.
- **`unreachable` state**
  - When Gateway cannot establish or maintain the upstream gameplay WebSocket, it enters `unreachable` and starts the `firemud.gateway.backendUnavailableGraceMs` timer.
  - During this state, new gameplay-route handshakes (`/ws/game/**`) are rejected with HTTP `503`. If an established session attempts to send gameplay traffic while upstream is unreachable, Gateway immediately closes with `1013/backend_unavailable` rather than buffering or silently dropping commands.
- **Sustained backend unavailability**
  - When `unreachable` remains continuous beyond `firemud.gateway.backendUnavailableGraceMs`, Spring Cloud Gateway closes affected gameplay WebSocket sessions with `1013/backend_unavailable`, signalling clients to apply reconnection/backoff rules.
  - Returning from `unreachable` to normal routing requires hysteresis (consecutive successful upstream connects/forwards) as defined in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window), so one brief success does not flap clients.
  - Proxy and gateway outage windows must be configured in lockstep using explicit keys: `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` equals `firemud.gateway.backendUnavailableGraceMs`, and `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` equals `firemud.gateway.backendUnavailableRecoverySuccessCount`.
  - Edge services must fail fast on invalid local values (`<= 0`), and deployment preflight must fail when any of these lockstep values diverge.
  - The TCP Proxy Service enforces equivalent admission outcomes with a bridge-availability circuit breaker: while the breaker is open (continuous gateway gameplay unreachability), new Telnet sockets are rejected quickly with `backend_unavailable` and existing affected sockets are closed with `backend_unavailable` rather than being held in ambiguous half-open states. Recovery from half-open to closed requires `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful bridge probes (default `3`) so proxy admission hysteresis stays aligned with gateway recovery criteria.
  - Operators should treat elevated counts of `1013` (`backend_unavailable`) and proxy‑side “backend unavailable” disconnects as indicators of core gameplay outages rather than client misuse, and use the metrics referenced in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients) and the Telnet degraded runbook to distinguish these from slow‑client backpressure events.

### Telnet Bridge State Machine (Established Sessions)

To avoid ambiguous half-open behavior for already-established Telnet sessions, the TCP Proxy bridge uses an explicit per-connection state machine:

- `healthy` – upstream WebSocket bridge is established; gameplay traffic flows normally.
- `close_due_to_clean_logout` – if the proxy receives `1000/logout` on the authenticated bridge path, it closes the Telnet session as `logout` and preserves the bounded subreason. `subreason=gateway_restart` is the planned-drain case within this branch.
- `close_due_to_unreachable` – if the established upstream gameplay WebSocket cannot be maintained for any other reason, the proxy closes that Telnet connection immediately with `backend_unavailable`. The proxy does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach.
- `close_due_to_edge_backpressure` – if buffered lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is still reachable, close that Telnet connection with `policy_violation` and emit `edge_backpressure` context in structured logs/metrics.

This state machine is authoritative for established-session behavior and complements (but does not replace) the proxy-wide open/half-open/closed admission breaker used for new Telnet admissions.

### NotifyDisconnect Behavioral Contract (Summary)

The TCP Proxy Service emits `NotifyDisconnect` events to the Game Session Service over an internal-only gRPC stream whenever Telnet sockets close. Behaviourally, this stream follows a simple, canonical contract:

- **At-least-once, advisory delivery** – Transport for `NotifyDisconnect` is intentionally at-least-once: events may be delivered more than once or arrive late relative to the underlying TCP close. Game Session treats this stream as a best-effort hint about Telnet liveness, not as the source of truth; Redis session state and gameplay activity remain authoritative.
- **Idempotency key** – Every event carries an idempotency key derived from `{proxyConnectionId, disconnectSequence}`. Game Session persists the latest processed `disconnectSequence` per `proxyConnectionId` and must treat older or duplicate events for the same pair as no-ops so proxy-side retries remain simple and safe.
- **Idempotency record retention** – Game Session retains the latest processed sequence record per `proxyConnectionId` for at least `session_expiration_ms + TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` so delayed retries and reconnect races remain deduplicated. These records then expire via TTL-based cleanup.
- **Allowed dedupe storage shapes** – These dedupe records may live in Redis or another durable store that survives ordinary Game Session process restarts for at least the required retention window. In-memory-only dedupe is not sufficient for the canonical contract.
- **Capacity and eviction guardrails** – Consumer implementations must enforce a bounded maximum cardinality for active dedupe records (for example per tenant and per process shard). If the cap is hit during disconnect floods, eviction policy is deterministic `oldest-expiry-first`, and implementations must emit explicit overload metrics/logs (for example `gamesession.notifydisconnect.dedupe.capacity_reached`) so operators can distinguish flood pressure from logic bugs.
- **Eviction scope** – When capacity guardrails are partitioned by shard or tenant, `oldest-expiry-first` applies within the partition that hit its cap first; implementations must not silently switch to an unbounded global pool during floods. Canonical examples include a process-local shard partition or a tenant-scoped shard partition, but any chosen partition boundary must remain bounded, explicit, and operationally observable.
- **Operator interpretation** – Sustained `gamesession.notifydisconnect.dedupe.capacity_reached` during a regional outage should be treated as disconnect-flood pressure on the dedupe path, not as evidence that duplicate teardown is acceptable. Operators should scale or relieve the affected partition and confirm Redis/durable-store health before relaxing retention guarantees.
- **Runbook linkage** – During live incidents, use the Telnet degraded runbook for the operator workflow around `gamesession.notifydisconnect.dedupe.capacity_reached` and related bridge-failure signals; this section remains the canonical behavior contract, while the runbook covers triage order and mitigation steps.
- **Retry window** – The proxy retries failed `NotifyDisconnect` calls for a short, bounded window after Telnet socket close (see the TCP Proxy Service design’s **Service Interactions** section and the `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` configuration for exact timing). After this window, the proxy stops retrying and relies on Game Session’s own liveness detection and Redis timeouts to reconcile any missing hints.
- **Complement to edge delivery guarantees** – `NotifyDisconnect` complements, but does not change, the at-most-once edge delivery model for gameplay commands described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Late or duplicate disconnect hints must never cause healthy, newly bound sessions to be torn down; consumers always key decisions off the idempotency key and current Redis state.

Resume does not imply replay of prior outbound transport data. After reconnect, Game Session may emit fresh reconstruction output once the new binding is admitted, but it must not replay pre-disconnect text or MCP frames as if the old transport had continued. Any reconstruction output must be re-derived from current authoritative state, not copied from an old outbound queue.

This section is the canonical behaviour-level summary for `NotifyDisconnect`. The TCP Proxy Service design remains authoritative for message fields, configuration knobs, and implementation details, while [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics) defines the general pattern for similar at-least-once event sinks elsewhere in the system.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
