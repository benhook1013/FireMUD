# Spring Cloud Gateway Client Behavior

## Gameplay Route Behavior

- The canonical gameplay WebSocket route is `/ws/game/**`.
- This route forwards to the Game Session Service session front-end layer; the gateway does not participate in gameplay shard routing or lease-owner selection.
- Gateway is the edge failure boundary. Fleet-level failover leaves sockets on healthy Gateway instances up, while a socket terminated by the serving instance requires a fresh `/ws/game/**` connection and the client-visible recovery flow in [Reconnection Strategy](../../system-architecture-reconnection.md). Bounded upstream rebind, its elapsed-time limits, and FIFO stall handling are owned by [Gateway architecture](../../system-architecture-gateway.md#backend-unavailable-grace-window) and [Reconnection Strategy](../../system-architecture-reconnection.md#bounded-non-edge-restart-recovery).
- Telnet clients use the same gameplay route after the TCP Proxy bridge. Their `LOGIN`, conditional `JOIN`, and `PLAY` semantics are owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow); the TCP Proxy transport procedure is documented in [TCP Proxy protocols](../tcp-proxy-service/protocols.md#recommended-telnet-client-flows). Hidden attach hints remain advisory transport metadata only.
- Planned Gateway drain must be surfaced by the TCP Proxy as `service_restart` when the deterministic bridge-drain signal is received. Controller takeover is surfaced as `session_replaced`; terminal logout remains `logout`. Unattributed loss of the specific Gateway bridge/socket currently serving a Telnet client is surfaced immediately as `backend_unavailable`; unaffected Telnet sessions routed through other healthy Gateway instances should continue normally. The Gateway-owned matrix is the authority; bridge subreason is diagnostic only.

## Trusted TCP Proxy Bridge Admission

- Traffic from the TCP Proxy Service always targets `/ws/game/**` via `GATEWAY_WS_URL`.
- In player-facing environments, the TCP Proxy -> Gateway WebSocket hop is mTLS-authenticated under the mutually exclusive environment-bound trust profiles in [ADR 0169](../../decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md); the local endpoint and certificate variables remain in [TCP Proxy configuration](../tcp-proxy-service/configuration.md#websocket-mtls-to-spring-cloud-gateway).
- Spring Cloud Gateway strips or overwrites spoofable tenant, game-instance, and routing headers from public ingress. It forwards `X-Tenant-Id` / `X-Game-Instance-Id` only when derived from trusted inputs, and accepts `X-World-Slug` / `X-Realm-Slug` / positive `X-Pointer-Version` from TCP Proxy only as an all-or-none bundle on the authenticated bridge; a partial or malformed bundle is rejected before the validated advisory context reaches Game Session.
- Gateway emits the positive `X-Firemud-Connection-Mode` discriminator on successful gameplay admission: `first_party_web` for the supported protected-cookie connect-token path and `trusted_tcp_proxy` for authenticated TCP Proxy bridges. A future target-only public non-browser header variant requires its own route classification and proof. The canonical marker and downstream trust rules are owned by [Gateway architecture](../../system-architecture-gateway.md#gateway-output-rules-downstream-trusted); Game Session must not infer path type from header absence.

## WebSocket Close and Handshake Classification

- Non-`101` `/ws/game/**` handshake failures must emit the canonical bounded error class through the gateway response/logging path. The authoritative route, connect-token, replay, and close classification is [Gateway architecture](../../system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake); client retry behavior is [Reconnection Strategy](../../system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
- First-party gameplay handshake failures must use the more specific bounded classes at the gateway edge where applicable:
  - `CONNECT_TOKEN_MISSING`
  - `CONNECT_TOKEN_EXPIRED`
  - `CONNECT_TOKEN_REPLAYED`
  - `CONNECT_SCOPE_MISMATCH`
  - `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`
  - `CONNECT_TOKEN_REJECTED` for malformed or otherwise rejected tokens outside the narrower classes above
- A concrete wire-level example remains the `X-Firemud-Handshake-Error-Class` response header paired with matching structured-log fields. Capable non-browser callers and operator tooling may rely on that bounded surface rather than parsing free-form text; browser WebSocket APIs cannot read failed-upgrade headers and use the conservative recovery rule in Gateway architecture instead.
- The gateway observability contract requires `gateway.websocket.closes{reason,subreason}`, `gateway.websocket.handshake.rejected`, and `gateway.websocket.slow_client_closes`.
- Close and handshake classifications must remain bounded and stable so reconnect logic, dashboards, and alerting do not depend on free-form strings.
- Close classes describe transport/session lifecycle only. They never establish the result of an in-flight command. The current durable `GetGameplayCommandStatus` lookup uses `{tenantId, gameInstanceId, commandId}`; target automation lookup and replay use the authoritative complete Command-Handoff Identity defined by the [Game Session API owner](../game-session-service/api-contracts.md#grpc-apis), not the current tuple or a Gateway-owned copy. Close or rebind does not imply completion, and durable status may be `LOST_BEFORE_STAGING`; see [ADR 0016](../../decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md).

## Implementation Status

Unless explicitly described as current behavior, the sections below define the target Gateway contract. Current implementation facts and gaps are:

- Java `CanonicalGatewayRoutesConfiguration` is the current route authority, with environment overrides; the target route catalog and deny-by-default exposure rules still require convergence proof. It currently has no `/assets/**` route or asset-store route ID; published asset delivery remains target-only pending a separate approved public origin/provisioner, and private MinIO is not public delivery.
- **Current Game Design route consequence:** the current coarse `/api/design/**` route forwards `/api/design/assets` through `StripPrefix=2` to Game Design's live `POST /assets` controller. That controller currently checks only privileged JWT/tenant access and has no Account hosted-terms/currentness gate, so official-hosted asset-upload readiness is blocked until Gateway denies this route or the exact Account-owned gate is implemented and proved.
- The current edge implements bounded connect-token handshake classes and replay handling, but this does not prove the complete target replay durability, rotation, or reconnect contract.
- **Current drift:** the protected admin `JwtAuthFilter` parses shared-HMAC JWTs through `JwtUtil`. This is current implementation behavior only, is not a player-facing asymmetric-validation capability, and must not be confused with the target receiving-service boundary.
- **Target boundary:** On protected admin routes, Gateway requires an `Authorization` header at ingress and forwards it without parsing or validating ordinary JWT contents; consuming services own asymmetric JWKS validation under [JWT and Token Contracts](../../system-architecture-jwt-and-token-contracts.md).
- The current public `/api/session/**` inventory is limited to `GET /api/session/ping`; internal `/sessions*` mutations remain non-public.
- The current route catalog blocks the documented internal subtrees, and `HeaderTrustFilter` owns trusted-header promotion; deployment-level drain, failover, and live readiness evidence remain separate proof obligations.

## Filter Chain and Admission Behavior

- Authentication, rate limiting, and logging filters run before routing.
- **Target contract:** On protected admin routes, `JwtAuthFilter` requires an `Authorization` header at ingress and forwards it unmodified. Spring Cloud Gateway does not parse or validate ordinary JWTs; validation occurs entirely in the consuming service. The current shared-HMAC parsing noted in Implementation Status is drift and does not change this target boundary.
- Rate-limiting behavior, including keying strategy and the division of responsibility with the TCP Proxy Service and Game Session Service, follows [Rate Limiting & Abuse Protection](../../system-architecture-gateway.md#rate-limiting--abuse-protection).
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
- `/assets/**` -> target-only published asset origin, pending separate approval/provisioner; not a current Gateway route and separate from `/frontend-assets/**`.

The canonical external allowlist stops there. World Management, Entity Management, Game Logic, and Automation & Scripting do not expose direct Gateway-routed external APIs in the base architecture unless a dedicated design update extends the allowlist.

Gateway routes strip the first two path segments before forwarding these REST families to their owning services. For example, `GET /api/admin/admission-pointers` is forwarded to Logging & Admin as `/admission-pointers`, and `/api/account/auth/login` is forwarded to Account as `/auth/login`. The admission-pointer route is GET-only, so mutation, preparation, and prepared-cutover writes are not forwarded. `/api/session/ping` is forwarded as the current public HTTP control-plane route, while `/ws/game/**` remains the separate gameplay WebSocket path. Published `/assets/**` delivery is target-only pending a separate approved public origin/provisioner; the current Gateway has no route for it, and private MinIO is not public delivery.

These public prefixes are route families, not blanket permission to expose every service-local path under the same subtree:

- owning service contracts must publish the externally allowed route inventory for their family;
- internal-only or operator/debug service-local subtrees such as `/internal/**` and `/actuator/**` remain non-public even when the service has a public `/api/{service}/**` prefix, and the gateway must block `/api/{public-family}/internal/**` and `/api/{public-family}/actuator/**` requests instead of forwarding them; and
- gateway config and filters must converge on deny-by-default behavior for undocumented internal subtrees instead of treating the coarse family prefix as the final exposure contract.

The canonical gateway route catalog publishes that explicit external inventory directly rather than forwarding blanket `/api/{service}/**` families. Adding a new externally reachable route requires an explicit route-catalog entry plus matching owner-side contract documentation, not just a new service-local controller path under an existing prefix.

## Tenant and Header Trust Model

- Spring Cloud Gateway remains tenant-agnostic. It forwards tenant-related headers only after applying the gateway’s header trust and canonicalization rules.
- This trust boundary is owned by `HeaderTrustFilter` and configured via `firemud.gateway.header-trust.*`; changes to trusted header sources or canonicalization behavior must update that service-local control surface in the same change.
- Public clients must not be able to inject trusted `X-Tenant-Id`, `X-Game-Instance-Id`, `X-World-Slug`, `X-Realm-Slug`, `X-Pointer-Version`, or proxy-owned correlation headers through the gateway boundary.
- Tenant isolation, quotas, and gameplay authorization decisions remain the responsibility of downstream domain services as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
