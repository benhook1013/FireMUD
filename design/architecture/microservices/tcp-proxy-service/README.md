# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.
The OpenAPI specification for the `/ping` health endpoint lives in `services/tcp-proxy-service/src/main/resources/openapi.yaml`.

## Implementation Status

This document describes the target-state behaviour of the TCP Proxy Service.
Where implementation is still catching up, treat the design below as the source
of truth and reconcile code/tests accordingly.

- **Telnet login-first flow (without `SESSION`)** – The intended baseline is
  that all Telnet clients issue `LOGIN` and may optionally send a `SESSION`
  envelope for advanced attach-to-session flows. Existing tests and smoke
  scripts are being aligned to treat `SESSION` as optional; if you observe
  discrepancies, update those flows to match this design.
- **Proxy → Gateway WebSocket mTLS (implementation)** – Not yet fully implemented
  or deployed. The Telnet → Gateway WebSocket client currently connects to
  Spring Cloud Gateway without client certificates and relies on the default
  JDK TLS behaviour when using `wss://`. The configuration described below is
  the target-state mutual TLS setup using the shared `FIREMUD_GRPC_*` certificate
  paths. Remaining work is tracked in
  `design/project-management/task-list-tcp-proxy-service.md` under the mTLS task.
- **MCP negotiation and richer Telnet heuristics** – MCP 2.1 negotiation,
  extended Telnet abuse heuristics, and advanced connection throttling behaviour
  are documented here as target-state features. Portions of this behaviour are
  still being implemented and hardened; any gaps should be reconciled against
  the tasks in `design/project-management/task-list-tcp-proxy-service.md` before
  treating this document as fully representative of production behaviour.
- **Connection limits and abuse protection** – Connection caps, idle timeouts,
  and input size limits are documented here as the desired safeguards for the
  DMZ boundary. When adding or modifying limits in code, keep metric names and
  behaviours consistent with the “Connection Limits and Abuse Protection”
  section and update this status note if significant deviations are required.

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
- Can optionally terminate Telnet-over-TLS. Forwarding to the gateway uses
  WebSocket connections and supports mutual TLS.
  See [Security Architecture](../../system-architecture-security.md).
- Runs in the network DMZ. All gameplay traffic is forwarded only via WebSocket through Spring Cloud Gateway; the proxy uses a narrow, mTLS-protected gRPC link to the Game Session Service exclusively for `NotifyDisconnect` lifecycle events.
- Sanitizes incoming Telnet data and enforces a whitelist of
  **Telnet protocol commands** as described in the
  [Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
- Forwards client IPs via `X-Client-IP` so central throttling occurs in the Game Session Service. Optional TLS termination is controlled by `TCP_PROXY_TLS_ENABLED`.
- Performs basic sanitization and minimal per-connection safety checks (idle timeout, buffer depth limits, and session handshake rules). Cross-tenant rate limiting and abuse policies are enforced by Spring Cloud Gateway and the Game Session Service.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

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
Spring Cloud Gateway and the Game Session Service.

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
  - Maximum line and envelope length constraints (for example `TCP_PROXY_MAX_LINE_BYTES`)
    truncate overly long input and, after repeated violations, hard-close the
    connection with a `tcpproxy.connections.closed{reason="line_too_long"}` event.
  - Repeatedly malformed `SESSION` envelopes increment a dedicated counter and
    eventually trigger a hard close (for example `tcpproxy.connections.closed{reason="malformed_envelope_limit"}`),
    protecting the parser from abuse while still allowing occasional mistakes.
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
buffered commands. Their definitions live in
[`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

### Telnet Session Envelope & Event Metrics

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
  the connection.
- **Malformed `SESSION` lines** – if either `sessionId` or `tenantId` is missing
  or cannot be parsed, the line is logged and ignored; no error is sent back to
  the client. Clients that choose to use `SESSION` must resend a correct envelope
  or proceed with `LOGIN` only.

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
  connection open/close, with only low-cardinality labels (such as `type` and
  optionally `tenantId` where the tenant set is small and controlled).
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

### Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of
**Telnet protocol commands** as described in the
[Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
This avoids implementing the full Telnet specification while still protecting
against malformed negotiation sequences and other legacy edge cases.

Example filtering logic from the Telnet sanitization layer (currently implemented by `TelnetServerHandler`):

```java
private static final byte IAC = (byte) 255;
private static final Set<Byte> ALLOWED_COMMANDS =
    Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);
```

Commands outside this list are discarded and only sanitized printable characters are forwarded to the gateway.

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
but are ignored.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).

Additional variables control the proxy runtime behaviour:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway | `ws://spring-cloud-gateway:8080/ws/game` |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_CERT` | Path to the TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the TLS private key | *(empty)* |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Certificate chain path for mTLS; shared with gRPC configuration | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Private key path for mTLS; shared with gRPC configuration | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle path for verifying the gateway; shared with gRPC configuration | `certs/ca.crt` |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for tracing; shared across services | `http://otel-collector:4317` |

These certificate and observability variables are shared with other services; see
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
for full details.

The gRPC server listens on port `6565` by default as configured in `src/main/resources/application.yml`.

### WebSocket mTLS to Spring Cloud Gateway

In production, the TCP Proxy Service connects to Spring Cloud Gateway over
`wss://` using mutual TLS. The proxy reuses the same certificate files and
watchers as gRPC:

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

### Metrics & Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The
service exports OpenTelemetry spans to the collector defined by
`OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)) so traces appear in Jaeger.

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
