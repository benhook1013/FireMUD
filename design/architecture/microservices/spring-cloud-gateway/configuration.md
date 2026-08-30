# Spring Cloud Gateway Configuration

## Implementation Status

The current Gateway `RequestRateLimiter` derives keys from raw client IP rather than the target versioned opaque subject hash. Canonicalization, shared helper adoption, HMAC key delivery/rotation, privacy and cardinality proof, and legacy-key expiry remain implementation work.

## Configuration Sources

Spring Cloud Gateway reads its configuration from a small set of sources. The full environment variable catalog and deployment-wide patterns live in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

| Source | Purpose | Authority |
| --- | --- | --- |
| `application.yml` | Base Spring profile configuration such as ports, gRPC settings, and default filters like `RequestRateLimiter` and `Retry`. | Service-local; environment variable mapping lives in Env & Secrets. |
| `routes.yml` | Target canonical baseline route definitions for HTTP and WebSocket paths and backend URIs, parameterized by environment variables for upstream targets. | Target service-local baseline. Current code still owns routes in `CanonicalGatewayRoutesConfiguration`; the resource and `spring.config.import` are not implemented. |
| `FIREMUD_SERVICES_*` | Service discovery overrides for backend targets reached from the gateway. | See [Service Discovery](../../infrastructure/environment-and-secrets.md#service-discovery). |
| `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT` | Coordination Redis endpoint used by the target Gateway replay/deny/readiness client. | See [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection). The current generic client does not consume this binding and remains migration drift. |
| `FIREMUD_GATEWAY_REPLAY_DOMAIN` | **Target non-secret replay-domain pin.** Must equal exactly `gateway-connect-token-replay-v1`; Gateway startup, target key helpers, ACL/prefix declarations, migration/reset tooling, and preflight use this value to produce the fixed `{gateway-connect-token-replay-v1}` hash tag. | Target-only; current Gateway configuration/helper does not consume it. |
| `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` | Cache/Rate-Limit Redis endpoint used by the gateway `RequestRateLimiter` filter. | See [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection). |
| `firemud.gateway.header-trust.*` | Header-trust and canonicalization configuration enforced by `HeaderTrustFilter` for public ingress versus trusted proxy sources. | Service-local gateway trust boundary; behavior must stay aligned with [Gateway Architecture](../../system-architecture-gateway.md#header-trust-model). |
| `firemud.gateway.backendUnavailableGraceMs` / `firemud.gateway.backendUnavailableRecoverySuccessCount` | Target gameplay-route backend-unavailable elapsed-time cutoff and recovery hysteresis knobs. The grace value must be positive and no greater than ADR 0013's 30,000 ms hard cutoff; ordinary qualifying recovery targets 10 seconds. | Not implemented in the current bound properties or bridge. Canonical behavior lives in [Gateway Architecture](../../system-architecture-gateway.md#backend-unavailable-grace-window), [Reconnection Strategy](../../system-architecture-reconnection.md#backend-unavailable-scenarios), and [ADR 0013](../../decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md). |
| `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` | TLS certificate and key paths for the gateway internal gRPC management plane. | See [gRPC TLS Certificates](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint for traces. | See [Observability](../../infrastructure/environment-and-secrets.md#observability). |

For the TCP Proxy -> Gateway WebSocket mTLS hop, the TCP Proxy client identity and trust bundle use `FIREMUD_GATEWAY_WS_*` variables on the TCP Proxy side, while the gateway listener certificate and trusted client CA are configured on the gateway TLS listener surface as described in [Gateway Architecture](../../system-architecture-gateway.md#tls-termination-for-gateway) and [Protocol Bridging](../../system-architecture-protocol-bridging.md#websocket-bridge-configuration). Do not treat `FIREMUD_GRPC_*` as the authoritative configuration for that WebSocket listener. Gateway HTTP management tooling separately uses `FIREMUD_GATEWAY_HTTP_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_HTTP_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_HTTP_CA_CERT_PATH` as documented in the [environment and secrets catalog](../../infrastructure/environment-and-secrets-catalog.md#gateway-http-management-plane-tls-target-state); these files and identities are not interchangeable with gRPC or WebSocket credentials.

## Route State and Baseline Configuration

- Spring Cloud Gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
- Target route configuration lives in `routes.yml`, imported by `application.yml` and reloaded on startup. Current code remains Java-owned until that convergence lands.
- **Current hosted-route consequence:** the Java route catalog's coarse `/api/design/**` entry forwards `/api/design/assets` through `StripPrefix=2` to Game Design's live `POST /assets` controller. Because Game Design has no Account hosted-terms/currentness gate, official-hosted asset-upload readiness is blocked until Gateway denies this route or the exact Account-owned gate is implemented and proved.
- The target files define the baseline route set for each environment.
- Explicitly enabled dev/test route APIs may overlay bounded in-memory changes on one disposable runtime; player-facing environments use only the version-controlled baseline and must fail startup if mutation is enabled.
- The default route configuration defines the core service routes required for local Docker Compose environments.

## Redis Role and Prefixes

### Coordination Redis

- The target Gateway replay/deny/readiness client uses the Gateway-owned non-secret `FIREMUD_GATEWAY_REPLAY_DOMAIN` pin, which must equal exactly `gateway-connect-token-replay-v1`; its key/domain representation and reset contract follow the canonical [Redis architecture](../../system-architecture-redis.md), [Redis usage and profiles](../../system-architecture-redis-usage-and-profiles.md), and [Redis reset and recovery](../../system-architecture-redis-reset-and-recovery.md) owner documents and are intentionally not restated here. Target startup and preflight reject any missing, unknown, or mismatched value. The current `GameplayHandshakeFilter` remains migration-only drift: it receives the generic `spring.data.redis` template routed through `FIREMUD_REDIS_CACHE_HOST`, writes only the untagged replay marker `gateway:connect-token:jti:<jti>`, and accepts `X-Firemud-Connect-Token` on non-proxy `/ws/game` without the planned route/profile guard. This is an implementation exception that must not be treated as an allowed Cache/Rate-Limit use: the filter can authorize a handshake while using this generic Cache-bound replay marker, and it does not currently write a browser-deny marker, readiness record, or replay fence. Target behavior rejects that header carrier as `CONNECT_TOKEN_REJECTED` until the `non_first_party_public` route/profile is registered and its issuance, replay, signed-context, response, and carrier proofs exist; it is not current `first_party_web` support or a fallback carrier. Player-facing admission must remain quarantined until the named Coordination client/ACL and owner-defined replay contract are cut over, the old replay state is inventoried/read back, and old markers are retained or explicitly cleaned only after their original acceptance windows.

### Cache/Rate-Limit Redis

- **Target state:** Spring Cloud Gateway uses Cache/Rate-Limit Redis exclusively for rate limiting and any future gateway-local caches; replay/deny/readiness state never uses this endpoint.
- It connects via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT` as documented in [Redis Connection](../../infrastructure/environment-and-secrets.md#redis-connection) and [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
- Target rate-limit buckets and related keys follow the isolated `ratelimit:<tenantId>:<subjectHash>:<timeWindow>` pattern, canonical subject-hash helper, and explicit loss semantics described in [Rate-Limit Bucket Design](../../system-architecture-redis-cache.md#rate-limit-bucket-design).
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
- **Target-state JWT and presence boundary:** Spring Cloud Gateway does not validate or parse ordinary JWTs for routed gameplay or admin traffic, does not require JWT signing material for those routes, and does not own gameplay login, session, or presence state. Downstream services, especially Game Session, remain responsible for those concerns. The explicit target gameplay-connect exception is `/ws/game/**`: `GameplayHandshakeFilter` validates the short-lived `gameplay-connect` JWT and signs the downstream connect context, so player-facing target deployments must provide the Gateway signing material and satisfy the dedicated signature, clock/lifetime, claim/scope, replay-readiness, and atomic consume requirements in [Gateway Architecture](../../system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake) and [ADR 0029](../../decisions/adr-0029-single-use-gameplay-connect-token-carriage.md). **Current implementation:** `GameplayHandshakeFilter` provides the bounded signature/claim/expiry and one-time marker checks but uses the legacy untagged Cache-bound marker and lacks the target readiness/fence/quarantine proof; this exception does not broaden ordinary routed JWT handling or establish current player-facing readiness.
- Admin and other meta/control services validate JWTs themselves. The gateway `JwtAuthFilter` only enforces the presence of an `Authorization` header on protected routes and forwards tokens unchanged.
- External TLS is terminated by the load balancer.
- Spring Cloud Gateway routes to backend services over in-cluster `http://` and `ws://` endpoints, while internal service-to-service traffic uses mTLS gRPC as described in the [Security Architecture](../../system-architecture-security.md).
- When internal WebSocket clients such as the TCP Proxy Service connect over `wss://` to `/ws/game/**`, the host used in `GATEWAY_WS_URL` must match a name present in the Gateway certificate SANs so SNI and hostname verification succeed. Using a bare IP or an unrelated hostname causes client-side TLS failure, and those failures should remain visible in TCP Proxy bridge metrics and logs.

## Dependencies

- Internal dependencies:
  - Game Session Service and other backend services via configured `http://` and `ws://` route targets.
  - TCP Proxy Service, which forwards Telnet traffic into the gateway.
- External dependency:
  - Spring Cloud Gateway infrastructure.

See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for the shared topology.
