# Spring Cloud Gateway Configuration

## Configuration Sources

Spring Cloud Gateway reads its configuration from a small set of sources. The full environment variable catalog and deployment-wide patterns live in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

| Source | Purpose | Authority |
| --- | --- | --- |
| `application.yml` | Base Spring profile configuration such as ports, gRPC settings, and default filters like `RequestRateLimiter` and `Retry`. | Service-local; environment variable mapping lives in Env & Secrets. |
| `routes-dev.yml` / `routes-prod.yml` | Profile-specific route definitions for HTTP and WebSocket paths and backend URIs. | Service-local baseline route set referenced by `spring.config.import` in `application.yml`. |
| `FIREMUD_SERVICES_*` | Service discovery overrides for backend targets reached from the gateway. | See [Service Discovery](../../infrastructure/environment-and-secrets.md#service-discovery). |
| `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate-Limit Redis endpoint used by the gateway `RequestRateLimiter` filter. | See [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection). |
| `firemud.gateway.backendUnavailableGraceMs` / `firemud.gateway.backendUnavailableRecoverySuccessCount` | Gameplay-route backend-unavailable grace window and recovery hysteresis knobs that must stay aligned with the TCP Proxy bridge-availability contract. | Canonical behavior lives in [Gateway Architecture](../../system-architecture-gateway.md#backend-unavailable-grace-window) and [Reconnection Strategy](../../system-architecture-reconnection.md#backend-unavailable-scenarios). |
| `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` | TLS certificate and key paths for the gateway internal gRPC management plane. | See [gRPC TLS Certificates](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for traces. | See [Observability](../../infrastructure/environment-and-secrets.md#observability). |

For the TCP Proxy -> Gateway WebSocket mTLS hop, the TCP Proxy client identity and trust bundle use `FIREMUD_GATEWAY_WS_*` variables on the TCP Proxy side, while the gateway listener certificate and trusted client CA are configured on the gateway TLS listener surface as described in [Gateway Architecture](../../system-architecture-gateway.md#tls-termination-for-gateway) and [Protocol Bridging](../../system-architecture-protocol-bridging.md#websocket-bridge-configuration). Do not treat `FIREMUD_GRPC_*` as the authoritative configuration for that WebSocket listener.

## Route State and Baseline Configuration

- Spring Cloud Gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
- Route configurations live in `routes-dev.yml` and `routes-prod.yml`, which are imported by `application.yml` based on the active profile and reloaded on startup.
- These files define the baseline route set for each environment.
- Dynamic route APIs can overlay additional routes or updates at runtime, but those changes are in-memory only and the system converges back to the baseline definitions on restart unless a higher-level tool updates config.
- The default route configuration defines the core service routes required for local Docker Compose environments.

## Redis Role and Prefixes

### Coordination Redis

- Spring Cloud Gateway does not access Coordination Redis.
- It never issues commands against `tick:*`, `timer:*`, `retry:*`, `session:*`, or other coordination prefixes. Gameplay coordination remains the responsibility of the Game Session Service and its Lua registry.

### Cache/Rate-Limit Redis

- Spring Cloud Gateway uses Cache/Rate-Limit Redis exclusively for rate limiting and any future gateway-local caches.
- It connects via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT` as documented in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) and [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
- Rate-limit buckets and related keys follow the `ratelimit:<tenantId>:<bucket>:<timeWindow>` patterns and hash-based bucketing strategies described in [Rate-Limit Bucket Design](../../system-architecture-redis-cache.md#rate-limit-bucket-design).
- Tenant markers in keys are for isolation and observability only. Limit values and policy decisions remain global and are not derived at Spring Cloud Gateway from tenant identity.
- These rate-limit structures are best-effort TTL-only counters, not durable correctness state.
- Spring Cloud Gateway does not read or write gameplay chat caches (`chat:*`) or other service-owned cache prefixes.

Changes to gateway Redis usage should be reviewed against:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)
- [Redis Design Checklist](../../system-architecture-redis-design-checklist.md)

Any additional gateway-local caches must explicitly declare whether they are strongly validated or best-effort TTL-only and be registered in the Cache/Rate-Limit Redis key catalog.

## Runtime and TLS Invariants

- The HTTP server listens on `SERVER_PORT` (typically `8080`), and the gRPC server listens on port `6565` as configured in `application.yml`.
- Spring Cloud Gateway does not validate or parse JWTs for gameplay or admin traffic and does not require JWT signing material.
- Admin and other meta/control services validate JWTs themselves. The gateway `JwtAuthFilter` only enforces the presence of an `Authorization` header on protected routes and forwards tokens unchanged.
- External TLS is terminated by the load balancer.
- Spring Cloud Gateway routes to backend services over in-cluster `http://` and `ws://` endpoints, while internal service-to-service traffic uses mTLS gRPC as described in the [Security Architecture](../../system-architecture-security.md).
- When internal WebSocket clients such as the TCP Proxy Service connect over `wss://` to `/ws/game/**`, the host used in `GATEWAY_WS_URL` must match a name present in the Gateway certificate SANs so SNI and hostname verification succeed.

## Dependencies

- Internal dependencies:
  - Game Session Service and other backend services via configured `http://` and `ws://` route targets.
  - TCP Proxy Service, which forwards Telnet traffic into the gateway.
- External dependency:
  - Spring Cloud Gateway infrastructure.

See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for the shared topology.
