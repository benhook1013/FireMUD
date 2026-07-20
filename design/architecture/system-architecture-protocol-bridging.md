# Protocol Bridging: WebSocket and Telnet (TCP)

This document describes how FireMUD supports **both modern and traditional MUD clients** by bridging two distinct communication protocols: **WebSocket** and **raw TCP (Telnet)**. Both are routed into a unified backend session service for shared logic and scalability.

This design is the **canonical specification** for gameplay command flows through the edge: it defines ordering and delivery guarantees, backpressure and slow-client behaviour, Telnet and WebSocket reconnection and buffering rules, and the Telnet disconnect reason taxonomy. Service-specific designs such as the TCP Proxy Service README describe implementation details and configuration but must remain consistent with the invariants in this document.

---

## Bridging Overview

FireMUD enables real-time interaction through two types of client connections:

| Client Type | Protocol | Entry Point |
| --- | --- | --- |
| Web-based clients | WebSocket | Spring Cloud Gateway (`/ws/game/**`) |
| Traditional MUD clients | TCP (Telnet) | TCP Proxy Service (custom) |

Despite their differences, both protocols are normalized into the same internal architecture using a **WebSocket-based session layer**.

---

## WebSocket Client Flow (Modern Clients)

- Used by browser-based MUD clients or modern tools.
- Connections are initiated using the WebSocket protocol.
- Routed through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md), which supports WebSocket proxying.
- Canonical player-facing endpoint is `/ws/game/**` (token-enforced for non-proxy clients; trusted TCP Proxy bridge is authenticated by mTLS identity).
- Forwarded to the [Game Session Service](./microservices/game-session-service/README.md), which maintains the gameplay session.
- Game Session restart should ideally be absorbed behind the established edge connection using shared state and backend rebind, as described in [Reconnection Strategy](./system-architecture-reconnection.md). Spring Cloud Gateway remains the edge socket owner, so a client whose live WebSocket was terminated on the specific Gateway process that restarted or crashed still reconnects, acquires a fresh connect token for `/ws/game/**`, and re-runs `LOGIN`/`PLAY`. Unaffected sockets on other healthy Gateway instances should remain up, and the gateway fleet should continue accepting new handshakes through those healthy instances.

### WebSocket Flow Benefits

- Leverages Spring Cloud Gateway’s routing, header enforcement and forwarding, logging, and rate limiting while leaving authentication and authorization decisions to backend services as described in [Authentication & Authorization](./system-architecture-authentication.md).
- Ideal for web UIs, admin tools, or companion clients.

### Gameplay WebSocket route contract (normative)

- `/ws/game/**` is the only gameplay WebSocket route.
- Non-proxy gameplay clients must present a valid connect token; missing/invalid token returns HTTP `403`.
- TCP Proxy bridge traffic is admitted without connect token only when the gateway authenticates the proxy identity over the internal mTLS listener and header-trust checks pass.
- All clients still require in-band `LOGIN` and `PLAY` before gameplay commands.

---

## Telnet / TCP Client Flow (Legacy Clients)

- Used by traditional MUD clients (e.g., MUDlet, TinTin++, GMud).
- Clients connect using raw TCP (typically Telnet-compatible) and are handled by a dedicated **TCP Proxy Service**.
- The TCP Proxy Service listens on port `2323` by default so Telnet clients can simply connect without additional configuration. This and the Spring Cloud Gateway WebSocket URL can be adjusted with the `TCP_PROXY_PORT` and `GATEWAY_WS_URL` environment variables described in the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables). `GATEWAY_WS_URL` should always be set explicitly by deployment config; local Compose smoke also sets it explicitly to the canonical in-stack target. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for general configuration guidance.
- Normal Telnet clients connect, optionally browse `WORLDS`, then issue `LOGIN` and `PLAY` before gameplay commands. Typed `SESSION` lines are no longer part of the Telnet contract. If smart-client attach hints return after a classic-client adapter is selected and proven, they remain advisory to Game Session and never carry identity, authorization, session, or attach authority.

The proxy establishes the Proxy → Gateway gameplay WebSocket lazily for each Telnet connection:

- The bridge is opened before the first forwarded gameplay or optional extension line.
- The proxy may include server-owned advisory bootstrap metadata on the authenticated Proxy → Gateway handshake when local defaults or future selected-extension smart-client hints are available.
- Those hints must never bypass `LOGIN` + `PLAY` or retroactively alter an already-established gameplay binding.

Planned Gateway drain example:

- Gateway closes the authenticated internal bridge with `1012/service_restart`.
- TCP Proxy classifies that machine-parseable bridge close as `bridge_shutdown_class=planned_drain`.
- The Telnet client receives a final `service_restart` disconnect. Optional operational metadata may identify the deployment, but it is not required to choose reconnect behavior.

Clean upstream session-end examples:

- Game Session signals terminal logout or forced session termination as `1000/logout`, and Gateway preserves that top-level outcome on the authenticated internal bridge. TCP Proxy translates it to Telnet `logout`.
- Game Session signals successful controller takeover for the displaced transport, and Gateway emits `1000/session_replaced`. TCP Proxy translates it to Telnet `session_replaced`.
- Bounded subreasons may add operational context, but omission or an unknown value cannot change either lifecycle classification.

Unattributed bridge-loss example affecting one established Telnet bridge:

- The authenticated internal bridge drops without a machine-parseable planned-drain close (for example abrupt transport reset or crash).
- TCP Proxy classifies the loss as `bridge_shutdown_class=unattributed_failure`.
- For that already-established Telnet session, the proxy closes the client connection immediately with `backend_unavailable`; it does not hold the TCP socket open for hidden bridge recovery or invent `service_restart`. The proxy cannot prove from an absent close frame whether Gateway, its network path, or the routed backend failed. Other Telnet sessions whose bridges terminate on healthy Gateway instances should remain unaffected.

No classic-client extension is currently supported or advertised. If a later selected adapter is enabled, its negotiation and package-local state are scoped to one client transport connection and must be established fresh after every reconnect. Redis-backed gameplay session state and durable actor state remain independent of optional extension metadata. See [Classic MUD Client Extensions](./system-architecture-mud-client-protocol.md#reconnection-and-failure-fallback) for details.

---

## Ordering & Delivery Invariants

The combined TCP Proxy → Spring Cloud Gateway → Game Session path preserves a clear set of ordering and delivery guarantees for **gameplay command streams from clients into Game Session**:

- **Per-connection FIFO where delivered** – For a given Telnet/TCP or WebSocket connection, gameplay commands and text lines are forwarded to the Game Session Service in the same order they were accepted by the edge (TCP Proxy for Telnet, Gateway for WebSocket). No component in this client → Game Session command path intentionally reorders gameplay messages or generates duplicates.
- **At-most-once edge delivery with bounded loss before acceptance** – The edge protocol path is **at most once**: once a command is dropped by an edge layer, that layer does not retry or replay it. Potential loss is limited to commands still resident before trusted Game Session acceptance; there is no implicit replay across disconnects. At trusted acceptance, Game Session assigns or preserves a stable `commandId` and durably records the command lifecycle before acknowledging it. Ordinary interactive commands remain tracked but non-replayable: internal loss before staging terminalizes as `LOST_BEFORE_STAGING` / `NOT_APPLIED` rather than silently disappearing or executing later. Internal retries use the same identity and owner guards; see [ADR 0062](./decisions/adr-0062-layered-gameplay-command-delivery-semantics.md) and [ADR 0016](./decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md).
- **No replay of prior outbound stream across reconnects** – The edge does not preserve or replay previously-sent server text, WebSocket frames, or optional extension messages onto a new client transport after reconnect. After resume, Game Session may send fresh post-`PLAY` state or summaries, but it must not treat the new transport as a continuation of the prior byte stream.
- **At-least-once delivery (edge event sinks)** – Internal gRPC event sinks associated with the edge (for example the TCP Proxy’s `NotifyDisconnect` stream into Game Session) are intentionally **at-least-once** and must be consumed idempotently with respect to their idempotency keys, as described in [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics). These streams are advisory hints that complement, but do not change, the at‑most‑once guarantees for client gameplay commands.
- **Explicit drop conditions (edge layers)** – Commands or lines may be dropped under clearly defined conditions, including:
  - TCP Proxy upstream-bridge failures: if the TCP Proxy cannot establish the Proxy → Gateway WebSocket bridge within its bounded retry budget because upstream gameplay is unavailable, it closes the Telnet connection with `backend_unavailable`. If handshake trust checks fail (for example mTLS certificate validation or policy deny), it closes with `policy_violation` instead. For established sessions, TCP Proxy preserves authoritative top-level bridge closes as their equivalent Telnet reasons: terminal `logout`, `session_replaced`, or `service_restart`. An unattributed established-session bridge loss retains the observation-limited `backend_unavailable` fallback. If upstream backpressure causes the proxy’s Telnet → Gateway input buffer ceiling to be exceeded while upstream remains reachable, it closes the connection with `policy_violation` and records `edge_backpressure` context in logs/metrics rather than silently discarding gameplay commands.
  - generic line-size and opaque extension-marker rate limits at TCP Proxy, without package, cord, tag, authentication-key, or negotiation semantics, as described in [Classic MUD Client Extensions](./system-architecture-mud-client-protocol.md);
  - Abuse and safety limits in the TCP Proxy Service for oversize lines and malformed Telnet traffic, where the proxy either discards input or closes the connection;
  - Client-side disconnects or network loss (TCP/WebSocket) where the edge cannot reliably determine whether the last few bytes were delivered to the client.
- **No implicit replay on reconnect** – Neither Spring Cloud Gateway nor the TCP Proxy Service replays gameplay commands or optional extension messages across reconnects. After any disconnect, clients must resend `LOGIN`, re-establish gameplay scope with `PLAY`, and negotiate any future supported extension fresh. Non-proxy WebSocket clients must also present a fresh connect token when opening `/ws/game/**`. Clients then rely on Game Session and Redis to resume or start fresh according to [Reconnection Strategy](./system-architecture-reconnection.md). Game Session and downstream domain services may use internal effect identifiers and transactional idempotency to protect tick processing and side effects, but these mechanisms are not exposed directly in the Telnet and WebSocket text protocol.

Edge behaviour distinguishes between **gameplay command lines** and **opaque reserved extension-marker lines**:

- Gameplay text commands that Game Session treats as input are never silently discarded while the connection remains open. When a gameplay line would exceed a non-MCP input or output safety limit, the TCP Proxy Service or gateway closes the connection with a clear reason rather than dropping the command in place.
- Opaque lines matching a configured reserved extension marker may be discarded under the marker-rate budget while the connection stays open. This is generic edge protection, not package parsing or a negotiation decision. Gameplay continues because no supported gameplay flow depends on an extension. Sustained symptoms that look like “partial gameplay output” or “missing gameplay commands without disconnects” should be treated as a bug in the edge or Game Session implementation rather than expected backpressure behaviour.

When any layer drops input due to its own limits or backpressure protections, it should close the connection with a clear, human-readable message or (for WebSocket clients) send an explicit error/close reason before terminating the session. Hard transport failures (for example process crash, node/network reset) may terminate sessions before any close frame or final line is emitted; clients must handle this abnormal transport-loss case per [Reconnection Strategy](./system-architecture-reconnection.md#abnormal-websocket-transport-loss). Edge components still do **not** silently discard gameplay commands while keeping a connection that appears healthy to the client.

Domain services treat incoming commands as **idempotent with respect to their effect identifiers** so that retries at the Game Session layer (for example tick replays) can safely handle duplicates even though the edge path is at-most-once. See [Transactions & Idempotency](./system-architecture-transactions.md) and [Redis Architecture](./system-architecture-redis.md) for the underlying invariants.

### Gameplay Command Idempotency (Client View)

The base Telnet and plain-text WebSocket protocols treat gameplay commands as **fire-and-forget with respect to the edge**, not as untracked after Game Session acceptance:

- Clients do not attach idempotency keys, effect identifiers, or per-command sequence numbers to text commands as part of the Telnet or WebSocket protocol described in this document.
- Trusted Game Session ingress assigns the stable identity when the client does not provide one. A human player never types or manages that identity.
- A versioned capable-client envelope may supply a `commandId` and use the canonical status API. Reusing that ID observes or advances the same logical record; it does not automatically make stale interactive intent replayable.
- When a command is lost before trusted acceptance, the client may reissue it only as a new gameplay action. After acceptance, Game Session's durable status distinguishes applied, abandoned, and not-applied outcomes.
- Automatic durable replay is limited to explicitly classified features with safe expiry and context preconditions.

Architecture and service designs must not require base text clients to participate in an idempotency protocol. They must still preserve Game Session's assigned identity and durable outcome through every internal retry boundary.

### Telnet Disconnect Reasons

Telnet clients receive final disconnect messages from the TCP Proxy Service when connections close due to policy, slow-client behaviour, backend outages, or internal errors. To keep behaviour aligned with WebSocket close codes from [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts) and [ADR 0127](./decisions/adr-0127-lifecycle-distinct-gameplay-close-taxonomy.md), the TCP Proxy Service standardises a small set of Telnet disconnect reason categories:

- `logout` – terminal gameplay logout or forced session termination; maps to WebSocket `1000/logout`. TCP Proxy preserves the top-level terminal outcome without requiring a subreason.
- `session_replaced` – the old transport was displaced by a successful controller takeover; maps to WebSocket `1000/session_replaced`.
- `idle_timeout` – idle-connection timeout where no traffic has been observed within the configured idle window; maps to WebSocket `1001` with reason `idle_timeout`.
- `policy_violation` – client behaviour that violates platform policies (for example sustained command-rate abuse, malformed envelopes, intentionally abusive traffic, or edge trust/policy handshake failures such as proxy mTLS certificate validation mismatch); maps to WebSocket `1008` with reason `policy_violation`.
- `service_restart` – planned Gateway drain or restart; maps to standard WebSocket `1012/service_restart`.
- `backend_unavailable` – gameplay backend services (Game Session or critical dependencies) are unavailable or overloaded beyond well-defined grace windows on the WebSocket and Telnet paths. On the WebSocket side, Spring Cloud Gateway emits `1013/backend_unavailable` when ADR 0013's bounded upstream-recovery timer expires or the stalled-input buffer cannot accept more input, as described in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window). On the Telnet side, the TCP Proxy emits `backend_unavailable` when its bridge-availability state determines gameplay admission is unavailable (including sustained inability to establish or maintain Proxy → Gateway gameplay connectivity) and does not keep ambiguous half-open gameplay sessions. Edge buffer-pressure closes map to `policy_violation` with `edge_backpressure` context only when the gameplay upstream is reachable; buffer exhaustion caused by a Game Session stall maps to `backend_unavailable`.
- `internal_error` – unexpected server-side failures not attributable to client behaviour and not clearly backend unavailable; maps to WebSocket `1011/internal_error`. A WebSocket transport may fail before that close frame can be emitted, in which case clients apply the same retry class to the abnormal transport loss. TCP Proxy retains `backend_unavailable` for unattributed loss of its established Gateway bridge because it cannot infer an internal-error cause from the absent frame.

The authoritative cross-layer translation table and precedence rules for WebSocket and Telnet close outcomes live in [Gateway Architecture](./system-architecture-gateway.md#canonical-close-translation-matrix). This document defines the Telnet taxonomy and must remain consistent with that table.

The exact Telnet disconnect line format is defined in the TCP Proxy Service design as `DISCONNECT <reason-token> <human-message>\n`. Every player-visible disconnect must include one of these reason tokens so that:

- Client authors can treat `policy_violation` as non-retriable (or much longer backoff), `logout` as terminal, `session_replaced` as displacement by another controller, `service_restart` as planned-maintenance retry, and the remaining failure outcomes according to [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour). Optional wire-visible diagnostic detail may refine operator presentation but cannot be required to determine those lifecycle classes.
- Operators can aggregate disconnect metrics by reason category in a way that lines up with WebSocket close-code dashboards.

Telnet structured logs and metrics may preserve the same bounded subreason context used by the WebSocket side (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, `none`). Telnet wire messages may include that detail, but the top-level reason token alone must determine lifecycle behavior. Deploy drain and takeover therefore use `service_restart` and `session_replaced` rather than depending on `gateway_restart` or `takeover` suffixes.

Concrete terminal and takeover examples:

- A Telnet client is already in gameplay.
  - A second client successfully takes over the same `{tenantId, playableStateNamespaceId, characterId}` controller binding, or an admin ends that session cleanly.
- For administrator-forced termination, Gateway emits `1000/logout` and TCP Proxy emits Telnet `logout`.
- For successful takeover, Gateway emits `1000/session_replaced` and TCP Proxy emits Telnet `session_replaced` on the displaced transport.

Any additional Telnet-specific reasons introduced in the TCP Proxy implementation must be documented here and mapped to one of the WebSocket categories above (or a new, explicitly added category) to keep the taxonomy unified.

### Cross-Client Takeover Examples

The underlying authentication and gameplay services enforce a **single active gameplay binding per `{tenantId, gameInstanceId, characterId}`**, as described in [Authentication & Authorization](./system-architecture-authentication.md#multi-client-behavior-and-session-takeover) and [Reconnection Strategy](./system-architecture-reconnection.md#resume-vs-reload-scenarios). From the networking and protocol edge, this manifests as follows:

- **Telnet → Web takeover**
  - A Telnet client connects via the TCP Proxy, issues `LOGIN`, and enters gameplay with `PLAY` for a character.
  - Later, a Web client connects via WebSocket to `/ws/game/**` and successfully issues `LOGIN` + `PLAY` for the same character.
  - Game Session treats the Web client as the new active binding, terminates or demotes the Telnet connection according to takeover rules, and closes the Telnet path with a `session_replaced` Telnet reason (mapped to WebSocket `1000/session_replaced` in the taxonomy above). No ordering guarantees are provided between the last Telnet commands and the first WebSocket commands; only per-connection FIFO holds on each individual connection.
  - The Telnet client must treat this disconnect as a normal session takeover outcome, apply its reconnection/backoff rules if it wishes to reconnect, and not assume that any new Telnet connection can “resume” alongside the active WebSocket binding.
- **Web → Telnet takeover**
  - A Web client connects via `/ws/game/**`, issues `LOGIN`, and enters gameplay with `PLAY` for a character.
  - A Telnet client later connects through the TCP Proxy and logs in as the same character.
  - Game Session treats the Telnet client as the new active binding; the WebSocket session is closed with `1000/session_replaced` and the Telnet connection becomes authoritative. Again, cross-connection ordering is not defined: only per-connection FIFO is guaranteed, and clients must not attempt to sequence commands across the old and new transports.
- **Concurrent Telnet + Web connections**
  - When clients deliberately keep both a Telnet connection and a WebSocket connection open for the same character (for example a scripting tool plus a web UI), only one binding at a time is gameplay-active. The “losing” connection is closed or demoted by Game Session, surfaced at the edge via the standard disconnect categories, and must not be relied on for ongoing gameplay commands.

The networking layer does not implement its own multi-client arbitration or attempt to keep connections in sync; it simply reflects Game Session’s takeover decisions via the Telnet and WebSocket disconnect taxonomy. Tools and clients must design their UX around the single-active-binding model rather than expecting concurrent, ordered control over a character from multiple transports.

---

## Backpressure & Slow Clients

Backpressure and slow-client handling are split across layers so that the platform remains robust without silently corrupting gameplay streams. Responsibilities and observability are intentionally divided between the TCP Proxy, Spring Cloud Gateway, and Game Session:

- **Telnet/TCP clients (TCP Proxy Service)**
  - The TCP Proxy Service enforces a strict per-socket output buffer limit. When a Telnet client cannot keep up with outbound traffic and the proxy’s output buffer fills, the proxy closes the Telnet connection with a clear message rather than silently dropping gameplay lines in the middle of a session. Relevant events are surfaced via metrics such as `tcpproxy.telnet.discarded` and per-connection counters; see the TCP Proxy Service design’s **Connection Limits and Abuse Protection** section.
  - Input-side backpressure is governed by per-connection and per-IP line-rate budgets, generic line-size limits, and an opaque reserved-marker rate limit. Marker lines over budget may be dropped without interpreting their protocol semantics; sustained generic abuse may cause the proxy to close the connection. The Telnet degraded runbook documents how operators should interpret these metrics and when to adjust limits vs block abusive sources.
- **WebSocket clients (Gateway / WebSocket container)**
  - Spring Cloud Gateway (or its underlying WebSocket container) is responsible for **network-level slow-client detection** on `/ws/game/**`. When a WebSocket client is slow to read and outbound send buffers for that connection fill or repeatedly time out, the gateway closes the WebSocket connection rather than silently discarding frames.
  - Gateway-side slow-client closures should use the standard close codes from [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts) and must map to `1008/policy_violation` (with a bounded subreason such as `edge_backpressure`) rather than `1013/backend_unavailable`. Gateway metrics such as `gateway.websocket.slow_client_closes` and route-level close-reason counters allow operators to distinguish network-level backpressure from backend outages.
  - Spring Cloud Gateway’s Redis-backed rate limiting focuses on **connection establishment and HTTP requests**, not individual WebSocket frames, as described in [Gateway Architecture](./system-architecture-gateway.md#rate-limiting--abuse-protection). Once a WebSocket is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level frame-by-frame throttling.
- **WebSocket clients (Game Session Service)**
  - Game Session provides **domain-level backpressure**. For gameplay WebSocket sessions, it applies a per-session outbound queue limit and send-timeout budget on its side of the connection. If either is exceeded (for example because a client has stopped reading or a downstream hop between Game Session and the gateway is persistently slow), Game Session closes the session with an explicit close reason instead of allowing the queue to grow without bound or dropping frames while pretending the connection is healthy.
  - For inbound overload (for example, a misbehaving client sending commands far beyond expected rates), Game Session either rejects excess commands with visible error messages or terminates the session after sustained abuse; it does not accept and then silently discard gameplay input. Domain-level backpressure and abuse closures are surfaced via metrics such as `gamesession.connection.closed{reason="backpressure"|"rate_limit"}` and command‑level error counters.
- **Client expectations**
  - When WebSocket connections close due to slow-client behavior, abuse, network issues, or backend unavailability, non-proxy clients must fetch a fresh connect token, reconnect, re-`LOGIN`, and re-`PLAY`. Game Session’s Redis-backed state determines whether gameplay resumes or starts fresh, per [Reconnection Strategy](./system-architecture-reconnection.md).

This model favors **clear closures over silent drops** when a client cannot keep up and provides enough metrics at each layer for operators to identify whether the TCP Proxy, Gateway, or Game Session is enforcing backpressure in a given incident.

### Global Load Shedding Strategy

During severe load or partial outages, each layer in the TCP Proxy → Gateway → Game Session path participates in protecting the platform, but responsibilities are ordered so that core gameplay services are preserved and client signals remain clear:

- **Core gameplay first (Game Session and Redis)**
  - Game Session and its Redis dependencies expose health and saturation metrics (for example queue depth, tick latency, and error rates). When these cross defined thresholds, Game Session prioritises preserving existing sessions and regions while rejecting new logins or high-cost commands, surfacing clear error responses rather than allowing unbounded growth in queues or CPU usage.
  - Region-level degradation and command throttling are considered core policy decisions and are described in more detail in the tick and Redis architecture docs; edge components treat these errors as backend-level signals rather than attempting to work around them.
- **Gateway next (connection creation and route-level limits)**
  - Spring Cloud Gateway protects the core by tightening rate limits on new HTTP and WebSocket connections and, when necessary, preferring to fail new handshake attempts with HTTP 429/503 (as described in [Gateway Architecture](./system-architecture-gateway.md#rate-limiting--abuse-protection)) over tearing down large numbers of existing gameplay sessions. When core gameplay backends remain unavailable beyond `firemud.gateway.backendUnavailableGraceMs`, Gateway then closes existing gameplay WebSocket sessions with `1013/backend_unavailable` and rejects further `/ws/game/**` handshakes with HTTP 503, per the grace-window semantics in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window) and the reconnection rules in [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios).
- **TCP Proxy as outer edge (DMZ safety rails)**
  - The TCP Proxy Service remains the first line of defence against obvious floods and abusive Telnet patterns via `TCP_PROXY_MAX_CONNECTIONS`, `TCP_PROXY_MAX_CONNECTIONS_PER_IP`, idle timeouts, and buffer depth limits, backed by metrics such as `tcpproxy.connections.limit.exceeded` and `tcpproxy.telnet.discarded`.
  - In healthy but busy conditions, these limits are tuned so that normal player behaviour is primarily shaped by Gateway and Game Session policies rather than frequent proxy disconnects. Under clear Telnet-specific abuse (for example, a small set of IPs consuming most connections), operators first adjust proxy-side caps or block misbehaving sources rather than relaxing gateway or Game Session limits.

Operators should interpret spikes in each layer’s metrics in this order when diagnosing load incidents: check Game Session and Redis saturation first, then Gateway rate-limit and backend unavailable signals, and finally TCP Proxy connection limits. This layered strategy ensures that both WebSocket and Telnet entry points shed load in a way that keeps behaviour predictable for players and preserves the integrity of core gameplay services.

### Telnet edge proxy and PROXY protocol

In all shared and player-facing environments, public Telnet terminates on a dedicated **Telnet edge proxy** (for example HAProxy) that forwards to the TCP Proxy Service using **PROXY protocol** on an internal-only listener. In this topology:

- External clients connect to the Telnet edge proxy on the public `LoadBalancer` / ingress.
- The edge proxy forwards to the TCP Proxy Service using PROXY protocol on the port configured by `TCP_PROXY_PROXY_PROTOCOL_PORT`; this listener is internal-only and must not be exposed directly to the Internet.
- The raw Telnet listener on `TCP_PROXY_PORT` remains available for local development and tightly controlled hobby/self‑hosted deployments where PROXY protocol is unnecessary; it is not a valid public player ingress in shared or player-facing environments.

When PROXY protocol is enabled, the TCP Proxy Service derives the real client IP from the PROXY header; Spring Cloud Gateway in turn derives `X-Client-IP` for Telnet sessions from the trusted `X-Proxy-Client-IP` header, as described in the Gateway header trust model. When PROXY protocol is not in use (for example local dev or tightly controlled self-hosted deployments), `TCP_PROXY_MAX_CONNECTIONS_PER_IP` and other per-IP heuristics are best-effort only and should be backed by higher-layer limits in Spring Cloud Gateway and the Game Session Service.

### Protocol handling and security

- Accepts and parses line-based input from raw TCP clients; Telnet option negotiation is minimal and optional so plain TCP clients with ANSI color codes work without additional configuration.
- Sanitizes incoming data and allows only a safe subset of **Telnet protocol commands** as outlined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Runs alongside Spring Cloud Gateway in the network **DMZ** so no client ever reaches internal services directly. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design).
- Supports Telnet-over-TLS when `TCP_PROXY_TLS_ENABLED` is set; certificates are provided via `TCP_PROXY_TLS_CERT` and `TCP_PROXY_TLS_KEY`. Plaintext Telnet is limited to local, automated-test, and explicitly private-network compatibility; player-facing deployments require TLS Telnet or the web client as defined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Telnet-over-TLS certificates (client ↔ proxy) are independent from the Proxy → Gateway WebSocket mutual TLS certificates (proxy ↔ Spring Cloud Gateway); they may reuse the same files in small deployments, but they are different trust surfaces.

### Bridging to the backend

- Normalizes the connection by proxying Telnet traffic through a WebSocket tunnel.
- Creates a WebSocket connection to Spring Cloud Gateway on behalf of the TCP client. In production this hop uses `wss://` with mutual TLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway); the detailed mTLS contract (required listener, SAN/hostname expectations, and certificate paths) lives in the TCP Proxy Service design’s **WebSocket mTLS to Spring Cloud Gateway** section, which should be treated as canonical for certificate wiring details.
- Forwards client identity to the backend using a gateway canonicalization model. The TCP Proxy Service supplies `X-Proxy-Client-IP` (derived from PROXY protocol when a Telnet edge proxy is enabled, or best-effort from the TCP peer address otherwise) and Spring Cloud Gateway sets the canonical `X-Client-IP` header after authenticating the TCP Proxy identity. Spring Cloud Gateway strips any `X-Client-IP`, `X-Game-Instance-Id`, `X-Tenant-Id`, and `X-Proxy-*` headers arriving directly from public clients, and only promotes proxy-supplied inputs when the connection is known to have traversed the TCP Proxy → Gateway path; this trust is enforced by the mTLS identity on the TCP Proxy → Gateway hop. Downstream services treat `X-Client-IP` as authoritative only because the gateway produced it.
- Proxies I/O between the TCP client and Spring Cloud Gateway.

### Buffering, reconnection, and observability

- Buffers active input while the client remains connected and discards it if the TCP connection drops; the proxy never replays Telnet commands after a disconnect.
- Telnet clients keep a sticky connection to the TCP Proxy Service; reconnection and session recovery are handled as described in [Reconnection Strategy](./system-architecture-reconnection.md).
- Disconnect handling is **layered**: the proxy cleans up Telnet sessions; Spring Cloud Gateway retains the client or proxy-facing gameplay WebSocket while performing ADR 0013's bounded rebind of a rebindable Game Session upstream, then closes with `1013/backend_unavailable` if recovery exhausts its window; and Game Session restores continuation authority from shared state. After an actual edge disconnect, clients use a fresh `/ws/game/**` connect token where applicable, re-`LOGIN`, and re-`PLAY`. The Telnet path does not support hidden reattachment after its distinct Proxy → Gateway gameplay WebSocket is lost: the proxy closes that Telnet connection rather than silently opening a fresh edge bridge behind the same client TCP socket.
- The proxy defines a `NotifyDisconnect` gRPC event so the Game Session Service can react quickly when Telnet clients drop. This stream is best-effort and **at-least-once**, and Game Session treats it as an idempotent, advisory hint rather than a source of truth for session liveness. Consumers key handling off `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore. The behaviour-level contract for this stream is summarised in the **NotifyDisconnect Behavioral Contract** section of [Reconnection Strategy](./system-architecture-reconnection.md#notifydisconnect-behavioral-contract-summary), while the TCP Proxy Service design’s **Service Interactions** section remains canonical for message fields, retry windows, and envelope context.
- Metrics are exported at `/actuator/prometheus` and tracing data is sent to the collector configured by `OTEL_ENDPOINT`. See [Logging & Monitoring](./system-architecture-logging-monitoring.md).
- Environment-specific tuning guidance for the TCP Proxy Service (connection caps, envelope budgets, and production hardening) is documented in the TCP Proxy Service design under **Tuning TCP Proxy for Different Environments**.

### Outbound Recovery Boundary

Resume affects gameplay identity and session binding, not transport continuity:

- Neither the TCP Proxy nor Gateway replays prior outbound text or optional extension traffic onto a newly reconnected client transport.
- After a successful reconnect and `PLAY`, Game Session may emit a bounded per-player transcript window followed by fresh state reconstruction output derived from current authoritative state (for example room description, prompt, status snapshot, or a newly generated structured projection) for the new transport.
- Allowed reconstruction output must be re-derived from current state at resume time; it must not be a byte-for-byte replay of previously queued outbound payloads from the old transport.
- If previously delivered content and newly derived reconstruction happen to look similar to a human reader, that is acceptable only because the content was regenerated from current state, not because the transport backlog was replayed.
- Prompt/status output remains a special output class rather than ordinary transcript text. Prompt lines should be coalesced after bursts of gameplay output and regenerated fresh for the new transport rather than copied into the reconnect transcript buffer. Current operator-default prompt behavior is surfaced in Game Session through `firemud.presentation.prompt.enabled`, `firemud.presentation.prompt.emit-after-reconnect-restore`, and `firemud.presentation.prompt.coalesce-window-ms`.
- Any future selected extension negotiates fresh per client transport connection as defined in [Classic MUD Client Extensions](./system-architecture-mud-client-protocol.md#reconnection-and-failure-fallback); resumed gameplay state must not assume that prior extension channels or package state still exist.

### WebSocket Bridge Configuration

The TCP Proxy Service acts as the bridge and speaks directly to Spring Cloud Gateway through the WebSocket route that also serves modern clients. The TCP Proxy Service uses the
`GATEWAY_WS_URL` environment variable so the proxy always connects to the `/ws/game/**` predicate shown in the
[Gateway Architecture](./system-architecture-gateway.md) document (`Path=/api/session/**,/ws/game/**`). This keeps the Telnet flow and the web client flow aligned:
they both traverse the same filters, metrics, and downstream `game-session-service` backend.

In production, set `GATEWAY_WS_URL` to the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mutual TLS and the gateway can authenticate the TCP Proxy identity before promoting any `X-Proxy-*` inputs.

`GATEWAY_WS_URL` is the **authoritative endpoint** for the TCP Proxy → Gateway WebSocket bridge; it is configured independently of the `FIREMUD_SERVICES_*` service-discovery overrides that other services and the gateway use for gRPC and HTTP routing. Changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` or related overrides does **not** automatically update the Telnet bridge; operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s internal WebSocket mTLS listener via their deployment configuration.

Set `GATEWAY_WS_URL` explicitly in every shared and player-facing environment; regardless of the value, the URL must point to a gateway route
whose path contains `/ws/game/**` (or the configured alias) so Telnet and WebSocket clients hit the identical entry point. When using `wss://`, the host portion of
`GATEWAY_WS_URL` must match a name present in the Gateway certificate’s SANs; pointing it at a bare IP or an unrelated hostname causes TLS validation to fail on the TCP
Proxy side and increments `tcpproxy.gateway.handshake.failures{reason="cert_validation"}`. For mTLS certificate loading and watcher details, see the TCP Proxy Service design’s WebSocket mTLS section.

Player-facing and local-development environments must bridge to the gameplay entry point so the gateway’s standard filters, metrics, and downstream routing apply consistently for Telnet and native WebSocket clients.

### TCP Flow Benefits

- Maintains full compatibility with legacy tools and the wider MUD ecosystem.
- Allows reuse of the same backend infrastructure and logic.
- Makes legacy clients first-class citizens in the platform.

---

## Unified Backend Session Logic

The [Game Session Service](./microservices/game-session-service/README.md) is the central component responsible for:

- Maintaining game session state per client connection.
- Interpreting and completing **system commands** (for example `LOGIN`, `LOGON`, and `PING`) and routing **gameplay commands** into the tick/command pipeline.
- Queuing gameplay commands, enforcing tick/region admission rules, and invoking the Game Logic Service to parse and resolve gameplay commands deterministically.
- Sending and receiving text streams in a line-based protocol format for both WebSocket and Telnet-bridged clients.
- Persisting session state in Redis to enable reconnect recovery.
- Manages disconnects, reconnections, and session cleanup.

> Whether a client is connected via WebSocket directly or tunneled through the TCP Proxy Service, the backend **treats all sessions the same**.

Where Game Session uses a stable session front-end surface that forwards work to an internal region or lease owner, that forwarding hop must preserve the same connection-level invariants that matter to clients: per-connection FIFO, bounded backpressure, and explicit failure propagation when forwarding cannot continue. Internal lease movement must not silently weaken the client-visible transport guarantees defined above.

When lease/epoch fencing fails after a command has been accepted at the session front-end, Game Session must not leave the outcome ambiguous. The implementation must choose one of two canonical outcomes:

- reject the command visibly before gameplay side effects occur, or
- retry internally behind idempotency/effect guards so at-most-once observable gameplay semantics are preserved.

If neither outcome can be guaranteed because ownership is ambiguous or forwarding continuity is lost, the session must fail visibly using the existing structured command-failure or reconnect path rather than silently dropping or partially applying the command.

---

## Recommended Telnet deployment modes

The exact Telnet configuration varies by environment, but recommended defaults are:

| Environment type | Public Telnet transport | Telnet edge proxy | Plaintext Telnet login policy |
| --- | --- | --- | --- |
| Local dev / CI | Plaintext to `TCP_PROXY_PORT` | Optional; often omitted | Allowed for protocol iteration; do not represent it as an account-factor-protected path. |
| Hobby / self‑hosted (single operator) | Telnet-over-TLS for any public player-facing endpoint; plaintext only on an explicitly private network | Required for public Telnet ingress; optional for private/local use | Public raw Telnet does not qualify as a supported player-facing deployment. |
| Player-facing staging / production | Telnet-over-TLS via edge proxy or web client | Required for public Telnet ingress | Do not expose public plaintext Telnet. |

These recommendations complement the detailed Telnet controls in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls) and the authentication flows in [Authentication & Authorization](./system-architecture-authentication.md). When in doubt, treat the Security Architecture and TCP Proxy Service design as canonical sources for Telnet hardening and update the bridge configuration here to match.

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Classic MUD Client Extensions (MCP Research)](./system-architecture-mud-client-protocol.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Infrastructure Overview](./infrastructure/README.md)
