# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.
The OpenAPI specification for the `/ping` health endpoint lives in `services/tcp-proxy-service/src/main/resources/openapi.yaml`.
This service exposes an **internal-only gRPC health check** (`Ping`) for operators and tooling, and it uses an **internal-only gRPC client** to call the Game Session Service’s `NotifyDisconnect` event sink when Telnet connections close; neither surface is ever published through Spring Cloud Gateway.

For TCP Proxy’s position in the overall system (DMZ, Telnet edge, and WebSocket bridge to Spring Cloud Gateway), see the [System Architecture Diagram](../../system-architecture-diagram.md) and [System Context Diagram](../../system-context-diagram.md); those diagrams show the Telnet edge proxy → PROXY protocol → TCP Proxy → Spring Cloud Gateway flow described in this document.

> **Canonical specs:** This document is the authoritative reference for:
>
> - Telnet `SESSION` envelope semantics and header propagation (see **Telnet Session Envelope & Event Metrics**). `LOGIN` / `LOGON` semantics remain canonical in the Authentication & Authorization doc.
> - `NotifyDisconnect` event semantics and layering guarantees.
> - Proxy metrics naming and label cardinality rules (see **Metrics Summary**).
>
> Other docs (Reconnection Strategy, Protocol Bridging, Gateway Architecture, and user journeys) intentionally summarize behaviour and link back here instead of redefining these protocols.

## Implementation Status

This document describes the behaviour of the TCP Proxy Service in its target architecture.
Where implementation is still catching up, treat the design below as the source
of truth and reconcile code/tests accordingly; any known gaps are called out in
the **Implementation Status** section below. The table here is descriptive and intentionally high-level; the authoritative, fine-grained task status for this service lives in `design/project-management/task-list-tcp-proxy-service.md` and should be treated as the source of truth when in doubt.

> When you encounter discrepancies between this design and the implementation, align behaviour with this document and the canonical cross-service docs (for example Authentication & Authorization for `LOGIN` semantics) rather than changing the protocol, unless there is an explicit design update.

| Area | Target behaviour | Current status | Tracked in |
| --- | --- | --- | --- |
| Telnet login-first flow (without `SESSION`) | All Telnet clients issue `LOGIN` and may optionally send a `SESSION` envelope for advanced attach-to-session flows; `SESSION` is always optional and Telnet shares the same login pipeline as WebSocket clients. `LOGIN` / `LOGON` semantics remain canonical in the Authentication & Authorization doc; this row only describes how Telnet traffic is forwarded into that flow. | Implemented. | `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md` |
| Proxy → Gateway WebSocket mTLS | Telnet → Gateway WebSocket client connects over `wss://` using mutual TLS and the dedicated `FIREMUD_GATEWAY_WS_*` client certificate paths (separate from the proxy’s gRPC server mTLS identity). The detailed mTLS contract (required listener, SAN/hostname expectations, and certificate paths) is defined in this document’s **WebSocket mTLS to Spring Cloud Gateway** section and should be treated as canonical; other docs intentionally refer back to that section instead of re-describing wiring. | Implemented. Player-facing environments fail closed if client-certificate identity verification is unavailable. | `design/project-management/task-list-tcp-proxy-service.md` (mTLS task). |
| MCP control-line handling and Telnet heuristics | MCP 2.1 control lines (including negotiation), extended Telnet abuse heuristics, and connection throttling are enforced at the proxy edge while keeping MCP payloads intact. The proxy’s responsibilities are transport-safety and abuse budgets; MCP package semantics are owned by the backend session layer as described in [Mud Client Protocol (MCP) Support](../../system-architecture-mud-client-protocol.md). MCP-specific budgets (for example limits on active cords, concurrent `_data-tag`s, and MCP messages per second) and how they feed metrics such as `tcpproxy.telnet.discarded` are described in the **MCP Resource Limits & Abuse Budgets** section. | Implemented. | `design/project-management/task-list-tcp-proxy-service.md` (MCP and abuse/heuristics tasks). |
| Connection limits and abuse protection | Connection caps, idle timeouts, input size limits, and malformed-envelope budgets protect the DMZ boundary, with metrics such as `tcpproxy.connections.limit.exceeded`. Recommended per-environment defaults, including guidance for NAT-heavy deployments, are defined in **Tuning TCP Proxy for Different Environments**. | Core limit handling is implemented; tuning and additional metrics may evolve as production behaviour is observed. | `design/project-management/task-list-tcp-proxy-service.md` (connection management and security sections). |
| Telnet client IP preservation via PROXY protocol | Telnet client IPs are preserved by terminating public TCP on a Telnet edge proxy (for example HAProxy) that forwards to the TCP Proxy Service using PROXY protocol on an internal-only listener/port, so the proxy can recover the real client IP and set `X-Proxy-Client-IP` on its WebSocket hop to Spring Cloud Gateway. On the PROXY-protocol listener, malformed or truncated PROXY headers are treated as a hard failure: the proxy closes the connection, increments `tcpproxy.telnet.discarded{reason="proxy_protocol"}`, and never silently falls back to using the TCP peer IP. | Implemented. In player-facing environments, PROXY protocol on the internal listener is required and the raw Telnet listener is never exposed directly to the Internet. | `design/project-management/task-list-tcp-proxy-service.md` (PROXY protocol task). |

### Cross-Path Connectivity Contract

The following are canonical and active across Telnet and WebSocket paths:

- Telnet login-first without `SESSION` is supported (`LOGIN` then `PLAY`; `SESSION` optional).
- Proxy → Gateway WebSocket hop is mTLS-authenticated in player-facing environments.
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

### Minimal Production Configuration Checklist

For any shared or player-facing environment, operators should ensure at least:

- `GATEWAY_WS_URL` points at the Spring Cloud Gateway WebSocket mTLS listener (`wss://.../ws/game`) as described in **WebSocket mTLS to Spring Cloud Gateway**, with `FIREMUD_GATEWAY_WS_*` variables configured so the proxy both authenticates the gateway and presents its own client certificate.
- `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` are set to non-zero values sized for expected load and NAT patterns, following the guidance in **Tuning TCP Proxy for Different Environments**; the `0` defaults are reserved for local/dev and CI.
- Telnet is fronted by a Telnet edge proxy with PROXY protocol enabled into `TCP_PROXY_PROXY_PROTOCOL_PORT`, or source IPs are otherwise preserved; in all cases, the PROXY-protocol listener remains internal-only and is never exposed directly as a public `LoadBalancer` port.
- Plaintext Telnet on `TCP_PROXY_PORT` is treated as a legacy channel governed by the Telnet hardening rules in the Security Architecture (2FA requirements, per-account “allow plaintext Telnet login” flag, and landing-menu warning), and TLS Telnet plus the web client are preferred for general use.

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
- Implements baseline Telnet negotiation and character encoding handling; advanced heuristics and option handling follow the Security and MCP sections in this document and their **Implementation Status** notes.
- Implements MCP 2.1 control-line handling and resource limits at the transport edge while forwarding MCP payloads verbatim. MCP package semantics and canonical negotiation behavior are owned by the backend session layer as described in [Mud Client Protocol (MCP) Support](../../system-architecture-mud-client-protocol.md); the proxy enforces only transport-safety and abuse budgets as described in this document’s **MCP Resource Limits & Abuse Budgets** section.
- Integrates with the [Reconnection Strategy](../../system-architecture-reconnection.md) so backend session state can be resumed when clients reconnect, send `LOGIN` again, and re-bind gameplay scope with `PLAY`; Telnet clients always reconnect and reauthenticate after any disconnect.
- Can optionally terminate Telnet-over-TLS while always supporting raw Telnet
  on the configured TCP port for classic clients (for example the Windows
  `telnet` command). Forwarding to the gateway uses WebSocket connections and
  supports mutual TLS. See [Security Architecture](../../system-architecture-security.md).
- Runs in the network DMZ. All gameplay traffic is forwarded only via WebSocket through Spring Cloud Gateway; the proxy uses a narrow, mTLS-protected gRPC client call to the Game Session Service exclusively for `NotifyDisconnect` lifecycle events. This event surface is **internal-only** and is secured using the same `FIREMUD_GRPC_*` certificate paths as other services. Telnet‑side TLS (if enabled) is configured via `TCP_PROXY_TLS_*` and is independent from the Proxy → Gateway WebSocket mTLS settings, which use the separate `FIREMUD_GATEWAY_WS_*` certificate paths.

> **Legacy Telnet requirement:** Support for plaintext/raw Telnet in production is an intentional, non-removable requirement so that classic MUD clients which cannot speak TLS can connect. Security hardening for this legacy channel lives in the [Telnet Command Handling and Controls](../../system-architecture-security.md#telnet-command-handling-and-controls) section of the Security Architecture (2FA requirements, per-account opt‑in, warnings, rate limits, DMZ boundary, etc.). Architecture and security reviews must treat raw Telnet as an accepted, documented trade-off rather than a defect to remove; concerns should focus on whether the documented mitigations are correctly implemented and configured.

- Sanitizes incoming Telnet data and enforces a whitelist of
  **Telnet protocol commands** as described in the
  [Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
- Forwards client identity using a gateway canonicalization model. The proxy sets `X-Proxy-Client-IP` on its internal WebSocket hop so Spring Cloud Gateway can set the canonical `X-Client-IP` header after authenticating the TCP Proxy identity. In Kubernetes, the raw TCP peer address may be a load balancer or node IP due to SNAT; the preferred production deployment is to place a Telnet edge proxy (HAProxy) in front of the TCP Proxy Service and enable PROXY protocol so the proxy can recover the true client IP before setting `X-Proxy-Client-IP`. Optional TLS termination is controlled by `TCP_PROXY_TLS_ENABLED`.
- When PROXY protocol is enabled for IP preservation, it should be enabled only on a dedicated, internal-only listener/port that is reachable solely from the Telnet edge proxy (HAProxy). Do not enable PROXY parsing on the public Telnet listener, since accepting PROXY headers directly from the Internet would allow client-IP spoofing.
- Performs basic sanitization and minimal per-connection safety checks (idle timeout, buffer depth limits, and session handshake rules). Cross-tenant rate limiting and abuse policies are enforced by Spring Cloud Gateway and the Game Session Service.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

> **Implementation notes:** This document is the canonical behavior contract for proxy handling, and implementation must remain aligned with these sections.

### Canonical Specs Quick Links

The following sections are the canonical references for proxy behaviour; other docs intentionally summarize and defer to them:

- [Telnet Session Envelope & Event Metrics](#telnet-session-envelope--event-metrics)
- [Service Interactions – NotifyDisconnect](#service-interactions)
- [Connection Limits and Abuse Protection](#connection-limits-and-abuse-protection)
- [Telnet Command Handling](#telnet-command-handling)
- [Metrics Summary](#metrics-summary)
- [TLS & Trust Surfaces (Summary)](#tls--trust-surfaces-summary)
- [Environment Variables](#environment-variables)

### Redis Role and Prefixes

- The TCP Proxy Service does **not** access Coordination Redis and never depends on Redis for correctness or session recovery.
- The proxy currently uses **no Redis keys**. All gameplay and session state live in the Game Session Service and Redis as described in the [Reconnection Strategy](../../system-architecture-reconnection.md) and [Redis Architecture](../../system-architecture-redis.md); proxy buffers are purely in-memory and connection-local.
- Future enhancements may use Cache/Rate-Limit Redis only for **optional, non-authoritative caches** or throttling decisions. When introduced, such keys must:
  - Use the Cache/Rate-Limit Redis deployment (never Coordination Redis).
  - Follow a dedicated prefix family such as `tcpproxy:rate:*` or `tcpproxy:cache:*` with TTLs and reset semantics declared in the Redis cache design.
  - Degrade safely when Redis is unavailable (treat cache misses/failures as “no cache”, never as an availability blocker).
- These caches must not change the proxy’s fundamental design as a stateless edge: any derived entries may be dropped, cold, or unavailable at any time without affecting correctness.

### Reconnection Behaviour at the Proxy Layer

The TCP Proxy Service treats each Telnet TCP connection as independent and keeps
reconnection logic centralized in the Game Session Service:

- Multiple Telnet connections using the same `{gameInstanceId, tenantId}` are allowed.
  The proxy simply forwards commands for each connection; Game Session enforces
  the “one session per character” behaviour by applying its takeover rules when
  a second client logs in as the same character, so only one active session per
  character is allowed at any time.
- The proxy does not emit a positive “reconnect” event. It only calls
  `NotifyDisconnect` when a Telnet socket closes, using a server-generated `proxyConnectionId` and a per-connection `disconnectSequence` counter for idempotency; Game Session interprets a subsequent `LOGIN` + `PLAY` flow (with or without a `SESSION` envelope) as either a fresh login or a resume/takeover based on Redis session state.
- After `NotifyDisconnect`, session state remains eligible for reconnection
  until the configured `session_expiration_ms` window elapses; see the
  [Reconnection Strategy](../../system-architecture-reconnection.md) and
  [Environment & Secrets](../../infrastructure/environment-and-secrets.md#authentication)
  for details on how this window is derived.

### Connection Limits and Abuse Protection (Tuning)

Because the TCP Proxy Service sits in the network DMZ, it enforces hard resource
ceilings even though tenant-aware throttling and rich abuse policies live in
Spring Cloud Gateway and the Game Session Service. Limits are configured via the
environment variables described in the **Environment Variables** section; any
implementation defaults must remain aligned with those variables. When code and
configuration diverge, treat this document and its environment variables as the
source of truth and update code/tests accordingly. The exact handler classes and
constants used in the implementation (for example the Telnet pipeline and in-memory
buffer sizes) are **implementation details**; this section defines the normative
behaviour regardless of which classes or frameworks the service uses internally.

- **Connection limits**
  - A global concurrent connection cap (for example `TCP_PROXY_MAX_CONNECTIONS`)
    prevents the proxy from exhausting sockets or file descriptors. When the
    limit is reached, new connections are rejected and counted via `tcpproxy.connections.limit.exceeded`.
  - A per-client-IP cap (for example `TCP_PROXY_MAX_CONNECTIONS_PER_IP`) guards
    against a single address consuming the entire connection budget. This cap is
    only as accurate as the observed client IP: in Kubernetes deployments, it is
    expected that either source-IP preservation or PROXY protocol is enabled so
    `X-Proxy-Client-IP` reflects the real client as described in
    [Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls)
    and [Deployment Environments](../../infrastructure/deployment-environments.md). When those
    mechanisms are not in place, treat per-IP limits as **best-effort heuristics** rather
    than strict fairness guarantees. In clusters where client IPs are not reliably
    preserved, operators should rely primarily on the global `TCP_PROXY_MAX_CONNECTIONS`
    cap and higher-layer rate limits, and either leave `TCP_PROXY_MAX_CONNECTIONS_PER_IP=0`
    (no ceiling) or set it to a generous guardrail value rather than a strict fairness
    control.
- **Slow/abusive client handling**
  - Read idle timeouts and maximum connection lifetimes close connections that
    send no data or linger indefinitely, limiting exposure to slowloris-style attacks. Idle closes are tracked via the `tcpproxy.idleClose` timer and connection lifecycle metrics.
  - Backpressure-aware write handling avoids unbounded buffer growth when
    sending data to very slow clients, closing the socket once thresholds are
    exceeded.
- **Input size and shape limits**
  - Maximum line and envelope length constraints, configured via
    `TCP_PROXY_MAX_LINE_BYTES`, reject oversized lines without forwarding partial input. When a line is rejected for size, the proxy sends a clear, user-facing warning such as `Line too long; command not processed.` so clients are not confused about what reached the game engine. If the session has negotiated ANSI/color support, the warning may use that formatting, but it must remain readable on plain Telnet clients. Oversized-line violations are tracked per connection and the proxy hard-closes once the count exceeds `TCP_PROXY_MAX_OVERSIZE_LINES` (default `10`) so accidental oversizes are tolerated without enabling unbounded memory or CPU abuse. Implementations must also enforce an in-memory buffer ceiling for per-connection input so that once the buffer is full the connection is closed and `tcpproxy.telnet.discarded` is incremented, even if `TCP_PROXY_MAX_LINE_BYTES` is not hit. The specific buffer constant and handler class are implementation details; the behavioural guarantee is that buffered input is always bounded and abusive clients are closed rather than being allowed to grow unbounded buffers.
  - Malformed `SESSION` envelopes increment a dedicated counter. When the number
    of malformed envelopes on a single connection exceeds
    `TCP_PROXY_MAX_MALFORMED_ENVELOPES`, the proxy closes the connection with a
    hard close and emits the corresponding abuse/close metrics. Before closure,
    clients may receive a short warning such as `Repeated malformed SESSION envelope; connection closing.` so advanced tools can surface configuration issues.
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
    rather than repeated proxy disconnects. A practical workflow is:
    1) Check `tcpproxy.connections.limit.exceeded` and `tcpproxy.telnet.discarded` for hard edge ceilings being hit,
    2) Then inspect Gateway rate-limit metrics and logs for throttling on the `/ws/game/**` route, and
    3) Finally review Game Session per-IP/per-session quotas and Redis-backed limits if gameplay commands are being rejected despite healthy edge and gateway metrics.

## Key Features

- **Telnet Compatibility** — accepts standard MUD clients over TCP.
- **WebSocket Bridging** — forwards all traffic to the gateway via WebSocket.
- **Bounded buffering** — buffers line assembly and enforces strict in-memory limits; the proxy does not attempt to preserve gameplay across upstream disconnects.
- **Graceful Disconnects** — informs the Game Session Service when a client drops.

### Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline; `LOGIN` / `LOGON` semantics and multi-client takeover behaviour remain canonical in the [Authentication & Authorization](../../system-architecture-authentication.md) document.

- **Minimal / legacy client (no `SESSION`)**
  - Connect to the TCP Proxy Service.
  - Send a `LOGIN` command with the appropriate credentials (and optional OTP where required).
  - Complete lobby selection with `PLAY <world> [character]` before gameplay commands.
  - Send gameplay commands (`LOOK`, `SAY`, movement, etc.) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Advanced client (attach/resume with `SESSION` + `LOGIN`)**
  - Obtain a `gameInstanceId` and `tenantId` from a first-party admission or session-management API (owned by Game Session and/or the control plane). Do not treat the specific endpoint shape as part of the Telnet protocol contract; only the identifiers and their validation rules matter to the edge.
  - Connect to the TCP Proxy Service.
  - Immediately send a `SESSION <gameInstanceId> <tenantId>` envelope as the first line on the connection.
  - Send a `LOGIN` command over the same connection.
  - Complete lobby selection with `PLAY <world> [character]`.
  - Continue with gameplay commands as normal.
  - Game Session evaluates the combination of `SESSION`, `LOGIN`, and `PLAY` against Redis-backed session state and the authentication rules described in the [Authentication & Authorization](../../system-architecture-authentication.md) and [Reconnection Strategy](../../system-architecture-reconnection.md) documents to decide whether to resume a prior session or start a fresh one.

### Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same
account or `SESSION` envelope. The proxy forwards traffic for every TCP
connection independently; visible behaviour is governed by the Game Session
Service’s “one session per character” rules described in the
[Reconnection Strategy](../../system-architecture-reconnection.md#resume-vs-reload-scenarios)
and [Authentication & Authorization](../../system-architecture-authentication.md#multi-client-behavior-and-session-takeover):

- **Two Telnet windows without `SESSION`**
  - Window A connects, issues `LOGIN`, and enters gameplay with `PLAY`.
  - Window B connects and issues `LOGIN` for the same character, then `PLAY`. Game Session
    treats this as a takeover: the old session is terminated and the new window
    becomes authoritative. Window A is disconnected and stops receiving updates.
  - If Window A reconnects and logs in again, it in turn takes over from
    Window B. At any moment only one connection actively controls the
    character; there is no concurrent “split control” even though the proxy
    forwards traffic from both TCP connections.
- **Two Telnet windows with the same `{gameInstanceId, tenantId}` envelope**
  - Both windows connect to the TCP Proxy Service and send
    `SESSION <gameInstanceId> <tenantId>` followed by `LOGIN` and `PLAY`.
  - The proxy forwards both flows independently; Game Session binds
    socket-level control to whichever `LOGIN`/`PLAY` flow most recently succeeded for the
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
- If the proxy cannot establish the WebSocket bridge to Spring Cloud Gateway because gameplay upstream is unavailable, it fail-closes the Telnet socket with a clear user-facing message (for example “Gateway link unavailable; please reconnect”) and a Telnet disconnect reason of `backend_unavailable` as defined in the Telnet disconnect taxonomy in [Protocol Bridging](../../system-architecture-protocol-bridging.md#telnet-disconnect-reasons).
- If bridge establishment fails because trust/policy checks fail (for example `cert_validation`, client-certificate mismatch, handshake policy deny), the proxy fail-closes with `policy_violation`, not `backend_unavailable`.
- If the WebSocket bridge drops after the Telnet connection is established, the proxy applies the established-session bridge state machine: it enters `unreachable`, retries bridge recovery for up to `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS`, then closes with `backend_unavailable` if recovery does not succeed. For unauthenticated/pre-admission sockets where initial bridge establishment fails due to upstream unavailability, the proxy fail-closes immediately with `backend_unavailable`.
- During sustained Gateway gameplay unreachability, proxy admission uses a bridge-availability circuit-breaker model: new Telnet sockets are rejected quickly with `backend_unavailable` instead of being held while repeated bridge attempts fail. Admission resumes only after bridge-health recovery criteria are met (`TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful probes) so reconnect storms do not amplify edge resource pressure.
- If upstream backpressure causes the Telnet → Gateway buffered-line ceiling to be exceeded, the proxy closes the Telnet connection with `policy_violation` and emits `edge_backpressure` context in structured logs/metrics rather than using `backend_unavailable`, so client behavior and outage dashboards remain distinguishable.
- If the connection is lost, the in-memory queue is cleared and no Telnet
  commands are replayed by the proxy. Reconnection hooks notify downstream
  services so the Game Session Service can resume gameplay from Redis-backed
  session state where available.
- All gameplay commands, including the mandatory `LOGIN` and `PLAY` admission steps, are forwarded
  verbatim over the WebSocket bridge, so Spring Cloud Gateway and the Game Session Service
  see the same protocol lines as native WebSocket clients. Telnet handlers only
  parse the optional initial `SESSION` envelope for first-party/advanced clients;
  everything else is sent to the canonical gameplay route described in
  [Gameplay WebSocket Route](../../system-architecture-gateway.md#gameplay-websocket-route),
  ensuring the shared login/resume pipeline is used without Telnet-specific translations.

### Bridge State Machine (Established Telnet Sessions)

For already-established Telnet sessions, the proxy uses an explicit per-connection bridge state machine:

- `healthy` – upstream bridge established and forwarding.
- `unreachable` – upstream bridge cannot be maintained; per-connection unreachability timer starts.
- `close_due_to_unreachable` – if continuous unreachability exceeds `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS`, close with `backend_unavailable`.
- `close_due_to_edge_backpressure` – if queued lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES`, close with `policy_violation` and record `edge_backpressure` context in structured logs/metrics.
- `recovered` – return to `healthy` only after proxy-level recovery hysteresis is met (`TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful probes).

This state machine is distinct from the proxy-wide open/half-open/closed admission breaker and defines the deterministic behavior for active Telnet sockets during partial disconnects and upstream flap windows.

### Service Interactions

The proxy does not expose a public client API. Instead it emits a gRPC event
for internal coordination:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client
  drops so the session may be suspended. This exists primarily to provide a fast, correlatable liveness hint keyed by `proxyConnectionId` when Telnet sockets close, even if the proxy’s WebSocket bridge teardown or downstream close detection is delayed or ambiguous during restarts.

These events let the Game Session Service resume suspended sessions and deliver
any **Redis-backed queued commands** owned by the Game Session Service. The TCP
Proxy never replays Telnet input after a disconnect; connection-local buffers
are cleared as soon as the TCP session closes. The `NotifyDisconnect` event is
therefore a **best-effort, at-least-once** lifecycle signal keyed by
`{proxyConnectionId, disconnectSequence}` rather than a request to re-run gameplay commands.

When a `NotifyDisconnect` call fails with a **transport-level** error (for example an unavailable channel, deadline exceeded, or TLS failure), the proxy retries it with a short, bounded exponential backoff window. Implementations should treat this as an advisory hint rather than a durability channel. The contract is:

- A dedicated configuration knob (`TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`) bounds the **total retry window** after Telnet socket close (recommended default `3000`–`5000` ms).
- Within that window, the proxy applies exponential backoff between attempts and gives up permanently once the total elapsed time since Telnet socket close exceeds `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`.
- After the window elapses, the proxy relies entirely on Game Session’s own liveness detection and Redis-backed timeouts; no further retries are attempted for that `{proxyConnectionId, disconnectSequence}`.

When the gRPC transport returns `OK` but the `NotifyDisconnectResponse.error` field is populated, the proxy treats most codes as permanent contract-level failures and **does not retry** (for example “unknown session”). The exception is `RESOURCE_EXHAUSTED`, which is treated as temporary app-level overload and may be retried within `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`. The proxy logs all app errors, increments `grpc_app_error_total{code="..."}` and `tcpproxy_disconnect_notify_app_error_total{code="..."}`, and moves on once retry policy for that code is exhausted.

This keeps retry behaviour simple while still surfacing most transient gRPC failures without risking long-lived retry storms.

The `NotifyDisconnectResponse.error.code` field uses a small, bounded set of values so metrics and dashboards remain low-cardinality and easy to reason about. The current codes are:

| Code | Meaning | Retry behaviour |
| --- | --- | --- |
| `OK` | Event accepted and processed successfully. The Game Session implementation may set this explicitly, and the TCP Proxy normalizes responses that omit an `error` field to `OK` so callers always see a concrete code. | Never retried; this is a successful completion. |
| `INVALID_ARGUMENT` | Request was structurally or semantically invalid (for example a non-UUID tenant/session identifier or a tenant that does not own the requested session). | Treated as a permanent contract failure; the proxy does not retry and relies on metrics/logs for visibility. |
| `NOT_FOUND` | Target session or game instance was not found or has already been cleaned up. | Treated as a permanent contract failure; the proxy does not retry and relies on metrics/logs for visibility. |
| `PERMISSION_DENIED` | Caller identity is authenticated but not authorized for the target event sink/session scope. | Treated as a permanent contract failure; the proxy does not retry and relies on metrics/logs for visibility. |
| `FAILED_PRECONDITION` | Event shape is valid but the target session state cannot accept it right now (for example stale connection context or superseded binding). | Treated as a permanent contract failure for that event; the proxy does not retry and relies on metrics/logs for visibility. |
| `RESOURCE_EXHAUSTED` | Consumer-side capacity guardrail hit while processing disconnect hints (for example dedupe store pressure). | Treated as a temporary application-level overload; the proxy may retry only within `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`, then gives up. |

Additional codes may be introduced in the future as new failure modes are surfaced, but they must remain few in number and be added to this table when introduced. Implementations should avoid inventing arbitrary, high-cardinality codes; instead, they should map related failures into these shared buckets and, where necessary, record more detailed context in logs and traces rather than in the error code itself.

The proxy generates `proxyConnectionId` when the Telnet socket is accepted and uses a **single, stable** identifier for the entire lifetime of that TCP connection. Every WebSocket bridge handshake initiated on behalf of that Telnet connection – including reconnects during a Gateway blip – re-sends the same `proxyConnectionId` as a WebSocket handshake header (`X-Proxy-Connection-Id`). Spring Cloud Gateway strips this header from public ingress and forwards it only when it has authenticated the TCP Proxy identity; Game Session captures this identifier during login/session binding so it can associate disconnect events with the correct authenticated session even when the client did not provide a `SESSION` envelope.

When a valid `SESSION <gameInstanceId> <tenantId>` envelope is captured, the proxy forwards those identifiers as WebSocket handshake headers (`X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id`). Spring Cloud Gateway strips these from public ingress and may forward canonical `X-Game-Instance-Id` / `X-Tenant-Id` headers only after authenticating the TCP Proxy identity. These values remain advisory context, not trusted facts: Game Session validates them against Redis-backed session ownership and tenant authorization.

Game Session must treat `NotifyDisconnect` as strictly advisory and idempotent and must
always be able to detect disconnects without relying on this stream as a single
source of truth:

- If a `NotifyDisconnect` arrives after the session has already been rebound to a new socket (and therefore a different `proxyConnectionId`), the event is ignored for state changes because it refers to an old, closed connection.
- Missing events are tolerated because disconnects are also detected at the
  Gateway and Game Session layers; recovery logic must not rely on this signal
  as a single source of truth.
- Duplicate events for the same `{proxyConnectionId, disconnectSequence}` (or older `disconnectSequence` values for a given `proxyConnectionId`) are handled without side effects so the stream can be retried safely.

### Failure Modes and Expectations

- Loss or delay of `NotifyDisconnect` events must never leave players “stuck logged in” or unable to resume; Game Session is responsible for independently detecting liveness via WebSocket/TCP close and Redis-backed timeouts.
- Retries and at-least-once delivery should only increase duplication of advisory events, not change observable gameplay behaviour; idempotent handling keyed by `{proxyConnectionId, disconnectSequence}` is required.
- In practice, losing events at this layer should only slow down session cleanup or metrics accuracy slightly, not introduce new correctness states.

### Logging and Correlation

For operators and developers, `NotifyDisconnect` is designed to be easy to correlate end-to-end:

- The TCP Proxy logs Telnet socket lifecycle events with a stable `proxyConnectionId` field, along with connection-level tags such as `tenantId`, `gameInstanceId` (when known), and client IP or `X-Proxy-Client-IP`.
- Spring Cloud Gateway propagates `X-Proxy-Connection-Id` only on authenticated TCP Proxy → Gateway hops and strips it from public ingress, then produces a canonical `X-Client-IP` header as described in the Gateway Architecture and Security docs.
- Game Session logs login, resume, takeover, and disconnect-processing events using the same `proxyConnectionId` and `{gameInstanceId, tenantId}` values captured during session binding so operators can correlate Telnet disconnects, `NotifyDisconnect` calls, and session lifecycle metrics.
- The `tcpproxy.disconnect.notify.transport_failure` and `tcpproxy.disconnect.notify.app_error` meters (and associated logs) reference `{proxyConnectionId, disconnectSequence}` so failures and retries can be tied back to specific Telnet connections and Game Session events when debugging reconnection issues.

Their definitions live in
[`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

### Telnet Session Envelope & Event Metrics

This section is the canonical reference for the TCP Proxy Service’s Telnet
`SESSION` envelope semantics and related event/metric behaviour. Other documents
(such as the Reconnection Strategy, Protocol Bridging, and user-journey flows)
intentionally summarize the behaviour at a higher level and should link back
here rather than redefining the protocol. The core `LOGIN` / `LOGON` command
semantics are defined in the Authentication & Authorization doc and shared by
both Telnet and WebSocket clients.

The `SESSION` envelope is an optional optimization used by first-party and other
advanced Telnet clients to attach to an existing session before `LOGIN`. Normal
Telnet clients never need to send `SESSION`; they simply issue `LOGIN` and let
the Game Session Service create or bind the session exactly as WebSocket
clients do.

When used, the envelope is a plain-text line that starts with `SESSION`
(case-insensitive) followed by the **canonical form**:

```text
SESSION <gameInstanceId> <tenantId>
```

Both `gameInstanceId` and `tenantId` are UUIDs across the system and must be supplied in canonical UUID string form.

The `TelnetSessionContext.captureFromEnvelope` helper trims and upper-cases the
prefix, splits on the first colon or whitespace, and ignores envelopes that are
missing either identifier. Once captured, `gameInstanceId` and `tenantId` are
propagated over the WebSocket bridge (`X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id` handshake headers, which Spring Cloud Gateway may promote to canonical `X-Game-Instance-Id` / `X-Tenant-Id` after authenticating the TCP Proxy identity) and also drive the event and metric generation below.
Malformed or partially specified envelopes are treated as best-effort hints only: they are logged and counted against the malformed envelope budgets described below but do not block the Telnet connection or change reconnection/session semantics, which always fall back to the normal `LOGIN`-driven pipeline shared with WebSocket clients.

#### Where `gameInstanceId` and `tenantId` come from

- Cross-service tests and advanced clients typically obtain `{gameInstanceId, tenantId}` from a first-party admission or session-management API (owned by Game Session and/or the control plane), then send `SESSION <gameInstanceId> <tenantId>` when attaching to that instance. Do not treat any specific endpoint shape as part of the Telnet protocol contract; only the identifiers and their validation rules matter to the edge. See:
  - `design/project-management/look-smoke-tests.md` (WebSocket and Telnet flows)
  - `design/project-management/look-cross-service-tests.md`
  - `design/architecture/system-architecture-authentication.md`
- Simpler Telnet clients do not send `SESSION`. They connect, issue `LOGIN`,
  and rely on the Game Session Service to derive session and tenant context from
  the login flow, matching the behavior of WebSocket clients.

#### Envelope and command handling rules

- **Envelope capture window** – the proxy attempts to capture at most one `SESSION` envelope while `sessionContext` is unset and before the first non-`SESSION` gameplay line is forwarded upstream. Only the first non-`SESSION` gameplay line (for example `LOGIN`, `LOOK`, `SAY`, or other normal commands) closes the envelope window permanently; after that point the proxy will not attempt to parse or consume additional `SESSION` envelopes on that connection.
- **Telnet and MCP control traffic** – Telnet option negotiation bytes (IAC sequences) are handled by the Telnet pipeline and are not treated as lines for the purposes of the envelope capture window; they neither count as `SESSION` envelopes nor as gameplay lines. MCP control/negotiation lines (`#$#...`) are also treated as out-of-band control traffic for envelope-window purposes: they may be forwarded upstream without closing the window, and they do not prevent a client from sending a valid `SESSION` envelope later (until the first gameplay line is forwarded).
- **Bridge establishment** – the proxy establishes its Proxy → Gateway WebSocket bridge the first time it must forward any non-`SESSION` text upstream (including MCP control lines and normal gameplay commands). If a valid `SESSION` envelope is captured before the bridge is established, the proxy includes the corresponding `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` hints in the WebSocket handshake; otherwise the bridge is established without those optional hints.
- **Consumed vs forwarded** – during the envelope capture window, lines beginning with `SESSION` are treated as envelope attempts and are **consumed by the TCP Proxy Service** (never forwarded to Spring Cloud Gateway / Game Session Service). MCP control lines and all other non-`SESSION` text are forwarded upstream. After the envelope window closes, any lines beginning with `SESSION` are forwarded verbatim as normal gameplay text (and therefore have no special meaning at the proxy layer).
- **Without any `SESSION` envelope** – all lines, including `LOGIN`, are forwarded verbatim to the gateway; the proxy does not drop or delay gameplay commands.
- **With a valid `SESSION` envelope** – once the first valid `SESSION` line is parsed, the connection is bound to that `{gameInstanceId, tenantId}` pair for its lifetime and those identifiers are propagated via headers and metrics. The envelope window closes immediately after a valid capture; any subsequent `SESSION` lines are forwarded as normal text and do not rebind the connection.
- **Malformed `SESSION` lines** – if either `gameInstanceId` or `tenantId` is missing or is not a valid UUID, the line is logged and ignored; no error is sent back to the client. Clients that choose to use `SESSION` may resend a corrected envelope as long as the envelope window is still open, or they may proceed with `LOGIN` only. Each malformed envelope also increments a per-connection counter; once the number of malformed envelopes exceeds `TCP_PROXY_MAX_MALFORMED_ENVELOPES`, the proxy closes the connection as abusive and emits the corresponding metrics.
- **Diagnostics mode (future enhancement)** – a debug/diagnostics mode may surface
  explicit warnings or errors for malformed or repeated `SESSION` envelopes to
  help advanced Telnet clients detect configuration issues. Until that mode ships,
  the behavior above (one-shot binding, silent ignores up to the configured malformed‑envelope budget) is considered canonical.

#### Security considerations for `{gameInstanceId, tenantId}`

The proxy treats `gameInstanceId` and `tenantId` from the `SESSION` envelope as
client-provided claims, not trusted facts. It forwards them as headers and
metrics context, but the Game Session Service is responsible for enforcing
multi-tenant safety:

- `tenantId` is validated against the authenticated account’s allowed tenants
  during `LOGIN` and subsequent session binding.
- Session ownership is checked so a client cannot bind to or resume another
  user’s game instance, even if it guesses a valid `gameInstanceId`.
- Any mismatch between the envelope’s `{gameInstanceId, tenantId}` and the account’s
  known sessions/tenants is treated as a cross-tenant hijack attempt, rejected,
  and logged with enough context for audit/alerting (without leaking sensitive
  credentials).
- While gameplay lobby admission is single-instance-per-tenant (`gameInstanceId="primary"`), non-`primary` `gameInstanceId` values from `SESSION` are forwarded only as advisory context and must not influence admission decisions. This mismatch should be observable via a bounded metric/log signal so stale clients can be migrated.

Metrics give observability into each Telnet connection while keeping
Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}` increments on
  connection open/close, with only low-cardinality labels (such as `type`).
- `tcpproxy.connection.duration` (a Micrometer `Timer`) records the wall-clock
  lifetime of sockets so dashboards can highlight long-running connections
  without embedding per-session identifiers in label values.
- `grpc_app_error_total{code="<code>"}` is incremented whenever the
  `TcpProxyEventService` observes an error from `NotifyDisconnect`, with
  `code` drawn from a bounded enum.

Detailed identifiers such as `gameInstanceId` and client IP are captured in
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
the allowed-command set are discarded, but MCP control lines and payloads
are forwarded verbatim to the gateway as sanitized text. MCP-capable clients
should therefore expect their standard MCP exchanges to arrive intact while not
relying on arbitrary Telnet option negotiation beyond the documented command
subset.

At the protocol level, the proxy treats Telnet control bytes as follows:

- A fixed set of Telnet commands are allowed and understood:
  - `SE` (End of subnegotiation parameters) – byte `240`
  - `NOP` (No operation) – byte `241`
  - `GA` (Go ahead) – byte `249`
  - `WILL` – byte `251`
  - `WONT` – byte `252`
  - `DO` – byte `253`
  - `DONT` – byte `254`
- Commands outside this set are discarded and only sanitized printable characters are forwarded to the gateway.
- For Telnet subnegotiation (`IAC SB ... IAC SE`), the proxy consumes the entire subnegotiation block up to the matching `SE` and does not surface any of its bytes as gameplay text when the option is unsupported. Well-formed `SB`/`SE` sequences for unsupported options are ignored cleanly; malformed or truncated subnegotiations may increment diagnostic counters but must not inject partial control bytes into the line-based command stream.

- Hard abuse signals include:
  - Line-length floods or repeated lines exceeding `TCP_PROXY_MAX_LINE_BYTES`.
  - Repeated malformed `SESSION` envelopes that drive the per-connection counter past `TCP_PROXY_MAX_MALFORMED_ENVELOPES`.
  - Excessive connection churn from the same IP that collides with global connection-limit policy (`TCP_PROXY_MAX_CONNECTIONS` / `TCP_PROXY_MAX_CONNECTIONS_PER_IP`).
- Diagnostic-only signals include:
  - Isolated malformed `SESSION` envelopes that do not trip the malformed-envelope budget.
  - Unknown MCP packages or malformed MCP control lines; these may be logged for debugging but must not be treated as abuse on their own.

Within those constraints, the proxy preserves compatibility while still enforcing a small, hardened Telnet surface.

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

For Telnet subnegotiation (`IAC SB ... IAC SE`), the proxy treats unsupported or unknown options as transport-only noise: the Telnet pipeline **consumes the entire subnegotiation block up to the matching `SE`** and does not surface any of its bytes as gameplay text. Well-formed `SB`/`SE` sequences for unsupported options are ignored cleanly; malformed or truncated subnegotiations may increment diagnostic counters but must not inject partial control bytes into the line-based command stream.

#### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation (for example GMud, TinTin++, and similar tools) are expected to work without special configuration.
- Clients that depend on advanced Telnet options (for example dynamic window sizing or terminal-type negotiation) should treat those features as best-effort: unsupported options are ignored rather than causing the connection to fail.
- MCP-aware clients should assume that MCP negotiation and messages are the primary extensibility mechanism. Telnet options remain deliberately constrained to reduce the attack surface at the DMZ edge.

### MCP Resource Limits & Abuse Budgets

To keep MCP traffic from overwhelming the Telnet edge while still being friendly to well-behaved tools, the TCP Proxy Service enforces **MCP-specific budgets** in addition to the generic Telnet connection and line limits. **Status:** some of these limits and their observability hooks are still being hardened; see the **Implementation Status** table and `design/project-management/task-list-tcp-proxy-service.md` for the current implementation state.

- Each connection has a bounded number of **active cords** and **concurrent `_data-tag` continuations**. Once these limits are exceeded, new MCP control lines are discarded and counted in `tcpproxy.telnet.discarded` with a low-cardinality `reason` label (for example `reason="mcp_budget"`), but the Telnet connection itself may remain open as long as other safety limits are respected.
- MCP control-line volume is also subject to a per-connection **MCP control-line rate** budget. When a client sends MCP control lines significantly faster than expected (for example due to a misbehaving script), excess lines are dropped rather than forwarded, again contributing to `tcpproxy.telnet.discarded{reason="mcp_budget"}` instead of being treated as immediate hard-close abuse.
- MCP line size still participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits, but **MCP parsing failures do not count towards the `TCP_PROXY_MAX_MALFORMED_ENVELOPES` budget**, which is reserved for Telnet `SESSION` envelope errors as described in **Telnet Session Envelope & Event Metrics**.

The exact thresholds for these MCP budgets are environment-specific and may evolve over time. Implementations configure them via `TCP_PROXY_MCP_*` knobs (see **Environment Variables**) and should treat them as guardrails that keep obviously misbehaving MCP clients from overwhelming the proxy while remaining generous enough that normal tools never hit them in practice.

Metrics and diagnostics for these budgets integrate with the existing observability surface:

- `tcpproxy.telnet.discarded{reason="mcp_budget"}` increments whenever MCP control lines are dropped due to active-cord, continuation, or control-line-rate budgets.
- `tcpproxy.mcp.negotiation_failures` increments when MCP negotiation parsing/handshake validation fails; once failures exceed `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` within `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS`, the connection is closed with `policy_violation`.
- A small set of MCP-focused meters provide additional visibility without adding high-cardinality labels:
  - `tcpproxy.mcp.control_lines` – counter for MCP control lines processed per connection.
  - `tcpproxy.mcp.discarded` – counter for MCP control lines discarded before reaching the gateway (mirrors the `mcp_budget` subset of `tcpproxy.telnet.discarded` in some deployments).
  - `tcpproxy.mcp.active_cords` – gauge for the number of active MCP cords per connection.

Operators should treat sustained increases in MCP-related discard signals as a prompt to either:

- Adjust client behaviour (for example reduce cord churn or update frequency), or
- Tighten MCP-specific budgets and, if necessary, block or rate-limit obviously abusive sources at the network or gateway layer.

Normal Telnet abuse detection remains focused on Telnet control bytes, malformed `SESSION` envelopes, connection churn, and generic line-size limits. MCP parsing errors and unknown MCP packages are **diagnostic-only** by design; they must not, on their own, cause connections to be hard-closed.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### TLS & Trust Surfaces (Summary)

The TCP Proxy Service participates in three distinct TLS / trust boundaries:

| Surface | Direction | Purpose | Key configuration |
| --- | --- | --- | --- |
| Telnet plaintext or Telnet‑over‑TLS | Client ↔ TCP Proxy Service | Player Telnet connections from legacy MUD clients. Plaintext is an intentional, hardened legacy channel; Telnet‑over‑TLS is preferred when clients support it. | `TCP_PROXY_PORT` for plaintext; `TCP_PROXY_TLS_ENABLED`, `TCP_PROXY_TLS_PORT`, `TCP_PROXY_TLS_CERT`, `TCP_PROXY_TLS_KEY` for Telnet‑over‑TLS. |
| WebSocket mTLS bridge | TCP Proxy Service ↔ Spring Cloud Gateway | Internal WebSocket hop that normalizes Telnet traffic into the same `/ws/game/**` route used by web clients. Uses mutual TLS in the target state so the gateway can authenticate the proxy and safely promote `X-Proxy-*` headers. | `GATEWAY_WS_URL`, `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, `FIREMUD_GATEWAY_WS_CA_CERT_PATH`. |
| Internal gRPC mTLS | Game Session Service (and other internal clients) ↔ TCP Proxy Service | Internal‑only gRPC endpoints such as `Ping` and the `NotifyDisconnect` event sink; no player traffic flows directly here. | `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`. |

Telnet‑over‑TLS and WebSocket mTLS may reuse the same certificate files in very small deployments, but they represent different trust surfaces and should be managed as separate concerns in production. See [Security Architecture](../../system-architecture-security.md#tls-termination-for-gateway) for the cluster‑wide TLS topology and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for how these surfaces fit into the end‑to‑end Telnet and WebSocket flow.

> **Certificate reuse guidance:** The default environment variable paths for WebSocket mTLS (`FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`) and internal gRPC mTLS (`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`) may point at the same files in local or hobby deployments. In production and other shared environments, operators should provision **separate certificates and keys per surface** and override these defaults accordingly so a compromise in one trust surface does not automatically extend to the others.

### Data Model

The proxy is stateless in the sense that it does not own any authoritative gameplay or session state. Any buffered input lives only in memory until forwarded
to the Spring Cloud Gateway while the Telnet connection is still active. Optional Redis-backed caches (as described under **Redis Role and Prefixes**) are treated as derived, non-authoritative state: they may be empty or unavailable without affecting correctness, and all session recovery behaviour is governed by the Game Session Service and Redis keys documented in the [Reconnection Strategy](../../system-architecture-reconnection.md) and [Redis Architecture](../../system-architecture-redis.md) docs.

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
- `tcpproxy.connections.limit.exceeded` for rejected connections when global/per-IP caps are reached.
- `tcpproxy.connection.events{type="connect"|"disconnect"}` and `tcpproxy.connection.duration` for connection lifecycle and lifetime tracking.
- `tcpproxy.command`, `tcpproxy.heartbeat`, `tcpproxy.idleClose`, and `tcpproxy.websocket.reconnect.delay` timers, plus `tcpproxy.websocket.reconnects` counters, for Telnet → Gateway bridge behaviour.
- `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason="..."}` for TLS and mTLS failures. The `reason` label is a small, bounded enum:
  - `bad_url` – invalid `GATEWAY_WS_URL` configuration.
  - `dns` – host resolution failure.
  - `connect_refused` – target actively refused the TCP connection.
  - `timeout` – connect or handshake timed out.
  - `cert_validation` – server certificate validation or hostname verification failure.
  - `client_cert_missing` – server requested client auth but no client certificate was configured.
  - `client_cert_invalid` – client certificate present but rejected/invalid.
  - `handshake_protocol` – TLS/WebSocket handshake protocol error (for example unsupported versions/ciphers or HTTP upgrade rejection).
  - `unexpected_close` – connection closed during handshake/reconnect.
  - `unknown` – fallback bucket for unexpected failures.
- `tcpproxy.telnet.discarded` and related `tcpproxy.disconnect.notify.*` counters for abuse and error visibility.
  - **Transport vs application failures are separated** so dashboards stay unambiguous and align with [gRPC API Style & Versioning](../../system-architecture-grpc.md#error-handling):
    - `tcpproxy.disconnect.notify.transport_failure{status="<grpc_status>"}` increments whenever a `NotifyDisconnect` attempt fails with a non-OK gRPC status (for example `UNAVAILABLE`, `DEADLINE_EXCEEDED`, TLS handshake failures). These are retried within the configured `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` window and counted once per failed attempt.
    - `tcpproxy.disconnect.notify.app_error{code="<code>"}` increments when the gRPC transport returns `OK` but the `NotifyDisconnectResponse.error.code` field is not `OK`. These are treated as permanent, contract-level outcomes and are **not retried**.
    - The shared `grpc_app_error_total{code="<code>"}` meter is reserved for application-level error codes (not transport statuses). It may be incremented alongside `tcpproxy.disconnect.notify.app_error` when the proxy receives a non-OK `NotifyDisconnectResponse.error.code`.

In Prometheus these Micrometer meters appear with the expected naming
translation, for example:

- `tcpproxy.connections.active` → `tcpproxy_connections_active`
- `tcpproxy.connections.limit.exceeded` → `tcpproxy_connections_limit_exceeded_total`
- `tcpproxy.telnet.discarded` → `tcpproxy_telnet_discarded_total`
- `tcpproxy.websocket.reconnects` → `tcpproxy_websocket_reconnects_total`

The example PromQL and Alertmanager rules in
`design/observability/grafana/tcp-proxy-alerts-snippets.md` use these
Prometheus-style names; treat this section as the canonical list of meters and
the Grafana snippets as reference queries over them.

Labels on these metrics are intentionally low-cardinality (for example `type`, and occasionally `tenantId`)
to keep Prometheus usage aligned with the global guidelines. Detailed context such as client IP, `gameInstanceId`,
and error details is captured in structured logs and tracing spans rather than metric labels.

### Operational Runbook Hooks

When wiring alerts and runbooks for the TCP Proxy Service, focus on a small set of edge-centric indicators and interpret them alongside Gateway and Game Session signals:

- **Capacity and churn**
  - Alert when `tcpproxy.connections.limit.exceeded` sustains non-zero values, especially if `tcpproxy.connections.active` or `tcpproxy.connections.total` are close to expected capacity; this usually indicates either insufficient proxy replicas or an overly aggressive per-IP cap.
  - Watch `tcpproxy.connections.active` and `tcpproxy.connection.duration` distributions for sudden drops in median lifetime or spikes in very short-lived connections, which can indicate abusive clients or misconfigured health checks hammering the Telnet port.
- **Abuse and discard behaviour**
  - Track `tcpproxy.telnet.discarded` and its low-cardinality `reason` label (for example `reason="line_size"`, `reason="malformed_envelope"`, `reason="mcp_budget"`). A sharp increase in any one reason should trigger investigation into either client behaviour (for example buggy scripts) or limit misconfiguration.
  - Combine discard alerts with application-layer metrics (for example Game Session per-tenant quotas and Gateway rate limits) to decide whether to add capacity, tighten limits, or block specific sources.
- **Gateway and TLS health**
  - Alert on sustained `tcpproxy.gateway.handshake.failures{reason!="timeout"}` and on long tails in `tcpproxy.websocket.reconnect.delay`; together these often indicate certificate, DNS, or listener misconfigurations between the proxy and Spring Cloud Gateway.
  - Cross-check these alerts with Gateway health and TLS/mTLS metrics from the Security and Logging & Monitoring docs so incidents are triaged at the correct layer (proxy vs gateway vs cluster networking).
- **NotifyDisconnect health**
  - Monitor `tcpproxy_disconnect_notify_transport_failure_total` and the associated `grpc_app_error_total{code="<code>"}` meter for spikes in `UNAVAILABLE` or `DEADLINE_EXCEEDED`, which may indicate Game Session outages or network issues on the internal gRPC path.
  - Treat sustained increases in permanent error codes (for example `INVALID_ARGUMENT`, `PERMISSION_DENIED`, `FAILED_PRECONDITION`) as configuration or contract issues rather than transient incidents; runbooks should direct operators to inspect Game Session logs and configuration when this happens.
  - Treat `RESOURCE_EXHAUSTED` spikes as consumer-side overload and check dedupe-capacity guardrails and Game Session saturation before changing retry settings.

Runbooks should always pair these TCP Proxy metrics with corresponding Spring Cloud Gateway and Game Session dashboards. Edge symptoms such as elevated discard counts or connection churn often originate from higher-layer changes (for example new rate limits or authentication flows), and resolving them in isolation at the proxy can mask the real cause.

### Local development and echo loop

There are two common local flows, depending on whether you want to test the full Telnet → WebSocket bridge or run the proxy completely standalone.

**1. Proxy + Gateway `/dev/echo` (bridge test)**

Use the bundled `/dev/echo` WebSocket endpoint under the `dev` profile to validate the Telnet → WebSocket bridge:

1. Start the proxy: `./gradlew :tcp-proxy-service:bootRun`. The task defaults to the `dev` profile for local runs. Override with `SPRING_PROFILES_ACTIVE=<profile>` or `-Dspring.profiles.active=<profile>` when needed.
2. Start Spring Cloud Gateway with the dev profile so `/dev/echo` is exposed (see the Gateway docs for details).
3. Point the bridge at the local echo: `GATEWAY_WS_URL=ws://localhost:8080/dev/echo`.
4. Connect from a Telnet/MUD client: `telnet localhost 2323`.
5. Type any text. You should see the same text echoed back via the Gateway `/dev/echo` handler. Avoid logging raw Telnet input during this workflow (especially `LOGIN` lines); if you enable deep payload logging for debugging, it must be explicitly opt-in and redact credentials.

### Proxy dev-isolated mode (standalone echo)

When `TCP_PROXY_DEV_ISOLATED=true`, the proxy runs with an in-process Telnet echo handler:

1. Start the proxy in dev-isolated mode: `./gradlew :tcp-proxy-service:bootRunDevIsolated`. This sets `spring.profiles.active=dev` and `TCP_PROXY_DEV_ISOLATED=true`.
2. The proxy no longer opens a WebSocket connection to the gateway. It echoes subsequent commands directly back over the Telnet session while still allowing advanced clients to send an optional `SESSION <gameInstanceId> <tenantId>` envelope. In this mode, `SESSION` parsing exercises only the envelope capture and logging behaviour; it does **not** perform real attach-to-session or Redis-backed resume flows.
3. Connect from a Telnet/MUD client: `telnet localhost 2323`.
4. Send a few commands (for example `LOOK`, `SAY hello`). Watch the Telnet output to verify that input is sanitized and echoed without requiring Spring Cloud Gateway, Game Session, or any other services. Do not use real credentials in this echo mode, and do not log raw input unless it is explicitly enabled and credential-redacted.

Prefer containers? A minimal Docker Compose profile launches just the proxy in dev-isolated mode. Start it with `docker compose -f docker/docker-compose.tcp-proxy-devisolated.yml --profile tcp-proxy-devisolated up` and in another terminal run `telnet localhost 2323`. Stop the stack with `docker compose -f docker/docker-compose.tcp-proxy-devisolated.yml --profile tcp-proxy-devisolated down` when finished.

When pointing at a real gateway in non-dev-isolated mode, override `GATEWAY_WS_URL` with its WebSocket endpoint. Do not rely on the `ws://spring-cloud-gateway:8080/ws/game` default in production; production deployments must configure `GATEWAY_WS_URL` to target the Gateway’s internal-only WebSocket mTLS listener.

## Environment Variables

The proxy uses minimal configuration. It still follows the scheme in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
so the standard `FIREMUD_POSTGRES_*` and `FIREMUD_REDIS_*` variables may be present
but the proxy does not use PostgreSQL. If Redis-backed proxy caches are enabled, configure the proxy to use the Cache/Rate-Limit Redis deployment (see `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` in the environment and secrets docs). TLS certificates are supplied via the standard shared paths where applicable:

- The proxy’s **internal gRPC server mTLS** uses [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).
- The **Proxy → Gateway WebSocket mTLS** hop uses `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_WS_CA_CERT_PATH` so operators can provision and rotate this identity independently from gRPC.

Additional variables control the proxy runtime behaviour. TLS‑related settings are grouped by surface, and it is helpful to think of them in terms of **local development shortcuts** versus **production‑critical settings**:

- **Telnet listener TLS (client ↔ proxy)**:
  - `TCP_PROXY_TLS_ENABLED` – enable Telnet‑over‑TLS termination.
  - `TCP_PROXY_TLS_PORT` – TCP port for the Telnet‑over‑TLS listener.
  - `TCP_PROXY_TLS_CERT` – path to the Telnet listener TLS certificate.
  - `TCP_PROXY_TLS_KEY` – path to the Telnet listener TLS private key.
  - In local development it is acceptable to expose only a plaintext Telnet port for convenience; in shared or production environments, Telnet-over-TLS should be offered and plaintext Telnet must follow the hardening and policy rules in `design/architecture/system-architecture-security.md#telnet-command-handling-and-controls` (2FA requirements, per-account opt-in, and landing-menu warnings).
- **Proxy → Gateway WebSocket mTLS (proxy ↔ Spring Cloud Gateway)**:
  - `GATEWAY_WS_URL` – WebSocket URL for forwarding to the gateway (for example `ws://spring-cloud-gateway:8080/ws/game` in local dev or `wss://...` in player-facing environments).
  - `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` – client certificate chain path used by the WebSocket client in mTLS configurations.
  - `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` – client private key path used by the WebSocket client in mTLS configurations.
  - `FIREMUD_GATEWAY_WS_CA_CERT_PATH` – CA bundle path for verifying the gateway certificate.
  - In development and CI it is fine to use `ws://` targets without mTLS; in production and other shared environments, `GATEWAY_WS_URL` must point at the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) and the `FIREMUD_GATEWAY_WS_*` paths must be configured so the proxy can authenticate the Gateway identity and present its own client certificate. See also the SAN and handshake-failure details in [Protocol Bridging](../../system-architecture-protocol-bridging.md#websocket-bridge-configuration) and the `tcpproxy.gateway.handshake.failures{reason=\"cert_validation\"}` metric.
- **Internal gRPC server mTLS (other services ↔ proxy gRPC)**:
  - The same `FIREMUD_GRPC_*` variables are reused by the proxy’s gRPC server for mutual TLS on internal-only diagnostics RPCs such as `Ping`.

The full variable list is (treat this table as the canonical source of defaults and behaviour for `TCP_PROXY_*`; other docs intentionally summarize and link here instead of redefining them):

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `TCP_PROXY_PROXY_PROTOCOL_PORT` | TCP port for the PROXY-protocol Telnet listener (internal-only; reachable only from the Telnet edge proxy) | `2325` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway; in the dev profile the application falls back to `ws://spring-cloud-gateway:8080/ws/game` when this variable is unset, but player-facing environments must set it explicitly (see **Production invariants**) | *(none; must be set explicitly outside of local/dev)* |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_PORT` | TCP port for the Telnet-over-TLS listener | `2324` |
| `TCP_PROXY_TLS_CERT` | Path to the Telnet listener TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the Telnet listener TLS private key | *(empty)* |
| `TCP_PROXY_DEV_ISOLATED` | Enable dev-isolated echo mode (bypasses Gateway/Game Session and echoes sanitized Telnet input locally; see **Proxy dev-isolated mode (standalone echo)**); intended for local development only | `false` |
| `TCP_PROXY_MAX_CONNECTIONS` | Maximum concurrent Telnet connections (`0` or unset = no explicit ceiling; **never use `0` in shared/player-facing environments**) | `0` |
| `TCP_PROXY_MAX_CONNECTIONS_PER_IP` | Maximum concurrent Telnet connections per client IP (`0` or unset = no explicit ceiling; **never use `0` in shared/player-facing environments**); accurate client IPs require source-IP preservation or PROXY protocol (see [Deployment Environments](../../infrastructure/deployment-environments.md)) | `0` |
| `TCP_PROXY_MAX_LINE_BYTES` | Maximum accepted Telnet/MCP line (including MCP control lines and continuation lines) in bytes before rejection/closure | `4096` |
| `TCP_PROXY_MAX_OVERSIZE_LINES` | Maximum oversized lines per connection before hard close | `10` |
| `TCP_PROXY_MAX_MALFORMED_ENVELOPES` | Maximum malformed `SESSION` envelopes per connection before hard close (see **Telnet Session Envelope & Event Metrics** for how this counter is applied) | `5` |
| `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` | Maximum total time after Telnet socket close during which the proxy retries failed `NotifyDisconnect` calls before giving up | `5000` |
| `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` | Maximum time to retry bridge recovery before closing with `backend_unavailable` (applies to initial bridge establishment and established-session `unreachable` state) | `5000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` | Continuous upstream-unreachable duration required to open the bridge-availability circuit breaker and fast-reject new Telnet admissions with `backend_unavailable` | `3000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_HALF_OPEN_MAX_PROBES` | Maximum concurrent bridge probe attempts while the circuit breaker is half-open before returning to open on failure | `3` |
| `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` | Consecutive successful half-open bridge probes required before returning to closed admission | `3` |
| `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` | Maximum buffered Telnet lines waiting to be forwarded to the gateway due to upstream backpressure; if this ceiling is exceeded the proxy closes the Telnet connection with `policy_violation` and emits `edge_backpressure` context in logs/metrics rather than silently dropping gameplay commands | `64` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` | Maximum MCP negotiation failures allowed per connection within the MCP negotiation failure window before closing as `policy_violation` | `5` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS` | MCP negotiation failure rolling window duration in milliseconds | `60000` |
| `TCP_PROXY_MCP_MAX_ACTIVE_CORDS` | Maximum concurrent MCP cords per connection before MCP control lines are discarded with `reason="mcp_budget"` | `16` |
| `TCP_PROXY_MCP_MAX_ACTIVE_DATA_TAGS` | Maximum concurrent MCP multiline `_data-tag` continuations per connection before MCP control lines are discarded with `reason="mcp_budget"` | `16` |
| `TCP_PROXY_MCP_MAX_CONTROL_LINES_PER_SEC` | Maximum MCP control-line processing rate per connection before excess lines are discarded with `reason="mcp_budget"` | `50` |
| `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` | Client certificate chain path for Proxy → Gateway WebSocket mTLS | `certs/client.crt` |
| `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Client private key path for Proxy → Gateway WebSocket mTLS | `certs/client.key` |
| `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | CA bundle path for verifying the gateway certificate on the WebSocket hop | `certs/ca.crt` |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Certificate chain path for the proxy’s internal gRPC server mTLS | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Private key path for the proxy’s internal gRPC server mTLS | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle path for verifying gRPC peers | `certs/ca.crt` |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for tracing; shared across services | `http://otel-collector:4317` |

These certificate and observability variables are shared with other services; see
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
for full details.

The proxy may expose both a raw Telnet listener (`TCP_PROXY_PORT`) and a TLS-wrapped Telnet listener (`TCP_PROXY_TLS_PORT`) at the same time (when `TCP_PROXY_TLS_ENABLED=true`). In production, exposing the raw Telnet port directly on the public internet is treated as a **legacy, plaintext channel**: credentials and gameplay traffic may be observed in transit, so additional safeguards apply. When
`FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled (the default), logins
over this plaintext Telnet port are only permitted for accounts that both:

- Have TOTP-based two-factor authentication enabled, and
- Opt in to **allow plaintext Telnet login** in their account settings (the
  checkbox/option defaults to off and explains the risk).

Plaintext Telnet connections are also tagged so the Game Session Service can
emit a landing-menu security warning recommending the TLS Telnet port or web
client instead. TLS Telnet endpoints and the web client are not subject to this
additional restriction.

When PROXY protocol is enabled in production, expose `TCP_PROXY_PROXY_PROTOCOL_PORT` only on an internal-only surface behind the Telnet edge proxy. Do not publish the PROXY-protocol listener directly as a public `LoadBalancer` port.

The gRPC server listens on port `6565` by default as configured in `src/main/resources/application.yml`.

**Production invariants (summary):**

- Set `GATEWAY_WS_URL` to a `wss://.../ws/game` URL that targets the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) in any shared/player-facing environment; do not run player-facing environments with a `ws://` Proxy → Gateway hop, even if the dev profile’s fallback URL happens to use `ws://`.
- Override `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` to non-zero values in shared/player-facing environments, sized to expected load as described in **Tuning TCP Proxy for Different Environments**; the `0` defaults are for local/dev and CI only.
- Treat the raw Telnet listener (`TCP_PROXY_PORT`) as a legacy, plaintext channel even when exposed, and prefer either Telnet-over-TLS (`TCP_PROXY_TLS_PORT`) or the web client for general use. When using PROXY protocol, expose `TCP_PROXY_PROXY_PROTOCOL_PORT` only on an internal-only surface behind the Telnet edge proxy and never as a public `LoadBalancer` port.

### WebSocket mTLS to Spring Cloud Gateway

In production, the TCP Proxy Service connects to Spring Cloud Gateway over
`wss://` using mutual TLS by dialing a dedicated internal-only Gateway WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`):

- Client certificate and key are loaded from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` and `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`.
- The Gateway’s certificate is validated against `FIREMUD_GATEWAY_WS_CA_CERT_PATH`,
  with hostname verification enabled using the host from `GATEWAY_WS_URL`.
- Certificate changes are picked up via the shared `TlsCertificateWatcher`
  so WebSocket clients can reload credentials without restarts.

The WebSocket client certificate must include the `clientAuth` extended key usage. This is intentionally decoupled from the proxy’s internal gRPC server certificate profile, which must include `serverAuth`.

TLS handshake failures are fail-closed: the proxy does not fall back to
plaintext. Instead it logs errors and increments a dedicated metric
(for example `tcpproxy.gateway.handshake.failures{reason="cert_validation"}`),
and Telnet connections fail-close if the proxy cannot establish the initial Proxy → Gateway bridge within `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS`. For established sessions where the bridge drops, the proxy applies the bridge state machine and closes with `backend_unavailable` if recovery does not complete within that window. See
[System Architecture: Security](../../system-architecture-security.md) for
certificate issuance and rotation details, and
[Environment & Secrets – TLS & Certificates](../../infrastructure/environment-and-secrets-catalog.md#tls--certificates)
for the shared TLS environment variable catalog and defaults.

When overriding `GATEWAY_WS_URL` in a `wss://` configuration, the host portion
of the URL is used for both SNI and hostname verification. If you point
`GATEWAY_WS_URL` at an IP address or a hostname that is not present in the
Gateway certificate’s SANs, the TLS handshake fails with
`reason="cert_validation"` and no insecure fallback occurs. In cluster-internal
deployments, prefer the Kubernetes DNS name for the Gateway service (for
example `wss://spring-cloud-gateway-mtls.<namespace>.svc.cluster.local:8443/ws/game`) and issue certificates for that name. For external access, use a public hostname that matches the Gateway’s certificate rather than switching to a bare IP.

> **Environment policy (normative):**
>
> - Proxy → Gateway gameplay traffic uses mTLS in all shared and player-facing environments.
> - Player-facing environments (staging/prod) must fail startup or admission if Proxy → Gateway mTLS identity verification is unavailable.

### Metrics & Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The
service exports OpenTelemetry spans to the collector defined by
`OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)) so traces appear in Jaeger.

### Connection Limits and Abuse Protection

Edge protection at the TCP Proxy layer combines several types of limits:

> This section focuses on how operators tune and interpret the limits described earlier under **Connection Limits and Abuse Protection** in the Architecture / Design Notes. If there is ever a discrepancy between that earlier section and this tuning guidance, treat the earlier section as the canonical source of behavioural requirements; this section is intentionally descriptive and operational in tone.

- **Connection ceilings** – `TCP_PROXY_MAX_CONNECTIONS` caps total Telnet sockets on a pod, and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` caps the number of concurrent connections per client IP. In NAT-heavy environments it is acceptable to keep the per-IP cap relatively high or unset, but only when Spring Cloud Gateway rate limiting and Game Session per-IP/per-session quotas are enabled and monitored; do not run with both proxy per-IP limits and higher-layer quotas effectively disabled.
- **Line and envelope limits** – `TCP_PROXY_MAX_LINE_BYTES`, `TCP_PROXY_MAX_OVERSIZE_LINES`, and `TCP_PROXY_MAX_MALFORMED_ENVELOPES` bound the impact of oversized commands and repeated malformed `SESSION` envelopes. Once these budgets are exhausted, the connection is treated as abusive and hard-closed.
- **MCP-specific budgets** – additional per-connection limits on MCP active cords, continuations, and control-line rates sit on top of the Telnet limits, as described in **MCP Resource Limits & Abuse Budgets**. Exceeding these budgets drops MCP control lines and contributes to `tcpproxy.telnet.discarded{reason="mcp_budget"}` while generally keeping the underlying Telnet connection open.

These controls are designed to be layered with, not a replacement for, higher-level protections:

- Spring Cloud Gateway still owns HTTP and WebSocket rate limiting and global abuse filters.
- Game Session Service still owns per-account, per-session, and per-tenant quotas.

Use the **Tuning TCP Proxy for Different Environments** section below for recommended starting values and adjust over time based on observed metrics such as `tcpproxy.connections.limit.exceeded`, `tcpproxy.telnet.discarded`, and MCP-related discard reasons.

### Tuning TCP Proxy for Different Environments

The connection and envelope limits exposed via `TCP_PROXY_MAX_CONNECTIONS`,
`TCP_PROXY_MAX_CONNECTIONS_PER_IP`, and `TCP_PROXY_MAX_MALFORMED_ENVELOPES`
are intended to be tuned per environment.

The Proxy → Gateway WebSocket bridge retry budget and input buffer depth (`TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` and `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES`) should be sized to match expected gateway availability characteristics and typical player command rates:

- Shorter retry budgets (for example `1000`–`3000` ms) minimize how long Telnet sockets wait for the proxy to establish the initial WebSocket bridge when Gateway is unavailable, but will drop clients more aggressively during deploys or outages. Longer retry budgets reduce “connection refused” flaps at the cost of holding the Telnet socket open slightly longer before failing closed.
- `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` should reflect how many recent gameplay commands you are willing to queue at the DMZ edge when the upstream WebSocket send path is backpressured (for example 32–128 lines), balanced against memory usage and the requirement that gameplay commands are not dropped silently while a connection remains open. If the buffer fills, the proxy closes the Telnet connection with `policy_violation`, emits `edge_backpressure` context, and increments `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}` so buffer-driven disconnects appear explicitly in dashboards and the Telnet degraded runbook.
- When tuning these values, watch `tcpproxy.websocket.reconnects`, `tcpproxy.websocket.reconnect.delay`, and `tcpproxy.telnet.discarded` (including the `gateway_buffer_full` breakdown) over a few releases to ensure you are not either buffering too aggressively or disconnecting legitimate players too often during normal deploy cycles.

Bridge-availability circuit-breaker settings should also be tuned explicitly:

- `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` (recommended default `3000`) controls how long upstream gameplay unreachability must persist before the breaker opens and new Telnet admissions are fast-rejected as `backend_unavailable`.
- `TCP_PROXY_GATEWAY_CIRCUIT_HALF_OPEN_MAX_PROBES` (recommended default `3`) controls how many concurrent bridge probe attempts are allowed while half-open.
- `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` (default `3`) controls how many consecutive successful half-open probe bridge establishments are required before returning to closed admission.

### Recommended Dev Defaults

- `TCP_PROXY_MAX_CONNECTIONS=50` – small cap to catch runaway local clients.
- `TCP_PROXY_MAX_CONNECTIONS_PER_IP=10` – enough for multiple terminals per developer.
- `TCP_PROXY_MAX_MALFORMED_ENVELOPES=10` – generous tolerance while iterating on tools or tests.
- It is acceptable to use `ws://` for `GATEWAY_WS_URL` in local/docker-compose setups; mTLS is not required in dev, but be cautious when forwarding dev traffic over the public Internet.
- In dev/CI-only environments it is permissible to leave `TCP_PROXY_MAX_CONNECTIONS` / `TCP_PROXY_MAX_CONNECTIONS_PER_IP` at `0` while iterating locally, but any shared or player-facing deployment must override these to non-zero values as outlined below.

### Minimum Viable Prod Hardening

- Size `TCP_PROXY_MAX_CONNECTIONS` to expected concurrent players plus a safety margin (for example `500`–`1000` depending on cluster size).
- Set `TCP_PROXY_MAX_CONNECTIONS_PER_IP=3`–`5` so individual IPs cannot exhaust the connection pool while still allowing multiple windows per player.
- Use `TCP_PROXY_MAX_MALFORMED_ENVELOPES=5` so connections that repeatedly send bad `SESSION` lines are closed as abusive.
- Strongly prefer `wss://` with mutual TLS for `GATEWAY_WS_URL` so the Proxy → Gateway hop always uses mTLS in production; fall back to `ws://` only in tightly controlled internal test environments.

### Heavier Deployments

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
- [User Journeys – Player Login and Gameplay](../../user-journeys-players.md#3-player-login-and-gameplay)
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
- `NotifyDisconnect(NotifyDisconnectRequest) returns (NotifyDisconnectResponse)` – implemented by the Game Session Service as an internal-only event sink that the TCP Proxy Service calls when a Telnet client disconnects; it is not exposed as a TCP Proxy inbound RPC.

All RPC definitions live in [`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -cacert "$FIREMUD_GRPC_CA_CERT_PATH" \
  -cert "$FIREMUD_GRPC_CERT_CHAIN_PATH" \
  -key "$FIREMUD_GRPC_PRIVATE_KEY_PATH" \
  localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

##### Local dev shortcuts

When running the proxy gRPC server without TLS/mTLS (dev-only), you can use:

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
