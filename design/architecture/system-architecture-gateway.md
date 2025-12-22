# Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

---

## Gateway Pattern

**Spring Cloud Gateway** serves as the **single entry point** into the FireMUD system for all **external client traffic**:

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- For admin APIs the Gateway forwards JWTs to backend services without validating them. Player login and session binding are processed by the **Game Session Service**; see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the detailed flow.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no sticky sessions required
- Auto‑scaling policies handle high concurrency
- Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; the Gateway
  itself does not hold session state between reconnects
- Gateway restarts automatically re-establish WebSocket connections to backend services. See [Reconnection Strategy](./system-architecture-reconnection.md).
- The Gateway and TCP Proxy Service run in the **network DMZ** and are the only ingress points for clients. NetworkPolicies restrict direct access to internal services. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design) for details.

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

### Authentication Responsibilities

- Spring Cloud Gateway never parses or validates JWTs. It only enforces the presence of an `Authorization` header on selected admin routes and forwards tokens unchanged.
- All JWT validation and authorization logic lives in downstream admin and meta services (such as the Logging & Admin Service and Account Service), which must treat Spring Cloud Gateway as a dumb proxy and may not assume it has performed any authentication checks.

---

## WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

At a configuration level, Spring Cloud Gateway defines WebSocket routes in `application.yml` (and profile-specific route files) and applies filters (such as rate limiting and retries) before forwarding to backend services. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for the authoritative description of route configuration, service discovery overrides, and gateway-related environment variables.

### Gameplay WebSocket Route

- **Canonical route path** – `/ws/game/**` is the canonical gameplay WebSocket entry point for both native WebSocket clients and Telnet clients bridged via the TCP Proxy Service. The `/api/session/**` predicate remains a legacy alias and is kept only for backward compatibility.
- **Telnet bridge usage** – The TCP Proxy Service connects to Spring Cloud Gateway using the `GATEWAY_WS_URL` environment variable, which by default points at `ws://spring-cloud-gateway:8080/ws/game`. In production deployments this value is set to `wss://…/ws/game` so the proxy–gateway hop is always encrypted.
- **Required headers** – Spring Cloud Gateway preserves or sets:
  - `X-Client-IP` with the originating client address (Telnet clients via the TCP Proxy Service; web clients via the external load balancer).
  - `X-Session-Id` and `X-Tenant-Id` when provided by advanced Telnet clients via the `SESSION` envelope, so the Game Session Service can correlate gameplay with Redis session state.
  - Standard correlation and trace headers defined in the logging/observability guidelines.
- **TLS expectations**
  - External clients connect over `wss://` to the public load balancer, which forwards to Spring Cloud Gateway as described in [Security Architecture](./system-architecture-security.md#tls-termination--internal-encryption).
  - The TCP Proxy Service connects to `/ws/game/**` using `wss://` with mutual TLS in production; plain `ws://` is reserved for local/dev-only flows.

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

> **Redis topology guidance:** Sharing a single Redis instance for both
> Coordination Redis and Cache/Rate‑Limit Redis is acceptable only for local
> development and very small hobby deployments. For any player-facing
> environment where you expect more than a handful of concurrent players or
> sustained HTTP/WebSocket traffic, configure the Gateway to use a **separate
> Cache/Rate‑Limit Redis deployment** so rate limiting and cache activity cannot
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
  - Gateway rate limiting is primarily **per-client IP** with optional route-level differentiation. The default `RequestRateLimiter` configuration uses the Cache/Rate‑Limit Redis deployment and derives keys from the client IP (as seen by the gateway after load balancer and TCP Proxy headers) and route ID, keeping key cardinality modest.
  - Game Session Service enforces **per-session and per-command** limits (for example, commands per tick region) using Redis coordination keys. See [Reconnection Strategy](./system-architecture-reconnection.md) and [Redis Architecture](./system-architecture-redis.md) for session/tick-level controls.
- **WebSocket vs HTTP semantics**
  - Spring Cloud Gateway’s Redis-backed `RequestRateLimiter` is applied to **connection establishment and discrete HTTP requests**, not to every WebSocket frame. This prevents Telnet/WebSocket gameplay traffic from being throttled as if each frame were a separate HTTP call.
  - Once a WebSocket connection is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level rate limiting; downstream services (especially Game Session Service) enforce per-session and per-command safety.
- **Edge vs core responsibilities**
  - The **TCP Proxy Service** enforces **connection-level and per-socket safety** for Telnet clients: idle timeouts, per-IP connection caps, buffer depth limits, and basic abuse heuristics. It relies on Spring Cloud Gateway and Game Session Service for cross-tenant and content-aware rate limiting.
  - **Spring Cloud Gateway** enforces **request- and connection-creation limits** using the Cache/Rate‑Limit Redis instance configured via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`, protecting backend services from floods of new connections or HTTP calls.
  - The **Game Session Service** applies **fine-grained gameplay limits** (per-session command rates, login attempt throttling, and region-level protections) so in-game abuse is handled close to business logic.

This layered model avoids over-counting Telnet/WebSocket frames while still protecting the platform: the gateway guards connection churn and HTTP floods, the TCP Proxy Service governs raw Telnet behavior, and the Game Session Service enforces gameplay-specific policies.

## Multi-Tenancy at the Gateway

Spring Cloud Gateway does not implement tenant-aware routing or isolation logic itself; it acts as a tenant-agnostic edge that forwards tenant metadata to the services that own multi-tenant behavior:

- Tenant identity (`tenantId`) is derived and enforced by backend services as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md), not by Spring Cloud Gateway.
- Gameplay flows may include tenant markers such as:
  - `X-Tenant-Id` and `X-Session-Id` headers injected by the TCP Proxy Service when advanced Telnet clients send a `SESSION` envelope.
  - Session and tenant context inferred by the Game Session Service from the `LOGIN` flow and Redis session keys.
- Spring Cloud Gateway preserves these headers and forwards them unchanged to backend services but does not:
  - Derive `tenantId` from hostnames or URL paths.
  - Enforce per-tenant routing tables or access control.
  - Apply per-tenant rate-limit overrides.
- All tenant isolation, quotas, and policy enforcement (for example, per-tenant session limits or resource quotas) are implemented in domain services such as the Game Session Service and Account Service, following the rules in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

## TLS Termination for Gateway

- **Browser / Web clients** – External `https://` / `wss://` connections terminate at the Internet-facing load balancer. The load balancer forwards `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ, and the gateway connects onward to backend services over mTLS-protected gRPC as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway).
- **Telnet clients** – Telnet or Telnet-over-TLS connections terminate at the TCP Proxy Service. The proxy then connects to the canonical gameplay route `/ws/game/**` on Spring Cloud Gateway over `wss://` using mutual TLS, and the gateway forwards gameplay to the Game Session Service over mTLS gRPC. Detailed certificate and environment variable mappings are documented in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway) and [Protocol Bridging](./system-architecture-protocol-bridging.md#websocket-bridge-configuration).

## Management Plane Security

- Spring Cloud Gateway exposes REST and gRPC management endpoints (such as dynamic route operations and `GatewayManagementService` RPCs) **only on internal network surfaces**, not via the public player-facing ingress.
- In Kubernetes, these endpoints are reachable only from inside the cluster or a dedicated admin network segment via `ClusterIP` Services, private ingress, and `NetworkPolicy` rules; the public Service/Ingress is limited to HTTP/WebSocket data-plane traffic.
- Authentication and authorization for these management endpoints follow the same mTLS and JWT patterns described in [Security Architecture](./system-architecture-security.md#tls-termination--internal-encryption) and [Admin Interface Access Model](./system-architecture-security.md#admin-interface-access-model). Implementation details and port-level separation are documented in the [Spring Cloud Gateway service README](./microservices/spring-cloud-gateway/README.md#management-plane-security).

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
`SPRING_PROFILES_ACTIVE` configure routing targets based on environment.

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Spring Cloud Gateway Service Details](./microservices/spring-cloud-gateway/README.md)

---
