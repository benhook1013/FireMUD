# Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

## Gateway Pattern

**Spring Cloud Gateway** serves as the **single HTTP(S)/WebSocket entry point** into the FireMUD system for all **external client traffic** that speaks HTTP or WebSocket. Traditional Telnet/TCP clients enter via the dedicated TCP Proxy Service as described in [Protocol Bridging](./system-architecture-protocol-bridging.md); together, Spring Cloud Gateway (for HTTP/WebSocket) and the TCP Proxy Service (for Telnet/TCP) form the public edge of the platform. The behaviour of this edge – including ordering guarantees, backpressure, and reconnection semantics for gameplay command streams – is defined canonically in [Protocol Bridging](./system-architecture-protocol-bridging.md); this document focuses on gateway responsibilities and defers to that design for detailed client-path invariants.

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- For admin APIs the Gateway forwards JWTs to backend services without validating them. Player login and session binding are processed by the **Game Session Service**; see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the detailed flow and the [Tenant Authorization Contract](./system-architecture-authentication.md#tenant-authorization-contract) for how downstream services enforce tenant access.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no sticky sessions required
- Auto‑scaling policies handle high concurrency
  - Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; Spring Cloud Gateway itself does not hold session state between reconnects
  - Spring Cloud Gateway restarts **disconnect WebSocket clients**; browsers and other WebSocket tools must open a fresh WebSocket connection and issue `LOGIN` again. Once reconnected, the gateway resumes routing and the Game Session Service uses Redis-backed state to decide whether to resume or start fresh as described in [Reconnection Strategy](./system-architecture-reconnection.md#resume-vs-reload-scenarios). The gateway does not maintain hidden, long‑lived WebSocket tunnels across its own restarts or attempt to replay in‑flight messages; edge delivery remains per‑connection FIFO and at‑most‑once as defined in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
- The Gateway and TCP Proxy Service run in the **network DMZ** and are the only ingress points for clients. NetworkPolicies restrict direct access to internal services. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design) for details.

> **Implementation status note (Telnet WebSocket mTLS):**
> The TCP Proxy → Gateway WebSocket mTLS behaviour described in this document reflects the **target-state** production design. The current rollout status and any remaining gaps are tracked in the TCP Proxy Service design’s **Implementation Status** table (`./microservices/tcp-proxy-service/README.md#implementation-status`). When you encounter discrepancies, align implementation with that design and this document rather than weakening mTLS requirements.
>
> **Important:**
> Spring Cloud Gateway is responsible for routing **only external client requests**.
> **Internal microservice-to-microservice communication does not pass through the Gateway**.
> Microservices use Kubernetes native service discovery and DNS for direct communication.
> Services communicate with each other over **gRPC**.
> See [System Architecture Overview](./system-architecture-overview.md) and [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the complete login and gRPC flow.

- Static URIs configured in the `dev` profile within `application.yml`
  (used by Docker Compose)
- Kubernetes DNS-based service names configured in the `prod` profile of
  `application.yml` (used in production)
- Initial routes are loaded on startup from `routes-dev.yml` or `routes-prod.yml` via `spring.config.import`.
- Initial route targets are loaded on startup, but operators can override them using environment variables prefixed `FIREMUD_SERVICES_`, matching the `ServiceEndpointsProperties` approach used by other microservices. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#service-discovery).
- Dynamic route management APIs (REST and gRPC) can add or remove routes at runtime as **ephemeral overrides** layered on top of the baseline configuration; config files remain the canonical source of truth for route definitions.

For the Telnet-to-WebSocket bridge – including the `GATEWAY_WS_URL` contract, Proxy → Gateway mutual TLS, and how gameplay traffic is normalized through `/ws/game/**` – treat [Protocol Bridging](./system-architecture-protocol-bridging.md) as the **canonical specification**. This document summarizes the gateway’s routing and configuration responsibilities and defers to the protocol-bridging design for detailed edge semantics.

### Authentication Responsibilities

- Spring Cloud Gateway never parses or validates JWTs. It only enforces the presence of an `Authorization` header on selected admin routes and forwards tokens unchanged.

### Header Trust Model

Spring Cloud Gateway acts as the canonicalizer for client identity and session/tenant headers. It defines a strict trust boundary between **public ingress**, the **Telnet TCP Proxy**, and **internal services**:

- Headers that may carry client or session identity (`X-Client-IP`, `X-Session-Id`, `X-Tenant-Id`, and all `X-Proxy-*` headers) are **never trusted directly from public clients**. The gateway strips any such headers that arrive from the external load balancer or browser/WebSocket clients before routing to backend services.
- For HTTP/WebSocket clients, the gateway derives `X-Client-IP` from a small, environment‑specific set of load-balancer headers (for example, `X-Forwarded-For` and `X-Real-IP`) plus the immediate peer address. The exact header list and precedence rules are defined in the [Security Architecture](./system-architecture-security.md#network-security--boundary-design), but the invariant is that downstream services treat only the gateway-produced `X-Client-IP` as authoritative.
- For Telnet traffic, the TCP Proxy Service supplies `X-Proxy-Client-IP`, `X-Proxy-Session-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` on the mTLS-authenticated WebSocket hop. After verifying the TCP Proxy client certificate on the internal WebSocket mTLS listener, the gateway:
  - Derives canonical `X-Client-IP` from `X-Proxy-Client-IP` (and any PROXY-provided metadata) and overwrites any existing `X-Client-IP`.
  - Promotes `X-Proxy-Session-Id` and `X-Proxy-Tenant-Id` to canonical `X-Session-Id` and `X-Tenant-Id` where appropriate for gameplay routes.
  - Preserves `X-Proxy-Connection-Id` as an opaque correlation identifier for disconnect events and observability.
- Downstream services must treat `X-Client-IP`, `X-Session-Id`, and `X-Tenant-Id` as **gateway-owned** headers. Services must ignore or overwrite any attempts by callers to spoof these values via gRPC metadata or additional HTTP headers and should rely on their own Redis/session keys for authoritative identity as described in [Authentication & Authorization](./system-architecture-authentication.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md).
- All JWT validation and authorization logic lives in downstream admin and meta services (such as the Logging & Admin Service and Account Service), which must treat Spring Cloud Gateway as a dumb proxy and may not assume it has performed any authentication checks.

---

## Header Trust Model Details

Spring Cloud Gateway is the **canonicalization point** for any client-identity and session-hint headers. Downstream services (including the Game Session Service) treat these headers as meaningful only because **the gateway produced them after applying trust rules**, not because an upstream client provided them.

### Upstream Inputs (`X-Proxy-*`)

These headers are treated as **untrusted inputs** unless the gateway has authenticated the upstream hop as the TCP Proxy Service:

- `X-Proxy-Client-IP` – the Telnet client IP address as observed by the TCP Proxy Service (ideally recovered via PROXY protocol from the Telnet edge proxy in Kubernetes SNAT scenarios).
- `X-Proxy-Connection-Id` – server-generated identifier for the Telnet socket, used to correlate `NotifyDisconnect` events with authenticated sessions.
- `X-Proxy-Session-Id` / `X-Proxy-Tenant-Id` – advisory context captured from the optional Telnet `SESSION <sessionId> <tenantId>` envelope.

### Public Ingress Strip/Drop Rules

For any connection that arrives from the public player/admin ingress, Spring Cloud Gateway **strips** all spoofable client/session headers before routing:

- `X-Client-IP`
- `X-Session-Id`, `X-Tenant-Id`
- `X-Proxy-Client-IP`, `X-Proxy-Connection-Id`, `X-Proxy-Session-Id`, `X-Proxy-Tenant-Id`

### TCP Proxy → Gateway Authentication

The TCP Proxy → Gateway hop uses **mutual TLS (mTLS)** by connecting to a dedicated **internal-only** Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). Spring Cloud Gateway treats the upstream hop as authenticated as the TCP Proxy Service only when:

- The presented client certificate chains to the cluster trust root (cert-manager under ClusterIssuer `firemud-ca-issuer`), and
- The certificate contains an expected SAN identity for the TCP Proxy Service (for example a URI SAN such as `spiffe://firemud/ns/<namespace>/sa/tcp-proxy-service`, or a DNS SAN such as `tcp-proxy-service.<namespace>.svc.cluster.local`).

If either check fails, the gateway rejects the WebSocket handshake and does not promote any `X-Proxy-*` inputs.

Until mTLS is fully deployed for the TCP Proxy → Gateway hop, treat any non-mTLS acceptance of `X-Proxy-*` headers as a **temporary dev-only stopgap**, protected by strict internal-only network exposure and NetworkPolicies. Do not rely on “internal network” alone for player-facing environments.

### Gateway Output Rules (Downstream-Trusted)

After applying strip/authentication rules, the gateway sets or forwards the downstream-facing headers:

- `X-Client-IP` – canonical client IP address:
  - If the upstream hop is authenticated as the TCP Proxy Service and `X-Proxy-Client-IP` is present, set `X-Client-IP` from `X-Proxy-Client-IP`.
  - Otherwise derive `X-Client-IP` from the trusted load balancer forwarded headers (for example `X-Forwarded-For`) using the gateway’s configured trusted-proxy rules.
- `X-Proxy-Connection-Id` – forwarded only when the upstream hop is authenticated as the TCP Proxy Service so downstream services can correlate lifecycle signals.
- `X-Session-Id` / `X-Tenant-Id` – forwarded only when the upstream hop is authenticated as the TCP Proxy Service and the corresponding `X-Proxy-Session-Id` / `X-Proxy-Tenant-Id` inputs were provided. These remain advisory session hints; the Game Session Service validates any session/tenant claims against Redis and authenticated session state.

---

## WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

At a configuration level, Spring Cloud Gateway defines WebSocket routes in `application.yml` (and profile-specific route files) and applies filters (such as rate limiting and retries) before forwarding to backend services. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for the authoritative description of route configuration, service discovery overrides, and gateway-related environment variables. For gameplay login and session semantics, this document defers to the canonical flow in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow); this doc focuses on transport-level responsibilities only.

### Gameplay WebSocket Route

- **Canonical route path** – `/ws/game/**` is the canonical gameplay WebSocket entry point for both native WebSocket clients and Telnet clients bridged via the TCP Proxy Service.
- **Telnet bridge usage** – The TCP Proxy Service connects to Spring Cloud Gateway using the `GATEWAY_WS_URL` environment variable, which by default points at `ws://spring-cloud-gateway:8080/ws/game`. In production deployments this value must be set to a `wss://.../ws/game` URL that targets the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mTLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway). Do not rely on the `ws://` default in player-facing environments. Exact `SESSION` envelope semantics and header propagation rules are defined in the TCP Proxy design’s **Telnet Session Envelope & Event Metrics** section; this document intentionally summarizes only the routing side.
- **Required headers** – Spring Cloud Gateway preserves or sets:
  - `X-Client-IP` with the originating client address. For web clients this is derived from the external load balancer’s forwarded headers. For Telnet clients this is derived by the gateway from `X-Proxy-Client-IP` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - `X-Proxy-Session-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` on the TCP Proxy → Gateway hop when advanced Telnet clients provide a `SESSION` envelope or when the proxy needs disconnect correlation. The gateway strips these from public ingress and only forwards canonical `X-Session-Id` / `X-Tenant-Id` and `X-Proxy-Connection-Id` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - Standard correlation and trace headers defined in the logging/observability guidelines.
- **TLS expectations**
  - External clients connect over `wss://` to the public load balancer, which forwards to Spring Cloud Gateway as described in [Security Architecture](./system-architecture-security.md#tls-termination--internal-encryption).
  - The TCP Proxy Service connects to `/ws/game/**` using `wss://` with mutual TLS to the Gateway’s internal-only WebSocket mTLS listener in production; plain `ws://` is reserved for local/dev-only flows.

### WebSocket Liveness and Idle Timeouts

Gameplay WebSocket connections are long-lived but not unbounded. To avoid half-open sessions and to make idle behaviour predictable:

- Spring Cloud Gateway (or the underlying WebSocket container) sends periodic WebSocket `ping` frames on `/ws/game/**` (for example every 30 seconds) while connections are idle.
- If no `pong` or other traffic is observed for a configured idle window (for example 90 seconds), the connection is closed with a clear close reason. Load balancers or CDNs in front of the gateway must be configured with idle timeouts greater than this window so they do not terminate connections more aggressively than the gateway itself.
- Web clients are not required to send their own application-level heartbeats, but they may do so; they must be prepared for the gateway to close idle or unreachable connections according to these limits and to follow the reconnection rules in [Reconnection Strategy](./system-architecture-reconnection.md).

For gameplay WebSocket sessions, FireMUD standardises a small set of close codes and reasons so clients and operators can interpret failures consistently. These codes and reasons define the canonical categories for WebSocket closures; Telnet disconnect messages on the TCP Proxy Service map directly onto the same categories as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons):

- `1000` with reason `logout` – explicit, clean shutdown (for example, user-initiated logout or admin‑initiated session end where no error occurred).
- `1001` with reason `idle_timeout` – idle-connection timeout where the gateway or Game Session has not observed traffic within its configured idle window.
- `1008` with reason `policy_violation` – client behaviour that violates platform policies (for example, sustained command‑rate abuse, malformed frames, repeated protocol violations, or sustained slow-client behaviour at the network edge where send buffers repeatedly overflow or time out).
- `1011` with reason `internal_error` – unexpected server‑side failures that are not attributable to the client and are not clearly a backend‑unavailable condition.
- `1013` with reason `backend_unavailable` – Spring Cloud Gateway has concluded that backend services needed for gameplay are unavailable or overloaded beyond a short tolerance window (see [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios)).

Gateway and Game Session implementations must choose one of these codes/reasons when closing gameplay WebSocket sessions for platform‑initiated reasons, and should log the mapped category and contributing metrics (for example `gateway.websocket.close_reason` and `gamesession.connection.closed{reason=...}`) so operations teams can distinguish idle timeouts, policy enforcement, backend outages, and internal errors.

To keep behaviour consistent and avoid double-closing sessions, ownership of these close codes is divided as follows:

- `logout` (`1000`) – Game Session owns explicit gameplay logouts and admin‑initiated session ends; Gateway may also use `1000` for graceful shutdown of its own listener when draining connections, but should not reinterpret Game Session’s logout decisions.
- `idle_timeout` (`1001`) – The layer that observes the idle condition first closes the connection: Game Session for application‑level idle (for example no gameplay traffic from a client that is otherwise reachable), or Gateway/WebSocket container for network‑level idle (no frames or pongs within the configured idle window). Other layers treat the close as a peer shutdown and do not wrap it in a second close reason.
- `policy_violation` (`1008`) – The layer that detects the violation closes with `policy_violation`. Gateway uses this for HTTP or WebSocket protocol abuse (for example frame shape violations on `/ws/game/**`); Game Session uses it for gameplay/content‑level abuse (for example sustained command‑rate or scripting violations); the TCP Proxy maps Telnet‑side `policy_violation` disconnects into this category via the Telnet reason taxonomy in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).
- `internal_error` (`1011`) – Any layer that encounters an unexpected server‑side error (not clearly attributable to the client and not covered by `backend_unavailable`) closes with `internal_error` and logs the underlying failure. Other layers treat it as a generic peer failure and avoid emitting a second, conflicting close reason for the same session.
- `backend_unavailable` (`1013`) – Gateway owns closing WebSocket sessions when core gameplay backends are continuously unavailable beyond the configured grace window. Game Session surfaces its own health via metrics and health checks; Gateway uses that information plus its own routing failures to decide when to send `1013/backend_unavailable` as described in [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios). Telnet clients see the corresponding `backend_unavailable` Telnet reason from the TCP Proxy.

### Backend-Unavailable Grace Window

Spring Cloud Gateway applies a small grace window before closing WebSocket sessions due to sustained backend outages so that brief flaps do not cause unnecessary reconnects:

- The `firemud.gateway.backendUnavailableGraceMs` configuration property controls how long (in milliseconds) Gateway tolerates continuous backend failures for gameplay routes before closing affected WebSocket sessions with `1013/backend_unavailable`. “Continuous failure” here means that all attempts to call Game Session for that route are failing with `UNAVAILABLE`/5xx responses or failing Gateway’s configured health checks; any successful call or healthy check resets the internal backend-unavailable timer to zero.
- Gateway starts the backend-unavailable timer when a route first enters this “all failed” state. Within the `firemud.gateway.backendUnavailableGraceMs` window, Gateway keeps existing WebSocket sessions open and surfaces backend errors as per-command failures, allowing clients to remain connected while the platform recovers from short blips.
- When the backend has been continuously unavailable beyond `firemud.gateway.backendUnavailableGraceMs` without any successful calls or healthy checks, Gateway must close affected gameplay WebSocket sessions with `1013/backend_unavailable` and reject new `/ws/game/**` connections with HTTP `503` so clients can apply the reconnection and backoff rules defined in [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour).
- Load balancers or CDNs in front of Gateway should be configured with idle and failure timeouts that do not undercut this grace window; otherwise, they may terminate connections before Gateway can emit the canonical `backend_unavailable` signal.

---

## Telnet / TCP Bridging

- Traditional MUD clients connect via **raw TCP** (Telnet protocol)
- These connections are terminated by a **dedicated TCP Proxy Service** outside of Spring Cloud Gateway
- The TCP Proxy Service acts as a **bridge**, creating a WebSocket connection through the Gateway to normalize legacy TCP traffic
  - Spring Cloud Gateway itself only handles **HTTP** and **WebSocket** traffic

This pattern ensures all real-time gameplay is unified through WebSocket on the backend, regardless of client type.

---

## Centralized Gateway Benefits

Spring Cloud Gateway provides centralized management of client traffic, offering:

- JWTs presented on admin or REST endpoints are validated by the consuming service. Gameplay clients do not provide tokens.
- Cross-cutting filters (e.g., rate limiting, logging, CORS)

> **Redis topology guidance:** Coordination Redis and Cache/Rate‑Limit Redis are
> always deployed as **separate Redis instances** (for example, two containers
> on a single dev machine or distinct pods/clusters in Kubernetes). Sharing a
> single Redis instance for both roles is considered an unsupported experiment.
> For any player-facing environment, configure the Gateway to use the dedicated
> **Cache/Rate‑Limit Redis deployment** so rate limiting and cache activity cannot
> interfere with tick/session coordination. See
> [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection)
> and [Redis Architecture](./system-architecture-redis.md#redis-profiles) for
> reference profiles.

- Service isolation through route-based access control
- Easy expansion of routes for new microservices
- TLS termination and mTLS between services are described in [Security Architecture](./system-architecture-security.md).

## Rate Limiting & Abuse Protection

Spring Cloud Gateway and the TCP Proxy Service share responsibility for protecting the platform from abusive traffic:

- **Keying strategy**
  - Gateway rate limiting is primarily **per-client IP** with optional route-level differentiation. The default `RequestRateLimiter` configuration uses the Cache/Rate‑Limit Redis deployment and derives keys from the client IP (as seen by the gateway after load balancer and TCP Proxy headers) and route ID, keeping key cardinality modest while still following the canonical `ratelimit:<tenantId>:<bucket>:<timeWindow>` key pattern from [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md#rate-limit-bucket-design). For the gateway itself, `tenantId` is a synthetic, edge-scope identifier (for example `gateway-edge`), and `bucket` incorporates the client IP and route identifier via a stable hash.
  - Game Session Service enforces **per-session and per-command** limits (for example, commands per tick region) using Redis coordination keys. See [Reconnection Strategy](./system-architecture-reconnection.md) and [Redis Architecture](./system-architecture-redis.md) for session/tick-level controls.
- **WebSocket vs HTTP semantics**
  - Spring Cloud Gateway’s Redis-backed `RequestRateLimiter` is applied to **connection establishment and discrete HTTP requests**, not to every WebSocket frame. This prevents Telnet/WebSocket gameplay traffic from being throttled as if each frame were a separate HTTP call.
  - Once a WebSocket connection is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level rate limiting; downstream services (especially Game Session Service) enforce per-session and per-command safety.
- **Gameplay WebSocket handshake errors**
  - HTTP `429` responses from `/ws/game/**` indicate that edge rate or connection limits have been exceeded (for example, RequestRateLimiter buckets or per-IP connection quotas). Gateway uses `429` only for these rate/policy conditions, and clients should treat this as equivalent to a `policy_violation` outcome as described in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - HTTP `503` responses from `/ws/game/**` indicate that Gateway currently considers gameplay backends unavailable according to the `firemud.gateway.backendUnavailableGraceMs` logic described in [Backend-Unavailable Grace Window](./system-architecture-gateway.md#backend-unavailable-grace-window). In these cases Gateway may also close existing WebSocket sessions with `1013/backend_unavailable`, and clients should treat `503` as equivalent to `1013` for backoff behaviour, per [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
- **Edge vs core responsibilities**
  - The **TCP Proxy Service** enforces **connection-level and per-socket safety** for Telnet clients: idle timeouts, per-IP connection caps, buffer depth limits, and basic abuse heuristics. It relies on Spring Cloud Gateway and Game Session Service for cross-tenant and content-aware rate limiting.
  - **Spring Cloud Gateway** enforces **request- and connection-creation limits** using the Cache/Rate‑Limit Redis instance configured via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`, protecting backend services from floods of new connections or HTTP calls.
  - The **Game Session Service** applies **fine-grained gameplay limits** (per-session command rates, login attempt throttling, and region-level protections) so in-game abuse is handled close to business logic.

This layered model avoids over-counting Telnet/WebSocket frames while still protecting the platform: the gateway guards connection churn and HTTP floods, the TCP Proxy Service governs raw Telnet behavior, and the Game Session Service enforces gameplay-specific policies.

## Multi-Tenancy at the Gateway

Spring Cloud Gateway does not implement tenant-aware routing or isolation logic itself; it acts as a tenant-agnostic edge that forwards tenant metadata to the services that own multi-tenant behavior:

- Tenant identity (`tenantId`) is derived and enforced by backend services as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md), not by Spring Cloud Gateway.
- Gameplay flows may include tenant markers such as:
  - `X-Proxy-Session-Id` / `X-Proxy-Tenant-Id` inputs on the TCP Proxy → Gateway hop when advanced Telnet clients send a `SESSION` envelope. The gateway strips these from public ingress and only forwards canonical `X-Session-Id` / `X-Tenant-Id` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - Session and tenant context inferred by the Game Session Service from the `LOGIN` flow and Redis session keys.
- Spring Cloud Gateway preserves these headers and forwards them unchanged to backend services but does not:
  - Derive `tenantId` from hostnames or URL paths.
  - Enforce per-tenant routing tables or access control.
  - Apply per-tenant rate-limit overrides.
- All tenant isolation, quotas, and policy enforcement (for example, per-tenant session limits or resource quotas) are implemented in domain services such as the Game Session Service and Account Service, following the rules in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

## TLS Termination for Gateway

- **Browser / Web clients** – External `https://` / `wss://` connections terminate at the Internet-facing load balancer. The load balancer forwards `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ. The gateway then routes traffic to backend services using in-cluster `http://` and `ws://` targets (typically on port `8080`), while backend services communicate with each other over mTLS-protected gRPC as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway).
- **Telnet clients** – Telnet (plaintext) or Telnet-over-TLS connections terminate at the TCP Proxy Service. The proxy then connects to the canonical gameplay route `/ws/game/**` on Spring Cloud Gateway by dialing the Gateway’s internal-only WebSocket mTLS listener over `wss://` with mutual TLS. Spring Cloud Gateway forwards gameplay to the Game Session Service over the same WebSocket route (`/ws/game/**`). Detailed certificate and environment variable mappings are documented in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway) and [Protocol Bridging](./system-architecture-protocol-bridging.md#websocket-bridge-configuration).

## Management Plane Security

- Spring Cloud Gateway exposes REST and gRPC management endpoints (such as dynamic route operations and `GatewayManagementService` RPCs) **only on internal network surfaces**, not via the public player-facing ingress.
- In Kubernetes, these endpoints are reachable only from inside the cluster or a dedicated admin network segment via `ClusterIP` Services, private ingress, and `NetworkPolicy` rules; the public Service/Ingress is limited to HTTP/WebSocket data-plane traffic.
- Authentication and authorization for these management endpoints is enforced at the gateway boundary: operator tooling must connect using mutual TLS (mTLS) client certificates (issued by cert-manager under ClusterIssuer `firemud-ca-issuer`, with `clientAuth` EKU), and only trusted operator identities are permitted to invoke management operations. JWT-based admin roles apply to product/admin APIs behind the gateway, but gateway-owned management endpoints do not rely on downstream services for authorization. Implementation details and the recommended internal-only exposure model are documented in the [Spring Cloud Gateway service README](./microservices/spring-cloud-gateway/README.md#management-plane-security).

## Observability

All gateway gRPC endpoints are instrumented with the shared `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor`.
WebSocket traffic is tracked using the `ConnectionMetricsFilter`. By default, tracing for WebSocket sessions records **connection-level metadata only** (for example, route ID, tenant, session identifiers, and basic timing) without full text payloads.
Full request/response payload tracing for WebSocket sessions is treated as an **opt‑in diagnostic mode**: it is disabled in player‑facing environments and, when enabled for debugging, must use aggressive sampling and redaction as described in [Logging & Monitoring](./system-architecture-logging-monitoring.md).

## Internal gRPC Communication

Internal services communicate directly with each other over **gRPC**.
Spring Cloud Gateway does not handle these calls. Each service discovers
its peers via Docker or Kubernetes DNS and connects using the service name.
This approach minimizes latency and matches the protocol table in the
[System Architecture Overview](./system-architecture-overview.md#communication-flows).

---

## Dev vs. Prod Configuration

| Environment | Route Target Format | Discovery Mechanism |
| --- | --- | --- |
| Dev | `http://service:8080` | Docker Compose DNS |
| Prod | `http://service.namespace.svc.cluster.local:8080` | Kubernetes DNS |

Spring profiles defined in `application.yml` and selected via
`SPRING_PROFILES_ACTIVE` configure routing targets based on environment. Baseline routes are defined in `routes-dev.yml` and `routes-prod.yml`, and any `FIREMUD_SERVICES_*` environment variable overrides or dynamic route changes apply *on top* of these files rather than replacing them as the canonical source of truth.

---

## Gateway Network Surfaces

The following table summarizes the main network surfaces exposed or used by Spring Cloud Gateway. Detailed TLS and authentication requirements are documented in [Security Architecture](./system-architecture-security.md) and the Spring Cloud Gateway service design.

| Surface | Direction | Protocol(s) | Typical Port(s) | Auth / TLS Expectations |
| --- | --- | --- | --- | --- |
| Public player/admin ingress → Spring Cloud Gateway | Inbound | `HTTP(S)`, `WS(S)` | Load balancer ports (for example, `80`/`443`) | TLS terminates at the Internet-facing load balancer; gateway receives `http://` / `ws://` as described in [TLS Termination for Gateway](./system-architecture-security.md#tls-termination-for-gateway). |
| TCP Proxy Service → Spring Cloud Gateway gameplay route | Inbound (internal only) | `WS(S)` | Gateway internal mTLS port (for example, `8443`) | Mutual TLS is required on the internal-only WebSocket listener; the gateway authenticates the TCP Proxy Service by verifying the client certificate chains to the cluster CA and carries the expected SAN identity before promoting any `X-Proxy-*` inputs. The host in `GATEWAY_WS_URL` must match the gateway certificate’s SANs. |
| Spring Cloud Gateway → backend services | Outbound | `HTTP`, `WS` | `8080` (typical) | In-cluster hop; protected by NetworkPolicies and namespace boundaries. Backend services handle JWT validation/authorization as applicable; gameplay traffic remains on the `/ws/game/**` WebSocket route to the Game Session Service. |
| Spring Cloud Gateway management plane (REST/gRPC) | Inbound (internal only) | `HTTP(S)`, gRPC | `8080` (REST), `6565` (gRPC) | Exposed only on internal surfaces (`ClusterIP` / private ingress); management operations require mTLS client certificates and are authorized at the gateway boundary (not delegated to downstream services). |

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Spring Cloud Gateway Service Details](./microservices/spring-cloud-gateway/README.md)

---
