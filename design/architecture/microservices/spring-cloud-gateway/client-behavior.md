# Spring Cloud Gateway Client Behavior

## Implementation Status

Unless explicitly described as current behavior, the sections below define the target Gateway contract. Current implementation facts and gaps are:

- Java `CanonicalGatewayRoutesConfiguration` is the current route authority, with environment overrides; the target route catalog and deny-by-default exposure rules still require convergence proof.
- The current edge implements bounded connect-token handshake classes and replay handling, but this does not prove the complete target replay durability, rotation, or reconnect contract.
- The protected admin `JwtAuthFilter` currently parses shared-HMAC JWTs through `JwtUtil`; this is implementation drift from the target in which consuming services own asymmetric JWKS validation. Player-facing validator gaps are recorded in the environment and JWT contract documents.
- The current public `/api/session/**` inventory is limited to `GET /api/session/ping`; internal `/sessions*` mutations remain non-public.
- The current route catalog blocks the documented internal subtrees, and `HeaderTrustFilter` owns trusted-header promotion; deployment-level drain, failover, and live readiness evidence remain separate proof obligations.

## Gameplay Route Behavior

- The canonical gameplay WebSocket route is `/ws/game/**`.
- This route forwards to the Game Session Service session front-end layer; the gateway does not participate in gameplay shard routing or lease-owner selection.
- Gateway is the edge failure boundary. Fleet-level failover leaves sockets on healthy Gateway instances up, while a socket terminated by the serving instance requires a fresh `/ws/game/**` connection and the client-visible recovery flow in [Reconnection Strategy](../../system-architecture-reconnection.md). Bounded upstream rebind, its elapsed-time limits, and FIFO stall handling are owned by [Gateway architecture](../../system-architecture-gateway.md#backend-unavailable-grace-window) and [Reconnection Strategy](../../system-architecture-reconnection.md#bounded-non-edge-restart-recovery).
- Telnet clients use the same gameplay route after the TCP Proxy bridge. Their `LOGIN`, conditional `JOIN`, and `PLAY` semantics are owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow); the TCP Proxy transport procedure is documented in [TCP Proxy protocols](../tcp-proxy-service/protocols.md#recommended-telnet-client-flows). Hidden attach hints remain advisory transport metadata only.
- Planned Gateway drain must be surfaced by the TCP Proxy as `logout` with `gateway_restart` context when the deterministic bridge-drain signal is received. Unattributed loss of the specific Gateway bridge/socket currently serving a Telnet client is surfaced immediately as `backend_unavailable`; unaffected Telnet sessions routed through other healthy Gateway instances should continue normally.

## Trusted TCP Proxy Bridge Admission

- Traffic from the TCP Proxy Service always targets `/ws/game/**` via `GATEWAY_WS_URL`.
- In player-facing environments, the TCP Proxy -> Gateway WebSocket hop is mTLS-authenticated under the trust policy in [Security](../../system-architecture-security.md#tls-termination--internal-encryption); the local endpoint and certificate variables remain in [TCP Proxy configuration](../tcp-proxy-service/configuration.md#websocket-mtls-to-spring-cloud-gateway).
- Spring Cloud Gateway strips spoofable tenant and game-instance headers from public ingress and forwards `X-Tenant-Id` / `X-Game-Instance-Id` only when they are derived from trusted inputs, such as `X-Proxy-Tenant-Id` / `X-Proxy-Game-Instance-Id` on the authenticated TCP Proxy -> Gateway hop.
- Gateway emits the positive `X-Firemud-Connection-Mode` discriminator on successful gameplay admission: `first_party_web` for the supported connect-token path and `trusted_tcp_proxy` for authenticated TCP Proxy bridges. The canonical marker and downstream trust rules are owned by [Gateway architecture](../../system-architecture-gateway.md#gateway-output-rules-downstream-trusted); Game Session must not infer path type from header absence.

## WebSocket Close and Handshake Classification

- Non-`101` `/ws/game/**` handshake failures must emit the canonical bounded error class through the gateway response/logging path. The authoritative route, connect-token, replay, and close classification is [Gateway architecture](../../system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake); client retry behavior is [Reconnection Strategy](../../system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
- First-party gameplay handshake failures must use the more specific bounded classes at the gateway edge where applicable:
  - `CONNECT_TOKEN_MISSING`
  - `CONNECT_TOKEN_EXPIRED`
  - `CONNECT_TOKEN_REPLAYED`
  - `CONNECT_SCOPE_MISMATCH`
  - `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`
  - `CONNECT_TOKEN_REJECTED` for malformed or otherwise rejected tokens outside the narrower classes above
- A concrete wire-level example remains the `X-Firemud-Handshake-Error-Class` response header paired with matching structured-log fields; downstream clients and operator tooling may rely on that bounded header/value surface rather than parsing free-form text.
- The gateway observability contract requires `gateway.websocket.closes{reason,subreason}`, `gateway.websocket.handshake.rejected`, and `gateway.websocket.slow_client_closes`.
- Close and handshake classifications must remain bounded and stable so reconnect logic, dashboards, and alerting do not depend on free-form strings.

## Filter Chain and Admission Behavior

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` requires an `Authorization` header on protected admin routes and forwards the JWT unmodified. Spring Cloud Gateway never parses or validates JWTs; validation occurs entirely in the consuming service.
- Rate limiting behavior, including keying strategy and the division of responsibility with the TCP Proxy Service and Game Session Service, follows [Rate Limiting & Abuse Protection](../../system-architecture-gateway.md#rate-limiting--abuse-protection).
- WebSocket upgrades are proxied using Spring Cloud Gateway’s built-in WebSocket support.
- `RequestMetricsFilter` records HTTP request activity for observability, while the dev WebSocket echo handler records actual WebSocket connection counts separately.
- First-party gameplay connect-token replay protection is Gateway-owned and must fail closed when its shared Coordination Redis authority is unavailable or capacity-safe continuity cannot be proven. The complete replay, quarantine, and durable-consume contract is defined in [Gateway architecture](../../system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake) and [ADR 0029](../../decisions/adr-0029-single-use-gameplay-connect-token-carriage.md); this service-local summary does not redefine it.
- Tracing for WebSocket sessions captures connection-level metadata such as route ID, tenant, session identifiers, and timing without logging full text payloads by default.
- Full request and response payload tracing for WebSocket sessions is an opt-in diagnostic mode only and must be tightly scoped with sampling and redaction aligned to [Logging & Monitoring](../../system-architecture-logging-monitoring.md).

## Key Routes

- `/ws/game/**` -> Game Session Service.
- `/api/admin/**` -> Logging & Admin Service.
- `/api/design/**` -> Game Design Service.
- `/api/account/**` -> Account Service.
- `/api/session/**` -> Game Session Service HTTP control-plane family.
- `/api/social/**` -> Social Groups Service.

The canonical external allowlist stops there. World Management, Entity Management, Game Logic, and Automation & Scripting do not expose direct Gateway-routed external APIs in the base architecture unless a dedicated design update extends the allowlist.

Gateway routes strip the first two path segments before forwarding these REST families to their owning services. For example, `GET /api/admin/admission-pointers` is forwarded to Logging & Admin as `/admission-pointers`, and `/api/account/auth/login` is forwarded to Account as `/auth/login`. The admission-pointer route is GET-only, so mutation, preparation, and prepared-cutover writes are not forwarded. `/api/session/ping` is forwarded as the current public HTTP control-plane route, while `/ws/game/**` remains the separate gameplay WebSocket path. The `/assets/**` object-store route keeps its dedicated single-prefix strip behavior.

These public prefixes are route families, not blanket permission to expose every service-local path under the same subtree:

- owning service contracts must publish the externally allowed route inventory for their family;
- internal-only or operator/debug service-local subtrees such as `/internal/**` and `/actuator/**` remain non-public even when the service has a public `/api/{service}/**` prefix, and the gateway must block `/api/{public-family}/internal/**` and `/api/{public-family}/actuator/**` requests instead of forwarding them; and
- gateway config and filters must converge on deny-by-default behavior for undocumented internal subtrees instead of treating the coarse family prefix as the final exposure contract.

The canonical gateway route catalog publishes that explicit external inventory directly rather than forwarding blanket `/api/{service}/**` families. Adding a new externally reachable route requires an explicit route-catalog entry plus matching owner-side contract documentation, not just a new service-local controller path under an existing prefix.

## Tenant and Header Trust Model

- Spring Cloud Gateway remains tenant-agnostic. It forwards tenant-related headers only after applying the gateway’s header trust and canonicalization rules.
- This trust boundary is owned by `HeaderTrustFilter` and configured via `firemud.gateway.header-trust.*`; changes to trusted header sources or canonicalization behavior must update that service-local control surface in the same change.
- Public clients must not be able to inject trusted `X-Tenant-Id`, `X-Game-Instance-Id`, or proxy-owned correlation headers through the gateway boundary.
- Tenant isolation, quotas, and gameplay authorization decisions remain the responsibility of downstream domain services as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
