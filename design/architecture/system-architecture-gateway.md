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

Example WebSocket route config (current default path `/api/session/**`; a `/ws/game/**` alias is available):

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: game-session
          uri: ws://game-session-service:8080
          predicates:
            - Path=/api/session/**,/ws/game/**
```

The gateway uses the `ws://` scheme so Spring Cloud Gateway upgrades HTTP requests into WebSocket connections automatically. The `ClientIpHeaderFilter` copies or preserves the `X-Client-IP` header during the handshake so backend services (and the TCP Proxy bridge) can rely on it when routing gameplay sessions.

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
- `application.yml` defines `RequestRateLimiter` and `Retry` filters that apply to every route by default.
  - The rate limiter stores tokens in Redis. The gateway connects to the **Cache/Rate‑Limit Redis** deployment via
    `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`, keeping rate
    limiting isolated from tick/session coordination as described in the Redis architecture. All environments configure this cache endpoint explicitly; there is no fallback to a generic Redis host/port.

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
