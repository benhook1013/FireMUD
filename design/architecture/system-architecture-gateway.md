# 🔀 Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

---

## 🚪 Gateway Pattern

**Spring Cloud Gateway** serves as the **single entry point** into the FireMUD system for all **external client traffic**:

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- For admin APIs the Gateway forwards JWTs to backend services without validating them. Gameplay login is processed by the **Game Session Service**; see [Authentication & Authorization](../system-architecture-authentication.md#-login-and-session-flow) for the detailed flow.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no sticky sessions required
- Auto‑scaling policies for high concurrency are planned. (TODO: Not yet implemented)
- Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; the Gateway
  itself does not hold session state between reconnects
- Gateway restarts are expected to automatically re-establish WebSocket connections to backend services. See [Reconnection Strategy](../system-architecture-reconnection.md). (TODO: Not yet implemented)
- The Gateway and TCP Proxy Service run in the **network DMZ** and are the only ingress points for clients. NetworkPolicies restrict direct access to internal services. See [Security Architecture](../system-architecture-security.md#🌐-network-security--boundary-design) for details.

> **Important:**
> Spring Cloud Gateway is responsible for routing **only external client requests**.
> **Internal microservice-to-microservice communication does not pass through the Gateway**.
> Microservices use Kubernetes native service discovery and DNS for direct communication.
> Services communicate with each other over **gRPC**.
> See [System Architecture Overview](../system-architecture-overview.md) and [Authentication & Authorization](../system-architecture-authentication.md#-login-and-session-flow) for the complete login and gRPC flow.

- Static URIs configured in the `dev` profile within `application.yml`
  (used by Docker Compose)
- Kubernetes DNS-based service names configured in the `prod` profile of
  `application.yml` (used in production)
- Initial routes are loaded on startup from `routes-dev.yml` or `routes-prod.yml` via `spring.config.import`.
- Initial route targets are fixed. Future versions will allow per-service overrides using environment variables prefixed `FIREMUD_SERVICES_`, matching the `ServiceEndpointsProperties` approach used by other microservices. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#service-discovery). (TODO: Not yet implemented)

---

## 🔷 WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

Example WebSocket route config (current default path `/api/session/**`; a `/ws/game/**` alias is planned): (TODO: Not yet implemented)

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: game-session
          uri: ws://game-session-service:8080
          predicates:
            - Path=/api/session/**
```

---

## 🔌 Telnet / TCP Bridging

- Traditional MUD clients connect via **raw TCP** (Telnet protocol)
- These connections are terminated by a **dedicated TCP Proxy Service** outside of Spring Cloud Gateway
- The TCP Proxy Service acts as a **bridge**, creating a WebSocket connection through the Gateway to normalize legacy TCP traffic
  - Spring Cloud Gateway itself only handles **HTTP** and **WebSocket** traffic

This pattern ensures all real-time gameplay is unified through WebSocket on the backend, regardless of client type.

---

## 🔐 Centralized Gateway Benefits

Spring Cloud Gateway provides centralized management of client traffic, offering:

- JWTs presented on admin or REST endpoints are validated by the consuming service. Gameplay clients do not provide tokens.
- Cross-cutting filters (e.g., rate limiting, logging, CORS)
- `application.yml` defines `RequestRateLimiter` and `Retry` filters that apply to every route by default.
  - The rate limiter stores tokens in Redis; the gateway reads `FIREMUD_REDIS_HOST` and `FIREMUD_REDIS_PORT` for this connection.
- Service isolation through route-based access control
- Easy expansion of routes for new microservices
- TLS termination and mTLS between services are described in [Security Architecture](../system-architecture-security.md).

## ⚙️ Dynamic Route Management

The gateway supports **runtime configuration** of custom routes. Operators can
add, update, or remove routes using either the REST API (`/routes`) or the
`GatewayManagementService` gRPC API. This allows on‑the‑fly changes without
restarting the service. See the
[Spring Cloud Gateway microservice documentation](./microservices/spring-cloud-gateway/README.md#rest--grpc-endpoints)
for example requests and supported fields. The gRPC interface is defined in [`gateway_management_service.proto`](../../protos/spring-cloud-gateway/v1/gateway_management_service.proto) and the REST schema in [`openapi.yaml`](../../services/spring-cloud-gateway/src/main/resources/openapi.yaml).
Dynamic routes are stored only in memory and are lost on service restart. A PostgreSQL `route_config` table exists but is not yet used for persistence. (TODO: Not yet implemented)
The gRPC management API listens on port `6565` as configured in `application.yml`.

## 📈 Observability

All gateway gRPC endpoints are instrumented with the shared `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor`.
WebSocket traffic is tracked using the `ConnectionMetricsFilter`. Full request and response tracing for WebSocket sessions is planned. (TODO: Not yet implemented)
These interceptors and filters record structured logs, Prometheus metrics, and OpenTelemetry spans so usage and performance can be monitored across the cluster.

## 🔗 Internal gRPC Communication

Internal services communicate directly with each other over **gRPC**.
Spring Cloud Gateway does not handle these calls. Each service discovers
its peers via Docker or Kubernetes DNS and connects using the service name.
This approach minimizes latency and matches the protocol table in the
[System Architecture Overview](../system-architecture-overview.md#🌐-communication-flows).

---

## 🔧 Dev vs. Prod Configuration

| Environment | Route Target Format      | Discovery Mechanism        |
|-------------|---------------------------|-----------------------------|
| Dev         | `http://service:8080`     | Docker Compose DNS          |
| Prod        | `http://service.namespace.svc.cluster.local:8080` | Kubernetes DNS |

Spring profiles defined in `application.yml` and selected via
`SPRING_PROFILES_ACTIVE` configure routing targets based on environment.

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Spring Cloud Gateway Service Details](./microservices/spring-cloud-gateway/README.md)

---
