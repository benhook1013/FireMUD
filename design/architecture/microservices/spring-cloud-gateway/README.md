# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

An OpenAPI specification for these REST endpoints lives in `services/spring-cloud-gateway/src/main/resources/openapi.yaml`.

## Implementation Status

- **Dynamic route management (REST/gRPC):** Implemented via `GatewayController` (`/routes` REST API) and the `GatewayManagementService` gRPC API for upsert/remove operations.
- **Rate limiting and Redis wiring:** Implemented using Spring Cloud Gateway’s `RequestRateLimiter` filter backed by the Cache/Rate‑Limit Redis profile configured in `application.yml` for `dev` and `prod` profiles.
- **Telnet WebSocket bridge expectations:** Implemented end‑to‑end through the `/ws/game/**` route in Spring Cloud Gateway and the TCP Proxy Service’s WebSocket bridge (`GATEWAY_WS_URL`), matching the behavior described in the reconnection and protocol bridging docs.

### Responsibilities

- Enforce authentication for admin routes. TLS termination occurs at the load balancer as described in the [Security Architecture](../../system-architecture-security.md)
- Upgrade WebSocket connections and forward them to backend services
- Apply rate limits and basic abuse protections
- Relay traffic to the Game Session Service and other backends
- Expose gRPC management endpoints (for example, `Ping`) on port `6565` for basic health and diagnostics. Connections use mutual TLS for authentication and are reachable only from inside the cluster or a dedicated admin network segment, not from public Internet clients.

## Architecture / Design Notes

- Handles persistent WebSocket connections and supports raw TCP through the TCP Proxy Service.
- Event-driven updates synchronize game state across connected players.
- Relies on the Game Session Service to restore sessions when clients reconnect as described in the [Reconnection Strategy](../../system-architecture-reconnection.md).
- Gateway restarts are transparent thanks to the layered reconnection model
  outlined in [Reconnection Strategy](../../system-architecture-reconnection.md).
- Applies rate limiting and authentication filters for admin endpoints.
- Relies on the Game Session Service for gameplay login and session management.
- Remains tenant-agnostic: it forwards tenant-related headers (such as `X-Tenant-Id` and `X-Session-Id`) unchanged to backend services, and all tenant isolation and quotas are enforced by domain services as described in [Multi-Tenancy at the Gateway](../../system-architecture-gateway.md#multi-tenancy-at-the-gateway) and [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- External TLS is terminated by the load balancer and traffic to backend services uses mutual TLS as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- gRPC endpoints use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` for consistent observability.

## Key Features

- Central API gateway and policy enforcement point (routing, rate limiting, and basic admin auth gating only; downstream services own JWT validation).
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.
- Routes REST and gRPC traffic to appropriate backend services.

### Data Model

The gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
Route configurations live in `routes-dev.yml` and `routes-prod.yml`, which are
imported by `application.yml` based on the active profile and reloaded on
startup. Routes are managed as static configuration; adding or updating routes
is done by changing config (or environment variables for service endpoints) and
redeploying the gateway. The default configuration defines routes for the core
services so Docker Compose environments work out of the box.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` requires an `Authorization` header on admin routes and forwards the JWT unmodified. Spring Cloud Gateway never parses or validates JWTs; validation occurs entirely in the consuming service.
- Rate limiting behavior (keying strategy, WebSocket vs HTTP semantics, and division of responsibility with the TCP Proxy Service and Game Session Service) follows the design in [Rate Limiting & Abuse Protection](../../system-architecture-gateway.md#rate-limiting--abuse-protection); this service configures the `RequestRateLimiter` filter to use the Cache/Rate‑Limit Redis instance defined by `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`.
- WebSocket upgrades are forwarded transparently using Spring Cloud Gateway's built-in support. The `ConnectionMetricsFilter` records active connections for observability.
- Tracing for WebSocket sessions captures connection‑level metadata (route ID, tenant, session identifiers, basic timing) without logging full text payloads by default.
- Full request and response payload tracing for WebSocket sessions is an opt‑in diagnostic mode and must be enabled only for tightly scoped debugging scenarios, with sampling and redaction aligned to the [Logging & Monitoring](../../system-architecture-logging-monitoring.md) guidelines.

### Key Routes

- `/api/session/**` → Game Session Service (WebSocket and REST endpoints).
- `/api/admin/**` → Logging & Admin Service (tokens are verified by the service).
- `/api/design/**` → Game Design Service for content management.
- `/api/account/**` → Account Service for user profiles.
- `/api/automation/**` → Automation Scripting Service.
- `/api/entity/**` → Entity Management Service.
- `/api/logic/**` → Game Logic Service.
- `/api/social/**` → Social Groups Service.
- `/api/world/**` → World Management Service.

Telnet clients send every line through the TCP Proxy Service, which bridges the commands onto the gateway’s `/ws/game/**` route. Because of that shared pipeline, Telnet and WebSocket sessions follow identical login and reconnection flows: the Game Session Service always sees the same `SESSION` envelope headers and `LOGIN`/gameplay commands regardless of transport.

## Dependencies

- **Internal:**
  - Game Session Service and other microservices over gRPC.
  - TCP Proxy Service forwards Telnet traffic into the gateway.
- **External:** Spring Cloud Gateway infrastructure.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Management Plane Security

Spring Cloud Gateway exposes both HTTP and gRPC management interfaces for operators and tooling. These endpoints are strictly internal and secured separately from player-facing traffic:

- **Reachability**
  - REST management endpoints such as `POST /routes` and `DELETE /routes/{routeId}` are reachable only via cluster-internal Services or a dedicated admin ingress and are **never** published on the public Internet-facing load balancer.
  - The gRPC `GatewayManagementService` runs on port `6565` and is exposed only on internal network surfaces (for example, `ClusterIP` Services and private admin ingress), not on the public player ingress.
- **Authentication and authorization**
  - gRPC management calls use mutual TLS with cert-manager–issued client certificates. Only clients presenting trusted admin certificates can connect.
  - HTTP management endpoints reuse the gateway’s authentication filter chain: `JwtAuthFilter` enforces the presence of an `Authorization` header with an admin-role JWT, while downstream admin/meta services own full JWT validation and authorization logic.
- **Data plane vs control plane**
  - Port `8080` is reserved for player-facing HTTP/WebSocket traffic behind the public load balancer; port `6565` is used for internal gRPC management.
  - Kubernetes `Service` and `Ingress` objects keep these planes separate so that exposing gameplay routes does not accidentally publish management endpoints.

> 🔗 See [Security Architecture](../../system-architecture-security.md) for TLS, mTLS, and admin access models, and [Gateway Architecture](../../system-architecture-gateway.md#management-plane-security) for the high-level boundary design.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Configuration Sources

Spring Cloud Gateway reads its configuration from a small set of sources; the full environment variable catalog and patterns live in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

| Source | Purpose | Authority |
| ------ | ------- | --------- |
| `application.yml` | Base Spring profile configuration (ports, gRPC settings, default filters such as `RequestRateLimiter` and `Retry`) | Service-local; structure documented here, environment variable mapping in Env & Secrets |
| `routes-dev.yml` / `routes-prod.yml` | Profile-specific route definitions for HTTP/WebSocket paths and backend URIs | Service-local; referenced by `spring.config.import` in `application.yml` |
| `FIREMUD_SERVICES_*` | Service discovery overrides for backend targets reached from the gateway | Described in [Service Discovery](../../infrastructure/environment-and-secrets.md#service-discovery) |
| `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate‑Limit Redis endpoint used by the gateway’s `RequestRateLimiter` filter | Described in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) |
| `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` | TLS certificate and key paths for gRPC/mTLS and the Telnet WebSocket bridge | Described in [gRPC TLS Certificates](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates) |
| `FIREMUD_AUTH_JWT_SECRET_PATH`, `FIREMUD_AUTH_JWT_SECRET`, `FIREMUD_AUTH_JWT_EXPIRATION_MS`, `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` | Shared authentication configuration (JWT signing and derived session TTL); not used by Spring Cloud Gateway to validate JWTs | Described in [Authentication Variables](../../infrastructure/environment-and-secrets.md#authentication-variables) |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for traces | Described in [Observability](../../infrastructure/environment-and-secrets.md#observability) |

### Redis Role and Prefixes

- **Coordination Redis**
  - Spring Cloud Gateway does not access Coordination Redis. It never issues commands against `tick:*`, `timer:*`, `retry:*`, `session:*`, or other coordination prefixes; gameplay coordination remains the responsibility of the Game Session Service and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md).
- **Cache/Rate-Limit Redis**
  - Uses **Cache/Rate-Limit Redis** exclusively for rate limiting and any future gateway-local caches, connecting via `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` as documented in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) and [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - Rate-limit buckets and related keys follow the `ratelimit:{tenantId}:{bucket}:{timeWindow}` patterns and hash-based bucketing strategies described in [Rate-Limit Bucket Design](../../system-architecture-redis-cache.md#rate-limit-bucket-design); gateway code should not invent ad-hoc `ratelimit:*` shapes.
  - Changes to rate-limiting strategy or cache usage in the gateway should be reviewed using the [Redis Change Checklist](../../system-architecture-redis.md#redis-change-checklist) to confirm prefix registration, role separation, and monitoring coverage.

The HTTP server listens on `SERVER_PORT` (typically `8080`), and the gRPC server listens on port `6565` as configured in `application.yml`. The `firemud.auth` properties (JWT secret and expiration) defined in `application.yml` are part of the shared authentication configuration and are not used by Spring Cloud Gateway to validate or parse JWTs; admin and other meta/control services consume these properties when verifying tokens, while the gateway's `JwtAuthFilter` only enforces the presence of an `Authorization` header on protected routes and forwards tokens unchanged.

When internal WebSocket clients such as the TCP Proxy Service connect over
`wss://` to `/ws/game/**`, the host they use in `GATEWAY_WS_URL` must match a
name present in the Gateway certificate’s SANs so standard SNI and hostname
verification succeeds; using a bare IP or an unrelated hostname causes the TLS
handshake to fail on the client side (see the TCP Proxy Service design for how
these failures are surfaced in metrics).

## Proto Files

Gateway-related proto definitions are stored in
[../../../../protos/spring-cloud-gateway/v1](../../../../protos/spring-cloud-gateway/v1).
After edits, run `./gradlew generateProto` to regenerate gateway stubs.
The `gateway_management_service.proto` file defines the gateway's management and
health RPCs (such as `Ping`) used by operators and tooling.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../../user-journeys.md#7-player-login-and-gameplay)
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

Add a route via REST:

```bash
curl -X POST http://localhost:8080/routes \
  -H 'Content-Type: application/json' \
  -d '{"routeId":"demo","uri":"http://example.com","predicates":[],"filters":[]}'
```

Remove it:

```bash
curl -X DELETE http://localhost:8080/routes/demo
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`gateway_management_service.proto`](../../../../protos/spring-cloud-gateway/v1/gateway_management_service.proto).

```bash
grpcurl -plaintext localhost:6565 spring_cloud_gateway.v1.GatewayManagementService/Ping
```

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

## Scalability

The gateway scales horizontally to handle high concurrency.
