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
- Spring Cloud Gateway restarts automatically re-establish backend WebSocket connections.

### WebSocket Flow Benefits

- Leverages Spring Cloud Gateway’s routing, auth, logging, and rate limiting.
- Ideal for web UIs, admin tools, or companion clients.

---

## Telnet / TCP Client Flow (Legacy Clients)

- Used by traditional MUD clients (e.g., MUDlet, TinTin++, GMud).
- Clients connect using raw TCP (typically Telnet-compatible) and are handled by a dedicated **TCP Proxy Service**.
- The TCP Proxy Service listens on port `2323` by default so Telnet clients can simply connect without additional configuration. This and the Spring Cloud Gateway WebSocket URL can be adjusted with the `TCP_PROXY_PORT` and `GATEWAY_WS_URL` environment variables described in the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables). See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for general configuration guidance.

### Protocol handling and security

- Accepts and parses line-based input from raw TCP clients; Telnet option negotiation is minimal and optional so plain TCP clients with ANSI color codes work without additional configuration.
- Sanitizes incoming data and allows only a safe subset of **Telnet protocol commands** as outlined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Runs alongside Spring Cloud Gateway in the network **DMZ** so no client ever reaches internal services directly. See [Security Architecture](./system-architecture-security.md#🌐-network-security--boundary-design).
- Supports Telnet-over-TLS when `TCP_PROXY_TLS_ENABLED` is set; certificates are provided via `TCP_PROXY_TLS_CERT` and `TCP_PROXY_TLS_KEY`.

### Bridging to the backend

- Normalizes the connection by proxying Telnet traffic through a WebSocket tunnel.
- Creates a WebSocket connection to Spring Cloud Gateway on behalf of the TCP client; forwarding uses mutual TLS for this hop.
- Forwards the client IP via `X-Client-IP` so the Game Session Service can enforce connection limits and rate limiting centrally.
- Proxies I/O between the TCP client and Spring Cloud Gateway.

### Buffering, reconnection, and observability

- Buffers active input while the client remains connected and discards it if the TCP connection drops.
- Telnet clients keep a sticky connection to the TCP Proxy Service; reconnection and session recovery are handled as described in [Reconnection Strategy](./system-architecture-reconnection.md).
- Disconnect handling is **layered**: the proxy cleans up Telnet sessions, Spring Cloud Gateway automatically recreates WebSocket backends, and the Game Session Service reloads state from Redis.
- The proxy defines gRPC events `NotifyDisconnect` and `PushBufferedInput` so the Game Session Service can recover Telnet sessions.
- Metrics are exported at `/actuator/prometheus` and tracing data is sent to the collector configured by `OTEL_ENDPOINT`. See [Logging & Monitoring](./system-architecture-logging-monitoring.md).

### WebSocket Bridge Configuration

The TCP Proxy Service acts as the bridge and speaks directly to Spring Cloud Gateway through the WebSocket route that also serves modern clients. The TCP Proxy Service uses the
`GATEWAY_WS_URL` environment variable (default `ws://spring-cloud-gateway:8080/ws/game`) so the proxy always connects to the `/ws/game/**` predicate shown in the
[Gateway Architecture](./system-architecture-gateway.md) document (`Path=/api/session/**,/ws/game/**`). This keeps the Telnet flow and the web client flow aligned:
they both traverse the same filters, metrics, and downstream `game-session-service` backend.

Override `GATEWAY_WS_URL` only when the Spring Cloud Gateway hostname or protocol differs from the default; regardless of the value, the URL must point to a gateway route
whose path contains `/ws/game/**` (or the configured alias) so Telnet and WebSocket clients hit the identical entry point.

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

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Infrastructure Overview](./infrastructure/README.md)
