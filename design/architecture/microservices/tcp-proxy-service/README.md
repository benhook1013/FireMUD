# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.
The OpenAPI specification for the `/ping` health endpoint lives in `services/tcp-proxy-service/src/main/resources/openapi.yaml`.
This service also exposes an **internal-only gRPC API** (for `Ping` and `NotifyDisconnect`) used by other services and tooling; it is never published through Spring Cloud Gateway.

## Implementation Status

This document describes the target-state behaviour of the TCP Proxy Service.
Where implementation is still catching up, treat the design below as the source
of truth and reconcile code/tests accordingly.

| Area | Target behaviour | Current status | Tracked in |
| --- | --- | --- | --- |
| Telnet login-first flow (without `SESSION`) | All Telnet clients issue `LOGIN` and may optionally send a `SESSION` envelope for advanced attach-to-session flows; `SESSION` is always optional and Telnet shares the same login pipeline as WebSocket clients. | Behaviour is implemented as described; some older tests and smoke scripts may still assume `SESSION` is required. | Align any remaining flows with this doc when you encounter discrepancies rather than changing the protocol. |
| Proxy → Gateway WebSocket mTLS | Telnet → Gateway WebSocket client connects over `wss://` using mutual TLS and the shared `FIREMUD_GRPC_*` certificate paths. | Not yet fully implemented or deployed; current client may connect without client certificates and rely on default JDK TLS when `wss://` is used. | `design/project-management/task-list-tcp-proxy-service.md` (mTLS task). |
| MCP negotiation and Telnet heuristics | MCP 2.1 negotiation, extended Telnet abuse heuristics, and advanced connection throttling are enforced at the proxy edge while keeping MCP payloads intact. | Partially implemented; some heuristics and MCP handling are still being hardened. | `design/project-management/task-list-tcp-proxy-service.md` (MCP and abuse/heuristics tasks). |
| Connection limits and abuse protection | Connection caps, idle timeouts, input size limits, and malformed-envelope budgets protect the DMZ boundary, with metrics such as `tcpproxy.connections.limit.exceeded`. | Core limit handling is implemented; tuning and additional metrics may evolve as production behaviour is observed. | `design/project-management/task-list-tcp-proxy-service.md` (connection management and security sections). |

### Responsibilities

- Accept Telnet connections and perform protocol negotiation
- Proxy buffered input to Spring Cloud Gateway as WebSocket frames while the
  Telnet connection remains open
- Provide graceful disconnect and reconnection handling

## Architecture / Design Notes

- Spring Boot service hosting a lightweight Netty-based Telnet server.
  - Buffers incoming input while the client remains connected and discards it if the
    TCP session drops. Buffers are strictly connection-local and are not replayed
    across reconnects; session recovery and any command replay are handled by the
    Game Session Service using Redis-backed state.
- Handles Telnet negotiation and character encoding quirks.
- Negotiates the Mud Client Protocol (MCP) when supported. See [Mud Client Protocol (MCP) Support](../../system-architecture-mud-client-protocol.md).
- Integrates with the [Reconnection Strategy](../../system-architecture-reconnection.md) so backend session state can be resumed when clients reconnect and send `LOGIN` again; Telnet clients always reconnect and reauthenticate after any disconnect.
- Can optionally terminate Telnet-over-TLS while always supporting raw Telnet
  on the configured TCP port for classic clients (for example the Windows
  `telnet` command). Forwarding to the gateway uses WebSocket connections and
  supports mutual TLS. See [Security Architecture](../../system-architecture-security.md).
- Runs in the network DMZ. All gameplay traffic is forwarded only via WebSocket through Spring Cloud Gateway; the proxy uses a narrow, mTLS-protected gRPC link to the Game Session Service exclusively for `NotifyDisconnect` lifecycle events. This gRPC surface is **internal-only** and is secured using the same `FIREMUD_GRPC_*` certificate paths as other services. Telnet‑side TLS (if enabled) is configured via `TCP_PROXY_TLS_*` and is independent from the Proxy → Gateway WebSocket mTLS settings, even if they share certificate files.
- Sanitizes incoming Telnet data and enforces a whitelist of
  **Telnet protocol commands** as described in the
  [Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
- Forwards client IPs via `X-Client-IP` so central throttling occurs in the Game Session Service. The proxy always overwrites any incoming `X-Client-IP` header on Telnet traffic so downstream services can treat this value as “set by the TCP Proxy only,” and the Gateway combines it with its own `X-Forwarded-For` handling on the WebSocket side. Optional TLS termination is controlled by `TCP_PROXY_TLS_ENABLED`.
- Performs basic sanitization and minimal per-connection safety checks (idle timeout, buffer depth limits, and session handshake rules). Cross-tenant rate limiting and abuse policies are enforced by Spring Cloud Gateway and the Game Session Service.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Redis Role and Prefixes

- The TCP Proxy Service does **not** access Redis directly. It treats all Redis-backed session and coordination state as an implementation detail of the Game Session Service and only participates via WebSocket forwarding to Spring Cloud Gateway and the internal `NotifyDisconnect` gRPC surface.

### Reconnection Behaviour at the Proxy Layer

The TCP Proxy Service treats each Telnet TCP connection as independent and keeps
reconnection logic centralized in the Game Session Service:

- Multiple Telnet connections using the same `{sessionId, tenantId}` are allowed.
  The proxy simply forwards commands for each connection; Game Session enforces
  the “one session per character” behaviour by applying its takeover rules when
  a second client logs in as the same character, so only one active session per
  character is allowed at any time.
- The proxy does not emit a positive “reconnect” event. It only calls
  `NotifyDisconnect` when a Telnet socket closes; Game Session interprets a
  subsequent `LOGIN` (with or without a `SESSION` envelope) as either a fresh
  login or a resume/takeover based on Redis session state.
- After `NotifyDisconnect`, session state remains eligible for reconnection
  until the configured `session_expiration_ms` window elapses; see the
  [Reconnection Strategy](../../system-architecture-reconnection.md) and
  [Environment & Secrets](../../infrastructure/environment-and-secrets.md#authentication)
  for details on how this window is derived.

### Connection Limits and Abuse Protection

Because the TCP Proxy Service sits in the network DMZ, it enforces hard resource
ceilings even though tenant-aware throttling and rich abuse policies live in
Spring Cloud Gateway and the Game Session Service. Limits are configured via the
environment variables described in the **Environment Variables** section; existing
constants such as the `MAX_BUFFER_DEPTH` value in `TelnetServerHandler` serve as
implementation defaults when the corresponding configuration is unset. When code
and configuration diverge, treat this document and its environment variables as
the source of truth and update code/tests to match.

- **Connection limits**
  - A global concurrent connection cap (for example `TCP_PROXY_MAX_CONNECTIONS`)
    prevents the proxy from exhausting sockets or file descriptors. When the
    limit is reached, new connections are rejected and counted via a dedicated
    metric (for example `tcpproxy.connections.rejected{reason="max_exceeded"}`).
  - A per-client-IP cap (for example `TCP_PROXY_MAX_CONNECTIONS_PER_IP`) guards
    against a single address consuming the entire connection budget.
- **Slow/abusive client handling**
  - Read idle timeouts and maximum connection lifetimes close connections that
    send no data or linger indefinitely (for example `tcpproxy.connections.closed{reason="idle_timeout"}`),
    limiting exposure to slowloris-style attacks.
  - Backpressure-aware write handling avoids unbounded buffer growth when
    sending data to very slow clients, closing the socket once thresholds are
    exceeded.
- **Input size and shape limits**
  - Maximum line and envelope length constraints, configured via
    `TCP_PROXY_MAX_LINE_BYTES`, truncate overly long input and, after repeated
    violations, hard-close the connection with a
    `tcpproxy.connections.closed{reason="line_too_long"}` event. The existing
    `MAX_BUFFER_DEPTH` constant in `TelnetServerHandler` acts as an additional
    safety net: once the in-memory buffer ceiling is reached, the connection is
    closed and `tcpproxy.telnet.discarded` is incremented even if
    `TCP_PROXY_MAX_LINE_BYTES` is not hit.
  - Malformed `SESSION` envelopes increment a dedicated counter. When the number
    of malformed envelopes on a single connection exceeds
    `TCP_PROXY_MAX_MALFORMED_ENVELOPES`, the proxy closes the connection with a
    `tcpproxy.connections.closed{reason="malformed_envelope_limit"}` event.
    Clients that choose to use `SESSION` should treat this as a per-connection
    budget for mistakes and either send a correct envelope or continue with
    `LOGIN` only after repeated failures.
- **Metrics and alerting**
  - These limits are instrumented via Micrometer and exposed at
    `/actuator/prometheus` alongside the existing `tcpproxy.connection.*`
    metrics, so standard dashboards and alerts can detect abuse patterns at the
    TCP edge. See [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
    for how these metrics feed into Prometheus/Grafana.
- **Limit coordination across layers**
  - The TCP Proxy Service’s connection caps, idle timeouts, and buffer depth
    limits are treated as **hard ceilings** at the network edge to protect
    sockets and memory in the DMZ.
  - Spring Cloud Gateway’s Redis-backed rate limiting and the Game Session
    Service’s per-IP and per-session command limits are **policy controls**
    focused on fairness and gameplay abuse, applied after traffic has passed
    the proxy.
  - When tuning thresholds, operators should treat the proxy as the first line
    of defence against obvious connection floods while ensuring that normal
    player behaviour is primarily shaped by Gateway and Game Session limits
    rather than repeated proxy disconnects.

## Key Features

- **Telnet Compatibility** — accepts standard MUD clients over TCP.
- **WebSocket Bridging** — forwards all traffic to the gateway via WebSocket.
- **Connection Buffering** — temporarily queues input to handle latency.
- **Graceful Disconnects** — informs the Game Session Service when a client drops.

### Recommended Telnet Client Flows

- **Minimal / legacy client (no `SESSION`)**
  - Connect to the TCP Proxy Service.
  - Send a `LOGIN` command with the appropriate credentials (and optional OTP where required).
  - Send gameplay commands (`LOOK`, `SAY`, movement, etc.) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Advanced client (attach/resume with `SESSION` + `LOGIN`)**
  - Obtain a `sessionId` and `tenantId` from the Game Session Service (for example via `POST /sessions`) or another first-party API.
  - Connect to the TCP Proxy Service.
  - Immediately send a `SESSION <sessionId> <tenantId>` (or `SESSION <sessionId>:<tenantId>`) envelope as the first line on the connection.
  - Send a `LOGIN` command over the same connection.
  - Continue with gameplay commands as normal.
  - Game Session evaluates the combination of `SESSION` and `LOGIN` against Redis-backed session state and the authentication rules described in the [Authentication & Authorization](../../system-architecture-authentication.md) and [Reconnection Strategy](../../system-architecture-reconnection.md) documents to decide whether to resume a prior session or start a fresh one.

### Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same
account or `SESSION` envelope. The proxy forwards traffic for every TCP
connection independently; visible behaviour is governed by the Game Session
Service’s “one session per character” rules described in the
[Reconnection Strategy](../../system-architecture-reconnection.md#resume-vs-reload-scenarios)
and [Authentication & Authorization](../../system-architecture-authentication.md#👥-multi-client-behavior-and-session-takeover):

- **Two Telnet windows without `SESSION`**
  - Window A connects and issues `LOGIN`. Gameplay continues normally.
  - Window B connects and issues `LOGIN` for the same character. Game Session
    treats this as a takeover: the old session is terminated and the new window
    becomes authoritative. Window A is disconnected and stops receiving updates.
  - If Window A reconnects and logs in again, it in turn takes over from
    Window B. At any moment only one connection actively controls the
    character; there is no concurrent “split control” even though the proxy
    forwards traffic from both TCP connections.
- **Two Telnet windows with the same `{sessionId, tenantId}` envelope**
  - Both windows connect to the TCP Proxy Service and send
    `SESSION <sessionId> <tenantId>` followed by `LOGIN`.
  - The proxy forwards both flows independently; Game Session binds
    socket-level control to whichever `LOGIN` most recently succeeded for the
    character backing that session. Earlier connections may be disconnected or
    treated as superseded by the takeover logic depending on the exact timing.
  - Clients should design UI/behaviour assuming that only one window at a time
    has active control of the character and that reconnecting or logging in
    from another window will move control rather than creating a second
    simultaneous session.

### Data Flow

- TCP connections are accepted on a dedicated port and proxied to Spring Cloud Gateway
  using a lightweight WebSocket bridge.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the connection is lost, the in-memory queue is cleared and no Telnet
  commands are replayed by the proxy. Reconnection hooks notify downstream
  services so the Game Session Service can resume gameplay from Redis-backed
  session state where available.
- All gameplay commands, including the mandatory `LOGIN` command, are forwarded
  verbatim over the WebSocket bridge, so Spring Cloud Gateway and the Game Session Service
  see the same protocol lines as native WebSocket clients. Telnet handlers only
  parse the optional initial `SESSION` envelope for first-party/advanced clients;
  everything else is sent to the canonical gameplay route described in
  [Gameplay WebSocket Route](../../system-architecture-gateway.md#gameplay-websocket-route),
  ensuring the shared login/resume pipeline is used without Telnet-specific translations.

### Service Interactions

The proxy does not expose a public client API. Instead it emits a gRPC event
for internal coordination:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client
  drops so the session may be suspended.

These events let the Game Session Service resume suspended sessions and deliver
any **Redis-backed queued commands** owned by the Game Session Service. The TCP
Proxy never replays Telnet input after a disconnect; connection-local buffers
are cleared as soon as the TCP session closes. The `NotifyDisconnect` event is
therefore a **best-effort, at-least-once** lifecycle signal keyed by
`{sessionId, tenantId}` rather than a request to re-run gameplay commands. Game
Session must treat `NotifyDisconnect` as strictly advisory and idempotent and must
always be able to detect disconnects without relying on this stream as a single
source of truth:

- If a `NotifyDisconnect` arrives after a new session has already been
  established for the same `{sessionId, tenantId}`, the newer session remains
  authoritative and the event is ignored for state changes.
- Missing events are tolerated because disconnects are also detected at the
  Gateway and Game Session layers; recovery logic must not rely on this signal
  as a single source of truth.
- Duplicate events for the same `{sessionId, tenantId}` are handled without
  side effects so the stream can be retried safely.

Their definitions live in
[`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

### Telnet Session Envelope & Event Metrics

This section is the canonical reference for the TCP Proxy Service’s `SESSION` +
`LOGIN` semantics. Other documents (such as the Reconnection Strategy, Protocol
Bridging, and user-journey flows) intentionally summarize the behaviour at a
higher level and should link back here rather than redefining the protocol.

The `SESSION` envelope is an optional optimization used by first-party and other
advanced Telnet clients to attach to an existing session before `LOGIN`. Normal
Telnet clients never need to send `SESSION`; they simply issue `LOGIN` and let
the Game Session Service create or bind the session exactly as WebSocket
clients do.

When used, the envelope is a plain-text line that starts with `SESSION`
(case-insensitive) followed by either `SESSION <sessionId> <tenantId>` or the
more compact `SESSION <sessionId>:<tenantId>`. The
`TelnetSessionContext.captureFromEnvelope` helper trims and upper-cases the
prefix, splits on the first colon or whitespace, and ignores envelopes that are
missing either identifier. Once captured, `sessionId` and `tenantId` are
propagated over the WebSocket bridge (`X-Session-Id` and `X-Tenant-Id` headers)
and also drive the event and metric generation below.

#### Where `sessionId` and `tenantId` come from

- Cross-service tests and advanced clients typically obtain a `sessionId` by calling the Game Session REST API (for example `POST /sessions`) and then send `SESSION <sessionId> <tenantId>` when attaching to that session. See:
  - `design/project-management/look-smoke-tests.md` (WebSocket and Telnet flows)
  - `design/project-management/look-cross-service-tests.md`
  - `design/architecture/system-architecture-authentication.md`
- Simpler Telnet clients do not send `SESSION`. They connect, issue `LOGIN`,
  and rely on the Game Session Service to derive session and tenant context from
  the login flow, matching the behavior of WebSocket clients.

#### Envelope and command handling rules

- **Without any `SESSION` envelope** – all lines, including `LOGIN`, are forwarded
  verbatim to the gateway; the proxy does not drop or delay gameplay commands.
- **With a valid `SESSION` envelope** – once the first valid `SESSION` line is
  parsed, the connection is bound to that `{sessionId, tenantId}` pair for its
  lifetime and those identifiers are propagated via headers and metrics. Subsequent
  lines beginning with `SESSION` are treated as normal text and do not rebind
  the connection under normal operation.
- **Malformed `SESSION` lines** – if either `sessionId` or `tenantId` is missing
  or cannot be parsed, the line is logged and ignored; no error is sent back to
  the client. Clients that choose to use `SESSION` must resend a correct envelope
  or proceed with `LOGIN` only. Each malformed envelope also increments a per‑connection
  counter; once the number of malformed envelopes exceeds `TCP_PROXY_MAX_MALFORMED_ENVELOPES`,
  the proxy closes the connection as abusive and emits the corresponding metrics.
- **Diagnostics mode (future enhancement)** – a debug/diagnostics mode may surface
  explicit warnings or errors for malformed or repeated `SESSION` envelopes to
  help advanced Telnet clients detect configuration issues. Until that mode ships,
  the behavior above (one-shot binding, silent ignores up to the configured malformed‑envelope budget) is considered canonical.

#### Security considerations for `{sessionId, tenantId}`

The proxy treats `sessionId` and `tenantId` from the `SESSION` envelope as
client-provided claims, not trusted facts. It forwards them as headers and
metrics context, but the Game Session Service is responsible for enforcing
multi-tenant safety:

- `tenantId` is validated against the authenticated account’s allowed tenants
  during `LOGIN` and subsequent session binding.
- Session ownership is checked so a client cannot bind to or resume another
  user’s session, even if it guesses a valid `sessionId`.
- Any mismatch between the envelope’s `{sessionId, tenantId}` and the account’s
  known sessions/tenants is treated as a cross-tenant hijack attempt, rejected,
  and logged with enough context for audit/alerting (without leaking sensitive
  credentials).

Metrics give observability into each Telnet connection while keeping
Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}` increments on
  connection open/close, with only low-cardinality labels (such as `type`).
- `tcpproxy.connection.duration` (a Micrometer `Timer`) records the wall-clock
  lifetime of sockets so dashboards can highlight long-running connections
  without embedding per-session identifiers in label values.
- `grpc.app_error{code="<code>"}` is incremented whenever the
  `TcpProxyEventService` observes an error from `NotifyDisconnect`, with
  `code` drawn from a bounded enum.

Detailed identifiers such as `sessionId` and client IP are captured in
structured logs (for example JSON fields) and in tracing context, not as
Prometheus label values. Operators correlate individual sessions using logs and
traces, while metrics remain suitable for aggregation and alerting in both
small hobby deployments and larger clusters.

To avoid label blow-up in multi-tenant clusters, `tenantId` is intentionally
omitted from all proxy metrics by default. For very small, single-tenant or
single-admin deployments, operators may temporarily enable per-tenant metrics
in custom dashboards by adding `tenantId` as an opt-in label on a subset of
meters and keeping those series in a short-retention or dedicated Prometheus
instance. Even in that mode, `tenantId` labels should be treated as a
diagnostic tool rather than a permanent part of the core monitoring surface.

### Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of
**Telnet protocol commands** as described in the
[Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
This avoids implementing the full Telnet specification while still protecting
against malformed negotiation sequences and other legacy edge cases.

Mud Client Protocol (MCP) 2.1 negotiation and messages are carried over the
line-based text channel (for example `#$#` control lines) and are not affected
by the low-level Telnet command whitelist. Unsupported Telnet options outside
the `ALLOWED_COMMANDS` set are discarded, but MCP control lines and payloads
are forwarded verbatim to the gateway as sanitized text. MCP-capable clients
should therefore expect their standard MCP exchanges to arrive intact while not
relying on arbitrary Telnet option negotiation beyond the documented command
subset.

Example filtering logic from the Telnet sanitization layer (currently implemented by `TelnetServerHandler`):

```java
private static final byte IAC = (byte) 255;
private static final Set<Byte> ALLOWED_COMMANDS =
    Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);
```

Commands outside this list are discarded and only sanitized printable characters are forwarded to the gateway.

Supported Telnet commands/options are intentionally minimal but cover the needs of common MUD clients:

| Command / Option | Byte | Purpose |
| --- | --- | --- |
| `SE` (End of subnegotiation parameters) | `240` | Terminates subnegotiation blocks. |
| `NOP` (No operation) | `241` | Ignored; kept for compatibility. |
| `GA` (Go ahead) | `249` | May be sent by some legacy clients; ignored by the server. |
| `WILL` | `251` | Negotiation: client proposes enabling an option. |
| `WONT` | `252` | Negotiation: client refuses to enable an option. |
| `DO` | `253` | Negotiation: client requests that the server enable an option. |
| `DONT` | `254` | Negotiation: client requests that the server disable an option. |

Options outside this subset (for example NAWS, terminal type, or arbitrary experimental options) are silently discarded by the sanitization layer. This keeps the implementation small and hardened while still allowing widely used MUD clients to connect. MCP control lines (`#$#...`) and their payloads are treated as **text** on top of this Telnet layer and are not affected by the low-level command whitelist.

#### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation (for example GMud, TinTin++, and similar tools) are expected to work without special configuration.
- Clients that depend on advanced Telnet options (for example dynamic window sizing or terminal-type negotiation) should treat those features as best-effort: unsupported options are ignored rather than causing the connection to fail.
- MCP-aware clients should assume that MCP negotiation and messages are the primary extensibility mechanism. Telnet options remain deliberately constrained to reduce the attack surface at the DMZ edge.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### Data Model

The proxy is stateless. Any buffered input lives only in memory until forwarded
to the Spring Cloud Gateway while the Telnet connection is still active.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on how Telnet connections are integrated into the platform.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- A simple `smoke-test.sh` script in the service directory checks the REST and gRPC endpoints.

### Metrics Summary

TCP Proxy metrics follow the global Micrometer/OpenTelemetry conventions described in
[Logging & Monitoring](../../system-architecture-logging-monitoring.md). Key meters include:

- `tcpproxy.connections.total`, `tcpproxy.connections.active`, and `tcpproxy.buffer.depth` for socket and buffer utilisation at the edge.
- `tcpproxy.connection.events{type="connect"|"disconnect"}` and `tcpproxy.connection.duration` for connection lifecycle and lifetime tracking.
- `tcpproxy.command`, `tcpproxy.heartbeat`, `tcpproxy.idleClose`, and `tcpproxy.websocket.reconnect.delay` timers, plus `tcpproxy.websocket.reconnects` counters, for Telnet → Gateway bridge behaviour.
- `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason="..."}` for TLS and mTLS failures.
- `tcpproxy.telnet.discarded` and related `tcpproxy.disconnect.notify.failure` counters for abuse and error visibility.

In Prometheus these Micrometer meters appear with the expected naming
translation, for example:

- `tcpproxy.connections.active` → `tcpproxy_connections_active`
- `tcpproxy.connections.limit.exceeded` → `tcpproxy_connections_limit_exceeded`
- `tcpproxy.telnet.discarded` → `tcpproxy_telnet_discarded`
- `tcpproxy.websocket.reconnects` → `tcpproxy_websocket_reconnects`

The example PromQL and Alertmanager rules in
`design/observability/grafana/tcp-proxy-alerts-snippets.md` use these
Prometheus-style names; treat this section as the canonical list of meters and
the Grafana snippets as reference queries over them.

Labels on these metrics are intentionally low-cardinality (for example `type`, and occasionally `tenantId`)
to keep Prometheus usage aligned with the global guidelines. Detailed context such as client IP, `sessionId`,
and error details is captured in structured logs and tracing spans rather than metric labels.

### Local development and echo loop

There are two common local flows, depending on whether you want to test the full Telnet → WebSocket bridge or run the proxy completely standalone.

**1. Proxy + Gateway `/dev/echo` (bridge test)**

Use the bundled `/dev/echo` WebSocket endpoint under the `dev` profile to validate the Telnet → WebSocket bridge:

1. Start the proxy: `./gradlew :tcp-proxy-service:bootRun`. The task defaults to the `dev` profile for local runs. Override with `SPRING_PROFILES_ACTIVE=<profile>` or `-Dspring.profiles.active=<profile>` when needed.
2. Start Spring Cloud Gateway with the dev profile so `/dev/echo` is exposed (see the Gateway docs for details).
3. Point the bridge at the local echo: `GATEWAY_WS_URL=ws://localhost:8080/dev/echo`.
4. Connect from a Telnet/MUD client: `telnet localhost 2323`.
5. Type any text. The proxy logs the input at INFO and you should see the same text echoed back via the Gateway `/dev/echo` handler.

### Proxy dev-isolated mode (standalone echo)

When `TCP_PROXY_DEV_ISOLATED=true`, the proxy runs with an in-process Telnet echo handler:

1. Start the proxy in dev-isolated mode: `./gradlew :tcp-proxy-service:bootRunDevIsolated`. This sets `spring.profiles.active=dev` and `TCP_PROXY_DEV_ISOLATED=true`.
2. The proxy no longer opens a WebSocket connection to the gateway. It echoes subsequent commands directly back over the Telnet session while still allowing advanced clients to send an optional `SESSION <sessionId> <tenantId>` envelope if they want to exercise the attach-to-session path.
3. Connect from a Telnet/MUD client: `telnet localhost 2323`.
4. Send a few commands (for example `LOGIN demo@example.com swordfish`, `LOOK`, `SAY hello`). Watch the logs and Telnet output to verify that input is sanitized and echoed without requiring Spring Cloud Gateway, Game Session, or any other services.

Prefer containers? A minimal Docker Compose profile launches just the proxy in dev-isolated mode. Start it with `docker compose -f docker/docker-compose.tcp-proxy-devisolated.yml --profile tcp-proxy-devisolated up` and in another terminal run `telnet localhost 2323`. Stop the stack with `docker compose -f docker/docker-compose.tcp-proxy-devisolated.yml --profile tcp-proxy-devisolated down` when finished.

When pointing at a real gateway in non-dev-isolated mode, override `GATEWAY_WS_URL` with its WebSocket endpoint. Outside of the `dev` profile the default remains `ws://spring-cloud-gateway:8080/ws/game` so production pods continue to forward to the routed gameplay endpoint on the cluster gateway when the variable is unset.

## Environment Variables

The proxy uses minimal configuration. It still follows the scheme in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
so the standard `FIREMUD_POSTGRES_*` and `FIREMUD_REDIS_*` variables may be present
but are ignored. TLS certificates are supplied via
[`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).

Additional variables control the proxy runtime behaviour. TLS‑related settings are grouped by surface:

- **Telnet listener TLS (client ↔ proxy)**:
  - `TCP_PROXY_TLS_ENABLED` – enable Telnet‑over‑TLS termination.
  - `TCP_PROXY_TLS_CERT` – path to the Telnet listener TLS certificate.
  - `TCP_PROXY_TLS_KEY` – path to the Telnet listener TLS private key.
- **Proxy → Gateway WebSocket mTLS (proxy ↔ Spring Cloud Gateway)**:
  - `GATEWAY_WS_URL` – WebSocket URL for forwarding to the gateway (for example `ws://spring-cloud-gateway:8080/ws/game` or `wss://...`).
  - `FIREMUD_GRPC_CERT_CHAIN_PATH` – client certificate chain path used by the WebSocket client in target‑state mTLS configurations.
  - `FIREMUD_GRPC_PRIVATE_KEY_PATH` – client private key path used by the WebSocket client in target‑state mTLS configurations.
  - `FIREMUD_GRPC_CA_CERT_PATH` – CA bundle path for verifying the gateway certificate.
- **Internal gRPC server mTLS (other services ↔ proxy gRPC)**:
  - The same `FIREMUD_GRPC_*` variables are reused by the proxy’s gRPC server for mutual TLS on `TcpProxyService` RPCs.

The full variable list is:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway | `ws://spring-cloud-gateway:8080/ws/game` |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_CERT` | Path to the Telnet listener TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the Telnet listener TLS private key | *(empty)* |
| `TCP_PROXY_MAX_CONNECTIONS` | Maximum concurrent Telnet connections (`0` or unset = no explicit ceiling) | `0` |
| `TCP_PROXY_MAX_CONNECTIONS_PER_IP` | Maximum concurrent Telnet connections per client IP (`0` or unset = no explicit ceiling) | `0` |
| `TCP_PROXY_MAX_LINE_BYTES` | Maximum accepted Telnet line/envelope length in bytes before truncation/closure | `4096` |
| `TCP_PROXY_MAX_MALFORMED_ENVELOPES` | Maximum malformed `SESSION` envelopes per connection before hard close (see **Telnet Session Envelope & Event Metrics** for how this counter is applied) | `5` |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Certificate chain path for mTLS; shared between the proxy’s gRPC server and its target-state WebSocket client | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Private key path for mTLS; shared between the proxy’s gRPC server and its target-state WebSocket client | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle path for verifying the gateway and gRPC peers | `certs/ca.crt` |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for tracing; shared across services | `http://otel-collector:4317` |

These certificate and observability variables are shared with other services; see
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
for full details.

The proxy may expose both a raw Telnet listener (`TCP_PROXY_PORT`) and a
TLS-wrapped Telnet endpoint (via `TCP_PROXY_TLS_ENABLED` or a fronting
TLS-terminating load balancer) at the same time. In production, exposing the
raw Telnet port directly on the public internet is treated as a **legacy,
plaintext channel**: credentials and gameplay traffic may be observed in
transit, so additional safeguards apply. When
`FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins
over this plaintext Telnet port are only permitted for accounts that both:

- Have TOTP-based two-factor authentication enabled, and
- Opt in to **allow plaintext Telnet login** in their account settings (the
  checkbox/option defaults to off and explains the risk).

Plaintext Telnet connections are also tagged so the Game Session Service can
emit a landing-menu security warning recommending the TLS Telnet port or web
client instead. TLS Telnet endpoints and the web client are not subject to this
additional restriction.

The gRPC server listens on port `6565` by default as configured in `src/main/resources/application.yml`.

### WebSocket mTLS to Spring Cloud Gateway *(Target-state; see Implementation Status)*

In the target-state production configuration, the TCP Proxy Service connects to Spring Cloud Gateway over
`wss://` using mutual TLS. The proxy intentionally reuses the same certificate
files and watchers as gRPC in the current design:

- Client certificate and key are loaded from
  `FIREMUD_GRPC_CERT_CHAIN_PATH` and `FIREMUD_GRPC_PRIVATE_KEY_PATH`.
- The Gateway’s certificate is validated against `FIREMUD_GRPC_CA_CERT_PATH`,
  with hostname verification enabled using the host from `GATEWAY_WS_URL`.
- Certificate changes are picked up via the shared `TlsCertificateWatcher`
  so WebSocket clients can reload credentials without restarts.

TLS handshake failures are fail-closed: the proxy does not fall back to
plaintext. Instead it logs errors and increments a dedicated metric
(for example `tcpproxy.gateway.handshake.failures{reason="cert_validation"}`),
and Telnet connections may see temporary backoff behaviour while the
Gateway link is unavailable. See
[System Architecture: Security](../../system-architecture-security.md) for
certificate issuance and rotation details.

When overriding `GATEWAY_WS_URL` in a `wss://` configuration, the host portion
of the URL is used for both SNI and hostname verification. If you point
`GATEWAY_WS_URL` at an IP address or a hostname that is not present in the
Gateway certificate’s SANs, the TLS handshake fails with
`reason="cert_validation"` and no insecure fallback occurs. In cluster-internal
deployments, prefer the Kubernetes DNS name for the Gateway service (for
example `wss://spring-cloud-gateway:8080/ws/game`) and issue certificates for
that name. For external access, use a public hostname such as
`wss://mud.example.com/ws/game` that matches the Gateway’s certificate rather
than switching to a bare IP.

> **Current default:** Until the mTLS task in `design/project-management/task-list-tcp-proxy-service.md` is completed, clusters may run with `ws://` or `wss://` without client certificates for the Proxy → Gateway hop. Treat the configuration in this section as the target-state reference and reconcile live environment settings against it during rollout.

### Metrics & Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The
service exports OpenTelemetry spans to the collector defined by
`OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)) so traces appear in Jaeger.

### Tuning TCP Proxy for Different Environments

The connection and envelope limits exposed via `TCP_PROXY_MAX_CONNECTIONS`,
`TCP_PROXY_MAX_CONNECTIONS_PER_IP`, and `TCP_PROXY_MAX_MALFORMED_ENVELOPES`
are intended to be tuned per environment:

- **Dev / Hobby setups**
  - `TCP_PROXY_MAX_CONNECTIONS=50` – small cap to catch runaway local clients.
  - `TCP_PROXY_MAX_CONNECTIONS_PER_IP=10` – enough for multiple terminals per developer.
  - `TCP_PROXY_MAX_MALFORMED_ENVELOPES=10` – generous tolerance while iterating on tools or tests.
- **Small production deployments**
  - `TCP_PROXY_MAX_CONNECTIONS` sized to expected concurrent players plus a safety margin (for example `500`–`1000` depending on cluster size).
  - `TCP_PROXY_MAX_CONNECTIONS_PER_IP=3`–`5` – allows multiple windows per player while limiting abuse from a single IP.
  - `TCP_PROXY_MAX_MALFORMED_ENVELOPES=5` – closes connections that repeatedly send bad `SESSION` lines.
- **Heavier deployments**
  - Scale the proxy horizontally and keep per-pod limits moderate (for example `TCP_PROXY_MAX_CONNECTIONS=2000` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP=5`), rather than pushing a single instance to extreme totals.
  - Treat sustained increases in `tcpproxy.connections.limit.exceeded` and `tcpproxy.telnet.discarded` as signals to either:
    - Add capacity (more pods) and/or
    - Block or throttle specific abusive IP ranges at the network or gateway layer.

When choosing `TCP_PROXY_MAX_CONNECTIONS_PER_IP`, remember that many real-world deployments place large numbers of players behind a single NAT or carrier-grade IP (for example campus networks or shared ISPs). In those environments it is safer to keep the per-IP cap relatively high (or even leave it unset) and rely on Spring Cloud Gateway’s rate limiting and the Game Session Service’s per-IP and per-session limits for fairness, while treating the proxy’s per-IP ceiling primarily as a guardrail against obviously abusive sources.

After changing any of these values, monitor at least:

- `tcpproxy.connections.active`, `tcpproxy.connections.total`
- `tcpproxy.connections.limit.exceeded`
- `tcpproxy.telnet.discarded`

for several release cycles to ensure that the new limits are effective without causing unexpected rejections for legitimate players.

## Proto Files

Even though the proxy has no public API, supporting event messages are defined
in [../../../../protos/tcp-proxy/v1](../../../../protos/tcp-proxy/v1). Stubs are
regenerated via `./gradlew generateProto` when the proto files change.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Security Architecture](../../system-architecture-security.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../../user-journeys.md#7-player-login-and-gameplay)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check.
- `NotifyDisconnect(NotifyDisconnectRequest) returns (NotifyDisconnectResponse)` – informs the Game Session Service a Telnet client disconnected.

All RPC definitions live in [`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -plaintext localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger.

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Cross-Service Integration Test

The `src/test/java/crossservice` directory contains an integration test that
launches this service alongside Spring Cloud Gateway with **Testcontainers**.
Run it after the Gateway image is built:

This test requires the Spring Cloud Gateway Docker image to be available. Build it with `./gradlew :spring-cloud-gateway:bootBuildImage` or pull from the registry.

```bash
./gradlew :tcp-proxy-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more
information.
