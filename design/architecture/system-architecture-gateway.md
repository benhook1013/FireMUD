# Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

## Gateway Pattern

**Spring Cloud Gateway** serves as the **single HTTP(S) and WebSocket entry point** into the FireMUD system for all **external client traffic** that speaks HTTP or WebSocket. Traditional Telnet/TCP clients enter via the dedicated TCP Proxy Service as described in [Protocol Bridging](./system-architecture-protocol-bridging.md); together, Spring Cloud Gateway (for HTTP and WebSocket) and the TCP Proxy Service (for Telnet/TCP) form the public edge of the platform. The behaviour of this edge – including ordering guarantees, backpressure, and reconnection semantics for gameplay command streams – is defined canonically in [Protocol Bridging](./system-architecture-protocol-bridging.md); this document focuses on gateway responsibilities and defers to that design for detailed client-path invariants.

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- For admin APIs the Gateway forwards JWTs to backend services without validating them. Player login and session binding are processed by the **Game Session Service**; see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the detailed flow and the [Tenant Authorization Contract](./system-architecture-authentication.md#tenant-authorization-contract) for how downstream services enforce tenant access.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no cookie-based session affinity is required. The gateway does not own gameplay lease state or shard-mapping state (those are owned by Game Session and stored in Coordination Redis). `/ws/game/**` routes to a stable Game Session service surface; lease ownership and any shard coordination remain internal to the Game Session layer per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`.
- Auto‑scaling policies handle high concurrency
  - Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; Spring Cloud Gateway itself does not hold session state between reconnects
  - Spring Cloud Gateway restarts **disconnect WebSocket clients**; browsers and other WebSocket tools must open a fresh WebSocket connection, issue `LOGIN`, and re-bind gameplay scope with `PLAY`. Once reconnected, the gateway resumes routing and the Game Session Service uses Redis-backed state to decide whether to resume or start fresh as described in [Reconnection Strategy](./system-architecture-reconnection.md#resume-vs-reload-scenarios). The gateway does not maintain hidden, long‑lived WebSocket tunnels across its own restarts or attempt to replay in‑flight messages; edge delivery remains per‑connection FIFO and at‑most‑once as defined in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
  - The Gateway and TCP Proxy Service run in the **network DMZ** and are the only ingress points for clients. NetworkPolicies restrict direct access to internal services. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design) for details.

> **Important:**
> Spring Cloud Gateway is responsible for routing **only external client requests**.
> **Internal microservice-to-microservice communication does not pass through the Gateway**.
> Microservices use Kubernetes native service discovery and DNS for direct communication.
> Internal synchronous RPCs use **gRPC**; asynchronous contracts (for example audit/saga events and lifecycle signals) use dedicated event flows documented in [System Architecture Overview](./system-architecture-overview.md#asynchronous-and-event-flows).
> See [System Architecture Overview](./system-architecture-overview.md) and [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the complete login and gRPC flow.

- Static URIs configured in the `dev` profile within `application.yml`
  (used by Docker Compose)
- Kubernetes DNS-based service names configured in the `prod` profile of
  `application.yml` (used in production)
- Initial routes are loaded on startup from `routes-dev.yml` or `routes-prod.yml` via `spring.config.import`.
- Initial route targets are loaded on startup, but operators can override them using environment variables prefixed `FIREMUD_SERVICES_`, matching the `ServiceEndpointsProperties` approach used by other microservices. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#service-discovery).
- Dynamic route management APIs (REST and gRPC) can add or remove routes at runtime as **ephemeral overrides** layered on top of the baseline configuration; config files remain the canonical source of truth for route definitions.

For the Telnet-to-WebSocket bridge – including the `GATEWAY_WS_URL` contract, Proxy → Gateway mutual TLS, and how gameplay traffic is normalized through `/ws/game/**` – treat [Protocol Bridging](./system-architecture-protocol-bridging.md) as the **canonical specification**. This document summarizes the gateway’s routing and configuration responsibilities and defers to the protocol-bridging design for detailed edge semantics.

### Authentication Responsibilities

- Spring Cloud Gateway never parses or validates JWTs. It only enforces the presence of an `Authorization` header on selected admin routes and forwards tokens unchanged.
- `/ws/game/**` must not require an `Authorization` header at the HTTP handshake layer. Gameplay authentication and tenant selection are performed inside the Game Session Service via `LOGIN` and `PLAY` after the WebSocket is established (see `system-architecture-authentication.md`).

### Header Trust Model

Spring Cloud Gateway acts as the canonicalizer for client identity and session/tenant headers. It defines a strict trust boundary between **public ingress**, the **Telnet TCP Proxy**, and **internal services**:

- Headers that may carry client or session identity (`X-Client-IP`, `X-Game-Instance-Id`, `X-Tenant-Id`, and all `X-Proxy-*` headers) are **never trusted directly from public clients**. The gateway strips any such headers that arrive from the external load balancer or browser/WebSocket clients before routing to backend services.
- For HTTP and WebSocket clients, the gateway derives `X-Client-IP` from a small, environment‑specific set of load-balancer headers (for example, `X-Forwarded-For` and `X-Real-IP`) plus the immediate peer address. The exact header list and precedence rules are defined in the [Security Architecture](./system-architecture-security.md#network-security--boundary-design), but the invariant is that downstream services treat only the gateway-produced `X-Client-IP` as authoritative.
- For Telnet traffic, the TCP Proxy Service supplies `X-Proxy-Client-IP`, `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` on the mTLS-authenticated WebSocket hop. After verifying the TCP Proxy client certificate on the internal WebSocket mTLS listener, the gateway:
  - Derives canonical `X-Client-IP` from `X-Proxy-Client-IP` (and any PROXY-provided metadata) and overwrites any existing `X-Client-IP`.
  - Promotes `X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id` to canonical `X-Game-Instance-Id` and `X-Tenant-Id` where appropriate for gameplay routes.
  - Preserves `X-Proxy-Connection-Id` as an opaque correlation identifier for disconnect events and observability.
- Downstream services must treat `X-Client-IP`, `X-Game-Instance-Id`, and `X-Tenant-Id` as **gateway-owned** headers. Services must ignore or overwrite any attempts by callers to spoof these values via gRPC metadata or additional HTTP headers and should rely on their own Redis/session keys for authoritative identity as described in [Authentication & Authorization](./system-architecture-authentication.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md).
- All JWT validation and authorization logic lives in downstream admin and meta services (such as the Logging & Admin Service and Account Service), which must treat Spring Cloud Gateway as a dumb proxy and may not assume it has performed any authentication checks.

---

## Header Trust Model Details

Spring Cloud Gateway is the **canonicalization point** for any client-identity and session-hint headers. Downstream services (including the Game Session Service) treat these headers as meaningful only because **the gateway produced them after applying trust rules**, not because an upstream client provided them.

### Upstream Inputs (`X-Proxy-*`)

These headers are treated as **untrusted inputs** unless the gateway has authenticated the upstream hop as the TCP Proxy Service:

- `X-Proxy-Client-IP` – the Telnet client IP address as observed by the TCP Proxy Service (ideally recovered via PROXY protocol from the Telnet edge proxy in Kubernetes SNAT scenarios).
- `X-Proxy-Connection-Id` – server-generated identifier for the Telnet socket, used to correlate `NotifyDisconnect` events with authenticated sessions.
- `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` – advisory context captured from the optional Telnet `SESSION <gameInstanceId> <tenantId>` envelope.

### Public Ingress Strip/Drop Rules

For any connection that arrives from the public player/admin ingress, Spring Cloud Gateway **strips** all spoofable client/session headers before routing:

- `X-Client-IP`
- `X-Game-Instance-Id`, `X-Tenant-Id`
- `X-Proxy-Client-IP`, `X-Proxy-Connection-Id`, `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`
- `X-Firemud-Connect-Context`, `X-Firemud-Connection-Mode`, and any other `X-Firemud-*` gameplay-admission headers

### TCP Proxy → Gateway Authentication

The TCP Proxy → Gateway hop uses **mutual TLS (mTLS)** by connecting to a dedicated **internal-only** Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). Spring Cloud Gateway treats the upstream hop as authenticated as the TCP Proxy Service only when:

- The presented client certificate chains to the cluster trust root (cert-manager under ClusterIssuer `firemud-ca-issuer`), and
- The certificate contains an expected SAN identity for the TCP Proxy Service.

If either check fails, the gateway rejects the WebSocket handshake and does not promote any `X-Proxy-*` inputs.

Gateway config enforces this trust boundary by allowlisting the expected TCP Proxy identity from the mTLS peer certificate. The canonical production model is:

- **Canonical (prod):** allowlist the TCP Proxy by **URI SAN** (SPIFFE-style identity), configured via `firemud.gateway.header-trust.tcp-proxy.trusted-client-cert-uri-sans` (for example `spiffe://firemud/ns/<namespace>/sa/tcp-proxy-service`).
- **Transitional (migration only):** allowlist by **DNS SAN** when URI SANs are not yet consistently issued, configured via `firemud.gateway.header-trust.tcp-proxy.trusted-client-cert-dns-sans` (for example `tcp-proxy-service.<namespace>.svc.cluster.local`).
- **Break-glass (incident only):** pin by leaf certificate **SHA-256 fingerprint** when SAN-based identity is unavailable or compromised, configured via `firemud.gateway.header-trust.tcp-proxy.trusted-client-cert-fingerprints-sha256`. This is intentionally operationally expensive and should not be treated as the steady state because it makes rotation and multi-environment deployments harder.

This policy is normative per `design/architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md`.

Until mTLS is fully deployed for the TCP Proxy → Gateway hop, treat any non-mTLS acceptance of `X-Proxy-*` headers as a **temporary dev-only stopgap**, protected by strict internal-only network exposure and NetworkPolicies. Do not rely on “internal network” alone for player-facing environments.

### Gateway Output Rules (Downstream-Trusted)

After applying strip/authentication rules, the gateway sets or forwards the downstream-facing headers:

- `X-Client-IP` – canonical client IP address:
  - If the upstream hop is authenticated as the TCP Proxy Service and `X-Proxy-Client-IP` is present, set `X-Client-IP` from `X-Proxy-Client-IP`.
  - Otherwise derive `X-Client-IP` from the trusted load balancer forwarded headers (for example `X-Forwarded-For`) using the gateway’s configured trusted-proxy rules.
- `X-Proxy-Connection-Id` – forwarded only when the upstream hop is authenticated as the TCP Proxy Service so downstream services can correlate lifecycle signals.
- `X-Game-Instance-Id` / `X-Tenant-Id` – forwarded only when the upstream hop is authenticated as the TCP Proxy Service and the corresponding `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` inputs were provided. These remain advisory admission hints; the Game Session Service validates any game-instance/tenant claims against Redis, entitlements, and authenticated session state.
- `X-Firemud-Connect-Context` – for first-party `/ws/game/**` handshakes that pass connect-token validation, gateway emits a short-lived signed context payload containing verified connect scope (`accountId`, `tenantId`, `gameInstanceId`, `connectTokenJti`, `verifiedAt`, `expiresAt`, `gatewayRequestId`). Game Session must validate signature and expiry before using this context; replay protection for `connectTokenJti` remains Gateway-owned and is not re-implemented as a second authority in Game Session.
- `X-Firemud-Connection-Mode` – gateway-owned marker identifying the gameplay admission path. Supported values are `first_party_web` and `trusted_tcp_proxy`. Game Session must treat this header as meaningful only when produced by the gateway after trust/canonicalization filters run, and must reject `/ws/game/**` admissions that present neither supported mode.
- `X-Session-Id` is not part of the canonical header contract and must not be emitted or consumed for gameplay/session binding decisions.

Gateway must overwrite any inbound values for these gateway-owned `X-Firemud-*` gameplay-admission headers; they are never forwarded from external callers verbatim and are meaningful downstream only when re-issued by the gateway after successful handshake validation.
Game Session must treat `X-Proxy-Connection-Id` as authoritative only when `X-Firemud-Connection-Mode=trusted_tcp_proxy`. On all other gameplay paths, gateway must drop or overwrite `X-Proxy-Connection-Id`, and downstream services must ignore it if present.

`X-Game-Instance-Id` and `X-Tenant-Id` are not authentication material and must never be treated as reconnect tokens or proof of session ownership. They exist to carry the TCP Proxy Service’s optional Telnet `SESSION <gameInstanceId> <tenantId>` envelope context into the gameplay WebSocket handshake for advanced tools:

- `X-Game-Instance-Id` is a hint for the desired game instance (`gameInstanceId`), not a gameplay “player session” identifier.
- `X-Tenant-Id` is a hint for the desired tenant and must be validated against the authenticated account’s allowed tenants and entitlements during the canonical `LOGIN` + lobby selection (`PLAY`) flow.
- Any mismatch between these hints and Redis-backed session bindings or authenticated claims must result in the hints being ignored (or the enter-game request being rejected) and should be logged as a suspicious attach attempt.

After `PLAY` succeeds, Redis-backed session binding is authoritative for tenant/gameplay scope. Header hints (`X-Game-Instance-Id`, `X-Tenant-Id`) remain admission-only context and must not override already-bound session scope.

For canonical Telnet `SESSION` parsing and forwarding rules, see the TCP Proxy Service design’s **Telnet Session Envelope & Event Metrics** section. For canonical tenant-selection behavior, see [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay).

---

## WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

At a configuration level, Spring Cloud Gateway defines WebSocket routes in `application.yml` (and profile-specific route files) and applies filters (such as rate limiting and retries) before forwarding to backend services. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for the authoritative description of route configuration, service discovery overrides, and gateway-related environment variables. For gameplay login and session semantics, this document defers to the canonical flow in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow); this doc focuses on transport-level responsibilities only.

### Gameplay WebSocket Route

- **Canonical route path** – `/ws/game/**` is the canonical gameplay WebSocket entry point for first-party gameplay clients and Telnet clients bridged via the TCP Proxy Service.
- **Single route policy** – `/ws/game/**` is the only supported gameplay WebSocket entry point. Gameplay admission through alternate legacy routes is not supported.
- **First-party admission marker** – successful first-party `/ws/game/**` handshakes must emit `X-Firemud-Connection-Mode=first_party_web` as defined in [Gateway Output Rules (Downstream-Trusted)](#gateway-output-rules-downstream-trusted), so downstream services can distinguish this path using the same positive discriminator model as the trusted TCP Proxy path.
- **Telnet bridge usage** – The TCP Proxy Service connects to Spring Cloud Gateway using the `GATEWAY_WS_URL` environment variable. The proxy’s `dev` profile may fall back to `ws://spring-cloud-gateway:8080/ws/game` when unset, but shared and player-facing environments must set it explicitly to a `wss://.../ws/game` URL that targets the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mTLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway). Exact `SESSION` envelope semantics and header propagation rules are defined in the TCP Proxy design’s **Telnet Session Envelope & Event Metrics** section; this document intentionally summarizes only the routing side.
- **Required headers** – Spring Cloud Gateway preserves or sets:
  - `X-Client-IP` with the originating client address. For web clients this is derived from the external load balancer’s forwarded headers. For Telnet clients this is derived by the gateway from `X-Proxy-Client-IP` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` on the TCP Proxy → Gateway hop when advanced Telnet clients provide a `SESSION` envelope or when the proxy needs disconnect correlation. The gateway strips these from public ingress and only forwards canonical `X-Game-Instance-Id` / `X-Tenant-Id` and `X-Proxy-Connection-Id` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - Standard correlation and trace headers defined in the logging/observability guidelines.
- **TLS expectations**
  - External clients connect over `wss://` to the public load balancer, which forwards to Spring Cloud Gateway as described in [Security Architecture](./system-architecture-security.md#tls-termination--internal-encryption).
  - The TCP Proxy Service connects to `/ws/game/**` using `wss://` with mutual TLS to the Gateway’s internal-only WebSocket mTLS listener in production; plain `ws://` is reserved for local/dev-only flows.
- **Admission discriminator** – `X-Firemud-Connection-Mode` is the sole positive discriminator for gameplay admission path. Its supported values and downstream meaning are defined in [Gateway Output Rules (Downstream-Trusted)](#gateway-output-rules-downstream-trusted). Game Session must accept the trusted-proxy bypass only when this gateway-issued header is `trusted_tcp_proxy`; it must not infer trusted-proxy admission from the absence of `X-Firemud-Connect-Context` or from raw `X-Proxy-*` headers.

### Gameplay Sharding (Routing Boundary)

Gateway is the gameplay admission surface for `/ws/game/**`, but it is not the owner of shard leases, shard mapping, or gameplay sharding policy. `/ws/game/**` routes to a stable Game Session service endpoint, and any sharding/lease moves are handled within the Game Session layer and its coordination mechanisms.

The edge contract does not define a distinct client-visible “shard handoff” close category. If a future architecture introduces explicit lease-aware routing or handoff semantics at the edge, it must be defined as a dedicated design update and integrated into the close-code and reconnection contracts described in this document and [Protocol Bridging](./system-architecture-protocol-bridging.md).

While shard routing stays internal to Game Session, the internal session front-end → lease-owner forwarding leg inherits the same user-visible safety constraints as the edge session:

- it must preserve per-connection FIFO for gameplay commands,
- it must apply bounded buffering/backpressure rather than unbounded queues,
- and if that forwarding path fails in a way that makes gameplay impossible, Game Session is responsible for surfacing the failure upstream so Gateway can emit the canonical client-visible close or error.

### WebSocket Liveness and Idle Timeouts

Gameplay WebSocket connections are long-lived but not unbounded. To avoid half-open sessions and to make idle behaviour predictable:

- Spring Cloud Gateway (or the underlying WebSocket container) sends periodic WebSocket `ping` frames on `/ws/game/**` (for example every 30 seconds) while connections are idle.
- If no `pong` or other traffic is observed for a configured idle window (for example 90 seconds), the connection is closed with a clear close reason. Load balancers or CDNs in front of the gateway must be configured with idle timeouts greater than this window so they do not terminate connections more aggressively than the gateway itself.
- Web clients are not required to send their own application-level heartbeats, but they may do so; they must be prepared for the gateway to close idle or unreachable connections according to these limits and to follow the reconnection rules in [Reconnection Strategy](./system-architecture-reconnection.md).

The canonical handshake-failure classification and client backoff policy for `/ws/game/**` is defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game). This document must not introduce a conflicting retry/backoff matrix.

For gameplay WebSocket sessions, FireMUD standardises a small set of close codes and reasons so clients and operators can interpret failures consistently. Spring Cloud Gateway is the **only component that emits WebSocket close frames to external clients**; backend services (including Game Session) express their intent via upstream failures or closes, and Gateway maps those outcomes into the standard close codes below. Telnet disconnect messages on the TCP Proxy Service map directly onto the same categories as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons):

- `1000` with reason `logout` – explicit, clean shutdown (for example, user-initiated logout, takeover completion, admin‑initiated session end, or planned edge drain where no error occurred).
- `1001` with reason `idle_timeout` – idle-connection timeout where the gateway or Game Session has not observed traffic within its configured idle window.
- `1008` with reason `policy_violation` – client behaviour that violates platform policies (for example, sustained command‑rate abuse, malformed frames, repeated protocol violations, or sustained slow-client behaviour at the network edge where send buffers repeatedly overflow or time out).
- `1011` with reason `internal_error` – unexpected server‑side failures that are not attributable to the client and are not clearly a backend‑unavailable condition.
- `1013` with reason `backend_unavailable` – Spring Cloud Gateway has concluded that backend services needed for gameplay are unavailable or overloaded beyond a short tolerance window (see [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios)).

Gateway and Game Session implementations must always map platform‑initiated closures into one of these categories. Game Session indicates categories through its upstream behaviour (for example how it closes or errors its side of the connection), while Gateway is responsible for translating those signals into the client‑facing close codes/reasons above and logging the mapped category and contributing metrics (for example `gateway.websocket.closes{reason=...,subreason=...}` and `gamesession.connection.closed{reason=...}`) so operations teams can distinguish idle timeouts, policy enforcement, backend outages, and internal errors.

In addition to the bounded top-level reason taxonomy, implementations must emit a bounded close `subreason` for client policy tuning and operations correlation without creating new top-level categories. Supported values are `user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, and `none`. The `subreason` field must be present in structured logs and close metrics and must remain low-cardinality.

Wire compatibility for `subreason` is explicit:

- The WebSocket close code and top-level reason remain the only mandatory client-facing transport contract.
- `subreason` is emitted as a best-effort hint on the wire when transport/framework constraints allow it, and is always emitted in structured logs/metrics.
- Clients and services must treat missing wire `subreason` as `none` (backward-compatible default), and must not fail protocol handling when only the top-level reason is present.
- When emitted on-wire, `subreason` is encoded as a close-reason suffix in the form `;subreason=<value>` appended to the top-level reason token.
- If transport/framework limits would exceed the WebSocket close-reason payload limit, producers must keep the top-level reason and omit the `subreason` suffix (equivalent to `none`) instead of truncating mid-token.

To keep behaviour consistent and avoid double-closing sessions, ownership of these close codes is divided as follows:

- `logout` (`1000`) – Game Session owns explicit gameplay logouts and admin‑initiated session ends; Gateway may also use `1000` for graceful shutdown of its own listener when draining connections, but should not reinterpret Game Session’s logout decisions.
- `idle_timeout` (`1001`) – The layer that observes the idle condition first closes the connection: Game Session for application‑level idle (for example no gameplay traffic from a client that is otherwise reachable), or Gateway/WebSocket container for network‑level idle (no frames or pongs within the configured idle window). Other layers treat the close as a peer shutdown and do not wrap it in a second close reason.
- `policy_violation` (`1008`) – The layer that detects the violation closes with `policy_violation`. Gateway uses this for HTTP or WebSocket protocol abuse (for example frame shape violations on `/ws/game/**`) and network-level slow-client/backpressure enforcement (subreason `edge_backpressure`); Game Session uses it for gameplay/content‑level abuse (for example sustained command‑rate or scripting violations); the TCP Proxy maps Telnet‑side `policy_violation` disconnects into this category via the Telnet reason taxonomy in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).
- `internal_error` (`1011`) – Any layer that encounters an unexpected server‑side error (not clearly attributable to the client and not covered by `backend_unavailable`) closes with `internal_error` and logs the underlying failure. Other layers treat it as a generic peer failure and avoid emitting a second, conflicting close reason for the same session.
- `backend_unavailable` (`1013`) – Gateway owns closing WebSocket sessions when core gameplay backends are continuously unavailable beyond the configured grace window. Game Session surfaces its own health via metrics and health checks; Gateway uses that information plus its own upstream connectivity failures to decide when to send `1013/backend_unavailable` as described in [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios). Telnet clients see the corresponding `backend_unavailable` Telnet reason from the TCP Proxy.

#### Canonical Close Translation Matrix

Gateway is the authoritative translation point for client-visible WebSocket closes. The following mapping is canonical for `/ws/game/**`:

| Upstream/session condition | Client-visible WebSocket close | Telnet reason token |
| --- | --- | --- |
| Explicit logout / takeover completion / planned gateway drain | `1000` / `logout` | `logout` |
| Idle timeout detected by first observing layer | `1001` / `idle_timeout` | `idle_timeout` |
| Edge or gameplay policy violation | `1008` / `policy_violation` | `policy_violation` |
| Unexpected non-policy server failure | `1011` / `internal_error` | `internal_error` |
| Sustained gameplay backend unavailability or unreachable upstream | `1013` / `backend_unavailable` | `backend_unavailable` |

Precedence when multiple failures are observed in the same interval is: `policy_violation` > `backend_unavailable` > `internal_error` > `idle_timeout` > `logout`, except that explicit user/admin logout always remains `logout`.

For `1000/logout`, producers must attach the most specific supported `subreason` (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`) when known, else `none`. When a graceful Gateway drain terminates the upstream gameplay WebSocket used by the TCP Proxy bridge, the proxy must preserve this as the Telnet-side `logout` category rather than collapsing it into `backend_unavailable`; detailed Telnet translation rules live in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).

For the internal authenticated TCP Proxy bridge path, graceful drain attribution is stricter than the public-client best-effort wire rule: Gateway must emit a machine-parseable bridge-drain signal that the proxy can rely on to classify the shutdown as `logout/gateway_restart`. The canonical encoding is a WebSocket close with `1000/logout;subreason=gateway_restart` on the bridge itself.

If the bridge transport fails before that close can be sent, producers must not substitute an ad hoc alternate signal. The outcome is treated as an unattributed bridge failure on the internal authenticated TCP Proxy bridge: for already-established Telnet sessions, the proxy closes immediately with `backend_unavailable`, while external WebSocket gameplay sessions continue to use the normal Gateway `unreachable` / recovery-window rules governed by `firemud.gateway.backendUnavailableGraceMs`. Both sides must emit a bounded structured field such as `bridge_shutdown_class=planned_drain|unattributed_failure` in logs/metrics so operators can distinguish deliberate drain from outage and can detect when a planned drain degraded into an unattributed transport loss.

### Backend-Unavailable Grace Window

Spring Cloud Gateway applies a small grace window before closing WebSocket sessions due to sustained backend outages so that brief flaps do not cause unnecessary reconnects:

- Gateway uses two explicit backend health states for gameplay routes:
  - `degraded_but_reachable` – upstream is connected and can still return bounded explicit gameplay/protocol errors for requests.
  - `unreachable` – upstream cannot be established or maintained (`UNAVAILABLE`, connect failures, handshake failures, or equivalent all-failed route state).
- The `firemud.gateway.backendUnavailableGraceMs` configuration property applies only to the `unreachable` state and controls how long Gateway tolerates continuous unreachability before closing affected sessions with `1013/backend_unavailable`.
- `firemud.gateway.backendUnavailableRecoverySuccessCount` defines recovery hysteresis for exiting `unreachable`. Gateway returns to `degraded_but_reachable` or healthy routing only after at least this many consecutive successful upstream connect/forward attempts (default `3`).
- Gateway enters `unreachable` when the `/ws/game/**` upstream cannot be established or maintained and route checks are continuously failing.
- While in `degraded_but_reachable`, sessions stay open and command outcomes are explicit backend errors. “Bounded explicit response” here means within the platform’s normal command/request timeout budget for the affected path; this state must not be used for stalled sessions that cannot produce a timely explicit gameplay/protocol response. Those sessions must transition to `unreachable`. While in `unreachable`, the backend unavailable timer runs and handshake/sending behavior follows the rules below.
- When the backend is currently unavailable, Gateway rejects new gameplay-route connections (`/ws/game/**`) with HTTP `503` so clients can apply the reconnection and backoff rules defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game). The grace window is an established-session behaviour; it does not create “maybe works” handshake outcomes.
- When the backend has been continuously unavailable beyond `firemud.gateway.backendUnavailableGraceMs` without any successful calls or healthy checks, Gateway must close affected gameplay WebSocket sessions with `1013/backend_unavailable`.
- Load balancers or CDNs in front of Gateway should be configured with idle and failure timeouts that do not undercut this grace window; otherwise, they may terminate connections before Gateway can emit the canonical `backend_unavailable` signal.
- Gateway startup must fail fast when `firemud.gateway.backendUnavailableGraceMs <= 0` or `firemud.gateway.backendUnavailableRecoverySuccessCount <= 0`.
- **Established-session input handling while backend is unavailable** – Gateway is a WebSocket proxy and does not generate gameplay-protocol error frames when the upstream Game Session hop is down. To avoid silently discarding gameplay commands while a connection appears healthy:
  - If an established `/ws/game/**` session is in `unreachable` state and the client attempts to send a gameplay message while upstream is unavailable, Gateway closes that session immediately with `1013/backend_unavailable` (and logs/records metrics for the close reason).
  - If the backend unavailable timer exceeds `firemud.gateway.backendUnavailableGraceMs`, Gateway closes remaining affected sessions with `1013/backend_unavailable` even if they are idle, so clients receive a clear canonical signal instead of sitting on half-open connections indefinitely.

### Gateway Restart Semantics

Gateway restarts can be planned (for example, rolling deploys) or unplanned (for example, crashes or infrastructure failures). To keep client behaviour and operational signals consistent:

- **Planned, graceful restarts**
  - During a controlled drain or rolling restart, Gateway closes existing gameplay WebSocket sessions with code `1000`, reason `logout`, and subreason `gateway_restart`. Clients should treat this as a clean, expected shutdown and may reconnect with their normal backoff policy.
- **Unplanned internal failures at Gateway**
  - When Gateway encounters an unexpected internal error that forces it to drop gameplay WebSocket sessions independently of backend health (for example, container crashes, unrecoverable configuration errors), it should close affected sessions with code `1011` and reason `internal_error` when a close frame can be emitted before teardown. Clients should treat this like other internal errors and apply the standard exponential backoff rules described in [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour).
  - Some hard failures (for example process crash, node restart, abrupt transport reset) can terminate the TCP/WebSocket transport before any close frame is emitted. Clients must treat missing close code/reason as abnormal transport loss and apply the `internal_error` retry policy.
- **Backend-unavailable vs restart**
  - `1013/backend_unavailable` remains reserved for cases where Gateway’s backend unavailable timer exceeds `firemud.gateway.backendUnavailableGraceMs` because core gameplay backends are failing or unreachable. Gateway must not emit `1013/backend_unavailable` solely because of its own process restarts; those conditions are covered by the planned/unplanned restart semantics above.

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

- JWTs presented on admin or REST endpoints are validated by the consuming service. Gameplay protocol clients do not provide JWTs; all non-proxy `/ws/game/**` WebSocket clients must provide a short-lived connect token for handshake-time edge policy as described below.
- Cross-cutting filters (e.g., rate limiting, logging, CORS)

> **Redis topology guidance:** Coordination Redis and Cache/Rate‑Limit Redis are
> always deployed as **separate Redis instances** (for example, two containers
> on a single dev machine or distinct pods/clusters in Kubernetes). Sharing a
> single Redis instance for both roles is allowed only for explicitly documented, truly ephemeral CI/preview topologies and must not be used for non-ephemeral or player-facing environments.
> For any player-facing environment, configure the Gateway to use the dedicated
> **Cache/Rate‑Limit Redis deployment** so rate limiting and cache activity cannot
> interfere with tick/session coordination. See
> [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection)
> and [Redis Architecture](./system-architecture-redis.md#redis-profiles) for
> reference profiles.

- Service isolation through route-based access control
- Easy expansion of routes for new microservices
- TLS termination and mTLS between services are described in [Security Architecture](./system-architecture-security.md).

## Rate Limiting & Abuse Protection

Spring Cloud Gateway and the TCP Proxy Service share responsibility for protecting the platform from abusive traffic:

### Tenant-Aware Edge Connect Token (Gameplay Handshake)

Tenant-aware edge limiting on `/ws/game/**` depends on a short-lived server-minted connect token presented at WebSocket handshake time.
This section is the canonical source of truth for connect-token enforcement and handshake outcomes.

- **Issuer and audience**
  - Issued only by the account/authentication control-plane after account authentication and tenant entitlement checks.
  - Issuance is performed via the control-plane connect-token API (for example `POST /auth/connect-token`); gateway must not mint connect tokens.
  - Audience is the gateway gameplay route (`/ws/game/**`) and must not be accepted on unrelated routes.
  - Gateway validates signatures against the issuer's published verification key set with explicit `kid` selection and overlap handling during rotation.
- **Transport location**
  - Sent in a dedicated handshake header (`X-Firemud-Connect-Token`) on `/ws/game/**`.
  - Gateway must not accept connect tokens from query parameters in player-facing environments.
- **Required claims**
  - `accountId`
  - `tenantId`
  - `gameInstanceId`
  - `exp` (absolute expiration)
  - `jti` (single-use nonce for replay defense)
- **Lifetime and replay**
  - Token TTL must be short (target: <= 30s).
  - Gateway must reject expired tokens and tokens whose `jti` has already been observed within the replay window.
  - Replay cache entries must expire automatically at `exp + small_skew`.
  - Replay cache ownership is Gateway-only; downstream services do not participate in connect-token replay checks.
  - Replay checks must be backed by shared Cache/Rate-Limit Redis (not per-pod memory) so `jti` replay decisions are consistent across horizontally scaled gateway pods.
  - Replay cache keys use `gateway:connect-token:replay:<jti>` and bounded cardinality with deterministic eviction (`oldest-expiry-first`).
  - On replay-cache capacity pressure, gateway must emit overload metrics and continue fail-closed behavior for uncertain replay outcomes.
  - Replay-cache outage behavior:
    - Player-facing environments (`/ws/game/**` in `enforce` mode): fail closed (`503` for replay-check unavailable).
    - Non-player-facing dev/preview environments: fail-open is allowed only when explicitly configured for local iteration and must emit drift metrics.
- **Validation outcomes**
  - Invalid/expired/replayed token is rejected with HTTP `403`.
  - Missing token is rejected with HTTP `403` for non-proxy gameplay clients.
  - Rate-limit exhaustion remains HTTP `429`.
  - Backend-unavailable remains HTTP `503`.
- **Trusted TCP Proxy exception**
  - Connect-token checks are bypassed only for `/ws/game/**` handshakes that authenticate as the TCP Proxy Service on the internal mTLS listener and satisfy header-trust allowlists.
  - If the proxy identity or trust checks fail, the handshake is rejected with HTTP `403` and classified as `POLICY_DENY`.

This token is an edge admission/rate-limiting hint only. It does not replace `LOGIN` + lobby selection + `PLAY` and does not grant gameplay authorization by itself.

- **Verified context handoff (Gateway -> Game Session)**
  - After successful connect-token validation, Gateway must emit a signed, short-lived connect context (`X-Firemud-Connect-Context`) for the upgraded `/ws/game/**` connection.
  - Context payload must include at least: `accountId`, `tenantId`, `gameInstanceId`, `connectTokenJti`, `verifiedAt`, `expiresAt`, `gatewayRequestId`.
  - Game Session validates signature (`kid` aware) and expiry bounds before using scope for `CONNECT_SCOPE_MISMATCH` enforcement. Replay protection for `connectTokenJti` is performed only at Gateway handshake time.
  - Missing/invalid/expired context on first-party handshakes that required connect-token validation must be rejected by Game Session admission with canonical error `CONNECT_CONTEXT_INVALID` before `PLAY` (no scope fallback to raw headers).
  - Gateway must also emit `X-Firemud-Connection-Mode: first_party_web` for these handshakes.
  - Key ownership and rotation contract:
    - Gateway signs context with a dedicated asymmetric key set identified by stable issuer and `kid`.
    - Game Session trusts keys from a gateway verification-key source and must support overlap during rotation (old and new `kid` concurrently valid for a bounded window).
    - Verification-key fetch/cache policy must be explicit (bounded cache TTL, refresh-on-miss for unknown `kid`, fail-closed if no valid key set is available).
    - Metrics/logs must distinguish signer/verification failures with bounded reasons (for example `unknown_kid`, `signature_invalid`, `context_expired`, `verification_keys_unavailable`) so incidents are triageable without high-cardinality labels.

- **Trusted proxy handoff (Gateway -> Game Session)**
  - For authenticated TCP Proxy bridge handshakes, Gateway must emit `X-Firemud-Connection-Mode: trusted_tcp_proxy`.
  - Game Session must treat this mode as the only valid bypass of the first-party connect-context requirement.
  - Trusted proxy sessions must not rely on the absence of `X-Firemud-Connect-Context` as an implicit discriminator; the positive `trusted_tcp_proxy` mode marker is required so admission remains fail-closed and unambiguous.

- **Keying strategy**
  - Gateway rate limiting is primarily **per-client IP** with optional route-level differentiation. The default `RequestRateLimiter` configuration uses the Cache/Rate‑Limit Redis deployment and derives keys from the client IP (as seen by the gateway after load balancer and TCP Proxy headers) and route ID, keeping key cardinality modest while still following the canonical `ratelimit:<tenantId>:<bucket>:<timeWindow>` key pattern from [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md#rate-limit-bucket-design). For the gateway itself, `tenantId` is a synthetic, edge-scope identifier (for example `gateway-edge`), and `bucket` incorporates the client IP and route identifier via a stable hash.
  - Filter ordering is normative: gateway header trust and client-IP canonicalization must run before gameplay admission checks, handshake classification, and any rate-limit key derivation. For trusted TCP Proxy bridge requests, the rate-limit key must use the canonicalized client IP derived from authenticated `X-Proxy-Client-IP`, not the proxy pod/node source IP. If authenticated client-IP promotion fails, the handshake must fail closed as `POLICY_DENY` rather than falling back to the proxy hop address in player-facing environments.
  - Tenant-aware edge rate limiting for gameplay uses the connect-token contract above. `/ws/game/**` rejects gameplay handshakes without a connect token (`403`) unless the request is the authenticated TCP Proxy bridge exception described above.
  - Game Session Service enforces **per-session and per-command** limits (for example, commands per tick region) using Redis coordination keys. See [Reconnection Strategy](./system-architecture-reconnection.md) and [Redis Architecture](./system-architecture-redis.md) for session/tick-level controls.
- **WebSocket vs HTTP semantics**
  - Spring Cloud Gateway’s Redis-backed `RequestRateLimiter` is applied to **connection establishment and discrete HTTP requests**, not to every WebSocket frame. This prevents Telnet and WebSocket gameplay traffic from being throttled as if each frame were a separate HTTP call.
  - Once a WebSocket connection is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level rate limiting; downstream services (especially Game Session Service) enforce per-session and per-command safety.
- **Gameplay WebSocket handshake errors**
  - HTTP `429` responses from gameplay routes indicate edge rate/connection policy boundaries.
  - HTTP `503` responses from gameplay routes may represent backend unavailable **or** replay-check infrastructure unavailable fail-closed outcomes.
  - HTTP `403` responses indicate handshake denial by policy or trust boundaries (for example internal-only listener, mTLS/client-identity mismatch, explicit route policy deny, or invalid connect token when token enforcement is enabled).
  - HTTP `401` is not part of the normal gameplay-route handshake taxonomy. If observed, treat as policy drift/misconfiguration and investigate.
  - Client retry/backoff handling is canonical in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - Gateway must emit a machine-readable handshake error class for all non-101 upgrades (for example response header `X-Firemud-Handshake-Error-Class` and mirrored structured log field) using the bounded set:
    - `POLICY_PRESSURE`
    - `BACKEND_UNAVAILABLE`
    - `REPLAY_CHECK_UNAVAILABLE`
    - `CONNECT_TOKEN_REJECTED`
    - `POLICY_DENY`
    - `PROTOCOL_MISMATCH`
    - `INTERNAL_ERROR`
  - `CONNECT_TOKEN_REJECTED` is mandatory for missing, expired, replayed, malformed, or signature-invalid gameplay connect tokens. `POLICY_DENY` is reserved for trust-boundary and route-policy denials after token parsing is no longer the deciding factor.
  - Reconnection/client policy must key on handshake error class first and HTTP status second.
- **Edge vs core responsibilities**
  - The **TCP Proxy Service** enforces **connection-level and per-socket safety** for Telnet clients: idle timeouts, per-IP connection caps, buffer depth limits, and basic abuse heuristics. It relies on Spring Cloud Gateway and Game Session Service for cross-tenant and content-aware rate limiting.
  - **Spring Cloud Gateway** enforces **request- and connection-creation limits** using the Cache/Rate‑Limit Redis instance configured via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`, protecting backend services from floods of new connections or HTTP calls.
  - The **Game Session Service** applies **fine-grained gameplay limits** (per-session command rates, login attempt throttling, and region-level protections) so in-game abuse is handled close to business logic.

This layered model avoids over-counting Telnet and WebSocket frames while still protecting the platform: the gateway guards connection churn and HTTP floods, the TCP Proxy Service governs raw Telnet behavior, and the Game Session Service enforces gameplay-specific policies.

## Multi-Tenancy at the Gateway

Spring Cloud Gateway is not the owner of tenant isolation policy or authorization decisions: backend services derive, validate, and enforce tenant access as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md) and [Authentication & Authorization](./system-architecture-authentication.md).

If gameplay execution is sharded across multiple Game Session instances, that sharding boundary is owned by the Game Session layer and its coordination mechanisms. The gateway remains a protocol edge and must not introduce an independent shard-routing plane unless explicitly designed and implemented as part of a dedicated sharding/routing architecture update.

- Tenant identity (`tenantId`) is derived and enforced by backend services as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md), not by Spring Cloud Gateway.
- Gameplay flows may include tenant markers such as:
  - `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` inputs on the TCP Proxy → Gateway hop when advanced Telnet clients send a `SESSION` envelope. The gateway strips these from public ingress and only forwards canonical `X-Game-Instance-Id` / `X-Tenant-Id` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - Session and tenant context inferred by the Game Session Service from the `LOGIN` flow and Redis session keys.
- Spring Cloud Gateway preserves these headers and forwards them unchanged to backend services but does not:
  - Derive `tenantId` from hostnames or URL paths.
  - Treat forwarded tenant/session hints as trusted without validation by the owning service (for example, Game Session validating tenant/session context against Redis).
  - Apply tenant-aware authorization rules on behalf of backend services.
- All tenant isolation, quotas, and policy enforcement (for example, per-tenant session limits or resource quotas) are implemented in domain services such as the Game Session Service and Account Service, following the rules in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

## TLS Termination for Gateway

- **Browser / Web clients** – External `https://` / `wss://` connections terminate at the Internet-facing load balancer. The load balancer forwards `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ. The gateway then routes traffic to backend services using in-cluster `http://` and `ws://` targets (typically on port `8080`), while backend services communicate with each other over mTLS-protected gRPC as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway).
- **Telnet clients** – Telnet (plaintext) or Telnet-over-TLS connections terminate at the TCP Proxy Service. The proxy then connects to the canonical gameplay route `/ws/game/**` on Spring Cloud Gateway by dialing the Gateway’s internal-only WebSocket mTLS listener over `wss://` with mutual TLS. Spring Cloud Gateway forwards gameplay to the Game Session Service over the same WebSocket route (`/ws/game/**`). Detailed certificate and environment variable mappings are documented in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway) and [Protocol Bridging](./system-architecture-protocol-bridging.md#websocket-bridge-configuration).

## Management Plane Security

- Spring Cloud Gateway exposes REST and gRPC management endpoints (such as dynamic route operations and `GatewayManagementService` RPCs) **only on internal network surfaces**, not via the public player-facing ingress.
- In Kubernetes, these endpoints are reachable only from inside the cluster or a dedicated admin network segment via `ClusterIP` Services, private ingress, and `NetworkPolicy` rules; the public Service/Ingress is limited to HTTP and WebSocket data-plane traffic.
- Authentication and authorization for these management endpoints is enforced at the gateway boundary: operator tooling must connect using mutual TLS (mTLS) client certificates (issued by cert-manager under ClusterIssuer `firemud-ca-issuer`, with `clientAuth` EKU), and only trusted operator identities are permitted to invoke management operations. JWT-based admin roles apply to product/admin APIs behind the gateway, but gateway-owned management endpoints do not rely on downstream services for authorization. Implementation details and the recommended internal-only exposure model are documented in the [Spring Cloud Gateway service README](./microservices/spring-cloud-gateway/README.md#management-plane-security).

### Dynamic Route Override Lifecycle

Dynamic route management APIs exist to apply **ephemeral overrides** on top of the baseline route configuration loaded from `routes-dev.yml` / `routes-prod.yml`.

The lifecycle expectations for these overrides must be explicit so operators understand convergence and persistence:

- **Persistence:** dynamic overrides are not the canonical source of truth and must not be treated as durable configuration. A restart of all Gateway pods must revert to baseline config unless an operator re-applies the override.
- **Multi-pod convergence:** in horizontally scaled gateway deployments, an override must either (a) be stored in a shared backend (so all pods observe the same override set) or (b) be applied to every pod consistently. If neither is true, the API must be documented as “single-pod only” and must not be used in production-like environments.
- **Auditing:** every dynamic route change must emit an audit log entry (who/what changed, previous value, new value, correlation IDs) so operators can reconstruct edge behavior during incidents.
- **Safety bounds:** dynamic overrides must not allow bypassing management-plane isolation (internal-only surfaces) or weakening header trust rules. Overrides are limited to route targets/predicates/filters and must not enable new public exposure of management endpoints.
- **Player-facing fail-fast guard:** in player-facing environments, gateway startup must fail if dynamic route mutation is enabled while shared persistence, multi-pod convergence, and route-change auditing are not enabled.
  - Recommended control flags: `firemud.gateway.dynamic-routes.enabled` and `firemud.gateway.dynamic-routes.allow-player-facing`; startup should fail when both evaluate true without the required control-plane capabilities above.
- **Readiness predicates:** startup/readiness checks must expose explicit booleans `dynamic_routes.persistence_ready`, `dynamic_routes.convergence_ready`, and `dynamic_routes.audit_ready`.
  - If any predicate is false while both `firemud.gateway.dynamic-routes.enabled=true` and `firemud.gateway.dynamic-routes.allow-player-facing=true`, gateway startup must fail hard before serving traffic.
  - Gateway health must expose an aggregate `dynamic_routes_ready` state so operators can verify enforcement from health endpoints and dashboards.

If the implementation cannot meet these lifecycle rules yet, the Gateway documentation should include an “Implemented Status” note that explicitly scopes dynamic route APIs to dev/test only.

**Current decision:** until shared persistence, multi-pod convergence, and full route-change auditing are implemented, dynamic route override APIs are scoped to dev/test only and must not be used as a production control plane.

## Observability

All gateway gRPC endpoints are instrumented with the shared `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor`.
WebSocket traffic is tracked using the `ConnectionMetricsFilter`. By default, tracing for WebSocket sessions records **connection-level metadata only** (for example, route ID, tenant, session identifiers, and basic timing) without full text payloads.
Full request/response payload tracing for WebSocket sessions is treated as an **opt‑in diagnostic mode**: it is disabled in player‑facing environments and, when enabled for debugging, must use aggressive sampling and redaction as described in [Logging & Monitoring](./system-architecture-logging-monitoring.md).

Gateway must expose a small set of low-cardinality WebSocket meters so incidents can be triaged without relying on logs alone:

- `gateway.websocket.closes{reason="<reason>",subreason="<subreason>"}` – counter incremented whenever the gateway closes a client WebSocket, with `reason` drawn from the bounded close taxonomy (`logout`, `idle_timeout`, `policy_violation`, `internal_error`, `backend_unavailable`) and `subreason` drawn from the bounded subreason taxonomy (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, `none`).
- `gateway.websocket.handshake.rejected{route="<route>",status="<status>",error_class="<class>"}` – counter incremented when the gateway rejects a gameplay-route handshake (`/ws/game/**`), with bounded `status` values (`429`, `503`, `403`, `401`, `426`, and a small set of configured policy statuses) and bounded `error_class` values from the handshake taxonomy above.
- `gateway.websocket.slow_client_closes` – counter incremented when the gateway closes a WebSocket because the client is not reading fast enough (send timeout / outbound buffer pressure). This meter is a subset of `gateway.websocket.closes{reason="policy_violation"}` and exists so operators can distinguish slow-client/network backpressure from other policy enforcement without introducing high-cardinality labels.

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
`SPRING_PROFILES_ACTIVE` configure routing targets based on environment. Baseline routes are defined in `routes-dev.yml` and `routes-prod.yml`, and any `FIREMUD_SERVICES_*` environment variable overrides or dynamic route changes apply *on top* of these files rather than replacing them as the canonical source of truth.

---

## Gateway Network Surfaces

The following table summarizes the main network surfaces exposed or used by Spring Cloud Gateway. Detailed TLS and authentication requirements are documented in [Security Architecture](./system-architecture-security.md) and the Spring Cloud Gateway service design.

| Surface | Direction | Protocol(s) | Typical Port(s) | Auth / TLS Expectations |
| --- | --- | --- | --- | --- |
| Public player/admin ingress → Spring Cloud Gateway | Inbound | `HTTP(S)`, `WS(S)` | Load balancer ports (for example, `80`/`443`) | TLS terminates at the Internet-facing load balancer; gateway receives `http://` / `ws://` as described in [TLS Termination for Gateway](./system-architecture-security.md#tls-termination-for-gateway). |
| TCP Proxy Service → Spring Cloud Gateway gameplay route | Inbound (internal only) | `WS(S)` | Gateway internal mTLS port (for example, `8443`) | Mutual TLS is required on the internal-only WebSocket listener; the gateway authenticates the TCP Proxy Service by verifying the client certificate chains to the cluster CA and carries the expected SAN identity before promoting any `X-Proxy-*` inputs. The host in `GATEWAY_WS_URL` must match the gateway certificate’s SANs. |
| Spring Cloud Gateway → backend services | Outbound | `HTTP`, `WS` | `8080` (typical) | In-cluster hop; protected by NetworkPolicies and namespace boundaries. Backend services handle JWT validation/authorization as applicable; gameplay traffic remains on the `/ws/game/**` WebSocket route to the Game Session Service. |
| Spring Cloud Gateway management plane (REST/gRPC) | Inbound (internal only) | `HTTP(S)`, gRPC | `8080` (REST), `6565` (gRPC) | Exposed only on internal surfaces (`ClusterIP` / private ingress); management operations require mTLS client certificates and are authorized at the gateway boundary (not delegated to downstream services). |

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Spring Cloud Gateway Service Details](./microservices/spring-cloud-gateway/README.md)

---
