# Spring Cloud Gateway Client Behavior

## Gameplay Route Behavior

- The canonical gameplay WebSocket route is `/ws/game/**`.
- This route forwards to the Game Session Service session front-end layer; the gateway does not participate in gameplay shard routing or lease-owner selection.
- Gateway restarts disconnect gameplay WebSocket clients. Non-proxy WebSocket clients reconnect by obtaining a fresh connect token, opening a new `/ws/game/**` socket, issuing `LOGIN`, and then re-binding gameplay scope with `PLAY` as described in [Reconnection Strategy](../../system-architecture-reconnection.md).
- Telnet clients use the same canonical gameplay admission flow after the TCP Proxy bridges them onto `/ws/game/**`. They still issue `LOGIN` followed by `PLAY` before gameplay commands, while the Telnet path may additionally contribute trusted `SESSION`-derived context headers from the TCP Proxy side.
- Planned Gateway drain must be surfaced by the TCP Proxy as `logout` with `gateway_restart` context when the deterministic bridge-drain signal is received. Unattributed bridge loss is surfaced immediately as `backend_unavailable`.

## Trusted TCP Proxy Bridge Admission

- Traffic from the TCP Proxy Service always targets `/ws/game/**` via `GATEWAY_WS_URL`.
- In player-facing environments, the TCP Proxy -> Gateway WebSocket hop is mTLS-authenticated.
- Spring Cloud Gateway strips spoofable tenant and game-instance headers from public ingress and forwards `X-Tenant-Id` / `X-Game-Instance-Id` only when they are derived from trusted inputs, such as `X-Proxy-Tenant-Id` / `X-Proxy-Game-Instance-Id` on the authenticated TCP Proxy -> Gateway hop.
- Gateway emits the gateway-owned discriminator `X-Firemud-Connection-Mode` on successful gameplay admission:
  - `first_party_web` for connect-token-validated first-party WebSocket handshakes.
  - `trusted_tcp_proxy` for authenticated TCP Proxy bridge handshakes.
- Game Session must rely on this positive marker rather than inferring path type from header absence.

## WebSocket Close and Handshake Classification

- Non-`101` `/ws/game/**` handshake failures must emit the canonical bounded error class through the gateway response/logging path so clients and operators can distinguish retry classes such as `CONNECT_TOKEN_REJECTED`, `POLICY_DENY`, `BACKEND_UNAVAILABLE`, and `REPLAY_CHECK_UNAVAILABLE`.
- A concrete wire-level example remains the `X-Firemud-Handshake-Error-Class` response header paired with matching structured-log fields; downstream clients and operator tooling may rely on that bounded header/value surface rather than parsing free-form text.
- The gateway observability contract requires `gateway.websocket.closes{reason,subreason}`, `gateway.websocket.handshake.rejected`, and `gateway.websocket.slow_client_closes`.
- Close and handshake classifications must remain bounded and stable so reconnect logic, dashboards, and alerting do not depend on free-form strings.

## Filter Chain and Admission Behavior

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` requires an `Authorization` header on protected admin routes and forwards the JWT unmodified. Spring Cloud Gateway never parses or validates JWTs; validation occurs entirely in the consuming service.
- Rate limiting behavior, including keying strategy and the division of responsibility with the TCP Proxy Service and Game Session Service, follows [Rate Limiting & Abuse Protection](../../system-architecture-gateway.md#rate-limiting--abuse-protection).
- WebSocket upgrades are proxied using Spring Cloud Gateway’s built-in WebSocket support.
- `ConnectionMetricsFilter` records active connections for observability.
- Tracing for WebSocket sessions captures connection-level metadata such as route ID, tenant, session identifiers, and timing without logging full text payloads by default.
- Full request and response payload tracing for WebSocket sessions is an opt-in diagnostic mode only and must be tightly scoped with sampling and redaction aligned to [Logging & Monitoring](../../system-architecture-logging-monitoring.md).

## Key Routes

- `/ws/game/**` -> Game Session Service.
- `/api/admin/**` -> Logging & Admin Service.
- `/api/design/**` -> Game Design Service.
- `/api/account/**` -> Account Service.
- `/api/session/**` -> Game Session Service control-plane and admin APIs.
- `/api/social/**` -> Social Groups Service.

The canonical external allowlist stops there. World Management, Entity Management, Game Logic, and Automation & Scripting do not expose direct Gateway-routed external APIs in the base architecture unless a dedicated design update extends the allowlist.

## Tenant and Header Trust Model

- Spring Cloud Gateway remains tenant-agnostic. It forwards tenant-related headers only after applying the gateway’s header trust and canonicalization rules.
- This trust boundary is implemented by `HeaderTrustFilter` and configured via `firemud.gateway.header-trust.*`; changes to trusted header sources or canonicalization behavior must update that service-local control surface in the same change.
- Public clients must not be able to inject trusted `X-Tenant-Id`, `X-Game-Instance-Id`, or proxy-owned correlation headers through the gateway boundary.
- Tenant isolation, quotas, and gameplay authorization decisions remain the responsibility of downstream domain services as described in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
