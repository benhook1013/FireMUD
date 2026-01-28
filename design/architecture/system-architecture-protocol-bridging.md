# Protocol Bridging: WebSocket and Telnet (TCP)

This document describes how FireMUD supports **both modern and traditional MUD clients** by bridging two distinct communication protocols: **WebSocket** and **raw TCP (Telnet)**. Both are routed into a unified backend session service for shared logic and scalability.

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
- Forwarded to the [Game Session Service](./microservices/game-session-service/README.md), which maintains the gameplay session.
- When Spring Cloud Gateway or Game Session pods restart, **clients reconnect their WebSocket connections**, and Game Session uses Redis-backed session state to resume gameplay where possible as described in [Reconnection Strategy](./system-architecture-reconnection.md). The gateway does not maintain hidden, long‑lived WebSocket tunnels across its own restarts; it simply resumes routing once clients re‑establish connections.

### WebSocket Flow Benefits

- Leverages Spring Cloud Gateway’s routing, header enforcement and forwarding, logging, and rate limiting while leaving authentication and authorization decisions to backend services as described in [Authentication & Authorization](./system-architecture-authentication.md).
- Ideal for web UIs, admin tools, or companion clients.

---

## Telnet / TCP Client Flow (Legacy Clients)

> Note: This section intentionally summarizes the Telnet flow at a high level for system context. The canonical, detailed semantics for the Telnet `SESSION` envelope, header propagation, and related metrics live in the TCP Proxy Service design’s **Telnet Session Envelope & Event Metrics** section; treat that document as the source of truth when protocol details and edge cases matter.

- Used by traditional MUD clients (e.g., MUDlet, TinTin++, GMud).
- Clients connect using raw TCP (typically Telnet-compatible) and are handled by a dedicated **TCP Proxy Service**.
- The TCP Proxy Service listens on port `2323` by default so Telnet clients can simply connect without additional configuration. This and the Spring Cloud Gateway WebSocket URL can be adjusted with the `TCP_PROXY_PORT` and `GATEWAY_WS_URL` environment variables described in the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables). See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for general configuration guidance.
- Normal Telnet clients never need to send a `SESSION` envelope. They connect, issue `LOGIN`, and let the Game Session Service create or bind the session exactly as WebSocket clients do; `SESSION` is an **optional optimization** for advanced tools. This document intentionally does not redefine envelope or header rules; for the canonical `SESSION` envelope behaviour (including malformed/partial handling and header propagation), see the TCP Proxy design’s **Telnet Session Envelope & Event Metrics** section. `SESSION` envelopes are scoped to a single TCP connection: advanced clients that reconnect must resend `SESSION` if they want those hints/headers applied on the new connection, even when the underlying gameplay session resumes from Redis.

MCP negotiation and cord state are also scoped to a single TCP connection. When a Telnet client reconnects after any disconnect (including Gateway outages, TCP Proxy restarts, or client-side network loss), it must re-run MCP negotiation and re-open any required cords. Redis-backed gameplay session state (account/player identity, tick queues, cooldowns) is distinct from MCP/SESSION state and governs whether gameplay resumes or starts fresh. See [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery) for details.

---

## Ordering & Delivery Invariants

The combined TCP Proxy → Spring Cloud Gateway → Game Session path preserves a clear set of ordering and delivery guarantees for **gameplay command streams from clients into Game Session**:

- **Per-connection FIFO where delivered** – For a given Telnet/TCP or WebSocket connection, gameplay commands and text lines are forwarded to the Game Session Service in the same order they were accepted by the edge (TCP Proxy for Telnet, Gateway for WebSocket). No component in this client → Game Session command path intentionally reorders gameplay messages or generates duplicates.
- **At-most-once delivery with bounded loss (gameplay commands)** – The edge protocol path for gameplay commands is **at most once**: once a command on a given connection is dropped by any edge layer (for example due to buffer ceilings or abuse limits), it is not retried or replayed by that layer. “Bounded” here means that potential loss is limited to the commands still resident in that layer’s per-connection buffers or reconnect windows at the time of failure; there is no implicit replay across disconnects. Higher-level retries and replay semantics live entirely in Game Session and domain services; see [Transactions & Idempotency](./system-architecture-transactions.md) for the canonical idempotency model.
- **At-least-once delivery (edge event sinks)** – Internal gRPC event sinks associated with the edge (for example the TCP Proxy’s `NotifyDisconnect` stream into Game Session) are intentionally **at-least-once** and must be consumed idempotently with respect to their idempotency keys, as described in [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics). These streams are advisory hints that complement, but do not change, the at‑most‑once guarantees for client gameplay commands.
- **Explicit drop conditions (edge layers)** – Commands or lines may be dropped under clearly defined conditions, including:
  - TCP Proxy per-connection ceilings such as `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` when the WebSocket bridge is unavailable within the configured reconnect window;
  - MCP-specific budgets (for example control-line rate and `_data-tag` continuation limits) that discard excess MCP control lines while keeping the connection open, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md);
  - Abuse and safety limits in the TCP Proxy Service (oversize lines, malformed envelopes) where the proxy either discards input or closes the connection;
  - Client-side disconnects or network loss (TCP/WebSocket) where the edge cannot reliably determine whether the last few bytes were delivered to the client.
- **No implicit replay on reconnect** – Neither Spring Cloud Gateway nor the TCP Proxy Service replays gameplay commands or MCP messages across reconnects. After any disconnect, clients must resend `LOGIN` (and any optional `SESSION`/MCP negotiation) and rely on Game Session and Redis to resume or start fresh.

When any layer drops input due to its own limits or backpressure protections, it must either close the connection with a clear, human-readable message or (for WebSocket clients) send an explicit error/close reason before terminating the session; edge components do **not** silently discard gameplay commands while keeping a connection that appears healthy to the client.

Domain services treat incoming commands as **idempotent with respect to their effect identifiers** so that retries at the Game Session layer (for example tick replays) can safely handle duplicates even though the edge path is at-most-once. See [Transactions & Idempotency](./system-architecture-transactions.md) and [Redis Architecture](./system-architecture-redis.md) for the underlying invariants.

---

## Backpressure & Slow Clients

Backpressure and slow-client handling are split across layers so that the platform remains robust without silently corrupting gameplay streams. Responsibilities and observability are intentionally divided between the TCP Proxy, Spring Cloud Gateway, and Game Session:

- **Telnet/TCP clients (TCP Proxy Service)**
  - The TCP Proxy Service enforces a strict per-socket output buffer limit. When a Telnet client cannot keep up with outbound traffic and the proxy’s output buffer fills, the proxy closes the Telnet connection with a clear message rather than silently dropping gameplay lines in the middle of a session. Relevant events are surfaced via metrics such as `tcpproxy.telnet.discarded` and per-connection counters; see the TCP Proxy Service design’s **Connection Limits and Abuse Protection** section.
  - Input-side backpressure is governed by per-connection and per-IP line-rate budgets, as well as MCP-specific limits. Excess input beyond these budgets may be dropped (for example MCP control lines over budget) or cause the proxy to close the connection for sustained abuse. The Telnet degraded runbook documents how operators should interpret these metrics and when to adjust limits vs block abusive sources.
- **WebSocket clients (Gateway / WebSocket container)**
  - Spring Cloud Gateway (or its underlying WebSocket container) is responsible for **network-level slow-client detection** on `/ws/game/**`. When a WebSocket client is slow to read and outbound send buffers for that connection fill or repeatedly time out, the gateway closes the WebSocket connection rather than silently discarding frames.
  - Gateway-side slow-client closures should use the standard close codes from [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts) (typically `1008` with `policy_violation` or `1013` with `backend_unavailable` when a downstream hop is persistently unhealthy). Gateway metrics such as `gateway.websocket.slow_client_closes` and route-level close-reason counters allow operators to distinguish network-level backpressure from other failures.
  - Spring Cloud Gateway’s Redis-backed rate limiting focuses on **connection establishment and HTTP requests**, not individual WebSocket frames, as described in [Gateway Architecture](./system-architecture-gateway.md#rate-limiting--abuse-protection). Once a WebSocket is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level frame-by-frame throttling.
- **WebSocket clients (Game Session Service)**
  - Game Session provides **domain-level backpressure**. For gameplay WebSocket sessions, it applies a per-session outbound queue limit and send-timeout budget on its side of the connection. If either is exceeded (for example because a client has stopped reading or a downstream hop between Game Session and the gateway is persistently slow), Game Session closes the session with an explicit close reason instead of allowing the queue to grow without bound or dropping frames while pretending the connection is healthy.
  - For inbound overload (for example, a misbehaving client sending commands far beyond expected rates), Game Session either rejects excess commands with visible error messages or terminates the session after sustained abuse; it does not accept and then silently discard gameplay input. Domain-level backpressure and abuse closures are surfaced via metrics such as `gamesession.connection.closed{reason="backpressure"|"rate_limit"}` and command‑level error counters.
- **Client expectations**
  - When WebSocket connections close due to slow-client behavior, abuse, network issues, or backend unavailability, clients must reconnect and re-`LOGIN`. Game Session’s Redis-backed state determines whether gameplay resumes or starts fresh, per [Reconnection Strategy](./system-architecture-reconnection.md).

This model favors **clear closures over silent drops** when a client cannot keep up and provides enough metrics at each layer for operators to identify whether the TCP Proxy, Gateway, or Game Session is enforcing backpressure in a given incident.

### Telnet edge proxy and PROXY protocol

In production and any environment where player IP preservation matters, public Telnet is expected to terminate on a dedicated **Telnet edge proxy** (for example HAProxy) that forwards to the TCP Proxy Service using **PROXY protocol** on an internal-only listener. In this topology:

- External clients connect to the Telnet edge proxy on the public `LoadBalancer` / ingress.
- The edge proxy forwards to the TCP Proxy Service using PROXY protocol on the port configured by `TCP_PROXY_PROXY_PROTOCOL_PORT`; this listener is internal-only and must not be exposed directly to the Internet.
- The raw Telnet listener on `TCP_PROXY_PORT` remains available for local development and tightly controlled hobby/self‑hosted deployments where PROXY protocol is unnecessary; in player-facing environments it should either be disabled or exposed only behind the Telnet edge proxy.

When PROXY protocol is enabled, the TCP Proxy Service derives the real client IP from the PROXY header; Spring Cloud Gateway in turn derives `X-Client-IP` for Telnet sessions from the trusted `X-Proxy-Client-IP` header, as described in the Gateway header trust model. When PROXY protocol is not in use (for example local dev), `TCP_PROXY_MAX_CONNECTIONS_PER_IP` and other per-IP heuristics are best-effort only and should be backed by higher-layer limits in Spring Cloud Gateway and the Game Session Service.

### Protocol handling and security

- Accepts and parses line-based input from raw TCP clients; Telnet option negotiation is minimal and optional so plain TCP clients with ANSI color codes work without additional configuration.
- Sanitizes incoming data and allows only a safe subset of **Telnet protocol commands** as outlined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Runs alongside Spring Cloud Gateway in the network **DMZ** so no client ever reaches internal services directly. See [Security Architecture](./system-architecture-security.md#🌐-network-security--boundary-design).
- Supports Telnet-over-TLS when `TCP_PROXY_TLS_ENABLED` is set; certificates are provided via `TCP_PROXY_TLS_CERT` and `TCP_PROXY_TLS_KEY`. The detailed plaintext Telnet security rules (2FA requirements, per-account opt-in, and landing-menu warnings) are defined in the **Telnet Command Handling and Controls** section of [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls); this document summarizes only the high-level flow.
- Telnet-over-TLS certificates (client ↔ proxy) are independent from the Proxy → Gateway WebSocket mutual TLS certificates (proxy ↔ Spring Cloud Gateway); they may reuse the same files in small deployments, but they are different trust surfaces.

### Bridging to the backend

- Normalizes the connection by proxying Telnet traffic through a WebSocket tunnel.
- Creates a WebSocket connection to Spring Cloud Gateway on behalf of the TCP client. In production this hop uses `wss://` with mutual TLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway); the detailed mTLS contract (required listener, SAN/hostname expectations, and certificate paths) lives in the TCP Proxy Service design’s **WebSocket mTLS to Spring Cloud Gateway** section, which should be treated as canonical for certificate wiring and rollout details.
- Forwards client identity to the backend using a gateway canonicalization model. The TCP Proxy Service supplies `X-Proxy-Client-IP` (derived from PROXY protocol when a Telnet edge proxy is enabled, or best-effort from the TCP peer address otherwise) and Spring Cloud Gateway sets the canonical `X-Client-IP` header after authenticating the TCP Proxy identity. Spring Cloud Gateway strips any `X-Client-IP`, `X-Session-Id`, `X-Tenant-Id`, and `X-Proxy-*` headers arriving directly from public clients, and only promotes proxy-supplied inputs when the connection is known to have traversed the TCP Proxy → Gateway path; this trust is enforced by the mTLS identity on the TCP Proxy → Gateway hop. Downstream services treat `X-Client-IP` as authoritative only because the gateway produced it.
- Proxies I/O between the TCP client and Spring Cloud Gateway.

### Buffering, reconnection, and observability

- Buffers active input while the client remains connected and discards it if the TCP connection drops; the proxy never replays Telnet commands after a disconnect.
- Telnet clients keep a sticky connection to the TCP Proxy Service; reconnection and session recovery are handled as described in [Reconnection Strategy](./system-architecture-reconnection.md).
- Disconnect handling is **layered**: the proxy cleans up Telnet sessions, Spring Cloud Gateway automatically recreates WebSocket backends, and the Game Session Service reloads state from Redis-backed session and command queues.
- The proxy defines a `NotifyDisconnect` gRPC event so the Game Session Service can react quickly when Telnet clients drop. This stream is best-effort and **at-least-once**, and Game Session treats it as an idempotent, advisory hint rather than a source of truth for session liveness. Consumers key handling off `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore. For the full canonical `NotifyDisconnect` contract (keys, retry window, and envelope context), see the TCP Proxy Service design’s **Service Interactions** section.
- Metrics are exported at `/actuator/prometheus` and tracing data is sent to the collector configured by `OTEL_ENDPOINT`. See [Logging & Monitoring](./system-architecture-logging-monitoring.md).
- Environment-specific tuning guidance for the TCP Proxy Service (connection caps, envelope budgets, and production hardening) is documented in the TCP Proxy Service design under **Tuning TCP Proxy for Different Environments**.

### WebSocket Bridge Configuration

The TCP Proxy Service acts as the bridge and speaks directly to Spring Cloud Gateway through the WebSocket route that also serves modern clients. The TCP Proxy Service uses the
`GATEWAY_WS_URL` environment variable (default `ws://spring-cloud-gateway:8080/ws/game`) so the proxy always connects to the `/ws/game/**` predicate shown in the
[Gateway Architecture](./system-architecture-gateway.md) document (`Path=/api/session/**,/ws/game/**`). This keeps the Telnet flow and the web client flow aligned:
they both traverse the same filters, metrics, and downstream `game-session-service` backend.

In production, set `GATEWAY_WS_URL` to the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mutual TLS and the gateway can authenticate the TCP Proxy identity before promoting any `X-Proxy-*` inputs.

`GATEWAY_WS_URL` is the **authoritative endpoint** for the TCP Proxy → Gateway WebSocket bridge; it is configured independently of the `FIREMUD_SERVICES_*` service-discovery overrides that other services and the gateway use for gRPC and HTTP routing. Changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` or related overrides does **not** automatically update the Telnet bridge; operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s internal WebSocket mTLS listener via their deployment configuration.

Override `GATEWAY_WS_URL` only when the Spring Cloud Gateway hostname or protocol differs from the default; regardless of the value, the URL must point to a gateway route
whose path contains `/ws/game/**` (or the configured alias) so Telnet and WebSocket clients hit the identical entry point. When using `wss://`, the host portion of
`GATEWAY_WS_URL` must match a name present in the Gateway certificate’s SANs; pointing it at a bare IP or an unrelated hostname causes TLS validation to fail on the TCP
Proxy side and increments `tcpproxy.gateway.handshake.failures{reason="cert_validation"}`. For mTLS certificate loading and watcher details, see the TCP Proxy Service design’s WebSocket mTLS section.

### TCP Flow Benefits

- Maintains full compatibility with legacy tools and the wider MUD ecosystem.
- Allows reuse of the same backend infrastructure and logic.
- Makes legacy clients first-class citizens in the platform.

---

## Unified Backend Session Logic

The [Game Session Service](./microservices/game-session-service/README.md) is the central component responsible for:

- Maintaining game session state per client connection.
- Handling command parsing and game world interaction.
- Sending and receiving text streams in a line-based protocol format.
- Persists session state in Redis to enable reconnect recovery.
- Manages disconnects, reconnections, and session cleanup.

> Whether a client is connected via WebSocket directly or tunneled through the TCP Proxy Service, the backend **treats all sessions the same**.

---

## Recommended Telnet deployment modes

The exact Telnet configuration varies by environment, but recommended defaults are:

| Environment type | Public Telnet transport | Telnet edge proxy | Plaintext Telnet login policy |
| --- | --- | --- | --- |
| Local dev / CI | Plaintext to `TCP_PROXY_PORT` | Optional; often omitted | `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` may be `false` while iterating; per-account “allow plaintext Telnet login” flags are still required. |
| Hobby / self‑hosted (single operator) | Prefer Telnet‑over‑TLS; plaintext permitted for legacy clients | Recommended but not strictly required; can front the TCP Proxy directly if PROXY protocol and per-IP limits are not needed | Keep `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP=true` and require both the per-account flag and 2FA for plaintext logins where possible. |
| Player‑facing staging / production | Prefer Telnet‑over‑TLS via edge proxy; plaintext supported only as a hardened legacy channel | Required: Telnet edge proxy terminates public Telnet and forwards via PROXY protocol to the TCP Proxy Service | `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` **must remain `true`**; plaintext Telnet is allowed only for accounts that have explicitly opted in and enabled 2FA, with all other players using TLS Telnet or the web client. |

These recommendations complement the detailed Telnet controls and 2FA rules in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls) and the authentication flows in [Authentication & Authorization](./system-architecture-authentication.md). When in doubt, treat the Security Architecture and TCP Proxy Service design as canonical sources for Telnet hardening and update the bridge configuration here to match.

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Infrastructure Overview](./infrastructure/README.md)
