# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

An OpenAPI specification for these REST endpoints lives in `services/spring-cloud-gateway/src/main/resources/openapi.yaml`.

## Implementation Status

- **Dynamic route management (REST/gRPC):** Implemented via `GatewayController` (`/routes` REST API) and the `GatewayManagementService` gRPC API for upsert/remove operations. These APIs apply **in-memory overrides** on top of the baseline routes loaded from configuration; config files remain the canonical source of truth and dynamic changes revert on restart unless persisted by a higher-level tool. **Current scope:** dev/test only until shared persistence, multi-pod convergence, and full route-change auditing are implemented. Player-facing environments must fail startup when dynamic route mutation is enabled without those controls (for example `firemud.gateway.dynamic-routes.enabled=true` with `firemud.gateway.dynamic-routes.allow-player-facing=true`) and must expose explicit readiness predicates (`dynamic_routes.persistence_ready`, `dynamic_routes.convergence_ready`, `dynamic_routes.audit_ready`, plus aggregate `dynamic_routes_ready`) so control-plane safety is observable.
- **Rate limiting and Redis wiring:** Implemented using Spring Cloud Gateway’s `RequestRateLimiter` filter backed by the Cache/Rate‑Limit Redis profile configured in `application.yml` for `dev` and `prod` profiles.
- **Telnet WebSocket bridge expectations:** Target-state behavior is through the `/ws/game/**` route in Spring Cloud Gateway and the TCP Proxy Service’s WebSocket bridge (`GATEWAY_WS_URL`), matching the behavior described in reconnection and protocol-bridging docs. Current rollout status is tracked in the TCP Proxy Service design’s **Cross-Path Compatibility Matrix (Current Rollout)** and **Implementation Status** sections. Transitional non-mTLS proxy hops are dev/ephemeral-compatibility only and must be fail-closed in player-facing staging/production environments.
- **WebSocket close/handshake observability contract:** Gateway Architecture requires `gateway.websocket.closes`, `gateway.websocket.handshake.rejected`, and `gateway.websocket.slow_client_closes`. Treat this as a required parity checklist for implementation and operations sign-off in each environment.

### Responsibilities

- Enforce the presence of an `Authorization` header for admin routes; JWT parsing and validation are always performed by downstream services. TLS termination occurs at the load balancer as described in the [Security Architecture](../../system-architecture-security.md)
- Upgrade WebSocket connections and forward them to backend services
- Apply rate limits and basic abuse protections
- Relay traffic to the Game Session Service and other backends
- Expose gRPC management endpoints (for example, `Ping`) on port `6565` for basic health and diagnostics. Connections use mutual TLS for authentication and are reachable only from inside the cluster or a dedicated admin network segment, not from public Internet clients.

## Architecture / Design Notes

- Handles persistent WebSocket connections and supports raw TCP through the TCP Proxy Service.
- Forwards real-time gameplay and administrative messages between clients and backend services; game state changes and synchronization logic live in the Game Session Service and Game Logic Service.
- Relies on the Game Session Service to restore sessions when clients reconnect as described in the [Reconnection Strategy](../../system-architecture-reconnection.md).
- Gateway restarts disconnect gameplay WebSocket clients; clients reconnect by opening a new `/ws/game/**` WebSocket, issuing `LOGIN`, and then re-binding gameplay scope with `PLAY` as described in [Reconnection Strategy](../../system-architecture-reconnection.md). Telnet clients are also disconnected because the TCP Proxy Service fail-closes Telnet sockets when its WebSocket bridge to Spring Cloud Gateway drops; clients reconnect and re-`LOGIN`/`PLAY`.
- Applies rate limiting and authentication filters for admin endpoints.
- Relies on the Game Session Service for gameplay login and session management.
- Remains tenant-agnostic: it forwards tenant-related headers (such as `X-Tenant-Id` and `X-Game-Instance-Id`) to backend services, but only after applying the gateway’s header trust and canonicalization rules. In particular, Spring Cloud Gateway strips spoofable tenant/game-instance headers from public ingress and only forwards `X-Tenant-Id` / `X-Game-Instance-Id` when they are produced from trusted inputs (for example `X-Proxy-Tenant-Id` / `X-Proxy-Game-Instance-Id` on the authenticated TCP Proxy → Gateway hop) as described in [Multi-Tenancy at the Gateway](../../system-architecture-gateway.md#multi-tenancy-at-the-gateway) and [Header Trust Model](../../system-architecture-gateway.md#header-trust-model). This enforcement is implemented by `HeaderTrustFilter` and configured via `firemud.gateway.header-trust.*`, including the deprecated `X-Session-Id` alias toggle `firemud.gateway.header-trust.emit-legacy-session-id` (target removal date: October 1, 2026). All tenant isolation and quotas are enforced by domain services as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- External TLS is terminated by the load balancer; Spring Cloud Gateway routes to backend services over in-cluster `http://` / `ws://` endpoints, while internal service-to-service traffic uses mTLS gRPC as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- gRPC endpoints use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` for consistent observability.

## Key Features

- Central API gateway and policy enforcement point (routing, rate limiting, and basic admin auth gating only; downstream services own all gameplay authentication and authorization decisions).
- Real-time delivery of gameplay and admin messages over HTTP and WebSocket.
- Reconnection support for dropped clients.
- Routes HTTP and WebSocket traffic to appropriate backend services. The gateway’s gRPC surface is reserved for internal management and diagnostics (for example, `GatewayManagementService` on port `6565`).

### Data Model

The gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
Route configurations live in `routes-dev.yml` and `routes-prod.yml`, which are
imported by `application.yml` based on the active profile and reloaded on
startup. These files define the **baseline route set** for each environment.
Dynamic route APIs can overlay additional routes or updates at runtime, but
those changes are in-memory only and the system always converges back to the
baseline definitions on restart unless a higher-level tool updates the config.
The default configuration defines routes for the core
services so Docker Compose environments work out of the box.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` requires an `Authorization` header on admin routes and forwards the JWT unmodified. Spring Cloud Gateway never parses or validates JWTs; validation occurs entirely in the consuming service.
- Rate limiting behavior (keying strategy, WebSocket vs HTTP semantics, and division of responsibility with the TCP Proxy Service and Game Session Service) follows the design in [Rate Limiting & Abuse Protection](../../system-architecture-gateway.md#rate-limiting--abuse-protection); this service configures the `RequestRateLimiter` filter to use the Cache/Rate‑Limit Redis instance defined by `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`.
- WebSocket upgrades are proxied using Spring Cloud Gateway’s built-in WebSocket support. The `ConnectionMetricsFilter` records active connections for observability.
- Tracing for WebSocket sessions captures connection‑level metadata (route ID, tenant, session identifiers, basic timing) without logging full text payloads by default.
- Full request and response payload tracing for WebSocket sessions is an opt‑in diagnostic mode and must be enabled only for tightly scoped debugging scenarios, with sampling and redaction aligned to the [Logging & Monitoring](../../system-architecture-logging-monitoring.md) guidelines.

### Key Routes

- `/ws/game/**` → Game Session Service (canonical WebSocket gameplay endpoint for first-party clients and Telnet clients bridged via the TCP Proxy Service; connect-token enforced in player-facing environments).
- `/ws/game-legacy/**` → Game Session Service (transitional compatibility endpoint for legacy/third-party clients that cannot yet provide connect tokens; must run with stricter edge guardrails and is scheduled for removal from player-facing environments by December 31, 2026).
- `/api/admin/**` → Logging & Admin Service (tokens are verified by the service).
- `/api/design/**` → Game Design Service for content management.
- `/api/account/**` → Account Service for user profiles.
- `/api/automation/**` → Automation Scripting Service.
- `/api/entity/**` → Entity Management Service.
- `/api/logic/**` → Game Logic Service.
- `/api/social/**` → Social Groups Service.
- `/api/world/**` → World Management Service.

Telnet clients send every line through the TCP Proxy Service, which bridges the commands onto the gateway’s `/ws/game/**` route. Because of that shared pipeline, Telnet and WebSocket sessions share the same admission and reconnection flow once connected: both use `LOGIN` followed by `PLAY` before gameplay commands, while the Telnet path may additionally provide optional `SESSION`-derived headers as described in the TCP Proxy Service design’s **Telnet Session Envelope & Event Metrics** section. `/ws/game-legacy/**` exists only for direct legacy WebSocket compatibility flows and is not part of the Telnet bridge target-state.

## Dependencies

- **Internal:**
  - Game Session Service and other backend services via the configured `http://` and `ws://` route targets (typically port `8080`).
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
  - HTTP management endpoints are authenticated and authorized at the gateway boundary, not delegated to downstream services. The recommended model is mTLS client certificates (same trust root as the gRPC management plane), with `NetworkPolicy` allowlists restricting which pods/namespaces may reach the endpoint. JWT-based roles apply to product/admin APIs routed through the gateway but are not relied upon as the primary authorization mechanism for gateway-owned management endpoints.
  - Operator client certificates should be issued by cert-manager under ClusterIssuer `firemud-ca-issuer`, must include the `clientAuth` EKU, and should be distributed as a dedicated Kubernetes Secret that is readable only by operator tooling service accounts (so normal workloads cannot reuse service mTLS credentials to call management APIs).
- **Data plane vs control plane**
  - Port `8080` hosts the gateway HTTP and WebSocket server. Public ingress exposes only data-plane routes on this port; management endpoints on this port are reachable only via internal-only Services or a dedicated private ingress. Port `6565` is used for internal gRPC management.
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
| `routes-dev.yml` / `routes-prod.yml` | Profile-specific route definitions for HTTP and WebSocket paths and backend URIs | Service-local; referenced by `spring.config.import` in `application.yml` |
| `FIREMUD_SERVICES_*` | Service discovery overrides for backend targets reached from the gateway | Described in [Service Discovery](../../infrastructure/environment-and-secrets.md#service-discovery) |
| `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate‑Limit Redis endpoint used by the gateway’s `RequestRateLimiter` filter | Described in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) |
| `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` | TLS certificate and key paths for the gateway’s internal gRPC/mTLS management plane | Described in [gRPC TLS Certificates](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates) |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for traces | Described in [Observability](../../infrastructure/environment-and-secrets.md#observability) |

For the TCP Proxy → Gateway WebSocket mTLS hop, the TCP Proxy client identity and trust bundle use `FIREMUD_GATEWAY_WS_*` variables on the TCP Proxy side, while the gateway listener certificate and trusted client CA are configured on the gateway TLS listener surface as described in [Gateway Architecture](../../system-architecture-gateway.md#tls-termination-for-gateway) and [Protocol Bridging](../../system-architecture-protocol-bridging.md#websocket-bridge-configuration). Do not treat `FIREMUD_GRPC_*` as the authoritative configuration for that WebSocket listener.

### Redis Role and Prefixes

- **Coordination Redis**
  - Spring Cloud Gateway does not access Coordination Redis. It never issues commands against `tick:*`, `timer:*`, `retry:*`, `session:*`, or other coordination prefixes; gameplay coordination remains the responsibility of the Game Session Service and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md).
- **Cache/Rate-Limit Redis**
  - Uses **Cache/Rate-Limit Redis** exclusively for rate limiting and any future gateway-local caches, connecting via `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` as documented in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) and [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - Rate-limit buckets and related keys follow the `ratelimit:<tenantId>:<bucket>:<timeWindow>` patterns and hash-based bucketing strategies described in [Rate-Limit Bucket Design](../../system-architecture-redis-cache.md#rate-limit-bucket-design). When present, tenant markers are included in keys for **isolation and observability only**; limit values and policy decisions remain global and are not derived at Spring Cloud Gateway from tenant identity. These rate-limit structures are treated as **best-effort TTL-only caches** of counters; correctness comes from the gateway’s rate-limit policy and enforcement logic, not from Redis acting as a durable store.
  - Spring Cloud Gateway does not read or write gameplay chat caches (`chat:*`) or other service-owned cache prefixes; it treats those as internal to their owning services and interacts with them only via upstream APIs.
- Changes to rate-limiting strategy or cache usage in Spring Cloud Gateway should be reviewed using the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role separation, and monitoring coverage. Any additional gateway-local caches must explicitly declare whether they are strongly validated (version-based) or best-effort TTL-only and be registered in the Cache/Rate-Limit Redis key catalog maintained in the Redis cache design docs (Redis cheat sheet plus `system-architecture-redis-cache.md`).

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

The HTTP server listens on `SERVER_PORT` (typically `8080`), and the gRPC server listens on port `6565` as configured in `application.yml`. Spring Cloud Gateway does **not** validate or parse JWTs for gameplay or admin traffic and does not require JWT signing material. Admin and other meta/control services perform JWT validation themselves, while the gateway's `JwtAuthFilter` only enforces the presence of an `Authorization` header on protected routes and forwards tokens unchanged.

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
- [User Journeys – Player Login and Gameplay](../../user-journeys-players.md#3-player-login-and-gameplay)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- These endpoints are internal-only in production (private ingress / cluster-internal access, protected by mTLS). The examples below are for local development and trusted operator contexts.
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
# Local development only (no mTLS)
grpcurl -plaintext localhost:6565 spring_cloud_gateway.v1.GatewayManagementService/Ping

# Production / operator contexts (mTLS)
grpcurl \
  -cacert "$FIREMUD_GRPC_CA_CERT_PATH" \
  -cert "$FIREMUD_GRPC_CERT_CHAIN_PATH" \
  -key "$FIREMUD_GRPC_PRIVATE_KEY_PATH" \
  spring-cloud-gateway:6565 \
  spring_cloud_gateway.v1.GatewayManagementService/Ping
```

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

## Scalability

The gateway scales horizontally to handle high concurrency.
